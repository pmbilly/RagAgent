package com.ragagent.sandbox.runtime;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 一个 provider 的持久会话沙箱生命周期协调（对照 Go internal/sandbox/session_lifecycle.go
 * 全文）。不含任何 provider 原生类型：一切经由 {@link SandboxSessionClient} 中立契约与
 * {@link SessionSandboxBindingStore} 权威绑定存储。
 *
 * <p>Resolve/Destroy 都在绑定的分布式生命周期锁内运行，create/recover/replace/delete
 * 由此跨进程串行化。Java 侧差异（备案）：Go 在锁内通过 ownership context（WithoutCancel）
 * 让清理拨号在调用方取消后仍能完成；Java 无法从进行中的代码段剥离调用方中断，
 * cleanup 用 best-effort（中断不主动检查，远程 client 自身超时兜底）。</p>
 */
public class RemoteSessionLifecycle {

    private static final Logger LOG = Logger.getLogger(RemoteSessionLifecycle.class.getName());

    /** 对照 SessionSandboxBindingVersion：当前持久化 schema 版本。 */
    public static final int SESSION_SANDBOX_BINDING_VERSION = 1;

    /**
     * 对照 types.SandboxConfigIDGlobalDefault（internal/types/tenant_sandbox_config_entity.go
     * L20）：部署级默认配置在 metadata/绑定里的哨兵值。
     */
    public static final String SANDBOX_CONFIG_ID_GLOBAL_DEFAULT = "-";

    // ── sandbox metadata 键（对照 session_lifecycle.go L16-26） ──────────

    public static final String REMOTE_METADATA_TENANT_ID = "weknora_tenant_id";
    public static final String REMOTE_METADATA_SESSION_ID = "weknora_session_id";
    public static final String REMOTE_METADATA_BINDING_VERSION = "weknora_binding_version";
    public static final String REMOTE_METADATA_PROVIDER = "weknora_provider";
    /** 记录沙箱由哪个配置创建；同一 provider 账户可被两个配置共享，清理必须按 config 过滤。 */
    public static final String REMOTE_METADATA_CONFIG_ID = "weknora_sandbox_config_id";

    /** 对照 NormalizeConfigID：空 config ID 映射到部署默认哨兵。 */
    public static String normalizeConfigId(String configId) {
        if (configId == null || configId.strip().isEmpty()) {
            return SANDBOX_CONFIG_ID_GLOBAL_DEFAULT;
        }
        return configId;
    }

    private final SandboxSessionClient client;
    private final SessionSandboxBindingStore bindings;
    private final SessionExistenceChecker sessionChecker;
    private final SandboxSessionClient.CreateRequest createRequest;
    private final Duration cleanupTimeout;
    private final String sandboxConfigID;
    private final java.util.function.Supplier<Instant> now;

    /** 对照 SessionExistenceChecker（L32-35）：检查租户作用域的持久会话记录。 */
    @FunctionalInterface
    public interface SessionExistenceChecker {
        boolean sessionExists(SessionSandboxBindingStore.SessionSandboxKey key);
    }

