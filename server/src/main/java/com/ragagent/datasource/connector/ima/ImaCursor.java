package com.ragagent.datasource.connector.ima;

import com.ragagent.common.web.JsonMappers;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.web.ZeroTimeSerializer;

/**
 * IMA 连接器的游标载荷。它装进
 * {@link com.ragagent.datasource.domain.SyncCursor#getConnectorCursor()}，
 * 最终落在 {@code data_sources.last_sync_cursor} 这个 jsonb 列里。
 *
 * <h2>KBLogical 是删除检测与"替换检测"的唯一权威来源</h2>
 * <p>{@code { logical_key: media_id }}：IMA 在同名文件被就地替换时会重新分配
 * {@code media_id}，所以逻辑键（{@link ImaFormats#logicalKey}）才是文档的稳定身份。</p>
 * <ul>
 *   <li>上次同步在、这次不在的逻辑键 ⇒ 删除；</li>
 *   <li>逻辑键在、{@code media_id} 变了 ⇒ 替换（重新抓内容，但
 *       {@code external_id} 不变，于是 ingest 层的"已有 external_id →
 *       删除后重建"路径把它当成更新）。</li>
 * </ul>
 * <p><b>只有本次真正同步成功（或确定性跳过）的条目才被记录</b>，所以一次
 * 临时性下载失败会让该键留空、下一轮重试，而不是被永久记成"未变"。</p>
 *
 * <h2>{@code kb_media} 是遗留字段</h2>
 * <p>保留它只为让旧版本写下的游标仍能反序列化；<b>从不写入、从不读取</b>。
 * 序列化时空值不出现。</p>
 *
 * <h2>为什么要有 to/from connectorCursor</h2>
 * <p>游标要落进 {@code SyncCursor} 的扁平 connectorCursor map 里；
 * Jackson 往返保证"键的取舍"（空值省略）与
 * "值都是字符串"两个净效果稳定。</p>
 *
 * <h2>内部形状</h2>
 * <p>本类型不直接作响应体；它的 JSON 形态只在
 * {@code last_sync_cursor} 列里出现，键名与既有数据逐键一致。</p>
 */
public class ImaCursor {

    /** 容忍未知属性（游标是历史数据的容器）。 */
    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 上次同步时间（零值也输出字面量）。 */
    @JsonProperty("last_sync_time")
    private OffsetDateTime lastSyncTime = ZeroTimeSerializer.ZERO_DATE_TIME;

    /** {@code { kb_id: { logical_key: media_id } }}；omitempty → 空时整个键消失。 */
    @JsonProperty("kb_logical")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Map<String, String>> kbLogical;

    /** 遗留字段：从不写入、从不读取。 */
    @JsonProperty("kb_media")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Map<String, String>> kbMedia;

    public OffsetDateTime getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(OffsetDateTime v) {
        lastSyncTime = v == null ? ZeroTimeSerializer.ZERO_DATE_TIME : v;
    }

    public Map<String, Map<String, String>> getKbLogical() {
        return kbLogical;
    }

    public void setKbLogical(Map<String, Map<String, String>> v) {
        kbLogical = v;
    }

    public Map<String, Map<String, String>> getKbMedia() {
        return kbMedia;
    }

    public void setKbMedia(Map<String, Map<String, String>> v) {
        kbMedia = v;
    }

    /**
     * 把有类型的游标摊成扁平 map。
     *
     * <p>净效果（已在 {@code ImaCursorTest} 中钉住）：{@code last_sync_time} 是一个
     * <b>RFC3339 字符串</b>，{@code kb_logical} 是嵌套的字符串 map；{@code kb_media}
     * 为 null 时整个键不出现。</p>
     */
    public Map<String, Object> toConnectorCursor() {
        @SuppressWarnings("unchecked")
        Map<String, Object> out = MAPPER.convertValue(this, Map.class);
        return out == null ? new LinkedHashMap<>() : out;
    }

    /**
     * 从扁平 map 还原游标。
     *
     * <p>形状不对、解析失败时回
     * {@code null}（等价于"没有上一轮游标"，退化成首次同步——只会多抓一次，
     * 不会漏数据）。</p>
     */
    public static ImaCursor fromConnectorCursor(Map<String, Object> connectorCursor) {
        if (connectorCursor == null) {
            return null;
        }
        try {
            return MAPPER.convertValue(connectorCursor, ImaCursor.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
