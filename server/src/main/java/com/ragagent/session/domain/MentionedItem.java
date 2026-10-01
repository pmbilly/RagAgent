package com.ragagent.session.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 被 @ 提及的知识库 / 文件 / 标签 / MCP 工具 / skill（对照 Go
 * {@code types.MentionedItem}，internal/types/message.go L27-36）。
 *
 * <p><b>八个键全部恒输出</b>：Go 的 string 零值是 {@code ""}，所以未使用的字段
 * 也要输出成空串，不能省略（§1.6 禁止条件键）。§14.9l S1 换锚后键名＝Java 字段名
 * （{@code kbType}/{@code kbId}/{@code kbName}/{@code serviceId}/{@code skillName}）。</p>
 *
 * <p>⚠️ 它**落两处 jsonb**（{@code messages.mentioned_items} 与
 * {@code sessions.agent_config.mentioned_items}），换键名必须配存量迁移（HANDOFF §14.9l S1）。</p>
 *
 * <p>与 {@code MapString} / {@code MentionedItemsFromRaw} 的关系：Go 把那两个函数用于
 * 从 steer 事件里 JSON 安全的 map 形态重建本结构（只认 string 类型的值，其余当空串）。
 * Java 侧对应 {@link #fromRawMap}。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MentionedItem {

    private String id = "";

    private String name = "";

    /** "kb" / "file" / "tag" / "mcp" / "skill" */
    private String type = "";

    /** "document" 或 "faq"（仅 kb 类型）。 */
    private String kbType = "";

    /** file / tag 提及所属的父知识库。 */
    private String kbId = "";

    /** 父知识库的显示名。 */
    private String kbName = "";

    /** MCP 工具提及所属的父服务。 */
    private String serviceId = "";

    /** 预加载的 agent skill 名。 */
    private String skillName = "";

    public MentionedItem() {
    }

    /**
     * 从"原始 map 列表"重建（steer 事件 data 里的 {@code mentioned_items} 这类**冻结载荷**：
     * 键名是下划线的线协议形状，不是本实体的字段名）。非 List 输入返回空列表。
     */
    public static java.util.List<MentionedItem> fromRawList(Object raw) {
        java.util.List<MentionedItem> out = new java.util.ArrayList<>();
        if (!(raw instanceof java.util.List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof java.util.Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> cast = (java.util.Map<String, Object>) m;
                out.add(fromRawMap(cast));
            }
        }
        return out;
    }

    /**
     * 从 JSON 解码后的 map 重建（对照 Go {@code MentionedItem} 的逐字段读取 +
     * {@code MapString}）。
     *
     * <p>键名是**冻结载荷**（steer 事件 data / Redis 事件）的下划线形状——与实体字段名
     * （camelCase）刻意不同，别"顺手统一"。</p>
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
