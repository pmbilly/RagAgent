package com.ragagent.datasource.connector.yuque;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 语雀 Open API v2 的响应形状（对照 Go {@code yuque/types.go} 的
 * {@code v2UserResponse} / {@code v2Repo} / {@code v2Doc} / {@code flexibleStatus}
 * 等一整组类型）。
 *
 * <h2>⚠️ 内部 API 形状，不是契约</h2>
 * <p>只用于**解码语雀的响应**：不作 HTTP 响应体、不落 jsonb。真正的契约类型是
 * {@link com.ragagent.datasource.domain.Resource} /
 * {@link com.ragagent.datasource.domain.FetchedItem} /
 * {@link com.ragagent.datasource.domain.SyncCursor}。</p>
 *
 * <h2>{@code status} 既能是字符串也能是数字</h2>
 * <p>语雀的 OpenAPI 规范把 {@code status} 声明为 string，运行时却返回整数
 * （原文："cannot unmarshal number into Go struct field v2Doc.data.status of type string"）。
 * Java 侧与 Go 的 {@code flexibleStatus} 一一对应，见 {@link FlexibleStatus}。</p>
 */
public final class YuqueApiTypes {

    private YuqueApiTypes() {
    }

    // ── flexibleStatus ────────────────────────────────────────────────────

    /**
     * 对照 Go {@code flexibleStatus}：同时接受字符串 {@code "1"} 与数字 {@code 1}
     * 的 {@code status} 字段，归一成文本形式，让既有的 {@code != "1"} 比较
     * 对两种响应形状都成立。
     *
     * <h2>其它类型必须抛错</h2>
     * <p>Go 的注释特意说明：只解码到 {@code int64}，于是浮点、布尔、数组、对象
     * 会<b>清晰报错</b>，而不是被静默字符串化——如果语雀再改一次形状，
     * 我们宁可看到一个明确的错误，也不愿把垃圾喂给 {@code Status == "1"} 的比较
     * （那会让草稿被当成已发布文档灌进知识库）。</p>
     */
    @JsonDeserialize(using = FlexibleStatus.Deserializer.class)
    public static final class FlexibleStatus {

        private final String value;

        public FlexibleStatus(String value) {
            this.value = value == null ? "" : value;
        }

        public static FlexibleStatus of(String value) {
            return new FlexibleStatus(value);
        }

        /** 归一后的文本形式；{@code null} 与字段缺失都是 {@code ""}。 */
        public String value() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof FlexibleStatus other && value.equals(other.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        @Override
        public String toString() {
            return value;
        }

        /**
         * 对照 Go 的 {@code (*flexibleStatus).UnmarshalJSON}：
         * {@code null} → {@code ""}；字符串原样；整数 → 十进制字符串；
         * 其余（浮点 / 布尔 / 数组 / 对象）抛错。
         */
        public static class Deserializer extends JsonDeserializer<FlexibleStatus> {

            @Override
            public FlexibleStatus deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                JsonNode node = parser.readValueAsTree();
                if (node == null || node.isNull()) {
                    return new FlexibleStatus("");
                }
                if (node.isTextual()) {
                    return new FlexibleStatus(node.textValue());
                }
                // 整数形态：解到 int64 语义，于是浮点 / 布尔 / 数组 / 对象会
                // 大声失败，而不是被悄悄字符串化。
                if (node.isIntegralNumber() && node.canConvertToLong()) {
                    return new FlexibleStatus(Long.toString(node.longValue()));
                }
                throw new IOException("flexibleStatus: expected string or integer, got "
                        + node.toString());
            }
        }
    }

    // ── 错误体 ────────────────────────────────────────────────────────────

    /** 对照 Go {@code apiErrorBody}：非 2xx 时语雀**有时**返回的形状。 */
    public static class ApiErrorBody {

        @JsonProperty("message")
        private String message = "";

        @JsonProperty("status")
        private int status;

        public String getMessage() {
            return message;
        }

        public void setMessage(String v) {
            message = v == null ? "" : v;
        }

        public int getStatus() {
            return status;
        }

        public void setStatus(int v) {
            status = v;
        }
    }

    // ── 当前用户 / 团队 ───────────────────────────────────────────────────

    /** 对照 Go {@code v2UserResponse}（{@code GET /api/v2/user}）。 */
    public static class V2UserResponse {

        @JsonProperty("data")
        private V2User data = new V2User();

        public V2User getData() {
            return data;
        }

        public void setData(V2User v) {
            data = v == null ? new V2User() : v;
        }
    }

    /** 对照 Go {@code v2User}。{@code type} 为 {@code "Group"} 表示这是团队令牌。 */
    public static class V2User {

        @JsonProperty("id")
        private long id;

        @JsonProperty("type")
        private String type = "";

        @JsonProperty("login")
        private String login = "";

