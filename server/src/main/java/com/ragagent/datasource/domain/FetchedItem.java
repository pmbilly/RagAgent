package com.ragagent.datasource.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeDeserializer;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 从外部源抓到的单个文档/内容项（对照 Go {@code types.FetchedItem}，
 * internal/types/datasource.go L312-382）。
 *
 * <h2>Go 实录（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   FetchedItem{} →
 *   {"external_id":"","title":"","content":null,"content_type":"","file_name":"","url":"",
 *    "updated_at":"0001-01-01T00:00:00Z","created_at":"0001-01-01T00:00:00Z",
 *    "metadata":null,"is_deleted":false,"source_resource_id":""}
 *   FetchedItem{Content:[]byte("hello")} → ... "content":"aGVsbG8=" ...
 * </pre>
 * <p>三个必须照抄的点：</p>
 * <ol>
 *   <li><b>{@code content} 是 Go 的 {@code []byte}，JSON 里是 <em>base64 字符串</em></b>
 *       （{@code encoding/json} 对 {@code []byte} 用 {@code base64.StdEncoding}）。
 *       Java 的 {@code byte[]} 经 Jackson 也是 base64，且默认变体
 *       {@code MIME_NO_LINEFEEDS} 与 Go 的 StdEncoding 同字母表、同填充、同样不折行。
 *       这个键**没有** omitempty，所以 nil 时输出 {@code null}（不是 {@code ""}）。</li>
 *   <li><b>{@code updated_at} / {@code created_at} 是值类型 {@code time.Time}</b>：
 *       零值输出 {@code "0001-01-01T00:00:00Z"}，不是 {@code null}。</li>
 *   <li><b>{@code metadata} 没有 omitempty</b>（它是 {@code map[string]string}），
 *       所以 nil 时**恒输出** {@code null}——别顺手给它加 {@code NON_EMPTY}。</li>
 * </ol>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子/软删除/自动时间戳/唯一索引</b>：全无——本类型不落表，
 *       只走 fetch → ingest 的进程内链路。</li>
 *   <li><b>关联预加载</b>：无。</li>
 *   <li><b>默认排序</b>：无。</li>
 * </ol>
 *
 * <h2>{@code ReplacesSubtree} / {@code SubtreeKeep} 的前置条件（照抄 Go 的注释）</h2>
 * <p>{@code replacesSubtree} 为 true 时会在父项（重新）灌入之后做一次子树清扫：
 * 删除所有 external_id 以 {@link SubtreeChildIds#subtreeChildPrefix(String)}
 * 开头、且不在 {@code subtreeKeep} 里的既有条目。设它的连接器**必须**满足：</p>
 * <ol>
 *   <li>子项的 external_id 用 {@link SubtreeChildIds#subtreeChildId} 构造
 *       （共享那个 {@code '#'} 前缀）；</li>
 *   <li>父项不晚于子项发出，且父项灌入时 {@code subtreeKeep} 已列全所有仍在的子项。</li>
 * </ol>
 * <p>{@code nil} 与空切片在这里等价（字段是进程内消费的），omitempty 只为 API/调试暴露。</p>
 */
@JsonPropertyOrder({"external_id", "title", "content", "content_type", "file_name", "url",
        "updated_at", "created_at", "metadata", "is_deleted", "source_resource_id",
        "replaces_subtree", "subtree_keep"})
public class FetchedItem {

    /** 在外部系统里的唯一 ID。 */
    @JsonProperty("external_id")
    private String externalId = "";

    @JsonProperty("title")
    private String title = "";

    /** 内容字节（优先 Markdown）。JSON 形态是 **base64 字符串**（见类注释）。 */
    @JsonProperty("content")
    private byte[] content;

    /** MIME 类型（text/markdown、text/html、application/pdf …）。 */
    @JsonProperty("content_type")
    private String contentType = "";

    /** 建议的文件名。 */
    @JsonProperty("file_name")
    private String fileName = "";

    @JsonProperty("url")
    private String url = "";

    /** 在外部系统的最后修改时间（值类型零值） 。 */
    @JsonProperty("updated_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime updatedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    /** 在外部系统的创建时间。源不暴露时为零值。 */
    @JsonProperty("created_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime createdAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    /** 附加元数据。**无 omitempty**：nil 时输出 {@code null}。 */
    @JsonProperty("metadata")
    @JsonSerialize(using = DataSourceMapSerializer.class)
    private Map<String, String> metadata;

    /**
     * 该条目在源里已被删除。
     *
     * <p>⚠️ 字段名刻意是 {@code deleted} 而**不是** {@code isDeleted}：Java 字段名以
     * {@code is} 开头时，Jackson 给字段的隐式属性名是 {@code isDeleted}、给
     * {@code isDeleted()} 这个 getter 的却是 {@code deleted}——两者对不上就会
     * **各生成一个属性**，JSON 里同时出现 {@code is_deleted} 与 {@code deleted}。
     * 去掉 {@code is} 前缀后字段与 getter 的隐式名都是 {@code deleted}、合并成一个，
     * 再被 {@code @JsonProperty("is_deleted")} 改名（约定 §9「同族的第二种形态」）。</p>
     */
    @JsonProperty("is_deleted")
    private boolean deleted;

    /** 来源资源 ID（例如该文档所属的文件夹）。 */
    @JsonProperty("source_resource_id")
    private String sourceResourceId = "";

    /** 见类注释的子树清扫契约。omitempty → {@code false} 时整个键消失。 */
    @JsonProperty("replaces_subtree")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean replacesSubtree;

    /** 见类注释：这条清扫**保留**哪些子项。omitempty → nil / 空时整个键消失。 */
    @JsonProperty("subtree_keep")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> subtreeKeep;

    public String getExternalId() { return externalId; }
    public void setExternalId(String v) { externalId = v == null ? "" : v; }

    public String getTitle() { return title; }
    public void setTitle(String v) { title = v == null ? "" : v; }

    public byte[] getContent() { return content; }
    public void setContent(byte[] v) { content = v; }

    public String getContentType() { return contentType; }
    public void setContentType(String v) { contentType = v == null ? "" : v; }

    public String getFileName() { return fileName; }
    public void setFileName(String v) { fileName = v == null ? "" : v; }

    public String getUrl() { return url; }
    public void setUrl(String v) { url = v == null ? "" : v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) {
        updatedAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) {
        createdAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> v) { metadata = v; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean v) { deleted = v; }

    public String getSourceResourceId() { return sourceResourceId; }
    public void setSourceResourceId(String v) { sourceResourceId = v == null ? "" : v; }

    public boolean isReplacesSubtree() { return replacesSubtree; }
    public void setReplacesSubtree(boolean v) { replacesSubtree = v; }

    public List<String> getSubtreeKeep() { return subtreeKeep; }
    public void setSubtreeKeep(List<String> v) { subtreeKeep = v; }
}
