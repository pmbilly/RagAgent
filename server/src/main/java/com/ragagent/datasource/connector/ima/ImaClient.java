package com.ragagent.datasource.connector.ima;

import com.ragagent.common.web.JsonMappers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorHttp;
import com.ragagent.datasource.connector.ima.ImaApiTypes.ApiEnvelope;
import com.ragagent.datasource.connector.ima.ImaApiTypes.GetAddableKnowledgeBaseListResp;
import com.ragagent.datasource.connector.ima.ImaApiTypes.GetDocContentResp;
import com.ragagent.datasource.connector.ima.ImaApiTypes.GetKnowledgeBaseResp;
import com.ragagent.datasource.connector.ima.ImaApiTypes.GetKnowledgeListResp;
import com.ragagent.datasource.connector.ima.ImaApiTypes.GetMediaInfoResp;
import com.ragagent.datasource.connector.ima.ImaApiTypes.SearchKnowledgeBaseResp;
import com.ragagent.datasource.connector.ima.ImaApiTypes.UrlInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IMA OpenAPI 客户端。
 *
 * <h2>两个 HTTP 客户端，两种超时</h2>
 * <p>{@code httpClient} 用 {@code defaultTimeout=60s} 调 OpenAPI；
 * {@code downloadClient} 用 {@code downloadTimeout=120s} 从
 * {@code get_media_info} 返回的 COS/CDN 地址拉正文（IMA 的大 PDF 很慢）。
 * 两者都经 {@link ConnectorHttp#newConnectorHttpClient(Duration)} 构造，
 * 于是 SSRF 校验、限次重定向跟随、跨域剥凭据头三层防护一体继承。</p>
 *
 * <h2>取消</h2>
 * <p>取消靠线程中断：{@link Connector#sleep(long)} 被中断时抛 {@link ConnectorException}。</p>
 *
 * <h2>重试矩阵</h2>
 * <ul>
 *   <li>传输层失败 / 429 / 业务限频码 {@code 110021} → 退避 {@code backoff[attempt]}；</li>
 *   <li>5xx → 只重试 {@code max5xxRetries}（1）次，固定等 {@code retry5xxDelay}；</li>
 *   <li>401/403 → 立刻转 {@link ConnectorException.InvalidCredentials}（不重试）；</li>
 *   <li>其它非 2xx → {@code "ima api http error: status=%d body=%s"}；</li>
 *   <li>业务码 {@code 110030}（无权限）→ {@link ConnectorException.InvalidCredentials}；</li>
 *   <li>其它非零业务码 → 原文 {@code "ima api error: code=%d msg=%s"}（含 {@code 110001}
 *       参数非法——那是我方的 bug，不是凭据问题，原文透给用户）。</li>
 * </ul>
 *
 * <h2>内部形状</h2>
 * <p>返回的 DTO 全部来自 {@link ImaApiTypes}，是"内部 API 形状，不是契约"。
 * 唯一对外出圈的是 {@link #downloadUrl} 拿回的字节与 Content-Type。</p>
 */
public class ImaClient {

    private static final Logger log = LoggerFactory.getLogger(ImaClient.class);

    /** 客户端常量。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(120);
    public static final int DEFAULT_PAGE_SIZE = 50;
    /** IMA 硬上限：{@code search_knowledge_base} 最大 limit=20（{@code get_knowledge_list} 是 50）。 */
    public static final int SEARCH_PAGE_SIZE = 20;
    public static final String USER_AGENT = "WeKnora-IMA-Connector/1.0";
    /** 单文件下载字节上限（与 IMA 最大的单文件一致）。 */
    public static final long MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024;

    public static final String API_BASE_PATH = "/openapi/wiki/v1";
    /** 笔记自成一个命名空间：wiki 端点只给 notebook_id，正文要到这里读。 */
    public static final String NOTE_BASE_PATH = "/openapi/note/v1";

    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String baseUrl;
    private final String clientId;
    private final String apiKey;
    private final ConnectorHttp.Client httpClient;
    private final ConnectorHttp.Client downloadClient;
    private final ImaRetryPolicy retry;
    private final AtomicBoolean credentialLogged = new AtomicBoolean();

    /** 构造器：默认重试预算，另有可注入的 {@link ImaRetryPolicy}。 */
    public ImaClient(ImaConfig cfg) {
        this(cfg, ImaRetryPolicy.defaults());
    }

    public ImaClient(ImaConfig cfg, ImaRetryPolicy retryPolicy) {
        this.baseUrl = cfg.baseURL();
        this.clientId = cfg.getClientId() == null ? "" : cfg.getClientId();
        this.apiKey = cfg.getApiKey() == null ? "" : cfg.getApiKey();
        this.httpClient = ConnectorHttp.newConnectorHttpClient(DEFAULT_TIMEOUT);
        this.downloadClient = ConnectorHttp.newConnectorHttpClient(DOWNLOAD_TIMEOUT);
        this.retry = retryPolicy == null ? ImaRetryPolicy.defaults() : retryPolicy;
    }

    // ── 业务端点 ──────────────────────────────────────────────────────────

    /**
     * 空 query 返回当前 token 可见的全部知识库。
     */
    public SearchKnowledgeBaseResp searchKnowledgeBase(String query, String cursor, int limit) {
        if (limit <= 0 || limit > SEARCH_PAGE_SIZE) {
            limit = SEARCH_PAGE_SIZE;
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("query", query == null ? "" : query);
        req.put("cursor", cursor == null ? "" : cursor);
        req.put("limit", limit);
        JsonNode data = callApi(API_BASE_PATH, "search_knowledge_base", req);
        return decode(data, SearchKnowledgeBaseResp.class, new SearchKnowledgeBaseResp());
    }

    /**
     * 批量取知识库详情（1-20 个 id）。
     * {@code ids} 为空时**不发请求**，直接回空 map。
     */
    public Map<String, ImaApiTypes.KnowledgeBaseInfo> getKnowledgeBase(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("ids", new ArrayList<>(ids));
        JsonNode data = callApi(API_BASE_PATH, "get_knowledge_base", req);
        GetKnowledgeBaseResp resp = decode(data, GetKnowledgeBaseResp.class, new GetKnowledgeBaseResp());
        return resp.getInfos() == null ? new LinkedHashMap<>() : resp.getInfos();
    }

    /**
     * 当前 token 有权写入的知识库。
     * 这是"这个凭据能看到哪些 KB"的权威端点（{@code ListResources} 的主来源）。
     */
    public GetAddableKnowledgeBaseListResp getAddableKnowledgeBaseList(String cursor, int limit) {
        if (limit <= 0 || limit > DEFAULT_PAGE_SIZE) {
            limit = DEFAULT_PAGE_SIZE;
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("cursor", cursor == null ? "" : cursor);
        req.put("limit", limit);
        JsonNode data = callApi(API_BASE_PATH, "get_addable_knowledge_base_list", req);
        return decode(data, GetAddableKnowledgeBaseListResp.class,
                new GetAddableKnowledgeBaseListResp());
    }

    /**
     * 列某个文件夹下的条目（{@code folderId} 空表示根）。
     *
     * <p><b>{@code knowledge_base_id} 无条件放进请求体，{@code folder_id} 只在非空时放</b>
     * ——这是既有的请求形状，别"顺手统一"成两个都放。</p>
     */
    public GetKnowledgeListResp getKnowledgeList(String kbId, String folderId, String cursor, int limit) {
        if (limit <= 0 || limit > DEFAULT_PAGE_SIZE) {
            limit = DEFAULT_PAGE_SIZE;
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("cursor", cursor == null ? "" : cursor);
        req.put("limit", limit);
        req.put("knowledge_base_id", kbId == null ? "" : kbId);
        if (folderId != null && !folderId.isEmpty()) {
            req.put("folder_id", folderId);
        }
        JsonNode data = callApi(API_BASE_PATH, "get_knowledge_list", req);
        return decode(data, GetKnowledgeListResp.class, new GetKnowledgeListResp());
    }

    /**
     * 拿 URL 访问信息，笔记还会给
     * {@code notebook_ext_info.notebook_id}。
     */
    public GetMediaInfoResp getMediaInfo(String mediaId) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("media_id", mediaId == null ? "" : mediaId);
        JsonNode data = callApi(API_BASE_PATH, "get_media_info", req);
        return decode(data, GetMediaInfoResp.class, new GetMediaInfoResp());
    }

    /**
     * POST {@code /openapi/note/v1/get_doc_content}，
     * 返回笔记正文（{@code target_content_format=0} 为纯文本）。
     *
     * <p>{@code noteId} 是 {@code get_media_info} 为笔记报出的 notebook_id：
     * wiki 命名空间从不暴露笔记正文，只指向它。</p>
     */
    public String getNoteContent(String noteId) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("note_id", noteId == null ? "" : noteId);
        req.put("target_content_format", 0);
        JsonNode data = callApi(NOTE_BASE_PATH, "get_doc_content", req);
        return decode(data, GetDocContentResp.class, new GetDocContentResp()).getContent();
    }

    // ── 下载 ──────────────────────────────────────────────────────────────

    /** 下载结果：响应体字节 + Content-Type。 */
    public record DownloadResult(byte[] body, String contentType) {
    }

    /**
     * 抓 {@code get_media_info} 给的 URL，
     * 带上 IMA 随 URL 一起返回的鉴权头。
     *
     * <p><b>先做一次 SSRF 校验</b>（这个 URL 来自 API 响应，对本进程而言是
     * 攻击者可影响的输入），再交给已经内置逐跳校验的 {@code downloadClient}。</p>
     *
     * <p><b>内存注记</b>：
     * {@link ConnectorHttp.Client} 一次性读完响应体，所以超限检查发生在读完之后
     * ——判定结果相同，但峰值内存不受这条上限保护。</p>
     */
    public DownloadResult downloadUrl(UrlInfo u) {
        String url = u == null ? "" : u.getUrl();
        if (url == null || url.isEmpty()) {
            throw new ConnectorException("empty url");
        }
        try {
            ConnectorHttp.ssrfGuard().validateURLForSSRF(url);
        } catch (com.ragagent.common.security.SsrfGuard.SsrfException e) {
            throw new ConnectorException("media URL rejected: " + e.getMessage(), e);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (u.getHeaders() != null) {
            headers.putAll(u.getHeaders());
        }
        headers.put("User-Agent", USER_AGENT);

        ConnectorHttp.Response resp = downloadClient.get(url, headers);
        if (!resp.ok()) {
            throw new ConnectorException("download http error: status=" + resp.status());
        }
        byte[] body = resp.body() == null ? new byte[0] : resp.body();
        if (body.length > MAX_DOWNLOAD_BYTES) {
            throw new ConnectorException("download body exceeds " + MAX_DOWNLOAD_BYTES + " bytes");
        }
        return new DownloadResult(body, resp.header("Content-Type"));
    }

    // ── 核心：信封解析 + 重试 ─────────────────────────────────────────────

    /** 默认命名空间的调用。 */
    JsonNode callApi(String action, Object req) {
        return callApi(API_BASE_PATH, action, req);
    }

    /**
     * 向 {@code <basePath>/<action>} 发一次带鉴权的 POST，
     * 解析 {@code {code, msg, data}} 信封，返回 {@code data} 节点（缺失或 {@code null}
     * 时回 {@code null}）。
     */
    JsonNode callApi(String basePath, String action, Object req) {
        logCredentialOnce();

        byte[] body;
        if (req != null) {
            try {
                body = MAPPER.writeValueAsBytes(req);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new ConnectorException("marshal request: " + e.getMessage(), e);
            }
        } else {
            body = "{}".getBytes(StandardCharsets.UTF_8);
        }

        String url = baseUrl + basePath + "/" + action;
        // 两个命名空间的动作名互不重叠，但日志里给出完整路径，免得二者混淆。
        String label = basePath + "/" + action;

        ConnectorException lastErr = null;
        for (int attempt = 0; attempt <= retry.maxRetries(); attempt++) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("ima-openapi-clientid", clientId);
            headers.put("ima-openapi-apikey", apiKey);
            headers.put("Content-Type", "application/json; charset=utf-8");
            headers.put("User-Agent", USER_AGENT);

            if (attempt == 0) {
                log.info("[IMA] POST {}", label);
            } else {
                log.info("[IMA] POST {} (retry {}/{})", label, attempt, retry.maxRetries());
            }

            ConnectorHttp.Response resp;
            try {
                resp = httpClient.post(url, headers, body);
            } catch (ConnectorException e) {
                lastErr = e;
                if (attempt < retry.maxRetries()) {
                    Connector.sleep(retry.backoffAt(attempt).toMillis());
                    continue;
                }
                throw lastErr;
            }

            // 响应体只在错误路径上才进日志：成功响应（尤其 get_media_info）带着
            // 预签名的存储 URL 与逐请求鉴权头，绝不能落进服务端日志。
            String bodyPreview = resp.truncatedBody(500);
            log.info("[IMA] POST {} -> status={} bodyLen={}", label, resp.status(),
                    resp.body() == null ? 0 : resp.body().length);

            // HTTP 层鉴权失败：直接暴露成 ErrInvalidCredentials。
            if (resp.status() == 401 || resp.status() == 403) {
                throw new ConnectorException.InvalidCredentials(
                        "status=" + resp.status() + " body=" + bodyPreview);
            }

            if (resp.status() == 429) {
                lastErr = new ConnectorException(
                        "ima rate limited: status=429 body=" + bodyPreview);
                if (attempt < retry.maxRetries()) {
                    Connector.sleep(retry.backoffAt(attempt).toMillis());
                    continue;
                }
                throw lastErr;
            }

            if (resp.status() >= 500 && resp.status() < 600) {
                lastErr = new ConnectorException(
                        "ima server error: status=" + resp.status() + " body=" + bodyPreview);
                if (attempt < retry.max5xxRetries()) {
                    Connector.sleep(retry.retry5xxDelay().toMillis());
                    continue;
                }
                throw lastErr;
            }

            if (resp.status() < 200 || resp.status() >= 300) {
                throw new ConnectorException(
                        "ima api http error: status=" + resp.status() + " body=" + bodyPreview);
            }

            ApiEnvelope env;
            try {
                byte[] raw = resp.body() == null ? new byte[0] : resp.body();
                env = MAPPER.readValue(raw, ApiEnvelope.class);
            } catch (java.io.IOException | RuntimeException e) {
                throw new ConnectorException("decode envelope: " + e.getMessage(), e);
            }

            int code = env.statusCode();
            if (code != 0) {
                if (code == 110030) {
                    // 无权限 → 让 service 把数据源置为 error 并停止排期，直到重新授权。
                    throw new ConnectorException.InvalidCredentials(
                            "ima code=" + code + " msg=" + env.message());
                }
                if (code == 110021 && attempt < retry.maxRetries()) {
                    lastErr = new ConnectorException(
                            "ima rate limited: code=" + code + " msg=" + env.message());
                    Connector.sleep(retry.backoffAt(attempt).toMillis());
                    continue;
                }
                throw new ConnectorException("ima api error: code=" + code + " msg=" + env.message());
            }

            JsonNode data = env.getData();
            if (data == null || data.isNull() || data.isMissingNode()) {
                return null;
            }
            return data;
        }
        throw lastErr;
    }

    /** {@code data} 节点存在且非 null 时才反序列化，否则回退默认实例。 */
    private static <T> T decode(JsonNode data, Class<T> type, T fallback) {
        if (data == null) {
            return fallback;
        }
        return MAPPER.convertValue(data, type);
    }

    /** 整条 client 生命期只记一次。 */
    private void logCredentialOnce() {
        if (credentialLogged.compareAndSet(false, true)) {
            log.info("[IMA] client configured client_id={} api_key={} base={}",
                    redact(clientId), redact(apiKey), baseUrl);
        }
    }

    /**
     * 给日志用的脱敏形态，<b>绝不</b>记录完整凭据。
     *
     * <p>按 <b>UTF-8 字节</b>计长度（不足 12 字节直接 {@code ***}）：
     * ASCII 凭据下与按字符实现完全一致，
     * 出现多字节字符时最多切出替换字符，但脱敏强度不变。</p>
     */
    public static String redact(String t) {
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
}
