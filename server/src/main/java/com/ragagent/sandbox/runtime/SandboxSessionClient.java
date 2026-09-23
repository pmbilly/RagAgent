package com.ragagent.sandbox.runtime;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 会话执行面 provider 契约（对照 Go {@code sandbox.RemoteSandboxClient}，
 * internal/sandbox/remote_client.go L451-519 全文；请求/结果类型对照同文件
 * L30-444 与 remote_sandbox.go）。
 *
 * <p>对照关系与命名：Java 侧以 {@code SandboxSessionClient} 命名以区别于已有的
 * 控制面切片 {@link ConfigSandboxClient}（Go 里两者同源于 RemoteSandboxClient，
 * Java 按 Go 的消费面拆成两个接口——配置 CRUD/盘点用窄切片，会话执行用本契约）。
 * 终端面（RemoteTerminalManager）不在本契约：docker 无 PTY、cube/e2b 的 SDK
 * 流传输属 W5δ 批（见 HANDOFF §0.0 provider-XDEP 清单）。</p>
 *
 * <p><b>并发</b>：实现必须线程安全（Go 注释原文：implementations MUST be safe
 * for concurrent use）。<b>取消</b>：每个方法必须尊重调用方的超时/中断；超时抛
 * {@link RemoteError}（kind=timeout）。</p>
 */
public interface SandboxSessionClient {

    /** 对照 Provider()：后端标识（绑定 schema 用它检测 provider 漂移）。 */
    String provider();

    /** 对照 Capabilities()：静态能力集，Health 成功前可安全调用。 */
    Capabilities capabilities();

    /** 对照 Health()：探测控制面；可达返回，不可达抛 RemoteError。 */
    void health();

    // ── 生命周期 ─────────────────────────────────────────────────────────

    /**
     * 对照 Create()：新建沙箱并返回不透明句柄。句柄归调用方所有；
     * 最终必须 Delete。
     */
    Handle create(CreateRequest req);

    /**
     * 对照 Connect()：重连已运行沙箱。不支持重连的实现必须抛
     * {@link RemoteError}（kind=unsupported）并声明 capabilities.supportsReconnect=false。
     */
    Handle connect(ConnectRequest req);

    /** 对照 Get()：按 ID 取单沙箱摘要；沙箱已消失返回 null（对照 nil+NotFound 折叠）。 */
    Summary get(String sandboxId);

    /** 对照 List()：枚举本客户端可见的沙箱，可选过滤。 */
    List<Summary> list(ListFilter filter);

    /**
     * 对照 Delete()：销毁沙箱。删除不存在的沙箱抛
     * {@link RemoteError}（kind=notFound）；调用方通常视为成功。
     */
    void delete(String sandboxId);

    // ── 执行 ─────────────────────────────────────────────────────────────

    /** 对照 Exec()：沙箱内跑一条命令，见 {@link ExecRequest} 的 Shell/Args 契约。 */
    ExecResult exec(Handle handle, ExecRequest req);

    // ── 文件面 ───────────────────────────────────────────────────────────

    void writeFile(Handle handle, String path, byte[] content);

    byte[] readFile(Handle handle, String path);

    List<DirEntry> listDir(Handle handle, String path);

    /**
     * 对照 MakeDir()：创建 path（含父目录）。已存在的目录是成功——envd 的
     * MakeDir 不是 mkdir -p，适配层必须抹平（否则向同一目录写第二个文件、或
     * resetSkillDir 后播种 SKILL.md 都会失败）。
     */
    void makeDir(Handle handle, String path);

    void remove(Handle handle, String path);

    StatEntry stat(Handle handle, String path);

    // ── 契约类型 ─────────────────────────────────────────────────────────

    /**
     * 对照 RemoteSandboxHandle：provider 签发的不透明活沙箱引用。
     * 适配器可在具体实现里包住 SDK 对象；管理器只读本接口暴露的稳定标识。
     */
    interface Handle {
        /** provider 范围内的沙箱标识。 */
        String id();

        /** 签发该句柄的后端。 */
        String provider();

        /** 创建时登记的 metadata 袋；provider 不支持找回时为 null。 */
        Map<String, String> metadata();
    }

    /** 对照 RemoteConnectRequest。 */
    record ConnectRequest(String sandboxId, String trafficAccessToken) {
    }

    /** 对照 RemoteTimeoutMode：server=用 provider 默认；explicit=用 value 逐字。 */
    enum TimeoutMode { SERVER_DEFAULT, EXPLICIT }

    /** 对照 RemoteTimeoutAction。 */
    enum TimeoutAction { PAUSE, KILL }

    /** 对照 RemoteTimeoutPolicy：provider 中立的空闲超时配置。 */
    record TimeoutPolicy(TimeoutMode mode, java.time.Duration value, TimeoutAction action,
                         boolean autoResume) {

        public static TimeoutPolicy serverDefault(TimeoutAction action) {
            return new TimeoutPolicy(TimeoutMode.SERVER_DEFAULT, null, action, false);
        }
    }

    /** 对照 RemoteVolumeMount：创建时挂入沙箱的卷。 */
    record VolumeMount(String name, String path) {
    }

