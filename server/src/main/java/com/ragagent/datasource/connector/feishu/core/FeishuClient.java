package com.ragagent.datasource.connector.feishu.core;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorHttp;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DocRawContentResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveFile;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveFolderMetaResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.ExportTaskCreateResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.ExportTaskStatusResponse;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNode;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiSpace;

/**
 * 飞书 Open Platform API 客户端。
 *
 * <h2>三次重试策略（wiki 与云盘共用）</h2>
 * <p>飞书的 drive export / wiki 接口限流很凶，一次上千文档的同步要发几万次调用；
 * 没有退避时<b>一轮 429 就会静默失败一大片文档</b>。策略（{@link #doRequest} 与
 * {@link #downloadRawBytes} 共用）：</p>
 * <ul>
 *   <li>429 → 尊重 {@code Retry-After}，最多 1+3 次；</li>
 *   <li>5xx → 只重试<b>一次</b>（{@link #MAX_5XX_RETRIES}）；</li>
 *   <li>其它非 2xx（4xx）→ 立刻失败，重试没有意义；</li>
 *   <li>传输层错误 → 按 {@link #RETRY_BACKOFF} 退避。</li>
 * </ul>
 *
 * <h2>超时与取消</h2>
 * <ul>
 *   <li>请求级超时：落在
 *       {@link ConnectorHttp.Client} 的构造参数（{@link #REQUEST_TIMEOUT}，30 秒）；</li>
 *   <li>取消：靠线程中断，
 *       {@link com.ragagent.datasource.Connector#sleep(long)} 会把它转成
 *       {@link ConnectorException}。</li>
 * </ul>
 *
 * <h2>实现注记</h2>
 * <ol>
 *   <li>响应体由 {@code exchange} 一次读完，所以不存在"读响应体失败"这个独立重试分支。</li>
 *   <li>512MB 上限只能"读完再判"——
 *       JDK 的 {@code HttpClient} 不允许替换 BodyHandler 的分块读取。
 *       净效果一致（超限即报错），差别只在内存峰值。</li>
 * </ol>
 *
 * <p><b>这是内部类型</b>：只进出飞书 API，从不落 jsonb、从不进 HTTP 响应。</p>
 */
