package com.ragagent.datasource.connector.gitlab;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorHttp;
import com.ragagent.common.text.Whitespace;

/**
 * GitLab REST 客户端。
 *
 * <h2>内部 API 形状，不是契约</h2>
 * <p>本类及其全部嵌套类型（{@link Project} / {@link TreeEntry} / {@link Comparison} /
 * {@link FileDetail}）<b>只</b>用于反序列化 GitLab 的响应。它们不落 jsonb、也从不进
 * HTTP 响应体，所以<b>不需要</b> {@code @JsonIgnore} 派生访问器、
 * 也不挂 {@code DataSourceMapSerializer}（B50 起浮点走 Jackson 默认）
 * （那套只约束会落库或作响应体的类型）。字段名一律按 GitLab 的
 * {@code snake_case} 用 {@code @JsonProperty} 显式标出——这里是<b>外部协议</b>的字段名，
 * 不是本项目的 JSON 契约。</p>
 *
 * <h2>取消与超时</h2>
 * <p>取消靠线程中断（{@link ConnectorHttp} 会把它转成 {@link ConnectorException}），
 * 超时落在客户端构造参数上。</p>
 *
 * <h2>失败形态</h2>
 * <p>非 2xx 由 {@link ApiException} 表达（{@code instanceof} 判定）；传输层失败由
 * {@link ConnectorHttp} 直接抛 {@link ConnectorException}。{@code raw()} 的
 * 404 回落正是靠这个区分。</p>
 */
public final class GitLabClient {

    /** 单次请求超时。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * <b>容忍未知属性</b>（GitLab 的响应字段只多不少，
     * 严格模式会让新版本 GitLab 加的字段直接把同步打挂）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String baseUrl;
    private final String token;
    private final ConnectorHttp.Client http;

    private GitLabClient(String baseUrl, String token, ConnectorHttp.Client http) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.http = http;
    }

    /**
     * 构造客户端。
     *
     * <p><b>归一顺序有语义</b>（顺序错了会在"带不带 scheme / 带不带
     * {@code /api/v4}"的六种组合上给出不同结果）：</p>
     * <ol>
     *   <li>{@code TrimSpace} → {@code TrimRight "/"}（去掉<b>全部</b>尾部斜杠）；</li>
     *   <li>baseURL 为空、或 token 去空白后为空 → {@code "GitLab platform configuration is missing"}；</li>
     *   <li>{@link ConnectorHttp#validateConnectorBaseUrl}——<b>此时可能还没补 scheme</b>：
     *       校验函数内部对无 scheme 的串会自己
     *       补 {@code https://} 再解析，所以 {@code evil.internal} 这种裸主机会被真的当成主机名校验，
     *       而不是被当成 path；</li>
     *   <li>不含 {@code "://"} 才补 {@code https://}；</li>
     *   <li>结尾不是 {@code /api/v4} 才补——所以 {@code .../api/v4extra} 会变成
     *       {@code .../api/v4extra/api/v4}（这条是刻意保留的既有行为）。</li>
     * </ol>
     *
     * @throws ConnectorException 配置缺失或 base_url 未过 SSRF 策略
     */
    public static GitLabClient newClient(String baseUrl, String token) {
        String base = Whitespace.trimSpace(baseUrl).replaceAll("^/+|/+$", "");
        if (base.isEmpty() || Whitespace.trimSpace(token).isEmpty()) {
            throw new ConnectorException("GitLab platform configuration is missing");
        }
        ConnectorHttp.validateConnectorBaseUrl(base);
        if (!base.contains("://")) {
            base = "https://" + base;
        }
        if (!base.endsWith("/api/v4")) {
            base += "/api/v4";
        }
        return new GitLabClient(base, token, ConnectorHttp.newConnectorHttpClient(REQUEST_TIMEOUT));
    }

    /** 归一后的 API 根（连接器把它放进 {@code FetchedItem.external_id} 做稳定标识）。 */
    public String baseUrl() {
        return baseUrl;
    }

    // ------------------------------------------------------------------
    // 端点
    // ------------------------------------------------------------------

