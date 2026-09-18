package com.ragagent.datasource.connector.rss;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.datasource.domain.SyncCursor;
import org.junit.jupiter.api.Test;

/**
 * {@link RssCursor} 的 **jsonb 存储契约**测试。
 *
 * <h2>为什么这个类型要逐字节比</h2>
 * <p>它被塞进 {@code SyncCursor.connector_cursor}、落 {@code data_sources.last_sync_cursor}
 * 这个 jsonb 列。Go 写完 Java 读、Java 写完 Go 读都要能读回来——所以键名、
 * {@code omitempty} 的有无、map 的键序都是契约本身，不是风格问题。</p>
 *
 * <h2>Go 实录（{@code /tmp/gochk-r} 与 rss 包的探针跑出来的真值）</h2>
 * <pre>
 *   rssCursor{LastSyncTime: t}                        → {"last_sync_time":"2006-01-02T15:04:05Z"}
 *   （FeedItems/FeedSignals 都是空 map）                 → 同上（两个键被 omitempty 干掉）
 *   nil maps                                          → 同上
 *   {FeedItems:{"https://a/f":{"guid-1":"h:abc",...}},
 *    FeedSignals:{"https://a/f":{...}}}                → {"last_sync_time":...,
 *                                                         "feed_items":{...},"feed_signals":{...}}
 *   {FeedItems:{"z":{}}, FeedSignals:{"z":{}}}         → 两个键**保留**（值是空 map 但键在）
 * </pre>
 * <p>真实同步产出的一整条 {@code SyncCursor}（Go 实录）：
 * {@code {"last_sync_time":"2026-09-18T06:09:08.504305Z","connector_cursor":{"feed_items":{…},
 * "feed_signals":{…},"last_sync_time":"2026-09-18T06:09:08.504305Z"},"last_schema_hash":""}}
 * ——注意 {@code connector_cursor} 里的键是<b>字母序</b>（{@code feed_items} &lt;
 * {@code feed_signals} &lt; {@code last_sync_time}），这是 Go 对 map 的规则，
 * Java 侧由 {@code DataSourceMapSerializer} 复刻。</p>
 *
 * <h2>⚠️ 已知差异：时间的时区写法</h2>
 * <p>Go 的 {@code LastSyncTime} 是 {@code time.Now().UTC()}，所以 JSON 里是
 * {@code "…Z"}；Java 的 {@code GoTimeSerializer} 按约定 §9 <b>统一归一化到 JVM 默认时区</b>，
 * 于是写成 {@code "+08:00"}。<b>瞬时相同、可互相解析</b>（Go 的
 * {@code json.Unmarshal} 进 {@code time.Time} 完全接受带偏移的串），
 * 但字节不同。下面 {@link #lastSyncTimeIsSameInstantButJvmZoneRepresentation()}
 * 把这条差异显式钉住，免得将来有人以为它对齐了。</p>
 */
class RssCursorJsonTest {

