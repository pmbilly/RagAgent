package com.ragagent.datasource.connector.ima;

import com.ragagent.common.web.JsonMappers;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.datasource.ConnectorHttp;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * IMA OpenAPI 的进程内替身，
 * 用 JDK 自带的 {@link HttpServer} 绑在 {@code 127.0.0.1} 的随机端口上。
 *
 * <h2>为什么用**裸 JSON map** 而不是生产 DTO</h2>
 * <p>响应体刻意用
 * {@link LinkedHashMap} 手写键名，而不是序列化生产 DTO。理由是<b>独立指定线上形状</b>——如果 fake 与被测
 * 客户端共用同一批 {@code @JsonProperty}，一个拼错的键名会让两边<em>一起</em>错，
 * 测试照样绿。手写键名让 fake 成为一份独立的契约描述。</p>
 *
 * <h2>请求/响应形状</h2>
 * <ul>
 *   <li>{@code POST /openapi/wiki/v1/<action>}（信封 {@code {code,msg,data}}）：</li>
 *   <li>{@code POST /openapi/note/v1/get_doc_content}（笔记正文，同一信封）；</li>
 *   <li>{@code GET /dl/<media_id>}（下载，返回 {@code Body} 与可选 Content-Type）。</li>
 * </ul>
 *
 * <h2>SSRF</h2>
 * <p>{@link #allowLoopback()} 把 {@code 127.0.0.1,::1,localhost} 放进白名单
 * （即 {@code SSRF_WHITELIST} 的取值）。
 * 这是**进程级**静态状态，所以 {@link #restoreSsrf()} 必须在 {@code @AfterAll} 调用，
 * 否则会污染同 JVM 里别的测试类。</p>
 */
final class FakeIma implements AutoCloseable {

    static final ObjectMapper MAPPER = JsonMappers.lenient();

    /** 一个假条目。 */
    static final class FakeFile {
        String mediaId = "";
        String title = "";
        String parentFolderId = "";
        int mediaType;
        String body = "";
        /** 下载响应的 Content-Type；空串表示不发这个头。 */
        String contentType = "";
        Map<String, String> urlHeaders;
        boolean noUrl;
        boolean infoFails;
        boolean downloadFails;
        String notebookId = "";
        String noteBody = "";
        boolean noteFails;

        static FakeFile of(String mediaId, String title, int mediaType) {
            FakeFile f = new FakeFile();
            f.mediaId = mediaId;
            f.title = title;
            f.mediaType = mediaType;
            return f;
        }

        FakeFile body(String v) {
            body = v;
            return this;
        }

        FakeFile contentType(String v) {
            contentType = v;
            return this;
        }

        FakeFile parentFolder(String v) {
            parentFolderId = v;
            return this;
        }

        FakeFile urlHeaders(Map<String, String> v) {
            urlHeaders = v;
            return this;
        }

        FakeFile noUrl() {
            noUrl = true;
            return this;
        }

        FakeFile downloadFails() {
            downloadFails = true;
            return this;
        }

        FakeFile notebook(String id, String noteBody) {
            notebookId = id;
            this.noteBody = noteBody;
            return this;
        }

        FakeFile noteFails() {
            noteFails = true;
            return this;
        }
    }

    /** 一个假文件夹。 */
    static final class FakeFolder {
        String folderId;
        String name;
        String parentFolderId = "";

        static FakeFolder of(String folderId, String name, String parentFolderId) {
            FakeFolder f = new FakeFolder();
            f.folderId = folderId;
            f.name = name;
            f.parentFolderId = parentFolderId;
            return f;
        }
    }

    /** 信封的两种拼法（API 两种拼写都接受）。 */
    enum EnvelopeStyle {
        CODE_MSG, RETCODE_ERRMSG
    }

    private final HttpServer server;
    private EnvelopeStyle envelopeStyle = EnvelopeStyle.CODE_MSG;

    private final Object lock = new Object();
    private final Map<String, Map<String, List<FakeFile>>> files = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<FakeFolder>>> folders = new ConcurrentHashMap<>();
    /** get_addable_knowledge_base_list 返回的知识库。 */
    private final List<Map<String, Object>> bases = new ArrayList<>();
    /** search_knowledge_base 返回的知识库。 */
    private final List<Map<String, Object>> searchBases = new ArrayList<>();

    private final Map<String, Integer> calls = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> downloadHeaders = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> lastRequestBodies = new ConcurrentHashMap<>();

    FakeIma() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/openapi/wiki/v1/", this::handleApi);
        server.createContext("/openapi/note/v1/", this::handleNoteApi);
        server.createContext("/dl/", this::handleDownload);
        server.start();
    }

    // ── SSRF 白名单 ────────────────────────────────────────────────────────

    /** 放行 loopback：stub server 绑在 127.0.0.1 上。 */
    static void allowLoopback() {
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("127.0.0.1,::1,localhost");
        ConnectorHttp.setSsrfGuard(guard);
    }

    /**
     * 还原**进程级**白名单。
     *
     * <p>{@code SsrfGuard.whitelist} 是静态字段，{@code new SsrfGuard()} 只是读 env
     * 的默认实例、并不会把白名单恢复成 env 的值，所以这里显式按 env 重新合并一次
     * （即 {@code SSRF_WHITELIST} 与 {@code SSRF_WHITELIST_EXTRA} 的合并语义）。</p>
     */
    static void restoreSsrf() {
        ConnectorHttp.setSsrfGuard(new SsrfGuard());
        ConnectorHttp.ssrfGuard().reloadWhitelist(envWhitelistRaw());
    }

    private static String envWhitelistRaw() {
        String primary = System.getenv("SSRF_WHITELIST");
        String extra = System.getenv("SSRF_WHITELIST_EXTRA");
        primary = primary == null ? "" : primary.trim();
        extra = extra == null ? "" : extra.trim();
        if (primary.isEmpty()) {
            return extra;
        }
        if (extra.isEmpty()) {
            return primary;
        }
        return primary + "," + extra;
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 设置一个知识库的条目与文件夹，并把它登记为 addable。 */
    void setKb(String kbId, List<FakeFile> kbFiles, FakeFolder... kbFolders) {
        synchronized (lock) {
            Map<String, List<FakeFile>> byParent = new LinkedHashMap<>();
            for (FakeFile f : kbFiles) {
                byParent.computeIfAbsent(f.parentFolderId, k -> new ArrayList<>()).add(f);
            }
            files.put(kbId, byParent);

            Map<String, List<FakeFolder>> foldersByParent = new LinkedHashMap<>();
            for (FakeFolder f : kbFolders) {
                foldersByParent.computeIfAbsent(f.parentFolderId, k -> new ArrayList<>()).add(f);
            }
            folders.put(kbId, foldersByParent);

            for (Map<String, Object> b : bases) {
                if (kbId.equals(b.get("id"))) {
                    return;
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", kbId);
            entry.put("name", "KB " + kbId);
            bases.add(entry);
        }
    }

    void setEnvelopeStyle(EnvelopeStyle style) {
        this.envelopeStyle = style;
    }

    /** 设置 search_knowledge_base 的返回（{@code id} / {@code name} / {@code coverUrl}）。 */
    void setSearchBases(List<Map<String, Object>> entries) {
        synchronized (lock) {
            searchBases.clear();
            searchBases.addAll(entries);
        }
    }

    int callCount(String key) {
        return calls.getOrDefault(key, 0);
    }

    /** 下载时实际到达 stub 的头（键已小写化，见 {@code handleDownload}）。 */
    Map<String, String> downloadHeaders(String mediaId) {
        return downloadHeaders.getOrDefault(mediaId, Map.of());
    }

    void resetCalls() {
        calls.clear();
    }

    /** 把该知识库从 addable 列表里摘掉（用于验证 search 回落）。 */
    void clearBases() {
        synchronized (lock) {
            bases.clear();
        }
    }

    private void record(String key) {
        calls.merge(key, 1, Integer::sum);
    }

    private FakeFile findFile(String mediaId) {
        synchronized (lock) {
            for (Map<String, List<FakeFile>> byParent : files.values()) {
                for (List<FakeFile> list : byParent.values()) {
                    for (FakeFile f : list) {
                        if (f.mediaId.equals(mediaId)) {
                            return f;
                        }
                    }
                }
            }
        }
        return null;
    }

    // ── 处理器 ────────────────────────────────────────────────────────────

    private void handleApi(HttpExchange ex) throws IOException {
        String action = ex.getRequestURI().getPath().substring("/openapi/wiki/v1/".length());
        record(action);

        JsonNode req = readBody(ex);
        lastRequestBodies.put(action, req);

        switch (action) {
            case "get_addable_knowledge_base_list" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                synchronized (lock) {
                    data.put("addable_knowledge_base_list", new ArrayList<>(bases));
                }
                data.put("is_end", true);
                data.put("next_cursor", "");
                writeEnvelope(ex, 0, "", data);
            }
            case "search_knowledge_base" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                synchronized (lock) {
                    data.put("info_list", new ArrayList<>(searchBases));
                }
                data.put("is_end", true);
                data.put("next_cursor", "");
                writeEnvelope(ex, 0, "", data);
            }
            case "get_knowledge_base" -> {
                Map<String, Object> infos = new LinkedHashMap<>();
                JsonNode ids = req == null ? null : req.get("ids");
                if (ids != null && ids.isArray()) {
                    for (JsonNode id : ids) {
                        String v = id.asText();
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("id", v);
                        info.put("name", "KB " + v);
                        info.put("description", "desc " + v);
                        infos.put(v, info);
                    }
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("infos", infos);
                writeEnvelope(ex, 0, "", data);
            }
            case "get_knowledge_list" -> {
                String kbId = jsonString(req, "knowledge_base_id");
                String folderId = jsonString(req, "folder_id");
                List<Map<String, Object>> list = new ArrayList<>();
                synchronized (lock) {
                    Map<String, List<FakeFolder>> fb = folders.getOrDefault(kbId, Map.of());
                    for (FakeFolder folder : fb.getOrDefault(folderId, List.of())) {
                        Map<String, Object> fj = new LinkedHashMap<>();
                        fj.put("folder_id", folder.folderId);
                        fj.put("name", folder.name);
                        fj.put("parent_folder_id", folder.parentFolderId);
                        list.add(fj);
                    }
                    Map<String, List<FakeFile>> ff = files.getOrDefault(kbId, Map.of());
                    for (FakeFile f : ff.getOrDefault(folderId, List.of())) {
                        // IMA 的列表响应**不带 media_type**。
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("media_id", f.mediaId);
                        item.put("title", f.title);
                        item.put("parent_folder_id", f.parentFolderId);
                        list.add(item);
                    }
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("knowledge_list", list);
                data.put("is_end", true);
                data.put("next_cursor", "");
                writeEnvelope(ex, 0, "", data);
            }
            case "get_media_info" -> {
                String mediaId = jsonString(req, "media_id");
                record("get_media_info:" + mediaId);
                FakeFile file = findFile(mediaId);
                if (file == null) {
                    writeEnvelope(ex, 110001, "unknown media", null);
                    return;
                }
                if (file.infoFails) {
                    send(ex, 500, null, new byte[0]);
                    return;
                }
                Map<String, Object> media = new LinkedHashMap<>();
                media.put("media_type", file.mediaType);
                Map<String, Object> urlInfo = new LinkedHashMap<>();
                // 笔记不带 url_info：正文在 note 命名空间里。
                if (!file.noUrl && file.mediaType != ImaFormats.MEDIA_TYPE_NOTE) {
                    urlInfo.put("url", baseUrl() + "/dl/" + mediaId);
                    urlInfo.put("headers", file.urlHeaders);
                } else {
                    urlInfo.put("url", "");
                    urlInfo.put("headers", null);
                }
                media.put("url_info", urlInfo);
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("notebook_id", file.notebookId);
                media.put("notebook_ext_info", note);
                writeEnvelope(ex, 0, "", media);
            }
            default -> writeEnvelope(ex, 110001, "unsupported action " + action, null);
        }
    }

    private void handleNoteApi(HttpExchange ex) throws IOException {
        String action = ex.getRequestURI().getPath().substring("/openapi/note/v1/".length());
        record("note/" + action);

        JsonNode req = readBody(ex);
        lastRequestBodies.put("note/" + action, req);
        if (!"get_doc_content".equals(action)) {
            writeEnvelope(ex, 110012, "unsupported note action " + action, null);
            return;
        }
        String noteId = jsonString(req, "note_id");
        record("get_doc_content:" + noteId);

        synchronized (lock) {
            for (Map<String, List<FakeFile>> byParent : files.values()) {
                for (List<FakeFile> list : byParent.values()) {
                    for (FakeFile f : list) {
                        if (noteId.isEmpty() || !f.notebookId.equals(noteId)) {
                            continue;
                        }
                        if (f.noteFails) {
                            writeEnvelope(ex, 110011, "note read failed", null);
                            return;
                        }
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("content", f.noteBody);
                        writeEnvelope(ex, 0, "", data);
                        return;
                    }
                }
            }
        }
        writeEnvelope(ex, 110001, "unknown note", null);
    }

    private void handleDownload(HttpExchange ex) throws IOException {
        String mediaId = ex.getRequestURI().getPath().substring("/dl/".length());
        record("download:" + mediaId);

        // ⚠️ JDK 的 HttpServer 会把头名**小写化**（"X-ima-token"），
        // 所以这里统一按小写键记录，测试用同样的形式查。
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> {
            if (!v.isEmpty()) {
                headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0));
            }
        });
        downloadHeaders.put(mediaId, headers);

        FakeFile file = findFile(mediaId);
        if (file == null || file.downloadFails) {
            send(ex, 500, null, new byte[0]);
            return;
        }
        byte[] body = file.body.getBytes(StandardCharsets.UTF_8);
        send(ex, 200, file.contentType.isEmpty() ? null : file.contentType, body);
    }

    // ── HTTP 工具 ─────────────────────────────────────────────────────────

    private JsonNode readBody(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (raw.length == 0) {
            return null;
        }
        return MAPPER.readTree(raw);
    }

    private static String jsonString(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v == null || !v.isTextual() ? "" : v.asText();
    }

    private void writeEnvelope(HttpExchange ex, int code, String msg, Object data)
            throws IOException {
        Map<String, Object> env = new LinkedHashMap<>();
        if (envelopeStyle == EnvelopeStyle.RETCODE_ERRMSG) {
            env.put("retcode", code);
            env.put("errmsg", msg);
        } else {
            env.put("code", code);
            env.put("msg", msg);
        }
        env.put("data", data);
        byte[] body = MAPPER.writeValueAsBytes(env);
        send(ex, 200, "application/json", body);
    }

    /** 某个 action 最后一次请求体（供"请求形状"断言用，例如 folder_id 的有无）。 */
    JsonNode lastRequestBody(String key) {
        return lastRequestBodies.get(key);
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body)
            throws IOException {
        if (contentType != null) {
            ex.getResponseHeaders().set("Content-Type", contentType);
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        } else {
            ex.close();
        }
    }

    /** 构造 connector 用的配置（base_url 指向本 stub）。 */
    DataSourceConfig config(String... resourceIds) {
        DataSourceConfig cfg = new DataSourceConfig();
        cfg.setType("ima");
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("client_id", "cid");
        credentials.put("api_key", "key");
        credentials.put("base_url", baseUrl());
        cfg.setCredentials(credentials);
        cfg.setResourceIds(new ArrayList<>(List.of(resourceIds)));
        return cfg;
    }

    @Override
    public void close() {
        server.stop(0);
    }

}
