package com.ragagent.sandbox.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 会话绑定沙箱管理器（对照 Go internal/sandbox/session_manager.go 全文）。
 *
 * <p>每个租户会话一个持久远程沙箱。provider 专属工作全部委托给
 * {@link SandboxSessionClient} 适配器；权威的会话→沙箱绑定是外部状态，存在
 * {@link SessionSandboxBindingStore}（生产 Redis，测试/单进程内存）。这让管理器
 * provider 中立（Cube/E2B/Docker）且多实例安全：并发服务同一会话的两个进程
 * 不会重复分配沙箱，重启不丢远程资源。</p>
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>SessionID 非空的 Execute 经生命周期协调器（{@link RemoteSessionLifecycle}）
 *       解析会话沙箱（懒创建/恢复/替换），在解析出的句柄上跑脚本；解析全程在
 *       分布式生命周期锁内。</li>
 *   <li>SessionID 为空的 Execute 走一次性 {@link RemoteSandbox}：分配、执行、回收。</li>
 *   <li>Cube/E2B 自带空闲回收；Docker 无 provider TTL，由后端自己的 idle 清扫负责。</li>
 * </ul>
 *
 * <h2>Go → Java 映射备案</h2>
 * <ul>
 *   <li>tenant 从 Go 的 ctx（SandboxTenantIDFromContext，会话属主租户）改为显式
 *       参数——共享 agent 的调用方必须传<b>会话属主</b>租户，否则沙箱绑错租户、
 *       会话删除时回收不到。</li>
 *   <li>Go 的 langfuse 包裹（wrapLangfuseRemoteClient）未翻：tracing.langfuse 是
 *       no-op 门面，接缝随后续批次。</li>
 *   <li>终端面（OpenSessionTerminal/peekBoundSandboxState）不在本批：PTY 流传输属
 *       W5δ provider-XDEP（见 HANDOFF）。</li>
 *   <li>Go 的 commandOutputCallback 的 ctx 钩子无 Java 等价——只透传显式回调。</li>
 *   <li>MakeDir 的"已存在即成功"由 SandboxSessionClient 适配层契约保证
 *       （见该接口 makeDir 注释），无需 Go 的 ignoreExistingDir。</li>
 *   <li>WriteSessionFile 的 maintenance filesystem 标记（remoteFileUser=root）是
 *       适配器侧选择：Java 适配器按"维护路径恒 root"自行处理（接缝备案）。</li>
 * </ul>
 */
public class SessionBoundManager implements SandboxManager {

    private static final Logger LOG = Logger.getLogger(SessionBoundManager.class.getName());

    // ── session_manager.go 常量 ─────────────────────────────────────────

    /** 会话输入根（对照 SessionInputRoot）。 */
    public static final String SESSION_INPUT_ROOT = SessionSandboxPaths.SESSION_INPUT_ROOT;
    /** 会话产物输出根（对照 SessionOutputRoot）。 */
    public static final String SESSION_OUTPUT_ROOT = SessionSandboxPaths.SESSION_OUTPUT_ROOT;
    /** 可写工作区根（对照 SessionWorkspaceRoot）。 */
    public static final String SESSION_WORKSPACE_ROOT = SessionSandboxPaths.SESSION_WORKSPACE_ROOT;
    /** 对照 skillOutputEnvVar。 */
    public static final String SKILL_OUTPUT_ENV_VAR = SessionSandboxPaths.SKILL_OUTPUT_ENV_VAR;
    /** 对照 sessionInputEnvVar。 */
    public static final String SESSION_INPUT_ENV_VAR = SessionSandboxPaths.SESSION_INPUT_ENV_VAR;

    /** 目录创建与可访问性检查的预算（以执行身份跑，对照 sessionArtifactDirBootstrapTimeout）。 */
    public static final Duration SESSION_ARTIFACT_DIR_BOOTSTRAP_TIMEOUT = Duration.ofSeconds(15);

    /** 生命周期协调器自身的记账性删除预算（对照 sessionLifecycleCleanupTimeout）。 */
    public static final Duration SESSION_LIFECYCLE_CLEANUP_TIMEOUT = Duration.ofSeconds(30);

    private final EffectiveConfig config;
    private final ScriptValidator validator;

    private final SandboxSessionClient client;
    private final SessionSandboxBindingStore bindings;
    private final RemoteSessionLifecycle.SessionExistenceChecker checker;
    private final RemoteSessionLifecycle lifecycle;
    private final RemoteSandbox ephemeral;

    /** 调用方观察到的生效沙箱类型。 */
    private final String activeType;

    private final Object stateLock = new Object();
    private boolean closed;

