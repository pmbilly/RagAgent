package com.ragagent.memory.domain;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * {@code memory_subjects.extraction_state} 这一列的内容——**只装主体级的 worker 租约**
 * （对照 Go {@code types.MemoryExtractionState}，internal/types/memory_extraction.go L46-70）。
 * 游标与排队的工作都在 {@link MemoryExtractionSession} 里。
 *
 * <h2>⚠️ 两个 omitempty 的效果不同（Go 实测，见 GoTruth 语料）</h2>
 * <pre>
 *   MemoryExtractionState{}                          → {"lease_until":"0001-01-01T00:00:00Z"}
 *   MemoryExtractionState{LeaseID:"L", LeaseUntil:t} → {"lease_id":"L","lease_until":"…"}
 * </pre>
 * <ul>
 *   <li>{@code lease_id} 是 string + omitempty → 空串**省略整个键**；</li>
 *   <li>{@code lease_until} 是 {@code time.Time} + omitempty → <b>永远在</b>。
 *       Go 的 {@code encoding/json} 判 omitempty 时 struct 值不算"空"，
 *       所以零值时间照样输出 year-1 字面量。<b>别看到 omitempty 就加 NON_NULL</b>。</li>
 * </ul>
 *
 * <h2>⚠️ 时间字段必须挂成对的序列化器</h2>
 * <p>本类型走的是 **jsonb 读路径**——处理器用的是裸 {@code JsonMappers.lenient()}，
 * 没有 {@code JavaTimeModule}（§9「jsonb 读路径的 mapper 没有 JavaTimeModule」）。
 * 只挂 {@code @JsonSerialize} 会让**写**对、**读**炸（{@code InvalidDefinitionException}）。
 * 挂上成对的两件套后两个方向自足，不依赖任何全局 mapper 配置——这就是
 * {@code AgentStep.timestamp} 那个模子。</p>
 *
 * <p>另外字段默认值必须是 {@link GoTimeSerializer#GO_ZERO_DATE_TIME}：
 * {@code null} 不会走自定义序列化器（Jackson 对 null 值用 nullSerializer），
 * 想让落库字节是 year-1 就必须让字段本身就持有零值时间。</p>
 */
@JsonPropertyOrder({"lease_id", "lease_until"})
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryExtractionState {

    /** 空串时**省略键**（Go 的 {@code omitempty}）。 */
    @JsonProperty("lease_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String leaseId = "";

    /** 零值是 Go 的 year-1 字面量，且**恒输出**（见类注释）。 */
    @JsonProperty("lease_until")
    private OffsetDateTime leaseUntil = GoTimeSerializer.GO_ZERO_DATE_TIME;

    public MemoryExtractionState() {
    }

    public String getLeaseId() {
        return leaseId;
    }

    public void setLeaseId(String v) {
        this.leaseId = v == null ? "" : v;
    }

    public OffsetDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(OffsetDateTime v) {
        this.leaseUntil = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    /** 对照 Go 的 {@code s.LeaseUntil.After(now)}。 */
    public boolean leaseUntilAfter(OffsetDateTime now) {
        return leaseUntil != null && leaseUntil.isAfter(now);
    }
}