    public RemoteSessionLifecycle(
            SandboxSessionClient client,
            SessionSandboxBindingStore bindings,
            SessionExistenceChecker sessionChecker,
            SandboxSessionClient.CreateRequest createRequest,
            Duration cleanupTimeout,
            String sandboxConfigID) {
        if (client == null) {
            throw new IllegalArgumentException("remote sandbox client is required");
        }
        if (bindings == null) {
            throw new IllegalArgumentException("session sandbox binding store is required");
        }
        if (sessionChecker == null) {
            throw new IllegalArgumentException("session existence checker is required");
        }
        if (!SandboxTypes.isNamedSandboxBackendType(client.provider())) {
            throw new IllegalArgumentException(String.format(
                    "unsupported remote sandbox provider \"%s\"", client.provider()));
        }
        if (!client.capabilities().supportsReconnect()) {
            throw new IllegalArgumentException(String.format(
                    "remote sandbox provider \"%s\" does not support reconnect", client.provider()));
        }
        if (createRequest == null || createRequest.templateId() == null
                || createRequest.templateId().strip().isEmpty()) {
            throw new IllegalArgumentException("remote sandbox template ID is required");
        }
        if (cleanupTimeout == null || cleanupTimeout.isZero() || cleanupTimeout.isNegative()) {
            throw new IllegalArgumentException("remote sandbox cleanup timeout must be positive");
        }
        String cfgId = sandboxConfigID == null ? "" : sandboxConfigID.strip();
        if (cfgId.isEmpty()) {
            cfgId = SANDBOX_CONFIG_ID_GLOBAL_DEFAULT;
        }
        SandboxSessionClient.CreateRequest req = new SandboxSessionClient.CreateRequest(
                createRequest.templateId(),
                createRequest.timeout(),
                EffectiveConfigResolver.cloneMetadata(createRequest.metadata()),
                EffectiveConfigResolver.cloneMetadata(createRequest.envVars()),
                createRequest.network(),
                createRequest.volumeMounts());
        this.client = client;
        this.bindings = bindings;
        this.sessionChecker = sessionChecker;
        this.createRequest = req;
        this.cleanupTimeout = cleanupTimeout;
        this.sandboxConfigID = cfgId;
        this.now = Instant::now;
    }

    // ── Resolve / Destroy ────────────────────────────────────────────────

    /**
     * 对照 Resolve：返回 key 当前的沙箱句柄，必要时在分布式生命周期锁内创建或恢复。
     * 会话已删除抛 {@code SandboxException.Kind#SESSION_DELETED}。
     */
    public SandboxSessionClient.Handle resolve(SessionSandboxBindingStore.SessionSandboxKey key) {
        key.validate();
        SandboxSessionClient.Handle handle = bindings.withLifecycleLock(key, null,
                () -> resolveLocked(key));
        if (handle == null) {
            throw SandboxException.internal("resolve remote sandbox returned no handle");
        }
        return handle;
    }

    /** 对照 Destroy：删除绑定的远程沙箱再 compare-delete 绑定；对缺失幂等。 */
    public void destroy(SessionSandboxBindingStore.SessionSandboxKey key) {
        key.validate();
        bindings.withLifecycleLock(key, null, () -> {
            SessionSandboxBinding binding = readBinding(key);
            if (binding != null) {
                destroyBindingLocked(key, binding);
            }
            return null;
        });
    }

