package com.ragagent.datasource.connector.feishu.core;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ragagent.common.web.ZeroTimeSerializer;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * 飞书游标的线格式编解码（wiki 的 {@code space_node_times} /
 * drive 的 {@code file_times}，以及各自的编码/解码）。
 *
 * <h2>⚠️ 这是会落 jsonb 的线格式</h2>
 * <p>它写进 {@code data_sources.last_sync_cursor} 这一列，键名与时间字面量必须与
 * 既有数据逐字节兼容，否则老游标读不出来（或者更糟：被当成"没有历史"，
 * 于是每次同步都全量重来）。所以：</p>
 * <pre>
 *   wiki   : {"last_sync_time":"2026-09-18T10:00:00+08:00",
 *             "space_node_times":{"space1":{"nt1":"100"}}}
 *   drive  : {"last_sync_time":"2026-09-18T10:00:00+08:00",
 *             "file_times":{"folder1":{"fdoc1":"100"}}}
 * </pre>
 * <p>{@code space_node_times} / {@code file_times} 带 {@code omitempty}：
 * <b>空/缺席时该键整个消失</b>；非空时才是嵌套对象。{@code last_sync_time} 没有
 * omitempty，恒输出（零值写成 year-1 字面量）。</p>
 *
 * <h2>为什么不是 DTO + Jackson</h2>
 * <p>净效果就是"把游标摊成一张 map"，而 {@link SyncCursor#getConnectorCursor()} 要的正是
 * 一张 {@code Map<String,Object>}，所以直接构造这张 map，省掉一次无谓的序列化往返。</p>
 *
 * <h2>时间格式为什么在这里重写了一遍</h2>
 * <p>格式化逻辑与 {@link ZeroTimeSerializer} 完全一致，但它是 {@code JsonSerializer}
 * 而不是工具类，且在 {@code com.ragagent.common.web} 下（不在本模块可改范围）。
 * 改动 {@code ZeroTimeSerializer} 时必须同步改这里。</p>
 */
public final class FeishuCursorCodec {

    /** wiki 游标里嵌套时间表的键名。 */
    public static final String KEY_SPACE_NODE_TIMES = "space_node_times";

    /** drive 游标里嵌套时间表的键名。 */
    public static final String KEY_FILE_TIMES = "file_times";

    private FeishuCursorCodec() {
    }

    // ── wiki：space_node_times ─────────────────────────────────────────

    /** 编码 wiki 游标。 */
    public static SyncCursor encodeSpaceNodeTimes(Map<String, Map<String, String>> times,
                                                  OffsetDateTime lastSync) {
        return encode(times, lastSync, KEY_SPACE_NODE_TIMES);
    }

    /** 解码 wiki 游标（键缺席时返回 {@code null}）。 */
    public static Map<String, Map<String, String>> decodeSpaceNodeTimes(Map<String, Object> connectorCursor) {
        return decode(connectorCursor, KEY_SPACE_NODE_TIMES);
    }

    // ── drive：file_times ──────────────────────────────────────────────

    /** 编码 drive 游标。 */
    public static SyncCursor encodeFileTimes(Map<String, Map<String, String>> times,
                                             OffsetDateTime lastSync) {
        return encode(times, lastSync, KEY_FILE_TIMES);
    }

    /** 解码 drive 游标（键缺席时返回 {@code null}）。 */
    public static Map<String, Map<String, String>> decodeFileTimes(Map<String, Object> connectorCursor) {
        return decode(connectorCursor, KEY_FILE_TIMES);
    }

    // ── 实现 ───────────────────────────────────────────────────────────

    private static SyncCursor encode(Map<String, Map<String, String>> times, OffsetDateTime lastSync,
                                     String timesKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("last_sync_time", formatGoTime(lastSync));
        // omitempty 语义：times 为空时整个键消失
        if (times != null && !times.isEmpty()) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, String>> e : times.entrySet()) {
                nested.put(e.getKey(), e.getValue() == null
                        ? new LinkedHashMap<String, String>() : e.getValue());
            }
            m.put(timesKey, nested);
        }

        SyncCursor cursor = new SyncCursor();
        cursor.setLastSyncTime(lastSync);
        cursor.setConnectorCursor(m);
        return cursor;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> decode(Map<String, Object> connectorCursor,
                                                           String timesKey) {
        if (connectorCursor == null) {
            return null;
        }
        Object raw = connectorCursor.get(timesKey);
        if (!(raw instanceof Map<?, ?> outer)) {
            // 键缺失或类型不符时按"没有这份数据"处理 → 返回 null
            return null;
        }
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : outer.entrySet()) {
            String resourceId = String.valueOf(e.getKey());
            Object inner = e.getValue();
            Map<String, String> times = new LinkedHashMap<>();
            if (inner instanceof Map<?, ?> innerMap) {
                for (Map.Entry<?, ?> ie : innerMap.entrySet()) {
                    times.put(String.valueOf(ie.getKey()),
                            ie.getValue() == null ? "" : String.valueOf(ie.getValue()));
                }
            }
            out.put(resourceId, times);
        }
        return out;
    }

    /**
     * 游标时间戳的 JSON 编码格式（RFC3339Nano、服务器本地时区偏移、
     * 纳秒尾部零裁剪）。
     *
     * <p>与 {@link ZeroTimeSerializer#serialize} 的两行完全一致——见类注释里那条
     * "改动必须同步"的提醒。</p>
     */
    static String formatGoTime(OffsetDateTime value) {
        if (ZeroTimeSerializer.isZeroValue(value)) {
            return ZeroTimeSerializer.ZERO_TIME_LITERAL;
        }
        OffsetDateTime local = value.atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime();
        return local.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