    /**
     * 构造器注入全部依赖（Spring 装配由主会话负责，本类不带任何 Spring 注解）。
     *
     * @param config          对照 Config；null 用默认
     * @param client          RemoteSandboxClient 的 Java 契约（必填）
     * @param store           绑定存储（必填）
     * @param checker         会话存在性检查（必填）
     * @param configId        本管理器服务的租户沙箱配置 ID，盖进沙箱 metadata，
     *                        让清理只命中本配置（空串按部署默认哨兵处理）
     * @param skipHealthProbe 跳过构造期 Health 往返——按请求重建的 per-tenant 管理器
     *                        必须设 true（对照 SessionBoundManagerConfig.SkipHealthProbe）
     */
    public SessionBoundManager(
            EffectiveConfig config,
            SandboxSessionClient client,
            SessionSandboxBindingStore store,
            RemoteSessionLifecycle.SessionExistenceChecker checker,
            String configId,
            boolean skipHealthProbe) {
        EffectiveConfig cfg = config == null ? EffectiveConfig.defaultConfig() : config;
        validateConfig(cfg);
        if (client == null) {
            throw new IllegalArgumentException(
                    "session bound manager requires a RemoteSandboxClient");
        }
        if (store == null) {
            throw new IllegalArgumentException(
                    "session bound manager requires a SessionSandboxBindingStore");
        }
        if (checker == null) {
            throw new IllegalArgumentException(
                    "session bound manager requires a SessionExistenceChecker");
        }

        String provider = client.provider();
        if (!SandboxTypes.isNamedSandboxBackendType(provider)) {
            throw new IllegalArgumentException(
                    "sandbox: unsupported remote provider \"" + provider + "\"");
        }

        // 应用 provider 的调优默认，让下游只读到非零 TTL/超时字段。端点默认刻意
        // 不在此应用：本构造器也服务具名配置，缺什么必须被告知，而不是拿到
        // 内建 localhost。
        switch (provider) {
            case SandboxTypes.TYPE_CUBE -> EffectiveConfigResolver.applyCubeRuntimeDefaults(cfg);
            case SandboxTypes.TYPE_E2B -> EffectiveConfigResolver.applyE2BRuntimeDefaults(cfg);
            case SandboxTypes.TYPE_DOCKER -> EffectiveConfigResolver.applyDockerRuntimeDefaults(cfg);
            default -> {
            }
        }

        SandboxSessionClient.CreateRequest createRequest =
                buildSessionCreateRequest(provider, cfg);
        // 选定 provider 的模板为空 = 部署配置错误。尽早失败，
        // 让运维拿到清晰消息而不是第一次分配沙箱时的远程 API 错误。
        if (createRequest.templateId() == null || createRequest.templateId().strip().isEmpty()) {
            throw new IllegalArgumentException(
                    "sandbox: " + provider + " template ID is required but not configured");
        }

        // Go 在此用 wrapLangfuseRemoteClient 包 client；Java 的 langfuse 是 no-op
        // 门面，接缝随后续批次（备案）。
        RemoteSessionLifecycle lifecycle = new RemoteSessionLifecycle(
                client, store, checker, createRequest,
                SESSION_LIFECYCLE_CLEANUP_TIMEOUT, configId);

        this.config = cfg;
        this.validator = new ScriptValidator();
        this.client = client;
        this.bindings = store;
        this.checker = checker;
        this.lifecycle = lifecycle;
        this.ephemeral = new RemoteSandbox(client, createRequest);
        this.activeType = provider;

        // per-tenant 管理器每个请求重建，探测会把远程往返加进每个请求。租户显式
        // 配置了后端时，不可达的 provider 必须在首次使用时失败，
        // 而不是替换成别的执行环境。
        if (skipHealthProbe) {
            return;
        }
        try {
            client.health();
        } catch (RuntimeException err) {
            throw SandboxException.internal(
                    "remote sandbox provider unavailable: " + err.getMessage(), err);
        }
    }

    /** 对照 ValidateConfig 的本管理器用到的子集。 */
    private static void validateConfig(EffectiveConfig cfg) {
        switch (cfg.type == null ? "" : cfg.type) {
            case SandboxTypes.TYPE_DOCKER, SandboxTypes.TYPE_CUBE, SandboxTypes.TYPE_E2B,
                    SandboxTypes.TYPE_DISABLED -> {
            }
            default -> throw new IllegalArgumentException("invalid sandbox type");
        }
        if (cfg.defaultTimeoutSec < 0) {
            throw new IllegalArgumentException("timeout cannot be negative");
        }
        if (cfg.maxMemory < 0) {
            throw new IllegalArgumentException("memory limit cannot be negative");
        }
        if (cfg.maxCpu < 0) {
            throw new IllegalArgumentException("CPU limit cannot be negative");
        }
    }

    // ---- SandboxManager ----

    @Override
    public String getType() {
        return activeType;
    }

    /** 对照 TerminalIdleDisconnect：钳到内建区间的终端 idle 断开（秒）。 */
    public long terminalIdleDisconnectSec() {
        return EffectiveConfig.effectiveTerminalIdleDisconnect(config.terminalIdleDisconnectSec);
    }

    /** 对照 GetSandbox：诊断面，返回当前 provider 的一次性 RemoteSandbox。 */
    @Override
    public Sandbox getSandbox() {
        return ephemeral;
    }

    /**
     * 对照 Execute：共享入口（DefaultManager 兼容层、skills 管理器、一次性工具路径）。
     * 先做脚本安全校验，再分派到会话绑定路径（SessionID 非空）或一次性路径。
     */
    @Override
    public SandboxManager.ExecuteResult execute(SandboxManager.ExecuteConfig cfg) {
        synchronized (stateLock) {
            if (closed) {
                throw SandboxException.sandboxDisabled();
            }
        }

        if (cfg == null) {
            throw SandboxException.invalidScript();
        }
        if (!cfg.skipValidation) {
            try {
                runScriptValidation(validator, cfg);
            } catch (SandboxException err) {
                LOG.warning("[sandbox] security validation failed: " + err.getMessage());
                SandboxManager.ExecuteResult result = new SandboxManager.ExecuteResult();
                result.exitCode = -1;
                result.error = err.getMessage();
                result.stderr = "Security validation failed: " + err.getMessage();
                throw err.withPartialResult(result);
            }
        }

        if (cfg.sessionId == null || cfg.sessionId.strip().isEmpty()) {
            return ephemeral.execute(cfg);
        }

        SandboxSessionClient.Handle handle = resolveSession(sessionKeyFromContext(cfg.sessionId));
        ensureSessionWorkspaceDirs(handle, executionOutputDir(cfg));
        return ephemeral.executeOnHandle(handle, cfg);
    }

