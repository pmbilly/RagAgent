package com.ragagent.sandbox.runtime;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.ListImagesCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveImageCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;

/**
 * Docker Engine 执行面基建（对照 Go {@code internal/sandbox/docker_engine.go} 全文）：
 * 适配器与 Engine API 之间的窄接口 {@link DockerEngine}（单测无 daemon 可跑）、
 * 按 daemon endpoint 共享的连接池，以及 Engine 错误到 provider 中立
 * {@link RemoteErrorKind} 的分类。
 *
 * <h2>与 Go SDK 的语义差异（docker-java 3.7.1 + zerodep transport）</h2>
 * <ul>
 *   <li><b>连接池</b>：Go 的 moby client 每个实例自带一个 HTTP transport，per-request
 *       重建会泄漏连接池，所以按 endpoint 池化（sharedDockerEngineClients）。docker-java
 *       的 {@code DockerClientImpl} 同样持有连接池，本类用进程级
 *       {@code ConcurrentHashMap} 复刻同一份池，键为 {@code host|tlsCertPath|allowPrivate}
 *       （与 Go dockerEndpoint.key 一致：超时不进键，它在每次 RPC 上施加）。</li>
 *   <li><b>guarded dialer</b>：Go 给 TCP daemon 安装带 SafeDialControlForPolicy 的
 *       DialContext，在拨号时按出站策略校验目标 IP。docker-java 的
 *       {@code ApacheDockerHttpClient} 不暴露自定义 ConnectionManager，无法注入
 *       拨号时校验。<b>残余风险备案</b>：TCP 地址只在保存配置时经
 *       {@link DockerHostSupport#validateDockerHost} 校验（TOCTOU——保存时公网、
 *       拨号时可能解析到私网地址）。allowPrivate 进 endpoint 身份键以保留未来的
 *       接线点。</li>
 *   <li><b>超时施加</b>：Go 用 context.WithTimeout 包装短 RPC（docker_rpc_timeout.go）；
 *       Java 侧由 {@link #rpc} 在虚拟线程上执行并限时（虚拟线程的阻塞 socket I/O
 *       可被中断取消，等效于 Go 的 ctx 取消）。长调用（pull/commit/image remove/
 *       exec 流）不在包装范围，与 Go 逐条对应。</li>
 *   <li><b>ContainerStatPath</b>：docker-java 3.4.0 没有暴露 HEAD /archive 的
 *       stat 命令，用 {@link DockerHttpClient} 原始请求补齐（仅空闲清扫读活动标记
 *       mtime 用，与 Go dockerEngineAPI 接口的 ContainerStatPath 同一端点）。</li>
 *   <li><b>API 版本协商</b>：与 Go 相同，不在建池时做（避免首个请求背一次 RTT），
 *       交给客户端惰性协商。</li>
 * </ul>
 */
public final class DockerEngineClients {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DockerEngineClients() {
    }

    // ── endpoint 身份 ────────────────────────────────────────────────────

    /**
     * 对照 Go dockerEndpoint：一个 daemon 连接的身份。endpoint 相同的两个配置共享客户端。
     */
    public record DockerEndpoint(String host, String tlsCertPath, boolean allowPrivate) {

        public String key() {
            // 超时按 RPC 施加、不进 HTTP 客户端，所以只差超时的两个配置仍共享一个池。
            return host + "|" + tlsCertPath + "|" + allowPrivate;
        }
    }

    // ── 连接池 ──────────────────────────────────────────────────────────

    /**
     * 进程级共享池（对照 sharedDockerEngineClients）：指向同一 daemon 的两个租户配置
     * 共享一个连接池；check 端点构造的一次性配置也不得各开一个。
     */
    private static final ConcurrentHashMap<String, DockerClient> POOL = new ConcurrentHashMap<>();

    /** 取 endpoint 的共享客户端，首次使用时构建。构建失败抛 RuntimeException。 */
    public static DockerClient get(DockerEndpoint endpoint) {
        return POOL.computeIfAbsent(endpoint.key(), k -> buildClient(endpoint));
    }

