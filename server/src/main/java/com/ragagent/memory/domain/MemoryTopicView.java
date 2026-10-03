package com.ragagent.memory.domain;

import com.ragagent.common.settings.MemoryConfig;
import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.common.web.GoTimeSerializer;

/**
 * 记忆管理器里展示的"话题计数"形状。
 *
 * <p><b>租户与 subject 刻意不上线</b>：这一行本来就只属于调用方，
 * 那些 id 不是 UI 该去忽略的东西。</p>
 *
 * <p>{@code aliases} 无 omitempty：nil 输出 {@code null}（不是 {@code []}）。
 * 投影函数 {@code MemoryTopicViewFromStat} 会在 nil 时补空列表——
 * 也就是说**存量行的 null 与投影后的空列表在线上是两种形态**，别统一。</p>
 */
public class MemoryTopicView {

    private String id = "";

    private String topic = "";

    /** 无 omitempty：nil → {@code null}。 */
    private List<String> aliases;

    private int hits;

    /** 当前生效的兴趣阈值（来自 {@link MemoryConfig}，不是行上的字段）。 */
    private int threshold;

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

    /**
     * 把存下来的计数器投影成管理器形状。
     *
     * <p>{@code threshold} **不是行上的字段**，由调用方（service 层的
     * {@code cfg.effectiveInterestThreshold()}）传进来——这正是本响应类型
     * 无法由实体直接序列化得到的原因。</p>
     *
     * <p>⚠️ 这里的 null → 空列表是**投影函数的**行为：{@code stat.aliases} 为 null 时
     * 输出 {@code []}。而<b>不经投影</b>的 {@link MemoryTopicView}（例如手工 new 出来的）
     * 的 aliases 是 {@code null}。两种形态在线上并存，别统一。</p>
     *
     * @return {@code stat} 为 null 时回 null
     */
    public static MemoryTopicView fromStat(MemoryTopicStat stat, int threshold) {
        if (stat == null) {
            return null;
        }
        MemoryTopicView view = new MemoryTopicView();
        view.setId(stat.getId());
        view.setTopic(stat.getTopic());
        view.setAliases(stat.getAliases() == null
                ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(stat.getAliases()));
        view.setHits(stat.getHits());
        view.setThreshold(threshold);
        view.setLastSeenAt(stat.getLastSeenAt());
        return view;
    }
}
