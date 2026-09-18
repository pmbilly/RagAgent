package com.ragagent.datasource.connector.feishu.core;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorHttp;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DocRawContentResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveFile;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveFileListFailure;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveFileListResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveFolderMetaResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveShortcutInfo;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.ExportTaskCreateResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.ExportTaskStatusResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.PartialDriveFileListException;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.PartialWikiNodeListException;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.TokenResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNode;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNodeInfoResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNodeListFailure;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNodeListResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiSpace;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiSpaceListResponse;

/**
 * 飞书 Open Platform API 客户端（对照 Go {@code core/client.go} 全文）。
 *
 * <h2>三次重试策略（wiki 与云盘共用）</h2>
 * <p>飞书的 drive export / wiki 接口限流很凶，一次上千文档的同步要发几万次调用；
 * 没有退避时<b>一波 429 就会静默失败一大片文档</b>。策略（{@link #doRequest} 与
 * {@link #downloadRawBytes} 共用）：</p>
 * <ul>
 *   <li>429 → 尊重 {@code Retry-After}，最多 1+3 次；</li>
 *   <li>5xx → 只重试<b>一次</b>（{@link #MAX_5XX_RETRIES}）；</li>
 *   <li>其它非 2xx（4xx）→ 立刻失败，重试没有意义；</li>
 *   <li>传输层错误 → 按 {@link #RETRY_BACKOFF} 退避。</li>
 * </ul>
 *
 * <h2>context.Context 去哪了（约定 §5 的落地）</h2>
 * <ul>
 *   <li>请求级超时：Go 的 {@code context.WithTimeout} → Java 落在
 *       {@link ConnectorHttp.Client} 的构造参数（{@link #REQUEST_TIMEOUT}，= Go 的 30s）；</li>
 *   <li>取消：Go 的 {@code ctx.Done()} → Java 的线程中断，
 *       {@link com.ragagent.datasource.Connector#sleep(long)} 会把它转成
 *       {@link ConnectorException}（对照 Go 的 {@code sleepCtx} 返回 {@code ctx.Err()}）。</li>
 * </ul>
 *
 * <h2>工具链差异（都在注释里就地标注）</h2>
 * <ol>
 *   <li>{@code io.ReadAll(resp.Body)} 在 Java 侧由 {@code exchange} 一次做完，
 *       所以 Go 那条 "read response body: %w" 的重试分支在 Java 不可达（照抄会变成死代码，
 *       故省略并把这条差异记在此处）。</li>
 *   <li>{@code io.LimitReader} 的 512MB 上限在 Java 侧只能"读完再判"——
 *       JDK 的 {@code HttpClient} 不允许替换 BodyHandler 的分块读取。
 *       净效果一致（超限即报错），差别只在内存峰值。</li>
 * </ol>
 *
 * <p><b>这是内部类型</b>：只进出飞书 API，从不落 jsonb、从不进 HTTP 响应。</p>
 */