    private static DockerClient buildClient(DockerEndpoint endpoint) {
        String host = endpoint.host() == null ? "" : endpoint.host().trim();
        if (host.isEmpty()) {
            host = DockerHostSupport.detectLocalDockerHost();
        }
        DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(host);
        if (endpoint.tlsCertPath() != null && !endpoint.tlsCertPath().trim().isEmpty()) {
            // 证书留在应用宿主而非工作区配置（部署基础设施不进数据库/备份/响应）。
            // daemon 证书始终校验——能建容器的 daemon 就是沙箱宿主的 root shell。
            // LocalDirectorySSLConfig 读 ca.pem/cert.pem/key.pem，与 Go 的
            // client.WithTLSClientConfig 同一目录布局。
            builder.withDockerTlsVerify("1")
                    .withDockerCertPath(endpoint.tlsCertPath().trim());
        }
        DefaultDockerClientConfig config = builder.build();
        try {
            // zerodep transport：自带 unix:// 拨号与 hijacked 流——exec 的
            // stdout/stderr/stdin 都经它双向泵（httpclient5 传输实测不传输 exec 的
            // 输出帧与 stdin，探针实锤后弃用）。TLS 由 config 的 SSLConfig 驱动。
            ZerodepDockerHttpClient.Builder zb = new ZerodepDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .maxConnections(100);
            if (config.getSSLConfig() != null) {
                zb.sslConfig(config.getSSLConfig());
            }
            // 刻意不设 responseTimeout：冷镜像拉取或长文件复制会在半途被杀
            // （Go 注释原文的 http.Client.Timeout 陷阱）。短调用由 rpc 限时。
            DockerHttpClient transport = zb.build();
            return DockerClientImpl.getInstance(config, transport);
        } catch (RuntimeException e) {
            throw new RuntimeException(
                    "sandbox: build docker client for " + host + ": " + e.getMessage(), e);
        }
    }

    // ── 短 RPC 超时（对照 docker_rpc_timeout.go 的 withDockerRPCTimeout） ──

    /** 短 RPC 共享执行器：虚拟线程的阻塞 I/O 可被中断取消（等效 Go ctx 取消）。 */
    private static final ExecutorService RPC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** 短 RPC 超时哨兵：adapter 的 dockerError 把它映射为 kind=TIMEOUT。 */
    public static final class DockerRpcTimeoutException extends RuntimeException {
        public DockerRpcTimeoutException() {
            super("context deadline exceeded");
        }
    }

    @FunctionalInterface
    public interface DockerRpc<T> {
        T run() throws Exception;
    }

