package com.ragagent.datasource.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 从外部源抓到的单个文档/内容项。
 *
 * <h2>JSON 形状（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   FetchedItem{} →
 *   {"external_id":"","title":"","content":null,"content_type":"","file_name":"","url":"",
 *    "updated_at":"0001-01-01T00:00:00Z","created_at":"0001-01-01T00:00:00Z",
 *    "metadata":null,"deleted":false,"sourceResourceId":"","replacesSubtree":false,
 *    "subtreeKeep":null}
 *   FetchedItem{Content:[]byte("hello")} → ... "content":"aGVsbG8=" ...
 * </pre>
 * <p>三个固定要点：</p>
 * <ol>
 *   <li><b>{@code content} 在 JSON 里是 <em>base64 字符串</em></b>。
 *       Jackson 对 {@code byte[]} 默认也是 base64，且默认变体
 *       {@code MIME_NO_LINEFEEDS} 与标准 base64 同字母表、同填充、同样不折行。
 *       {@code null} 时输出 {@code null}（不是 {@code ""}）。</li>
 *   <li><b>{@code updated_at} / {@code created_at} 恒输出</b>：
 *       零值输出 {@code "0001-01-01T00:00:00Z"}，不是 {@code null}。</li>
 *   <li><b>{@code metadata} 恒输出</b>，
 *       {@code null} 时写 {@code null}——别顺手给它加 {@code NON_EMPTY}。</li>
 * </ol>
 *
 * <h2>持久化语义</h2>
 * <ol>
 *   <li><b>钩子/软删除/自动时间戳/唯一索引</b>：全无——本类型不落表，
 *       只走 fetch → ingest 的进程内链路。</li>
 *   <li><b>关联预加载</b>：无。</li>
 *   <li><b>默认排序</b>：无。</li>
 * </ol>
 *
 * <h2>{@code ReplacesSubtree} / {@code SubtreeKeep} 的前置条件</h2>
 * <p>{@code replacesSubtree} 为 true 时会在父项（重新）灌入之后做一次子树清扫：
 * 删除所有 external_id 以 {@link SubtreeChildIds#subtreeChildPrefix(String)}
 * 开头、且不在 {@code subtreeKeep} 里的既有条目。设它的连接器**必须**满足：</p>
 * <ol>
 *   <li>子项的 external_id 用 {@link SubtreeChildIds#subtreeChildId} 构造
 *       （共享那个 {@code '#'} 前缀）；</li>
 *   <li>父项不晚于子项发出，且父项灌入时 {@code subtreeKeep} 已列全所有仍在的子项。</li>
 * </ol>
 * <p>{@code null} 与空列表在这里等价（字段是进程内消费的）；恒输出只为 API/调试暴露。</p>
 */
public class FetchedItem {

    /** 在外部系统里的唯一 ID。 */
    private String externalId = "";

    private String title = "";

    /** 内容字节（优先 Markdown）。JSON 形态是 **base64 字符串**（见类注释）。 */
    private byte[] content;

    /** MIME 类型（text/markdown、text/html、application/pdf …）。 */
    private String contentType = "";

    /** 建议的文件名。 */
    private String fileName = "";

    private String url = "";

    /** 在外部系统的最后修改时间（值类型零值） 。 */
    private OffsetDateTime updatedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    /** 在外部系统的创建时间。源不暴露时为零值。 */
    private OffsetDateTime createdAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    /** 附加元数据。{@code null} 时输出 {@code null}。 */
    @JsonSerialize(using = DataSourceMapSerializer.class)
    private Map<String, String> metadata;

    /**
     * 该条目在源里已被删除。
     *
     * <p>⚠️ 字段名刻意是 {@code deleted} 而**不是** {@code isDeleted}：Java 字段名以
     * {@code is} 开头时，Jackson 给字段的隐式属性名是 {@code isDeleted}、给
     * {@code isDeleted()} 这个 getter 的却是 {@code deleted}——两者对不上就会
     * **各生成一个属性**。本仓字段名即键名，只输出 {@code deleted}：
     * 去掉 {@code is} 前缀后字段与 getter 的隐式名都是 {@code deleted}、合并成一个。</p>
     */
    private boolean deleted;

    /** 来源资源 ID（例如该文档所属的文件夹）。 */
    private String sourceResourceId = "";

    /** 见类注释的子树清扫契约。{@code false} 也恒输出。 */
    private boolean replacesSubtree;

    /** 见类注释：这条清扫**保留**哪些子项。{@code null} 时输出 {@code null}。 */
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
