package com.ragagent.sandbox.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * provider 控制面的<b>薄</b> HTTP 客户端切片（对照 Go
 * {@code CubeRemoteClient}/{@code E2BRemoteClient}/{@code DockerRemoteClient} 中
 * sandbox-check 与配置盘点实际走到的方法：Health / ListTemplates / List / Delete）。
 *
 * <h2>本批边界（显式接缝，随波 4 补齐）</h2>
 * <p>对照 Go 的能力分离：{@code RemoteTemplateCatalog} 是可选能力
 * （template_catalog.go L45-58："session lifecycle never needs template
 * administration"），{@code ConfigSandboxClient} 只需要 list/delete。exec/PTY/
 * 文件/快照构建不在本批。ensure/replace 标准模板只在控制面<b>可达</b>后才会被
 * 调用——dev 恒不可达，故本批以 UNSUPPORTED 分类收场（不冒充真实路由）。</p>
 *
 * <h2>路由说明（诚实声明）</h2>
 * <p>cube/e2b 的请求路由按各自控制面的公开 API 形状对齐（cube: /health、
 * /templates、/sandboxes；e2b: /sandboxes?limit=1 即 ListSandboxesV2 的探测形、
 * /templates）。Go 侧走各自 SDK，本批不引入 SDK 依赖——<b>已被 A/B 钉住的契约
 * 是失败分类与响应形态</b>（连接拒绝 → Unavailable → 固定文案），路由字面在
 * 接入真实 provider 时再校准。</p>
 */
public final class RemoteProviderClient {

    /** provider 名（对照 RemoteProvider："cube"/"e2b"/"docker"）。 */
    public final String provider;
    /** 请求基址（已含 scheme）。 */
    public final String baseUrl;
    /** Bearer 凭据；空 = 不带鉴权头（cube 的单节点无鉴权形态）。 */
    public final String apiKey;
    /** 单次 HTTP 调用的超时（秒，0 = 30）。 */
    public final long httpTimeoutSec;

    private final HttpClient http;

    private RemoteProviderClient(String provider, String baseUrl, String apiKey,
            long httpTimeoutSec) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.httpTimeoutSec = httpTimeoutSec > 0 ? httpTimeoutSec : 30;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.httpTimeoutSec))
                .build();
    }

    /** 对照 NewRemoteClientForCheck 的 cube 分支（tenant_resolver.go L244）。 */
    public static RemoteProviderClient cube(EffectiveConfig cfg) {
        return new RemoteProviderClient("cube", cfg.cubeApiUrl, cfg.cubeApiKey,
                cfg.cubeHttpTimeoutSec);
    }

    /** 对照 NewRemoteClientForCheck 的 e2b 分支（tenant_resolver.go L247）。 */
    public static RemoteProviderClient e2b(EffectiveConfig cfg) {
        String base = cfg.e2bApiUrl == null || cfg.e2bApiUrl.isEmpty()
                ? "https://api.e2b.app" : cfg.e2bApiUrl;
        return new RemoteProviderClient("e2b", base, cfg.e2bApiKey, cfg.e2bHttpTimeoutSec);
    }

    /**
     * 对照 NewDockerRemoteClientForCheck：先过 docker 后端开关（tenant_resolver.go
     * L253-256），Host 形如 DOCKER_HOST；unix socket 由本机 CLI 托管，本批只支持
     * TCP 探测。
     */
    public static RemoteProviderClient docker(EffectiveConfig cfg) {
        SandboxBackendPolicy.ensureDockerBackendAllowed(SandboxTypes.TYPE_DOCKER);
        String host = cfg.dockerHost == null || cfg.dockerHost.isEmpty()
                ? EffectiveConfig.DEFAULT_DOCKER_HOST : cfg.dockerHost;
        if (host.startsWith("unix://")) {
            throw RemoteError.of("docker", "Health", RemoteErrorKind.UNAVAILABLE,
                    "Cannot connect to the Docker daemon at " + host);
        }
        return new RemoteProviderClient("docker", host, "", cfg.dockerHttpTimeoutSec);
    }

    // ── 探测/盘点面 ───────────────────────────────────────────────────────

    /** 对照 Health（cube L159 / e2b L188——e2b 用 ListSandboxesV2 limit=1 探测）。 */
    public void health(String pathAndQuery) {
        send("Health", "GET", pathAndQuery, null);
    }

    /** 对照 RemoteTemplateCatalog.ListTemplates（cube GET /templates）。 */
    public String listTemplates(String pathAndQuery) {
        return send("ListTemplates", "GET", pathAndQuery, null);
    }

    /** 对照 ConfigSandboxLister.List。 */
    public String list(String pathAndQuery) {
        return send("List", "GET", pathAndQuery, null);
    }

    /** 对照 ConfigSandboxClient.Delete。 */
    public void delete(String pathAndQuery) {
        send("Delete", "DELETE", pathAndQuery, null);
    }

    // ── 传输 ─────────────────────────────────────────────────────────────

    String send(String op, String method, String pathAndQuery, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathAndQuery))
                .timeout(Duration.ofSeconds(httpTimeoutSec))
                .header("Accept", "application/json");
        if (!apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException err) {
            RemoteErrorKind kind = RemoteError.classifyTransport(err);
            throw new RemoteError(provider, op, kind, "", err, 0);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            throw RemoteError.of(provider, op, RemoteErrorKind.INTERNAL, "interrupted");
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response.body();
        }
        throw new RemoteError(provider, op,
                RemoteError.httpErrorKind(op, status),
                "unexpected status " + status, null, status);
    }

    /** 三个探测方法的路由常量（集中一处便于接入真实 provider 时校准）。 */
    public static final class Routes {
        public static final String CUBE_HEALTH = "/health";
        public static final String CUBE_TEMPLATES = "/templates";
        public static final String CUBE_SANDBOXES = "/sandboxes";
        public static final String E2B_HEALTH = "/sandboxes?limit=1";
        public static final String E2B_TEMPLATES = "/templates";
        public static final String E2B_SANDBOXES = "/sandboxes";
        public static final String DOCKER_PING = "/_ping";

        private Routes() {
        }
    }

    /** 未实现能力的统一出口（ensure/replace：仅控制面可达后调用）。 */
    public static RemoteError unsupportedCatalogWrite(String provider, String op) {
        return RemoteError.of(provider, op, RemoteErrorKind.UNSUPPORTED,
                "not wired in this build; only reachable with a live control plane");
    }
}