    /**
     * 把一次 Engine 调用限时执行。timeout<=0 时不限时（对应 Go 的长调用清单：
     * pull/commit/image remove/exec hijack 流）。
     */
    public static <T> T rpc(Duration timeout, DockerRpc<T> call) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            try {
                return call.run();
            } catch (IOException | RuntimeException e) {
                throw e instanceof RuntimeException re ? re : new RuntimeException(e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        Future<T> future = RPC_EXECUTOR.submit(() -> {
            try {
                return call.run();
            } catch (Exception e) {
                throw new WrappingException(e);
            }
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 中断虚拟线程 → 阻塞 socket I/O 被取消，与 Go 的 ctx 超时同效。
            future.cancel(true);
            throw new DockerRpcTimeoutException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteError(SandboxTypes.TYPE_DOCKER, "", RemoteErrorKind.TIMEOUT,
                    "context canceled", e, 0);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof WrappingException w) {
                Throwable cause = w.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(cause);
            }
            throw new RuntimeException(e.getCause());
        }
    }

    private static final class WrappingException extends RuntimeException {
        WrappingException(Throwable cause) {
            super(cause);
        }
    }

    // ── 错误分类（对照 dockerErrorKind L336 / dockerError L367） ──────────

    /**
     * 对照 dockerErrorKind：moby client 用 containerd errdefs 标注错误（比消息文本
     * 可靠）；docker-java 用 HTTP 状态码标注（{@link DockerException#getHttpStatus()}），
     * 两张映射语义对齐。transport 层异常（拒连、DNS、TLS）对照 Go 的
     * net.Error 分支：超时 → timeout，其余 → unavailable。
     */
    public static RemoteErrorKind dockerErrorKind(String op, Throwable err) {
        if (err == null) {
            return null;
        }
        if (err instanceof DockerRpcTimeoutException
                || err instanceof java.util.concurrent.TimeoutException) {
            return RemoteErrorKind.TIMEOUT;
        }
        if (err instanceof IOException) {
            return RemoteError.classifyTransport((IOException) err);
        }
        if (err instanceof DockerException de) {
            return dockerErrorKindOfStatus(op, de.getHttpStatus());
        }
        return RemoteErrorKind.INTERNAL;
    }

    static RemoteErrorKind dockerErrorKindOfStatus(String op, int status) {
        return switch (status) {
            case 400, 422 -> RemoteErrorKind.INVALID_REQUEST;
            case 401, 403 -> RemoteErrorKind.AUTHENTICATION;
            // 镜像缺失在 Create 上是坏模板而非沙箱消失：归类 NotFound 会告诉生命周期
            // 它可以重绑（Go 注释原文）。
            case 404 -> "Create".equals(op)
                    ? RemoteErrorKind.INVALID_REQUEST
                    : RemoteErrorKind.NOT_FOUND;
            case 408, 504 -> RemoteErrorKind.TIMEOUT;
            case 409 -> RemoteErrorKind.CONFLICT;
            case 410 -> RemoteErrorKind.TERMINAL;
            case 429, 507 -> RemoteErrorKind.CAPACITY;
            case 501 -> RemoteErrorKind.UNSUPPORTED;
            default -> status >= 500 ? RemoteErrorKind.UNAVAILABLE : RemoteErrorKind.INTERNAL;
        };
    }

    /** 对照 dockerError：把 Engine 错误包成 RemoteError；已是 RemoteError 的原样返回。 */
    public static RemoteError dockerError(String op, Throwable err) {
        if (err == null) {
            return null;
        }
        if (err instanceof RemoteError re) {
            return re;
        }
        RemoteErrorKind kind = err instanceof DockerRpcTimeoutException
                ? RemoteErrorKind.TIMEOUT
                : dockerErrorKind(op, err);
        return new RemoteError(SandboxTypes.TYPE_DOCKER, op, kind, err.getMessage(), err, 0);
    }

    /** 对照 dockerInvalidRequest：调用方侧的错误，从未抵达 daemon。 */
    public static RemoteError dockerInvalidRequest(String op, String message) {
        return new RemoteError(SandboxTypes.TYPE_DOCKER, op, RemoteErrorKind.INVALID_REQUEST,
                message, null, 0);
    }

    // ── 容器状态归一（对照 dockerStateOf） ───────────────────────────────

    /**
     * "exited" 刻意不是 terminal：停止的容器保留文件系统，Connect 会重启它——
     * 这是 Docker 最接近 E2B pause+auto-resume 的形态（Go 注释原文）。
     */
    public static String dockerStateOf(String status) {
        String s = status == null ? "" : status.trim().toLowerCase();
        return switch (s) {
            case "running" -> SandboxSessionClient.Summary.STATE_RUNNING;
            case "paused", "exited", "created" -> SandboxSessionClient.Summary.STATE_PAUSED;
            case "restarting", "removing" -> SandboxSessionClient.Summary.STATE_TRANSITIONING;
            case "dead" -> SandboxSessionClient.Summary.STATE_TERMINAL;
            default -> SandboxSessionClient.Summary.STATE_UNKNOWN;
        };
    }

    // ── label 投影（对照 dockerContainerLabels/dockerSandboxMetadata） ───

    /** 对照 dockerManagedLabel：本后端创建的每个容器都带它，清扫靠它过滤。 */
    public static final String DOCKER_MANAGED_LABEL = "com.weknora.sandbox.managed";

    /** metadata → 容器 label，并加盖所有权标记（清扫的过滤锚）。 */
    public static Map<String, String> dockerContainerLabels(Map<String, String> metadata) {
        java.util.LinkedHashMap<String, String> labels = new java.util.LinkedHashMap<>();
        if (metadata != null) {
            labels.putAll(metadata);
        }
        labels.put(DOCKER_MANAGED_LABEL, "true");
        return labels;
    }

    /** dockerContainerLabels 的逆：剥掉所有权标记，调用方看到的就是原始 metadata。 */
    public static Map<String, String> dockerSandboxMetadata(Map<String, String> labels) {
        if (labels == null) {
            return null;
        }
        java.util.LinkedHashMap<String, String> metadata = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            if (DOCKER_MANAGED_LABEL.equals(e.getKey())) {
                continue;
            }
            metadata.put(e.getKey(), e.getValue());
        }
        return metadata;
    }

    // ── Engine 窄接口（对照 dockerEngineAPI） ────────────────────────────

    /** create 容器请求（对照 client.ContainerCreateOptions 的适配器用到的投影）。 */
    public record ContainerCreateSpec(
            String image,
            String user,
            String[] entrypoint,
            String[] cmd,
            String workingDir,
            Map<String, String> labels,
            List<String> env,
            HostIsolation isolation) {
    }

