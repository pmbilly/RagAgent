package com.ragagent.datasource.domain;

import java.time.OffsetDateTime;
import java.util.Map;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 外部系统里一个可同步的资源（文档 / 文件夹 / 空间）。
 *
 * <h2>它是响应体</h2>
 * <p>{@code GET /datasources/:id/resources} 的响应是一个 {@code []Resource} 裸数组，
 * 没有 {@code data}/{@code success} 信封。所以下面的键序与"恒输出"口径是
 * **线上契约**。</p>
 *
 * <h2>JSON 形状（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   Resource{} →
 *   {"external_id":"","name":"","type":"","description":"","url":"",
 *    "modified_at":"0001-01-01T00:00:00Z"}
 *   Resource(全字段) →
 *   {"external_id":"e1","name":"n","type":"document","description":"d","url":"u",
 *    "modified_at":"2026-09-18T10:00:00+08:00","parent_id":"p1","has_children":true,
 *    "metadata":{"k":"v"}}
 * </pre>
 * <p>要点：{@code modified_at} 恒输出，零值输出
 * {@code "0001-01-01T00:00:00Z"} 而不是 {@code null}（Jackson 对 null 值
 * 根本不调自定义序列化器）。所以 Java 字段默认值必须是
 * {@link GoTimeSerializer#GO_ZERO_DATE_TIME}。</p>
 *
 * <h2>持久化语义</h2>
 * <ol>
 *   <li><b>钩子/软删除/自动时间戳/唯一索引</b>：全无——本类型**不落表**，
 *       只在连接器与 handler 之间流动。</li>
 *   <li><b>关联预加载</b>：无。层级关系靠 {@code parent_id} 表达，
 *       {@code ListAvailableResources} 的 {@code parentID} 参数做惰性展开。</li>
 *   <li><b>默认排序</b>：无——顺序由各连接器决定，handler 原样透传。</li>
 * </ol>
 */
public class Resource {

    /** 在外部系统里的唯一标识。 */
    private String externalId = "";

    private String name = "";

    /** 资源类型（document / folder / space / page …）。 */
    private String type = "";

    private String description = "";

    /** 在外部系统里访问它的 URL。 */
    private String url = "";

    /** 在外部系统里的最后修改时间。零值也输出字面量。 */
    private OffsetDateTime modifiedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    /** 层级资源才有。空串照输出。 */
    private String parentId = "";

    /** 还有可展开的子项。{@code false} 照输出。 */
    private boolean hasChildren;

    /** 附加元数据。{@code null} 时输出 {@code null}。 */
    @JsonSerialize(using = DataSourceMapSerializer.class)
    private Map<String, Object> metadata;

    public String getExternalId() { return externalId; }
    public void setExternalId(String v) { externalId = v == null ? "" : v; }

    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }

    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "" : v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { description = v == null ? "" : v; }

    public String getUrl() { return url; }
    public void setUrl(String v) { url = v == null ? "" : v; }

    public OffsetDateTime getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(OffsetDateTime v) {
        modifiedAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public String getParentId() { return parentId; }
    public void setParentId(String v) { parentId = v == null ? "" : v; }

    public boolean isHasChildren() { return hasChildren; }
    public void setHasChildren(boolean v) { hasChildren = v; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> v) { metadata = v; }
}
