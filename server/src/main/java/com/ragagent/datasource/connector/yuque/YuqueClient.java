package com.ragagent.datasource.connector.yuque;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorHttp;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.ApiErrorBody;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2Doc;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2DocDetail;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2DocDetailResponse;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2DocListResponse;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2Group;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2GroupListResponse;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2Repo;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2RepoListResponse;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2User;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2UserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 语雀 Open API v2 客户端（对照 Go {@code yuque/client.go} 全文）。
 *
 * <h2>令牌永不进日志</h2>
 * <p>原始 {@code X-Auth-Token} <b>从不</b>被记录；只在整条 client 生命期的
 * <b>第一次</b>真实请求时打一行脱敏形态（{@link #redactToken}），而不是每个请求都打
 * ——否则千文档规模的同步日志会被它淹没（这正是 Go 注释里写明的理由）。</p>
 *
 * <h2>重试矩阵（与 Go 逐行对应）</h2>
 * <ul>
 *   <li>传输层失败 → 退避 {@code backoff[attempt]}；</li>
 *   <li>429 → 等 {@link #parseRetryAfter}（{@code Retry-After} 头，缺失/不可解析时
 *       回落 {@code backoff[min(attempt, len-1)]}）；</li>
 *   <li>5xx → 只重试 {@code max5xxRetries}（1）次，固定等 {@code retry5xxDelay}；</li>
 *   <li>401/403 → 立刻 {@link ConnectorException.InvalidCredentials}（不重试，
 *       让 service 能把"坏令牌"与"临时故障"分开并自动标记数据源）；</li>
 *   <li>其它非 2xx → 优先用错误体的 {@code message}，没有才回落到响应体预览。</li>
 * </ul>
 *
 * <p>顺序有语义：401/403 的判定在 5xx <b>之后</b>，所以一个 503 不会被误判成凭据问题。</p>
 */
public class YuqueClient {

    private static final Logger log = LoggerFactory.getLogger(YuqueClient.class);

    /** 对照 Go {@code yuque/client.go} 的常量。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final String USER_AGENT = "WeKnora-Yuque-Connector/1.0";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String baseUrl;
    private final String token;
    private final ConnectorHttp.Client httpClient;
    private final YuqueRetryPolicy retry;
    private final AtomicBoolean tokenLogged = new AtomicBoolean();

    public YuqueClient(YuqueConfig cfg) {
        this(cfg, YuqueRetryPolicy.defaults());
    }

    public YuqueClient(YuqueConfig cfg, YuqueRetryPolicy retryPolicy) {
        this.baseUrl = cfg.baseURL();
        this.token = cfg.getApiToken() == null ? "" : cfg.getApiToken();
        this.httpClient = ConnectorHttp.newConnectorHttpClient(DEFAULT_TIMEOUT);
        this.retry = retryPolicy == null ? YuqueRetryPolicy.defaults() : retryPolicy;
    }

    // ── 端点 ──────────────────────────────────────────────────────────────

    /** 对照 Go {@code Ping}：{@code GET /api/v2/user} 验凭据。 */
    public void ping() {
        doRequest("GET", "/api/v2/user", V2UserResponse.class);
    }

    /** 对照 Go {@code GetCurrentUser}。 */
    public V2User getCurrentUser() {
        return doRequest("GET", "/api/v2/user", V2UserResponse.class).getData();
    }

    /**
     * 对照 Go {@code ListUserGroups}：给定用户所属的团队。
     *
     * <p>注意 {@code userId} 是语雀的**数字**用户 ID（不是 login）——
     * {@code /users/{id}/groups} 只认整数形式。</p>
     *
     * <p>用户没加入任何团队时语雀返回 <b>404</b>（不是空列表），
     * 由调用方把它当成"没有团队"处理。</p>
     */
    public List<V2Group> listUserGroups(long userId) {
        String path = "/api/v2/users/" + userId + "/groups";
        return doRequest("GET", path, V2GroupListResponse.class).getData();
    }

    /** 对照 Go {@code ListUserRepos}：某个用户 login 名下 type=Book 的仓库。 */
    public List<V2Repo> listUserRepos(String login) {
        return listReposPaginated("/api/v2/users/" + login + "/repos");
    }

    /** 对照 Go {@code ListGroupRepos}：某个团队 login 名下 type=Book 的仓库。 */
    public List<V2Repo> listGroupRepos(String login) {
        return listReposPaginated("/api/v2/groups/" + login + "/repos");
    }

    /**
     * 对照 Go {@code listReposPaginated}：按 offset 翻页，只取
     * {@code type=Book}（设计稿 / 表格 / 资源库都跳过）。
     */
    private List<V2Repo> listReposPaginated(String basePath) {
        List<V2Repo> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            String q = buildQuery(Map.of(
                    "type", "Book",
                    "offset", Integer.toString(offset),
                    "limit", Integer.toString(DEFAULT_PAGE_SIZE)));
            V2RepoListResponse resp = doRequest("GET", basePath + q, V2RepoListResponse.class);
            if (resp.getData() != null) {
                all.addAll(resp.getData());
            }
            if (resp.size() < DEFAULT_PAGE_SIZE) {
                break;
            }
            offset += DEFAULT_PAGE_SIZE;
        }
        return all;
    }

    /**
     * 对照 Go {@code ListBookDocs}：列一本书里的全部文档（只含摘要，正文要
     * {@link #getDocDetail}）。
     */
    public List<V2Doc> listBookDocs(long bookId) {
        String basePath = "/api/v2/repos/" + bookId + "/docs";
        List<V2Doc> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            String q = buildQuery(Map.of(
                    "offset", Integer.toString(offset),
                    "limit", Integer.toString(DEFAULT_PAGE_SIZE)));
            V2DocListResponse resp = doRequest("GET", basePath + q, V2DocListResponse.class);
            if (resp.getData() != null) {
                all.addAll(resp.getData());
            }
            if (resp.size() < DEFAULT_PAGE_SIZE) {
                break;
            }
            offset += DEFAULT_PAGE_SIZE;
        }
        return all;
    }

    /** 对照 Go {@code GetDocDetail}：按文档 ID 取全文（含 {@code body}）。 */
    public V2DocDetail getDocDetail(long docId) {
        String path = "/api/v2/repos/docs/" + docId;
        return doRequest("GET", path, V2DocDetailResponse.class).getData();
    }

    // ── 请求核心 ──────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code doRequest}：带重试的鉴权请求 + JSON 解码。
     *
     * @param resultType 目标类型；Go 传的是结果指针，Java 传类对象
     */
    <T> T doRequest(String method, String path, Class<T> resultType) {
        logTokenOnce();

        ConnectorException lastErr = null;
        for (int attempt = 0; attempt <= retry.maxRetries(); attempt++) {
            String reqUrl = baseUrl + path;

            Map<String, String> headers = new java.util.LinkedHashMap<>();
            headers.put("X-Auth-Token", token);
            headers.put("User-Agent", USER_AGENT);
            headers.put("Content-Type", "application/json; charset=utf-8");

            if (attempt == 0) {
                log.info("[Yuque] {} {}", method, path);
            } else {
                log.info("[Yuque] {} {} (retry {}/{})", method, path, attempt, retry.maxRetries());
            }

            ConnectorHttp.Response resp;
            try {
                resp = httpClient.exchange(method, reqUrl, headers, null);
            } catch (ConnectorException e) {
                lastErr = e;
                if (attempt < retry.maxRetries()) {
                    Connector.sleep(retry.backoffAt(attempt).toMillis());
                    continue;
                }
                throw lastErr;
            }

            String bodyPreview = resp.truncatedBody(500);
            log.info("[Yuque] {} {} -> status={} bodyLen={} body={}",
                    method, path, resp.status(), resp.body() == null ? 0 : resp.body().length,
                    bodyPreview);

            if (resp.status() == 429) {
                Duration wait = parseRetryAfter(resp.header("Retry-After"), retry.backoffAt(attempt));
                lastErr = new ConnectorException("yuque rate limited: status=429 body=" + bodyPreview);
                if (attempt < retry.maxRetries()) {
                    Connector.sleep(wait.toMillis());
                    continue;
                }
                throw lastErr;
            }

            if (resp.status() >= 500 && resp.status() < 600) {
                lastErr = new ConnectorException(
                        "yuque server error: status=" + resp.status() + " body=" + bodyPreview);
                if (attempt < retry.max5xxRetries()) {
                    Connector.sleep(retry.retry5xxDelay().toMillis());
                    continue;
                }
                throw lastErr;
            }

            // 401/403 → 暴露成 ErrInvalidCredentials，让 DataSourceService 能把
            // "坏令牌"与临时故障分开、并自动标记数据源。
            if (resp.status() == 401 || resp.status() == 403) {
                throw new ConnectorException.InvalidCredentials(
                        "status=" + resp.status() + " body=" + bodyPreview);
            }

            if (resp.status() < 200 || resp.status() >= 300) {
                ApiErrorBody apiErr = tryParseError(resp);
                if (apiErr != null && apiErr.getMessage() != null && !apiErr.getMessage().isEmpty()) {
                    throw new ConnectorException("yuque api error: status=" + resp.status()
                            + " msg=" + apiErr.getMessage());
                }
                throw new ConnectorException("yuque api error: status=" + resp.status()
                        + " body=" + bodyPreview);
            }

            try {
                byte[] raw = resp.body() == null ? new byte[0] : resp.body();
                T value = MAPPER.readValue(raw, resultType);
                // Go 的 json.Unmarshal("null", &resp) 成功且留下**零值** struct；
                // Jackson 直接回 null，所以这里补一个零值实例。
                return value != null ? value : newInstance(resultType);
            } catch (java.io.IOException | RuntimeException e) {
                throw new ConnectorException("decode response: " + e.getMessage(), e);
            }
        }
        throw lastErr;
    }

    /** 错误体解析失败时 Go 是 {@code _ = json.Unmarshal(...)} —— 静默忽略。 */
    private static ApiErrorBody tryParseError(ConnectorHttp.Response resp) {
        try {
            byte[] raw = resp.body() == null ? new byte[0] : resp.body();
            return MAPPER.readValue(raw, ApiErrorBody.class);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ConnectorException("decode response: cannot instantiate " + type.getName(), e);
        }
    }

    /** 对照 Go 的 {@code c.logTokenOnce.Do(...)}：整条 client 生命期只记一次。 */
    private void logTokenOnce() {
        if (tokenLogged.compareAndSet(false, true)) {
            log.info("[Yuque] client configured token={} base={}", redactToken(token), baseUrl);
        }
    }

    // ── 纯函数 ────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code parseRetryAfter}：从 {@code Retry-After} 头取等待时长，
     * 取不到就回落到 {@code fallback}。
     *
     * <p>{@code Retry-After: "0"}（或负数）被强制成 <b>100ms</b>，
     * 这样我们仍然让出一次调度、不会忙重试。</p>
     *
     * <p>只支持整数秒形式（RFC 7231 也允许 HTTP-date，但语雀从没发过）。
     * 解析用的是 {@link GoDuration#parse}（{@code header + "s"}），
     * 所以连"什么算合法"都与 Go 一致：{@code "abc"} → {@code "abcs"} 失败、
     * {@code "1s"} → {@code "1ss"} 失败、{@code "0.5"} → 500ms 成功。</p>
     */
    public static Duration parseRetryAfter(String header, Duration fallback) {
        if (header == null || header.isEmpty()) {
            return fallback;
        }
        try {
            Duration secs = GoDuration.parse(header + "s");
            if (secs.isZero() || secs.isNegative()) {
                return Duration.ofMillis(100);
            }
            return secs;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * 对照 Go {@code redactToken}：令牌的脱敏形态，<b>绝不</b>记录完整令牌。
     *
     * <p>按 Go 的**字节**语义实现（{@code len(t) < 12} 数的是字节，切片也是字节切片）：
     * ASCII 令牌下与按字符实现完全一致。</p>
     */
    public static String redactToken(String t) {
        byte[] b = (t == null ? "" : t).getBytes(StandardCharsets.UTF_8);
        if (b.length < 12) {
            return "***";
        }
        byte[] out = new byte[6 + 3 + 4];
        System.arraycopy(b, 0, out, 0, 6);
        out[6] = '.';
        out[7] = '.';
        out[8] = '.';
        System.arraycopy(b, b.length - 4, out, 9, 4);
        return new String(out, StandardCharsets.UTF_8);
    }

    /**
     * 对照 Go {@code buildQuery}：编码查询参数，**省略空值**；返回串带前导
     * {@code "?"}，全空时回空串。
     *
     * <p>Go 的 {@code url.Values.Encode()} 按 key <b>升序</b>拼，所以 Java 侧用
     * {@link TreeMap}；转义规则也照抄 Go 的 {@code QueryEscape}
     * （保留 {@code A-Za-z0-9-_.~}，空格转 {@code +}，其余百分号转义）。
     * 用 {@code URLEncoder} 会在 {@code *} 等字符上分叉，所以自己写。</p>
     */
    static String buildQuery(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(params).entrySet()) {
            String v = entry.getValue();
            if (v == null || v.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(goQueryEscape(entry.getKey())).append('=').append(goQueryEscape(v));
        }
        return sb.length() == 0 ? "" : "?" + sb;
    }

    /** 对照 Go 的 {@code url.QueryEscape}（保留 {@code -_.~}，空格转 {@code +}）。 */
    private static String goQueryEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (byte raw : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (raw & 0xFF);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }
}