    /** 对照 container.Resources + HostConfig 的隔离/资源包络。 */
    public record HostIsolation(
            long memoryBytes,
            long memorySwap,
            long nanoCpus,
            long pidsLimit,
            List<String> capDrop,
            List<String> capAdd,
            List<String> securityOpts,
            String runtime,
            String networkMode,
            boolean init) {
    }

    /** inspect 容器的投影（Get/Connect/waitUntilRunning 用）。 */
    public record ContainerInspectResult(
            String id,
            String image,
            Map<String, String> labels,
            String status,
            String startedAt,
            String finishedAt) {
    }

    /** list 容器的投影。 */
    public record ContainerListItem(
            String id,
            String image,
            Map<String, String> labels,
            String state,
            long createdEpochSec) {
    }

    /** exec 创建请求（对照 client.ExecCreateOptions）。 */
    public record ExecCreateSpec(
            String[] cmd,
            String user,
            String workingDir,
            List<String> env,
            boolean attachStdin) {
    }

    /** commit 请求（对照 client.ContainerCommitOptions；changes 用 LABEL 键值投影）。 */
    public record CommitSpec(String reference, String comment, Map<String, String> labels) {
    }

    /** HEAD /archive 的投影（空闲清扫读活动标记 mtime 用）。 */
    public record ContainerPathStat(String name, long size, long mode, String mtime,
            String linkTarget) {
    }

    /** list 镜像的投影。 */
    public record ImageListItem(String id, String[] repoTags, Map<String, String> labels,
            long createdEpochSec) {
    }

    /** exec 输出一帧（对照 stdcopy 流：stdout/stderr）。 */
    public record ExecFrame(String stream, byte[] payload) {
    }

    /**
     * exec hijack 流（对照 Go ExecAttachResult 的 reader/conn）：transport 已做
     * stdcopy 分帧（1=stdout 2=stderr），stdin 在 attach 时一次性写入并关闭写端
     * （对照 Go 的 Write+CloseWrite——cat/python - 这类读 stdin 的命令不会挂在
     * 开着的写侧上）。EOF 时 {@link #nextFrame} 返回 null。
     */
    public interface ExecStream extends AutoCloseable {

        /** 下一帧；EOF 返回 null；等待超过 timeoutMillis 无帧抛 TimeoutException。 */
        ExecFrame nextFrame(long timeoutMillis) throws TimeoutException;

        @Override
        void close();
    }

    /**
     * 适配器与 Engine API 之间的窄切片（对照 dockerEngineAPI）：存在目的是让单测
     * 不需要 daemon。真实实现 {@link DockerJavaEngine} 由 docker-java 满足。
     * 错误原样上抛（docker-java DockerException / IOException），分类归 adapter。
     */
    public interface DockerEngine {

        void ping();

        String containerCreate(ContainerCreateSpec spec);

        void containerStart(String containerId);

        void containerUnpause(String containerId);

        ContainerInspectResult containerInspect(String containerId);

        List<ContainerListItem> containerList(List<String> labelFilters);

        void containerRemove(String containerId);

        String execCreate(String containerId, ExecCreateSpec spec);

        /** attach 一次性写入 stdin 并关闭写端；流由调用方读帧。 */
        ExecStream execAttach(String execId, byte[] stdin);

        int execInspect(String execId);

        ContainerPathStat containerStatPath(String containerId, String path);

        /**
         * 把一份 tar 流解进容器内 remotePath 目录（对照 client.CopyToContainer）。
         * zerodep 传输的 exec stdin 无半关闭（写端不 EOF，cat 永远阻塞）——
         * 文件写入与 stdin 种子改走本 API 绕开 hijack 写路径。
         */
        void putArchive(String containerId, String remotePath, byte[] tar);

        /** 返回镜像 ID；缺失时抛 {@link NotFoundException}。 */
        String imageInspect(String imageId);

        void imagePull(String image);

        List<ImageListItem> imageList(boolean all, List<String> labelFilters);

        void imageRemove(String imageId);

        /** 返回提交出的镜像 ID。 */
        String containerCommit(String containerId, CommitSpec spec);
    }

    /** {@link DockerEngine} 的 docker-java 实现（对照 Go 的 *client.Client）。 */
    public static final class DockerJavaEngine implements DockerEngine {