    /**
     * 对照 Cleanup：标记管理器关闭。会话沙箱不在这里强删——其生命周期权威在
     * 绑定存储里，本副本在关机时回收会泄漏给其他 WeKnora 副本。provider 用
     * 自己的 timeout/pause 策略回收空闲沙箱。幂等。
     */
    @Override
    public void cleanup() {
        synchronized (stateLock) {
            closed = true;
        }
    }

    // ---- 会话生命周期 ----

    /**
     * 对照 DestroySession：删除 sessionID 绑定的远程沙箱（若有）与权威绑定。
     * 幂等：不存在的会话照常成功。
     */
    public void destroySession(long tenantId, String sessionId) {
        if (sessionId == null || sessionId.strip().isEmpty()) {
            return;
        }
        if (remoteDisabled()) {
            return;
        }
        lifecycle.destroy(sandboxKey(tenantId, sessionId));
    }

    /**
     * 对照 InvalidateConfigSandboxes：把本配置拥有的每个会话沙箱标记 stale，
     * 让每个会话下次使用时按配置当前镜像重建；返回标记数量。
     * 与 DestroySession 的镜像维护对应物：这里不拆任何沙箱，标记不可能删掉
     * 正在执行的沙箱——替换发生在会话下一次 resolve。
     */
    public int invalidateConfigSandboxes(long tenantId, String configId) {
        requireRemoteBackend();
        return bindings.invalidateByConfig(tenantId, configId);
    }

    // ---- chat-turn 租约 ----

    /** 对照 BeginSessionTurn：打开会话的 chat-turn 租约。之后的首次 resolve 可重建 stale 镜像。 */
    public void beginSessionTurn(long tenantId, String sessionId) {
        if (!(bindings instanceof SessionSandboxBindingStore.SessionTurnLeaseStore leaser)) {
            return;
        }
        leaser.beginTurn(sandboxKey(tenantId, sessionId));
    }

    /** 对照 EndSessionTurn：关闭 chat-turn 租约（忽略调用方取消——Java 侧无 ctx 可忽略）。 */
    public void endSessionTurn(long tenantId, String sessionId) {
        if (!(bindings instanceof SessionSandboxBindingStore.SessionTurnLeaseStore leaser)) {
            return;
        }
        leaser.endTurn(sandboxKey(tenantId, sessionId));
    }

    // ---- 快照转发（skill 镜像维护专用） ----

    /** 对照 CreateSnapshot：为 sessionID 的活沙箱建 provider 快照（会话执行永不使用）。 */
    public SandboxSessionClient.SnapshotRef createSnapshot(long tenantId, String sessionId,
            String name) {
        requireRemoteBackend();
        SandboxSessionClient.SnapshotManager snapshots = snapshotManager();
        SandboxSessionClient.Handle handle = resolveSession(sandboxKey(tenantId, sessionId));
        return snapshots.createSnapshot(handle.id(), name);
    }

    /** 对照 DeleteSnapshot：skill install 用它放弃孤儿；reaper 用它修剪过期快照。缺失幂等。 */
    public void deleteSnapshot(String snapshotId) {
        requireRemoteBackend();
        snapshotManager().deleteSnapshot(snapshotId);
    }

    /** 对照 ListSnapshots：审计与后续清理用。 */
    public List<SandboxSessionClient.SnapshotRef> listSnapshots(String sandboxId) {
        requireRemoteBackend();
        return snapshotManager().listSnapshots(sandboxId);
    }

    private SandboxSessionClient.SnapshotManager snapshotManager() {
        SandboxSessionClient.SnapshotManager snapshots =
                SandboxSessionClient.snapshotManagerFrom(client);
        if (snapshots == null || !client.capabilities().supportsSnapshots()) {
            throw SandboxException.internal(
                    "sandbox: remote provider does not support snapshots");
        }
        return snapshots;
    }

    // ---- 会话文件 API ----

    /**
     * 对照 EnsureSessionDir：在会话的活沙箱里建 dir；无活绑定时 no-op——skill
     * 框架会在下一次 Execute 时物化目录。
     */
    public void ensureSessionDir(long tenantId, String sessionId, String dir) {
        if (dir == null || dir.strip().isEmpty()) {
            return;
        }
        SandboxSessionClient.Handle handle = lookupSessionHandle(tenantId, sessionId);
        if (handle == null) {
            return;
        }
        try {
            client.makeDir(handle, dir);
        } catch (RuntimeException err) {
            throw SandboxException.internal(
                    "sandbox: ensure session dir " + dir + ": " + err.getMessage(), err);
        }
    }