        @JsonProperty("name")
        private String name = "";

        public long getId() {
            return id;
        }

        public void setId(long v) {
            id = v;
        }

        public String getType() {
            return type;
        }

        public void setType(String v) {
            type = v == null ? "" : v;
        }

        public String getLogin() {
            return login;
        }

        public void setLogin(String v) {
            login = v == null ? "" : v;
        }

        public String getName() {
            return name;
        }

        public void setName(String v) {
            name = v == null ? "" : v;
        }
    }

    /** 对照 Go {@code v2GroupListResponse}（{@code GET /api/v2/users/{id}/groups}）。 */
    public static class V2GroupListResponse {

        @JsonProperty("data")
        private List<V2Group> data;

        public List<V2Group> getData() {
            return data;
        }

        public void setData(List<V2Group> v) {
            data = v;
        }
    }

    /** 对照 Go {@code v2Group}。 */
    public static class V2Group {

        @JsonProperty("id")
        private long id;

        @JsonProperty("login")
        private String login = "";

        @JsonProperty("name")
        private String name = "";

        public long getId() {
            return id;
        }

        public void setId(long v) {
            id = v;
        }

        public String getLogin() {
            return login;
        }

        public void setLogin(String v) {
            login = v == null ? "" : v;
        }

        public String getName() {
            return name;
        }

        public void setName(String v) {
            name = v == null ? "" : v;
        }
    }

    // ── 仓库（知识库） ────────────────────────────────────────────────────

    /**
     * 对照 Go {@code v2RepoListResponse}（{@code /api/v2/users/{login}/repos}
     * 与 {@code /groups/{login}/repos}）。
     */
    public static class V2RepoListResponse {

        @JsonProperty("data")
        private List<V2Repo> data;

        public List<V2Repo> getData() {
            return data;
        }

        public void setData(List<V2Repo> v) {
            data = v;
        }

        /** 分页是否到底：Go 用 {@code len(resp.Data) < defaultPageSize} 判定。 */
        public int size() {
            return data == null ? 0 : data.size();
        }
    }

    /** 对照 Go {@code v2Repo}。 */
    public static class V2Repo {

        @JsonProperty("id")
        private long id;

        /** {@code "Book" | "Design"}：列表接口的过滤枚举，连接器请求的是 {@code type=Book}。 */
        @JsonProperty("type")
        private String type = "";

        @JsonProperty("slug")
        private String slug = "";

        @JsonProperty("name")
        private String name = "";

        @JsonProperty("user_id")
        private long userId;

        /** 例如 {@code "group_login/book_slug"}。 */
        @JsonProperty("namespace")
        private String namespace = "";

        /** 0 私密 / 1 公开 / 2 组织内可见。 */
        @JsonProperty("public")
        private int publicValue;

        @JsonProperty("description")
        private String description = "";

        /** RFC3339 字符串。 */
        @JsonProperty("updated_at")
        private String updatedAt = "";

        public long getId() {
            return id;
        }

        public void setId(long v) {
            id = v;
        }

        public String getType() {
            return type;
        }

        public void setType(String v) {
            type = v == null ? "" : v;
        }

        public String getSlug() {
            return slug;
        }

        public void setSlug(String v) {
            slug = v == null ? "" : v;
        }

        public String getName() {
            return name;
        }

        public void setName(String v) {
            name = v == null ? "" : v;
        }

        public long getUserId() {
            return userId;
        }

        public void setUserId(long v) {
            userId = v;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String v) {
            namespace = v == null ? "" : v;
        }

        public int getPublicValue() {
            return publicValue;
        }

        public void setPublicValue(int v) {
            publicValue = v;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String v) {
            description = v == null ? "" : v;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(String v) {
            updatedAt = v == null ? "" : v;
        }
    }

    // ── 文档 ──────────────────────────────────────────────────────────────

    /** 对照 Go {@code v2DocListResponse}（{@code GET /api/v2/repos/{book_id}/docs}）。 */
    public static class V2DocListResponse {

        @JsonProperty("meta")
        private Meta meta = new Meta();

        @JsonProperty("data")
        private List<V2Doc> data;

        public Meta getMeta() {
            return meta;
        }

        public void setMeta(Meta v) {
            meta = v == null ? new Meta() : v;
        }

        public List<V2Doc> getData() {
            return data;
        }

        public void setData(List<V2Doc> v) {
            data = v;
        }

        /** 分页是否到底：Go 用 {@code len(resp.Data) < defaultPageSize} 判定。 */
        public int size() {
            return data == null ? 0 : data.size();
        }

        /** 对照 Go 里那个匿名结构体 {@code Meta struct { Total int } }（连接器从不读它）。 */
        public static class Meta {

            @JsonProperty("total")
            private int total;

            public int getTotal() {
                return total;
            }