    private SandboxSessionClient.Handle resolveLocked(SessionSandboxBindingStore.SessionSandboxKey key) {
        SessionSandboxBinding binding = readBinding(key);

        boolean exists;
        try {
            exists = sessionChecker.sessionExists(key);
        } catch (RuntimeException err) {
            throw SandboxException.internal("check owning session: " + err.getMessage(), err);
        }
        if (!exists) {
            RuntimeException joined = null;
            if (binding != null) {
                try {
                    destroyBindingLocked(key, binding);
                } catch (RuntimeException err) {
                    joined = err;
                }
            }
            SandboxException deleted = SandboxException.sessionDeleted();
            if (joined != null) {
                deleted.initCause(joined);
            }
            throw deleted;
        }

        if (binding != null && !binding.provider.equals(client.provider())) {
            boolean deleted;
            try {
                deleted = bindings.deleteIfMatch(key, binding.provider, binding.sandboxId);
            } catch (RuntimeException err) {
                throw SandboxException.internal(
                        "delete mismatched provider binding: " + err.getMessage(), err);
            }
            if (!deleted) {
                throw SandboxException.internal(
                        "mismatched provider binding changed during replacement");
            }
            binding = null;
        }

        // stale 绑定 = 沙箱启动的镜像已被配置替换。重建等 turn 边界：新 turn 的首次
        // resolve 可销毁重建，同一 turn 的后续 resolve 保留沙箱（/workspace 草稿与
        // 进行中的 exec 在 turn 中段的安装后存活）。无 turn 租约的 resolve 立即重建。
        //
        // 重建前必须销毁：下面的恢复 pass 会收养任何带着本会话 metadata 的活沙箱，
        // 幸存的旧沙箱会被再次捡起。
        if (binding != null && binding.staleAt != null && shouldRebuildStaleBinding(key)) {
            try {
                destroyBindingLocked(key, binding);
            } catch (RuntimeException err) {
                throw SandboxException.internal(
                        "release stale sandbox binding: " + err.getMessage(), err);
            }
            binding = null;
        }
        // 即便没有 stale，turn 的首次 resolve 也要花掉 rebuildOnce，
        // 让同一 turn 里后来的安装无法再拆沙箱。
        consumeTurnRebuild(key);

        if (binding != null) {
            ConnectOutcome outcome = connectBinding(key, binding);
            if (!outcome.replace()) {
                return outcome.handle();
            }
            boolean deleted;
            try {
                deleted = bindings.deleteIfMatch(key, binding.provider, binding.sandboxId);
            } catch (RuntimeException err) {
                throw SandboxException.internal(
                        "delete terminal sandbox binding: " + err.getMessage(), err);
            }
            if (!deleted) {
                throw SandboxException.internal("terminal sandbox binding changed during replacement");
            }
        }

        Recovered recovered = recoverOwnedSandbox(key);
        if (recovered.found()) {
            return recovered.handle();
        }
        return createAndBind(key);
    }

    /** （handle, replace）二元组：replace=true 表示绑定该被替换（沙箱已终结或不可达）。 */
    private record ConnectOutcome(SandboxSessionClient.Handle handle, boolean replace) {
    }

    private ConnectOutcome connectBinding(SessionSandboxBindingStore.SessionSandboxKey key,
            SessionSandboxBinding binding) {
        SandboxSessionClient.Summary summary;
        try {
            summary = client.get(binding.sandboxId);
        } catch (RemoteError err) {
            if (canReplaceRemoteBinding(err)) {
                return new ConnectOutcome(null, true);
            }
            throw SandboxException.internal("get bound remote sandbox: " + err.getMessage(), err);
        }
        if (summary == null) {
            throw SandboxException.internal("remote sandbox Get returned nil summary");
        }
        if (!summary.id().equals(binding.sandboxId)) {
            throw SandboxException.internal(String.format(
                    "remote sandbox Get returned ID \"%s\" for binding \"%s\"",
                    summary.id(), binding.sandboxId));
        }
        if (SandboxSessionClient.Summary.STATE_TERMINAL.equals(summary.state())) {
            return new ConnectOutcome(null, true);
        }

        SandboxSessionClient.Handle handle;
        try {
            handle = client.connect(new SandboxSessionClient.ConnectRequest(
                    binding.sandboxId, binding.trafficAccessToken));
        } catch (RemoteError err) {
            if (canReplaceRemoteBinding(err)) {
                return new ConnectOutcome(null, true);
            }
            throw SandboxException.internal("connect bound remote sandbox: " + err.getMessage(), err);
        }
        validateHandle(handle, binding.sandboxId);
        persistInboundToken(bindings, key, binding, handle);
        return new ConnectOutcome(handle, false);
    }

    /**
     * 对照 persistInboundToken：把 provider 重发的 traffic token 写回绑定。
     * Connect 可能返回比 Redis 更新的凭据（pause→resume）；不写回的话之后的重启
     * 恢复旧副本，数据面调用 403，CanReplaceRemoteBinding 也不再换绑定。
     */
    static void persistInboundToken(
            SessionSandboxBindingStore store,
            SessionSandboxBindingStore.SessionSandboxKey key,
            SessionSandboxBinding binding,
            SandboxSessionClient.Handle handle) {
        if (store == null) {
            return;
        }
        String token = SandboxSessionClient.inboundTokenOf(handle);
        if (token == null || token.isEmpty() || token.equals(binding.trafficAccessToken)) {
            return;
        }
        try {
            store.replaceTrafficTokenIfMatch(key, binding, token);
        } catch (RuntimeException err) {
            LOG.warning("[sandbox] persist inbound token of session " + key.sessionId()
                    + " failed: " + err.getMessage());
        }
    }

