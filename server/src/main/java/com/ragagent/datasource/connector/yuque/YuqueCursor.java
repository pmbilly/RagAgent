package com.ragagent.datasource.connector.yuque;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeDeserializer;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 语雀的增量同步状态（对照 Go {@code yuque.yuqueCursor}，
 * yuque/types.go L209-214）。装进
 * {@link com.ragagent.datasource.domain.SyncCursor#getConnectorCursor()}、
 * 最终落在 {@code data_sources.last_sync_cursor} 这个 jsonb 列里。
 *
 * <p>形状是 {@code last_sync_time} + {@code book_doc_times}：
 * 外层键是 book_id（字符串），内层键是 doc_id（字符串），值是**原始的**
 * {@code content_updated_at} RFC3339 字符串（不做时间解析——变更检测就是
 * 字符串相等比较，这样连"语雀改了时间格式"都不会误判为全员变更）。</p>
 *
 * <h2>内部形状</h2>
 * <p>不直接作响应体；JSON 形态只出现在 {@code last_sync_cursor} 列里，与 Go 逐键一致。</p>
 */
public class YuqueCursor {

    /** 与 Go 的 {@code json.Unmarshal} 对齐：容忍未知属性（游标承载历史数据）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @JsonProperty("last_sync_time")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime lastSyncTime = GoTimeSerializer.GO_ZERO_DATE_TIME;

    /** {@code { book_id: { doc_id: content_updated_at } }}；omitempty → 空时整键消失。 */
    @JsonProperty("book_doc_times")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Map<String, String>> bookDocTimes;

    public OffsetDateTime getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(OffsetDateTime v) {
        lastSyncTime = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public Map<String, Map<String, String>> getBookDocTimes() {
        return bookDocTimes;
    }

    public void setBookDocTimes(Map<String, Map<String, String>> v) {
        bookDocTimes = v;
    }

    /**
     * 对照 Go 里 {@code json.Marshal(newCursor)} + {@code json.Unmarshal(..., &cursorMap)}
     * 那两行：把有类型的游标摊成扁平 map。
     *
     * <p>净效果（已在 {@code YuqueCursorTest} 钉住）：{@code last_sync_time} 是
     * <b>RFC3339 字符串</b>（roundtrip 把 {@code time.Time} 变成 {@code string}），
     * {@code book_doc_times} 是嵌套字符串 map；后者为空时整个键不出现。</p>
     */
    public Map<String, Object> toConnectorCursor() {
        @SuppressWarnings("unchecked")
        Map<String, Object> out = MAPPER.convertValue(this, Map.class);
        return out == null ? new LinkedHashMap<>() : out;
    }

    /**
     * 对照 Go 的 {@code json.Marshal(cursor.ConnectorCursor)} +
     * {@code json.Unmarshal(..., &p)}。
     *
     * <p><b>与 Go 的一处已知差异</b>：Go 用 {@code _ = json.Unmarshal(...)} 忽略错误，
     * 形状不对的游标会留下**部分填充**的结构；Java 侧解析失败回 {@code null}
     * （等价于没有上一轮游标 → 退化成首次同步，只会多抓一次，不会漏数据）。</p>
     */
    public static YuqueCursor fromConnectorCursor(Map<String, Object> connectorCursor) {
        if (connectorCursor == null) {
            return null;
        }
        try {
            return MAPPER.convertValue(connectorCursor, YuqueCursor.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