    private static final Pattern KEY = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\":");

    private static OffsetDateTime t() {
        return OffsetDateTime.of(2006, 1, 2, 15, 4, 5, 0, ZoneOffset.UTC);
    }

    private static RssCursor cursor(OffsetDateTime lastSync,
                                    Map<String, Map<String, String>> items,
                                    Map<String, Map<String, String>> signals) {
        RssCursor c = new RssCursor();
        c.setLastSyncTime(lastSync);
        c.setFeedItems(items);
        c.setFeedSignals(signals);
        return c;
    }

    @Test
    void omitsEmptyFeedMapsButKeepsLastSyncTime() {
        // Go 实录：空 map / nil map 都只输出 last_sync_time
        Map<String, Object> emptyMaps = cursor(t(), new LinkedHashMap<>(), new LinkedHashMap<>())
                .toMap();
        assertThat(emptyMaps).containsOnlyKeys("last_sync_time");

        Map<String, Object> nilMaps = cursor(t(), null, null).toMap();
        assertThat(nilMaps).containsOnlyKeys("last_sync_time");
    }

    @Test
    void emitsBothMapsWhenPopulated() {
        Map<String, Map<String, String>> items = new LinkedHashMap<>();
        items.put("https://a/f", new LinkedHashMap<>(Map.of("guid-1", "h:abc", "guid-2", "h:def")));
        Map<String, Map<String, String>> signals = new LinkedHashMap<>();
        signals.put("https://a/f", new LinkedHashMap<>(Map.of("guid-1", "s:11")));

        Map<String, Object> map = cursor(t(), items, signals).toMap();
        assertThat(map).containsOnlyKeys("last_sync_time", "feed_items", "feed_signals");

        @SuppressWarnings("unchecked")
        Map<String, Object> feedItems = (Map<String, Object>) map.get("feed_items");
        assertThat(feedItems).containsOnlyKeys("https://a/f");
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) feedItems.get("https://a/f");
        assertThat(inner).containsExactlyInAnyOrderEntriesOf(
                Map.of("guid-1", "h:abc", "guid-2", "h:def"));
    }

    @Test
    void keepsFeedKeyWhenInnerMapIsEmpty() {
        // Go 实录：{FeedItems:{"z":{}}} → "feed_items":{"z":{}} —— omitempty 只看外层 map 的 len
        Map<String, Map<String, String>> items = new LinkedHashMap<>();
        items.put("z", new LinkedHashMap<>());
        Map<String, Map<String, String>> signals = new LinkedHashMap<>();
        signals.put("z", new LinkedHashMap<>());
        Map<String, Object> map = cursor(t(), items, signals).toMap();
        assertThat(map).containsOnlyKeys("last_sync_time", "feed_items", "feed_signals");
        // outer key 保留、"内层是空 map"也保留（omitempty 只看外层 len）
        assertThat(map.get("feed_items").toString()).isEqualTo("{z={}}");
    }

    @Test
    void syncCursorSerializesConnectorCursorWithAlphabeticalKeys() {
        Map<String, Map<String, String>> items = new LinkedHashMap<>();
        items.put("https://a/f", new LinkedHashMap<>(Map.of("guid-1", "h:abc")));
        Map<String, Map<String, String>> signals = new LinkedHashMap<>();
        signals.put("https://a/f", new LinkedHashMap<>(Map.of("guid-1", "s:11")));
        RssCursor rss = cursor(t(), items, signals);

        SyncCursor sync = new SyncCursor();
        sync.setLastSyncTime(rss.getLastSyncTime());
        sync.setConnectorCursor(rss.toMap());

        String json = sync.toJSON().toString();
        // 键序：Go 对 map 恒按字母序输出（DataSourceMapSerializer 复刻）
        assertThat(keyOrder(json)).containsExactly(
                "last_sync_time", "connector_cursor", "feed_items", "feed_signals",
                "last_sync_time", "last_schema_hash");
        assertThat(json).contains("\"last_schema_hash\":\"\"");
        assertThat(json).contains("\"feed_items\"");
        assertThat(json).contains("\"guid-1\":\"h:abc\"");
    }

    @Test
    void lastSyncTimeIsSameInstantButJvmZoneRepresentation() {
        Map<String, Object> map = cursor(t(), null, null).toMap();
        String rendered = (String) map.get("last_sync_time");
        // 瞬时一致：这是跨语言能互相读回的关键
        assertThat(Instant.parse(rendered)).isEqualTo(t().toInstant());
        // ⚠️ 已知差异：Go 写 "2006-01-02T15:04:05Z"（UTC），Java 归一化到 JVM 默认时区。
        // 只有 JVM 时区恰好是 UTC 时两者才字节相同。
        if (!ZoneId.systemDefault().getRules().getOffset(t().toInstant()).equals(ZoneOffset.UTC)) {
            assertThat(rendered).isNotEqualTo("2006-01-02T15:04:05Z");
        }
    }

    @Test
    void roundTripsThroughTheStoredMap() {
        Map<String, Map<String, String>> items = new LinkedHashMap<>();
        items.put("https://a/f", new LinkedHashMap<>(Map.of("guid-1", "h:abc")));
        Map<String, Map<String, String>> signals = new LinkedHashMap<>();
        signals.put("https://a/f", new LinkedHashMap<>(Map.of("guid-1", "s:11")));
        RssCursor original = cursor(t(), items, signals);

        RssCursor restored = RssCursor.fromMap(original.toMap());
        assertThat(restored.getLastSyncTime().toInstant()).isEqualTo(t().toInstant());
        assertThat(restored.getFeedItems()).isEqualTo(items);
        assertThat(restored.getFeedSignals()).isEqualTo(signals);
    }

    @Test
    void toleratesUnknownKeysOnRead() {
        // 约定 §7.5 第 6 条：Go 的 json.Unmarshal 默认忽略未知字段，Jackson 默认失败。
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("last_sync_time", "2006-01-02T15:04:05Z");
        stored.put("feed_items", new LinkedHashMap<String, Object>());
        stored.put("a_future_key", "ignored");
        RssCursor restored = RssCursor.fromMap(stored);
        assertThat(restored).isNotNull();
        assertThat(restored.getLastSyncTime().toInstant()).isEqualTo(t().toInstant());
    }

    @Test
    void fromMapReturnsNullForNullInput() {
        assertThat(RssCursor.fromMap(null)).isNull();
    }

    @Test
    void bucketsAreCreatedOnDemand() {
        RssCursor c = new RssCursor();
        c.bucketForItems("f1").put("i1", "h1");
        c.bucketForSignals("f1").put("i1", "s1");
        c.bucketForItems("f1").put("i2", "h2");
        assertThat(c.getFeedItems().get("f1")).containsEntry("i1", "h1").containsEntry("i2", "h2");
        assertThat(c.getFeedSignals().get("f1")).containsEntry("i1", "s1");
    }

    private static List<String> keyOrder(String json) {
        Matcher m = KEY.matcher(json);
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }
}