    /**
     * 验证 PRIVATE-TOKEN 被这个 GitLab 实例接受。
     * 刻意走 {@code /user} 而不是项目列表——老的 GitLab 部署即使 token 有效，
     * 也可能拒绝项目列表的排序参数。
     */
    public void ping() {
        get("/user", UserInfo.class);
    }

    public Project project(String id) {
        return get("/projects/" + projectPath(id), Project.class);
    }

    public List<Project> projects() {
        return get("/projects?membership=true&per_page=100&order_by=path_with_namespace&sort=asc",
                listOf(Project.class));
    }

    public String commitSha(String id, String ref) {
        CommitRef commit = get("/projects/" + projectPath(id) + "/repository/commits/"
                + GitLabUrl.pathEscape(ref), CommitRef.class);
        return commit.id() == null ? "" : commit.id();
    }

    /**
     * 逐页取目录项，靠响应头 {@code X-Next-Page} 推进。
     *
     * <p><b>分页游标在响应头里，不在 body 里</b>——GitLab 的 {@code /repository/tree}
     * 返回的是一个裸 JSON 数组，没有 {@code next_page} 字段。空串表示没有下一页。</p>
     */
    public List<TreeEntry> tree(String id, String ref, String dir) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("ref", ref);
        q.put("per_page", "100");
        q.put("page", "1");
        if (dir != null && !dir.isEmpty()) {
            q.put("path", dir);
        }
        String endpoint = "/projects/" + projectPath(id) + "/repository/tree";
        List<TreeEntry> all = new ArrayList<>();
        while (true) {
            Page<TreeEntry> page = getTreePage(endpoint, q);
            all.addAll(page.items());
            if (page.nextPage().isEmpty()) {
                return all;
            }
            q.put("page", page.nextPage());
        }
    }

    /**
     * 取文件正文，带 404 的 base64 回落。
     *
     * <p>先 {@code /repository/files/<esc>/raw?ref=}；只有拿到
     * <b>404</b> 才改走 {@code /repository/files/<esc>?ref=}，并要求
     * {@code encoding == "base64"} 才解码。非 404 的失败直接
     * {@code "gitlab raw file: " + err}（Gitaly 的 5xx 不该被回落掩盖）。</p>
     *
     * <p>那种"有文件详情端点、却没有标准 {@code /raw} 路由"的 GitLab 部署，
     * 详情响应的 {@code content} 与 raw 完全同源，只是 base64 了。</p>
     */
    public byte[] raw(String id, String ref, String file) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("ref", ref);
        String encodedFile = filePathEscape(file);
        String filesPath = "/projects/" + projectPath(id) + "/repository/files/" + encodedFile;
        String rawEndpoint = filesPath + "/raw?" + GitLabUrl.valuesEncode(q);

        try {
            return getRaw(rawEndpoint);
        } catch (ConnectorException err) {
            if (!(err instanceof ApiException apiErr) || apiErr.status() != 404) {
                throw new ConnectorException("gitlab raw file: " + err.getMessage(), err);
            }
        }

        FileDetail detail;
        String detailEndpoint = filesPath + "?" + GitLabUrl.valuesEncode(q);
        try {
            detail = get(detailEndpoint, FileDetail.class);
        } catch (ConnectorException err) {
            throw new ConnectorException("gitlab file content: " + err.getMessage(), err);
        }
        String encoding = detail.encoding() == null ? "" : detail.encoding();
        if (!"base64".equals(encoding)) {
            throw new ConnectorException("gitlab file content: unsupported encoding \"" + encoding + "\"");
        }
        String content = detail.content() == null ? "" : detail.content();
        try {
            return decodeBase64Content(content);
        } catch (IllegalArgumentException err) {
            throw new ConnectorException("gitlab file content: decode base64: " + err.getMessage(), err);
        }
    }

    /**
     * GitLab 文件内容的 base64 解码（Java 原生）。
     *
     * <p><b>2026-10-03（B44）</b>：原 {@code GoBase64} 手写复刻已退役——GitLab 的 base64
     * 按每 60 字符换行，故先去换行再走 JDK 标准解码器；非法字符由
     * {@link IllegalArgumentException} 报出（原 Go 的 {@code CorruptInputException}
     * 带字节偏移诊断，属复刻面、不保留）。</p>
     */
    static byte[] decodeBase64Content(String content) {
        return java.util.Base64.getDecoder().decode(content.replace("\n", "").replace("\r", ""));
    }

    /**
     * 比对两个 ref 的差异。
     *
     * <p>调用方必须<b>先看异常、再检查 {@code compareTimeout}</b>：
     * 两者是独立触发条件，异常抛出时结果对象不可读。</p>
     */
    public Comparison compare(String id, String from, String to) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("from", from);
        q.put("to", to);
        return get("/projects/" + projectPath(id) + "/repository/compare?" + GitLabUrl.valuesEncode(q),
                Comparison.class);
    }

    // ------------------------------------------------------------------
    // 纯函数
    // ------------------------------------------------------------------

    /**
     * 把 GitLab 项目标识编码成 URL 路径段。
     *
     * <ol>
     *   <li>纯数字 ID <b>原样</b>（{@code ParseInt} 成功即返回，含前导零与负号）；</li>
     *   <li>含 {@code %} 时先尝试 {@code pathUnescape}（<b>失败就保留原串</b>）；
     *       这一步是为了让已经编码过的 {@code group%2Fproject} 不会被二次编码成
     *       {@code group%252Fproject}；</li>
     *   <li>含 {@code /} 则逐段 {@code PathEscape} 后用 {@code %2F} 拼回去；</li>
     *   <li>否则整串 {@code PathEscape}。</li>
     * </ol>
     */
    public static String projectPath(String id) {
        String trimmed = Whitespace.trimSpace(id);
        if (trimmed.isEmpty()) {
            return "";
        }
        try {
            Long.parseLong(trimmed);
            return trimmed;
        } catch (NumberFormatException ignored) {
            // 不是数字 → 按命名空间路径处理
        }
        String decoded = trimmed;
        if (trimmed.contains("%")) {
            try {
                decoded = GitLabUrl.pathUnescape(trimmed);
            } catch (GitLabUrl.InvalidEscapeException ignored) {
                // 解码失败时保留原串
            }
        }
        if (decoded.contains("/")) {
            String[] parts = decoded.split("/", -1);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    b.append("%2F");
                }
                b.append(GitLabUrl.pathEscape(parts[i]));
            }
            return b.toString();
        }
        return GitLabUrl.pathEscape(decoded);
    }

    /**
     * 公司 GitLab 的 raw-file 路由只让
     * ASCII 字母、数字、连字符、下划线保持字面。
     *
     * <p><b>其余一律逐字节 {@code %XX}，大写十六进制</b>——所以 {@code .} 也要转义
     * （{@code a.md} → {@code a%2Emd}）。注意按 <b>UTF-8 字节</b>遍历：
     * 中文会被拆成多个字节各自转义（{@code 中} → {@code %E4%B8%AD}），
     * 这<b>不是</b>按码点编码，用 {@code char} 遍历会直接错。</p>
     */
    public static String filePathEscape(String file) {
        char[] hex = "0123456789ABCDEF".toCharArray();
        byte[] bytes = (file == null ? "" : file).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder b = new StringBuilder(bytes.length * 3);
        for (byte raw : bytes) {
            int c = raw & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                b.append((char) c);
                continue;
            }
            b.append('%').append(hex[(c >> 4) & 0x0F]).append(hex[c & 0x0F]);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------
    // HTTP 细节
    // ------------------------------------------------------------------

    private <T> T get(String endpoint, Class<T> type) {
        return get(endpoint, MAPPER.getTypeFactory().constructType(type));
    }

    private <T> T get(String endpoint, JavaType type) {
        ConnectorHttp.Response resp = http.get(baseUrl + endpoint, authHeaders());
        if (!resp.ok()) {
            throw new ApiException(endpoint, resp.status());
        }
        return parse(resp.body(), type);
    }

    private byte[] getRaw(String endpoint) {
        ConnectorHttp.Response resp = http.get(baseUrl + endpoint, authHeaders());
        if (!resp.ok()) {
            throw new ApiException(endpoint, resp.status());
        }
        return resp.body() == null ? new byte[0] : resp.body();
    }

    private Page<TreeEntry> getTreePage(String endpoint, Map<String, String> query) {
        ConnectorHttp.Response resp = http.get(baseUrl + endpoint + "?" + GitLabUrl.valuesEncode(query),
                authHeaders());
        if (!resp.ok()) {
            throw new ApiException(endpoint, resp.status());
        }
        List<TreeEntry> page = parse(resp.body(), listOf(TreeEntry.class));
        return new Page<>(page, resp.header("X-Next-Page"));
    }

    private Map<String, String> authHeaders() {
        return Map.of("PRIVATE-TOKEN", token);
    }

    private static <T> T parse(byte[] body, JavaType type) {
        try {
            return MAPPER.readValue(body == null ? new byte[0] : body, type);
        } catch (IOException e) {
            // 调用方只把它当"这次请求失败了"，
            // 具体文案由 Jackson 给出
            throw new ConnectorException("gitlab API response decode: " + e.getMessage(), e);
        }
    }

    private static JavaType listOf(Class<?> element) {
        return MAPPER.getTypeFactory().constructCollectionType(List.class, element);
    }

    private record Page<T>(List<T> items, String nextPage) {
    }

    // ------------------------------------------------------------------
    // GitLab 的响应形状（内部，不是契约）
    // ------------------------------------------------------------------

    /**
     * GitLab 返回了非 2xx。
     *
     * <p>消息格式：{@code gitlab API <endpoint>: status <n>}。{@code endpoint}
     * 是<b>不含 query</b> 的那一段。</p>
     */
    public static class ApiException extends ConnectorException {

        private static final long serialVersionUID = 1L;

        private final String endpoint;
        private final int status;

        ApiException(String endpoint, int status) {
            super("gitlab API " + endpoint + ": status " + status);
            this.endpoint = endpoint;
            this.status = status;
        }

        public String endpoint() {
            return endpoint;
        }

        public int status() {
            return status;
        }
    }

    /** GitLab 的 project 响应。{@code namespace} 从未被读取，保留形状。 */
    public record Project(
            @JsonProperty("id") long id,
            @JsonProperty("path_with_namespace") String pathWithNamespace,
            @JsonProperty("name") String name,
            @JsonProperty("web_url") String webUrl,
            @JsonProperty("default_branch") String defaultBranch,
            @JsonProperty("namespace") Namespace namespace) {

        public record Namespace(@JsonProperty("id") long id) {
        }
    }

    /** {@code /repository/tree} 的一条目录项。 */
    public record TreeEntry(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("type") String type,
            @JsonProperty("path") String path) {
    }

    /**
     * 两个 ref 的比对结果。
     *
     * <p>只建模了连接器真正读的字段：{@code deleted_file} 与 {@code renamed_file}
     * 决定"要不要发删除条目"，{@code old_path} / {@code new_path} 决定作用范围。
     * {@code new_file} 与 {@code compare_same_ref} 从不读，保留形状。</p>
     */
    public record Comparison(
            @JsonProperty("diffs") List<Diff> diffs,
            @JsonProperty("compare_timeout") boolean compareTimeout,
            @JsonProperty("compare_same_ref") boolean compareSameRef) {

        public List<Diff> diffs() {
            return diffs == null ? List.of() : diffs;
        }

        public record Diff(
                @JsonProperty("old_path") String oldPath,
                @JsonProperty("new_path") String newPath,
                @JsonProperty("new_file") boolean newFile,
                @JsonProperty("deleted_file") boolean deletedFile,
                @JsonProperty("renamed_file") boolean renamedFile) {
        }
    }

    /** {@code /repository/files/<path>} 详情响应（只取占位符那两段）。 */
    record FileDetail(
            @JsonProperty("encoding") String encoding,
            @JsonProperty("content") String content) {
    }

    /** {@code /user} 与 {@code /repository/commits/<ref>} 的单字段响应。 */
    record UserInfo(@JsonProperty("id") long id) {
    }

    record CommitRef(@JsonProperty("id") String id) {
    }
}