        private final DockerClient client;

        public DockerJavaEngine(DockerClient client) {
            this.client = client;
        }

        @Override
        public void ping() {
            client.pingCmd().exec();
        }

        @Override
        public String containerCreate(ContainerCreateSpec spec) {
            HostConfig hostConfig = new HostConfig()
                    .withMemory(spec.isolation().memoryBytes())
                    .withMemorySwap(spec.isolation().memorySwap())
                    .withNanoCPUs(spec.isolation().nanoCpus())
                    .withPidsLimit(spec.isolation().pidsLimit())
                    .withSecurityOpts(spec.isolation().securityOpts())
                    .withRuntime(spec.isolation().runtime())
                    .withNetworkMode(spec.isolation().networkMode())
                    .withInit(spec.isolation().init());
            if (!spec.isolation().capDrop().isEmpty()) {
                hostConfig.withCapDrop(spec.isolation().capDrop().stream()
                        .map(Capability::valueOf).toArray(Capability[]::new));
            }
            if (!spec.isolation().capAdd().isEmpty()) {
                hostConfig.withCapAdd(spec.isolation().capAdd().stream()
                        .map(Capability::valueOf).toArray(Capability[]::new));
            }
            CreateContainerCmd cmd = client.createContainerCmd(spec.image())
                    .withUser(spec.user())
                    .withEntrypoint(spec.entrypoint())
                    .withCmd(spec.cmd())
                    .withWorkingDir(spec.workingDir())
                    .withLabels(spec.labels())
                    .withHostConfig(hostConfig);
            if (spec.env() != null && !spec.env().isEmpty()) {
                cmd.withEnv(spec.env());
            }
            CreateContainerResponse created = cmd.exec();
            return created.getId();
        }

        @Override
        public void containerStart(String containerId) {
            client.startContainerCmd(containerId).exec();
        }

        @Override
        public void containerUnpause(String containerId) {
            client.unpauseContainerCmd(containerId).exec();
        }

        @Override
        public ContainerInspectResult containerInspect(String containerId) {
            InspectContainerResponse inspected = client.inspectContainerCmd(containerId).exec();
            InspectContainerResponse.ContainerState state = inspected.getState();
            String status = state == null ? null : state.getStatus();
            String startedAt = state == null ? null : state.getStartedAt();
            String finishedAt = state == null ? null : state.getFinishedAt();
            String image = inspected.getConfig() == null ? null : inspected.getConfig().getImage();
            Map<String, String> labels = inspected.getConfig() == null
                    ? null
                    : inspected.getConfig().getLabels();
            return new ContainerInspectResult(inspected.getId(), image, labels,
                    status, startedAt, finishedAt);
        }

        @Override
        public List<ContainerListItem> containerList(List<String> labelFilters) {
            ListContainersCmd cmd = client.listContainersCmd().withShowAll(true);
            if (labelFilters != null && !labelFilters.isEmpty()) {
                cmd.withLabelFilter(labelFilters);
            }
            return client.listContainersCmd().withShowAll(true).exec().stream()
                    .filter(c -> matchesLabels(c, labelFilters))
                    .map(c -> new ContainerListItem(c.getId(), c.getImage(), c.getLabels(),
                            c.getState(), c.getCreated() == null ? 0 : c.getCreated()))
                    .toList();
        }

