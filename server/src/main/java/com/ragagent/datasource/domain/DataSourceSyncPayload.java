package com.ragagent.datasource.domain;

import com.ragagent.common.web.JsonMappers;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 一次数据源同步任务的载荷（对照 Go {@code types.DataSourceSyncPayload}，
 * internal/types/datasource.go L495-516）。
 *
 * <h2>JSON 形状（§14.9q D3；{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   7 参构造（无追踪）→
 *   {"initiator":{"userId":"","role":""},"trigger":"","dataSourceId":"","tenantId":0,
 *    "syncLogId":"","forceFull":false,"maxItems":0}
 *   全字段 + 追踪载体 →
 *   {"initiator":{"userId":"user-1","role":"admin"},"trigger":"manual","dataSourceId":"d1",
 *    "tenantId":7,"syncLogId":"l1","forceFull":true,"maxItems":10,
 *    "lf_trace_id":"tr","lf_parent_obs_id":"po","lf_traceparent":"tp","lf_user_id":"u",
 *    "lf_session_id":"s"}
 * </pre>
 * <p>两个容易写错的点：</p>
 * <ol>
 *   <li><b>{@code initiator} 永远在</b>：Go 对它写了 {@code omitempty}，
 *       但它的类型是**结构体**（不是指针）——{@code encoding/json} 的 omitempty
 *       对 struct 一律无效，所以空发起人输出的是 {@code "initiator":{}}。
 *       Java 侧对应 {@code NON_NULL}（而不是 {@code NON_EMPTY}）：即便值为
 *       {@link TaskInitiator#empty()} 也要输出。</li>
 *   <li><b>§1.6</b>：自有键全部恒输出（{@code forceFull} false 照写、{@code trigger} 空串照写、
 *       {@code maxItems} 0 照写）；只有 {@code lf_*} 平铺键保持"空值整键省略"的载具口径。</li>
 * </ol>
 *
 * <h2>langfuse 追踪载体（2026-09-24 C 批接线）</h2>
 * <p>追踪载体（{@code lf_*} 五键）直接平铺在 record 上（{@code @JsonUnwrapped} 不支持
 * record 的 Creator 参数），空值整键省略。载荷只在进程内队列里流动，不落库、不出响应。</p>
 *
 * <p><b>⚠️ {@code lf_*} 五键冻结</b>（§14.9q D3 判定）：它们是<b>平铺载具的命名空间前缀</b>
 * ——{@code TracingContext}（{@code common/context}）被平铺进本载荷与 memory / wiki / knowledge
 * 三个兄弟载荷，去掉 {@code lf_} 前缀就会与载荷自有字段撞名（如 {@code userId}、{@code sessionId}）。
 * 四域 + 共享记录是同一个形状，改名要一起动且失去命名空间保护；确需清理时应改成"嵌套一个
 * {@code tracing} 键"（形状变更，另批），而不是去掉前缀。除这五键外，本类的键名＝组件名（§1.6）。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子/软删除/自动时间戳/唯一索引/关联预加载/默认排序</b>：全无——
 *       本类型不落表。</li>
 * </ol>
 */
public record DataSourceSyncPayload(
        /**
         * 发起人。**恒输出**（对照 Go 的 struct + 无效 omitempty）——
         * 所以这里用 NON_NULL，而不是 NON_EMPTY。
         */
        /** 发起人。§1.6：恒输出（nil → {@code null}）。 */
        TaskInitiator initiator,
        /** 区分"用户手动触发"与"调度器创建"。§1.6：空串照写。 */
        String trigger,
        String dataSourceId,
        long tenantId,
        /** 用于追踪同步日志。 */
        String syncLogId,
        /** 即便配了增量模式也强制全量。§1.6：false 恒输出。 */
        boolean forceFull,
        /** 最多抓取多少条（0 = 不限）。§1.6：0 照写。 */
        int maxItems,
        /** 追踪载体五键（平铺成 {@code lf_*}；空值整键省略）。 */
        @JsonProperty("lf_trace_id")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfTraceId,
        @JsonProperty("lf_parent_obs_id")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfParentObsId,
        @JsonProperty("lf_traceparent")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfTraceparent,
        @JsonProperty("lf_user_id")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfUserId,
        @JsonProperty("lf_session_id")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfSessionId) {

    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 紧凑构造器：字符串归一成空串，让 NON_EMPTY 对 null 与 "" 表现一致。 */
    public DataSourceSyncPayload {
        trigger = trigger == null ? "" : trigger;
        dataSourceId = dataSourceId == null ? "" : dataSourceId;
        syncLogId = syncLogId == null ? "" : syncLogId;
        lfTraceId = lfTraceId == null ? "" : lfTraceId;
        lfParentObsId = lfParentObsId == null ? "" : lfParentObsId;
        lfTraceparent = lfTraceparent == null ? "" : lfTraceparent;
        lfUserId = lfUserId == null ? "" : lfUserId;
        lfSessionId = lfSessionId == null ? "" : lfSessionId;
        // Go 的零值是 TaskInitiator{} 而不是 nil；这里把 null 归一成它，
        // 保证 "initiator 恒输出" 这条在任何构造路径上都成立。
        initiator = initiator == null ? TaskInitiator.empty() : initiator;
    }

    /** 兼容构造：不带追踪载体（等价于未启用追踪的入队点）。 */
    public DataSourceSyncPayload(TaskInitiator initiator, String trigger, String dataSourceId,
                                 long tenantId, String syncLogId, boolean forceFull, int maxItems) {
        this(initiator, trigger, dataSourceId, tenantId, syncLogId, forceFull, maxItems,
                "", "", "", "", "");
    }

    /** 带追踪载体的构造（入队侧用；载体为空时与兼容构造等价）。 */
    public static DataSourceSyncPayload withTracing(TaskInitiator initiator, String trigger,
                                                    String dataSourceId, long tenantId,
                                                    String syncLogId, boolean forceFull, int maxItems,
                                                    com.ragagent.common.context.TracingContext tracing) {
        com.ragagent.common.context.TracingContext tc = tracing == null
                ? com.ragagent.common.context.TracingContext.EMPTY : tracing;
        return new DataSourceSyncPayload(initiator, trigger, dataSourceId, tenantId, syncLogId,
                forceFull, maxItems, tc.traceId(), tc.parentObservationId(), tc.traceparent(),
                tc.userId(), tc.sessionId());
    }

    /** 追踪载体的结构视图（worker 侧续接用）。 */
    public com.ragagent.common.context.TracingContext tracing() {
        return new com.ragagent.common.context.TracingContext(
                lfTraceId, lfParentObsId, lfTraceparent, lfUserId, lfSessionId);
    }

    /** 对照 Go 的 {@code json.Marshal(payload)}：载荷以 JSON 形态进队列。 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("marshal data source sync payload failed", e);
        }
    }

    /** 对照 Go 的 {@code json.Unmarshal(task.Payload(), &payload)}。 */
    public static DataSourceSyncPayload fromJson(String json) {
        try {
            return MAPPER.readValue(json, DataSourceSyncPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "unmarshal data source sync payload: " + e.getMessage(), e);
        }
    }
}