    /** create 策略是否要求数据面调用携带 per-sandbox traffic token。 */
    private boolean inboundTokenRequired() {
        var network = createRequest.network();
        Boolean allowPublic = network == null ? null : network.allowPublicTraffic;
        return allowPublic != null && !allowPublic;
    }

    /**
     * 该句柄会在每次数据面调用上 403：provider 签发 token、本配置要求 token、
     * 句柄没有。Docker 的句柄不实现 InboundTokenCarrier，天然排除。
     */
    private boolean missingRequiredInboundToken(SandboxSessionClient.Handle handle) {
        boolean carries = handle instanceof SandboxSessionClient.InboundTokenCarrier;
        return carries && inboundTokenRequired()
                && SandboxSessionClient.inboundTokenOf(handle).isEmpty();
    }

    /** 对照 recoverOwnedSandbox 的返回形态。 */
    private record Recovered(SandboxSessionClient.Handle handle, boolean found) {
    }

    private Recovered recoverOwnedSandbox(SessionSandboxBindingStore.SessionSandboxKey key) {
        SandboxSessionClient.Capabilities capabilities = client.capabilities();
        if (!capabilities.supportsMetadata() || !capabilities.supportsListSandboxes()) {
            return new Recovered(null, false);
        }

        Map<String, String> metadata = metadata(key);
        List<SandboxSessionClient.Summary> summaries;
        try {
            summaries = client.list(new SandboxSessionClient.ListFilter(metadata, null));
        } catch (RemoteError err) {
            throw SandboxException.internal("list owned remote sandboxes: " + err.getMessage(), err);
        }
        // StartedAt 升序（零值排最后），同刻按 ID——与 Go 的 sort.Slice 语义一致
        List<SandboxSessionClient.Summary> sorted = new ArrayList<>(summaries);
        sorted.sort(Comparator
                .comparing((SandboxSessionClient.Summary s) -> zeroLast(s.startedAt()))
                .thenComparing(SandboxSessionClient.Summary::id));

        for (SandboxSessionClient.Summary summary : sorted) {
            if (summary.id() == null || summary.id().isEmpty()
                    || SandboxSessionClient.Summary.STATE_TERMINAL.equals(summary.state())) {
                continue;
            }
            if (!metadataMatches(summary.metadata(), metadata)) {
                throw SandboxException.internal(String.format(
                        "remote provider returned sandbox \"%s\" outside metadata filter",
                        summary.id()));
            }
            SandboxSessionClient.Handle handle;
            try {
                handle = client.connect(new SandboxSessionClient.ConnectRequest(summary.id(), ""));
            } catch (RemoteError err) {
                if (canReplaceRemoteBinding(err)) {
                    continue;
                }
                throw SandboxException.internal(
                        "connect owned remote sandbox: " + err.getMessage(), err);
            }
            validateHandle(handle, summary.id());
            // metadata 收养的沙箱没有绑定能供出 inbound token，两个带 token 的
            // provider 都只在 create 时签发。在封闭入站策略下收养它会把会话卡死：
            // 每个数据面调用 403（分类为 authentication 而非 NotFound），
            // CanReplaceRemoteBinding 永不让绑定被替换。判据是 provider **有没有**
            // 这个概念，而不是 token 是否为空：Docker 的句柄不实现 carrier。
            if (missingRequiredInboundToken(handle)) {
                // 删而不是绕开是安全的：本循环持有该 key 的生命周期锁，
                // metadata(key) 把候选限定到本会话。树上没有别的回收路径，
                // 上面的 Connect 已经唤醒了它并刷新 TTL，留下它就是每次重启
                // 搁浅一个计费沙箱。
                LOG.warning("[sandbox] deleting remote sandbox " + summary.id() + " of session "
                        + key.sessionId()
                        + ": adopted by metadata without the inbound traffic token "
                        + "this config's network policy requires");
                try {
                    cleanupSandboxID(summary.id());
                } catch (RuntimeException err) {
                    throw SandboxException.internal(String.format(
                            "delete unusable owned remote sandbox \"%s\": %s",
                            summary.id(), err.getMessage()), err);
                }
                continue;
            }
            try {
                cleanupOwnedDuplicates(sorted, summary.id(), metadata);
            } catch (RuntimeException err) {
                throw err;
            }
            String templateID = summary.templateId();
            if (templateID == null || templateID.isEmpty()) {
                templateID = createRequest.templateId();
            }
            // 只有入站公开、或 provider 重发了 token 时才会到这里，
            // 空 token 是"策略不需要"而不是"丢了凭据"。
            SessionSandboxBinding binding = newBinding(
                    key, summary.id(), templateID,
                    SandboxSessionClient.inboundTokenOf(handle),
                    summary.startedAt() == null ? null : summary.startedAt().toInstant());
            boolean created;
            try {
                created = bindings.create(key, binding);
            } catch (RuntimeException err) {
                throw SandboxException.internal(
                        "bind owned remote sandbox: " + err.getMessage(), err);
            }
            if (created) {
                return new Recovered(handle, true);
            }
            SandboxSessionClient.Handle winner = connectWinner(key);
            return new Recovered(winner, true);
        }
        return new Recovered(null, false);
    }

