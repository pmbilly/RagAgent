package com.ragagent.browserskill.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.browserskill.domain.AccountStatus;
import com.ragagent.browserskill.domain.BrowserStatus;
import com.ragagent.browserskill.domain.ClusterRequest;
import com.ragagent.browserskill.domain.DeviceRecord;
import com.ragagent.browserskill.domain.PairingRecord;
import com.ragagent.browserskill.domain.RpcError;
import com.ragagent.browserskill.domain.Scope;
import com.ragagent.browserskill.domain.TaskInterruption;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对照 Go {@code browserskill.Manager}（manager.go 1189 行 + authorization.go +
 * cluster.go + daemon.go + focus.go + human.go + stop.go + result.go 的组合）：
 * 把已认证用户接到一个未经修改的 BrowserSkill 守护进程上；浏览器操作、tab 同意与
 * 命令调度都留在上游。
 *
 * <h2>翻译边界（波 3 browserskill 批）</h2>
 * <ul>
 *   <li><b>全量翻译</b>：授权面（Pair/Revoke/AuthorizeHTTP/Account/authenticate/
 *       renew/exchange）、HTTP 面的失败分类（extension WS 握手、internal 签名族、
 *       extension 下载）、守护进程启动失败分类（exec + daemon.json 轮询的四种
 *       错误文案）、集群转发的配置判定（validInternalURL/clusterSecret 长度）。</li>
 *   <li><b>波 4 agent tools 接缝（执行循环）</b>：Call/Control 的 RPC 执行段、
 *       extension WS 升级后的双向转发（pump/relayHandshake/receiveUI）。这些路径
 *       的 HTTP 可达性为零——Call/Control 只经 internal 路由（nodeID 每进程随机
 *       不可预知）或 session/:id/local-browser 路由（随波 4 翻译）触达；dev 部署
 *       守护进程永远起不来（BROWSERSKILL_BINARY 指向失败命令），连接失败分类在
 *       ensureDaemon/daemon 拨号处已按 Go 逐字对齐。rpc() 复刻 unix socket 拨号
 *       失败分类（"BrowserSkill daemon unavailable"），拨号成功即接缝。</li>
 * </ul>
 *
 * <h2>dev 确定性（golden 全部落在这里）</h2>
 * <p>BROWSERSKILL_BINARY 未设 → Enabled()=false → /me/browser POST 503、
 * authorize 503 "browser unavailable"、extension 503 "unavailable"、
 * internal 404 "unavailable"（secret 短）、download 404 "extension package is
 * not configured"。设为失败命令后：pair 成功、authorize 兑换成功、WS 握手
 * 落 503 "browser runtime unavailable"（daemon 起不来）——两侧逐字节一致。</p>
 */
public class BrowserSkillManager {

    private static final Logger log = LoggerFactory.getLogger(BrowserSkillManager.class);

    /** 对照 AuthProtocol：带凭据的 WebSocket 子协议前缀（manager.go L28-31） */
    public static final String AUTH_PROTOCOL = "bsk-auth.";

    static final Duration PAIRING_LIFETIME = Duration.ofMinutes(5);
    static final Duration DEVICE_LIFETIME = Duration.ofDays(90);
    static final Duration RENEWAL_INTERVAL = Duration.ofDays(30);

    /** 对照 internalPath（cluster.go L19） */
    public static final String INTERNAL_PATH = "/api/v1/local-browser/internal";

    /** 对照 HumanStepWait（human.go L8-13）：BrowserSkill 默认的求助窗口 */
    public static final Duration HUMAN_STEP_WAIT = Duration.ofMinutes(5);
    /** 对照 HumanStepTimeout：给原生结果留出取消前的时间 */
    public static final Duration HUMAN_STEP_TIMEOUT = HUMAN_STEP_WAIT.plusSeconds(15);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 对照 methods 白名单（manager.go L638-670），逐值一致 */
    private static final Set<String> METHODS = Set.of(
            "snapshot", "observe", "navigate", "navigate_back", "navigate_forward", "reload",
            "click", "fill", "press", "hover", "wheel", "scroll_to", "select",
            "tab_list", "tab_create", "tab_select", "tab_close", "tab_borrow", "tab_return",
            "get_html", "evaluate", "screenshot", "focus", "blur", "console", "network",
            "wait_for_navigation", "wait_ms", "window_resize", "emulate", "request_help");

    /** 环境变量名（对照 NewManager/extensionPath 的 os.Getenv） */
    public static final String ENV_BINARY = "BROWSERSKILL_BINARY";
    public static final String ENV_PUBLIC_URL = "BROWSERSKILL_PUBLIC_URL";
    public static final String ENV_INTERNAL_URL = "BROWSERSKILL_INTERNAL_URL";
    public static final String ENV_CLUSTER_SECRET = "BROWSERSKILL_CLUSTER_SECRET";
    public static final String ENV_EXTENSION_PATH = "BROWSERSKILL_EXTENSION_PATH";
    public static final String ENV_MAX_CONNECTIONS = "BROWSERSKILL_MAX_CONNECTIONS";

    private final ObjectMapper mapper;
    private final BrowserSkillStore store;

    private final String binary;
    private final String publicURL;
    private final String clusterSecret;
    private final String internalURL;
    private final String extensionPath;
    private final String nodeID;
    private final int maxConnections;

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, Instant> seenRPC = new ConcurrentHashMap<>();
    private final Object daemonLock = new Object();
    private final AtomicReference<DaemonRuntime> daemon = new AtomicReference<>();
    private volatile boolean closed;

    /** 对照 localRPCKey：internal 签名请求在本地执行、禁止二次转发（cluster.go L21/L110） */
    private static final ThreadLocal<Boolean> LOCAL_RPC = new ThreadLocal<>();

    public BrowserSkillManager(ObjectMapper mapper, BrowserSkillStore store,
                               String binary, String publicURL, String clusterSecret,
                               String internalURL, String extensionPath) {
        this.mapper = mapper;
        this.store = store;
        this.binary = binary == null ? "" : binary;
        this.publicURL = publicURL == null ? "" : publicURL;
        this.clusterSecret = clusterSecret == null ? "" : clusterSecret;
        this.internalURL = internalURL == null ? "" : internalURL;
        this.extensionPath = extensionPath == null ? "" : extensionPath;
        this.nodeID = randomID();
        this.maxConnections = connectionLimit();
    }

    /** 从环境构造（对照 NewManager 的 os.Getenv 组；nodeID 随机 32 hex） */
    public static BrowserSkillManager fromEnv(ObjectMapper mapper, BrowserSkillStore store) {
        return new BrowserSkillManager(mapper, store,
                System.getenv(ENV_BINARY), System.getenv(ENV_PUBLIC_URL),
                System.getenv(ENV_CLUSTER_SECRET), System.getenv(ENV_INTERNAL_URL),
                System.getenv(ENV_EXTENSION_PATH));
    }

    static int connectionLimit() {
        String n = System.getenv(ENV_MAX_CONNECTIONS);
        if (n != null) {
            try {
                int v = Integer.parseInt(n);
                if (v > 0) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // 对照 Go：Atoi 失败 → 默认 32
            }
        }
        return 32;
    }

    // ── 随机与哈希助手（authorization.go L19-43） ────────────────────────────