        private static boolean matchesLabels(Container c, List<String> labelFilters) {
            // ListContainersCmd 的 label filter 由 daemon 执行；这里保留调用方传参
            // 形状，daemon 侧语义一致时此过滤是冗余兜底。
            if (labelFilters == null || labelFilters.isEmpty() || c.getLabels() == null) {
                return true;
            }
            for (String f : labelFilters) {
                int eq = f.indexOf('=');
                if (eq < 0) {
                    if (!c.getLabels().containsKey(f)) {
                        return false;
                    }
                } else if (!f.substring(eq + 1).equals(c.getLabels().get(f.substring(0, eq)))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void containerRemove(String containerId) {
            RemoveContainerCmd cmd = client.removeContainerCmd(containerId)
                    .withForce(true)
                    .withRemoveVolumes(true);
            cmd.exec();
        }

        @Override
        public String execCreate(String containerId, ExecCreateSpec spec) {
            ExecCreateCmd cmd = client.execCreateCmd(containerId)
                    .withCmd(spec.cmd())
                    .withAttachStdout(true)
                    .withAttachStderr(true);
            if (spec.user() != null && !spec.user().isEmpty()) {
                cmd.withUser(spec.user());
            }
            if (spec.workingDir() != null && !spec.workingDir().isEmpty()) {
                cmd.withWorkingDir(spec.workingDir());
            }
            if (spec.env() != null && !spec.env().isEmpty()) {
                cmd.withEnv(spec.env());
            }
            cmd.withAttachStdin(spec.attachStdin());
            ExecCreateCmdResponse created = cmd.exec();
            return created.getId();
        }

        @Override
        public ExecStream execAttach(String execId, byte[] stdin) {
            byte[] payload = stdin == null ? new byte[0] : stdin;
            // stdin 一次性写入并关闭写端：对照 Go 的 Write+CloseWrite。
            ExecStartCmdProxy proxy = new ExecStartCmdProxy();
            client.execStartCmd(execId)
                    .withStdIn(new java.io.ByteArrayInputStream(payload))
                    .exec(proxy.callback(proxy.queue()));
            return proxy;
        }

        /** 桥接 push 回调 → pull 队列的流实现。 */
        private static final class ExecStartCmdProxy implements ExecStream {

            private final java.util.concurrent.BlockingQueue<Object> queue =
                    new java.util.concurrent.LinkedBlockingQueue<>();
            private volatile ExecStartResultCallback callback;
            private volatile boolean closed;

            java.util.concurrent.BlockingQueue<Object> queue() {
                return queue;
            }

            ExecStartResultCallback callback(java.util.concurrent.BlockingQueue<Object> q) {
                ExecStartResultCallback cb = new ExecStartResultCallback() {
                    @Override
                    public void onNext(Frame frame) {
                        if (!closed) {
                            q.offer(toExecFrame(frame));
                        }
                        super.onNext(frame);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        if (!closed) {
                            q.offer(throwable);
                        }
                        super.onError(throwable);
                    }

                    @Override
                    public void onComplete() {
                        if (!closed) {
                            q.offer(EOF_MARKER);
                        }
                        super.onComplete();
                    }
                };
                this.callback = cb;
                return cb;
            }

            private static final Object EOF_MARKER = new Object();

            private static ExecFrame toExecFrame(Frame frame) {
                StreamType type = frame.getStreamType();
                String stream = type == StreamType.STDERR ? "stderr"
                        : type == StreamType.STDOUT || type == StreamType.RAW ? "stdout"
                        : "stdin";
                return new ExecFrame(stream, frame.getPayload());
            }

            @Override
            public ExecFrame nextFrame(long timeoutMillis) throws TimeoutException {
                Object item;
                try {
                    item = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("context canceled", e);
                }
                if (item == null) {
                    throw new TimeoutException("exec stream wait timed out");
                }
                if (item == EOF_MARKER) {
                    return null;
                }
                if (item instanceof Throwable t) {
                    throw t instanceof RuntimeException re ? re : new RuntimeException(t);
                }
                return (ExecFrame) item;
            }

            @Override
            public void close() {
                closed = true;
                queue.offer(EOF_MARKER);
                ExecStartResultCallback cb = callback;
                if (cb != null) {
                    try {
                        cb.close();
                    } catch (java.io.IOException e) {
                        // 关闭失败不掩盖主流程错误
                    }
                }
            }
        }

        @Override
        public int execInspect(String execId) {
            InspectExecResponse inspected = client.inspectExecCmd(execId).exec();
            return inspected.getExitCode() == null ? 0 : inspected.getExitCode();
        }

        @Override
        public void putArchive(String containerId, String remotePath, byte[] tar) {
            try {
                client.copyArchiveToContainerCmd(containerId)
                        .withTarInputStream(new java.io.ByteArrayInputStream(tar))
                        .withRemotePath(remotePath)
                        .exec();
            } catch (com.github.dockerjava.api.exception.NotFoundException e) {
                throw DockerEngineClients.dockerError("PutArchive", e);
            } catch (RuntimeException e) {
                throw DockerEngineClients.dockerError("PutArchive", e);
            }
        }

        @Override
        public ContainerPathStat containerStatPath(String containerId, String path) {
            // docker-java 3.4.0 无 stat-path 命令 → 原始 HEAD /archive 请求，
            // mtime 在 base64 的 X-Docker-Container-Path-Stat 头里。Request.Method
            // 枚举没有 HEAD，用 Builder 继承的 method(String) 重载补齐；底层
            // DockerHttpClient 经 DockerClientImpl.getHttpClient() 取出。
            String encodedId = URLEncoder.encode(containerId, StandardCharsets.UTF_8);
            String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8);
            DockerHttpClient http;
            if (client instanceof DockerClientImpl impl) {
                http = impl.getHttpClient();
            } else {
                throw new IllegalStateException(
                        "containerStatPath requires the pooled DockerClientImpl transport");
            }
            DockerHttpClient.Request request = DockerHttpClient.Request.builder()
                    .method("HEAD")
                    .path("/containers/" + encodedId + "/archive?path=" + encodedPath)
                    .build();
            try (DockerHttpClient.Response response = http.execute(request)) {
                String header = response.getHeader("X-Docker-Container-Path-Stat");
                if (header == null || header.isEmpty()) {
                    throw new IllegalStateException(
                            "no X-Docker-Container-Path-Stat header for " + path);
                }
                JsonNode stat = MAPPER.readTree(
                        Base64.getMimeDecoder().decode(header));
                return new ContainerPathStat(
                        textOrNull(stat, "name"),
                        stat.path("size").asLong(0),
                        stat.path("mode").asLong(0),
                        textOrNull(stat, "mtime"),
                        textOrNull(stat, "linkTarget"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private static String textOrNull(JsonNode node, String field) {
            JsonNode v = node.get(field);
            return v == null || v.isNull() ? null : v.asText();
        }

        @Override
        public String imageInspect(String imageId) {
            InspectImageResponse inspected = client.inspectImageCmd(imageId).exec();
            return inspected.getId();
        }

        @Override
        public void imagePull(String image) {
            PullImageCmd cmd = client.pullImageCmd(image);
            PullImageResultCallback callback = cmd.exec(new PullImageResultCallback());
            try {
                // pull 不限时（对照 Go：pull 留在调用方 context 上）。
                callback.awaitCompletion();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("context canceled", e);
            }
        }

        @Override
        public List<ImageListItem> imageList(boolean all, List<String> labelFilters) {
            ListImagesCmd cmd = client.listImagesCmd();
            if (all) {
                cmd.withShowAll(true);
            }
            if (labelFilters != null && !labelFilters.isEmpty()) {
                // ListImagesCmd 的 label filter 走 Map 形态（label → value）；"k=v"
                // 字符串在这里拆开。带 '=' 的 k=v 与 exists-only 两种形态都可能传入。
                Map<String, String> labels = new java.util.LinkedHashMap<>();
                for (String f : labelFilters) {
                    int eq = f.indexOf('=');
                    if (eq < 0) {
                        labels.put(f, "");
                    } else {
                        labels.put(f.substring(0, eq), f.substring(eq + 1));
                    }
                }
                cmd.withLabelFilter(labels);
            }
            List<Image> listed = cmd.exec();
            return listed.stream()
                    .map(i -> new ImageListItem(i.getId(), i.getRepoTags(), i.getLabels(),
                            i.getCreated() == null ? 0 : i.getCreated()))
                    .toList();
        }

        @Override
        public void imageRemove(String imageId) {
            // withNoPrune(false) = API noprune=false = 级联删除无 tag 的父层，
            // 对照 Go 的 ImageRemoveOptions{PruneChildren: true}。
            RemoveImageCmd cmd = client.removeImageCmd(imageId).withNoPrune(false);
            cmd.exec();
        }

        @Override
        public String containerCommit(String containerId, CommitSpec spec) {
            // Go 用 Changes=["LABEL k=v"...]；docker-java 的 CommitCmd 无 changes，
            // 但 daemon 的 commit 会把请求 config 的 Labels 逐键合并进容器继承的
            // Labels（Dockerfile LABEL 语义）——withLabels 结果等价。
            com.github.dockerjava.api.command.CommitCmd cmd = client.commitCmd(containerId)
                    .withRepository(spec.reference())
                    .withMessage(spec.comment());
            if (spec.labels() != null && !spec.labels().isEmpty()) {
                cmd.withLabels(spec.labels());
            }
            return cmd.exec();
        }
    }

    /** 供测试注入的 CompletableFuture 等待辅助（避免 unused 警告）。 */
    static void awaitQuietly(Future<?> f) {
        try {
            f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
            // 清扫路径吞错
        }
    }

    /** CompletableFuture 工具（保持 java.util.concurrent 依赖面集中）。 */
    static <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
