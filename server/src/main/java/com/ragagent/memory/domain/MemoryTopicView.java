package com.ragagent.memory.domain;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeDeserializer;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 记忆管理器里展示的"话题计数"形状（对照 Go {@code types.MemoryTopicView}，
 * internal/types/memory.go:1031-1038）。
 *
 * <p><b>租户与 subject 刻意不上线</b>：这一行本来就只属于调用方，
 * 那些 id 不是 UI 该去忽略的东西。</p>
 *
 * <p>{@code aliases} 无 omitempty：nil 输出 {@code null}（不是 {@code []}）。
 * 投影函数 {@code MemoryTopicViewFromStat} 会在 nil 时补空列表——
 * 也就是说**存量行的 nil 与投影后的空列表在线上是两种形态**，照抄别统一。</p>
 */
@JsonPropertyOrder({"id", "topic", "aliases", "hits", "threshold", "last_seen_at"})
public class MemoryTopicView {

    @JsonProperty("id")
    private String id = "";

    @JsonProperty("topic")
    private String topic = "";

    /** 无 omitempty：nil → {@code null}。 */
    @JsonProperty("aliases")
    private List<String> aliases;

    @JsonProperty("hits")
    private int hits;

    /** 当前生效的兴趣阈值（来自 {@link MemoryConfig}，不是行上的字段）。 */
    @JsonProperty("threshold")
    private int threshold;

    @JsonProperty("last_seen_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime lastSeenAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }

    public String getTopic() { return topic; }
    public void setTopic(String v) { topic = v == null ? "" : v; }

    public List<String> getAliases() { return aliases; }
    public void setAliases(List<String> v) { aliases = v; }

    public int getHits() { return hits; }
    public void setHits(int v) { hits = v; }

    public int getThreshold() { return threshold; }
    public void setThreshold(int v) { threshold = v; }

    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime v) {
        lastSeenAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }
}