public class FeishuClient implements DocxMarkdown.SheetReader {

    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);

    /** 单次请求超时。 */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** 429/传输层错误的最大重试次数。 */
    public static final int MAX_RETRIES = 3;

    /** 5xx 的最大重试次数。 */
    public static final int MAX_5XX_RETRIES = 1;

    /** 单次下载的字节上限（512 MB）。 */
    public static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;

    /**
     * 5xx 重试前的固定等待（默认 2 秒）。
     *
     * <p>做成<b>可覆盖的字段</b>，让重试类用例不必真的睡 2 秒。生产取默认值。</p>
     */
    public static volatile Duration retry5xxDelay = Duration.ofSeconds(2);

    /** 传输层错误的退避序列（可被测试覆盖）。 */
    public static volatile List<Duration> retryBackoff =
            List.of(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8));

    /**
     * 导出任务的最长轮询时间（默认 60 秒）与轮询间隔（默认 2 秒）。
     *
     * <p>做成字段是<b>刻意的</b>：留出注入缝，超时/轮询分支才测得到，且不必真的睡 60 秒。</p>
     */
    public static volatile Duration exportTimeout = Duration.ofSeconds(60);

    /** @see #exportTimeout */
    public static volatile Duration exportPollInterval = Duration.ofSeconds(2);

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    final String baseUrl;
    final String appId;
    final String appSecret;

    /** 多维表格日期单元格的渲染时区（默认 GMT+8）。 */
    private final ZoneId location;

    final ConnectorHttp.Client httpClient;

    /** 传输层协作者（构造期装配）。 */
    final FeishuTransport transport;

    /** Drive 文件列举协作者（构造期装配）。 */
    final FeishuDriveOps driveOps;

    /** wiki 树遍历协作者（构造期装配）。 */
    final FeishuWikiTreeOps treeOps;

    /** 从配置构造。 */
    public FeishuClient(FeishuConfig config) {
        this(config.resolveBaseUrl(), config.getAppId(), config.getAppSecret(),
                FeishuConfig.resolveLocation(config.getTimezone()),
                ConnectorHttp.newConnectorHttpClient(REQUEST_TIMEOUT));
    }

    /**
     * 完整构造器——测试直接指到本机 stub server 用。
     */
    public FeishuClient(String baseUrl, String appId, String appSecret, ZoneId location,
                        ConnectorHttp.Client httpClient) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.appId = appId == null ? "" : appId;
        this.appSecret = appSecret == null ? "" : appSecret;
        this.location = location;
        this.httpClient = httpClient;
        this.transport = new FeishuTransport(this);
        this.driveOps = new FeishuDriveOps(this);
        this.treeOps = new FeishuWikiTreeOps(this);
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** 没配 location 时回落 GMT+8。 */
    public ZoneId tz() {
        return location != null
                ? location
                : ZoneId.ofOffset("GMT", java.time.ZoneOffset.ofTotalSeconds(
                        FeishuConfig.DEFAULT_TIMEZONE_OFFSET_SECONDS));
    }

    /** 实现见 {@link FeishuTransport}。 */
        public String getTenantAccessToken() {
        return transport.getTenantAccessToken();
    }

    /** 实现见 {@link FeishuTransport}。 */
        public void ping() {
        transport.ping();
    }

    /** 实现见 {@link FeishuTransport}。 */
        public <T> T doRequest(String method, String path, Object body, Class<T> resultType) {
        return transport.doRequest(method, path, body, resultType);
    }

    /** 实现见 {@link FeishuTransport}。 */
        public static Duration parseRetryAfter(String header, Duration fallback) {
        return FeishuTransport.parseRetryAfter(header, fallback);
    }

    /** 实现见 {@link FeishuWikiTreeOps}。 */
        public List<WikiSpace> listWikiSpaces() {
        return treeOps.listWikiSpaces();
    }

    /** 实现见 {@link FeishuWikiTreeOps}。 */
        public List<WikiNode> listWikiNodes(String spaceId, String parentNodeToken) {
        return treeOps.listWikiNodes(spaceId, parentNodeToken);
    }

    /** 实现见 {@link FeishuWikiTreeOps}。 */
        public WikiNode getWikiNode(String spaceId, String nodeToken) {
        return treeOps.getWikiNode(spaceId, nodeToken);
    }

    /** 实现见 {@link FeishuWikiTreeOps}。 */
        public List<WikiNode> listAllWikiNodesRecursive(String spaceId) {
        return treeOps.listAllWikiNodesRecursive(spaceId);
    }

    /** 实现见 {@link FeishuWikiTreeOps}。 */
        public List<WikiNode> listWikiNodesRecursiveFrom(String spaceId, String nodeToken) {
        return treeOps.listWikiNodesRecursiveFrom(spaceId, nodeToken);
    }

    /** 实现见 {@link FeishuDriveOps}。 */
        public DriveFilePage listDriveFiles(String folderToken, String pageToken) {
        return driveOps.listDriveFiles(folderToken, pageToken);
    }

    /** 实现见 {@link FeishuDriveOps}。 */
        public DriveFolderMetaResponse getDriveFolderMeta(String folderToken) {
        return driveOps.getDriveFolderMeta(folderToken);
    }

    /** 实现见 {@link FeishuDriveOps}。 */
        public List<DriveFile> listDriveFilesAllPages(String folderToken) {
        return driveOps.listDriveFilesAllPages(folderToken);
    }

    /** 实现见 {@link FeishuDriveOps}。 */
        public List<DriveFile> listDriveFilesRecursiveFrom(String folderToken) {
        return driveOps.listDriveFilesRecursiveFrom(folderToken);
    }

    // ──────────────────────────────────────────────────────────────────
    // 认证
    // ──────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────
    // 通用请求（JSON API）
    // ──────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────
    // wiki：空间 / 节点
    // ──────────────────────────────────────────────────────────────────

    /**
     * 已废弃路径，仅为兼容保留。
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

    /** 建导出任务，返回 ticket。 */
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

    /** 导出任务状态查询的结果。 */
    record ExportStatus(String fileToken, String fileName) {
    }

    /**
     * 查询导出任务状态。
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

    /** 下载导出结果（必须在完成后 10 分钟内下载）。 */
    public byte[] downloadExportFile(String fileToken) {
        return downloadRawBytes("/open-apis/drive/v1/export_tasks/file/" + fileToken + "/download");
    }

    /**
     * 建导出任务 → 轮询到完成 → 下载文件。
     *
     * <p>超时 60 秒、轮询间隔 2 秒（见 {@link #exportTimeout} / {@link #exportPollInterval}）。</p>
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
            // 等待轮询间隔；中断语义走 Connector.sleep
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

    /** 导出下载结果：文件字节与文件名。 */
    public record ExportDownload(byte[] data, String fileName) {
    }

    // ──────────────────────────────────────────────────────────────────
    // Drive 文件下载
    // ──────────────────────────────────────────────────────────────────

    /**
     * 按 file token 下载云盘文件。
     * 用于 {@code obj_type="file"} 的 wiki 节点（用户上传的 PDF/Word/图片…）。
     */
    public byte[] downloadDriveFile(String fileToken) {
        return downloadRawBytes("/open-apis/drive/v1/files/" + fileToken + "/download");
    }

    /**
     * 下载<b>文档内嵌</b>媒体（File/Image 块引用的
     * 附件与图片）。
     *
     * <p>内嵌媒体的 token 空间与独立的 Drive 文件不同，必须走 {@code /medias/}
     * 而不是 {@code /files/}。</p>
     */
    public byte[] downloadMediaFile(String fileToken) {
        return downloadRawBytes("/open-apis/drive/v1/medias/"
                + FeishuSupport.pathEscape(fileToken) + "/download");
    }

    /** 带鉴权的 GET，返回原始响应体。 */
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
                    Connector.sleep(FeishuTransport.backoffAt(attempt).toMillis());
                    continue;
                }
                throw lastErr;
            }

            if (resp.status() == 429) {
                Duration wait = parseRetryAfter(resp.header("Retry-After"), FeishuTransport.backoffAt(attempt));
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
            // body 已经在 exchange 里读完，只能读完再判超限（净效果一致）。
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

    /** 云盘文件列表的一页。 */
    public record DriveFilePage(List<DriveFile> files, String nextPageToken) {
    }

    // ──────────────────────────────────────────────────────────────────
    // docx 块
    // ──────────────────────────────────────────────────────────────────

    /**
     * 把一篇 docx 的全部 block 拉成一个扁平的
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

    /** 一次内嵌表格读取的结果：字符串化的行 + 是否截断。 */
    public record SheetRange(List<List<String>> rows, boolean truncated) {
    }

    /**
     * 读内嵌电子表格单元格的值。
     *
     * <p>{@code embedToken} 是 sheet 块的 {@code sheet.token}，形如
     * {@code "spreadsheetToken_sheetId"}（在<b>最后一个</b>下划线处切分）。
     * 单元格一律字符串化（显示值）供 RAG 文本检索。行数按
     * {@link DocxBlocks#MAX_TABLE_ROWS} 截断，源行数更多时 {@code truncated=true}。</p>
     */
    public SheetRange readSheetRange(String embedToken) {
        // 在最后一个下划线处切分：spreadsheet token 本身可能含下划线
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

    /** 一次内嵌多维表格读取的结果：表头 + 数据行 + 是否截断。 */
    public record BitableTable(List<List<String>> rows, boolean truncated) {
    }

    /**
     * 把一个内嵌多维表格读成表格——
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

    /** null 列表归一成空列表：循环/追加前统一处理。 */
    static <T> List<T> nvl(List<T> list) {
        return list == null ? List.of() : list;
    }

    static String nvl(String s) {
        return s == null ? "" : s;
    }
}
