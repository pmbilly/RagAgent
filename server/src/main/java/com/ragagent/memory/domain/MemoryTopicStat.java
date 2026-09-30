package com.ragagent.memory.domain;

import com.ragagent.common.settings.MemoryKeys;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 一个话题被这个人问过多少次
 * （对照 Go {@code types.MemoryTopicStat}，internal/types/memory.go L707-724）。
 *
 * <p>单次提问是噪声；同一个主题出现在几个不同会话里才是信号。先计数、到阈值再提升，
 * 是 MemoryOS 让兴趣跟踪不被每一个路过的问题塞满的方式，也是一个知识库问题
 * 能够产生记忆、却不必每次都产生记忆的原因。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>自动时间戳</b>：{@code created_at}/{@code updated_at} 走字段名约定，
 *       Go 显式写；{@code last_seen_at} 由仓库层显式赋值（
 *       {@code BumpTopic} 的 INSERT 里就带 {@code now}），{@code promoted_at} 显式写。</li>
 *   <li><b>钩子</b>：无。</li>
 *   <li><b>关联预加载</b>：无。</li>
 *   <li><b>软删除</b>：无。</li>
 *   <li><b>默认排序</b>：{@code hits DESC, last_seen_at DESC}（TopTopics /
 *       ListUnpromotedTopics），仓库层显式写。</li>
 *   <li><b>唯一索引</b>：{@code idx_mem_topic_scope (tenant_id, subject_id, normalized_key)}
 *       ——模型 tag 与迁移**都**声明了，{@code ON CONFLICT} 的靶子就是它。</li>
 *   <li><b>DEFAULT 列</b>：{@code topic}（{@code default:''}）、{@code hits}（{@code default:0}）
 *       带字面量 default tag → GORM 实测仍显式写入。
 *       ⚠️ {@code aliases} 的 DDL 是 {@code NOT NULL DEFAULT '[]'}，
 *       而 Go 的 {@code Value()} 对 nil 也返回 {@code "[]"}（**从不** NULL）→
 *       策略取 {@code ALWAYS}，字段默认空列表，绝不能写出 NULL（会违 NOT NULL）。</li>
 * </ol>
 *
 * <h2>JSON 形态</h2>
 * <p>本类型**不是**响应体（handler 回的是 {@link MemoryTopicView}），但键按 Go struct 声明序：
 * </p>
 * <pre>
 *   MemoryTopicStat{} →
 *   {"id":"","tenant_id":0,"subject_id":"","normalized_key":"","topic":"","aliases":null,
 *    "hits":0,"last_seen_at":"0001-01-01T00:00:00Z","promoted_at":null,
 *    "created_at":"0001-01-01T00:00:00Z","updated_at":"0001-01-01T00:00:00Z"}
 * </pre>
 * <p>{@code aliases} 无 omitempty → nil 输出 {@code null}（**落库**却是 {@code []}，
 * 见类型处理器）。</p>
 */
@TableName(value = "memory_topic_stats", autoResultMap = true)
@JsonPropertyOrder({
        "id", "tenant_id", "subject_id", "normalized_key", "topic", "aliases", "hits",
        "last_seen_at", "promoted_at", "created_at", "updated_at"
})
public class MemoryTopicStat {

    @TableId(value = "id", type = IdType.INPUT)
    @JsonProperty("id")
    private String id = "";

    @JsonProperty("tenant_id")
    private Long tenantId = 0L;

    @JsonProperty("subject_id")
    private String subjectId = "";

    @JsonProperty("normalized_key")
    private String normalizedKey = "";

    @JsonProperty("topic")
    private String topic = "";

    /**
     * 同一个主题以别的说法到达时的那些说法。被要求给主题命名的模型不会两次给出同一个名字，
     * 所以用户看到的标签是规范的那个，而所有归于它的表层形式都留在这里——
     * 既是审计轨迹，也是一个精确匹配索引，省得解析器对同一个问题反复裁决。
     */
    @TableField(value = "aliases", typeHandler = MemoryStringListTypeHandler.class,
            insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    @JsonProperty("aliases")
    private List<String> aliases;

    @JsonProperty("hits")
    private int hits;

    @JsonProperty("last_seen_at")
    private OffsetDateTime lastSeenAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    @JsonProperty("promoted_at")
    private OffsetDateTime promotedAt;

    @JsonProperty("created_at")
    private OffsetDateTime createdAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v == null ? 0L : v; }

    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { subjectId = v == null ? "" : v; }

    public String getNormalizedKey() { return normalizedKey; }
    public void setNormalizedKey(String v) { normalizedKey = v == null ? "" : v; }

    public String getTopic() { return topic; }
    public void setTopic(String v) { topic = v == null ? "" : v; }

    /**
     * {@code null} 是 Go 里的合法状态（响应输出 {@code null}），**不**归一成空列表——
     * 归一化只发生在投影函数 {@link MemoryTopicView#fromStat} 里。
     */
    public List<String> getAliases() { return aliases; }
    public void setAliases(List<String> v) {
        aliases = v == null ? null : new ArrayList<>(v);
    }

    public int getHits() { return hits; }
    public void setHits(int v) { hits = v; }

    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime v) {
        lastSeenAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public OffsetDateTime getPromotedAt() { return promotedAt; }
    public void setPromotedAt(OffsetDateTime v) { promotedAt = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) {
        createdAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) {
        updatedAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    /**
     * 对照 Go {@code MemoryTopicAliases.Has}：某个表层说法是否已经归到这个主题。
     *
     * <p><b>它不是字段</b>（Go 里是方法）。方法名是 {@code hasAlias} 而不是
     * {@code isAlias}/{@code getAlias}，所以 Jackson 不会把它当属性——
     * 这正是 §7.5 第 2 条那个坑的规避方式：**别给它起 get/is 前缀的名字**。
     * 它也不落库（没有对应列）。</p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean hasAlias(String surface) {
        String target = MemoryKeys.normalizeTopicKey(surface);
        if (target == null || target.isEmpty()) {
            return false;
        }
        if (aliases == null) {
            return false;
        }
        for (String alias : aliases) {
            if (target.equals(MemoryKeys.normalizeTopicKey(alias))) {
                return true;
            }
        }
        return false;
    }
}
