package com.ragagent.datasource.connector.rss;

import com.ragagent.common.web.JsonMappers;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.web.ZeroTimeSerializer;

/**
 * RSS 连接器的增量同步状态。
 *
 * <h2>⚠️ 这是跨数据的存储契约</h2>
 * <p>它被序列化成一张 map 塞进
 * {@link com.ragagent.datasource.domain.SyncCursor#getConnectorCursor()}，最终落
 * {@code data_sources.last_sync_cursor} 这个 jsonb 列。JSON 键名
 * （{@code last_sync_time} / {@code feed_items} / {@code feed_signals}）
 * 必须与既有数据逐字一致，新旧数据要能互读。</p>
 *
 * <h2>序列化形状（{@code RssCursorJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   {lastSyncTime: t}                     → {"last_sync_time":"2006-01-02T15:04:05Z"}
 *   （feedItems/feedSignals 都是空 map）    → 同上（两个键被省略）
 *   {feedItems:{"f":{"g":"h:1"}}, ...}    → {"last_sync_time":...,
 *                                            "feed_items":{"f":{"g":"h:1"}},
 *                                            "feed_signals":{...}}
 * </pre>
 * <p>要点：{@code last_sync_time} 恒输出（零值也输出字面量）；
 * 另两个键对 map 看 {@code size}，<b>空 map 整个键消失</b>，
 * 而"有一个键、值是空 map"时那个键<b>保留</b>（{@code {"z":{}}} 会输出）。</p>
 *
 * <h2>落库行为清单</h2>
 * <ol>
 *   <li><b>钩子 / 软删除 / 自动时间戳 / 唯一索引 / 关联预加载 / 默认排序</b>：全无——
 *       本类型不落表，只是 jsonb 载荷里的一块。</li>
 * </ol>
 */
@JsonPropertyOrder({"last_sync_time", "feed_items", "feed_signals"})
public class RssCursor {

    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 本次同步的时间（UTC）。无 omitempty → 恒输出。 */
    @JsonProperty("last_sync_time")
    private OffsetDateTime lastSyncTime = ZeroTimeSerializer.ZERO_DATE_TIME;

    /**
     * {@code feedURL → itemID → 内容指纹}（{@code "h:<sha256 前 16 位十六进制>"}）。
     * omitempty → {@code null} 或空 map 时整个键消失。
     */
    @JsonProperty("feed_items")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Map<String, String>> feedItems;

    /**
     * {@code feedURL → itemID → feed 信号指纹}（{@code "s:<sha256 前 16 位十六进制>"}）。
     * 与 {@link #feedItems} 的区别：它是<b>只看 feed、不抓文章页</b>就能算出来的，
     * 用来在增量同步时省掉整页抓取。
     */
    @JsonProperty("feed_signals")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Map<String, String>> feedSignals;

    public OffsetDateTime getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(OffsetDateTime v) {
        lastSyncTime = v == null ? ZeroTimeSerializer.ZERO_DATE_TIME : v;
    }

    public Map<String, Map<String, String>> getFeedItems() {
        return feedItems;
    }

    public void setFeedItems(Map<String, Map<String, String>> v) {
        feedItems = v;
    }

    public Map<String, Map<String, String>> getFeedSignals() {
        return feedSignals;
    }

    public void setFeedSignals(Map<String, Map<String, String>> v) {
        feedSignals = v;
    }

    // ── 便捷方法 ───────────────────────────────────────────────────────────

    /**
     * 保证两级 map 都在，返回该 feed 的条目指纹桶。
     */
    Map<String, String> bucketForItems(String feedUrl) {
        if (feedItems == null) {
            feedItems = new LinkedHashMap<>();
        }
        return feedItems.computeIfAbsent(feedUrl, k -> new LinkedHashMap<>());
    }

    /** 见 {@link #bucketForItems(String)}。 */
    Map<String, String> bucketForSignals(String feedUrl) {
        if (feedSignals == null) {
            feedSignals = new LinkedHashMap<>();
        }
        return feedSignals.computeIfAbsent(feedUrl, k -> new LinkedHashMap<>());
    }

    /**
     * 序列化成扁平 map —— 供
     * 增量同步塞进 {@code SyncCursor.connectorCursor}。
     */
    Map<String, Object> toMap() {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = MAPPER.convertValue(this, Map.class);
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }

    /**
     * 把上一轮的 {@code connector_cursor} 读回来。
     *
     * <p>用<b>容忍未知属性</b>的 mapper：老版本写下的游标在新增字段后仍能读出来。</p>
     */
    static RssCursor fromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        return MAPPER.convertValue(map, RssCursor.class);
    }
}
