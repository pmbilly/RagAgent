package com.ragagent.datasource.domain;

import com.ragagent.common.web.JsonMappers;
import com.ragagent.common.context.TracingContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 一次数据源同步任务的载荷。
 *
 * <h2>JSON 形状（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   7 参构造（无追踪）→
 *   {"initiator":{"userId":"","role":""},"trigger":"","dataSourceId":"","tenantId":0,
 *    "syncLogId":"","forceFull":false,"maxItems":0}
 *   全字段 + 追踪载体 →
 *   {"initiator":{"userId":"user-1","role":"admin"},"trigger":"manual","dataSourceId":"d1",
 *    "tenantId":7,"syncLogId":"l1","forceFull":true,"maxItems":10,
 *    "tracing":{"lf_trace_id":"tr","lf_parent_obs_id":"po","lf_traceparent":"tp",
 *    "lf_user_id":"u","lf_session_id":"s"}}
 * </pre>
 * <p>两个容易写错的点：</p>
 * <ol>
 *   <li><b>{@code initiator} 永远在</b>：空发起人输出的也是 {@code "initiator":{}}，
 *       不是键省略。构造路径上 {@code null} 一律归一成空发起人。</li>
 *   <li>自有键全部恒输出（{@code forceFull} false 照写、{@code trigger} 空串照写、
 *       {@code maxItems} 0 照写）；只有追踪载体保持"空值整键省略"的口径（嵌套 {@code tracing} 键）。</li>
 * </ol>
 *
 * <h2>langfuse 追踪载体</h2>
 * <p>追踪载体（{@code TracingContext}）以嵌套键 {@code tracing} 随负载携带
 * （其内部是五个 {@code lf_*} 键）；空载体整键省略。
 * 载荷只在进程内队列里流动，不落库、不出响应。</p>
 *
 * <p><b>⚠️ 追踪载体命名空间</b>：{@code lf_*} 前缀属于
 * <b>{@code TracingContext} 自身</b>的键（{@code common/context}）；本载荷与 memory / wiki / knowledge
 * 等兄弟载荷共用同一形状，去掉 {@code lf_} 前缀会与载荷自有字段撞名（如 {@code userId}、{@code sessionId}）。
 * 改名要一起动且失去命名空间保护。除这五键外，本类的键名＝组件名。</p>
 *
 * <h2>持久化语义</h2>
 * <ol>
 *   <li><b>钩子/软删除/自动时间戳/唯一索引/关联预加载/默认排序</b>：全无——
 *       本类型不落表。</li>
 * </ol>
 */
public record DataSourceSyncPayload(
        /**
         * 发起人。恒输出——{@code null} 归一成空发起人，
         * 空发起人输出 {@code "initiator":{}}。
         */
        TaskInitiator initiator,
        /** 区分"用户手动触发"与"调度器创建"。空串照写。 */
        String trigger,
        String dataSourceId,
        long tenantId,
        /** 用于追踪同步日志。 */
        String syncLogId,
        /** 即便配了增量模式也强制全量。false 恒输出。 */
        boolean forceFull,
        /** 最多抓取多少条（0 = 不限）。0 照写。 */
        int maxItems,
        /**
         * 观测载体（嵌套键 {@code tracing}，五个 {@code lf_*} 组件收在里面）。
         * 空载体经 {@code EmptyOmitFilter} 整键省略。
         */
        @JsonProperty("tracing")
        @JsonInclude(value = JsonInclude.Include.CUSTOM,
                valueFilter = TracingContext.EmptyOmitFilter.class) TracingContext tracing) {

    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 紧凑构造器：字符串归一成空串，让 NON_EMPTY 对 null 与 "" 表现一致。 */
    public DataSourceSyncPayload {
        trigger = trigger == null ? "" : trigger;
        dataSourceId = dataSourceId == null ? "" : dataSourceId;
        syncLogId = syncLogId == null ? "" : syncLogId;
        // 把 null 归一成空发起人，
        // 保证 "initiator 恒输出" 这条在任何构造路径上都成立。
        initiator = initiator == null ? TaskInitiator.empty() : initiator;
    }

    /** 兼容构造：不带追踪载体（等价于未启用追踪的入队点）。 */
    public DataSourceSyncPayload(TaskInitiator initiator, String trigger, String dataSourceId,
                                 long tenantId, String syncLogId, boolean forceFull, int maxItems) {
        this(initiator, trigger, dataSourceId, tenantId, syncLogId, forceFull, maxItems, null);
    }

    /** 带追踪载体的构造（入队侧用；载体为空时与兼容构造等价）。 */
    public static DataSourceSyncPayload withTracing(TaskInitiator initiator, String trigger,
                                                    String dataSourceId, long tenantId,
                                                    String syncLogId, boolean forceFull, int maxItems,
                                                    com.ragagent.common.context.TracingContext tracing) {
        com.ragagent.common.context.TracingContext tc = tracing == null
                ? com.ragagent.common.context.TracingContext.EMPTY : tracing;
        return new DataSourceSyncPayload(initiator, trigger, dataSourceId, tenantId, syncLogId,
                forceFull, maxItems, tc.isEmpty() ? null : tc);
    }

    /** 追踪载体的结构视图（worker 侧续接用）。 */
    public com.ragagent.common.context.TracingContext tracing() {
        return tracing == null ? com.ragagent.common.context.TracingContext.EMPTY : tracing;
    }

    /** 序列化：载荷以 JSON 形态进队列。 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("marshal data source sync payload failed", e);
        }
    }

    /** 反序列化队列里的载荷。 */
    public static DataSourceSyncPayload fromJson(String json) {
        try {
            return MAPPER.readValue(json, DataSourceSyncPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "unmarshal data source sync payload: " + e.getMessage(), e);
        }
    }
}