    /**
     * 对照 WriteSessionInputFile：把持久附件写进会话沙箱，首次调用会 provision。
     * 回落到 Local 时拒绝（写宿主会把附件泄出租户隔离边界）。
     */
    public void writeSessionInputFile(long tenantId, String sessionId, String filePath,
            byte[] content) {
        requireRemoteBackend();
        if (sessionId == null || sessionId.strip().isEmpty()) {
            throw SandboxException.internal("sandbox: session ID required for input staging");
        }
        String clean = SessionSandboxPaths.cleanSessionInputPath(filePath);
        SandboxSessionClient.Handle handle = resolveSession(sandboxKey(tenantId, sessionId));
        try {
            client.makeDir(handle, parentOf(clean));
        } catch (RuntimeException err) {
            throw SandboxException.internal(
                    "sandbox: create input directory: " + err.getMessage(), err);
        }
        try {
            client.writeFile(handle, clean, content);
        } catch (RuntimeException err) {
            throw SandboxException.internal(
                    "sandbox: write session input " + clean + ": " + err.getMessage(), err);
        }
    }

    /** 对照 WriteSessionWorkspaceFile：模型 authored 文件；必须在 /workspace 下且避开 input。 */
    public void writeSessionWorkspaceFile(long tenantId, String sessionId, String filePath,
            byte[] content) {
        writeSessionWorkspaceFiles(tenantId, sessionId,
                List.of(new SessionWorkspaceFile(filePath, content)));
    }

    /** 一次写的单文件形态（对照 SessionWorkspaceFile struct）。 */
    public record SessionWorkspaceFile(String path, byte[] content) {
    }

    /**
     * 对照 WriteSessionWorkspaceFiles：先准备一次会话工作区，再写全部文件。
     * 播种宿主 skill 树不能每条都重跑目录 bootstrap 或遍历父路径。
     */
    public void writeSessionWorkspaceFiles(long tenantId, String sessionId,
            List<SessionWorkspaceFile> files) {
        requireRemoteBackend();
        if (sessionId == null || sessionId.strip().isEmpty()) {
            throw SandboxException.internal("sandbox: session ID required for workspace write");
        }
        if (files == null || files.isEmpty()) {
            return;
        }
        List<SessionWorkspaceFile> items = new ArrayList<>(files.size());
        Set<String> parents = new LinkedHashSet<>();
        for (SessionWorkspaceFile file : files) {
            String clean = SessionSandboxPaths.cleanSessionWorkspaceWritePath(file.path());
            items.add(new SessionWorkspaceFile(clean, file.content()));
            parents.add(parentOf(clean));
        }
        SandboxSessionClient.Handle handle = resolveSession(sandboxKey(tenantId, sessionId));
        ensureSessionWorkspaceDirs(handle, SESSION_OUTPUT_ROOT);
        for (String parent : parents) {
            try {
                client.makeDir(handle, parent);
            } catch (RuntimeException err) {
                throw SandboxException.internal(
                        "sandbox: create workspace directory: " + err.getMessage(), err);
            }
        }
        for (SessionWorkspaceFile file : items) {
            try {
                client.writeFile(handle, file.path(), file.content());
            } catch (RuntimeException err) {
                throw SandboxException.internal(
                        "sandbox: write session file " + file.path() + ": " + err.getMessage(), err);
            }
        }
    }

    /** 对照 RemoveSessionInputPath：删除已播种附件；无活沙箱 no-op、绝不 provision。 */
    public void removeSessionInputPath(long tenantId, String sessionId, String targetPath) {
        requireRemoteBackend();
        String clean = SessionSandboxPaths.cleanSessionInputPath(targetPath);
        SandboxSessionClient.Handle handle = lookupSessionHandle(tenantId, sessionId);
        if (handle == null) {
            return;
        }
        try {
            client.remove(handle, clean);
        } catch (RuntimeException err) {
            throw SandboxException.internal(
                    "sandbox: remove session input " + clean + ": " + err.getMessage(), err);
        }
    }

    /**
     * 对照 ListSessionFiles：递归列出会话活沙箱里 dir 下的文件。
     * 会话无绑定沙箱时返回 null（不报错），调用方把"无沙箱"与"空输出"同等对待。
     */
    public List<SandboxSessionClient.DirEntry> listSessionFiles(long tenantId, String sessionId,
            String dir) {
        if (dir == null || dir.strip().isEmpty()) {
            throw SandboxException.internal("sandbox: dir required for ListSessionFiles");
        }
        SandboxSessionClient.Handle handle = lookupSessionHandle(tenantId, sessionId);
        if (handle == null) {
            return null;
        }
        return listFilesRecursive(handle, dir);
    }

    /**
     * 对照 StatSessionFile：不下载内容的单文件元数据。无绑定沙箱时报错——
     * 调用方手里已有 ListSessionFiles 给的路径，不应与 reaper/destroy 竞速。
     */
    public SandboxSessionClient.StatEntry statSessionFile(long tenantId, String sessionId,
            String filePath) {
        if (filePath == null || filePath.strip().isEmpty()) {
            throw SandboxException.internal("sandbox: path required for StatSessionFile");
        }
        SandboxSessionClient.Handle handle = lookupSessionHandle(tenantId, sessionId);
        if (handle == null) {
            throw SandboxException.internal(
                    "sandbox: no live sandbox for session " + sessionId);
        }
        return client.stat(handle, filePath);
    }

    /** 对照 ReadSessionFile：从会话活沙箱下载文件。无绑定时报错，理由同 StatSessionFile。 */
    public byte[] readSessionFile(long tenantId, String sessionId, String filePath) {
        if (filePath == null || filePath.strip().isEmpty()) {
            throw SandboxException.internal("sandbox: path required for ReadSessionFile");
        }
        SandboxSessionClient.Handle handle = lookupSessionHandle(tenantId, sessionId);
        if (handle == null) {
            throw SandboxException.internal(
                    "sandbox: no live sandbox for session " + sessionId);
        }
        return client.readFile(handle, filePath);
    }