    private static OffsetDateTime zeroLast(OffsetDateTime t) {
        // Go 的 time.Time 零值排最后；Java 用 far-future 近似
        return t == null ? OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(Long.MAX_VALUE / 2), java.time.ZoneOffset.UTC) : t;
    }

    private SandboxSessionClient.Handle createAndBind(
            SessionSandboxBindingStore.SessionSandboxKey key) {
        SandboxSessionClient.Capabilities capabilities = client.capabilities();
        Map<String, String> metadata = null;
        if (capabilities.supportsMetadata()) {
            metadata = new LinkedHashMap<>();
            if (createRequest.metadata() != null) {
                metadata.putAll(createRequest.metadata());
            }
            metadata.putAll(metadata(key));
        }
        SandboxSessionClient.CreateRequest request = new SandboxSessionClient.CreateRequest(
                createRequest.templateId(),
                createRequest.timeout(),
                metadata,
                EffectiveConfigResolver.cloneMetadata(createRequest.envVars()),
                createRequest.network(),
                createRequest.volumeMounts());

        SandboxSessionClient.Handle handle;
        try {
            handle = client.create(request);
        } catch (RemoteError err) {
            throw SandboxException.internal("create remote sandbox: " + err.getMessage(), err);
        }
        try {
            validateHandle(handle, "");
        } catch (RuntimeException err) {
            RuntimeException joined = err;
            try {
                cleanupCreated(handle);
            } catch (RuntimeException cleanupErr) {
                joined.addSuppressed(cleanupErr);
            }
            throw joined;
        }
        // 与 metadata 收养同样的 403 卡死：token 只在 create 时签发，
        // authentication 错误不可替换。绑定空 token 会永久卡住会话，
        // 所以让 create 失败并销毁不可用的沙箱。
        if (missingRequiredInboundToken(handle)) {
            RuntimeException failure = SandboxException.internal(String.format(
                    "create remote sandbox \"%s\": inbound traffic token missing under a closed-inbound policy",
                    handle.id()));
            try {
                cleanupCreated(handle);
            } catch (RuntimeException cleanupErr) {
                failure.addSuppressed(cleanupErr);
            }
            throw failure;
        }

        boolean exists;
        try {
            exists = sessionChecker.sessionExists(key);
        } catch (RuntimeException checkErr) {
            RuntimeException failure = SandboxException.internal(
                    "recheck owning session: " + checkErr.getMessage(), checkErr);
            try {
                cleanupCreated(handle);
            } catch (RuntimeException cleanupErr) {
                failure.addSuppressed(cleanupErr);
            }
            throw failure;
        }
        if (!exists) {
            SandboxException failure = SandboxException.sessionDeleted();
            try {
                cleanupCreated(handle);
            } catch (RuntimeException cleanupErr) {
                failure.addSuppressed(cleanupErr);
            }
            throw failure;
        }

        SessionSandboxBinding binding = newBinding(
                key, handle.id(), request.templateId(),
                SandboxSessionClient.inboundTokenOf(handle), now.get());
        boolean created;
        try {
            created = bindings.create(key, binding);
        } catch (RuntimeException bindErr) {
            RuntimeException failure = SandboxException.internal(
                    "create sandbox binding: " + bindErr.getMessage(), bindErr);
            try {
                cleanupCreated(handle);
            } catch (RuntimeException cleanupErr) {
                failure.addSuppressed(cleanupErr);
            }
            throw failure;
        }
        if (created) {
            return handle;
        }

        SessionSandboxBinding winner;
        try {
            winner = readBinding(key);
        } catch (RuntimeException winnerErr) {
            // 权威赢家未知，删这个沙箱可能毁掉另一协调者刚绑定的资源
            throw SandboxException.internal(
                    "read winning sandbox binding: " + winnerErr.getMessage(), winnerErr);
        }
        if (winner != null && winner.provider.equals(client.provider())
                && winner.sandboxId.equals(handle.id())) {
            return handle;
        }
        try {
            cleanupCreated(handle);
        } catch (RuntimeException cleanupErr) {
            throw SandboxException.internal(
                    "cleanup losing remote sandbox: " + cleanupErr.getMessage(), cleanupErr);
        }
        if (winner == null) {
            throw SandboxException.internal("sandbox binding create lost without a winner");
        }
        return connectKnownWinner(winner);
    }

    private SandboxSessionClient.Handle connectWinner(SessionSandboxBindingStore.SessionSandboxKey key) {
        SessionSandboxBinding winner = readBinding(key);
        if (winner == null) {
            throw SandboxException.internal("sandbox binding create lost without a winner");
        }
        return connectKnownWinner(winner);
    }

    private SandboxSessionClient.Handle connectKnownWinner(SessionSandboxBinding winner) {
        if (!winner.provider.equals(client.provider())) {
            throw SandboxException.internal(String.format(
                    "sandbox binding winner uses provider \"%s\", current provider is \"%s\"",
                    winner.provider, client.provider()));
        }
        ConnectOutcome outcome = connectBinding(
                new SessionSandboxBindingStore.SessionSandboxKey(winner.tenantId, winner.sessionId),
                winner);
        if (outcome.replace()) {
            throw SandboxException.internal("sandbox binding winner is already terminal");
        }
        return outcome.handle();
    }

    private void cleanupOwnedDuplicates(
            List<SandboxSessionClient.Summary> summaries,
            String selectedID,
            Map<String, String> metadata) {
        for (SandboxSessionClient.Summary candidate : summaries) {
            if (candidate.id() == null || candidate.id().isEmpty()
                    || candidate.id().equals(selectedID)
                    || SandboxSessionClient.Summary.STATE_TERMINAL.equals(candidate.state())) {
                continue;
            }
            if (!metadataMatches(candidate.metadata(), metadata)) {
                throw SandboxException.internal(String.format(
                        "remote provider returned sandbox \"%s\" outside metadata filter",
                        candidate.id()));
            }
            try {
                cleanupSandboxID(candidate.id());
            } catch (RuntimeException err) {
                throw SandboxException.internal(String.format(
                        "delete duplicate owned remote sandbox \"%s\": %s",
                        candidate.id(), err.getMessage()), err);
            }
        }
    }

    private void destroyBindingLocked(
            SessionSandboxBindingStore.SessionSandboxKey key,
            SessionSandboxBinding binding) {
        if (binding.provider.equals(client.provider())) {
            try {
                client.delete(binding.sandboxId);
            } catch (RemoteError err) {
                if (!canReplaceRemoteBinding(err)) {
                    throw SandboxException.internal(
                            "delete remote sandbox: " + err.getMessage(), err);
                }
            }
        }

        boolean deleted;
        try {
            deleted = bindings.deleteIfMatch(key, binding.provider, binding.sandboxId);
        } catch (RuntimeException err) {
            throw SandboxException.internal("delete sandbox binding: " + err.getMessage(), err);
        }
        if (deleted) {
            return;
        }
        SessionSandboxBinding current = readBinding(key);
        if (current == null) {
            return;
        }
        throw SandboxException.internal("sandbox binding changed during destroy");
    }

    private SessionSandboxBinding readBinding(SessionSandboxBindingStore.SessionSandboxKey key) {
        SessionSandboxBinding binding;
        try {
            binding = bindings.get(key);
        } catch (RuntimeException err) {
            throw SandboxException.internal("get sandbox binding: " + err.getMessage(), err);
        }
        if (binding == null) {
            return null;
        }
        try {
            binding.validate(key);
        } catch (RuntimeException err) {
            throw SandboxException.internal("validate sandbox binding: " + err.getMessage(), err);
        }
        return binding;
    }

    private void cleanupCreated(SandboxSessionClient.Handle handle) {
        if (handle == null || handle.id() == null || handle.id().isEmpty()) {
            throw SandboxException.internal("cannot clean up remote sandbox without an ID");
        }
        cleanupSandboxID(handle.id());
    }

    /**
     * 对照 cleanupSandboxID：带 cleanupTimeout 预算删除一个沙箱。Go 在
     * ownership context（调用方取消后仍存活）上设超时；Java 无等价物，
     * 只能同步跑并依赖远程 client 自身的超时（差异已备案）。
     */
    private void cleanupSandboxID(String sandboxID) {
        try {
            client.delete(sandboxID);
        } catch (RemoteError err) {
            if (!canReplaceRemoteBinding(err)) {
                throw err;
            }
        }
    }

    private void validateHandle(SandboxSessionClient.Handle handle, String expectedID) {
        if (handle == null) {
            throw SandboxException.internal("remote sandbox client returned nil handle");
        }
        if (handle.id() == null || handle.id().strip().isEmpty()) {
            throw SandboxException.internal("remote sandbox client returned handle without ID");
        }
        if (expectedID != null && !expectedID.isEmpty() && !handle.id().equals(expectedID)) {
            throw SandboxException.internal(String.format(
                    "remote sandbox handle ID \"%s\" does not match expected \"%s\"",
                    handle.id(), expectedID));
        }
        if (!handle.provider().equals(client.provider())) {
            throw SandboxException.internal(String.format(
                    "remote sandbox handle provider \"%s\" does not match client \"%s\"",
                    handle.provider(), client.provider()));
        }
    }

    private SessionSandboxBinding newBinding(
            SessionSandboxBindingStore.SessionSandboxKey key,
            String sandboxID,
            String templateID,
            String trafficAccessToken,
            Instant createdAt) {
        if (createdAt == null || createdAt.toEpochMilli() == 0) {
            createdAt = now.get();
        }
        SessionSandboxBinding binding = new SessionSandboxBinding();
        binding.version = SESSION_SANDBOX_BINDING_VERSION;
        binding.provider = client.provider();
        binding.tenantId = key.tenantId();
        binding.sessionId = key.sessionId();
        binding.sandboxId = sandboxID;
        binding.templateId = templateID;
        binding.createdAt = createdAt;
        binding.configId = sandboxConfigID;
        binding.trafficAccessToken = trafficAccessToken;
        return binding;
    }

    private Map<String, String> metadata(SessionSandboxBindingStore.SessionSandboxKey key) {
        Map<String, String> md = new LinkedHashMap<>();
        md.put(REMOTE_METADATA_TENANT_ID, Long.toUnsignedString(key.tenantId()));
        md.put(REMOTE_METADATA_SESSION_ID, key.sessionId());
        md.put(REMOTE_METADATA_BINDING_VERSION, Integer.toString(SESSION_SANDBOX_BINDING_VERSION));
        md.put(REMOTE_METADATA_PROVIDER, client.provider());
        md.put(REMOTE_METADATA_CONFIG_ID, sandboxConfigID);
        return md;
    }

    /**
     * 对照 shouldRebuildStaleBinding：无 turn 或新 turn 首次 resolve 重建；
     * 已用上沙箱的 turn 保留它。读不到的租约按"turn 进行中"处理，
     * Redis 抖动不能毁掉 /workspace。
     */
    private boolean shouldRebuildStaleBinding(SessionSandboxBindingStore.SessionSandboxKey key) {
        if (!(bindings instanceof SessionSandboxBindingStore.SessionTurnLeaseStore leaser)) {
            return true;
        }
        SessionSandboxBindingStore.TurnLeaseState state;
        try {
            state = leaser.turnState(key);
        } catch (RuntimeException err) {
            LOG.warning("[sandbox] turn lease of session " + key.sessionId()
                    + " unavailable (" + err.getMessage() + "); keeping the current sandbox");
            return false;
        }
        if (!state.active()) {
            return true;
        }
        return state.rebuildOnce();
    }

    private void consumeTurnRebuild(SessionSandboxBindingStore.SessionSandboxKey key) {
        if (!(bindings instanceof SessionSandboxBindingStore.SessionTurnLeaseStore leaser)) {
            return;
        }
        try {
            leaser.consumeTurnRebuild(key);
        } catch (RuntimeException err) {
            LOG.warning("[sandbox] consume turn rebuild of session " + key.sessionId()
                    + " failed: " + err.getMessage());
        }
    }

    /** 对照 metadataMatches：required 的每一项 candidate 都得有同值。 */
    static boolean metadataMatches(Map<String, String> candidate, Map<String, String> required) {
        for (Map.Entry<String, String> e : required.entrySet()) {
            String value = candidate.get(e.getKey());
            String expected = e.getValue();
            boolean equal = value == null ? expected == null : value.equals(expected);
            if (!equal) {
                return false;
            }
        }
        return true;
    }

    /**
     * 对照 CanReplaceRemoteBinding（remote_errors.go L244-254）：错误证明被绑定的
     * 远程沙箱永久消失。刻意白名单制——未知与新引入的错误默认保留绑定。
     */
    static boolean canReplaceRemoteBinding(Throwable err) {
        RemoteError re = findRemoteError(err);
        if (re == null) {
            return false;
        }
        return re.kind == RemoteErrorKind.NOT_FOUND || re.kind == RemoteErrorKind.TERMINAL;
    }

    /** 对照 IsRemoteNotFound：NotFound 与 Terminal 都按"已消失"处理。 */
    static boolean isRemoteNotFound(Throwable err) {
        RemoteError re = findRemoteError(err);
        return re != null
                && (re.kind == RemoteErrorKind.NOT_FOUND || re.kind == RemoteErrorKind.TERMINAL);
    }

    /** 对照 IsRemoteInvalidRequest。 */
    static boolean isRemoteInvalidRequest(Throwable err) {
        RemoteError re = findRemoteError(err);
        return re != null && re.kind == RemoteErrorKind.INVALID_REQUEST;
    }

    private static RemoteError findRemoteError(Throwable err) {
        while (err != null) {
            if (err instanceof RemoteError re) {
                return re;
            }
            err = err.getCause();
        }
        return null;
    }
}