            public void setTotal(int v) {
                total = v;
            }
        }
    }

    /** 对照 Go {@code v2Doc}：列表接口返回的摘要（不含正文）。 */
    public static class V2Doc {

        @JsonProperty("id")
        private long id;

        /** {@code Doc / Sheet / Thread / Board / Table}。 */
        @JsonProperty("type")
        private String type = "";

        @JsonProperty("slug")
        private String slug = "";

        @JsonProperty("title")
        private String title = "";

        @JsonProperty("book_id")
        private long bookId;

        @JsonProperty("user_id")
        private long userId;

        /** {@code "0"} 草稿 / {@code "1"} 已发布——API 可能返回整数或字符串。 */
        @JsonProperty("status")
        private FlexibleStatus status = new FlexibleStatus("");

        /** RFC3339 字符串——变更检测用它。 */
        @JsonProperty("content_updated_at")
        private String contentUpdatedAt = "";

        @JsonProperty("updated_at")
        private String updatedAt = "";

        @JsonProperty("word_count")
        private int wordCount;

        public long getId() {
            return id;
        }

        public void setId(long v) {
            id = v;
        }

        public String getType() {
            return type;
        }

        public void setType(String v) {
            type = v == null ? "" : v;
        }

        public String getSlug() {
            return slug;
        }

        public void setSlug(String v) {
            slug = v == null ? "" : v;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String v) {
            title = v == null ? "" : v;
        }

        public long getBookId() {
            return bookId;
        }

        public void setBookId(long v) {
            bookId = v;
        }

        public long getUserId() {
            return userId;
        }

        public void setUserId(long v) {
            userId = v;
        }

        public FlexibleStatus getStatus() {
            return status;
        }

        public void setStatus(FlexibleStatus v) {
            status = v == null ? new FlexibleStatus("") : v;
        }

        public String getContentUpdatedAt() {
            return contentUpdatedAt;
        }

        public void setContentUpdatedAt(String v) {
            contentUpdatedAt = v == null ? "" : v;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(String v) {
            updatedAt = v == null ? "" : v;
        }

        public int getWordCount() {
            return wordCount;
        }

        public void setWordCount(int v) {
            wordCount = v;
        }
    }

    /** 对照 Go {@code v2DocDetailResponse}（{@code GET /api/v2/repos/docs/{id}}）。 */
    public static class V2DocDetailResponse {

        @JsonProperty("data")
        private V2DocDetail data = new V2DocDetail();

        public V2DocDetail getData() {
            return data;
        }

        public void setData(V2DocDetail v) {
            data = v == null ? new V2DocDetail() : v;
        }
    }

    /** 对照 Go {@code v2DocDetail}。{@code format} 是 {@code markdown / lake / html}。 */
    public static class V2DocDetail {

        @JsonProperty("id")
        private long id;

        @JsonProperty("type")
        private String type = "";

        @JsonProperty("slug")
        private String slug = "";

        @JsonProperty("title")
        private String title = "";

        @JsonProperty("book_id")
        private long bookId;

        @JsonProperty("format")
        private String format = "";

        @JsonProperty("body")
        private String body = "";

        @JsonProperty("status")
        private FlexibleStatus status = new FlexibleStatus("");

        @JsonProperty("content_updated_at")
        private String contentUpdatedAt = "";

        @JsonProperty("updated_at")
        private String updatedAt = "";

        @JsonProperty("word_count")
        private int wordCount;

        @JsonProperty("book")
        private V2Repo book = new V2Repo();

        public long getId() {
            return id;
        }

        public void setId(long v) {
            id = v;
        }

        public String getType() {
            return type;
        }

        public void setType(String v) {
            type = v == null ? "" : v;
        }

        public String getSlug() {
            return slug;
        }

        public void setSlug(String v) {
            slug = v == null ? "" : v;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String v) {
            title = v == null ? "" : v;
        }

        public long getBookId() {
            return bookId;
        }

        public void setBookId(long v) {
            bookId = v;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String v) {
            format = v == null ? "" : v;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String v) {
            body = v == null ? "" : v;
        }

        public FlexibleStatus getStatus() {
            return status;
        }

        public void setStatus(FlexibleStatus v) {
            status = v == null ? new FlexibleStatus("") : v;
        }

        public String getContentUpdatedAt() {
            return contentUpdatedAt;
        }

        public void setContentUpdatedAt(String v) {
            contentUpdatedAt = v == null ? "" : v;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(String v) {
            updatedAt = v == null ? "" : v;
        }

        public int getWordCount() {
            return wordCount;
        }

        public void setWordCount(int v) {
            wordCount = v;
        }

        public V2Repo getBook() {
            return book;
        }

        public void setBook(V2Repo v) {
            book = v == null ? new V2Repo() : v;
        }
    }
}