    /**
     * 对照 WriteSessionFile：往会话活沙箱写 install/maintenance 文件。刻意窄于一般
     * 远程写：只接受 tenant skills 镜像根（普通附件必须走 WriteSessionInputFile
     * 及其 /workspace/input 守卫）。
     */
    public void writeSessionFile(long tenantId, String sessionId, String filePath,
            byte[] content) {
        requireRemoteBackend();
        if (sessionId == null || sessionId.strip().isEmpty()) {
            throw SandboxException.internal("sandbox: session ID required for file staging");
        }
        String clean = SessionSandboxPaths.clean(filePath == null ? "" : filePath.strip());
        if (!clean.equals(SessionSandboxPaths.SKILLS_IMAGE_ROOT)
                && !clean.startsWith(SessionSandboxPaths.SKILLS_IMAGE_ROOT + "/")) {
            throw SandboxException.internal(String.format(
                    "sandbox: install file path %s is outside %s",
                    SessionSandboxPaths.quoteGo(filePath), SessionSandboxPaths.SKILLS_IMAGE_ROOT));
        }
        // Go 在此给 ctx 打 maintenance filesystem 标记（文件调用以 root 执行）；
        // Java 侧该选择发生在适配器内部（接缝备案，见类注释）。
        SandboxSessionClient.Handle handle = resolveSession(sandboxKey(tenantId, sessionId));
        // resetSkillDir 已经 mkdir -p 过这个目录；Cube 的 MakeDir 会把已存在目录报成
        // 错误，契约要求适配层抹平（见 SandboxSessionClient.makeDir），否则
        // SKILL.md 的播种会中止。
        try {
            client.makeDir(handle, parentOf(clean));
        } catch (RuntimeException err) {
            throw SandboxException.internal(
                    "sandbox: create install directory: " + err.getMessage(), err);
        }
        try {
            client.writeFile(handle, clean, content);
        } catch (RuntimeException err) {
            throw SandboxException.internal(
                    "sandbox: write install file " + clean + ": " + err.getMessage(), err);
        }
    }

    // ---- 会话 shell API ----

    /** 对照 ShellExecOptions：每次 shell 调用的旋钮。 */
    public record ShellExecOptions(
            /** stdout/stderr 观察钩子；不得持有字节。 */
            SandboxSessionClient.ExecRequest.OutputListener onOutput,
            String workDir,
            Duration timeout,
            Map<String, String> env,
            /** 允许安装器调用进入 skills 镜像根（仅限安装路径；模型工具绝不可设）。 */
            boolean allowSkillsRoot,
            /** 强制 root 并选择维护 bootstrap：只准备 WorkDir，不要求 input/output。 */
            boolean asRoot) {

        public static ShellExecOptions of(String workDir, Duration timeout,
                Map<String, String> env) {
            return new ShellExecOptions(null, workDir, timeout, env, false, false);
        }
    }

    /** 对照 ExecShellCommand：会话持久沙箱里的 shell 一行命令（shell_exec 工具契约）。 */
    public SandboxManager.ExecuteResult execShellCommand(long tenantId, String sessionId,
            String command, String workDir, Duration timeout, Map<String, String> env) {
        return execShellCommandWithOptions(tenantId, sessionId, command,
                ShellExecOptions.of(workDir, timeout, env));
    }

    /**
     * 对照 ExecShellCommandWithOptions：带安装选项的 shell。明确拒绝回落——
     * 特权安装调用也绝不逃到 WeKnora 宿主机上。
     */
    public SandboxManager.ExecuteResult execShellCommandWithOptions(long tenantId,
            String sessionId, String command, ShellExecOptions opts) {
        if (remoteDisabled()) {
            throw SandboxException.internal(String.format(
                    "sandbox: shell_exec requires the remote sandbox provider (current mode: %s)",
                    getType()));
        }
        if (sessionId == null || sessionId.strip().isEmpty()) {
            throw SandboxException.internal("sandbox: session_id required for ExecShellCommand");
        }
        if (command == null || command.strip().isEmpty()) {
            throw SandboxException.internal("sandbox: command required for ExecShellCommand");
        }
        Duration timeout = opts.timeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(config.defaultTimeoutSec);
        }
        if (timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(EffectiveConfig.DEFAULT_TIMEOUT_SEC);
        }

        String workDir = opts.workDir() == null ? "" : opts.workDir().strip();
        if (workDir.isEmpty()) {
            workDir = SESSION_WORKSPACE_ROOT;
        }
        workDir = SessionSandboxPaths.cleanSessionWorkDir(workDir, opts.allowSkillsRoot());
        SandboxSessionClient.Handle handle = resolveSession(sandboxKey(tenantId, sessionId));
        String user = SandboxSessionClient.ExecRequest.DEFAULT_SANDBOX_EXEC_USER;
        if (opts.asRoot()) {
            // 安装器拥有 skill 目录，不依赖可写的会话工作区（快照前会清空）。
            prepareSessionDirs(handle, user, workDir);
        } else {
            prepareSessionDirs(handle, user, SESSION_INPUT_ROOT, SESSION_OUTPUT_ROOT, workDir);
        }

