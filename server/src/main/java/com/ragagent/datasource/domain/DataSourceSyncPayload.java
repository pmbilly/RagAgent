package com.ragagent.datasource.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 一次数据源同步任务的载荷（对照 Go {@code types.DataSourceSyncPayload}，
 * internal/types/datasource.go L495-516）。
 *
 * <h2>Go 实录（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   DataSourceSyncPayload{} →
 *   {"initiator":{},"data_source_id":"","tenant_id":0,"sync_log_id":"","force_full":false}
 *   DataSourceSyncPayload(全字段) →
 *   {"lf_trace_id":"tr","lf_parent_obs_id":"po","lf_traceparent":"tp","lf_user_id":"u",
 *    "lf_session_id":"s","initiator":{"user_id":"user-1","role":"admin"},"trigger":"manual",
 *    "data_source_id":"d1","tenant_id":7,"sync_log_id":"l1","force_full":true,"max_items":10}
 * </pre>
 * <p>两个容易写错的点：</p>
 * <ol>
 *   <li><b>{@code initiator} 永远在</b>：Go 对它写了 {@code omitempty}，
 *       但它的类型是**结构体**（不是指针）——{@code encoding/json} 的 omitempty
 *       对 struct 一律无效，所以空发起人输出的是 {@code "initiator":{}}。
 *       Java 侧对应 {@code NON_NULL}（而不是 {@code NON_EMPTY}）：即便值为
 *       {@link TaskInitiator#empty()} 也要输出。</li>
 *   <li><b>{@code force_full} 没有 omitempty</b> → false 恒输出；
 *       {@code trigger} / {@code max_items} 有 omitempty → 空串 / 0 省略。</li>
 * </ol>
 *
 * <h2>⚠️ 与 Go 的一处已知差异：未内嵌 {@code TracingContext}</h2>
 * <p>Go 内嵌 {@code types.TracingContext}（langfuse 的 {@code lf_*} 五个字段）。
 * Java 侧未实现 langfuse 追踪（§9 阶段 4.0 已知差异 1），故按
 * {@code WikiIngestPayload} / {@code MemoryExtractPayload} 的既有处置去掉它们——
 * 等价于 Go 在**未启用追踪**时的载荷（五个字段都是 omitempty，空时不输出）。
 * 这不改变任何外部可见行为：载荷只在进程内队列里流动，不落库、不出响应。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子/软删除/自动时间戳/唯一索引/关联预加载/默认排序</b>：全无——
 *       本类型不落表。</li>
 * </ol>
 */
@JsonPropertyOrder({"initiator", "trigger", "data_source_id", "tenant_id", "sync_log_id",
        "force_full", "max_items"})
public record DataSourceSyncPayload(
        /**
         * 发起人。**恒输出**（对照 Go 的 struct + 无效 omitempty）——
         * 所以这里用 NON_NULL，而不是 NON_EMPTY。
         */
        @JsonProperty("initiator") @JsonInclude(JsonInclude.Include.NON_NULL) TaskInitiator initiator,
        /** 区分"用户手动触发"与"调度器创建"。omitempty → 空串省略。 */
        @JsonProperty("trigger") @JsonInclude(JsonInclude.Include.NON_EMPTY) String trigger,
        @JsonProperty("data_source_id") String dataSourceId,
        @JsonProperty("tenant_id") long tenantId,
        /** 用于追踪同步日志。 */
        @JsonProperty("sync_log_id") String syncLogId,
        /** 即便配了增量模式也强制全量。无 omitempty → false 恒输出。 */
        @JsonProperty("force_full") boolean forceFull,
        /** 最多抓取多少条（0 = 不限）。omitempty → 0 省略。 */
        @JsonProperty("max_items") @JsonInclude(JsonInclude.Include.NON_DEFAULT) int maxItems) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 紧凑构造器：字符串归一成空串，让 NON_EMPTY 对 null 与 "" 表现一致。 */
    public DataSourceSyncPayload {
        trigger = trigger == null ? "" : trigger;
        dataSourceId = dataSourceId == null ? "" : dataSourceId;
        syncLogId = syncLogId == null ? "" : syncLogId;
        // Go 的零值是 TaskInitiator{} 而不是 nil；这里把 null 归一成它，
        // 保证 "initiator 恒输出" 这条在任何构造路径上都成立。
        initiator = initiator == null ? TaskInitiator.empty() : initiator;
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
