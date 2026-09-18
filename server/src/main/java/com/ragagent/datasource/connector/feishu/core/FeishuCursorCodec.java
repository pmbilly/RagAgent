package com.ragagent.datasource.connector.feishu.core;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * 飞书游标的线格式编解码（对照 Go {@code core.FeishuCursor} /
 * {@code core.FeishuDriveCursor} 两个结构体，以及 wiki/drive 各自的
 * {@code EncodeCursor} / {@code DecodeCursorTimes}）。
 *
 * <h2>⚠️ 这是会落 jsonb 的线格式</h2>
 * <p>它写进 {@code data_sources.last_sync_cursor} 这一列，格式必须与 Go 逐字段一致，
 * 否则 A/B 时同一行游标两边读不出来（或者更糟：一边写、另一边当成"没有历史"，
 * 于是每次同步都全量重来）。所以：</p>
 * <pre>
 *   wiki   : {"last_sync_time":"2026-09-18T10:00:00+08:00",
 *             "space_node_times":{"space1":{"nt1":"100"}}}
 *   drive  : {"last_sync_time":"2026-09-18T10:00:00+08:00",
 *             "file_times":{"folder1":{"fdoc1":"100"}}}
 * </pre>
 * <p>{@code space_node_times} / {@code file_times} 带 {@code omitempty}：
 * <b>空/缺席时该键整个消失</b>；非空时才是嵌套对象。{@code last_sync_time} 没有
 * omitempty，恒输出（Go 的 {@code time.Time} 是值类型，零值写成 year-1 字面量）。</p>
 *
 * <h2>为什么不是 DTO + Jackson</h2>
 * <p>Go 侧那两行 {@code json.Marshal} → {@code json.Unmarshal} 的净效果就是
 * "把结构体摊成一张 map"，而 {@link SyncCursor#getConnectorCursor()} 要的正是
 * 一张 {@code Map<String,Object>}。Java 侧直接构造这张 map，省掉一次无谓的
 * 序列化往返（第 4 条：GORM 的"三步舞"可退化成一步，但要能证明净效果——
 * 这里净效果就是同一张 map）。</p>
 *
 * <h2>时间格式为什么在这里重写了一遍</h2>
 * <p>{@link GoTimeSerializer} 的格式化逻辑只有两行，但它是 {@code JsonSerializer}
 * 而不是工具类，且它在 {@code com.ragagent.common.web} 下（不在本模块可改范围）。
 * 这里照抄它的两行并注释来源；改动 {@code GoTimeSerializer} 时必须同步改这里。</p>
 */
public final class FeishuCursorCodec {

    /** 对照 Go {@code FeishuCursor.SpaceNodeTimes} 的 json tag。 */
    public static final String KEY_SPACE_NODE_TIMES = "space_node_times";

    /** 对照 Go {@code FeishuDriveCursor.FileTimes} 的 json tag。 */
    public static final String KEY_FILE_TIMES = "file_times";

    private FeishuCursorCodec() {
    }

    // ── wiki：space_node_times ─────────────────────────────────────────

    /** 对照 Go {@code wikiOps.EncodeCursor}。 */
    public static SyncCursor encodeSpaceNodeTimes(Map<String, Map<String, String>> times,
                                                  OffsetDateTime lastSync) {
        return encode(times, lastSync, KEY_SPACE_NODE_TIMES);
    }

    /** 对照 Go {@code wikiOps.DecodeCursorTimes}（缺席时返回 null，与 Go 的 nil map 一致）。 */
    public static Map<String, Map<String, String>> decodeSpaceNodeTimes(Map<String, Object> connectorCursor) {
        return decode(connectorCursor, KEY_SPACE_NODE_TIMES);
    }

    // ── drive：file_times ──────────────────────────────────────────────

    /** 对照 Go {@code driveOps.EncodeCursor}。 */
    public static SyncCursor encodeFileTimes(Map<String, Map<String, String>> times,
                                             OffsetDateTime lastSync) {
        return encode(times, lastSync, KEY_FILE_TIMES);
    }

    /** 对照 Go {@code driveOps.DecodeCursorTimes}。 */
    public static Map<String, Map<String, String>> decodeFileTimes(Map<String, Object> connectorCursor) {
        return decode(connectorCursor, KEY_FILE_TIMES);
    }

    // ── 实现 ───────────────────────────────────────────────────────────

    private static SyncCursor encode(Map<String, Map<String, String>> times, OffsetDateTime lastSync,
                                     String timesKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("last_sync_time", formatGoTime(lastSync));
        // 对照 Go 的 omitempty：len(times) == 0 时整个键消失
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
            // Go 的 json.Unmarshal 对缺失/类型不符的键会留下 nil map → 返回 nil
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
     * Go 的 {@code time.Time} JSON 编码（RFC3339Nano、服务器本地时区偏移、
     * 纳秒尾部零裁剪）。
     *
     * <p>与 {@link GoTimeSerializer#serialize} 的两行完全一致——见类注释里那条
     * "改动必须同步"的提醒。</p>
     */
    static String formatGoTime(OffsetDateTime value) {
        if (GoTimeSerializer.isGoZero(value)) {
            return GoTimeSerializer.GO_ZERO_TIME_LITERAL;
        }
        OffsetDateTime local = value.atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime();
        return local.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