    static String randomToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String randomID() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String tokenHash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 对照 validToken：RawURL 解码 32 字节且再编码相等（拒绝填充符） */
    static boolean validToken(String token) {
        if (token == null) {
            return false;
        }
        try {
            byte[] b = Base64.getUrlDecoder().decode(token);
            return b.length == 32
                    && Base64.getUrlEncoder().withoutPadding().encodeToString(b).equals(token);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ── Enabled / 配置校验（manager.go L134 / cluster.go L55-74） ────────────

    /** 对照 Enabled：集成是否已配置（binary 非空） */
    public boolean enabled() {
        return !binary.isEmpty();
    }

    /** 对照 ValidateConfiguration：存储、传输与副本路由要求（容器 must() 失败即拒启） */
    public void validateConfiguration() {
        if (!enabled()) {
            return;
        }
        if (store == null) {
            throw new IllegalStateException("BrowserSkill requires persistent authorization storage");
        }
        if (!publicURL.isEmpty()) {
            PairingEndpoint endpoint = pairingEndpoint(publicURL);
            if (endpoint == null) {
                throw new IllegalStateException(endpointError);
            }
        }
        if (!internalURL.isEmpty()
                && (!validInternalURL(internalURL) || clusterSecret.length() < 32)) {
            throw new IllegalStateException("BrowserSkill multi-replica mode requires a valid internal URL "
                    + "and a shared cluster secret of at least 32 characters");
        }
    }

    // ── extension origin / pairing URL（manager.go L179-216 / L537-552） ─────

    /** 对照 validExtensionOrigin：chrome-extension:// + 32 个 [a-p] */
    static boolean validExtensionOrigin(String origin) {
        String prefix = "chrome-extension://";
        if (origin == null || !origin.startsWith(prefix)) {
            return false;
        }
        String id = origin.substring(prefix.length());
        if (id.length() != 32) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 'a' || c > 'p') {
                return false;
            }
        }
        return true;
    }

    private String endpointError;

    /** pairingEndpoint 的 (端点, 错误) 二元返回（对照 Go 的 (string, error)） */
    record PairingEndpoint(String endpoint) {
        boolean invalid() {
            return endpoint == null;
        }
    }

