package com.ragagent.datasource.connector.notion;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorHttp;

/**
 * Notion API 客户端：限流 + 重试 + 分页（对照 Go {@code notionClient}，
 * client.go 全文）。
 *
 * <h2>限流器（对照 {@code rate.NewLimiter(rate.Limit(3), 3)}）</h2>
 * <p>Go 用 {@code golang.org/x/time/rate}：<b>3 req/s、突发 3</b>。Java 侧不引新依赖，
 * 自写一个等价的令牌桶（{@link RateLimiter}）。默认值仍是
 * {@code perSecond(3, 3)}——**别因为测试方便就改默认值**；
 * 测试通过 {@link #forTesting} 注入 {@link RateLimiter#unlimited()}，
 * 否则每个 stub 调用要等 1/3 秒，测试会变成慢测。</p>
 *
 * <h2>重试矩阵（对照 {@code doRequest}）</h2>
 * <ul>
 *   <li>2xx → 直接回响应体；</li>
 *   <li>401 / 403 → {@link ConnectorException.InvalidCredentials}
 *       （细节是**响应体原文**）；</li>
 *   <li>404 → {@link ConnectorException.ResourceNotFound}
 *       （细节是**路径**，不是响应体）；</li>
 *   <li>429 → 读 {@code Retry-After}（{@code strconv.ParseFloat}，{@code >0} 才用，
 *       否则 1s），重试；小数秒是允许的（实测 {@code 0.25} → 等 250ms）；</li>
 *   <li>&gt;= 500 → {@code 1<<attempt} 秒退避（1s/2s/4s）后重试；</li>
 *   <li>其它 → {@code "unexpected status %d: <响应体>"}，**不重试**；</li>
 *   <li>重试耗尽 → {@link ConnectorException.FetchFailed}
 *       （{@code "failed to fetch items from source: <最后一次的错误>"}）。</li>
 * </ul>
 * <p>实测（probe）：500 共 4 次请求、耗时 7–8s；429 带 {@code Retry-After: 0.25} →
 * 3 次请求、约 500ms；{@code Retry-After} 非法 → 退化成 1s。<b>Java 侧把退避与
 * 休眠做成可注入的</b>（{@link Backoff} / {@link Sleeper}），测试里设成 0，
 * 于是没有一条用例真的在等墙钟（约定 §7.5 第 7 条那类坑）。</p>
 *
 * <h2>ctx 的处置</h2>
 * <p>Go 的 {@code sleepWithContext(ctx, d)} 用 ctx 做可取消的休眠 → Java 侧走
 * {@link Connector#sleep(long)}（线程中断 = 取消，中断即抛 {@code ConnectorException}）。
 * {@code limiter.Wait(ctx)} 同理。</p>
 *
 * <h2>与 Go 的一处已知差异：响应体缓存</h2>
 * <p>Go 每次重试都重建 {@code req.Body}（{@code io.NopCloser(bytes.NewReader(...))}），
 * Java 侧用 {@code BodyPublishers.ofByteArray} 的同一个字节数组——语义相同
 * （两者都可重复发送），但 Java 不重建请求对象，因为 JDK 的
 * {@code byte[]} publisher 支持多次订阅。</p>
 */
public final class NotionClient {

    /** 对照 Go {@code datasource.NewConnectorHTTPClient(30 * time.Second)}。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String token;
    private final String baseUrl;
    private final ConnectorHttp.Client httpClient;
    private final RateLimiter limiter;
    private final Backoff backoff;
    private final Sleeper sleeper;

    /**
     * 单次下载上限，默认 {@link NotionConstants#MAX_DOWNLOAD_SIZE}（100MB）。
     *
     * <p>做成实例字段（而不是直接用常量）只为给测试一个缝：真的往内存里灌
     * 100MB+1 字节会把测试 JVM 的堆打满。默认值与 Go 的
     * {@code maxDownloadSize} 一字不差。</p>
     */
    private int maxDownloadSize = NotionConstants.MAX_DOWNLOAD_SIZE;

    /** 对照 Go {@code newClient(token, baseURL)}（含 base_url 的 SSRF 校验）。 */
    public NotionClient(String token, String baseUrl) {
        this(token, baseUrl, RateLimiter.perSecond(3, 3), Backoff.exponentialSeconds(),
                Connector::sleep,
                ConnectorHttp.newConnectorHttpClient(REQUEST_TIMEOUT));
    }

