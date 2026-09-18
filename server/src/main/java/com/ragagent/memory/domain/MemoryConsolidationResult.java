package com.ragagent.memory.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 一次"整仓回顾"做了什么（对照 Go {@code types.MemoryConsolidationResult}，
 * internal/types/memory.go:1016-1026）。
 *
 * <p>零值表示**仓库本来就整洁**，不是回顾失败——失败与否由 {@link #skipped} 说明。
 * 这个区分是有意的：一次什么都没合并的回顾是常态，"什么都没发生"本身
 * 既不能告诉提问的人它有没有干活，也不能告诉他值不值得再点一次。</p>
 *
 * <p>前五个计数无 omitempty 恒输出；{@code skipped} 带 omitempty（空串省略）。</p>
 */
@JsonPropertyOrder({"merged", "demoted", "expired", "reviewed", "candidates", "skipped"})
public class MemoryConsolidationResult {

    // ── skipped 的取值（对照 Go 的 MemoryConsolidationSkip* 常量） ──────────

    /** 记忆太少，不可能已经漂移出矛盾——不值得花一次模型调用。 */
    public static final String SKIP_TOO_FEW_ITEMS = "too_few_items";
    /** 没有哪两条看起来足够接近，不值得问模型。 */
    public static final String SKIP_NO_CANDIDATES = "no_candidates";
    /** 有候选，但判定"说的是不是同一件事"的模型够不到。 */
    public static final String SKIP_MODEL_UNAVAILABLE = "model_unavailable";
    /** 模型看过了，说这些记录确实是不同的事。 */
    public static final String SKIP_MODEL_DECLINED = "model_declined";
    /**
     * 这个人刚刚才请求过回顾。
     * <b>只有人主动请求的回顾会报这个</b>；每日任务有自己的、长得多的间隔，默默跳过。
     */
    public static final String SKIP_TOO_SOON = "too_soon";

    @JsonProperty("merged")
    private int merged;

    @JsonProperty("demoted")
    private int demoted;

    @JsonProperty("expired")
    private int expired;

    /** 这次回顾看了多少条活跃记忆。 */
    @JsonProperty("reviewed")
    private int reviewed;

    /** 有多少组候选被摆到了模型面前。 */
    @JsonProperty("candidates")
    private int candidates;

    /** 为什么什么都没合并；真合并了就是空。omitempty。 */
    @JsonProperty("skipped")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String skipped;

    public int getMerged() { return merged; }
    public void setMerged(int v) { merged = v; }

    public int getDemoted() { return demoted; }
    public void setDemoted(int v) { demoted = v; }

    public int getExpired() { return expired; }
    public void setExpired(int v) { expired = v; }

    public int getReviewed() { return reviewed; }
    public void setReviewed(int v) { reviewed = v; }

    public int getCandidates() { return candidates; }
    public void setCandidates(int v) { candidates = v; }

    public String getSkipped() { return skipped; }
    public void setSkipped(String v) { skipped = v; }
}
