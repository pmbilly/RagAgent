package com.ragagent.session.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 被 @ 提及的知识库 / 文件 / 标签 / MCP 工具 / skill（对照 Go
 * {@code types.MentionedItem}，internal/types/message.go L27-36）。
 *
 * <p><b>八个键全部无 omitempty</b>：Go 的 string 零值是 {@code ""}，所以未使用的字段
 * 也要输出成空串，不能省略。逐字段对照 Go 的 json tag 写就行，别按"用不到的字段就省"的直觉改。</p>
 *
 * <p>与 {@code MapString} / {@code MentionedItemsFromRaw} 的关系：Go 把那两个函数用于
 * 从 steer 事件里 JSON 安全的 map 形态重建本结构（只认 string 类型的值，其余当空串）。
 * Java 侧对应 {@link #fromRawMap}。</p>
 */
@JsonPropertyOrder({"id", "name", "type", "kb_type", "kb_id", "kb_name", "service_id", "skill_name"})
@JsonIgnoreProperties(ignoreUnknown = true)
public class MentionedItem {

    @JsonProperty("id")
    private String id = "";

    @JsonProperty("name")
    private String name = "";

    /** "kb" / "file" / "tag" / "mcp" / "skill" */
    @JsonProperty("type")
    private String type = "";

    /** "document" 或 "faq"（仅 kb 类型）。 */
    @JsonProperty("kb_type")
    private String kbType = "";

    /** file / tag 提及所属的父知识库。 */
    @JsonProperty("kb_id")
    private String kbId = "";

    /** 父知识库的显示名。 */
    @JsonProperty("kb_name")
    private String kbName = "";

    /** MCP 工具提及所属的父服务。 */
    @JsonProperty("service_id")
    private String serviceId = "";

    /** 预加载的 agent skill 名。 */
    @JsonProperty("skill_name")
    private String skillName = "";

    public MentionedItem() {
    }

    /**
     * 从 JSON 解码后的 map 重建（对照 Go {@code MentionedItem} 的逐字段读取 +
     * {@code MapString}）。
     *
     * <p>{@code MapString} 只接受 string 类型的值，其余（数字、对象、null）一律当空串——
     * 照抄这个宽容行为，别改成 toString。</p>
     */
    public static MentionedItem fromRawMap(java.util.Map<String, Object> m) {
        MentionedItem item = new MentionedItem();
        if (m == null) {
            return item;
        }
        item.id = mapString(m, "id");
        item.name = mapString(m, "name");
        item.type = mapString(m, "type");
        item.kbType = mapString(m, "kb_type");
        item.kbId = mapString(m, "kb_id");
        item.kbName = mapString(m, "kb_name");
        item.serviceId = mapString(m, "service_id");
        item.skillName = mapString(m, "skill_name");
        return item;
    }

    /** 对照 Go {@code types.MapString}：非 string 值当空串。 */
    public static String mapString(java.util.Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String s ? s : "";
    }

    public String getId() {
        return id;
    }

    public void setId(String v) {
        this.id = v == null ? "" : v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v == null ? "" : v;
    }

    public String getType() {
        return type;
    }

    public void setType(String v) {
        this.type = v == null ? "" : v;
    }

    public String getKbType() {
        return kbType;
    }

    public void setKbType(String v) {
        this.kbType = v == null ? "" : v;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String v) {
        this.kbId = v == null ? "" : v;
    }

    public String getKbName() {
        return kbName;
    }

    public void setKbName(String v) {
        this.kbName = v == null ? "" : v;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String v) {
        this.serviceId = v == null ? "" : v;
    }

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String v) {
        this.skillName = v == null ? "" : v;
    }
}