        Instant start = Instant.now();
        SandboxSessionClient.ExecRequest request = new SandboxSessionClient.ExecRequest(
                opts.onOutput(), command, List.of(), true, null, opts.env(), workDir, user,
                timeout);
        SandboxSessionClient.ExecResult execResult;
        try {
            execResult = client.exec(handle, request);
        } catch (RemoteError err) {
            return RemoteSandbox.remoteExecuteResult(null, err,
                    Duration.between(start, Instant.now()));
        }
        return RemoteSandbox.remoteExecuteResult(execResult, null,
                Duration.between(start, Instant.now()));
    }

    // ---- internal helpers ----

    /** 对照 ensureSessionWorkspaceDirs：以执行命令的同一账户准备共享执行布局。 */
    private void ensureSessionWorkspaceDirs(SandboxSessionClient.Handle handle, String outputDir) {
        prepareSessionDirs(handle,
                SandboxSessionClient.ExecRequest.DEFAULT_SANDBOX_EXEC_USER,
                SESSION_INPUT_ROOT, outputDir);
    }

    /**
     * 对照 prepareSessionDirs：绝不改名或删除既有数据来"修"访问权。坏镜像或
     * 不可访问目录需要显式诊断，而不是一个看似空的替换目录和丢失的附件/产物。
     */
    private void prepareSessionDirs(SandboxSessionClient.Handle handle, String user,
            String... dirs) {
        SandboxSessionClient.ExecRequest request = new SandboxSessionClient.ExecRequest(
                null,
                SessionSandboxPaths.workspaceBootstrapCommand(dirs),
                List.of(), true, null, null, null, user,
                SESSION_ARTIFACT_DIR_BOOTSTRAP_TIMEOUT);
        SandboxSessionClient.ExecResult result;
        try {
            result = client.exec(handle, request);
        } catch (RuntimeException err) {
            throw SandboxException.internal(String.format(
                    "sandbox: workspace preparation failed for user %s: %s; command was not started",
                    user, err.getMessage()), err);
        }
        if (result == null || result.exitCode() != 0 || result.killed()) {
            String detail = "provider returned no result";
            if (result != null) {
                detail = String.format("exit=%d killed=%b stderr=%s",
                        result.exitCode(), result.killed(),
                        result.stderr() == null ? "" : result.stderr().strip());
            }
            throw SandboxException.internal(String.format(
                    "sandbox: workspace preparation failed for user %s at %s: %s. "
                            + "Command was not started; existing files were preserved. "
                            + "Use an accessible directory under /workspace. If /workspace itself is inaccessible, "
                            + "the sandbox image/template must provide /workspace owned by %s. "
                            + "Switching tools or retrying the same operation will not change filesystem permissions",
                    user, String.join(", ", dirs), detail,
                    SandboxSessionClient.ExecRequest.DEFAULT_SANDBOX_EXEC_USER));
        }
    }

    /**
     * 对照 executionOutputDir：为本次 Execute 解析产物目录。优先 cfg.Env 的
     * WEKNORA_SKILL_OUTPUT_DIR（仍在 /workspace 下时），否则回落 SessionOutputRoot。
     */
    static String executionOutputDir(SandboxManager.ExecuteConfig cfg) {
        if (cfg != null && cfg.env != null) {
            String dir = cfg.env.get(SKILL_OUTPUT_ENV_VAR);
            if (dir != null && !dir.strip().isEmpty()) {
                String clean = SessionSandboxPaths.validatedSessionOutputDir(dir);
                if (clean != null) {
                    return clean;
                }
            }
        }
        return SESSION_OUTPUT_ROOT;
    }

    /**
     * 对照 withWorkspaceEnvDefaults：把工作区路径盖进沙箱自身环境。
     * 租户配置的值优先——把产物目录指到别处的运维不该被这里覆写。
     */
    static Map<String, String> withWorkspaceEnvDefaults(Map<String, String> env) {
        Map<String, String> result = env == null
                ? new LinkedHashMap<>(2)
                : env;
        String output = result.get(SKILL_OUTPUT_ENV_VAR);
        if (output == null || output.strip().isEmpty()) {
            result.put(SKILL_OUTPUT_ENV_VAR, SESSION_OUTPUT_ROOT);
        }
        String input = result.get(SESSION_INPUT_ENV_VAR);
        if (input == null || input.strip().isEmpty()) {
            result.put(SESSION_INPUT_ENV_VAR, SESSION_INPUT_ROOT);
        }
        return result;
    }

    /** 对照 resolveSession：解析（或懒创建）sessionID 绑定的远程沙箱。持久路径专用。 */
    private SandboxSessionClient.Handle resolveSession(
            SessionSandboxBindingStore.SessionSandboxKey key) {
        return lifecycle.resolve(key);
    }

    /**
     * 对照 lookupSessionHandle：读权威绑定，存在且 provider 匹配时连接远程沙箱，
     * 绝不分配。产物/播种路径（必须永不 provision）使用。
     * 无绑定或 provider 不匹配返回 null（对照 (nil,false,nil)）。
     */
    private SandboxSessionClient.Handle lookupSessionHandle(long tenantId, String sessionId) {
        if (remoteDisabled() || sessionId == null || sessionId.strip().isEmpty()) {
            return null;
        }
        SessionSandboxBindingStore.SessionSandboxKey key = sandboxKey(tenantId, sessionId);
        SessionSandboxBinding binding;
        try {
            binding = bindings.get(key);
        } catch (RuntimeException err) {
            throw SandboxException.internal("sandbox: read session binding: " + err.getMessage(), err);
        }
        if (binding == null || !binding.provider.equals(client.provider())) {
            return null;
        }
        SandboxSessionClient.Handle handle;
        try {
            handle = client.connect(new SandboxSessionClient.ConnectRequest(
                    binding.sandboxId, binding.trafficAccessToken));
        } catch (RemoteError err) {
            if (RemoteSessionLifecycle.canReplaceRemoteBinding(err)) {
                return null;
            }
            throw SandboxException.internal(
                    "sandbox: connect session sandbox: " + err.getMessage(), err);
        }
        if (handle == null || !handle.id().equals(binding.sandboxId)
                || !handle.provider().equals(client.provider())) {
            throw SandboxException.internal("sandbox: remote handle does not match binding");
        }
        RemoteSessionLifecycle.persistInboundToken(bindings, key, binding, handle);
        return handle;
    }

    private List<SandboxSessionClient.DirEntry> listFilesRecursive(
            SandboxSessionClient.Handle handle, String dir) {
        SandboxSessionClient.StatEntry stat;
        try {
            stat = client.stat(handle, dir);
        } catch (RemoteError err) {
            if (RemoteSessionLifecycle.isRemoteNotFound(err)) {
                return null;
            }
            throw SandboxException.internal("sandbox: stat " + dir + ": " + err.getMessage(), err);
        }
        if (stat == null) {
            return null;
        }

        List<SandboxSessionClient.DirEntry> files = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        stack.add(dir);
        while (!stack.isEmpty()) {
            String cur = stack.remove(stack.size() - 1);
            List<SandboxSessionClient.DirEntry> entries;
            try {
                entries = client.listDir(handle, cur);
            } catch (RuntimeException err) {
                throw err;
            }
            for (SandboxSessionClient.DirEntry entry : entries) {
                String path = entry.path() == null || entry.path().isEmpty()
                        ? SessionSandboxPaths.join(cur, entry.name())
                        : entry.path();
                if (entry.type() == SandboxSessionClient.DirEntryType.DIR) {
                    stack.add(path);
                    continue;
                }
                if (entry.type() == SandboxSessionClient.DirEntryType.FILE) {
                    files.add(new SandboxSessionClient.DirEntry(entry.name(), path,
                            entry.type(), entry.size(), entry.modTime()));
                }
            }
        }
        return files;
    }

    /**
     * 对照 sessionKey：解析租户作用域的绑定键。tenantId 来自调用方显式传参
     * （Go 从 ctx 的 SandboxTenantIDFromContext 取——会话属主租户；共享 agent 场景
     * 必须传属主而非请求租户，否则会话删除时回收不到 MicroVM）。0 = 调用方错误，
     * 绑定必须在 Redis 里全局可寻址。
     */
    private SessionSandboxBindingStore.SessionSandboxKey sandboxKey(long tenantId,
            String sessionId) {
        if (tenantId == 0) {
            throw SandboxException.internal("sandbox: tenant ID missing from context");
        }
        SessionSandboxBindingStore.SessionSandboxKey key = new SessionSandboxBindingStore
                .SessionSandboxKey(tenantId, sessionId == null ? "" : sessionId.strip());
        key.validate();
        return key;
    }

    /**
     * Execute 路径的租户来源：对照 Go 的 SandboxTenantIDFromContext（ctx）。
     * Java 用 TenantContext（ThreadLocal）。已知接缝：Go 的 WithSandboxTenantID
     * （共享 agent 用会话属主租户覆写请求租户）在 Java 尚无对应物——
     * 共享 agent 沙箱随共享 agent 批次接线；接线前退回请求租户（非借用路径上
     * 二者相同）。
     */
    private SessionSandboxBindingStore.SessionSandboxKey sessionKeyFromContext(
            String sessionId) {
        Long tenantId = com.ragagent.common.context.TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            throw SandboxException.internal("sandbox: tenant ID missing from context");
        }
        return sandboxKey(tenantId, sessionId);
    }

    private boolean remoteDisabled() {
        synchronized (stateLock) {
            return closed;
        }
    }

    private void requireRemoteBackend() {
        synchronized (stateLock) {
            if (closed) {
                throw SandboxException.sandboxDisabled();
            }
        }
    }

    /** path.Dir 的等价实现（父目录；Go 的 path.Dir 在无斜杠时返回 "."）。 */
    private static String parentOf(String clean) {
        int slash = clean.lastIndexOf('/');
        if (slash < 0) {
            return ".";
        }
        if (slash == 0) {
            return "/";
        }
        return clean.substring(0, slash);
    }

    // ---- 构造期投影 ----

    /**
     * 对照 buildSessionCreateRequest：把 Config 投影成 provider 中立的创建请求。
     * metadata 块由生命周期协调器按会话填充；env vars 原样传播。provider 参数
     * （来自 RemoteSandboxClient.Provider()）是身份的权威来源——Cube 和 E2B
     * 绝不读对方的模板或 TTL。
     */
    static SandboxSessionClient.CreateRequest buildSessionCreateRequest(String provider,
            EffectiveConfig cfg) {
        Map<String, String> envVars = withWorkspaceEnvDefaults(
                cfg.envVars == null ? new LinkedHashMap<>() : new LinkedHashMap<>(cfg.envVars));
        RemoteNetworkPolicy network = cfg.network;
        switch (provider) {
            case SandboxTypes.TYPE_CUBE: {
                long ttl = cfg.cubeSandboxTtlSec;
                if (ttl <= 0) {
                    ttl = EffectiveConfig.DEFAULT_CUBE_SANDBOX_TTL_SEC;
                }
                return new SandboxSessionClient.CreateRequest(
                        cfg.cubeTemplate,
                        new SandboxSessionClient.TimeoutPolicy(
                                SandboxSessionClient.TimeoutMode.EXPLICIT,
                                Duration.ofSeconds(ttl),
                                SandboxSessionClient.TimeoutAction.PAUSE, true),
                        null, envVars, network, null);
            }
            case SandboxTypes.TYPE_E2B: {
                long ttl = cfg.e2bSandboxTtlSec;
                if (ttl <= 0) {
                    ttl = EffectiveConfig.DEFAULT_E2B_SANDBOX_TTL_SEC;
                }
                return new SandboxSessionClient.CreateRequest(
                        cfg.e2bTemplate,
                        new SandboxSessionClient.TimeoutPolicy(
                                SandboxSessionClient.TimeoutMode.EXPLICIT,
                                Duration.ofSeconds(ttl),
                                SandboxSessionClient.TimeoutAction.PAUSE, true),
                        null, envVars, network, null);
            }
            case SandboxTypes.TYPE_DOCKER: {
                long ttl = cfg.dockerIdleTtlSec;
                if (ttl <= 0) {
                    ttl = EffectiveConfig.DEFAULT_DOCKER_IDLE_TTL_SEC;
                }
                // Docker 只能执行总出站开关（见 DockerRemoteClient.networkMode）；
                // allow/deny 列表在保存时被拒，到不了这里。
                return new SandboxSessionClient.CreateRequest(
                        cfg.dockerImage,
                        new SandboxSessionClient.TimeoutPolicy(
                                SandboxSessionClient.TimeoutMode.EXPLICIT,
                                Duration.ofSeconds(ttl),
                                // Docker 的 pause 让容器内存继续驻留宿主，暂停被遗弃的
                                // 沙箱回收不到任何东西。空闲容器直接删；生命周期像
                                // 处理 provider 回收的沙箱一样重绑会话。
                                SandboxSessionClient.TimeoutAction.KILL, false),
                        null, envVars, network, null);
            }
            default:
                throw new IllegalArgumentException(String.format(
                        "sandbox: unsupported remote provider \"%s\" for session create request",
                        provider));
        }
    }

    /**
     * 对照 runScriptValidation（manager.go L112-170）：DefaultManager 与
     * SessionBoundManager 共享的执行前安全检查。校验器是 sandbox 包的确定性纯逻辑，
     * 已整体移植为 {@link ScriptValidator}。
     */
    static void runScriptValidation(ScriptValidator validator, SandboxManager.ExecuteConfig config) {
        if (validator == null || config == null) {
            return;
        }

        String scriptContent = config.scriptContent;
        if ((scriptContent == null || scriptContent.isEmpty())
                && config.script != null && !config.script.isEmpty()) {
            byte[] content;
            try {
                content = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(config.script));
            } catch (java.io.IOException err) {
                throw SandboxException.internal(
                        "failed to read script for validation: " + err.getMessage(), err);
            }
            scriptContent = new String(content, java.nio.charset.StandardCharsets.UTF_8);
        }

        if (scriptContent != null && !scriptContent.isEmpty()) {
            ScriptValidator.ValidationResult result = validator.validateScript(scriptContent);
            if (!result.valid) {
                for (ScriptValidator.ValidationError verr : result.errors) {
                    LOG.warning("[sandbox] Validation error: " + verr.getMessage());
                }
                if (!result.errors.isEmpty()) {
                    throw SandboxException.securityViolation(result.errors.get(0).getMessage());
                }
                throw SandboxException.securityViolation(null);
            }
        }

        if (config.args != null && !config.args.isEmpty()) {
            ScriptValidator.ValidationResult result = validator.validateArgs(config.args);
            if (!result.valid) {
                for (ScriptValidator.ValidationError verr : result.errors) {
                    LOG.warning("[sandbox] Arg validation error: " + verr.getMessage());
                }
                if (!result.errors.isEmpty()) {
                    throw SandboxException.argInjection(result.errors.get(0).getMessage());
                }
                throw SandboxException.argInjection(null);
            }
        }

        if (config.stdin != null && !config.stdin.isEmpty()) {
            ScriptValidator.ValidationResult result = validator.validateStdin(config.stdin);
            if (!result.valid) {
                for (ScriptValidator.ValidationError verr : result.errors) {
                    LOG.warning("[sandbox] Stdin validation error: " + verr.getMessage());
                }
                if (!result.errors.isEmpty()) {
                    throw SandboxException.stdinInjection(result.errors.get(0).getMessage());
                }
                throw SandboxException.stdinInjection(null);
            }
        }
    }

    // ---- 兼容类型（对照 PermissiveSessionExistenceChecker） ----

    /**
     * 接受所有会话的存在性检查。仅在"自己的 DestroySession 是唯一的会话删除路径"
     * 的部署里安全（单进程内存绑定存储）；Redis 权威部署必须注入真正查询
     * 会话仓储的检查器。
     */
    public static final class PermissiveSessionExistenceChecker
            implements RemoteSessionLifecycle.SessionExistenceChecker {

        @Override
        public boolean sessionExists(SessionSandboxBindingStore.SessionSandboxKey key) {
            return true;
        }
    }
}