    /**
     * 对照 pairingEndpoint：显式网关或页 origin 换来的 wss 端点。本地地址放行 ws，
     * 其余要求 wss；禁止凭据/查询/片段。URI.toString 保留原输入形态（对照 u.String()）。
     */
    PairingEndpoint pairingEndpoint(String raw) {
        URI u;
        try {
            u = URI.create(raw);
        } catch (RuntimeException e) {
            endpointError = "invalid BrowserSkill public URL";
            return new PairingEndpoint(null);
        }
        if (u.getScheme() == null || u.getHost() == null) {
            endpointError = "invalid BrowserSkill public URL";
            return new PairingEndpoint(null);
        }
        String hostname = stripBrackets(u.getHost());
        boolean local = hostname.equals("localhost") || hostname.equals("127.0.0.1") || hostname.equals("::1");
        String scheme = u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("wss") && (!local || !scheme.equals("ws"))) {
            endpointError = "BrowserSkill public URL requires WSS";
            return new PairingEndpoint(null);
        }
        if (u.getUserInfo() != null || u.getRawQuery() != null || u.getFragment() != null) {
            endpointError = "BrowserSkill public URL must not contain credentials, query or fragment";
            return new PairingEndpoint(null);
        }
        return new PairingEndpoint(u.toString());
    }

    private static String stripBrackets(String host) {
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /**
     * 对照 pairingURL：显式网关优先；否则用浏览器页 origin（origin 只用于生成
     * 返给调用方的链接，服务器绝不按它拨号——Go 注释原文）。经代理的外部端口
     * 由页 origin 保留。
     */
    String pairingURL(String origin) {
        if (!publicURL.isEmpty()) {
            PairingEndpoint endpoint = pairingEndpoint(publicURL);
            if (endpoint.invalid()) {
                throw new BrowserSkillException(endpointError);
            }
            return endpoint.endpoint();
        }
        URI u;
        try {
            u = URI.create(origin);
        } catch (RuntimeException e) {
            throw new BrowserSkillException("invalid BrowserSkill page origin");
        }
        if (u.getHost() == null || u.getUserInfo() != null
                || (u.getPath() != null && !u.getPath().isEmpty())
                || u.getRawQuery() != null || u.getFragment() != null) {
            throw new BrowserSkillException("invalid BrowserSkill page origin");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String target;
        switch (scheme) {
            case "https" -> target = "wss";
            case "http" -> target = "ws";
            default -> throw new BrowserSkillException("BrowserSkill page origin requires HTTP or HTTPS");
        }
        String rebuilt = target + "://" + u.getRawAuthority() + "/api/v1/local-browser/extension";
        PairingEndpoint endpoint = pairingEndpoint(rebuilt);
        if (endpoint.invalid()) {
            throw new BrowserSkillException(endpointError);
        }
        return endpoint.endpoint();
    }

    // ── 设备与任务（manager.go L61-95 / L136-143 / L284-326） ─────────────────

    /** 会话任务（对照 Go 的 task struct；conn 恒 null——WS 接缝） */
    static final class BrowserTask {
        String action = "";
        Instant actionStarted;
        Instant actionFinished;
        String pageUrl = "";
        String lastError = "";
        volatile boolean lifecycleCancelRequested;
        String helpPrompt = "";
        boolean commandQueued;
        int helpCalls;
        Instant previewAt;
        JsonNode previewData;
        boolean previewBusy;
        boolean idle;
        String id = "";
        boolean selected;
        boolean paused;
        boolean starting;
        boolean stopping;
        boolean forgotten;
        long epoch;
        final Map<Long, Runnable> calls = new HashMap<>();
        long nextCall;
    }

    /** 设备连接（对照 Go 的 device struct） */
    static final class Device {
        final Object lock = this;
        DaemonRuntime runtime;
        String browserID = "";
        String recordID = "";
        boolean ready;
        long generation;
        Instant expires;
        final Map<String, BrowserTask> tasks = new HashMap<>();

        /**
         * 对照 {@code d.conn != nil}：WS 中继连接未翻译（波 4 接缝），Java 侧
         * 恒无连接——所有依赖它的路径都落在 Go 的断连分类上。
         */
        boolean connPresent() {
            return false;
        }
    }

    private Device get(Scope s) {
        if (!s.valid()) {
            return null;
        }
        return devices.get(s.key());
    }

    /**
     * 对照 ensureDevice：与守护进程共享，但绝不共享授权或任务映射。清理过期/旧
     * runtime 的设备；新设备从持久任务标记（browser_task_interruptions）预置
     * selected+paused 任务；容量上限 maxConnections。
     */
    Device ensureDevice(Scope s) {
        DaemonRuntime runtime = ensureDaemon();
        if (closed || runtime.exited()) {
            throw new BrowserSkillException("BrowserSkill daemon unavailable");
        }
        synchronized (this) {
            Iterator<Map.Entry<String, Device>> it = devices.entrySet().iterator();
            while (it.hasNext()) {
                Device old = it.next().getValue();
                synchronized (old.lock) {
                    if (old.runtime != runtime
                            || (old.expires != null && Instant.now().isAfter(old.expires))) {
                        it.remove();
                        disconnectDeviceLocked(old);
                        old.expires = null;
                    }
                }
            }
            Device d = devices.get(s.key());
            if (d == null) {
                if (devices.size() >= maxConnections) {
                    throw new BrowserSkillException("local browser connection capacity reached");
                }
                List<TaskInterruption> rows = store.tasks(s);
                d = new Device();
                d.runtime = runtime;
                for (TaskInterruption row : rows) {
                    BrowserTask t = new BrowserTask();
                    t.selected = true;
                    t.paused = true;
                    d.tasks.put(row.getSession(), t);
                }
                devices.put(s.key(), d);
            }
            return d;
        }
    }

    /** 调用方须持有 d.lock；绝不停止共享守护进程（Go 注释原文） */
    private static void disconnectDeviceLocked(Device d) {
        // conn/upstream 恒 null（WS 接缝），无连接可关
        d.ready = false;
        d.browserID = "";
        d.generation++;
        for (BrowserTask t : d.tasks.values()) {
            t.id = "";
            t.previewData = null;
            pauseTask(t);
        }
    }

    static void pauseTask(BrowserTask t) {
        t.paused = true;
        t.epoch++;
        for (Runnable cancel : t.calls.values()) {
            cancel.run();
        }
    }

    void disconnectScope(Scope s) {
        Device d;
        synchronized (this) {
            d = devices.remove(s.key());
        }
        if (d != null) {
            synchronized (d.lock) {
                disconnectDeviceLocked(d);
                d.expires = null;
            }
        }
    }

    // ── daemon 生命周期（daemon.go） ─────────────────────────────────────────

    /** 对照 daemon struct：每应用实例一个本地进程 */
    static final class DaemonRuntime {
        final Path home;
        final int port;
        final Process process;

        DaemonRuntime(Path home, int port, Process process) {
            this.home = home;
            this.port = port;
            this.process = process;
        }

        boolean exited() {
            return !process.isAlive();
        }

        void stop() {
            process.destroyForcibly();
            try {
                rmrf(home);
            } catch (IOException e) {
                log.debug("[browserskill] failed to clean daemon home {}: {}", home, e.toString());
            }
        }
    }

    private static void rmrf(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力清理（对照 Go 的 os.RemoveAll 容错）
                }
            });
        }
    }

    /**
     * 对照 ensureDaemon：单守护进程；退出后重建。Java 用 daemonLock 串行化
     * （对照 Go 的 starting channel：后来者等待而非失败——净效果一致）。
     */
    DaemonRuntime ensureDaemon() {
        synchronized (daemonLock) {
            if (closed) {
                throw new BrowserSkillException("browser manager is closed");
            }
            DaemonRuntime current = daemon.get();
            if (current != null && !current.exited()) {
                return current;
            }
            DaemonRuntime previous = current;
            daemon.set(null);
            if (previous != null) {
                previous.stop();
            }
            DaemonRuntime d = startDaemon();
            if (!closed) {
                daemon.set(d);
            } else {
                d.stop();
                throw new BrowserSkillException("browser manager is closed");
            }
            return d;
        }
    }

    /**
     * 对照 startDaemon（daemon.go L129-192）：exec 二进制 daemon start --foreground
     * --port 0 --daemon-idle 24h --session-idle 30m，BSK_HOME=临时目录，轮询
     * daemon.json 的 ws_port（50ms 步进、10s 上限）。四种启动失败文案逐字对照。
     */
    private DaemonRuntime startDaemon() {
        Path home;
        try {
            home = Files.createTempDirectory("wkb-");
        } catch (IOException e) {
            throw new BrowserSkillException("could not start BrowserSkill daemon");
        }
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    binary, "daemon", "start", "--foreground", "--port", "0",
                    "--daemon-idle", "24h", "--session-idle", "30m");
            pb.environment().put("BSK_HOME", home.toString());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            p = pb.start();
        } catch (IOException e) {
            try {
                rmrf(home);
            } catch (IOException ignored) {
                // 同上
            }
            throw new BrowserSkillException("could not start BrowserSkill daemon");
        }
        DaemonRuntime d = new DaemonRuntime(home, 0, p);
        boolean started = false;
        try {
            Instant deadline = Instant.now().plusSeconds(10);
            while (true) {
                if (!p.isAlive()) {
                    throw new BrowserSkillException("BrowserSkill daemon exited during startup");
                }
                Path manifest = home.resolve("daemon.json");
                if (Files.exists(manifest)) {
                    try {
                        JsonNode info = mapper.readTree(Files.readAllBytes(manifest));
                        JsonNode port = info.get("ws_port");
                        if (port != null && port.isInt() && port.intValue() > 0) {
                            started = true;
                            return new DaemonRuntime(home, port.intValue(), p);
                        }
                    } catch (IOException ignored) {
                        // 对照 Go：读/解析失败 continue
                    }
                }
                if (Instant.now().isAfter(deadline)) {
                    throw new BrowserSkillException("BrowserSkill daemon startup timed out");
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BrowserSkillException("BrowserSkill daemon startup timed out");
                }
            }
        } finally {
            if (!started) {
                p.destroyForcibly();
                try {
                    rmrf(home);
                } catch (IOException ignored) {
                    // 同上
                }
            }
        }
    }

    /** 对照 Close：断开全部用户并停掉本 manager 持有的进程；清空自己的租约 */
    public void close() {
        synchronized (daemonLock) {
            if (closed) {
                return;
            }
            closed = true;
            for (Device d : devices.values()) {
                synchronized (d.lock) {
                    disconnectDeviceLocked(d);
                    d.expires = null;
                }
            }
            devices.clear();
            DaemonRuntime runtime = daemon.get();
            daemon.set(null);
            if (store != null) {
                try {
                    store.releaseOwner(nodeID);
                } catch (RuntimeException e) {
                    log.debug("[browserskill] releaseOwner failed: {}", e.toString());
                }
            }
            if (runtime != null) {
                runtime.stop();
            }
        }
    }

    // ── 状态（manager.go L146-177 / authorization.go L164-222） ──────────────

    /** 对照 Status：读本节点的瞬态任务状态，不创建任务 */
    BrowserStatus status(Scope s, String session) {
        BrowserStatus result = BrowserStatus.disabled(enabled());
        Device d = get(s);
        if (d == null) {
            return result;
        }
        synchronized (d.lock) {
            result.setConnected(d.connPresent() && d.ready && Instant.now().isBefore(d.expires));
            BrowserTask t = d.tasks.get(session);
            if (t != null) {
                result.setSelected(t.selected);
                result.setPaused(t.paused);
                result.setAction(t.action.isEmpty() ? null : t.action);
                result.setStopping(t.stopping);
                result.setPageUrl(t.pageUrl.isEmpty() ? null : t.pageUrl);
                result.setLastError(t.lastError.isEmpty() ? null : t.lastError);
                if (t.actionStarted != null) {
                    Instant end = t.actionFinished;
                    if (end == null) {
                        end = Instant.now();
                    }
                    result.setActionElapsedMs(Duration.between(t.actionStarted, end).toMillis());
                }
                result.setIdle(t.idle);
                result.setTaskId(t.id.isEmpty() ? null : t.id);
                result.setNeedsHelp(t.helpCalls > 0 && !t.paused);
                if (result.isNeedsHelp()) {
                    result.setHelpPrompt(t.helpPrompt.isEmpty() ? null : t.helpPrompt);
                }
            }
        }
        return result;
    }

    /**
     * 对照 GetStatus：经 route 归属（远端转发或本地瞬态 + 持久标记校正）。
     * 集群返回值反序列化成 Status；本地分支复查租约归属与任务打断标记。
     */
    public BrowserStatus getStatus(Scope s, String session) {
        RouteResult routed = route(s, session, "status", "", null);
        if (routed.remote()) {
            try {
                return mapper.treeToValue(routed.data(), BrowserStatus.class);
            } catch (IOException e) {
                throw new BrowserSkillException("invalid browser owner response");
            }
        }
        BrowserStatus st = status(s, session);
        if (st.isConnected() && store != null) {
            DeviceRecord record = store.account(s);
            if (record == null || record.getRevokedAt() != null
                    || !Instant.now().isBefore(record.getExpiresAt().toInstant())
                    || record.getLeaseUntil() == null
                    || !Instant.now().isBefore(record.getLeaseUntil().toInstant())
                    || !nodeID.equals(record.getOwner())) {
                st.setConnected(false);
                st.setTaskId(null);
                st.setPaused(st.isSelected());
            }
        }
        if (!st.isSelected() && store != null && !session.isEmpty()) {
            for (TaskInterruption row : store.tasks(s)) {
                if (row.getSession().equals(session)) {
                    st.setSelected(true);
                    st.setPaused(true);
                    break;
                }
            }
        }
        return st;
    }

    /**
     * 对照 Account（authorization.go L164-183）：独立于会话读取成员的设备授权。
     * 禁用形态恒返回 {enabled, extension_available}，无 device。
     */
    public AccountStatus account(Scope s) {
        AccountStatus result = AccountStatus.create(enabled(), extensionPathExists());
        if (store == null || !enabled()) {
            return result;
        }
        DeviceRecord r = store.account(s);
        if (r != null && r.getRevokedAt() == null
                && Instant.now().isBefore(r.getExpiresAt().toInstant())) {
            result.setDevice(r);
            BrowserStatus st = getStatus(s, "");
            result.applyStatus(st);
        }
        return result;
    }

    /** 对照 extensionPath()（authorization.go L224-234）：env 路径必须是普通文件 */
    private String resolveExtensionPath() {
        if (extensionPath.isEmpty()) {
            return null;
        }
        Path p = Path.of(extensionPath);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        return extensionPath;
    }

    private boolean extensionPathExists() {
        return resolveExtensionPath() != null;
    }

    // ── Pair / Revoke（manager.go L218-243 / L303-313） ──────────────────────

    /** 对照 Pair：五分钟、一次性激活链接；扩展成功兑换才替换既有设备授权 */
    public String pair(Scope s, String origin) {
        if (!enabled() || !s.valid() || store == null) {
            throw new BrowserSkillException("local browser is unavailable");
        }
        String endpoint = pairingURL(origin);
        String token = randomToken();
        PairingRecord p = new PairingRecord();
        p.setScopeKey(s.key());
        p.setTokenHash(tokenHash(token));
        p.setTenant(s.tenant());
        p.setUser(s.user());
        p.setExpiresAt(OffsetDateTime.now().plus(PAIRING_LIFETIME));
        store.createPair(p);
        return endpoint + "#" + token;
    }

    /** 对照 Revoke：作废成员的持久授权并关闭其本地连接 */
    public void revoke(Scope s) {
        if (store == null) {
            throw new BrowserSkillException("local browser is unavailable");
        }
        store.revoke(s);
        disconnectScope(s);
    }

    // ── HTTP 面：authorize（authorization.go L45-124） ───────────────────────

    /** authorize 请求体（对照 AuthorizeHTTP 的匿名 struct；未知键忽略=Go 语义） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthorizeInput {
        private String action;
        @com.fasterxml.jackson.annotation.JsonProperty("next_token")
        private String nextToken;
        private String label;

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getNextToken() { return nextToken; }
        public void setNextToken(String nextToken) { this.nextToken = nextToken; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }

    /**
     * 对照 AuthorizeHTTP：extension 专用、独立于浏览器 cookie。设备令牌留在
     * chrome.storage.local，DB 只提交哈希。响应是 http.Error 纯文本或
     * json.Encoder 的字母序 map（device_id < expires_at < renew_after < service_name，
     * 尾随换行——Encoder 语义）。
     */
    public void authorizeHTTP(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        if (!enabled() || store == null) {
            BrowserSkillHttp.plainError(response, 503, "browser unavailable");
            return;
        }
        if (!validExtensionOrigin(request.getHeader("Origin"))) {
            BrowserSkillHttp.plainError(response, 403, "extension origin required");
            return;
        }
        if (!"POST".equals(request.getMethod())) {
            BrowserSkillHttp.plainError(response, 405, "method not allowed");
            return;
        }
        String authHeader = request.getHeader("Authorization");
        String token = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring("Bearer ".length()) : "";
        AuthorizeInput input = BrowserSkillHttp.readJsonLimited(request, 4096, mapper, AuthorizeInput.class);
        String nextToken = input == null ? null : input.getNextToken();
        if (!validToken(token) || input == null || !validToken(nextToken)
                || token.equals(nextToken)) {
            BrowserSkillHttp.plainError(response, 400, "invalid authorization request");
            return;
        }
        DeviceRecord record;
        try {
            switch (input.getAction() == null ? "" : input.getAction()) {
                case "pair" -> {
                    String label = input.getLabel() == null ? "" : input.getLabel().trim();
                    if (label.isEmpty()) {
                        label = "Chrome";
                    }
                    if (label.codePointCount(0, label.length()) > 100) {
                        BrowserSkillHttp.plainError(response, 400, "device label too long");
                        return;
                    }
                    OffsetDateTime now = OffsetDateTime.now();
                    DeviceRecord r = new DeviceRecord();
                    r.setId(randomID());
                    r.setLabel(label);
                    r.setTokenHash(tokenHash(nextToken));
                    r.setExpiresAt(now.plus(DEVICE_LIFETIME));
                    r.setRenewAfter(now.plus(RENEWAL_INTERVAL));
                    r.setCreatedAt(now);
                    r.setLastSeenAt(now);
                    record = store.exchange(tokenHash(token), r);
                    disconnectScope(record.scope());
                }
                case "renew" -> record = store.renew(tokenHash(token), tokenHash(nextToken));
                default -> {
                    BrowserSkillHttp.plainError(response, 400, "invalid action");
                    return;
                }
            }
        } catch (BrowserAuthorizationException e) {
            BrowserSkillHttp.plainError(response, 401, "authorization expired or already used");
            return;
        } catch (RuntimeException e) {
            BrowserSkillHttp.plainError(response, 503, "authorization storage unavailable");
            return;
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", record.getId());
        payload.put("expires_at", record.getExpiresAt());
        payload.put("renew_after", record.getRenewAfter());
        payload.put("service_name", "WeKnora");
        response.setHeader("Content-Type", "application/json");
        BrowserSkillHttp.writeJson(response, 200, mapper, payload);
    }

    // ── HTTP 面：extension 下载（authorization.go L236-246） ─────────────────

    /**
     * 对照 DownloadExtension：把配置的扩展包发给已认证用户。包未配置 → 404
     * 纯文本（dev 默认形态）。Go 的 http.ServeFile 头语义：
     * Content-Type/Disposition 先设，Last-Modified（秒截断）/Accept-Ranges/
     * Content-Length 由 ServeFile 补。
     */
    public void downloadExtension(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String p = resolveExtensionPath();
        if (!enabled() || p == null) {
            BrowserSkillHttp.plainError(response, 404, "extension package is not configured");
            return;
        }
        response.setHeader("Content-Type", "application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"browser-skill-weknora.zip\"");
        Path file = Path.of(p);
        OffsetDateTime modified = OffsetDateTime.ofInstant(
                Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.SECONDS),
                OffsetDateTime.now().getOffset());
        // 对照 http.ServeFile 的 If-Modified-Since 条件请求（秒粒度）
        String since = request.getHeader("If-Modified-Since");
        if (since != null) {
            OffsetDateTime sinceTime = BrowserSkillHttp.parseHttpTime(since);
            if (sinceTime != null && !modified.toInstant().isAfter(sinceTime.toInstant())) {
                response.setStatus(304);
                return;
            }
        }
        byte[] body = Files.readAllBytes(file);
        response.setHeader("Last-Modified", BrowserSkillHttp.formatHttpTime(modified));
        response.setHeader("Accept-Ranges", "bytes");
        BrowserSkillHttp.writeBytes(response, 200, body);
    }

    // ── HTTP 面：extension WS 握手（manager.go ServeHTTP L328-487 的鉴权前段） ──

    /** 对照 gorilla 的 Subprotocols：逗号分隔、trim、跳过空段 */
    static List<String> subprotocols(HttpServletRequest request) {
        List<String> protocols = new ArrayList<>();
        java.util.Enumeration<String> headers = request.getHeaders("Sec-WebSocket-Protocol");
        while (headers != null && headers.hasMoreElements()) {
            String header = headers.nextElement();
            {
                for (String proto : header.split(",")) {
                    String trimmed = proto.trim();
                    if (!trimmed.isEmpty()) {
                        protocols.add(trimmed);
                    }
                }
            }
        }
        return protocols;
    }

    /**
     * 对照 ServeHTTP 的 extension 分支：每个扩展先认证再拨共享守护进程——
     * 只有握手身份被改写，浏览器执行留在上游（Go 注释原文）。鉴权失败族是
     * 本批 golden 的主体；升级成功后的中继循环是波 4 接缝（dev 的 daemon
     * 必然起不来，dial 失败分类 503 "daemon unavailable" 已按 Go 对齐）。
     */
    public void serveExtension(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!enabled()) {
            BrowserSkillHttp.plainError(response, 503, "unavailable");
            return;
        }
        String origin = request.getHeader("Origin");
        if (!validExtensionOrigin(origin)) {
            BrowserSkillHttp.plainError(response, 403, "invalid extension origin");
            return;
        }
        List<String> protocols = subprotocols(request);
        if (protocols.size() != 1 || !protocols.get(0).startsWith(AUTH_PROTOCOL)) {
            BrowserSkillHttp.plainError(response, 401, "authentication required");
            return;
        }
        String token = protocols.get(0).substring(AUTH_PROTOCOL.length());
        if (token.length() != 43) {
            BrowserSkillHttp.plainError(response, 401, "invalid pairing");
            return;
        }
        if (store == null) {
            BrowserSkillHttp.plainError(response, 503, "authorization unavailable");
            return;
        }
        DeviceRecord record;
        try {
            record = store.authenticate(tokenHash(token));
        } catch (RuntimeException e) {
            BrowserSkillHttp.plainError(response, 401, "device authorization invalid; pair again");
            return;
        }
        Device d;
        try {
            d = ensureDevice(record.scope());
        } catch (RuntimeException e) {
            BrowserSkillHttp.plainError(response, 503, "browser runtime unavailable");
            return;
        }
        String leaseKey = randomID();
        try {
            try {
                store.claim(record, nodeID, internalURL, leaseKey);
            } catch (BrowserLeaseHeldException e) {
                BrowserSkillHttp.plainError(response, 409, "browser connection owned elsewhere; retry shortly");
                return;
            }
            synchronized (d.lock) {
                if (d.connPresent()) {
                    BrowserSkillHttp.plainError(response, 409, "browser already connected");
                    return;
                }
                d.recordID = record.getId();
                d.expires = record.getExpiresAt().toInstant();
                // 对照 websocket.DefaultDialer.DialContext(ws://127.0.0.1:port) 的失败分类
                if (!dialDaemon(d)) {
                    BrowserSkillHttp.plainError(response, 503, "daemon unavailable");
                    return;
                }
                // 波 4 接缝：websocket 升级 + 双向转发（relayHandshake/pump）未翻译。
                // dialDaemon 成功意味着真守护进程在跑，dev 部署不可达。
                throw new IllegalStateException("browser websocket relay is not translated (wave 4 seam)");
            }
        } finally {
            try {
                store.release(record.getId(), leaseKey);
            } catch (RuntimeException e) {
                log.debug("[browserskill] release failed: {}", e.toString());
            }
        }
    }

    /** 对照 ws://127.0.0.1:<port> 拨号的失败分类（连不上 → false → 503 daemon unavailable） */
    private boolean dialDaemon(Device d) {
        if (d.runtime == null || d.runtime.port <= 0) {
            return false;
        }
        try (Socket socket = new Socket("127.0.0.1", d.runtime.port)) {
            return socket.isConnected();
        } catch (IOException e) {
            return false;
        }
    }

    // ── HTTP 面：internal 签名 RPC（cluster.go L161-253） ────────────────────

    /** 对照 signRPC：HMAC-SHA256(secret, timestamp + "\n" + body)；
     *  调用方传的 timestamp 参数是 ts+"\n"+nonce → 消息实为 ts\nnonce\nbody */
    static String signRPC(String secret, String timestamp, byte[] body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + "\n").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 对照 validInternalURL：http(s)、有 host、无 user/query/fragment、path 为空或 / */
    static boolean validInternalURL(String raw) {
        URI u;
        try {
            u = URI.create(raw);
        } catch (RuntimeException e) {
            return false;
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        return (scheme.equals("http") || scheme.equals("https")) && u.getHost() != null
                && u.getUserInfo() == null && u.getRawQuery() == null && u.getFragment() == null
                && (u.getPath() == null || u.getPath().isEmpty() || u.getPath().equals("/"));
    }

    /** 对照 clusterTimeout：call 的 human 步（request_help/tab_borrow）放宽到 315s */
    static Duration clusterTimeout(String operation, String method) {
        if ("call".equals(operation) && isHumanStep(method)) {
            return HUMAN_STEP_TIMEOUT;
        }
        return Duration.ofMinutes(2);
    }

    /** 对照 IsHumanStep：可以等用户输入的浏览器操作 */
    public static boolean isHumanStep(String method) {
        return "request_help".equals(method) || "tab_borrow".equals(method);
    }

    /** 对照 humanTimeoutMS：越界/非数回落到默认求助窗口 */
    static long humanTimeoutMS(Object value) {
        double ms = -1;
        if (value instanceof Number n) {
            ms = n.doubleValue();
        }
        if (ms >= 1 && ms <= HUMAN_STEP_WAIT.toMillis()) {
            return (long) ms;
        }
        return HUMAN_STEP_WAIT.toMillis();
    }

    /** 对照 pausedError（human.go L37-44）：固定的用户介入恢复指引 */
    static RpcError pausedError() {
        return new RpcError("task_paused", "Browser is connected, but this task is paused for user "
                + "intervention. Ask the user to complete any manual step, click Continue operation in the "
                + "conversation browser preview, then continue in this same conversation. Do not reconnect "
                + "or pair again. Observe before acting.");
    }

    /**
     * 对照 InternalHTTP：接受已认证、防重放的请求，发给本节点的连接。
     * 校验序逐字对照：方法/secret 长度 → 时间窗 ±60s → 1MB 体 → HMAC 常量比较 →
     * nonce 32 → 重放表（8192 容量、2 分钟过期）→ node/scope 归属 → 分派。
     */
    public void internalHTTP(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        if (!"POST".equals(request.getMethod()) || clusterSecret.length() < 32) {
            BrowserSkillHttp.plainError(response, 404, "unavailable");
            return;
        }
        String timestamp = request.getHeader("X-Browser-Timestamp");
        String nonce = request.getHeader("X-Browser-Nonce");
        long unix;
        try {
            unix = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            BrowserSkillHttp.plainError(response, 401, "expired request");
            return;
        }
        Instant then = Instant.ofEpochSecond(unix);
        Instant now = Instant.now();
        if (Duration.between(then, now).getSeconds() > 60 || Duration.between(now, then).getSeconds() > 60) {
            BrowserSkillHttp.plainError(response, 401, "expired request");
            return;
        }
        byte[] body = BrowserSkillHttp.readAllLimited(request, 1 << 20);
        if (body == null) {
            BrowserSkillHttp.plainError(response, 400, "invalid request");
            return;
        }
        byte[] sig;
        try {
            String header = request.getHeader("X-Browser-Signature");
            sig = HexFormat.of().parseHex(header == null ? "" : header);
        } catch (RuntimeException e) {
            BrowserSkillHttp.plainError(response, 401, "invalid signature");
            return;
        }
        byte[] want = HexFormat.of().parseHex(signRPC(clusterSecret, timestamp + "\n" + nonce, body));
        if (!MessageDigest.isEqual(sig, want)) {
            BrowserSkillHttp.plainError(response, 401, "invalid signature");
            return;
        }
        if (nonce == null || nonce.length() != 32) {
            BrowserSkillHttp.plainError(response, 401, "invalid nonce");
            return;
        }
        // 重放表：过期项惰性清理；容量 8192 满 → 一律拒绝（对照 Go seenRPC 循环）
        boolean replayed;
        synchronized (seenRPC) {
            Iterator<Map.Entry<String, Instant>> it = seenRPC.entrySet().iterator();
            while (it.hasNext()) {
                if (Instant.now().isAfter(it.next().getValue())) {
                    it.remove();
                }
            }
            replayed = seenRPC.containsKey(nonce);
            boolean full = seenRPC.size() >= 8192;
            if (!replayed && !full) {
                seenRPC.put(nonce, Instant.now().plus(Duration.ofMinutes(2)));
            }
        }
        if (replayed) {
            BrowserSkillHttp.plainError(response, 409, "replayed or throttled request");
            return;
        }
        ClusterRequest input;
        try {
            input = mapper.readValue(body, ClusterRequest.class);
        } catch (IOException e) {
            input = null;
        }
        if (input == null || input.getScope() == null
                || !nodeID.equals(input.getNode()) || !input.getScope().toScope().valid()) {
            BrowserSkillHttp.plainError(response, 409, "invalid owner request");
            return;
        }
        // 签名的内部 RPC 本地执行，绝不二次转发（Go 注释原文）
        LOCAL_RPC.set(Boolean.TRUE);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        try {
            Scope scope = input.getScope().toScope();
            switch (input.getOperation() == null ? "" : input.getOperation()) {
                case "status" -> {
                    BrowserStatus st = getStatus(scope, input.getSession());
                    result.put("data", mapper.valueToTree(st));
                }
                case "call" -> {
                    JsonNode data = call(scope, input.getSession(), input.getMethod(), input.getParams());
                    if (data != null) {
                        result.put("data", data);
                    }
                }
                case "control" -> {
                    control(scope, input.getSession(), input.getMethod());
                    result.put("data", mapper.createObjectNode());
                }
                case "preview" -> {
                    JsonNode data = preview(scope, input.getSession());
                    if (data != null) {
                        result.put("data", data);
                    }
                }
                case "idle" -> idle(scope, input.getSession());
                case "focus" -> focus(scope, input.getSession());
                case "forget" -> forgetLocal(scope, List.of(input.getSession()));
                default -> throw new BrowserSkillException("unsupported browser operation");
            }
        } catch (RuntimeException err) {
            if (err instanceof RpcError rpcError) {
                rpcError.boundDetails(mapper);
                result.put("rpc_error", rpcError.toNode(mapper));
            } else {
                result.put("error", err.getMessage() == null ? err.toString() : err.getMessage());
            }
        } finally {
            LOCAL_RPC.remove();
        }
        response.setHeader("Content-Type", "application/json");
        BrowserSkillHttp.writeJson(response, 200, mapper, result);
    }

    // ── 集群路由（cluster.go route L76-159） ─────────────────────────────────

    record RouteResult(JsonNode data, boolean remote) {
        static final RouteResult LOCAL = new RouteResult(null, false);
    }

    /**
     * 对照 route：远端归属判定与转发。member 失败 / 授权失效 / 断连 / owner 变更 /
     * 集群配置缺失的错误文案逐字对照；转发体 scope 键是 Go 无 tag struct 的大写
     * {@code Tenant/User}。
     */
    RouteResult route(Scope s, String session, String operation, String method, Map<String, Object> params) {
        if (store == null) {
            return RouteResult.LOCAL;
        }
        store.member(s);
        DeviceRecord r = store.account(s);
        Instant now = Instant.now();
        if (r == null || r.getRevokedAt() != null || !now.isBefore(r.getExpiresAt().toInstant())) {
            if ("call".equals(operation)
                    || "preview".equals(operation) || "focus".equals(operation)
                    || ("control".equals(operation) && !"stop".equals(method) && !"select".equals(method))) {
                throw new BrowserAuthorizationException();
            }
            return RouteResult.LOCAL;
        }
        if (r.getOwner() == null || r.getOwner().isEmpty() || r.getLeaseUntil() == null
                || !now.isBefore(r.getLeaseUntil().toInstant())) {
            if ("call".equals(operation) || "preview".equals(operation) || "focus".equals(operation)
                    || ("control".equals(operation)
                        && ("start".equals(method) || "resume".equals(method)))) {
                throw new BrowserSkillException(
                        "browser disconnected; wait for reconnection and resume the task");
            }
            return RouteResult.LOCAL;
        }
        if (nodeID.equals(r.getOwner())) {
            return RouteResult.LOCAL;
        }
        if (Boolean.TRUE.equals(LOCAL_RPC.get())) {
            throw new BrowserSkillException("browser owner changed; refresh status");
        }
        if (clusterSecret.length() < 32 || r.getOwnerUrl() == null || !validInternalURL(r.getOwnerUrl())) {
            throw new BrowserSkillException(
                    "browser is connected to another node; configure BrowserSkill cluster routing");
        }
        return forward(r, s, session, operation, method, params);
    }

    /** 对照 route 的转发尾段：POST ownerURL/internalPath + HMAC 头，2min/315s 超时 */
    private RouteResult forward(DeviceRecord r, Scope s, String session, String operation,
                                String method, Map<String, Object> params) {
        LinkedHashMap<String, Object> req = new LinkedHashMap<>();
        req.put("node", r.getOwner());
        LinkedHashMap<String, Object> scopeJson = new LinkedHashMap<>();
        scopeJson.put("Tenant", s.tenant());
        scopeJson.put("User", s.user());
        req.put("scope", scopeJson);
        req.put("session", session);
        req.put("operation", operation);
        req.put("method", method);
        if (params != null) {
            req.put("params", params);
        }
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(req);
        } catch (IOException e) {
            throw new BrowserSkillException(e.toString());
        }
        long timestamp = Instant.now().getEpochSecond();
        String nonce = randomID();
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
            String url = r.getOwnerUrl().replaceAll("/+$", "") + INTERNAL_PATH;
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(clusterTimeout(operation, method))
                    .header("Content-Type", "application/json")
                    .header("X-Browser-Timestamp", Long.toString(timestamp))
                    .header("X-Browser-Nonce", nonce)
                    .header("X-Browser-Signature",
                            signRPC(clusterSecret, timestamp + "\n" + nonce, body))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            java.net.http.HttpResponse<byte[]> response;
            try {
                response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new BrowserSkillException(
                        "browser owner unavailable; do not replay interrupted operations");
            }
            if (response.statusCode() != 200) {
                throw new BrowserSkillException("browser owner rejected the request; refresh connection status");
            }
            JsonNode result = mapper.readTree(response.body());
            if (result == null || result.isMissingNode()) {
                throw new BrowserSkillException("invalid browser owner response");
            }
            JsonNode rpcError = result.get("rpc_error");
            if (rpcError != null && !rpcError.isNull()) {
                RpcError err = rpcErrorFromNode(rpcError);
                err.boundDetails(mapper);
                throw err;
            }
            JsonNode error = result.get("error");
            if (error != null && error.isTextual() && !error.textValue().isEmpty()) {
                throw new BrowserSkillException(error.textValue());
            }
            return new RouteResult(result.get("data"), true);
        } catch (IOException e) {
            throw new BrowserSkillException("invalid browser owner response");
        }
    }

    /** rpc_error 节点 → RpcError（对照 json.Unmarshal 进 *RPCError） */
    private RpcError rpcErrorFromNode(JsonNode node) {
        String code = node.hasNonNull("code") ? node.get("code").asText() : "";
        String message = node.hasNonNull("message") ? node.get("message").asText() : "";
        JsonNode data = node.has("data") && !node.get("data").isNull() ? node.get("data") : null;
        return new RpcError(code, message, data);
    }

    // ── 执行面：Call / Control / Preview / Focus / Idle / Forget（波 4 接缝） ──

    /**
     * 对照 Call（manager.go L672-844）：会话任务内串行化已授权的自动化命令。
     * 本批只翻到「连接失败分类」——命令白名单、未配对/未选择/离线/暂停的固定
     * 文案与 RPC 拨号失败分类；拨号成功后的命令循环是波 4 agent tools 接缝。
     */
    public JsonNode call(Scope s, String session, String method, Map<String, Object> params) {
        route(s, session, "call", method, params);
        if (!METHODS.contains(method)) {
            throw new BrowserSkillException("unsupported BrowserSkill tool");
        }
        Device d = get(s);
        if (d == null) {
            throw new BrowserSkillException("local browser is not paired");
        }
        synchronized (d.lock) {
            BrowserTask target = d.tasks.get(session);
            if (target == null) {
                throw new BrowserSkillException("select a browser task first");
            }
            if (!d.connPresent() || !d.ready || d.expires == null
                    || !Instant.now().isBefore(d.expires)) {
                throw new BrowserSkillException(
                        "local browser is offline; keep Chrome and the extension open for reconnection");
            }
            if (target.paused || target.stopping) {
                throw pausedError();
            }
            // 波 4 接缝：命令执行循环（auto_start、gate 队列、rpc 调用、结果回填）
            throw new IllegalStateException("browser command loop is not translated (wave 4 seam)");
        }
    }

    /**
     * 对照 Control（manager.go L872-1032）：select/start/pause/resume/stop/
     * finish/auto_start。本批逐字翻译前置分类（未配对/无效控制/容量/生命周期
     * 冲突/断连），session.start RPC 段是波 4 接缝（dev 下断连检查先命中）。
     */
    public void control(Scope s, String session, String action) {
        route(s, session, "control", action, null);
        Device d = get(s);
        if (d == null && "select".equals(action) && enabled() && s.valid()) {
            d = ensureDevice(s);
        }
        if (d == null && "finish".equals(action)) {
            return;
        }
        if (d == null && "stop".equals(action) && store != null) {
            store.clearTask(s, session);
            return;
        }
        if (d == null) {
            throw new BrowserSkillException("pair a browser first");
        }
        if ("stop".equals(action)) {
            stopTask(s, session, d, false);
            return;
        }
        synchronized (d.lock) {
            BrowserTask t = d.tasks.get(session);
            if ("finish".equals(action)) {
                // 自动清理不得 resume 或丢弃被打断的工作（Go 注释原文）
                if (t == null || t.paused || t.starting || t.stopping || !t.calls.isEmpty() || !t.idle) {
                    return;
                }
            }
            if ("auto_start".equals(action) && (t == null || !t.selected || t.paused || t.forgotten)) {
                throw new BrowserSkillException(
                        "local browser is not selected or is paused; ask the user to resume");
            }
            if (t == null) {
                if (d.tasks.size() >= 64) {
                    throw new BrowserSkillException("end an existing browser task before starting another");
                }
                t = new BrowserTask();
                d.tasks.put(session, t);
            }
            if ("pause".equals(action)) {
                pauseTask(t);
                return;
            }
            if (!"start".equals(action) && !"resume".equals(action) && !"stop".equals(action)
                    && !"select".equals(action) && !"auto_start".equals(action)) {
                throw new BrowserSkillException("invalid browser control");
            }
            if (t.forgotten || t.stopping) {
                throw new BrowserSkillException("browser conversation was deleted or its task is ending");
            }
            if ("select".equals(action)) {
                // 重新选择 tab 从不清除打断、也从不 resume 任务（Go 注释原文）
                t.selected = true;
                return;
            }
            if ("auto_start".equals(action) && !t.id.isEmpty()) {
                return;
            }
            if (t.starting) {
                throw new BrowserSkillException("browser task lifecycle is busy");
            }
            if (!d.connPresent() || !d.ready || d.expires == null
                    || !Instant.now().isBefore(d.expires)) {
                throw new BrowserSkillException("browser is disconnected");
            }
            // 波 4 接缝：markTask + session.start/tool.snapshot RPC + 派生状态机
            throw new IllegalStateException("task start RPC is not translated (wave 4 seam)");
        }
    }

    /**
     * 对照 stopTask（stop.go）：先挡住新命令再取消活动工作。任务不存在 → 只清
     * 持久标记；无连接而任务未创建 → 纯标记清理（可达）；有真实 session id 而断连
     * → 固定文案（可达）；session.stop RPC 本体随波 4。
     */
    void stopTask(Scope s, String session, Device d, boolean automatic) {
        synchronized (d.lock) {
            BrowserTask t = d.tasks.get(session);
            if (t == null) {
                store.clearTask(s, session);
                return;
            }
            if (automatic && (t.paused || t.starting || t.stopping || !t.calls.isEmpty() || !t.idle)) {
                return;
            }
            if (t.stopping) {
                throw new BrowserSkillException("browser task is already ending");
            }
            t.stopping = true;
            pauseTask(t);
            t.lifecycleCancelRequested = true;
            try {
                Instant deadline = Instant.now().plusSeconds(35);
                while (true) {
                    boolean same = d.tasks.get(session) == t;
                    boolean busy = t.starting || !t.calls.isEmpty();
                    String id = t.id;
                    long generation = d.generation;
                    boolean connected = d.connPresent() && d.ready
                            && d.expires != null && Instant.now().isBefore(d.expires);
                    if (!same) {
                        throw new BrowserSkillException("browser task changed while ending");
                    }
                    if (busy) {
                        if (Instant.now().isAfter(deadline)) {
                            throw new BrowserSkillException("browser task ending timed out");
                        }
                        // 对照 Go 的 20ms tick 等待
                        try {
                            TimeUnit.MILLISECONDS.sleep(20);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new BrowserSkillException("browser task ending timed out");
                        }
                        continue;
                    }
                    if (!id.isEmpty()) {
                        if (!connected) {
                            throw new BrowserSkillException(
                                    "browser is disconnected; retry ending after reconnection");
                        }
                        // 波 4 接缝：session.stop RPC
                        throw new IllegalStateException("session stop RPC is not translated (wave 4 seam)");
                    }
                    if (d.tasks.get(session) != t || d.generation != generation) {
                        throw new BrowserSkillException(
                                "browser connection changed while ending; retry ending the task");
                    }
                    store.clearTask(s, session);
                    d.tasks.remove(session);
                    return;
                }
            } finally {
                t.stopping = false;
            }
        }
    }

    /**
     * 对照 Preview（manager.go L1034-1085）：只读、用户暂停时仍可用。
     * 未配对/无任务/离线的固定文案可达；捕获循环随波 4。
     */
    public JsonNode preview(Scope s, String session) {
        RouteResult routed = route(s, session, "preview", "", null);
        if (routed.remote()) {
            return routed.data();
        }
        Device d = get(s);
        if (d == null) {
            throw new BrowserSkillException("browser unavailable");
        }
        synchronized (d.lock) {
            BrowserTask t = d.tasks.get(session);
            if (t == null || t.id.isEmpty() || !d.connPresent() || !d.ready
                    || d.expires == null || !Instant.now().isBefore(d.expires)) {
                throw new BrowserSkillException("browser unavailable");
            }
            // 波 4 接缝：preview 缓存与 gateway.task_preview UI 通道
            throw new IllegalStateException("browser preview capture is not translated (wave 4 seam)");
        }
    }

    /** 对照 Focus（focus.go L16-31）：用户发起；只把服务端持有的任务 ID 发给 Chrome */
    public void focus(Scope s, String session) {
        route(s, session, "focus", "", null);
        JsonNode data = callUI(s, session, "gateway.task_focus");
        JsonNode focused = data.get("focused");
        if (focused == null || !focused.isBoolean() || !focused.booleanValue()) {
            throw new BrowserSkillException("browser task tab unavailable");
        }
    }

    /** 对照 callUI（focus.go L35-76）：绕过守护进程自动化队列的 UI 通道 */
    JsonNode callUI(Scope s, String session, String method) {
        Device d = get(s);
        if (d == null) {
            throw new BrowserSkillException("browser disconnected");
        }
        synchronized (d.lock) {
            BrowserTask t = d.tasks.get(session);
            if (t == null || t.id.isEmpty() || !d.ready || !d.connPresent()) {
                throw new BrowserSkillException("browser task unavailable");
            }
            // 波 4 接缝：UI 请求/回复配对（receiveUI）
            throw new IllegalStateException("browser UI channel is not translated (wave 4 seam)");
        }
    }

    /**
     * 对照 Idle（focus.go L106-150）：释放调试而不关 tab、不取消选择。
     * 无设备/无就绪任务 → 直接返回（对照 Go 的两处 return nil）；更深路径随波 4。
     */
    public void idle(Scope s, String session) {
        RouteResult routed = route(s, session, "idle", "", null);
        if (routed.remote()) {
            return;
        }
        Device d = get(s);
        if (d == null) {
            return;
        }
        synchronized (d.lock) {
            BrowserTask target = d.tasks.get(session);
            if (target == null || target.id.isEmpty() || !d.ready || !d.connPresent()) {
                return;
            }
            // 波 4 接缝：gate 队列 + gateway.task_idle
            throw new IllegalStateException("browser idle release is not translated (wave 4 seam)");
        }
    }

    /** 对照 FinishTurn（focus.go L155-163）：先释放调试；完成任务后关闭保留的任务 */
    public void finishTurn(Scope s, String session, boolean keepOpen) {
        idle(s, session);
        if (keepOpen) {
            return;
        }
        control(s, session, "finish");
    }

    /** 对照 ForgetAll（manager.go L1089-1104）：成员全部会话删除后的兜底清理 */
    public void forgetAll(Scope s) {
        if (store == null) {
            return;
        }
        List<TaskInterruption> rows;
        try {
            rows = store.tasks(s);
        } catch (RuntimeException e) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (TaskInterruption row : rows) {
            ids.add(row.getSession());
        }
        forget(s, ids);
    }

    /** 对照 Forget：指定会话删除后停止并移除其浏览器任务；删除可到达任一副本 */
    public void forget(Scope s, List<String> sessions) {
        if (store == null) {
            return;
        }
        for (String session : sessions) {
            boolean forwarded;
            try {
                forwarded = route(s, session, "forget", "", null).remote();
            } catch (RuntimeException e) {
                continue;
            }
            if (forwarded) {
                continue;
            }
            forgetLocal(s, List.of(session));
        }
    }

    /** 对照 forgetLocal（manager.go L1125-1189）：本地 tombstone + RPC 停止的清理 */
    void forgetLocal(Scope s, List<String> sessions) {
        Device d = get(s);
        if (d == null) {
            for (String session : sessions) {
                store.clearTask(s, session);
            }
            return;
        }
        synchronized (d.lock) {
            for (String session : sessions) {
                BrowserTask t = d.tasks.get(session);
                if (t != null) {
                    pauseTask(t);
                    t.selected = false;
                    t.forgotten = true;
                } else {
                    store.clearTask(s, session);
                }
            }
        }
        // 波 4 接缝：等待 in-flight start 后的 session.stop RPC（dev 不可达）——
        // 到此为止时 tombstone 保留（对照 Go 注释：原生会话的空闲清理仍生效）。
    }

    // ── rpc 拨号分类（manager.go L593-636 的拨号前段） ────────────────────────

    /**
     * 对照 rpc()：连守护进程 unix socket（BSK_HOME/run/daemon.sock）。拨号失败 →
     * "BrowserSkill daemon unavailable"（dev 的确定性分类）；拨号成功即波 4 接缝
     * （命令循环、取消传播、BoundDetails 装配未翻译）。
     */
    JsonNode rpc(Device d, String method, Object params) {
        UnixDomainSocketAddress addr =
                UnixDomainSocketAddress.of(d.runtime.home.resolve("run").resolve("daemon.sock"));
        try (SocketChannel channel = SocketChannel.open(addr)) {
            // 波 4 接缝：行式 JSON-RPC 命令循环
            throw new IllegalStateException("browser command loop is not translated (wave 4 seam)");
        } catch (IOException e) {
            throw new BrowserSkillException("BrowserSkill daemon unavailable");
        }
    }
}
