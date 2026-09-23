package com.ragagent.sandbox.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Docker 后端的会话执行面适配器（对照 Go {@code DockerRemoteClient}，
 * internal/sandbox/docker_remote_client.go 全文 ~1320 行）。
 *
 * <p>一个沙箱 = 一个长活容器：PID 1 sleep，脚本/shell/文件操作全部 exec 进该容器，
 * 直到会话结束或空闲清扫回收。文件操作走 exec（显式账户 root、容器内 timeout(1)、
 * 活动标记刷新）；Archive 端点只有 HEAD /archive 一处（空闲清扫读活动标记 mtime）。</p>
 *
 * <h2>与 Go SDK 的语义差异（docker-java 3.4.0，承接 {@link DockerEngineClients} 的备案）</h2>
 * <ul>
 *   <li><b>短 RPC 超时</b>：Go 用 withDockerRPCTimeout 装饰整个 dockerEngineAPI；
 *       Java 侧 {@link DockerEngineClients#rpc} 按次包装（虚拟线程 + 限时，阻塞 I/O
 *       可中断 = Go ctx 取消）。长调用（pull 30min 预算 / commit / image remove /
 *       exec attach 流）不包装，与 Go 逐条对应。</li>
 *   <li><b>exec stdin</b>：Go 在 attach 后 Write + CloseWrite；docker-java 的
 *       ExecStartCmd.withStdIn 在拷贝完输入流后半关闭写端——同一契约，由
 *       {@code DockerJavaEngine.execAttach} 封装。stdin 为空时 attachStdin=false。</li>
 *   <li><b>exec 流分帧</b>：Go stdcopy.StdCopy 按 8 字节头分流 stdout/stderr；
 *       docker-java 的 Frame.getStreamType 已做同一分帧（1=stdout 2=stderr），
 *       逐帧喂 {@link SandboxSessionClient.ExecRequest.OutputListener onOutput}。</li>
 *   <li><b>Go string 是任意字节，Java String 不是</b>：WriteFile/ReadFile 走内部
 *       byte[] 通道（{@link #runExec} 的 stdinBytes / stdout 原始字节），保证二进制
 *       文件保真；公开 {@link #exec} 的 String stdin 按 UTF-8 编码（文本域）。
 *       Go 的 {@code string(content)} / {@code []byte(result.Stdout)} 在 Go 侧是
 *       字节透明直通，Java 侧若同样经 String 会把非法 UTF-8 序列替换损坏。</li>
 *   <li><b>Get 的 NotFound 折叠</b>：Go 适配层返回 dockerError(NotFound)；Java 契约
 *       （{@link SandboxSessionClient#get}）把"沙箱已消失"折叠为 null，其余错误照抛。</li>
 *   <li><b>commit 的 Changes</b>：Go 用 {@code Changes=["LABEL k=v"...]}；docker-java
 *       的 CommitCmd 无 changes，daemon 对请求 config 的 Labels 做 Dockerfile LABEL
 *       语义合并——等价，见 DockerEngineClients.containerCommit 注释。</li>
 *   <li><b>ContainerStatPath</b>：docker-java 无 stat-path 命令，DockerJavaEngine 用
 *       原始 HEAD /archive + base64 X-Docker-Container-Path-Stat 头补齐。</li>
 *   <li><b>maintenance 账户</b>：Go remoteFileUser(ctx) 有维护模式分支（ctx 标记 →
 *       root）；Java 无等价传递面，文件操作恒为 DefaultSandboxExecUser（root），
 *       备案差异。</li>
 * </ul>
 *
 * <p>metadata 键（tenant/session/config id）由调用方从
 * {@link ConfigSandboxClient.ConfigSandboxes} 常量携带；本类只加盖所有权标记
 * （{@link DockerEngineClients#DOCKER_MANAGED_LABEL}）与空闲 TTL 标记。</p>
 */
public final class DockerSandboxClient implements SandboxSessionClient,
        SandboxSessionClient.SnapshotManager {

    private static final Logger LOG = LoggerFactory.getLogger(DockerSandboxClient.class);

    // ── 常量（对照 Go docker_remote_client.go / docker_idle_sweeper.go /
    //      docker_snapshot.go / docker_template_catalog.go 的包级常量） ────────

    /** 每个 exec 触碰、空闲清扫读取的活动标记。活在 /workspace 与 /tmp 之外。 */
    public static final String DOCKER_ACTIVITY_MARKER = "/var/lib/weknora-sandbox-activity";

    /** 记录容器创建时生效的空闲 TTL，清扫时容器自带的值优先于本配置。 */
    public static final String DOCKER_IDLE_TTL_LABEL = "com.weknora.sandbox.idle-ttl-seconds";

    /** 技能快照镜像命名空间（本地 tag，永不 pull）。 */
    public static final String DOCKER_SKILL_SNAPSHOT_REPO = "weknora-skill";
    public static final String DOCKER_SKILL_SNAPSHOT_LABEL = "com.weknora.sandbox.skill-snapshot";
    public static final String DOCKER_SKILL_SNAPSHOT_SOURCE_LABEL =
            "com.weknora.sandbox.skill-snapshot-source";

    /** 运维自建镜像可挂此 label 进模板目录。 */
    public static final String DOCKER_TEMPLATE_LABEL = "com.weknora.sandbox.template";

    /** 会话工作区根（session_manager.go SessionWorkspaceRoot）。 */
    public static final String SESSION_WORKSPACE_ROOT = "/workspace";

    /** PID 1 用数字 0：entrypoint 要建 chmod 666 的活动标记，exec 才按调用指定账户。 */
    public static final String DOCKER_SANDBOX_PID1_USER = "0";

    /** CapDrop=ALL 后返还的能力：包管理器写镜像属主目录 + 降权 + 自杀子进程。 */
    public static final List<String> DOCKER_SANDBOX_CAPABILITIES = List.of(
            "CHOWN", "DAC_OVERRIDE", "FOWNER", "FSETID", "SETGID", "SETUID", "KILL");

    static final List<String> DOCKER_RESERVED_PATH_PREFIXES = List.of(
            "/proc", "/sys", "/dev", DOCKER_ACTIVITY_MARKER);

    /** entrypoint：建活动标记 + chmod，再 exec sleep infinity。 */
    public static final String DOCKER_SANDBOX_ENTRYPOINT_SCRIPT =
            "touch " + DOCKER_ACTIVITY_MARKER + " 2>/dev/null; "
                    + "chmod 666 " + DOCKER_ACTIVITY_MARKER + " 2>/dev/null; "
                    + "exec sleep infinity";

    static final Duration DOCKER_START_READY_TIMEOUT = Duration.ofSeconds(15);
    static final Duration DOCKER_START_READY_POLL = Duration.ofMillis(50);
    static final Duration DOCKER_EXEC_GRACE = Duration.ofSeconds(10);
    static final Duration DOCKER_FILESYSTEM_OP_TIMEOUT = Duration.ofSeconds(30);
    static final Duration DOCKER_IMAGE_PULL_BUDGET = Duration.ofMinutes(30);
    static final Duration REMOTE_CLEANUP_TIMEOUT = Duration.ofSeconds(30);
    static final Duration DOCKER_SWEEP_MIN_INTERVAL = Duration.ofMinutes(1);
    static final Duration DOCKER_SWEEP_BUDGET = Duration.ofMinutes(2);
    static final Duration DOCKER_ACTIVITY_CLOCK_SKEW = Duration.ofMinutes(2);

    // ── 构造 ─────────────────────────────────────────────────────────────

    /**
     * 每配置运行时切片（对照 dockerRuntimeSettings）。由
     * {@link #settingsFromConfig} 从 {@link EffectiveConfig} 投影并应用内建默认。
     */
    public record Settings(
            String image,
            double cpuLimit,
            long memoryBytes,
            long pidsLimit,
            String networkMode,
            String runtime,
            Duration idleTTL,
            Duration httpTimeout,
            DockerEngineClients.DockerEndpoint endpoint) {
    }

    private final DockerEngineClients.DockerEngine api;
    private final Settings settings;
    private final IdleSweeper sweeper;

    /** 单测接缝：直接给引擎切片与设置（对照 newDockerRemoteClientWithAPI）。 */
    public DockerSandboxClient(DockerEngineClients.DockerEngine api, Settings settings) {
        this.api = api;
        this.settings = settings;
        this.sweeper = settings != null
                && settings.idleTTL() != null
                && settings.idleTTL().compareTo(Duration.ZERO) > 0
                ? new IdleSweeper(this, settings.idleTTL()) : null;
    }

    /**
     * 对照 NewDockerRemoteClient：总闸（docker 后端未启用 →
     * {@link DockerBackendDisabledException}）+ 设置投影 + 共享 daemon 连接。
     */
    public static DockerSandboxClient forConfig(EffectiveConfig cfg) {
        SandboxBackendPolicy.ensureDockerBackendAllowed(SandboxTypes.TYPE_DOCKER);
        Settings settings = settingsFromConfig(cfg);
        return new DockerSandboxClient(engineFor(settings), settings);
    }

    /**
     * 对照 NewDockerRemoteClientForCheck：连通性检查客户端。与解析客户端唯一的差别是
     * 无空闲清扫——探测跑在管理员还在编辑的配置上，半成品配置绝不能删工作配置的容器。
     */
    public static DockerSandboxClient forCheck(EffectiveConfig cfg) {
        SandboxBackendPolicy.ensureDockerBackendAllowed(SandboxTypes.TYPE_DOCKER);
        Settings settings = settingsFromConfig(cfg);
        settings = new Settings(settings.image(), settings.cpuLimit(), settings.memoryBytes(),
                settings.pidsLimit(), settings.networkMode(), settings.runtime(),
                Duration.ZERO, settings.httpTimeout(), settings.endpoint());
        return new DockerSandboxClient(engineFor(settings), settings);
    }

    private static DockerEngineClients.DockerEngine engineFor(Settings settings) {
        return new DockerEngineClients.DockerJavaEngine(
                DockerEngineClients.get(settings.endpoint()));
    }

    /** 对照 dockerSettingsFromConfig：投影 + 内建默认 + 网络模式/TLS 校验。 */
    static Settings settingsFromConfig(EffectiveConfig cfg) {
        if (cfg == null) {
            throw new IllegalArgumentException("sandbox: docker client requires a config");
        }
        String image = cfg.dockerImage == null ? "" : cfg.dockerImage.trim();
        if (image.isEmpty()) {
            throw new IllegalArgumentException("sandbox: docker backend requires an image");
        }
        double cpu = cfg.dockerCpuLimit > 0
                ? cfg.dockerCpuLimit : EffectiveConfig.DEFAULT_DOCKER_CPU_LIMIT;
        long memory = cfg.dockerMemoryBytes > 0
                ? cfg.dockerMemoryBytes : EffectiveConfig.DEFAULT_DOCKER_MEMORY_LIMIT;
        long pids = cfg.dockerPidsLimit > 0
                ? cfg.dockerPidsLimit : EffectiveConfig.DEFAULT_DOCKER_PIDS_LIMIT;
        Duration idleTTL = cfg.dockerIdleTtlSec > 0
                ? Duration.ofSeconds(cfg.dockerIdleTtlSec)
                : Duration.ofSeconds(EffectiveConfig.DEFAULT_DOCKER_IDLE_TTL_SEC);
        Duration httpTimeout = cfg.dockerHttpTimeoutSec > 0
                ? Duration.ofSeconds(cfg.dockerHttpTimeoutSec)
                : Duration.ofSeconds(EffectiveConfig.DEFAULT_DOCKER_HTTP_TIMEOUT_SEC);
        String networkMode = cfg.dockerNetworkMode == null ? "" : cfg.dockerNetworkMode.trim();
        String host = cfg.dockerHost == null ? "" : cfg.dockerHost.trim();
        String tlsCertPath = cfg.dockerTlsCertPath == null ? "" : cfg.dockerTlsCertPath.trim();
        DockerHostSupport.validateDockerNetworkMode(networkMode);
        DockerHostSupport.validateDockerRemoteTLS(host, tlsCertPath);
        return new Settings(image, cpu, memory, pids, networkMode,
                cfg.dockerRuntime == null ? "" : cfg.dockerRuntime.trim(),
                idleTTL, httpTimeout,
                new DockerEngineClients.DockerEndpoint(host, tlsCertPath,
                        cfg.allowPrivateEndpoints));
    }

    // ── 能力面 ───────────────────────────────────────────────────────────

    @Override
    public String provider() {
        return SandboxTypes.TYPE_DOCKER;
    }

    /**
     * 对照 Capabilities（docker_remote_client.go L220）：Reconnect/Metadata/List/
     * PauseResume/Snapshots/FilesystemEnumeration=true；TimeoutRefresh=false（daemon
     * 没有可刷新的超时，回收是自己的清扫）；Volumes=false（卷挂载面未映射到命名卷，
     * 提前广告会让工作区配置一个永远不出现的挂载）；无终端面（docker 无 PTY）。
     */
    @Override
    public Capabilities capabilities() {
        return new Capabilities(
                /* supportsReconnect */ true,
                /* supportsMetadata */ true,
                /* supportsListSandboxes */ true,
                /* supportsPauseResume */ true,
                /* supportsTimeoutRefresh */ false,
                /* supportsFilesystemEnumeration */ true,
                /* supportsSnapshots */ true,
                /* supportsVolumes */ false,
                /* supportsTerminals */ false);
    }

    /** 对照 Health：ping daemon。 */
    @Override
    public void health() {
        shortRpc("Health", () -> {
            api.ping();
            return null;
        });
    }

    // ── 生命周期 ─────────────────────────────────────────────────────────

    @Override
    public Handle create(CreateRequest req) {
        requireBackendEnabled();
        if (req == null) {
            throw DockerEngineClients.dockerInvalidRequest("Create", "create request is required");
        }
        if (req.volumeMounts() != null && !req.volumeMounts().isEmpty()) {
            throw RemoteError.of(SandboxTypes.TYPE_DOCKER, "Create",
                    RemoteErrorKind.UNSUPPORTED, "docker backend does not mount volumes yet");
        }
        String requested = req.templateId() == null ? "" : req.templateId().trim();
        final String image = requested.isEmpty() ? settings.image() : requested;
        ensureImage(image);

        Map<String, String> labels = DockerEngineClients.dockerContainerLabels(req.metadata());
        labels.put(DOCKER_IDLE_TTL_LABEL,
                Long.toString(effectiveIdleTTL(req.timeout(), settings.idleTTL()).getSeconds()));

        String containerId;
        try {
            containerId = shortRpc("Create", () -> api.containerCreate(new DockerEngineClients.ContainerCreateSpec(
                    image,
                    DOCKER_SANDBOX_PID1_USER,
                    new String[]{"/bin/sh", "-c", DOCKER_SANDBOX_ENTRYPOINT_SCRIPT},
                    new String[0],
                    SESSION_WORKSPACE_ROOT,
                    labels,
                    dockerEnvSlice(req.envVars()),
                    new DockerEngineClients.HostIsolation(
                            settings.memoryBytes(),
                            // memory == memory+swap 关闭 swap：失控分配被杀而不是拖垮宿主磁盘
                            settings.memoryBytes(),
                            (long) (settings.cpuLimit() * 1e9),
                            settings.pidsLimit(),
                            List.of("ALL"),
                            DOCKER_SANDBOX_CAPABILITIES,
                            List.of("no-new-privileges"),
                            settings.runtime(),
                            networkMode(settings.networkMode(), req.network()),
                            true))));
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError("Create", e);
        }
        try {
            shortRpc("Create", () -> {
                api.containerStart(containerId);
                return null;
            });
        } catch (RuntimeException e) {
            // 起不来的容器是废物，否则会以未绑定残留的身份挂到很久以后的清扫
            removeQuietly(containerId);
            throw DockerEngineClients.dockerError("Create", e);
        }
        // ContainerStart 返回 ≠ State.Running：daemon 受理后 PID 1 还要换入 sh，
        // "created" 容器上的 ExecCreate 会 409（skill install 首试失败重试成功的旧根因）
        try {
            waitUntilRunning(containerId, "Create");
        } catch (RuntimeException e) {
            removeQuietly(containerId);
            throw e;
        }
        sweepInBackground();
        return new DockerHandle(containerId, DockerEngineClients.dockerSandboxMetadata(labels));
    }

    @Override
    public Handle connect(ConnectRequest request) {
        requireBackendEnabled();
        String sandboxId = request == null || request.sandboxId() == null
                ? "" : request.sandboxId().trim();
        if (sandboxId.isEmpty()) {
            throw DockerEngineClients.dockerInvalidRequest("Connect", "sandbox ID is required");
        }
        // 无 provider 网关 → 无入站凭据可恢复；trafficAccessToken 忽略
        DockerEngineClients.ContainerInspectResult inspected = inspect(sandboxId, "Connect");
        if (inspected.status() == null) {
            throw DockerEngineClients.dockerError("Connect",
                    new IllegalStateException("daemon returned no container state"));
        }
        String normalized = DockerEngineClients.dockerStateOf(inspected.status());
        if (Summary.STATE_TERMINAL.equals(normalized)) {
            throw RemoteError.of(SandboxTypes.TYPE_DOCKER, "Connect",
                    RemoteErrorKind.TERMINAL, "container is dead");
        }
        if (Summary.STATE_PAUSED.equals(normalized)) {
            // dockerStateOf 把 paused/exited/created 都归为 paused；resume 内部再按
            // 原始状态分 unpause/start，等效 Go 的两段恢复
            resume(inspected.id(), inspected.status(), "Connect");
            waitUntilRunning(inspected.id(), "Connect");
        }
        sweepInBackground();
        return new DockerHandle(inspected.id(),
                DockerEngineClients.dockerSandboxMetadata(inspected.labels()));
    }

    @Override
    public Summary get(String sandboxId) {
        DockerEngineClients.ContainerInspectResult inspected =
                inspect(sandboxId == null ? "" : sandboxId.trim(), "Get");
        String state = DockerEngineClients.dockerStateOf(inspected.status());
        OffsetDateTime startedAt = parseTime(inspected.startedAt());
        OffsetDateTime endAt = finishedAtOrNull(inspected.finishedAt());
        String templateId = inspected.image() == null ? "" : inspected.image();
        Map<String, String> metadata =
                DockerEngineClients.dockerSandboxMetadata(inspected.labels());
        return new Summary(inspected.id(), templateId, state, inspected.status(),
                metadata, startedAt, endAt);
    }

    @Override
    public List<Summary> list(ListFilter filter) {
        List<String> labelFilters = new ArrayList<>();
        labelFilters.add(DockerEngineClients.DOCKER_MANAGED_LABEL + "=true");
        if (filter != null && filter.metadata() != null) {
            for (Map.Entry<String, String> e : filter.metadata().entrySet()) {
                labelFilters.add(e.getKey() + "=" + e.getValue());
            }
        }
        List<DockerEngineClients.ContainerListItem> items;
        try {
            items = shortRpc("List", () -> api.containerList(labelFilters));
        } catch (RemoteError e) {
            throw e;
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError("List", e);
        }
        Set<String> wanted = filter == null || filter.states() == null
                ? Set.of() : Set.copyOf(filter.states());
        List<Summary> summaries = new ArrayList<>();
        for (DockerEngineClients.ContainerListItem item : items) {
            String state = DockerEngineClients.dockerStateOf(item.state());
            if (!wanted.isEmpty() && !wanted.contains(state)) {
                continue;
            }
            summaries.add(new Summary(
                    item.id(),
                    item.image() == null ? "" : item.image(),
                    state,
                    item.state(),
                    DockerEngineClients.dockerSandboxMetadata(item.labels()),
                    Instant.ofEpochSecond(item.createdEpochSec()).atOffset(ZoneOffset.UTC),
                    null));
        }
        return summaries;
    }

    @Override
    public void delete(String sandboxId) {
        try {
            shortRpc("Delete", () -> {
                api.containerRemove(sandboxId == null ? "" : sandboxId.trim());
                return null;
            });
        } catch (RemoteError e) {
            throw e;
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError("Delete", e);
        }
    }

    /** 对照 removeQuietly：清理路径上的删除，调用方另有更值得报的错。 */
    private void removeQuietly(String id) {
        try {
            DockerEngineClients.rpc(REMOTE_CLEANUP_TIMEOUT, () -> {
                api.containerRemove(id);
                return null;
            });
        } catch (RuntimeException ignored) {
            // 清扫/生命周期会兜底
        }
    }

    // ── Exec ─────────────────────────────────────────────────────────────

    /**
     * 对照 Exec（docker_remote_client.go L648）：超时由容器内的 timeout(1) 强制——
     * 取消 HTTP 请求杀不掉容器里的进程，客户端 deadline（超时 + 宽限）只是后备，
     * 让 wrapper 有机会自己把 137/124 报成 Killed=true。
     */
    @Override
    public ExecResult exec(Handle handle, ExecRequest req) {
        requireBackendEnabled();
        Instant start = Instant.now();
        ExecOutcome outcome = runExec(handle, req, null);
        return new ExecResult(
                utf8(outcome.stdout()),
                utf8(outcome.stderr()),
                outcome.exitCode(),
                Duration.between(start, Instant.now()),
                execWasKilled(outcome.exitCode()));
    }

    private record ExecOutcome(byte[] stdout, byte[] stderr, int exitCode) {
    }

    private record RawExecOutput(byte[] stdout, byte[] stderr) {
    }

    /** exec 管线：ExecCreate（含 not-running 重试）→ attach 流 → ExecInspect。 */
    private ExecOutcome runExec(Handle handle, ExecRequest req, byte[] stdinOverride) {
        String id = dockerHandleID("Exec", handle);
        if (req.shell() && req.args() != null && !req.args().isEmpty()) {
            throw DockerEngineClients.dockerInvalidRequest("Exec",
                    "shell requests must not carry args");
        }
        if (req.command() == null || req.command().trim().isEmpty()) {
            throw DockerEngineClients.dockerInvalidRequest("Exec", "command is required");
        }
        Duration timeout = req.timeout() != null && !req.timeout().isZero() && !req.timeout().isNegative()
                ? req.timeout()
                : Duration.ofSeconds(EffectiveConfig.DEFAULT_TIMEOUT_SEC);
        byte[] stdinBytes = stdinOverride != null
                ? stdinOverride
                : req.stdin() == null || req.stdin().isEmpty() ? null : utf8(req.stdin());
        // stdin 走"种子文件 + 重定向"：zerodep 传输的 exec stdin 无半关闭
        // （写端不 EOF），直接 attach 会把读 stdin 的命令挂死到 timeout——
        // 探针实锤。种子经 PutArchive 落 /tmp，wrapper 以 < 重定向喂给命令；
        // 契约与 Go 的 Write+CloseWrite 等效（命令看到数据后立即 EOF）。
        String stdinSeed = null;
        if (stdinBytes != null && stdinBytes.length > 0) {
            stdinSeed = "/tmp/weknora-stdin-" + Long.toHexString(System.nanoTime()) + ".bin";
            byte[] seedTar = tarSingleFile(baseNameOf(stdinSeed), stdinBytes);
            final String seedDir = "/tmp";
            try {
                DockerEngineClients.rpc(DOCKER_FILESYSTEM_OP_TIMEOUT,
                        () -> {
                            api.putArchive(id, seedDir, seedTar);
                            return null;
                        });
            } catch (RemoteError e) {
                throw e;
            } catch (RuntimeException e) {
                throw DockerEngineClients.dockerError("Exec", e);
            }
        }
        Instant deadline = Instant.now().plus(timeout).plus(DOCKER_EXEC_GRACE);

        String execId = createExec(id, req, timeout, stdinSeed);
        RawExecOutput raw = streamExec(execId, req, stdinBytes, deadline);
        int exitCode;
        try {
            exitCode = shortRpc("Exec", () -> api.execInspect(execId));
        } catch (RemoteError e) {
            throw e;
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError("Exec", e);
        }
        return new ExecOutcome(raw.stdout(), raw.stderr(), exitCode);
    }

    private String createExec(String id, ExecRequest req, Duration timeout, String stdinSeed) {
        List<String> cmd = List.of(execCommand(req, timeout, stdinSeed));
        DockerEngineClients.ExecCreateSpec spec = new DockerEngineClients.ExecCreateSpec(
                cmd.toArray(new String[0]),
                execUser(req.user()),
                req.workDir(),
                dockerEnvSlice(req.env()),
                false);
        try {
            return shortRpc("Exec", () -> api.execCreate(id, spec));
        } catch (RuntimeException e) {
            if (!dockerContainerNotRunning(e)) {
                throw DockerEngineClients.dockerError("Exec", e);
            }
            // 第一次命令撞上还没换入 PID 1 的容器、或被宿主/清扫停掉的容器：
            // 恢复到 running 再试一次（对照 Go 的 ensureRunning 分支）
            ensureRunning(id, "Exec");
            return shortRpc("Exec", () -> api.execCreate(id, spec));
        }
    }

    /**
     * 对照 streamExec：启动 exec，读帧收集 stdout/stderr 并逐帧喂 onOutput。
     * EOF 返回；超过客户端 deadline 抛 TIMEOUT（容器内 timeout(1) 正常会先杀掉进程，
     * 把超时表现为 Killed=true——走到这里说明 wrapper 没按期收场）。
     */
    private RawExecOutput streamExec(String execId, ExecRequest req, byte[] stdinBytes,
            Instant deadline) {
        DockerEngineClients.ExecStream stream;
        try {
            stream = api.execAttach(execId, stdinBytes);
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError("Exec", e);
        }
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (stream) {
            while (true) {
                long remainingMs = Duration.between(Instant.now(), deadline).toMillis();
                if (remainingMs <= 0) {
                    throw RemoteError.of(SandboxTypes.TYPE_DOCKER, "Exec",
                            RemoteErrorKind.TIMEOUT, "context deadline exceeded");
                }
                DockerEngineClients.ExecFrame frame;
                try {
                    frame = stream.nextFrame(Math.min(remainingMs, 250));
                } catch (TimeoutException e) {
                    continue; // 单次 poll 粒度超时，总 deadline 未到
                }
                if (frame == null) {
                    break; // EOF
                }
                if ("stderr".equals(frame.stream())) {
                    stderr.writeBytes(frame.payload());
                    if (req.onOutput() != null) {
                        req.onOutput().onOutput("stderr", frame.payload());
                    }
                } else {
                    stdout.writeBytes(frame.payload());
                    if (req.onOutput() != null) {
                        req.onOutput().onOutput("stdout", frame.payload());
                    }
                }
            }
        } catch (RuntimeException e) {
            if (e instanceof RemoteError re) {
                throw re;
            }
            throw DockerEngineClients.dockerError("Exec", e);
        }
        return new RawExecOutput(stdout.toByteArray(), stderr.toByteArray());
    }

    /**
     * 对照 dockerExecCommand：wrapper 刷新活动标记（空闲清扫免费搭车）并由容器内的
     * timeout(1) 强制超时；位置参数 "$@"/"$1" 无引号透传调用方命令，含引号/换行的
     * 脚本改不了实际执行的内容。
     */
    public static String[] execCommand(ExecRequest req, Duration timeout) {
        return execCommand(req, timeout, null);
    }

    /**
     * exec 的 wrapper argv。stdinSeed 非空时（PutArchive 播种的 stdin 文件）以
     * {@code < seed} 重定向喂给被包装命令——zerodep 传输的 hijack 写端无半关闭，
     * 直接 attach stdin 会让读端挂死（探针实锤）。
     */
    public static String[] execCommand(ExecRequest req, Duration timeout, String stdinSeed) {
        long seconds = Math.round(timeout.toMillis() / 1000.0);
        if (seconds <= 0) {
            seconds = 1;
        }
        String redirect = stdinSeed == null || stdinSeed.isEmpty()
                ? "" : " < " + stdinSeed;
        String touch = "touch " + DOCKER_ACTIVITY_MARKER + " 2>/dev/null || true; ";
        if (req.shell()) {
            return new String[]{
                    "/bin/sh", "-c",
                    touch + "exec timeout -s KILL " + seconds
                            + " /bin/bash --noprofile --norc -c \"$1\"" + redirect,
                    "weknora-exec", req.command(),
            };
        }
        String[] argv = new String[5 + (req.args() == null ? 0 : req.args().size())];
        argv[0] = "/bin/sh";
        argv[1] = "-c";
        argv[2] = touch + "exec timeout -s KILL " + seconds + " \"$@\"" + redirect;
        argv[3] = "weknora-exec";
        argv[4] = req.command();
        if (req.args() != null) {
            for (int i = 0; i < req.args().size(); i++) {
                argv[5 + i] = req.args().get(i);
            }
        }
        return argv;
    }

    /**
     * 对照 dockerExecUser：空白账户落到 DefaultSandboxExecUser（root）——所有 exec
     * 的单一收口，与 E2B/Cube 的默认账户一致。
     */
    public static String execUser(String user) {
        String trimmed = user == null ? "" : user.trim();
        if (!trimmed.isEmpty()) {
            return trimmed;
        }
        return ExecRequest.DEFAULT_SANDBOX_EXEC_USER;
    }

    /** 对照 dockerExecWasKilled：137=SIGKILL（timeout -s KILL）、124=timeout(1) 干预。 */
    public static boolean execWasKilled(int exitCode) {
        return exitCode == 137 || exitCode == 124;
    }

    /** 对照 dockerContainerNotRunning：Engine 409 的消息是信号（Conflict 不可替换）。 */
    static boolean dockerContainerNotRunning(Throwable err) {
        if (err == null) {
            return false;
        }
        String message = err.getMessage();
        return message != null && message.toLowerCase().contains("is not running");
    }

    // ── 文件面（全部走 exec：显式账户 + 有界执行 + 活动跟踪） ─────────────

    @Override
    public void writeFile(Handle handle, String path, byte[] content) {
        String id = dockerHandleID("WriteFile", handle);
        String clean = dockerCleanPath("WriteFile", path);
        makeDirInternal(id, parentOf(clean), "WriteFile");
        // PutArchive 载荷：单条目 tar，名字取 path 基名、落进父目录。
        // （Go 原实现是 cat > "$1" + hijacked stdin——zerodep 传输的 exec stdin
        // 无半关闭，写端永不 EOF 会把 cat 挂死到 timeout，探针实锤后改走归档 API。
        // 观察契约不变：父目录物化 + 字节落到 clean。）
        byte[] tar = tarSingleFile(baseNameOf(clean), content == null ? new byte[0] : content);
        try {
            DockerEngineClients.rpc(DOCKER_FILESYSTEM_OP_TIMEOUT,
                    () -> {
                        api.putArchive(id, parentOf(clean), tar);
                        return null;
                    });
        } catch (RemoteError e) {
            throw e;
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError("WriteFile", e);
        }
    }

    /** path 的最后一段（对照 Go path.Base；"/" 与空串归 ""）。 */
    private static String baseNameOf(String path) {
        String p = path == null ? "" : path;
        int idx = p.lastIndexOf('/');
        return idx < 0 ? p : p.substring(idx + 1);
    }

    /**
     * 最小 ustar 单文件归档（对照 Go archive/tar 的 WriteFile 载荷面）：512 头 +
     * 内容 + 512 对齐。PutArchive 只需要 Go tar reader 认的常规字段。
     */
    static byte[] tarSingleFile(String name, byte[] content) {
        String safeName = name == null || name.isEmpty() ? "file" : name;
        byte[] data = content == null ? new byte[0] : content;
        byte[] header = new byte[512];
        byte[] nameBytes = safeName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));
        putOctal(header, 100, 8, 0644);          // mode
        putOctal(header, 108, 8, 0);             // uid
        putOctal(header, 116, 8, 0);             // gid
        putOctal(header, 124, 12, data.length);  // size
        putOctal(header, 136, 12, System.currentTimeMillis() / 1000); // mtime
        header[156] = '0';                        // typeflag: regular file
        System.arraycopy("ustar\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                0, header, 257, 6);
        byte[] version = "00".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(version, 0, header, 263, 2);
        System.arraycopy("root".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                0, header, 265, 4);              // uname
        System.arraycopy("root".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                0, header, 297, 4);              // gname
        java.util.Arrays.fill(header, 148, 156, (byte) ' '); // checksum 占位
        int sum = 0;
        for (byte b : header) {
            sum += (b & 0xFF);
        }
        byte[] chk = String.format("%06o\0 ", sum).getBytes(
                java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(chk, 0, header, 148, 8);

        int padded = (data.length + 511) / 512 * 512;
        byte[] out = new byte[512 + padded + 1024]; // 头 + 数据对齐 + 两块终止
        System.arraycopy(header, 0, out, 0, 512);
        System.arraycopy(data, 0, out, 512, data.length);
        return out;
    }

    /** 八进制写入 tar 头字段（width 含结尾 NUL，对照 Go archive/tar 的格式）。 */
    private static void putOctal(byte[] header, int off, int width, long value) {
        String oct = Long.toOctalString(value);
        String paddedStr = "0".repeat(Math.max(0, width - 1 - oct.length())) + oct + "\0";
        System.arraycopy(paddedStr.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                0, header, off, width);
    }

    @Override
    public byte[] readFile(Handle handle, String path) {
        String id = dockerHandleID("ReadFile", handle);
        String clean = dockerCleanPath("ReadFile", path);
        ExecRequest exec = new ExecRequest(null, "cat",
                List.of("--", clean), false, null, null, null, execUser(null),
                DOCKER_FILESYSTEM_OP_TIMEOUT);
        ExecOutcome result = runExec(new DockerHandle(id, null), exec, null);
        if (result.exitCode() != 0) {
            throw dockerFileOpError("ReadFile", clean, utf8(result.stderr()));
        }
        return result.stdout();
    }

    @Override
    public StatEntry stat(Handle handle, String path) {
        String id = dockerHandleID("Stat", handle);
        String clean = dockerCleanPath("Stat", path);
        ExecRequest exec = new ExecRequest(null, "find",
                List.of(clean, "-maxdepth", "0", "-printf", "%y\t%s\t%T@\t%p\\n"),
                false, null, null, null, execUser(null), DOCKER_FILESYSTEM_OP_TIMEOUT);
        ExecOutcome result = runExec(new DockerHandle(id, null), exec, null);
        if (result.exitCode() != 0) {
            throw dockerFileOpError("Stat", clean, utf8(result.stderr()));
        }
        List<DirEntry> entries = parseFindOutput(utf8(result.stdout()));
        if (entries.isEmpty()) {
            throw RemoteError.of(SandboxTypes.TYPE_DOCKER, "Stat",
                    RemoteErrorKind.NOT_FOUND, clean + " does not exist");
        }
        DirEntry entry = entries.get(0);
        return new StatEntry(entry.path(), entry.type(), entry.size(), entry.modTime());
    }

    @Override
    public void makeDir(Handle handle, String path) {
        String id = dockerHandleID("MakeDir", handle);
        makeDirInternal(id, dockerCleanPath("MakeDir", path), "MakeDir");
    }

    private void makeDirInternal(String id, String dir, String op) {
        ExecRequest exec = new ExecRequest(null, "mkdir", List.of("-p", dir),
                false, null, null, null, execUser(null), DOCKER_FILESYSTEM_OP_TIMEOUT);
        ExecOutcome result;
        try {
            result = runExec(new DockerHandle(id, null), exec, null);
        } catch (RemoteError e) {
            throw e;
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError(op, e);
        }
        if (result.exitCode() != 0) {
            throw RemoteError.of(SandboxTypes.TYPE_DOCKER, op,
                    RemoteErrorKind.INVALID_REQUEST,
                    "mkdir -p " + dir + ": " + firstNonEmptyLine(utf8(result.stderr())));
        }
    }

    @Override
    public void remove(Handle handle, String path) {
        String id = dockerHandleID("Remove", handle);
        String clean = dockerCleanPath("Remove", path);
        if ("/".equals(clean)) {
            throw DockerEngineClients.dockerInvalidRequest("Remove",
                    "refusing to remove the container root");
        }
        ExecRequest exec = new ExecRequest(null, "rm", List.of("-rf", clean),
                false, null, null, null, execUser(null), DOCKER_FILESYSTEM_OP_TIMEOUT);
        ExecOutcome result;
        try {
            result = runExec(new DockerHandle(id, null), exec, null);
        } catch (RemoteError e) {
            throw e;
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError("Remove", e);
        }
        if (result.exitCode() != 0) {
            throw RemoteError.of(SandboxTypes.TYPE_DOCKER, "Remove",
                    RemoteErrorKind.INVALID_REQUEST,
                    "rm -rf " + clean + ": " + firstNonEmptyLine(utf8(result.stderr())));
        }
    }

    @Override
    public List<DirEntry> listDir(Handle handle, String path) {
        String id = dockerHandleID("ListDir", handle);
        String clean = dockerCleanPath("ListDir", path);
        ExecRequest exec = new ExecRequest(null, "find",
                List.of(clean, "-mindepth", "1", "-maxdepth", "1",
                        "-printf", "%y\t%s\t%T@\t%p\\n"),
                false, null, null, null, execUser(null), DOCKER_FILESYSTEM_OP_TIMEOUT);
        ExecOutcome result;
        try {
            result = runExec(new DockerHandle(id, null), exec, null);
        } catch (RemoteError e) {
            throw e;
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError("ListDir", e);
        }
        String stderr = utf8(result.stderr());
        if (result.exitCode() != 0) {
            if (stderr.contains("No such file or directory")) {
                throw RemoteError.of(SandboxTypes.TYPE_DOCKER, "ListDir",
                        RemoteErrorKind.NOT_FOUND, clean + " does not exist");
            }
            throw RemoteError.of(SandboxTypes.TYPE_DOCKER, "ListDir",
                    RemoteErrorKind.INTERNAL,
                    "find " + clean + ": " + firstNonEmptyLine(stderr));
        }
        return parseFindOutput(utf8(result.stdout()));
    }

    /** 对照 dockerFileOpError：缺路径是 NotFound，其余带工具自己的抱怨。 */
    static RemoteError dockerFileOpError(String op, String clean, String stderr) {
        if (stderr != null && stderr.contains("No such file or directory")) {
            return RemoteError.of(SandboxTypes.TYPE_DOCKER, op,
                    RemoteErrorKind.NOT_FOUND, clean + " does not exist");
        }
        return RemoteError.of(SandboxTypes.TYPE_DOCKER, op,
                RemoteErrorKind.INVALID_REQUEST,
                op + " " + clean + ": " + firstNonEmptyLine(stderr));
    }

    /**
     * 对照 parseDockerFindOutput：find -printf 的行 → 目录条目；坏行跳过而不是让
     * 整个目录不可见。
     */
    public static List<DirEntry> parseFindOutput(String output) {
        List<DirEntry> entries = new ArrayList<>();
        if (output == null) {
            return entries;
        }
        for (String rawLine : output.split("\n", -1)) {
            String line = trimTrailingCr(rawLine);
            String[] fields = line.split("\t", 4);
            if (fields.length != 4) {
                continue;
            }
            String path = fields[3];
            long size = 0;
            try {
                size = Long.parseLong(fields[1]);
            } catch (NumberFormatException ignored) {
                // 坏字段置 0，条目照常出现
            }
            OffsetDateTime modTime = null;
            try {
                double seconds = Double.parseDouble(fields[2]);
                long whole = (long) seconds;
                modTime = Instant.ofEpochSecond(whole,
                        (long) ((seconds - whole) * 1e9)).atOffset(ZoneOffset.UTC);
            } catch (NumberFormatException ignored) {
                // 同上
            }
            entries.add(new DirEntry(baseName(path), path, entryTypeOf(fields[0]),
                    size, modTime));
        }
        return entries;
    }

    static DirEntryType entryTypeOf(String findType) {
        return switch (findType) {
            case "f" -> DirEntryType.FILE;
            case "d" -> DirEntryType.DIR;
            default -> DirEntryType.OTHER;
        };
    }

    private static String baseName(String path) {
        int idx = path.lastIndexOf('/');
        return idx < 0 ? path : path.substring(idx + 1);
    }

    private static String parentOf(String clean) {
        int idx = clean.lastIndexOf('/');
        return idx <= 0 ? "/" : clean.substring(0, idx);
    }

    private static String trimTrailingCr(String line) {
        String out = line;
        while (out.endsWith("\r")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /**
     * 对照 dockerCleanPath：绝对路径 + path.Clean 归一 + 保留字前缀拒绝。
     * 相对路径被拒：它会相对容器工作目录解析，而 exec 路径与 archive 路径的工作目录
     * 并不相同。
     */
    public static String dockerCleanPath(String op, String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw DockerEngineClients.dockerInvalidRequest(op, "path is required");
        }
        if (!trimmed.startsWith("/")) {
            throw DockerEngineClients.dockerInvalidRequest(op, "path must be absolute: " + raw);
        }
        String clean = goPathClean(trimmed);
        String reserved = dockerReservedPath(clean);
        if (reserved != null) {
            throw DockerEngineClients.dockerInvalidRequest(op,
                    "path is not addressable: " + reserved);
        }
        return clean;
    }

    /** 对照 dockerReservedPath：clean 必须已归一。 */
    static String dockerReservedPath(String clean) {
        for (String prefix : DOCKER_RESERVED_PATH_PREFIXES) {
            if (clean.equals(prefix) || clean.startsWith(prefix + "/")) {
                return prefix;
            }
        }
        return null;
    }

    /** Go path.Clean 的绝对路径语义。 */
    static String goPathClean(String path) {
        boolean rooted = path.startsWith("/");
        String[] parts = path.split("/");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            switch (part) {
                case "", "." -> {
                }
                case ".." -> {
                    if (!out.isEmpty() && !"..".equals(out.get(out.size() - 1))) {
                        out.remove(out.size() - 1);
                    } else if (!rooted) {
                        out.add("..");
                    }
                }
                default -> out.add(part);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (rooted) {
            sb.append('/');
        }
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(out.get(i));
        }
        String cleaned = sb.toString();
        return cleaned.isEmpty() ? (rooted ? "/" : ".") : cleaned;
    }

    /** 对照 firstNonEmptyLine：截 200 字符加省略号（Go 的 … 原字符）。 */
    public static String firstNonEmptyLine(String output) {
        if (output == null) {
            return "";
        }
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                if (trimmed.length() > 200) {
                    return trimmed.substring(0, 200) + "…";
                }
                return trimmed;
            }
        }
        return "";
    }

    // ── 网络模式 / 空闲 TTL ──────────────────────────────────────────────

    /**
     * 对照 networkMode：Docker 只在 L3/L4 过滤，域级 allow/deny 无法兑现；
     * 唯一干净映射的是"完全无出网"。域规则在保存时已被配置面拒绝。
     */
    public static String networkMode(String configuredNetworkMode, RemoteNetworkPolicy policy) {
        if (policy != null && policy.allowInternetAccess != null && !policy.allowInternetAccess) {
            return "none";
        }
        String mode = configuredNetworkMode == null ? "" : configuredNetworkMode.trim();
        if (!mode.isEmpty()) {
            return mode;
        }
        return "bridge";
    }

    /**
     * 对照 effectiveIdleTTL：调用方的超时策略优先（会话层按 provider 表达的语义），
     * 配置值兜底。action 不兑现：Docker 的 pause 把内存留在宿主上，暂停弃置沙箱
     * 收不回任何东西——空闲容器一律删除。
     */
    public static Duration effectiveIdleTTL(TimeoutPolicy policy, Duration configuredIdleTTL) {
        if (policy != null && policy.mode() == TimeoutMode.EXPLICIT
                && policy.value() != null && policy.value().compareTo(Duration.ZERO) > 0) {
            return policy.value();
        }
        return configuredIdleTTL;
    }

    // ── 状态推进（Create/Connect/Exec 共用） ─────────────────────────────

    private DockerEngineClients.ContainerInspectResult inspect(String id, String op) {
        try {
            return shortRpc(op, () -> api.containerInspect(id));
        } catch (RemoteError e) {
            throw e;
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError(op, e);
        }
    }

    /** 对照 resume：paused → unpause，其余 → start。 */
    private void resume(String id, String status, String op) {
        String effectiveOp = op == null || op.isEmpty() ? "Connect" : op;
        try {
            shortRpc(effectiveOp, () -> {
                if ("paused".equalsIgnoreCase(status)) {
                    api.containerUnpause(id);
                } else {
                    api.containerStart(id);
                }
                return null;
            });
        } catch (RemoteError e) {
            throw e;
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError(effectiveOp, e);
        }
    }

    /** 对照 waitUntilRunning：轮询 inspect 直到 running 或等下去没有意义。 */
    private void waitUntilRunning(String id, String op) {
        Instant deadline = Instant.now().plus(DOCKER_START_READY_TIMEOUT);
        String lastStatus = "";
        while (true) {
            DockerEngineClients.ContainerInspectResult inspected;
            try {
                inspected = shortRpc(op, () -> api.containerInspect(id));
            } catch (RemoteError e) {
                throw e;
            } catch (RuntimeException e) {
                throw DockerEngineClients.dockerError(op, e);
            }
            if (inspected.status() == null) {
                throw DockerEngineClients.dockerError(op,
                        new IllegalStateException("daemon returned no container state"));
            }
            lastStatus = inspected.status().trim();
            String normalized = DockerEngineClients.dockerStateOf(lastStatus);
            if (Summary.STATE_RUNNING.equals(normalized)) {
                return;
            }
            if (Summary.STATE_TERMINAL.equals(normalized)) {
                throw RemoteError.of(SandboxTypes.TYPE_DOCKER, op,
                        RemoteErrorKind.TERMINAL, "container is dead");
            }
            String lower = lastStatus.toLowerCase();
            if ("exited".equals(lower) || "paused".equals(lower)) {
                throw RemoteError.of(SandboxTypes.TYPE_DOCKER, op,
                        RemoteErrorKind.CONFLICT, "container is not running: " + lastStatus);
            }
            if (!Instant.now().isBefore(deadline)) {
                throw RemoteError.of(SandboxTypes.TYPE_DOCKER, op,
                        RemoteErrorKind.TIMEOUT,
                        "container did not reach running (last state \"" + lastStatus + "\")");
            }
            try {
                Thread.sleep(DOCKER_START_READY_POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw RemoteError.of(SandboxTypes.TYPE_DOCKER, op,
                        RemoteErrorKind.TIMEOUT, "context canceled");
            }
        }
    }

    /** 对照 ensureRunning：Exec 版的 Connect——先恢复再等 exec 可用。 */
    private void ensureRunning(String id, String op) {
        DockerEngineClients.ContainerInspectResult inspected = inspect(id, op);
        if (inspected.status() == null) {
            throw DockerEngineClients.dockerError(op,
                    new IllegalStateException("daemon returned no container state"));
        }
        String normalized = DockerEngineClients.dockerStateOf(inspected.status());
        if (Summary.STATE_RUNNING.equals(normalized)) {
            return;
        }
        if (Summary.STATE_TERMINAL.equals(normalized)) {
            throw RemoteError.of(SandboxTypes.TYPE_DOCKER, op,
                    RemoteErrorKind.TERMINAL, "container is dead");
        }
        if (Summary.STATE_PAUSED.equals(normalized)) {
            resume(inspected.id(), inspected.status(), op);
        }
        waitUntilRunning(id, op);
    }

    // ── 镜像 ─────────────────────────────────────────────────────────────

    /**
     * 对照 ensureImage：daemon 没有就拉。pull 用 30 分钟预算而非调用方 deadline
     * （冷拉沙箱镜像要几分钟）。
     */
    private void ensureImage(String image) {
        try {
            shortRpc("Create", () -> api.imageInspect(image));
            return;
        } catch (RuntimeException inspectErr) {
            // 任何 inspect 失败都视为"本 daemon 没有"——与 Go 的 err == nil 判定同形
        }
        if (isSkillSnapshotRef(image)) {
            // 技能快照是 daemon 本地 commit，不是 registry tag；pull 它会去 Docker Hub
            // 找一个从未 push 的名字
            throw DockerEngineClients.dockerInvalidRequest("Create",
                    "skill snapshot image " + image + " is not on this daemon");
        }
        try {
            DockerEngineClients.rpc(DOCKER_IMAGE_PULL_BUDGET, () -> {
                api.imagePull(image);
                return null;
            });
        } catch (RuntimeException e) {
            RemoteErrorKind kind = DockerEngineClients.dockerErrorKind("Create", e);
            throw new RemoteError(SandboxTypes.TYPE_DOCKER, "Create", kind,
                    "pull image " + image + ": " + e.getMessage(), e, 0);
        }
    }

    // ── 快照（对照 docker_snapshot.go 全文） ─────────────────────────────

    /**
     * 对照 CreateSnapshot：容器文件系统 commit 成带 tag 的本地镜像。Engine 在 commit
     * 期间暂停容器——与 Cube/E2B 的"快照期间 provider 暂停沙箱"契约一致。产出的
     * tag 是模板 ID，Create 直接收作 TemplateID，skill install 不需要 docker 专属分支。
     */
    @Override
    public SnapshotRef createSnapshot(String sandboxId, String name) {
        String id = sandboxId == null ? "" : sandboxId.trim();
        if (id.isEmpty()) {
            throw DockerEngineClients.dockerInvalidRequest("CreateSnapshot",
                    "sandbox ID is required");
        }
        String reference = skillSnapshotReference(name, id);
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(DOCKER_SKILL_SNAPSHOT_LABEL, "true");
        labels.put(DOCKER_SKILL_SNAPSHOT_SOURCE_LABEL, id);
        String committed;
        try {
            committed = DockerEngineClients.rpc(null, () -> api.containerCommit(id,
                    new DockerEngineClients.CommitSpec(reference,
                            "weknora skill snapshot", labels)));
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError("CreateSnapshot", e);
        }
        if (committed == null || committed.trim().isEmpty()) {
            throw DockerEngineClients.dockerInvalidRequest("CreateSnapshot",
                    "provider returned an empty snapshot ID");
        }
        String canonical = canonicalSnapshotID(reference);
        return new SnapshotRef(canonical, List.of(canonical));
    }

    /**
     * 对照 DeleteSnapshot：缺失的镜像不是错误（reaper 与 install 补偿路径都会重试删）。
     * PruneChildren 才真正回收存储：第 N+1 代持有第 N 代的层做祖先，级联删由 daemon
     * 按 live 引用计数保护。
     */
    @Override
    public void deleteSnapshot(String snapshotId) {
        String id = snapshotId == null ? "" : snapshotId.trim();
        if (id.isEmpty()) {
            throw DockerEngineClients.dockerInvalidRequest("DeleteSnapshot",
                    "snapshot ID is required");
        }
        try {
            DockerEngineClients.rpc(null, () -> {
                api.imageRemove(id);
                return null;
            });
        } catch (RuntimeException e) {
            RemoteError normalized = DockerEngineClients.dockerError("DeleteSnapshot", e);
            if (normalized.kind == RemoteErrorKind.NOT_FOUND) {
                return;
            }
            throw normalized;
        }
        pruneDanglingSkillImages();
    }

    /**
     * 对照 pruneDanglingSkillImages：丢掉不再带 tag 的技能快照镜像。天性 best effort
     * ——回收存储绝不能把成功的删除变成失败；被容器持有的镜像以 conflict 返回被跳过。
     */
    private void pruneDanglingSkillImages() {
        List<DockerEngineClients.ImageListItem> listed;
        try {
            listed = DockerEngineClients.rpc(null, () -> api.imageList(true,
                    List.of(DOCKER_SKILL_SNAPSHOT_LABEL + "=true")));
        } catch (RuntimeException e) {
            return;
        }
        for (DockerEngineClients.ImageListItem item : listed) {
            if (!imageIsSkillSnapshot(item) || imageHasTag(item)) {
                continue;
            }
            String id = item.id() == null ? "" : item.id().trim();
            if (id.isEmpty()) {
                continue;
            }
            try {
                DockerEngineClients.rpc(null, () -> {
                    api.imageRemove(id);
                    return null;
                });
            } catch (RuntimeException ignored) {
                // 下轮清扫重试
            }
        }
    }

    static boolean imageHasTag(DockerEngineClients.ImageListItem item) {
        if (item.repoTags() == null) {
            return false;
        }
        for (String tag : item.repoTags()) {
            if (tag != null && !tag.trim().isEmpty() && !"<none>:<none>".equals(tag)) {
                return true;
            }
        }
        return false;
    }

    static boolean imageIsSkillSnapshot(DockerEngineClients.ImageListItem item) {
        if (item.labels() != null && "true".equals(item.labels().get(DOCKER_SKILL_SNAPSHOT_LABEL))) {
            return true;
        }
        if (item.repoTags() != null) {
            for (String tag : item.repoTags()) {
                if (isSkillSnapshotRef(tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 对照 ListSnapshots：空 sandboxId 列全部，非空按 source 过滤。 */
    @Override
    public List<SnapshotRef> listSnapshots(String sandboxId) {
        String wantSource = sandboxId == null ? "" : sandboxId.trim();
        List<String> filters = new ArrayList<>();
        filters.add(DOCKER_SKILL_SNAPSHOT_LABEL + "=true");
        if (!wantSource.isEmpty()) {
            filters.add(DOCKER_SKILL_SNAPSHOT_SOURCE_LABEL + "=" + wantSource);
        }
        List<DockerEngineClients.ImageListItem> listed;
        try {
            listed = shortRpc("ListSnapshots", () -> api.imageList(true, filters));
        } catch (RemoteError e) {
            throw e;
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError("ListSnapshots", e);
        }
        List<SnapshotRef> out = new ArrayList<>();
        for (DockerEngineClients.ImageListItem item : listed) {
            if (!imageIsSkillSnapshot(item)) {
                continue;
            }
            if (!wantSource.isEmpty()
                    && (item.labels() == null
                            || !wantSource.equals(item.labels().get(DOCKER_SKILL_SNAPSHOT_SOURCE_LABEL)))) {
                continue;
            }
            out.add(snapshotRefOf(item));
        }
        return out;
    }

    static SnapshotRef snapshotRefOf(DockerEngineClients.ImageListItem item) {
        List<String> names = new ArrayList<>();
        String id = item.id() == null ? "" : item.id().trim();
        if (item.repoTags() != null) {
            for (String tag : item.repoTags()) {
                if (tag == null || tag.isEmpty() || "<none>:<none>".equals(tag)) {
                    continue;
                }
                String canonical = canonicalSnapshotID(tag);
                names.add(canonical);
                if (isSkillSnapshotRef(canonical)
                        && (id.isEmpty() || !isSkillSnapshotRef(id))) {
                    id = canonical;
                }
            }
        }
        if (id.isEmpty() && !names.isEmpty()) {
            id = names.get(0);
        }
        return new SnapshotRef(id, names);
    }

    /** 对照 dockerSkillSnapshotReference。 */
    public static String skillSnapshotReference(String name, String sandboxId) {
        String base = sanitizeImageName(name);
        if (base.isEmpty()) {
            base = sanitizeImageName(sandboxId);
        }
        if (base.isEmpty()) {
            throw DockerEngineClients.dockerInvalidRequest("CreateSnapshot",
                    "snapshot name is required");
        }
        return DOCKER_SKILL_SNAPSHOT_REPO + "/" + base;
    }

    /** 对照 dockerIsSkillSnapshotRef：ImageList 偶尔带 docker.io/ 前缀（只是别名）。 */
    public static boolean isSkillSnapshotRef(String ref) {
        String trimmed = ref == null ? "" : ref.trim();
        String prefix = DOCKER_SKILL_SNAPSHOT_REPO + "/";
        if (trimmed.startsWith(prefix)) {
            return true;
        }
        return trimmed.startsWith("docker.io/" + prefix);
    }

    /** 对照 dockerCanonicalSnapshotID：剥 docker.io/ 前缀与 :latest 后缀。 */
    public static String canonicalSnapshotID(String ref) {
        String trimmed = ref == null ? "" : ref.trim();
        if (trimmed.startsWith("docker.io/")) {
            trimmed = trimmed.substring("docker.io/".length());
        }
        if (trimmed.endsWith(":latest")) {
            trimmed = trimmed.substring(0, trimmed.length() - ":latest".length());
        }
        return trimmed;
    }

    /**
     * 对照 dockerSanitizeImageName：install 生成的快照名映射到单个 Docker 路径组件
     * ——小写 [a-z0-9]，内部以 - 分隔，掐头去尾，上限 80 字符。
     */
    public static String sanitizeImageName(String raw) {
        StringBuilder b = new StringBuilder();
        boolean lastSep = true;
        String lowered = raw == null ? "" : raw.trim().toLowerCase();
        for (int i = 0; i < lowered.length(); ) {
            int cp = lowered.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                b.appendCodePoint(cp);
                lastSep = false;
            } else if (cp == '.' || cp == '_' || cp == '-') {
                if (lastSep || b.length() == 0) {
                    continue;
                }
                b.append('-');
                lastSep = true;
            }
        }
        String out = trimDashes(b.toString());
        final int maxName = 80;
        if (out.length() > maxName) {
            out = trimDashes(out.substring(0, maxName));
        }
        return out;
    }

    private static String trimDashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '-') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '-') {
            end--;
        }
        return s.substring(start, end);
    }

    // ── 模板目录（对照 docker_template_catalog.go 全文） ─────────────────

    /** 后台 pull 的进程级记录：第二个 refresh 报告在途 pull 而不是再起一个。 */
    private static final class PullRecord {

        final Instant started;
        volatile boolean done;
        volatile String error;

        PullRecord(Instant started, boolean done, String error) {
            this.started = started;
            this.done = done;
            this.error = error;
        }
    }

    private static final ConcurrentHashMap<String, PullRecord> BACKGROUND_PULLS =
            new ConcurrentHashMap<>();

    /** 对照 ListTemplates：daemon 上的沙箱镜像就是模板目录。 */
    public List<RemoteTemplate> listTemplates() {
        List<DockerEngineClients.ImageListItem> listed;
        try {
            listed = shortRpc("ListTemplates", () -> api.imageList(false, null));
        } catch (RemoteError e) {
            throw e;
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError("ListTemplates", e);
        }
        String configured = settings.image() == null ? "" : settings.image().trim();
        boolean configuredPresent = false;
        List<RemoteTemplate> templates = new ArrayList<>();
        for (DockerEngineClients.ImageListItem image : listed) {
            boolean snapshot = imageIsSkillSnapshot(image);
            if (image.repoTags() == null) {
                continue;
            }
            for (String tag : image.repoTags()) {
                if (tag == null || tag.isEmpty() || "<none>:<none>".equals(tag)) {
                    continue;
                }
                // 技能快照是镜像，否则会像 Cube 的 snap- ID 一样混进"挑基础模板"列表
                if (snapshot && !tag.equals(configured)) {
                    continue;
                }
                boolean standard = RemoteTemplate.isStandardTemplate(tag);
                if (!standard && !snapshot
                        && (image.labels() == null
                                || !"true".equals(image.labels().get(DOCKER_TEMPLATE_LABEL)))
                        && !tag.equals(configured)) {
                    continue;
                }
                if (tag.equals(configured)) {
                    configuredPresent = true;
                }
                RemoteTemplate template = new RemoteTemplate();
                template.id = tag;
                template.name = tag;
                template.status = "ready";
                template.image = tag;
                template.version = image.id();
                template.standard = standard;
                template.createdAt = DateTimeFormatter.ISO_INSTANT.format(
                        Instant.ofEpochSecond(image.createdEpochSec()));
                templates.add(template);
            }
        }
        // 配置的镜像还没拉，它仍然是这份配置要用的模板；藏起来等于告诉管理员
        // 自己的选择不存在，缺的只是拉一次
        if (!configured.isEmpty() && !configuredPresent) {
            templates.add(pendingTemplate(configured));
        }
        return templates;
    }

    /** 对照 pendingTemplate：尚未在 daemon 上的镜像，报告后台 pull 的结局。 */
    private RemoteTemplate pendingTemplate(String image) {
        RemoteTemplate template = new RemoteTemplate();
        template.id = image;
        template.name = image;
        template.image = image;
        template.status = "missing";
        template.standard = RemoteTemplate.isStandardTemplate(image);
        PullRecord state = BACKGROUND_PULLS.get(pullKey(image));
        if (state == null) {
            return template;
        }
        if (!state.done) {
            template.status = "building";
        } else if (state.error != null) {
            template.status = "failed";
            template.error = state.error;
        }
        return template;
    }

    /** 对照 EnsureStandardTemplate：配置镜像缺失时后台拉起，pull 期间报 building。 */
    public RemoteTemplate ensureStandardTemplate() {
        String image = settings.image() == null || settings.image().trim().isEmpty()
                ? EffectiveConfig.DEFAULT_DOCKER_IMAGE : settings.image().trim();
        try {
            shortRpc("EnsureStandardTemplate", () -> api.imageInspect(image));
            RemoteTemplate template = new RemoteTemplate();
            template.id = image;
            template.name = image;
            template.image = image;
            template.status = "ready";
            template.standard = RemoteTemplate.isStandardTemplate(image);
            return template;
        } catch (RuntimeException inspectErr) {
            // 落到后台 pull
        }
        String key = pullKey(image);
        boolean[] alreadyRunning = {false};
        BACKGROUND_PULLS.compute(key, (k, existing) -> {
            if (existing != null && !existing.done) {
                alreadyRunning[0] = true;
                return existing;
            }
            return new PullRecord(Instant.now(), false, null);
        });
        if (alreadyRunning[0]) {
            return pendingTemplate(image);
        }
        Thread.ofVirtual().start(() -> pullInBackground(key, image));
        return pendingTemplate(image);
    }

    /** 对照 ReplaceStandardTemplate：就是一次配置镜像的 pull。 */
    public RemoteTemplate replaceStandardTemplate() {
        return ensureStandardTemplate();
    }

    /** 对照 DeleteSupersededStandardTemplates：Docker 没有集群侧模板对象，no-op。 */
    public void deleteSupersededStandardTemplates(String standardId) {
        // no-op
    }

    private void pullInBackground(String key, String image) {
        String error = null;
        try {
            DockerEngineClients.rpc(DOCKER_IMAGE_PULL_BUDGET, () -> {
                api.imagePull(image);
                return null;
            });
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        if (error == null) {
            // 完成的 pull 不留状态：镜像已对 ImageInspect 可见，比记账更真
            BACKGROUND_PULLS.remove(key);
        } else {
            BACKGROUND_PULLS.put(key, new PullRecord(Instant.now(), true, error));
        }
    }

    private String pullKey(String image) {
        return settings.endpoint().key() + "|" + image;
    }

    // ── 空闲清扫（对照 docker_idle_sweeper.go 全文） ─────────────────────

    /** 供测试断言（forCheck 无 sweeper）。 */
    IdleSweeper sweeper() {
        return sweeper;
    }

    private void sweepInBackground() {
        if (sweeper != null) {
            sweeper.trigger();
        }
    }

    /**
     * daemon 没有原生空闲超时：每个 exec 触碰容器内的活动标记（wrapper 免费搭车），
     * 清扫对每个容器用一次 HEAD /archive 读 mtime 判定回收。删除空闲容器不需要与
     * 绑定存储协调——生命周期本就把"provider 已没有的沙箱"当作可替换。
     */
    static final class IdleSweeper {

        private static final ConcurrentHashMap<String, Instant> SWEEP_THROTTLE =
                new ConcurrentHashMap<>();

        private final DockerSandboxClient client;
        private final Duration ttl;
        private final Supplier<Instant> now;

        IdleSweeper(DockerSandboxClient client, Duration ttl) {
            this(client, ttl, Instant::now);
        }

        IdleSweeper(DockerSandboxClient client, Duration ttl, Supplier<Instant> now) {
            this.client = client;
            this.ttl = ttl;
            this.now = now;
        }

        /**
         * 对照 trigger：后台清扫，除非最近刚扫过。脱离调用方（虚拟线程），失败只记日志
         * ——回收存储绝不能把一次正常执行变成错误。
         */
        void trigger() {
            if (!claimSweep()) {
                return;
            }
            Thread.ofVirtual().start(() -> {
                try {
                    int reclaimed = sweep();
                    if (reclaimed > 0) {
                        LOG.info("[sandbox] docker idle sweep reclaimed {} container(s)", reclaimed);
                    }
                } catch (RuntimeException e) {
                    LOG.warn("[sandbox] docker idle sweep failed: {}", e.getMessage());
                }
            });
        }

        /** 对照 claimSweep：每 daemon 端点限一分钟一扫。 */
        boolean claimSweep() {
            String key = client.settings.endpoint().key();
            Instant stamp = now.get();
            boolean[] won = {false};
            SWEEP_THROTTLE.compute(key, (k, last) -> {
                if (last != null && Duration.between(last, stamp).compareTo(DOCKER_SWEEP_MIN_INTERVAL) < 0) {
                    return last;
                }
                won[0] = true;
                return stamp;
            });
            return won[0];
        }

        /** 对照 sweep：删掉每一个空闲超过自身 TTL 的受管容器。 */
        int sweep() {
            List<Summary> summaries = client.list(new ListFilter(null, null));
            int reclaimed = 0;
            for (Summary summary : summaries) {
                if (!isIdle(summary)) {
                    continue;
                }
                // 删除前立即复查：列全量加逐个 stat 在忙 daemon 上足够久，
                // 期间会话可能刚好被 resume，删了就毁掉用户正在用的沙箱
                if (!isIdle(summary)) {
                    continue;
                }
                try {
                    client.delete(summary.id());
                } catch (RuntimeException e) {
                    // 一个删不掉的容器不能停掉整轮清扫；下轮再试
                    LOG.warn("[sandbox] docker idle sweep: delete {}: {}",
                            summary.id(), e.getMessage());
                    continue;
                }
                reclaimed++;
            }
            return reclaimed;
        }

        boolean isIdle(Summary summary) {
            Duration effectiveTTL = ttlFor(summary);
            if (effectiveTTL == null || effectiveTTL.isZero() || effectiveTTL.isNegative()) {
                return false;
            }
            Instant lastUsed = lastActivity(summary);
            if (lastUsed == null) {
                // 标记与创建时间都拿不到：当 idle 处理会误删一个只是暂时读不了的沙箱
                return false;
            }
            return Duration.between(lastUsed, now.get()).compareTo(effectiveTTL) > 0;
        }

        /** 对照 ttlFor：容器创建时盖的 TTL 标记优先于本客户端自己的配置。 */
        Duration ttlFor(Summary summary) {
            if (summary.metadata() != null) {
                String raw = summary.metadata().get(DOCKER_IDLE_TTL_LABEL);
                if (raw != null) {
                    try {
                        return Duration.ofSeconds(Long.parseLong(raw.trim()));
                    } catch (NumberFormatException ignored) {
                        // 落到配置值
                    }
                }
            }
            return ttl;
        }

        /**
         * 对照 lastActivity：容器最后一次跑命令的时间；什么都没跑过回落到启动时间。
         * 标记 mtime 受容器内脚本影响（touch -d）：未来时间戳会永久禁用回收，
         * 直接拒绝并回落启动时间；回拨只会让沙箱显得更空闲，代价自负。
         */
        Instant lastActivity(Summary summary) {
            try {
                DockerEngineClients.ContainerPathStat stat = DockerEngineClients.rpc(
                        client.settings.httpTimeout(),
                        () -> client.api.containerStatPath(summary.id(), DOCKER_ACTIVITY_MARKER));
                if (stat != null && stat.mtime() != null && !stat.mtime().isEmpty()) {
                    Instant marker = OffsetDateTime.parse(stat.mtime().trim()).toInstant();
                    if (marker.isAfter(now.get().plus(DOCKER_ACTIVITY_CLOCK_SKEW))) {
                        LOG.warn("[sandbox] docker idle sweep: container {} reports activity "
                                        + "at {}, which is in the future; falling back to its start time",
                                summary.id(), marker);
                    } else {
                        return marker;
                    }
                }
            } catch (RuntimeException e) {
                // stat 失败回落启动时间（rpc 的超时也是 RuntimeException 哨兵）
            }
            return summary.startedAt() == null ? null : summary.startedAt().toInstant();
        }
    }

    // ── 共用工具 ─────────────────────────────────────────────────────────

    /** 对照 dockerSandboxHandle：管理器持有的不透明句柄。 */
    public record DockerHandle(String id, Map<String, String> metadata)
            implements SandboxSessionClient.Handle {

        @Override
        public String provider() {
            return SandboxTypes.TYPE_DOCKER;
        }
    }

    /** 对照 dockerHandleID：句柄必须属于 docker 且带 ID。 */
    static String dockerHandleID(String op, Handle handle) {
        if (handle == null) {
            throw DockerEngineClients.dockerInvalidRequest(op, "sandbox handle is required");
        }
        if (!SandboxTypes.TYPE_DOCKER.equals(handle.provider())) {
            throw DockerEngineClients.dockerInvalidRequest(op,
                    "handle belongs to provider " + handle.provider());
        }
        String id = handle.id() == null ? "" : handle.id().trim();
        if (id.isEmpty()) {
            throw DockerEngineClients.dockerInvalidRequest(op, "sandbox handle has no ID");
        }
        return id;
    }

    /** 对照 dockerEnvSlice：map → KEY=VALUE 列表。 */
    static List<String> dockerEnvSlice(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return null;
        }
        List<String> pairs = new ArrayList<>(env.size());
        for (Map.Entry<String, String> e : env.entrySet()) {
            pairs.add(e.getKey() + "=" + e.getValue());
        }
        return pairs;
    }

    /** Go time.Parse(RFC3339Nano) 失败 → null（Go 零值语义由 null 表达）。 */
    static OffsetDateTime parseTime(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 对照 Get 的 finished 判定：可解析且 year &gt; 1 才是真实结束时间。 */
    static OffsetDateTime finishedAtOrNull(String raw) {
        OffsetDateTime parsed = parseTime(raw);
        if (parsed != null && parsed.getYear() > 1) {
            return parsed.withOffsetSameInstant(ZoneOffset.UTC);
        }
        return null;
    }

    private static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    private static String utf8(byte[] b) {
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    private <T> T shortRpc(String op, DockerEngineClients.DockerRpc<T> call) {
        try {
            return DockerEngineClients.rpc(settings.httpTimeout(), call);
        } catch (RuntimeException e) {
            throw DockerEngineClients.dockerError(op, e);
        }
    }

    /** 总闸：docker 后端未启用时 Create/Connect/Exec 直接拒绝。 */
    private static void requireBackendEnabled() {
        SandboxBackendPolicy.ensureDockerBackendAllowed(SandboxTypes.TYPE_DOCKER);
    }
}