    /**
     * 测试用构造器：把限流器、退避、休眠、HTTP 客户端全部换掉。
     *
     * <p>刻意**不做** SSRF 校验——测试要能构造指向 127.0.0.1 的客户端，
     * 而放行 loopback 是测试自己 {@code SsrfGuard.reloadWhitelist} 的事
     * （对应 Go 的 {@code t.Setenv("SSRF_WHITELIST", …)}）。</p>
     */
    public NotionClient(String token, String baseUrl, RateLimiter limiter, Backoff backoff,
                        Sleeper sleeper, ConnectorHttp.Client httpClient) {
        this.token = token;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.limiter = limiter;
        this.backoff = backoff;
        this.sleeper = sleeper;
        this.httpClient = httpClient;
    }

    /** 对照 Go {@code newClient}：空 base_url 回落默认值，再校验（SSRF）。 */
    public static NotionClient create(String token, String baseUrl) {
        String resolved = (baseUrl == null || baseUrl.isEmpty())
                ? NotionConstants.DEFAULT_BASE_URL
                : baseUrl;
        ConnectorHttp.validateConnectorBaseUrl(resolved);
        return new NotionClient(token, resolved);
    }

    /** 测试/连接器共用的工厂：允许替换限流与退避，但仍做 base_url 校验。 */
    public static NotionClient forTesting(String token, String baseUrl, RateLimiter limiter,
                                          Backoff backoff, Sleeper sleeper) {
        String resolved = (baseUrl == null || baseUrl.isEmpty())
                ? NotionConstants.DEFAULT_BASE_URL
                : baseUrl;
        ConnectorHttp.validateConnectorBaseUrl(resolved);
        return new NotionClient(token, resolved, limiter, backoff, sleeper,
                ConnectorHttp.newConnectorHttpClient(REQUEST_TIMEOUT));
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** 测试缝：把单次下载上限调小（默认值仍是 Go 的 100MB）。 */
    void setMaxDownloadSizeForTest(int bytes) {
        this.maxDownloadSize = bytes;
    }

    // ──────────────────────────────────────────────────────────────────────
    // doRequest
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code (*notionClient).doRequest}：带鉴权、限流、重试的请求。
     *
     * @param method HTTP 方法（{@code "GET"} / {@code "POST"}）
     * @param path   以 {@code /} 开头的路径（可含查询串）
     * @param body   请求体对象（序列化成 JSON）；{@code null} 表示无体
     */
    public byte[] doRequest(String method, String path, Object body) {
        limiter.waitForToken();

        byte[] bodyBytes = null;
        if (body != null) {
            try {
                bodyBytes = NotionJson.MAPPER.writeValueAsBytes(body);
            } catch (Exception e) {
                throw new ConnectorException("marshal request body: " + e.getMessage(), e);
            }
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Notion-Version", NotionConstants.API_VERSION);
        headers.put("Content-Type", "application/json");

        RuntimeException lastErr = null;
        for (int attempt = 0; attempt <= NotionConstants.MAX_RETRIES; attempt++) {
            ConnectorHttp.Response resp;
            try {
                resp = httpClient.exchange(method, baseUrl + path, headers, bodyBytes);
            } catch (ConnectorException e) {
                lastErr = e;
                if (attempt < NotionConstants.MAX_RETRIES) {
                    sleeper.sleep(backoff.delayMillis(attempt));
                    continue;
                }
                break;
            }

            int status = resp.status();
            if (status >= 200 && status < 300) {
                return resp.body();
            }
            if (status == 401 || status == 403) {
                throw new ConnectorException.InvalidCredentials(resp.bodyAsString());
            }
            if (status == 404) {
                throw new ConnectorException.ResourceNotFound(path);
            }
            if (status == 429) {
                long waitMillis = retryAfterMillis(resp.header("Retry-After"));
                lastErr = new ConnectorException("rate limited: " + resp.bodyAsString());
                if (attempt < NotionConstants.MAX_RETRIES) {
                    sleeper.sleep(waitMillis);
                    continue;
                }
            } else if (status >= 500) {
                lastErr = new ConnectorException(
                        "server error " + status + ": " + resp.bodyAsString());
                if (attempt < NotionConstants.MAX_RETRIES) {
                    sleeper.sleep(backoff.delayMillis(attempt));
                    continue;
                }
            } else {
                throw new ConnectorException(
                        "unexpected status " + status + ": " + resp.bodyAsString());
            }
        }

        // Go 的注释到不了这里（每个分支要么返回、要么设置 lastErr）。
        // Go 的兜底是**裸** ErrFetchFailed；Java 侧保留该文本并附 cause。
        String detail = lastErr == null
                ? "failed to fetch items from source"
                : lastErr.getMessage();
        throw new ConnectorException.FetchFailed(detail, lastErr);
    }

    /**
     * 对照 Go 的 {@code Retry-After} 解析：
     * <pre>
     *   retryAfter := resp.Header.Get("Retry-After")
     *   wait := 1 * time.Second
     *   if secs, err := strconv.ParseFloat(retryAfter, 64); err == nil &amp;&amp; secs &gt; 0 {
     *       wait = time.Duration(secs * float64(time.Second))
     *   }
     * </pre>
     *
     * <p>两个细节照抄：① 解析失败的**空**头（字段缺席）也走 1s；
     * ② {@code strconv.ParseFloat} **不接受**首尾空白，而 Java 的
     * {@code Double.parseDouble} 接受——故这里显式拒绝含空白的串。</p>
     */
    static long retryAfterMillis(String raw) {
        String value = raw == null ? "" : raw;
        if (value.isEmpty() || !value.equals(value.trim())) {
            return 1000L;
        }
        double seconds;
        try {
            seconds = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 1000L;
        }
        if (Double.isNaN(seconds) || seconds <= 0) {
            return 1000L;
        }
        return (long) (seconds * 1000.0d);
    }

    // ──────────────────────────────────────────────────────────────────────
    // API 方法
    // ──────────────────────────────────────────────────────────────────────

    /** 对照 Go {@code Ping}：{@code GET /v1/users/me}。 */
    public void ping() {
        doRequest("GET", "/v1/users/me", null);
    }

    /** 对照 Go {@code SearchPages}：{@code POST /v1/search}（分页）。 */
    public List<NotionPage> searchPages() {
        return paginatePages("POST", "/v1/search");
    }

    /** 对照 Go {@code GetPage}：{@code GET /v1/pages/{id}}，并回填 Title。 */
    public NotionPage getPage(String pageId) {
        byte[] respBody = doRequest("GET", "/v1/pages/" + pageId, null);
        NotionPage page = unmarshal(respBody, NotionPage.class, "unmarshal page");
        page.title = NotionProperties.extractTitle(page);
        return page;
    }

    /** 对照 Go {@code GetDatabaseInfo}：{@code GET /v1/databases/{id}}。 */
    public NotionDatabaseInfo getDatabaseInfo(String dbId) {
        byte[] respBody = doRequest("GET", "/v1/databases/" + dbId, null);
        NotionPage db = unmarshal(respBody, NotionPage.class, "unmarshal database");
        db.title = NotionProperties.extractTitle(db);

        String dataSourceId = "";
        JsonNode node = parseTree(respBody);
        JsonNode dataSources = node == null ? null : node.get("data_sources");
        if (dataSources != null && dataSources.isArray() && !dataSources.isEmpty()) {
            JsonNode first = dataSources.get(0);
            JsonNode idNode = first == null ? null : first.get("id");
            if (idNode != null && idNode.isTextual()) {
                dataSourceId = idNode.textValue();
            }
        }
        return new NotionDatabaseInfo(db, dataSourceId);
    }

    /** 对照 Go {@code GetDataSourceInfo}：{@code GET /v1/data_sources/{id}}。 */
    public NotionPage getDataSourceInfo(String dataSourceId) {
        byte[] respBody = doRequest("GET", "/v1/data_sources/" + dataSourceId, null);
        NotionPage ds = unmarshal(respBody, NotionPage.class, "unmarshal data_source");
        ds.title = NotionProperties.extractTitle(ds);
        return ds;
    }

    /**
     * 对照 Go {@code GetBlockChildrenFlat}：只取直接子块（不递归）。
     *
     * <p>注意它**没有** {@code maxBlocksPerPage} 上限——那是
     * {@code getBlockChildrenRecursive} 才有的判断。两条循环的终止条件不同，
     * 别合并。</p>
     */
    public List<NotionBlock> getBlockChildrenFlat(String blockId) {
        List<NotionBlock> allBlocks = new ArrayList<>();
        String startCursor = "";
        while (true) {
            String path = "/v1/blocks/" + blockId + "/children";
            if (!startCursor.isEmpty()) {
                path += "?start_cursor=" + startCursor;
            }
            byte[] respBody;
            try {
                respBody = doRequest("GET", path, null);
            } catch (ConnectorException e) {
                throw new ConnectorException(
                        "get block children for " + blockId + ": " + e.getMessage(), e);
            }
            NotionPaginatedResponse resp = unmarshalPage(respBody);
            allBlocks.addAll(parseBlocks(resp, "unmarshal blocks"));
            if (!resp.hasMore || resp.nextCursor().isEmpty()) {
                break;
            }
            startCursor = resp.nextCursor();
        }
        return allBlocks;
    }

    /** 对照 Go {@code GetBlockChildrenAll}。 */
    public List<NotionBlock> getBlockChildrenAll(String blockId) {
        return getBlockChildrenRecursive(blockId, 0);
    }

    /**
     * 对照 Go {@code getBlockChildrenRecursive}。
     *
     * <p>三个上限/跳过规则逐条照抄：</p>
     * <ol>
     *   <li>累计块数 {@code >= maxBlocksPerPage(1000)} 立即停止翻页
     *       （实测：每页 400 块时请求 3 次、共 1200 块）；</li>
     *   <li>{@code depth >= maxBlockDepth(5)} 时**不再往子层递归**
     *       （实测：链式嵌套请求 l0…l5 共 6 次，Children 层数 5）；</li>
     *   <li>{@code child_page}/{@code child_database}/{@code unsupported}/
     *       {@code template}/{@code breadcrumb}/{@code table_of_contents}
     *       即使 {@code has_children=true} 也不递归（由 connector 层另行处理或忽略）。</li>
     * </ol>
     */
    private List<NotionBlock> getBlockChildrenRecursive(String blockId, int depth) {
        List<NotionBlock> allBlocks = new ArrayList<>();
        String startCursor = "";
        while (true) {
            String path = "/v1/blocks/" + blockId + "/children";
            if (!startCursor.isEmpty()) {
                path += "?start_cursor=" + startCursor;
            }
            byte[] respBody;
            try {
                respBody = doRequest("GET", path, null);
            } catch (ConnectorException e) {
                throw new ConnectorException(
                        "get block children for " + blockId + ": " + e.getMessage(), e);
            }
            NotionPaginatedResponse resp = unmarshalPage(respBody);
            allBlocks.addAll(parseBlocks(resp, "unmarshal blocks"));
            if (allBlocks.size() >= NotionConstants.MAX_BLOCKS_PER_PAGE
                    || !resp.hasMore || resp.nextCursor().isEmpty()) {
                break;
            }
            startCursor = resp.nextCursor();
        }

        if (depth >= NotionConstants.MAX_BLOCK_DEPTH) {
            return allBlocks;
        }

        for (NotionBlock block : allBlocks) {
            if (!block.hasChildren) {
                continue;
            }
            switch (block.type()) {
                case "child_page":
                case "child_database":
                case "unsupported":
                case "template":
                case "breadcrumb":
                case "table_of_contents":
                    continue;
                default:
                    break;
            }
            try {
                block.children = getBlockChildrenRecursive(block.id(), depth + 1);
            } catch (ConnectorException e) {
                // Go: logger.Warnf(... "failed to get children for block %s (depth %d)")
                continue;
            }
        }
        return allBlocks;
    }

    /**
     * 对照 Go {@code QueryDatabaseAll}：先按 data_source ID 直查，
     * 404 之类的失败再回落成 database 容器 ID → 取 {@code data_sources[0].id} 重查。
     *
     * <p>注意 Go 的回落是**任何**错误都触发（401 也会），不只是 404——照抄。</p>
     */
    public List<NotionPage> queryDatabaseAll(String id) {
        ConnectorException firstError;
        try {
            return paginatePages("POST", "/v1/data_sources/" + id + "/query");
        } catch (ConnectorException e) {
            firstError = e;
        }

        NotionDatabaseInfo info;
        try {
            info = getDatabaseInfo(id);
        } catch (ConnectorException dbErr) {
            throw new ConnectorException("query database " + id + ": not a data_source ("
                    + firstError.getMessage() + ") and not a database (" + dbErr.getMessage() + ")",
                    dbErr);
        }
        if (info.dataSourceId.isEmpty()) {
            throw new ConnectorException("database " + id + " has no data sources");
        }
        return paginatePages("POST", "/v1/data_sources/" + info.dataSourceId + "/query");
    }

    /**
     * 对照 Go {@code ResolveBlock}：{@code GET /v1/blocks/{id}}，用于把
     * {@code file_upload} 换成带临时下载地址的形态。
     */
    public NotionBlock resolveBlock(String blockId) {
        byte[] respBody = doRequest("GET", "/v1/blocks/" + blockId, null);
        return unmarshal(respBody, NotionBlock.class, "unmarshal block");
    }

    /**
     * 对照 Go {@code DownloadFile}。
     *
     * <p>不走限流器（它不是 Notion API 调用）；SSRF 用与出站客户端同一个
     * {@link SsrfGuard}（Java 侧是进程级静态引用，见 {@link ConnectorHttp#setSsrfGuard}）。</p>
     *
     * <p><b>已知差异</b>：Go 用 {@code io.LimitReader(resp.Body, maxDownloadSize+1)}
     * <b>流式</b>截断，永远不会把超过 100MB+1 的字节读进内存；Java 的
     * {@link ConnectorHttp.Response} 已经把响应体读完了，只能在**读完之后**判上限。
     * 结果是">=100MB 的附件会先占用一次等量内存"。这是框架层的既有形态
     * （{@code Response.body()} 是 {@code byte[]}），要真正流式化得改
     * {@code ConnectorHttp}——不在本模块的改动范围内。</p>
     */
    public byte[] downloadFile(String fileUrl) {
        try {
            ConnectorHttp.ssrfGuard().validateURLForSSRF(fileUrl);
        } catch (SsrfGuard.SsrfException e) {
            throw new ConnectorException("attachment URL rejected: " + e.getMessage(), e);
        }

        RuntimeException lastErr = null;
        for (int attempt = 0; attempt <= NotionConstants.MAX_RETRIES; attempt++) {
            ConnectorHttp.Response resp;
            try {
                resp = httpClient.get(fileUrl, null);
            } catch (ConnectorException e) {
                lastErr = e;
                if (attempt < NotionConstants.MAX_RETRIES) {
                    sleeper.sleep(backoff.delayMillis(attempt));
                    continue;
                }
                break;
            }

            if (resp.status() != 200) {
                lastErr = new ConnectorException(
                        "download failed with status " + resp.status());
                if (resp.status() >= 500 && attempt < NotionConstants.MAX_RETRIES) {
                    sleeper.sleep(backoff.delayMillis(attempt));
                    continue;
                }
                break;
            }

            byte[] data = resp.body() == null ? new byte[0] : resp.body();
            if (data.length > maxDownloadSize) {
                // 文案里用的是 Go 的**常量**（"100 MB"），不是被测试调小后的值
                throw new ConnectorException("file exceeds maximum download size ("
                        + (NotionConstants.MAX_DOWNLOAD_SIZE / (1024 * 1024)) + " MB)");
            }
            return data;
        }

        String detail = lastErr == null ? "unknown error" : lastErr.getMessage();
        throw new ConnectorException("download file: " + detail, lastErr);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 分页
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code paginatePages}：POST 走请求体（{@code page_size=100} +
     * 可选 {@code start_cursor}），GET 走查询串
     * （{@code ?start_cursor=X&page_size=100}，**不做 URL 编码**——Go 也是原样拼）。
     *
     * <p>实测到的请求形态：</p>
     * <pre>
     *   POST /v1/search  body={"page_size":100}
     *   POST /v1/search  body={"page_size":100,"start_cursor":"CUR1"}
     *   GET  /v1/blocks/gp/children
     *   GET  /v1/blocks/gp/children?start_cursor=G2
     * </pre>
     */
    public List<NotionPage> paginatePages(String method, String path) {
        List<NotionPage> allPages = new ArrayList<>();
        String startCursor = "";

        while (true) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("page_size", 100);
            if (!startCursor.isEmpty()) {
                body.put("start_cursor", startCursor);
            }

            byte[] respBody;
            if ("POST".equals(method)) {
                respBody = paginateRequest(method, path, body, path);
            } else {
                String p = path;
                if (!startCursor.isEmpty()) {
                    p += "?start_cursor=" + startCursor + "&page_size=100";
                }
                respBody = paginateRequest(method, p, null, path);
            }
            NotionPaginatedResponse resp = unmarshalPage(respBody);
            List<NotionPage> pages = parsePages(resp);
            for (NotionPage page : pages) {
                page.title = NotionProperties.extractTitle(page);
            }
            allPages.addAll(pages);

            if (!resp.hasMore || resp.nextCursor().isEmpty()) {
                break;
            }
            startCursor = resp.nextCursor();
        }
        return allPages;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 解析工具
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 对照 Go 的 {@code return nil, fmt.Errorf("paginate %s: %w", path, err)}：
     * 分页路径上的**每一个** doRequest 错误都带 {@code "paginate <path>: "} 前缀
     * （注意前缀里的是原始 path，GET 加过查询串的那个不参与）。
     */
    private byte[] paginateRequest(String method, String requestPath, Object body, String path) {
        try {
            return doRequest(method, requestPath, body);
        } catch (ConnectorException e) {
            throw new ConnectorException("paginate " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * 对照 Go：{@code json.Unmarshal(respBody, &resp)} 失败 →
     * {@code "unmarshal paginated response: <err>"}。
     */
    private NotionPaginatedResponse unmarshalPage(byte[] respBody) {
        return unmarshal(respBody, NotionPaginatedResponse.class, "unmarshal paginated response");
    }

    /**
     * 对照 Go 的 {@code var pages []notionPage; json.Unmarshal(resp.Results, &pages)}。
     *
     * <p>{@code results} **缺席**（Go 的 RawMessage 为 nil）→ 解不出来；
     * 字面量 {@code null} → 空列表。见 {@link NotionPaginatedResponse}。</p>
     */
    private List<NotionPage> parsePages(NotionPaginatedResponse resp) {
        if (resp.results == null) {
            throw new ConnectorException(
                    "unmarshal page results: unexpected end of JSON input");
        }
        if (resp.results.isNull()) {
            return new ArrayList<>();
        }
        if (!resp.results.isArray()) {
            throw new ConnectorException("unmarshal page results: cannot unmarshal non-array");
        }
        List<NotionPage> out = new ArrayList<>();
        for (JsonNode node : resp.results) {
            out.add(NotionJson.MAPPER.convertValue(node, NotionPage.class));
        }
        return out;
    }

    /**
     * 对照 Go 的 {@code json.Unmarshal(resp.Results, &blocks)}
     * （{@code notionBlock} 的自定义反序列化在 {@link NotionBlock.Deserializer}）。
     */
    private List<NotionBlock> parseBlocks(NotionPaginatedResponse resp, String errorPrefix) {
        if (resp.results == null) {
            throw new ConnectorException(errorPrefix + ": unexpected end of JSON input");
        }
        if (resp.results.isNull()) {
            return new ArrayList<>();
        }
        if (!resp.results.isArray()) {
            throw new ConnectorException(errorPrefix + ": cannot unmarshal non-array");
        }
        List<NotionBlock> out = new ArrayList<>();
        for (JsonNode node : resp.results) {
            out.add(NotionJson.MAPPER.convertValue(node, NotionBlock.class));
        }
        return out;
    }

    private <T> T unmarshal(byte[] body, Class<T> type, String errorPrefix) {
        try {
            return NotionJson.MAPPER.readValue(body == null ? new byte[0] : body, type);
        } catch (Exception e) {
            throw new ConnectorException(errorPrefix + ": " + e.getMessage(), e);
        }
    }

    private static JsonNode parseTree(byte[] body) {
        try {
            return NotionJson.MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 限流 / 退避 / 休眠
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 令牌桶限流器（对照 Go 的 {@code rate.NewLimiter(rate.Limit(3), 3)}）。
     *
     * <p>默认 {@code perSecond(3, 3)}：3 req/s、突发 3——前 3 次调用立即通过，
     * 之后每 1/3 秒放行一个。<b>测试注入 {@link #unlimited()}</b>，
     * 否则每个 stub 调用都要等 1/3 秒。</p>
     *
     * <p>{@code Wait} 的休眠走注入的 {@link Sleeper}，所以测试既能"不等"，
     * 也能用一个记账的 Sleeper 断言"确实等过 333ms"。</p>
     */
    public static final class RateLimiter {

        private final double limitPerSecond;
        private final double burst;
        private final Sleeper sleeper;
        private final java.util.function.LongSupplier nanoTime;
        private double tokens;
        private long lastNanos;

        private RateLimiter(double limitPerSecond, double burst, Sleeper sleeper,
                            java.util.function.LongSupplier nanoTime) {
            this.limitPerSecond = limitPerSecond;
            this.burst = burst;
            this.sleeper = sleeper;
            this.nanoTime = nanoTime;
        }

        /** 对照 Go {@code rate.NewLimiter(rate.Limit(limit), burst)}。 */
        public static RateLimiter perSecond(double limit, double burst) {
            return new RateLimiter(limit, burst, Connector::sleep, System::nanoTime);
        }

        /** 同样语义，但用注入的 Sleeper（测试用）。 */
        public static RateLimiter perSecond(double limit, double burst, Sleeper sleeper) {
            return new RateLimiter(limit, burst, sleeper, System::nanoTime);
        }

        /**
         * 注入休眠与时钟（测试用）：把时钟也换掉，测试才能<b>确定性地</b>断言
         * "第 4 个请求等了 334ms"，而不是靠真实墙钟（约定 §7.5 第 7 条）。
         */
        public static RateLimiter perSecond(double limit, double burst, Sleeper sleeper,
                                            java.util.function.LongSupplier nanoTime) {
            return new RateLimiter(limit, burst, sleeper, nanoTime);
        }

        /**
         * 不限流（测试用）。<b>默认值是 3/3，别改</b>——那会改掉线上行为；
         * 放开速率是**测试**的责任。
         */
        public static RateLimiter unlimited() {
            return new RateLimiter(0, 0, millis -> { }, System::nanoTime);
        }

        /** 对照 Go {@code limiter.Wait(ctx)}。 */
        public synchronized void waitForToken() {
            if (!(limitPerSecond > 0) || Double.isInfinite(limitPerSecond)) {
                return;
            }
            long now = nanoTime.getAsLong();
            if (lastNanos == 0) {
                lastNanos = now;
                tokens = burst;
            }
            tokens = Math.min(burst, tokens + (now - lastNanos) / 1e9d * limitPerSecond);
            lastNanos = now;

            while (tokens < 1.0d) {
                double needSeconds = (1.0d - tokens) / limitPerSecond;
                long sleepMillis = Math.max(1L, (long) Math.ceil(needSeconds * 1000.0d));
                sleeper.sleep(sleepMillis);
                now = nanoTime.getAsLong();
                tokens = Math.min(burst, tokens + (now - lastNanos) / 1e9d * limitPerSecond);
                lastNanos = now;
            }
            tokens -= 1.0d;
        }
    }

    /**
     * 重试退避（对照 Go 的 {@code time.Duration(1&lt;&lt;attempt) * time.Second}：
     * 1s / 2s / 4s，attempt 从 0 起）。
     *
     * <p>做成可注入的接口是为了让测试**不要真的等**（约定 §7.5 第 7 条：
     * 禁止用墙钟造时间的测试）。</p>
     */
    @FunctionalInterface
    public interface Backoff {
        long delayMillis(int attempt);

        static Backoff exponentialSeconds() {
            return attempt -> (1L << attempt) * 1000L;
        }

        static Backoff none() {
            return attempt -> 0L;
        }
    }

    /** 休眠端口（默认 {@link Connector#sleep(long)}：被中断即抛 {@code ConnectorException}）。 */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis);
    }
}
