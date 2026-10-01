package com.ragagent.memory.domain;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * {@code memory_subjects.extraction_state} 这一列的内容——**只装主体级的 worker 租约**
 * （对照 Go {@code types.MemoryExtractionState}，internal/types/memory_extraction.go L46-70）。
 * 游标与排队的工作都在 {@link MemoryExtractionSession} 里。
 *
 * <h2>JSON 形态（§14.9k M2 换锚后：键名＝Java 字段名，两个键都恒输出）</h2>
 * <pre>
 *   MemoryExtractionState{}                          → {"leaseId":"","leaseUntil":"0001-01-01T00:00:00Z"}
 *   MemoryExtractionState{LeaseID:"L", LeaseUntil:t} → {"leaseId":"L","leaseUntil":"…"}
 * </pre>
 * <p>Go 的 {@code lease_id,omitempty} 随契约 §1.6「禁止条件键」退役——空串照样写出 {@code ""}；
 * {@code leaseUntil} 本来就是恒输出（Go 判 omitempty 时 {@code time.Time} 值不算"空"，
 * 零值时间照样写字面量）。</p>
 *
 * <h2>⚠️ 存量行要跑迁移：这里的读路径是宽松的</h2>
 * <p>改名前写进这一列的是 {@code {"lease_id":…,"lease_until":…}}。读的人
 * （{@link MemoryExtractionStateTypeHandler}）忽略未知字段，所以旧键会被**静默丢弃**、
 * 拿到一个零值租约——不报错，但"别人正持有租约"会变成"没人持有"。改名前写下的行
 * 必须跑迁移 SQL（HANDOFF §14.9k M2）。</p>
 *
 * <h2>时间字段为什么不需要自定义序列化器</h2>
 * <p>本类型走 jsonb 读写路径，解析器是 {@code JsonMappers.lenient()}——它**带**
 * {@code JavaTimeModule}（注意：不是裸 mapper），{@link java.time.OffsetDateTime} 按
 * ISO-8601 输出，零值正好是 Go 的 {@code "0001-01-01T00:00:00Z"} 字面量。</p>
 *
 * <p>字段默认值必须是 {@link GoTimeSerializer#GO_ZERO_DATE_TIME}：
 * {@code null} 不会走自定义序列化器（Jackson 对 null 值用 nullSerializer），
 * 想让落库字节是 year-1 就必须让字段本身就持有零值时间。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryExtractionState {

    /** 空串时**省略键**（Go 的 {@code omitempty}）。 */
    private String leaseId = "";

    /** 零值是 Go 的 year-1 字面量，且**恒输出**（见类注释）。 */
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
