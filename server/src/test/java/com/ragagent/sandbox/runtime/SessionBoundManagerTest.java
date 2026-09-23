package com.ragagent.sandbox.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ragagent.common.context.TenantContext;

/**
 * SessionBoundManager 的纯单元测试（内存 binding store + fake client）。
 *
 * <p>fake client 对照 Go internal/sandbox/remote_fake_test.go 的 {@code fakeRemoteClient}：
 * 内存沙箱记录、create/connect/get/list/delete 计数、错误注入 map、create 时签发
 * inbound token（Cube/E2B 语义；DefaultConfig 的入站策略是关闭的，无 token 的 fake
 * 会让 createAndBind 像"provider 漏发凭据"一样失败——注释见 Go 原文）。
 * binding store 用 {@link SessionSandboxBindingStore#memoryStore()}。</p>
 *
 * <p>只覆盖不依赖真实网络/真实 provider 的确定性路径；不启动 Spring。</p>
 */
class SessionBoundManagerTest {

    private static final long TENANT_ID = 42L;
    private static final String TEMPLATE_ID = "tpl-cube";

    private SessionSandboxBindingStore.MemorySessionSandboxBindingStore store;
    private FakeSessionClient client;
    private SessionBoundManager manager;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID, null, "owner", false, "user-1", false);
        store = SessionSandboxBindingStore.memoryStore();
        client = new FakeSessionClient();
        manager = newManager();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private SessionBoundManager newManager() {
        return new SessionBoundManager(cubeConfig(), client, store,
                new SessionBoundManager.PermissiveSessionExistenceChecker(),
                "", true);
    }

    /** Cube 形态的最小配置：模板必填，网络策略 = resolve(null)（出网开、入站关）。 */
    private static EffectiveConfig cubeConfig() {
        EffectiveConfig cfg = new EffectiveConfig();
        cfg.type = SandboxTypes.TYPE_CUBE;
        cfg.cubeTemplate = TEMPLATE_ID;
        cfg.cubeSandboxTtlSec = 1800;
        cfg.cubeHttpTimeoutSec = 30;
        cfg.network = EffectiveConfigResolver.resolveNetworkPolicy(null);
        return cfg;
    }

    private static SandboxManager.ExecuteConfig scriptConfig(String sessionId) {
        SandboxManager.ExecuteConfig cfg = new SandboxManager.ExecuteConfig();
        cfg.script = "/host/scripts/script.sh";
        cfg.scriptContent = "echo hi\n";
        cfg.skipValidation = true;
        cfg.sessionId = sessionId;
        return cfg;
    }

    private SessionSandboxBindingStore.SessionSandboxKey key(String sessionId) {
        return new SessionSandboxBindingStore.SessionSandboxKey(TENANT_ID, sessionId);
    }

    // ── 一次性路径（空 SessionID） ────────────────────────────────────────

    @Test
    void ephemeralExecuteCreatesRunsAndDisposes() {
        SandboxManager.ExecuteConfig cfg = scriptConfig(null);

        SandboxManager.ExecuteResult result = manager.execute(cfg);

        assertThat(result.exitCode).isZero();
        assertThat(result.error).isEmpty();
        assertThat(client.createCount).isEqualTo(1);
        // 一次性路径恒拆：脚本上传 + 执行后删除
        assertThat(client.deleteCount).isEqualTo(1);
        assertThat(client.writeFiles).hasSize(1);
        assertThat(client.writeFiles.get(0).path).isEqualTo("/workspace/script.sh");
        // 一次性路径不绑定会话
        assertThat(store.listTenantBindingKeys(TENANT_ID)).isEmpty();
    }

    // ── 会话绑定路径 ──────────────────────────────────────────────────────

    @Test
    void sessionExecuteResolvesSandboxAndPersistsBinding() {
        SandboxManager.ExecuteResult result = manager.execute(scriptConfig("s-1"));

        assertThat(result.exitCode).isZero();
        assertThat(client.createCount).isEqualTo(1);
        // 会话沙箱存活：Execute 后绝不删除
        assertThat(client.deleteCount).isZero();

        SessionSandboxBinding binding = store.get(key("s-1"));
        assertThat(binding).isNotNull();
        assertThat(binding.version)
                .isEqualTo(RemoteSessionLifecycle.SESSION_SANDBOX_BINDING_VERSION);
        assertThat(binding.provider).isEqualTo("cube");
        assertThat(binding.tenantId).isEqualTo(TENANT_ID);
        assertThat(binding.sessionId).isEqualTo("s-1");
        assertThat(binding.sandboxId).isEqualTo(client.lastCreatedId());
        assertThat(binding.templateId).isEqualTo(TEMPLATE_ID);
        // 空 configId 归一到部署默认哨兵
        assertThat(binding.configId)
                .isEqualTo(RemoteSessionLifecycle.SANDBOX_CONFIG_ID_GLOBAL_DEFAULT);
        assertThat(binding.createdAt).isNotNull();

        // create 请求带上了会话 metadata（tenant/session/binding version/provider/config）
        Map<String, String> metadata = client.lastCreateMetadata();
        assertThat(metadata.get(RemoteSessionLifecycle.REMOTE_METADATA_TENANT_ID))
                .isEqualTo("42");
        assertThat(metadata.get(RemoteSessionLifecycle.REMOTE_METADATA_SESSION_ID))
                .isEqualTo("s-1");
        assertThat(metadata.get(RemoteSessionLifecycle.REMOTE_METADATA_PROVIDER))
                .isEqualTo("cube");
        assertThat(metadata.get(RemoteSessionLifecycle.REMOTE_METADATA_CONFIG_ID))
                .isEqualTo(RemoteSessionLifecycle.SANDBOX_CONFIG_ID_GLOBAL_DEFAULT);
    }

    @Test
    void sessionExecuteReusesLiveSandboxOnSecondCall() {
        manager.execute(scriptConfig("s-1"));
        manager.execute(scriptConfig("s-1"));

        // 第二次 resolve 走 connect 而不是 create
        assertThat(client.createCount).isEqualTo(1);
        assertThat(client.connectCount).isGreaterThanOrEqualTo(1);
        assertThat(client.deleteCount).isZero();
    }

    @Test
    void destroySessionRemovesSandboxAndBindingIdempotently() {
        manager.execute(scriptConfig("s-1"));
        String sandboxId = client.lastCreatedId();

        manager.destroySession(TENANT_ID, "s-1");

        assertThat(client.deletedIds).containsExactly(sandboxId);
        assertThat(store.get(key("s-1"))).isNull();

        // 幂等：不存在的会话照常成功
        manager.destroySession(TENANT_ID, "s-1");
        assertThat(client.deleteCount).isEqualTo(1);
    }

    @Test
    void executeRejectsSessionIdWithBraces() {
        SandboxManager.ExecuteConfig cfg = scriptConfig("bad{session}");
        assertThatThrownBy(() -> manager.execute(cfg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain braces");
        assertThat(client.createCount).isZero();
    }

    // ── lookup 路径绝不 provision ────────────────────────────────────────

    @Test
    void readSessionFileWithoutBindingErrorsAndNeverProvisions() {
        assertThatThrownBy(
                () -> manager.readSessionFile(TENANT_ID, "s-2", "/workspace/out.txt"))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("no live sandbox for session s-2");
        assertThat(client.createCount).isZero();
    }

    @Test
    void listSessionFilesWithoutBindingReturnsNullWithoutProvisioning() {
        assertThat(manager.listSessionFiles(TENANT_ID, "s-2", "/workspace/output")).isNull();
        assertThat(client.createCount).isZero();
    }

    @Test
    void writeSessionInputFileProvisionsSandboxOnce() {
        byte[] content = "attachment".getBytes();
        manager.writeSessionInputFile(TENANT_ID, "s-3", "/workspace/input/doc.txt", content);

        assertThat(client.createCount).isEqualTo(1);
        SessionSandboxBinding binding = store.get(key("s-3"));
        assertThat(binding).isNotNull();
        // 附件写进清洗后的 input 路径
        assertThat(client.writeFiles).anySatisfy(w -> {
            assertThat(w.path).isEqualTo("/workspace/input/doc.txt");
            assertThat(new String(w.content)).isEqualTo("attachment");
        });
        // 父目录经 MakeDir 物化
        assertThat(client.makeDirPaths).contains("/workspace/input");

        // 第二次写不再 provision
        manager.writeSessionInputFile(TENANT_ID, "s-3",
                "/workspace/input/doc2.txt", "more".getBytes());
        assertThat(client.createCount).isEqualTo(1);
    }

    @Test
    void writeSessionInputFileRejectsPathOutsideInputRoot() {
        assertThatThrownBy(() -> manager.writeSessionInputFile(TENANT_ID, "s-4",
                "/workspace/other/doc.txt", "x".getBytes()))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("is outside /workspace/input");
        assertThat(client.createCount).isZero();
    }

    // ── 会话 shell API ────────────────────────────────────────────────────

    @Test
    void execShellCommandPreparesWorkspaceAndRunsCommand() {
        SandboxManager.ExecuteResult result = manager.execShellCommand(
                TENANT_ID, "s-5", "ls -la", "/workspace", null, null);

        assertThat(result.exitCode).isZero();
        // 第一次 exec 是目录 bootstrap（15s 预算），最后一次才是 shell 命令本身
        assertThat(client.execRequests).isNotEmpty();
        SandboxSessionClient.ExecRequest bootstrap = client.execRequests.get(0);
        assertThat(bootstrap.command())
                .isEqualTo(SessionSandboxPaths.workspaceBootstrapCommand(
                        SessionSandboxPaths.SESSION_INPUT_ROOT,
                        SessionSandboxPaths.SESSION_OUTPUT_ROOT,
                        "/workspace"));
        assertThat(bootstrap.timeout())
                .isEqualTo(SessionBoundManager.SESSION_ARTIFACT_DIR_BOOTSTRAP_TIMEOUT);

        SandboxSessionClient.ExecRequest shell = client.execRequests
                .get(client.execRequests.size() - 1);
        assertThat(shell.command()).isEqualTo("ls -la");
        assertThat(shell.shell()).isTrue();
        assertThat(shell.workDir()).isEqualTo("/workspace");
        assertThat(shell.user())
                .isEqualTo(SandboxSessionClient.ExecRequest.DEFAULT_SANDBOX_EXEC_USER);
    }

    @Test
    void execShellCommandRefusesWorkDirOutsideAllowedRoots() {
        assertThatThrownBy(() -> manager.execShellCommand(
                TENANT_ID, "s-6", "ls", "/etc", null, null))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("is outside allowed roots");
        assertThat(client.createCount).isZero();
    }

    // ── Cleanup 语义 ─────────────────────────────────────────────────────

    @Test
    void cleanupDisablesManagerIdempotently() {
        manager.cleanup();
        manager.cleanup(); // 幂等

        assertThatThrownBy(() -> manager.execute(scriptConfig(null)))
                .isInstanceOf(SandboxException.class)
                .extracting(e -> ((SandboxException) e).kind)
                .isEqualTo(SandboxException.Kind.SANDBOX_DISABLED);
        assertThat(client.createCount).isZero();
    }

    // ── chat-turn 租约（内存 store 实现了 lease 半边） ────────────────────

    @Test
    void turnLeaseOpenAndCloseWithoutError() {
        manager.beginSessionTurn(TENANT_ID, "s-1");
        manager.execute(scriptConfig("s-1"));
        manager.endSessionTurn(TENANT_ID, "s-1");

        assertThat(store.get(key("s-1"))).isNotNull();
    }

    // ── 构造期投影 ────────────────────────────────────────────────────────

    @Test
    void withWorkspaceEnvDefaultsStampsWorkspacePaths() {
        Map<String, String> env = SessionBoundManager.withWorkspaceEnvDefaults(null);
        assertThat(env)
                .containsEntry(SessionBoundManager.SKILL_OUTPUT_ENV_VAR,
                        SessionBoundManager.SESSION_OUTPUT_ROOT)
                .containsEntry(SessionBoundManager.SESSION_INPUT_ENV_VAR,
                        SessionBoundManager.SESSION_INPUT_ROOT);

        // 租户配置的产物目录优先，不被默认值覆写
        Map<String, String> custom = new LinkedHashMap<>();
        custom.put(SessionBoundManager.SKILL_OUTPUT_ENV_VAR, "/workspace/custom");
        Map<String, String> merged = SessionBoundManager.withWorkspaceEnvDefaults(custom);
        assertThat(merged)
                .containsEntry(SessionBoundManager.SKILL_OUTPUT_ENV_VAR, "/workspace/custom")
                .containsEntry(SessionBoundManager.SESSION_INPUT_ENV_VAR,
                        SessionBoundManager.SESSION_INPUT_ROOT);
    }

    @Test
    void constructorRejectsMissingTemplate() {
        EffectiveConfig cfg = cubeConfig();
        cfg.cubeTemplate = " ";
        assertThatThrownBy(() -> new SessionBoundManager(cfg, client, store,
                new SessionBoundManager.PermissiveSessionExistenceChecker(), "", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("template ID is required");
    }

    @Test
    void constructorRejectsUnknownProvider() {
        assertThatThrownBy(() -> new SessionBoundManager(cubeConfig(),
                new FakeSessionClient() {
                    @Override
                    public String provider() {
                        return "unknown";
                    }
                }, store,
                new SessionBoundManager.PermissiveSessionExistenceChecker(), "", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported remote provider");
    }

    // ═════════════════════════════════════════════════════════════════════
    // fake client（对照 Go remote_fake_test.go 的 fakeRemoteClient）
    // ═════════════════════════════════════════════════════════════════════

    /** 内存沙箱记录（对照 fakeRemoteRecord）。 */
    private static final class FakeRecord {
        final String id;
        final String templateId;
        String state = SandboxSessionClient.Summary.STATE_RUNNING;
        final Map<String, String> metadata;
        final OffsetDateTime startedAt = OffsetDateTime.now();

        FakeRecord(String id, String templateId, Map<String, String> metadata) {
            this.id = id;
            this.templateId = templateId;
            this.metadata = metadata == null ? null : new LinkedHashMap<>(metadata);
        }
    }

    /** 带 inbound token 的句柄（对照 fakeRemoteHandle，实现 InboundTokenCarrier）。 */
    private static final class FakeHandle
            implements SandboxSessionClient.Handle, SandboxSessionClient.InboundTokenCarrier {

        final String id;
        final String provider;
        final Map<String, String> metadata;
        final String trafficAccessToken;

        FakeHandle(String id, String provider, Map<String, String> metadata,
                String trafficAccessToken) {
            this.id = id;
            this.provider = provider;
            this.metadata = metadata;
            this.trafficAccessToken = trafficAccessToken;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public Map<String, String> metadata() {
            return metadata;
        }

        @Override
        public String trafficAccessToken() {
            return trafficAccessToken;
        }
    }

    record FakeWriteFile(String path, byte[] content) {
    }

    /**
     * Cube 语义的内存 provider：create 时签发 "test-inbound-token"（对照
     * newFakeRemoteClient 对 Cube/E2B 的默认），连接缺失/终结沙箱报 NotFound。
     */
    static class FakeSessionClient implements SandboxSessionClient {

        final Map<String, FakeRecord> sandboxes = new LinkedHashMap<>();
        final Map<String, RemoteError> connectErrs = new LinkedHashMap<>();
        final Map<String, RemoteError> getErrs = new LinkedHashMap<>();
        final Map<String, RemoteError> deleteErrs = new LinkedHashMap<>();

        final List<String> connectIds = new ArrayList<>();
        final List<String> getIds = new ArrayList<>();
        final List<String> deletedIds = new ArrayList<>();
        final List<SandboxSessionClient.ExecRequest> execRequests = new ArrayList<>();
        final List<FakeWriteFile> writeFiles = new ArrayList<>();
        final List<String> makeDirPaths = new ArrayList<>();
        final List<SandboxSessionClient.CreateRequest> createRequests = new ArrayList<>();

        int createCount;
        int connectCount;
        int deleteCount;

        final String trafficToken = "test-inbound-token";

        @Override
        public String provider() {
            return SandboxTypes.TYPE_CUBE;
        }

        @Override
        public SandboxSessionClient.Capabilities capabilities() {
            return new SandboxSessionClient.Capabilities(
                    true,  // supportsReconnect
                    true,  // supportsMetadata
                    true,  // supportsListSandboxes
                    false, // supportsPauseResume
                    false, // supportsTimeoutRefresh
                    true,  // supportsFilesystemEnumeration
                    false, // supportsSnapshots
                    false, // supportsVolumes
                    false);// supportsTerminals
        }

        @Override
        public void health() {
            // 可达
        }

        @Override
        public synchronized SandboxSessionClient.Handle create(
                SandboxSessionClient.CreateRequest req) {
            createCount++;
            createRequests.add(req);
            String id = provider() + "-" + createCount;
            sandboxes.put(id, new FakeRecord(id, req.templateId(), req.metadata()));
            return new FakeHandle(id, provider(), req.metadata(), trafficToken);
        }

        @Override
        public synchronized SandboxSessionClient.Handle connect(
                SandboxSessionClient.ConnectRequest req) {
            connectCount++;
            connectIds.add(req.sandboxId());
            RemoteError injected = connectErrs.get(req.sandboxId());
            if (injected != null) {
                throw injected;
            }
            FakeRecord record = sandboxes.get(req.sandboxId());
            if (record == null
                    || SandboxSessionClient.Summary.STATE_TERMINAL.equals(record.state)) {
                throw RemoteError.of(provider(), "Connect",
                        RemoteErrorKind.NOT_FOUND, "sandbox not found");
            }
            // E2B/Cube 只在 create 签发；Connect 原样返回请求携带的 token
            return new FakeHandle(req.sandboxId(), provider(), record.metadata,
                    req.trafficAccessToken());
        }

        @Override
        public synchronized SandboxSessionClient.Summary get(String sandboxId) {
            getIds.add(sandboxId);
            RemoteError injected = getErrs.get(sandboxId);
            if (injected != null) {
                throw injected;
            }
            FakeRecord record = sandboxes.get(sandboxId);
            if (record == null) {
                throw RemoteError.of(provider(), "Get",
                        RemoteErrorKind.NOT_FOUND, "sandbox not found");
            }
            return new SandboxSessionClient.Summary(record.id, record.templateId,
                    record.state, record.state, record.metadata, record.startedAt, null);
        }

        @Override
        public synchronized List<SandboxSessionClient.Summary> list(
                SandboxSessionClient.ListFilter filter) {
            List<SandboxSessionClient.Summary> result = new ArrayList<>();
            for (FakeRecord record : sandboxes.values()) {
                if (!RemoteSessionLifecycle.metadataMatches(record.metadata, filter.metadata())) {
                    continue;
                }
                result.add(new SandboxSessionClient.Summary(record.id, record.templateId,
                        record.state, record.state, record.metadata, record.startedAt, null));
            }
            return result;
        }

        @Override
        public synchronized void delete(String sandboxId) {
            deleteCount++;
            deletedIds.add(sandboxId);
            RemoteError injected = deleteErrs.get(sandboxId);
            if (injected != null) {
                throw injected;
            }
            if (sandboxes.remove(sandboxId) == null) {
                throw RemoteError.of(provider(), "Delete",
                        RemoteErrorKind.NOT_FOUND, "sandbox not found");
            }
        }

        @Override
        public synchronized SandboxSessionClient.ExecResult exec(
                SandboxSessionClient.Handle handle, SandboxSessionClient.ExecRequest req) {
            execRequests.add(req);
            return new SandboxSessionClient.ExecResult("", "", 0, Duration.ZERO, false);
        }

        @Override
        public synchronized void writeFile(SandboxSessionClient.Handle handle, String path,
                byte[] content) {
            writeFiles.add(new FakeWriteFile(path, content.clone()));
        }

        @Override
        public byte[] readFile(SandboxSessionClient.Handle handle, String path) {
            return null;
        }

        @Override
        public List<SandboxSessionClient.DirEntry> listDir(
                SandboxSessionClient.Handle handle, String path) {
            return List.of();
        }

        @Override
        public synchronized void makeDir(SandboxSessionClient.Handle handle, String path) {
            makeDirPaths.add(path);
        }

        @Override
        public void remove(SandboxSessionClient.Handle handle, String path) {
            // no-op
        }

        @Override
        public SandboxSessionClient.StatEntry stat(SandboxSessionClient.Handle handle,
                String path) {
            return null;
        }

        String lastCreatedId() {
            return provider() + "-" + createCount;
        }

        Map<String, String> lastCreateMetadata() {
            return createRequests.get(createRequests.size() - 1).metadata();
        }
    }
}