    /** 对照 RemoteNetworkPolicy 的中立承载（Java 侧已有 {@link RemoteNetworkPolicy}）。 */
    // （网络策略类型复用 runtime 包既有的 RemoteNetworkPolicy，不重复定义）

    /** 对照 RemoteCreateRequest：新建沙箱的中立参数。 */
    record CreateRequest(
            String templateId,
            TimeoutPolicy timeout,
            Map<String, String> metadata,
            Map<String, String> envVars,
            RemoteNetworkPolicy network,
            List<VolumeMount> volumeMounts) {
    }

    /** 对照 RemoteExecRequest：单条命令调用。 */
    record ExecRequest(
            /** 对照 OnOutput：运行中的 stdout/stderr 观察钩子；调用方不得持有字节。 */
            OutputListener onOutput,
            String command,
            List<String> args,
            boolean shell,
            String stdin,
            Map<String, String> env,
            String workDir,
            String user,
            java.time.Duration timeout) {

        /** 对照 OnOutput func(stream string, chunk []byte)。 */
        @FunctionalInterface
        public interface OutputListener {
            void onOutput(String stream, byte[] chunk);
        }

        /** 对照 DefaultSandboxExecUser：WeKnora 沙箱脚本的默认执行账户是 root。 */
        public static final String DEFAULT_SANDBOX_EXEC_USER = "root";
    }

    /** 对照 RemoteExecResult。 */
    record ExecResult(String stdout, String stderr, int exitCode,
                      java.time.Duration duration, boolean killed) {
    }

    /** 对照 RemoteDirEntryType。 */
    enum DirEntryType { FILE, DIR, OTHER }

    /** 对照 RemoteDirEntry。 */
    record DirEntry(String name, String path, DirEntryType type, long size, OffsetDateTime modTime) {
    }

    /** 对照 RemoteStatEntry。 */
    record StatEntry(String path, DirEntryType type, long size, OffsetDateTime modTime) {
    }

    /** 对照 RemoteSandboxSummary（与 {@link ConfigSandboxClient.RemoteSandboxSummary} 同形；执行面独立演化）。 */
    record Summary(String id, String templateId, String state, String rawState,
                   Map<String, String> metadata, OffsetDateTime startedAt, OffsetDateTime endAt) {

        /** 对照 RemoteStateRunning 等常量（normalized state）。 */
        public static final String STATE_RUNNING = "running";
        public static final String STATE_PAUSED = "paused";
        public static final String STATE_TRANSITIONING = "transitioning";
        public static final String STATE_TERMINAL = "terminal";
        public static final String STATE_UNKNOWN = "unknown";
    }

    /** 对照 RemoteListFilter（与 {@link ConfigSandboxClient.RemoteListFilter} 同形）。 */
    record ListFilter(Map<String, String> metadata, List<String> states) {
    }

    /**
     * 对照 RemoteSandboxCapabilities：广告可选操作的原生支持情况。
     * SessionBoundManager 据此跳过 provider 特定路径；缺 metadata/list 能力时走
     * 次优但正确的路径（依赖绑定存储而非扫 provider metadata）。
     * supportsReconnect 是持久会话生命周期管理的必要条件。
     */
    record Capabilities(
            boolean supportsReconnect,
            boolean supportsMetadata,
            boolean supportsListSandboxes,
            boolean supportsPauseResume,
            boolean supportsTimeoutRefresh,
            boolean supportsFilesystemEnumeration,
            boolean supportsSnapshots,
            boolean supportsVolumes,
            boolean supportsTerminals) {
    }

    /**
     * 对照 RemoteSnapshotManager：可选能力，仅 skill install/remove 路径使用，
     * 会话执行永不触达。快照 ID 可直接回填 {@link CreateRequest#templateId}——
     * Cube/E2B 存为模板，Docker 存为本地镜像 tag（这正是技能镜像的机理）。
     */
    interface SnapshotManager {
        /** 对照 CreateSnapshot：快照活沙箱；空 name 交给 provider 生成；provider 会在快照期间暂停沙箱。 */
        SnapshotRef createSnapshot(String sandboxId, String name);

        /** 对照 DeleteSnapshot：缺失的快照不是错误（幂等）。 */
        void deleteSnapshot(String snapshotId);

        /** 对照 ListSnapshots：空 sandboxId 列全部。 */
        List<SnapshotRef> listSnapshots(String sandboxId);
    }

    /** 对照 RemoteSnapshotRef。 */
    record SnapshotRef(String id, List<String> names) {
    }

    /** 对照 SnapshotManagerFrom：类型断言与能力广告必须同时成立。 */
    static SnapshotManager snapshotManagerFrom(SandboxSessionClient client) {
        if (client == null || !(client instanceof SnapshotManager mgr)) {
            return null;
        }
        if (!client.capabilities().supportsSnapshots()) {
            return null;
        }
        return mgr;
    }

    /** 对照 InboundTokenOf：句柄携带的入站流量凭据，provider 没有则空串。 */
    static String inboundTokenOf(Handle handle) {
        return handle instanceof InboundTokenCarrier carrier ? carrier.trafficAccessToken() : "";
    }

    /** 对照 RemoteInboundTokenCarrier：Cube/E2B 实现，Docker 不实现。 */
    interface InboundTokenCarrier {
        String trafficAccessToken();
    }
}