public class FeishuClient implements DocxMarkdown.SheetReader {

    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);

    /** 对照 Go {@code 30 * time.Second}（{@code NewClient} 传给 {@code NewConnectorHTTPClient}）。 */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** 对照 Go {@code feishuMaxRetries}。 */
    public static final int MAX_RETRIES = 3;

    /** 对照 Go {@code feishuMax5xxRetries}。 */
    public static final int MAX_5XX_RETRIES = 1;

    /** 对照 Go {@code maxFeishuDownloadBytes}（512 MB）。 */
    public static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;

    /**
     * 5xx 重试前的固定等待（对照 Go 的 {@code feishuRetry5xxDelay} 常量）。
     *
     * <p>Go 侧是 {@code const = 2 * time.Second}，测试跑起来就是真等 2 秒。
     * Java 侧做成<b>可覆盖的字段</b>，让重试次数类用例不必真的睡 2 秒
     * （任务书约束第 3 条：不靠墙钟造时间）。生产取默认值，与 Go 一致。</p>
     */
    public static volatile Duration retry5xxDelay = Duration.ofSeconds(2);

    /** 对照 Go {@code feishuRetryBackoff}（包级 var，可被测试覆盖）。 */
    public static volatile List<Duration> retryBackoff =
            List.of(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8));

    /**
     * 导出任务的最长轮询时间与轮询间隔（对照 Go {@code ExportAndDownload} 里的
     * {@code 60 * time.Second} 与 {@code time.After(2 * time.Second)}）。
     *
     * <p>做成字段是<b>刻意的</b>：Go 把这两个数写死在函数里，测试只能靠"导出立刻完成"
     * 绕开等待。Java 侧留出注入缝，超时分支才测得到，且不必睡 60 秒。</p>
     */
    public static volatile Duration exportTimeout = Duration.ofSeconds(60);

    /** @see #exportTimeout */
    public static volatile Duration exportPollInterval = Duration.ofSeconds(2);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String baseUrl;
    private final String appId;
    private final String appSecret;

    /** 多维表格日期单元格的渲染时区（默认 GMT+8）。 */
    private final ZoneId location;

    private final ConnectorHttp.Client httpClient;

    // Token 缓存（线程安全，对照 Go 的 tokenMu + tokenCache + tokenExpAt）
    private final Object tokenLock = new Object();
    private String tokenCache = "";
    private OffsetDateTime tokenExpAt = OffsetDateTime.MIN;

    /** 对照 Go {@code NewClient}。 */
    public FeishuClient(FeishuConfig config) {
        this(config.resolveBaseUrl(), config.getAppId(), config.getAppSecret(),
                FeishuConfig.resolveLocation(config.getTimezone()),
                ConnectorHttp.newConnectorHttpClient(REQUEST_TIMEOUT));
    }

    /**
     * 完整构造器——测试直接指到本机 stub server 用（对照 Go 测试里
     * {@code &Client{baseURL: srv.URL, appID: "a", appSecret: "s", httpClient: srv.Client()}}
     * 的同包直构）。
     */
    public FeishuClient(String baseUrl, String appId, String appSecret, ZoneId location,
                        ConnectorHttp.Client httpClient) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.appId = appId == null ? "" : appId;
        this.appSecret = appSecret == null ? "" : appSecret;
        this.location = location;
        this.httpClient = httpClient;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** 对照 Go {@code (*Client).tz()}：没配 location 时回落 GMT+8。 */
    public ZoneId tz() {
        return location != null
                ? location
                : ZoneId.ofOffset("GMT", java.time.ZoneOffset.ofTotalSeconds(
                        FeishuConfig.DEFAULT_TIMEZONE_OFFSET_SECONDS));
    }

    // ──────────────────────────────────────────────────────────────────
    // 认证
    // ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code GetTenantAccessToken}：取（或返回缓存的）tenant access token。
     *
     * <p>飞书 token 有效期 2 小时；这里留 <b>5 分钟安全边际</b>再过期。
     * 与 Go 一样整段加锁——并发同步任务会同时打进来。</p>
     */
    public String getTenantAccessToken() {
        synchronized (tokenLock) {
            if (!tokenCache.isEmpty() && OffsetDateTime.now().isBefore(tokenExpAt)) {
                return tokenCache;
            }

            byte[] payload;
            try {
                payload = MAPPER.writeValueAsBytes(Map.of("app_id", appId, "app_secret", appSecret));
            } catch (Exception e) {
                throw new ConnectorException("marshal token request: " + e.getMessage(), e);
            }

            String url = baseUrl + "/open-apis/auth/v3/tenant_access_token/internal";
            ConnectorHttp.Response resp = httpClient.exchange("POST", url,
                    Map.of("Content-Type", "application/json; charset=utf-8"), payload);

            TokenResponse result;
            try {
                result = MAPPER.readValue(resp.bodyAsString(), TokenResponse.class);
            } catch (Exception e) {
                throw new ConnectorException("decode token response: " + e.getMessage(), e);
            }
            if (result == null) {
                throw new ConnectorException("decode token response: empty body");
            }
            if (result.code() != 0) {
                throw new ConnectorException(
                        "feishu auth error: code=" + result.code() + " msg=" + result.msg());
            }

            String token = result.tenantAccessToken() == null ? "" : result.tenantAccessToken();
            tokenCache = token;
            Duration ttl = Duration.ofSeconds(result.expire());
            if (ttl.compareTo(Duration.ofMinutes(5)) > 0) {
                ttl = ttl.minusMinutes(5);
            }
            tokenExpAt = OffsetDateTime.now().plus(ttl);

            int prefixLen = Math.min(8, token.length());
            int suffixLen = Math.min(4, token.length());
            log.info("[Feishu] got tenant_access_token: {}...{} expire={}s",
                    token.substring(0, prefixLen), token.substring(token.length() - suffixLen),
                    result.expire());

            return tokenCache;
        }
    }

    /** 对照 Go {@code Ping}：拿一次 token 即算验活。 */
    public void ping() {
        getTenantAccessToken();
    }

    // ──────────────────────────────────────────────────────────────────
    // 通用请求（JSON API）
    // ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code DoRequest}：带鉴权的 API 调用 + JSON 解码 + 瞬时失败重试。
     *
     * @param method     {@code "GET"} / {@code "POST"} …
     * @param path       以 {@code /open-apis/...} 开头的路径（baseUrl 由客户端补上）
     * @param body       请求体对象；{@code null} 表示无体
     * @param resultType 解码目标；{@code null} 表示不关心响应体（对照 Go 的 {@code result == nil}）
     * @return 解码结果；{@code resultType == null} 时返回 {@code null}
     */
    public <T> T doRequest(String method, String path, Object body, Class<T> resultType) {
        String token = getTenantAccessToken();

        byte[] bodyBytes = null;
        if (body != null) {
            try {
                bodyBytes = MAPPER.writeValueAsBytes(body);
            } catch (Exception e) {
                throw new ConnectorException("marshal request body: " + e.getMessage(), e);
            }
        }

        String url = baseUrl + path;
        RuntimeException lastErr = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt == 0) {
                log.info("[Feishu] {} {}", method, path);
            } else {
                log.info("[Feishu] {} {} (retry {}/{})", method, path, attempt, MAX_RETRIES);
            }

            ConnectorHttp.Response resp;
            try {
                resp = httpClient.exchange(method, url, Map.of(
                        "Content-Type", "application/json; charset=utf-8",
                        "Authorization", "Bearer " + token), bodyBytes);
            } catch (RuntimeException e) {
                // 对照 Go：c.httpClient.Do(req) 失败 → 退避重试
                lastErr = e instanceof ConnectorException ce
                        ? ce : new ConnectorException("execute request: " + e.getMessage(), e);
                if (attempt < MAX_RETRIES) {
                    Connector.sleep(backoffAt(attempt).toMillis());
                    continue;
                }
                throw lastErr;
            }

            String respBody = resp.bodyAsString();
            log.info("[Feishu] {} {} → status={} bodyLen={} body={}",
                    method, path, resp.status(), resp.body() == null ? 0 : resp.body().length,
                    FeishuSupport.truncate(respBody, 1000));

            if (resp.status() == 429) {
                Duration wait = parseRetryAfter(resp.header("Retry-After"), backoffAt(attempt));
                lastErr = new ConnectorException(
                        "feishu rate limited: status=429 body=" + FeishuSupport.truncate(respBody, 500));
                if (attempt < MAX_RETRIES) {
                    Connector.sleep(wait.toMillis());
                    continue;
                }
                throw lastErr;
            }

            if (resp.status() >= 500 && resp.status() < 600) {
                lastErr = new ConnectorException("feishu server error: status=" + resp.status()
                        + " body=" + FeishuSupport.truncate(respBody, 500));
                if (attempt < MAX_5XX_RETRIES) {
                    Connector.sleep(retry5xxDelay.toMillis());
                    continue;
                }
                throw lastErr;
            }

            if (resp.status() != 200) {
                // 对照 Go：这一支用**完整** body（不截断）
                throw new ConnectorException(
                        "feishu api error: status=" + resp.status() + " body=" + respBody);
            }

            if (resultType == null) {
                return null;
            }
            try {
                return MAPPER.readValue(respBody, resultType);
            } catch (Exception e) {
                throw new ConnectorException("decode response: " + e.getMessage(), e);
            }
        }

        // 不可达：循环内每个分支要么 return 要么 throw（保留以对齐 Go 的收尾 return）
        throw lastErr != null ? lastErr : new ConnectorException("request failed");
    }

    private static Duration backoffAt(int attempt) {
        List<Duration> backoff = retryBackoff;
        int idx = Math.min(attempt, backoff.size() - 1);
        return backoff.get(idx);
    }

    /**
     * 对照 Go {@code parseRetryAfter}：把 {@code Retry-After}（秒）解释成等待时长，
     * {@code 0}/负数强制成 100ms 的短延迟，缺失或不可解析时回落。
     *
     * <p>Go 的测试直接调这个包级函数，所以 Java 侧也保持静态可调。</p>
     */
    public static Duration parseRetryAfter(String header, Duration fallback) {
        if (header == null || header.isEmpty()) {
            return fallback;
        }
        double secs;
        try {
            secs = Double.parseDouble(header.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
        if (secs <= 0) {
            return Duration.ofMillis(100);
        }
        return Duration.ofNanos((long) (secs * 1_000_000_000L));
    }

    // ──────────────────────────────────────────────────────────────────
    // wiki：空间 / 节点
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code ListWikiSpaces}：列出应用可见的全部 wiki 空间（自动翻页）。 */
    public List<WikiSpace> listWikiSpaces() {
        List<WikiSpace> allSpaces = new ArrayList<>();
        String pageToken = "";
        while (true) {
            String path = "/open-apis/wiki/v2/spaces?page_size=50";
            if (!pageToken.isEmpty()) {
                path += "&page_token=" + pageToken;
            }

            WikiSpaceListResponse resp = doRequest("GET", path, null, WikiSpaceListResponse.class);
            if (resp == null || resp.code() != 0) {
                int code = resp == null ? -1 : resp.code();
                String msg = resp == null ? "" : resp.msg();
                log.error("[Feishu] ListWikiSpaces error: code={} msg={}", code, msg);
                throw new ConnectorException("list wiki spaces error: code=" + code + " msg=" + msg);
            }

            List<WikiSpace> items = resp.data() == null ? List.of() : nvl(resp.data().items());
            log.info("[Feishu] ListWikiSpaces: got {} spaces, has_more={}",
                    items.size(), resp.data() != null && resp.data().hasMore());
            for (int i = 0; i < items.size(); i++) {
                WikiSpace s = items.get(i);
                log.info("[Feishu]   space[{}]: id={} name=\"{}\" visibility={}",
                        i, s.spaceId(), s.name(), s.visibility());
            }

            allSpaces.addAll(items);

            if (resp.data() == null || !resp.data().hasMore()
                    || resp.data().pageToken() == null || resp.data().pageToken().isEmpty()) {
                break;
            }
            pageToken = resp.data().pageToken();
        }

        log.info("[Feishu] ListWikiSpaces: total {} spaces", allSpaces.size());
        return allSpaces;
    }

    /**
     * 对照 Go {@code ListWikiNodes}：列出某空间下的全部节点（自动翻页）。
     * {@code parentNodeToken} 为空时返回顶层节点。
     */
    public List<WikiNode> listWikiNodes(String spaceId, String parentNodeToken) {
        List<WikiNode> allNodes = new ArrayList<>();
        String pageToken = "";
        String parent = parentNodeToken == null ? "" : parentNodeToken;

        while (true) {
            String path = "/open-apis/wiki/v2/spaces/" + spaceId + "/nodes?page_size=50";
            if (!parent.isEmpty()) {
                path += "&parent_node_token=" + parent;
            }
            if (!pageToken.isEmpty()) {
                path += "&page_token=" + pageToken;
            }

            WikiNodeListResponse resp = doRequest("GET", path, null, WikiNodeListResponse.class);
            if (resp == null || resp.code() != 0) {
                int code = resp == null ? -1 : resp.code();
                String msg = resp == null ? "" : resp.msg();
                throw new ConnectorException("list wiki nodes error: code=" + code + " msg=" + msg);
            }

            for (WikiNode node : resp.data() == null ? List.<WikiNode>of() : nvl(resp.data().items())) {
                // 飞书对"列子节点"的响应有时不带 parent_node_token / space_id，就地补齐，
                // 否则下游的 ResolveResourceAncestors 与 picker 展开会丢层级。
                if (!parent.isEmpty() && node.getParentNodeId().isEmpty()) {
                    node.setParentNodeId(parent);
                }
                if (node.getSpaceId().isEmpty()) {
                    node.setSpaceId(spaceId);
                }
                allNodes.add(node);
            }

            if (resp.data() == null || !resp.data().hasMore()
                    || resp.data().pageToken() == null || resp.data().pageToken().isEmpty()) {
                break;
            }
            pageToken = resp.data().pageToken();
        }

        return allNodes;
    }

    /** 对照 Go {@code GetWikiNode}：取单个 wiki 节点的元数据。 */
    public WikiNode getWikiNode(String spaceId, String nodeToken) {
        String path = "/open-apis/wiki/v2/spaces/get_node?token="
                + FeishuSupport.queryEscape(nodeToken);

        WikiNodeInfoResponse resp = doRequest("GET", path, null, WikiNodeInfoResponse.class);
        if (resp == null || resp.code() != 0) {
            int code = resp == null ? -1 : resp.code();
            String msg = resp == null ? "" : resp.msg();
            throw new ConnectorException("get wiki node error: code=" + code + " msg=" + msg);
        }
        if (resp.data() == null || resp.data().node() == null) {
            throw new ConnectorException("get wiki node error: code=0 msg=empty node");
        }

        WikiNode node = resp.data().node();
        if (node.getSpaceId().isEmpty()) {
            node.setSpaceId(spaceId);
        }
        return node;
    }

    /**
     * 对照 Go {@code listAllWikiNodesRecursive}：深度优先列出空间下全部节点。
     *
     * <p>部分子树列举失败时收集进 {@link PartialWikiNodeListException} 并<b>继续</b>——
     * 已经拿到的节点照样可用。</p>
     */
    public List<WikiNode> listAllWikiNodesRecursive(String spaceId) {
        List<WikiNode> topNodes = listWikiNodes(spaceId, "");

        List<WikiNode> allNodes = new ArrayList<>();
        List<WikiNodeListFailure> failures = new ArrayList<>();
        walkWikiNodes(this, spaceId, topNodes, allNodes, failures);

        if (!failures.isEmpty()) {
            throw new PartialWikiNodeListException(allNodes, failures);
        }
        return allNodes;
    }

    private static void walkWikiNodes(FeishuClient client, String spaceId, List<WikiNode> nodes,
                                      List<WikiNode> allNodes, List<WikiNodeListFailure> failures) {
        for (WikiNode node : nodes) {
            allNodes.add(node);
            if (!node.isHasChild()) {
                continue;
            }
            List<WikiNode> children;
            try {
                children = client.listWikiNodes(spaceId, node.getNodeToken());
            } catch (RuntimeException e) {
                RuntimeException wrapped = new ConnectorException(
                        "list children of " + node.getNodeToken() + ": " + e.getMessage(), e);
                failures.add(new WikiNodeListFailure(node, wrapped));
                log.warn("[Feishu] partial wiki node listing failure: space={} node={} err={}",
                        spaceId, node.getNodeToken(), e.getMessage());
                continue;
            }
            walkWikiNodes(client, spaceId, children, allNodes, failures);
        }
    }

    /**
     * 对照 Go {@code ListWikiNodesRecursiveFrom}：返回某个节点<b>及其全部后代</b>。
     * {@code nodeToken} 为空时等价于整空间遍历。
     */
    public List<WikiNode> listWikiNodesRecursiveFrom(String spaceId, String nodeToken) {
        if (nodeToken == null || nodeToken.isEmpty()) {
            return listAllWikiNodesRecursive(spaceId);
        }

        WikiNode root = getWikiNode(spaceId, nodeToken);

        List<WikiNode> out = new ArrayList<>();
        out.add(root);
        try {
            out.addAll(listWikiNodeDescendants(spaceId, root));
            return out;
        } catch (PartialWikiNodeListException e) {
            // Go: append([]WikiNode{root}, nodes...) 之后把同一个 partial 错误往上抛。
            // 部分结果里 root 仍要保留，所以重建一个携带 root 的异常。
            List<WikiNode> partial = new ArrayList<>();
            partial.add(root);
            partial.addAll(e.getNodes());
            throw new PartialWikiNodeListException(partial, e.getFailures());
        }
    }

    /** 对照 Go {@code listWikiNodeDescendants}（不含 root 本身）。 */
    private List<WikiNode> listWikiNodeDescendants(String spaceId, WikiNode root) {
        if (!root.isHasChild()) {
            return new ArrayList<>();
        }

        List<WikiNode> children;
        try {
            children = listWikiNodes(spaceId, root.getNodeToken());
        } catch (RuntimeException e) {
            RuntimeException wrapped = new ConnectorException(
                    "list children of " + root.getNodeToken() + ": " + e.getMessage(), e);
            log.warn("[Feishu] partial wiki node listing failure: space={} node={} err={}",
                    spaceId, root.getNodeToken(), e.getMessage());
            throw new PartialWikiNodeListException(List.of(), List.of(new WikiNodeListFailure(root, wrapped)));
        }

        List<WikiNode> allNodes = new ArrayList<>();
        List<WikiNodeListFailure> failures = new ArrayList<>();
        walkWikiNodes(this, spaceId, children, allNodes, failures);
        if (!failures.isEmpty()) {
            throw new PartialWikiNodeListException(allNodes, failures);
        }
        return allNodes;
    }

    /**
     * 对照 Go {@code getDocumentRawContent}（已废弃路径，保留以对齐 Go 的 API 面）。
     *
     * @deprecated 优先用 {@link #exportAndDownload}，它保留格式。
     */
    @Deprecated
    public String getDocumentRawContent(String documentId) {
        String path = "/open-apis/docx/v1/documents/" + documentId + "/raw_content";
        DocRawContentResponse resp = doRequest("GET", path, null, DocRawContentResponse.class);
        if (resp == null || resp.code() != 0) {
            int code = resp == null ? -1 : resp.code();
            String msg = resp == null ? "" : resp.msg();
            throw new ConnectorException("get document raw content error: code=" + code + " msg=" + msg);
        }
        return resp.data() == null || resp.data().content() == null ? "" : resp.data().content();
    }

    // ──────────────────────────────────────────────────────────────────
    // 导出任务 API
    //   1. POST /drive/v1/export_tasks            → 建任务，拿 ticket
    //   2. GET  /drive/v1/export_tasks/:ticket    → 轮询到 job_status=0
    //   3. GET  /drive/v1/export_tasks/file/:ticket/download → 下载字节
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code createExportTask}。 */
    String createExportTask(String token, String objType, String fileExtension) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("file_extension", fileExtension);
        body.put("token", token);
        body.put("type", objType);

        ExportTaskCreateResponse resp = doRequest("POST", "/open-apis/drive/v1/export_tasks",
                body, ExportTaskCreateResponse.class);
        if (resp == null || resp.code() != 0) {
            int code = resp == null ? -1 : resp.code();
            String msg = resp == null ? "" : resp.msg();
            throw new ConnectorException("create export task error: code=" + code + " msg=" + msg);
        }
        return resp.data() == null || resp.data().ticket() == null ? "" : resp.data().ticket();
    }

    /** 对照 Go {@code getExportTaskStatus} 的三返回值。 */
    record ExportStatus(String fileToken, String fileName) {
    }

    /**
     * 对照 Go {@code getExportTaskStatus}：轮询导出任务状态。
     *
     * <p>返回的 {@code fileToken} 只有任务成功时才非空；{@code 1}/{@code 2}
     * （初始化中/处理中）返回空 token 表示"还没好"。</p>
     */
    ExportStatus getExportTaskStatus(String ticket, String token) {
        String path = "/open-apis/drive/v1/export_tasks/" + ticket + "?token=" + token;

        ExportTaskStatusResponse resp = doRequest("GET", path, null, ExportTaskStatusResponse.class);
        if (resp == null || resp.code() != 0) {
            int code = resp == null ? -1 : resp.code();
            String msg = resp == null ? "" : resp.msg();
            throw new ConnectorException("get export task status error: code=" + code + " msg=" + msg);
        }

        FeishuApiTypes.ExportTaskResult r =
                resp.data() == null ? null : resp.data().result();
        if (r == null) {
            return new ExportStatus("", "");
        }
        switch (r.jobStatus()) {
            case 0: // 成功
                return new ExportStatus(nvl(r.fileToken()), nvl(r.fileName()));
            case 1, 2: // 初始化中 / 处理中
                return new ExportStatus("", "");
            default:
                throw new ConnectorException("export task failed: status=" + r.jobStatus()
                        + " msg=" + nvl(r.jobErrorMsg()));
        }
    }

    /** 对照 Go {@code downloadExportFile}（导出结果必须在完成后 10 分钟内下载）。 */
    public byte[] downloadExportFile(String fileToken) {
        return downloadRawBytes("/open-apis/drive/v1/export_tasks/file/" + fileToken + "/download");
    }

    /**
     * 对照 Go {@code ExportAndDownload}：建导出任务 → 轮询到完成 → 下载文件。
     *
     * <p>超时 60 秒、轮询间隔 2 秒（见 {@link #exportTimeout} / {@link #exportPollInterval}，
     * 两者在 Java 侧是可覆盖字段，Go 侧写死在函数里）。</p>
     *
     * @param objToken 文档的 obj_token
     * @param objType  飞书的 obj_type（{@code "docx"}/{@code "doc"}/{@code "sheet"}/{@code "bitable"}）
     */
    public ExportDownload exportAndDownload(String objToken, String objType) {
        String fileExt = FeishuConfig.OBJ_TYPE_TO_EXPORT_FILE_EXTENSION.get(objType);
        if (fileExt == null) {
            throw new ConnectorException("unsupported obj_type for export: " + objType);
        }
        String exportType = FeishuConfig.OBJ_TYPE_TO_EXPORT_TYPE.get(objType);
        if (exportType == null) {
            throw new ConnectorException("unsupported obj_type for export: " + objType);
        }

        String ticket = createExportTask(objToken, exportType, fileExt);

        Instant deadline = Instant.now().plus(exportTimeout);
        String fileToken = "";
        String fileName = "";
        while (Instant.now().isBefore(deadline)) {
            ExportStatus st = getExportTaskStatus(ticket, objToken);
            fileToken = st.fileToken();
            fileName = st.fileName();
            if (!fileToken.isEmpty()) {
                break; // 导出就绪
            }
            // 对照 Go 的 select { ctx.Done() / time.After(2s) }：中断语义走 sleep
            Connector.sleep(exportPollInterval.toMillis());
        }

        if (fileToken.isEmpty()) {
            throw new ConnectorException(
                    "export task timed out after " + exportTimeout.toSeconds() + "s (ticket=" + ticket + ")");
        }

        byte[] data = downloadExportFile(fileToken);

        if (fileName.isEmpty()) {
            fileName = "export" + FeishuConfig.EXPORT_FILE_EXT_TO_SUFFIX.get(fileExt);
        }
        return new ExportDownload(data, fileName);
    }

    /** 对照 Go {@code ExportAndDownload} 的 {@code (data, fileName, error)}。 */
    public record ExportDownload(byte[] data, String fileName) {
    }

    // ──────────────────────────────────────────────────────────────────
    // Drive 文件下载
    // ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code DownloadDriveFile}：按 file token 下载云盘文件。
     * 用于 {@code obj_type="file"} 的 wiki 节点（用户上传的 PDF/Word/图片…）。
     */
    public byte[] downloadDriveFile(String fileToken) {
        return downloadRawBytes("/open-apis/drive/v1/files/" + fileToken + "/download");
    }

    /**
     * 对照 Go {@code downloadMediaFile}：下载<b>文档内嵌</b>媒体（File/Image 块引用的
     * 附件与图片）。
     *
     * <p>内嵌媒体的 token 空间与独立的 Drive 文件不同，必须走 {@code /medias/}
     * 而不是 {@code /files/}。</p>
     */
    public byte[] downloadMediaFile(String fileToken) {
        return downloadRawBytes("/open-apis/drive/v1/medias/"
                + FeishuSupport.pathEscape(fileToken) + "/download");
    }

    /** 对照 Go {@code downloadRawBytes}：带鉴权的 GET，返回原始响应体。 */
    public byte[] downloadRawBytes(String path) {
        String token = getTenantAccessToken();
        String url = baseUrl + path;
        RuntimeException lastErr = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt == 0) {
                log.info("[Feishu] download GET {}", path);
            } else {
                log.info("[Feishu] download GET {} (retry {}/{})", path, attempt, MAX_RETRIES);
            }

            ConnectorHttp.Response resp;
            try {
                resp = httpClient.exchange("GET", url, Map.of("Authorization", "Bearer " + token), null);
            } catch (RuntimeException e) {
                lastErr = e instanceof ConnectorException ce
                        ? ce : new ConnectorException("download request: " + e.getMessage(), e);
                if (attempt < MAX_RETRIES) {
                    Connector.sleep(backoffAt(attempt).toMillis());
                    continue;
                }
                throw lastErr;
            }

            if (resp.status() == 429) {
                Duration wait = parseRetryAfter(resp.header("Retry-After"), backoffAt(attempt));
                lastErr = new ConnectorException("download rate limited: status=429 body="
                        + FeishuSupport.truncate(resp.bodyAsString(), 500));
                if (attempt < MAX_RETRIES) {
                    Connector.sleep(wait.toMillis());
                    continue;
                }
                throw lastErr;
            }

            if (resp.status() >= 500 && resp.status() < 600) {
                lastErr = new ConnectorException("download server error: status=" + resp.status()
                        + " body=" + FeishuSupport.truncate(resp.bodyAsString(), 500));
                if (attempt < MAX_5XX_RETRIES) {
                    Connector.sleep(retry5xxDelay.toMillis());
                    continue;
                }
                throw lastErr;
            }

            if (resp.status() != 200) {
                log.error("[Feishu] download GET {} → status={} body={}", path, resp.status(),
                        FeishuSupport.truncate(resp.bodyAsString(), 500));
                throw new ConnectorException("download failed: status=" + resp.status()
                        + " body=" + resp.bodyAsString());
            }

            byte[] data = resp.body() == null ? new byte[0] : resp.body();
            // 对照 Go 的 io.LimitReader(maxFeishuDownloadBytes+1) 之后判超限；
            // Java 侧 body 已经在 exchange 里读完，只能读完再判（净效果一致）。
            if (data.length > MAX_DOWNLOAD_BYTES) {
                throw new ConnectorException(
                        "download exceeds max size (" + MAX_DOWNLOAD_BYTES + " bytes): " + path);
            }

            log.info("[Feishu] download GET {} → OK, {} bytes", path, data.length);
            return data;
        }

        throw lastErr != null ? lastErr : new ConnectorException("download failed: " + path);
    }

    // ──────────────────────────────────────────────────────────────────
    // Drive（云盘）文件列举
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code listDriveFiles} 的双返回值。 */
    public record DriveFilePage(List<DriveFile> files, String nextPageToken) {
    }

    /**
     * 对照 Go {@code listDriveFiles}：列一个云盘文件夹的直接子项（单页）。
     *
     * <p>{@code folderToken == ""} 直接拒绝：根文件夹不可分页、也不返回快捷方式
     * （飞书 API 限制），静默放行会丢内容且可能产出无界响应。</p>
     */
    public DriveFilePage listDriveFiles(String folderToken, String pageToken) {
        if (folderToken == null || folderToken.isEmpty()) {
            throw new ConnectorException("root folder not supported; specify a concrete folder_token "
                    + "(root folder is not paginated and does not return shortcuts)");
        }

        String path = "/open-apis/drive/v1/files?folder_token=" + FeishuSupport.queryEscape(folderToken);
        path += "&page_size=200"; // 上限
        path += "&order_by=EditedTime&direction=DESC";
        if (pageToken != null && !pageToken.isEmpty()) {
            path += "&page_token=" + FeishuSupport.queryEscape(pageToken);
        }

        DriveFileListResponse resp = doRequest("GET", path, null, DriveFileListResponse.class);
        if (resp == null || resp.code() != 0) {
            int code = resp == null ? -1 : resp.code();
            String msg = resp == null ? "" : resp.msg();
            throw new ConnectorException("list drive files error: code=" + code + " msg=" + msg);
        }

        List<DriveFile> files = resp.data() == null ? List.of() : nvl(resp.data().files());
        String next = resp.data() == null ? "" : nvl(resp.data().nextPageToken());
        log.info("[FeishuDrive] listDriveFiles: folder={} got {} files, has_more={}",
                folderToken, files.size(), resp.data() != null && resp.data().hasMore());
        return new DriveFilePage(files, next);
    }

    /**
     * 对照 Go {@code GetDriveFolderMeta}：取单个云盘文件夹的元数据（名字、所有者…）。
     * 用来解析根文件夹的人类可读名字——列表 API 只返回子项，不返回它自己。
     */
    public DriveFolderMetaResponse getDriveFolderMeta(String folderToken) {
        if (folderToken == null || folderToken.isEmpty()) {
            throw new ConnectorException("root folder not supported; specify a concrete folder_token");
        }
        String path = "/open-apis/drive/explorer/v2/folder/" + FeishuSupport.queryEscape(folderToken) + "/meta";
        DriveFolderMetaResponse resp = doRequest("GET", path, null, DriveFolderMetaResponse.class);
        if (resp == null || resp.code() != 0) {
            int code = resp == null ? -1 : resp.code();
            String msg = resp == null ? "" : resp.msg();
            throw new ConnectorException("get drive folder meta error: code=" + code + " msg=" + msg);
        }
        return resp;
    }

    /** 对照 Go {@code ListDriveFilesAllPages}：翻页取完一个文件夹的全部直接子项。 */
    public List<DriveFile> listDriveFilesAllPages(String folderToken) {
        List<DriveFile> all = new ArrayList<>();
        String pageToken = "";
        while (true) {
            DriveFilePage page = listDriveFiles(folderToken, pageToken);
            all.addAll(page.files());
            if (page.nextPageToken().isEmpty()) {
                break;
            }
            pageToken = page.nextPageToken();
        }
        return all;
    }

    /**
     * 对照 Go {@code ListDriveFilesRecursiveFrom}：深度优先走一个云盘文件夹子树，
     * 返回全部<b>非文件夹</b>文件。
     *
     * <ul>
     *   <li>{@code folder} → 递归（{@code visited} 纯属防御性环路保护；云盘文件夹没有
     *       快捷方式概念，理论上不成环）；</li>
     *   <li>{@code shortcut} → 展开成目标（target_type 不可能是 folder，已实测），
     *       把目标当普通文件纳入，不需要额外 API 调用（{@code shortcut_info} 就在列表响应里）；</li>
     *   <li>其它 → 直接收下。</li>
     * </ul>
     * <p>部分失败（某个子文件夹列举报错）收集进
     * {@link PartialDriveFileListException}，遍历<b>继续</b>——镜像 wiki 的同语义。</p>
     */
    public List<DriveFile> listDriveFilesRecursiveFrom(String folderToken) {
        Set<String> visited = new HashSet<>();
        List<DriveFile> all = new ArrayList<>();
        List<DriveFileListFailure> failures = new ArrayList<>();
        walkDriveFolder(this, folderToken, visited, all, failures);
        if (!failures.isEmpty()) {
            throw new PartialDriveFileListException(all, failures);
        }
        return all;
    }

    private static void walkDriveFolder(FeishuClient client, String folderToken, Set<String> visited,
                                        List<DriveFile> all, List<DriveFileListFailure> failures) {
        if (visited.contains(folderToken)) {
            return;
        }
        visited.add(folderToken);

        List<DriveFile> files;
        try {
            files = client.listDriveFilesAllPages(folderToken);
        } catch (RuntimeException e) {
            RuntimeException wrapped = new ConnectorException(
                    "list children of " + folderToken + ": " + e.getMessage(), e);
            failures.add(new DriveFileListFailure(folderToken, wrapped));
            log.warn("[FeishuDrive] partial drive file listing failure: folder={} err={}",
                    folderToken, e.getMessage());
            return;
        }

        for (DriveFile f : files) {
            switch (f.getType()) {
                case "folder" -> walkDriveFolder(client, f.getToken(), visited, all, failures);
                case "shortcut" -> {
                    DriveShortcutInfo info = f.getShortcutInfo();
                    if (info != null && info.targetToken() != null && !info.targetToken().isEmpty()) {
                        DriveFile expanded = new DriveFile();
                        expanded.setToken(info.targetToken());
                        expanded.setName(f.getName());
                        expanded.setType(info.targetType());
                        expanded.setParentToken(f.getParentToken());
                        expanded.setUrl(f.getUrl());
                        expanded.setCreatedTime(f.getCreatedTime());
                        expanded.setModifiedTime(f.getModifiedTime());
                        expanded.setOwnerId(f.getOwnerId());
                        all.add(expanded);
                    }
                }
                default -> all.add(f);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // docx 块（blocks.go L126-465）
    // ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code listDocumentBlocks}：把一篇 docx 的全部 block 拉成一个扁平的
     * <b>先序</b>数组。每页 500 个块。
     *
     * @param documentId docx 文档的 obj_token
     */
    public List<DocxBlocks.DocxBlock> listDocumentBlocks(String documentId) {
        List<DocxBlocks.DocxBlock> all = new ArrayList<>();
        String pageToken = "";
        while (true) {
            String path = "/open-apis/docx/v1/documents/"
                    + FeishuSupport.pathEscape(documentId)
                    + "/blocks?page_size=500&document_revision_id=-1";
            if (!pageToken.isEmpty()) {
                path += "&page_token=" + FeishuSupport.queryEscape(pageToken);
            }
            DocxBlocks.DocxBlocksResponse resp =
                    doRequest("GET", path, null, DocxBlocks.DocxBlocksResponse.class);
            if (resp == null || resp.code() != 0) {
                int code = resp == null ? -1 : resp.code();
                String msg = resp == null ? "" : resp.msg();
                throw new ConnectorException("list document blocks error: code=" + code + " msg=" + msg);
            }
            List<DocxBlocks.DocxBlock> items = resp.data() == null ? List.of() : nvl(resp.data().items());
            all.addAll(items);
            if (items.isEmpty()) {
                // 防御：畸形的一页（items 空但 has_more=true 且 page_token 非空）会一直
                // 循环到任务截止时间、白烧 API 配额。没有更多可收的了，停。
                break;
            }
            if (all.size() >= DocxBlocks.MAX_DOCUMENT_BLOCKS) {
                log.warn("[Feishu] document {} exceeded {} blocks; truncating",
                        documentId, DocxBlocks.MAX_DOCUMENT_BLOCKS);
                break;
            }
            if (resp.data() == null || !resp.data().hasMore()
                    || resp.data().pageToken() == null || resp.data().pageToken().isEmpty()) {
                break;
            }
            pageToken = resp.data().pageToken();
        }
        return all;
    }

    /** 对照 Go {@code readSheetRange} 的三返回值。 */
    public record SheetRange(List<List<String>> rows, boolean truncated) {
    }

    /**
     * 对照 Go {@code readSheetRange}：读内嵌电子表格单元格的值。
     *
     * <p>{@code embedToken} 是 sheet 块的 {@code sheet.token}，形如
     * {@code "spreadsheetToken_sheetId"}（在<b>最后一个</b>下划线处切分）。
     * 单元格一律字符串化（显示值）供 RAG 文本检索。行数按
     * {@link DocxBlocks#MAX_TABLE_ROWS} 截断，源行数更多时 {@code truncated=true}。</p>
     */
    public SheetRange readSheetRange(String embedToken) {
        // Go 用的是 strings.LastIndex（不是 Cut）：spreadsheet token 本身可能含下划线
        int idx = embedToken == null ? -1 : embedToken.lastIndexOf('_');
        if (idx < 0) {
            throw new ConnectorException("invalid sheet embed token: \"" + embedToken + "\"");
        }
        String spreadsheetToken = embedToken.substring(0, idx);
        String sheetId = embedToken.substring(idx + 1);

        String path = "/open-apis/sheets/v2/spreadsheets/"
                + FeishuSupport.pathEscape(spreadsheetToken)
                + "/values/"
                + FeishuSupport.pathEscape(sheetId)
                + "?valueRenderOption=ToString";
        DocxBlocks.SheetValuesResponse resp =
                doRequest("GET", path, null, DocxBlocks.SheetValuesResponse.class);
        if (resp == null || resp.code() != 0) {
            int code = resp == null ? -1 : resp.code();
            String msg = resp == null ? "" : resp.msg();
            throw new ConnectorException("read sheet range error: code=" + code + " msg=" + msg);
        }
        List<List<Object>> raw = resp.data() == null || resp.data().valueRange() == null
                ? null : resp.data().valueRange().values();
        DocxBlocks.Capped<List<Object>> capped = DocxBlocks.capRows(raw == null ? List.of() : raw);
        return new SheetRange(DocxBlocks.stringifyMatrix(capped.rows()), capped.truncated());
    }

    /** 对照 Go {@code readBitableRecords} 的三返回值。 */
    public record BitableTable(List<List<String>> rows, boolean truncated) {
    }

    /**
     * 对照 Go {@code readBitableRecords}：把一个内嵌多维表格读成表格——
     * 一行字段名表头 + 每条记录一行。
     *
     * <p>{@code embedToken} 是 bitable 块的 {@code bitable.token}，形如
     * {@code "appToken_tableId"}（在<b>最后一个</b>下划线处切分）。
     * 记录行按 {@link DocxBlocks#MAX_TABLE_ROWS} 截断。</p>
     */
    public BitableTable readBitableRecords(String embedToken) {
        int idx = embedToken == null ? -1 : embedToken.lastIndexOf('_');
        if (idx < 0) {
            throw new ConnectorException("invalid bitable embed token: \"" + embedToken + "\"");
        }
        String appToken = embedToken.substring(0, idx);
        String tableId = embedToken.substring(idx + 1);

        List<DocxBlocks.BitableColumn> cols = new ArrayList<>();
        String baseFPath = "/open-apis/bitable/v1/apps/"
                + FeishuSupport.pathEscape(appToken)
                + "/tables/"
                + FeishuSupport.pathEscape(tableId)
                + "/fields?page_size=" + DocxBlocks.MAX_BITABLE_FIELD_PAGE_SIZE;
        String fieldPageToken = "";
        while (true) {
            String fpath = baseFPath;
            if (!fieldPageToken.isEmpty()) {
                fpath += "&page_token=" + FeishuSupport.queryEscape(fieldPageToken);
            }
            DocxBlocks.BitableFieldsResponse fieldsResp =
                    doRequest("GET", fpath, null, DocxBlocks.BitableFieldsResponse.class);
            if (fieldsResp == null || fieldsResp.code() != 0) {
                int code = fieldsResp == null ? -1 : fieldsResp.code();
                String msg = fieldsResp == null ? "" : fieldsResp.msg();
                throw new ConnectorException(
                        "read bitable fields error: code=" + code + " msg=" + msg);
            }
            List<DocxBlocks.BitableField> items =
                    fieldsResp.data() == null ? List.of() : nvl(fieldsResp.data().items());
            for (DocxBlocks.BitableField f : items) {
                String formatter = "";
                if (f.property() != null && f.property().dateFormatter() != null) {
                    formatter = f.property().dateFormatter();
                }
                cols.add(new DocxBlocks.BitableColumn(nvl(f.fieldName()), f.type(), formatter));
            }
            if (items.isEmpty()) {
                // 防御：空页 + has_more=true 会死循环（这个循环自身没有大小上限）。
                break;
            }
            if (fieldsResp.data() == null || !fieldsResp.data().hasMore()
                    || fieldsResp.data().pageToken() == null
                    || fieldsResp.data().pageToken().isEmpty()) {
                break;
            }
            fieldPageToken = fieldsResp.data().pageToken();
        }

        List<String> header = new ArrayList<>(cols.size());
        for (DocxBlocks.BitableColumn col : cols) {
            header.add(col.name());
        }
        ZoneId loc = tz();

        List<List<String>> dataRows = new ArrayList<>();
        boolean truncated = false;
        // 用"查询记录"接口（POST .../records/search）：旧的 GET .../records 官方已标废弃
        // （"已不推荐使用，可使用[查询记录]替代"）。空 body 查的是表格默认视图，
        // 靠 page_token 翻页到底；所以带筛选的默认视图会漏掉被筛掉的记录——
        // 对 RAG 可接受，但不是字面意义上的"全部记录"。
        String baseRPath = "/open-apis/bitable/v1/apps/"
                + FeishuSupport.pathEscape(appToken)
                + "/tables/"
                + FeishuSupport.pathEscape(tableId)
                + "/records/search?page_size=500";
        String pageToken = "";
        while (true) {
            String rpath = baseRPath;
            if (!pageToken.isEmpty()) {
                rpath += "&page_token=" + FeishuSupport.queryEscape(pageToken);
            }
            DocxBlocks.BitableRecordsResponse rec =
                    doRequest("POST", rpath, new LinkedHashMap<String, Object>(),
                            DocxBlocks.BitableRecordsResponse.class);
            if (rec == null || rec.code() != 0) {
                int code = rec == null ? -1 : rec.code();
                String msg = rec == null ? "" : rec.msg();
                throw new ConnectorException("read bitable records error: code=" + code + " msg=" + msg);
            }
            for (DocxBlocks.BitableRecord item :
                    rec.data() == null ? List.<DocxBlocks.BitableRecord>of() : nvl(rec.data().items())) {
                List<String> row = new ArrayList<>(cols.size());
                Map<String, Object> fields = item.fields() == null ? Map.of() : item.fields();
                for (DocxBlocks.BitableColumn col : cols) {
                    row.add(DocxBlocks.bitableFieldCell(fields.get(col.name()), col, loc));
                }
                dataRows.add(row);
            }
            if (rec.data() == null || nvl(rec.data().items()).isEmpty()) {
                // 防御：空页 + has_more=true 永远不会推进 dataRows，下面的
                // maxTableRows 上限也就永不触发——必须在这里停，否则会循环到任务截止。
                break;
            }
            if (dataRows.size() >= DocxBlocks.MAX_TABLE_ROWS) {
                if (dataRows.size() > DocxBlocks.MAX_TABLE_ROWS || rec.data().hasMore()) {
                    truncated = true;
                }
                break;
            }
            if (!rec.data().hasMore() || rec.data().pageToken() == null
                    || rec.data().pageToken().isEmpty()) {
                break;
            }
            pageToken = rec.data().pageToken();
        }
        DocxBlocks.Capped<List<String>> capped = DocxBlocks.capRows(dataRows);
        truncated = truncated || capped.truncated();
        List<List<String>> rows = new ArrayList<>();
        rows.add(header);
        rows.addAll(capped.rows());
        return new BitableTable(rows, truncated);
    }

    // ──────────────────────────────────────────────────────────────────
    // 小工具
    // ──────────────────────────────────────────────────────────────────

    /** Go 的 nil slice 在 Java 是 null：循环/追加前统一归一。 */
    static <T> List<T> nvl(List<T> list) {
        return list == null ? List.of() : list;
    }

    static String nvl(String s) {
        return s == null ? "" : s;
    }
}
