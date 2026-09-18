package com.ragagent.datasource.connector.rss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.DataSourceConfig;
import org.junit.jupiter.api.Test;

/**
 * RSS 连接器的**纯函数**对等测试（{@link RssUtil} / {@link RssConfig}）。
 *
 * <h2>期望值的来源（本项目的验收标准）</h2>
 * <p>全部是 <b>Go 实录</b>：把 {@code internal/datasource/connector/rss/types.go} 里的
 * {@code contentFingerprint} / {@code feedSignalFingerprint} / {@code itemExternalID} /
 * {@code firstNonEmpty} / {@code sanitizeFileName} / {@code copyFeedCursor} /
 * {@code feedURLsFromSettings} / {@code Config.feedURLList} / {@code Config.parseHeaders}
 * <b>原样抄进</b>一个独立 Go 程序（{@code /tmp/gochk-r}），喂同样的输入跑出来，
 * 输出抄进下面的断言。</p>
 *
 * <h2>它们为什么必须逐字节一致</h2>
 * <p>指纹进 {@code last_sync_cursor} 这个 jsonb 列。跨语言部署（或从 Go 迁到 Java）
 * 时，Go 写下的 {@code feed_items} 指纹要被 Java 读出来做"和上一轮比"——
 * 算法差一个字节，增量同步就会把整个 feed 重灌一遍。</p>
 *
 * <h2>{@code feedSignalFingerprint} 的输入是结构体不是 feed 文本</h2>
 * <p>Go 侧它只读 {@code gofeed.Item} 的五个字段（GUID / Link / Title /
 * UpdatedParsed / PublishedParsed）。复刻程序里用一个同形结构体顶替即可，
 * 不需要真的解析 feed——本项目就是这么录的。</p>
 */
class RssPureFunctionsTest {

    private static OffsetDateTime utc(int y, int mo, int d, int h, int mi, int s) {
        return OffsetDateTime.of(y, mo, d, h, mi, s, 0, ZoneOffset.UTC);
    }

    private static FeedParser.ParsedItem item(String guid, String link, String title,
                                              OffsetDateTime updated, OffsetDateTime published) {
        return new FeedParser.ParsedItem(guid, link, title, null, null, updated, published, null);
    }

    // ── contentFingerprint ────────────────────────────────────────────────

    @Test
    void contentFingerprintMatchesGo() {
        // Go 实录（/tmp/gochk-r）：
        //   ""                   -> "h:e3b0c44298fc1c14"
        //   "hello"              -> "h:2cf24dba5fb0a30e"
        //   "# Title\n\nbody text\n" -> "h:9caaf00e46035184"
        //   "中文内容"             -> "h:082516888f2d11c0"
        assertThat(RssUtil.contentFingerprint("")).isEqualTo("h:e3b0c44298fc1c14");
        assertThat(RssUtil.contentFingerprint("hello")).isEqualTo("h:2cf24dba5fb0a30e");
        assertThat(RssUtil.contentFingerprint("# Title\n\nbody text\n"))
                .isEqualTo("h:9caaf00e46035184");
        assertThat(RssUtil.contentFingerprint("中文内容")).isEqualTo("h:082516888f2d11c0");
    }

    // ── feedSignalFingerprint ─────────────────────────────────────────────

    @Test
    void feedSignalFingerprintMatchesGo() {
        // Go 实录：
        //   nil                                                  -> ""
        //   {g1, https://example.com/a, t} + "body"              -> "s:20da6c79768c8916"
        //   同上 + "changed"                                      -> "s:8f05ceac4a9c8e43"
        //   {guid-1, http://127.0.0.1:1/article/a1, Article One,
        //    published=2006-01-02T15:04:05Z} + "summary fallback"-> "s:ab96c53c2e1d4196"
        //   {updated=2006-01-03T15:04:05Z} + ""                   -> "s:d0e7f3fb77983285"
        //   {updated=2006-01-02T23:04:05+08:00} + ""              -> "s:31567da82d89663c"
        //   {updated=零值 time.Time} + ""                          -> "s:7c370d9536d7d0d6"
        //   {} + ""                                               -> "s:7c370d9536d7d0d6"
        assertThat(RssUtil.feedSignalFingerprint(null, "x")).isEmpty();

        FeedParser.ParsedItem a = item("g1", "https://example.com/a", "t", null, null);
        assertThat(RssUtil.feedSignalFingerprint(a, "body")).isEqualTo("s:20da6c79768c8916");
        assertThat(RssUtil.feedSignalFingerprint(a, "body")).isEqualTo("s:20da6c79768c8916");
        assertThat(RssUtil.feedSignalFingerprint(a, "changed")).isEqualTo("s:8f05ceac4a9c8e43");

        FeedParser.ParsedItem withPublished = item("guid-1", "http://127.0.0.1:1/article/a1",
                "Article One", null, utc(2006, 1, 2, 15, 4, 5));
        assertThat(RssUtil.feedSignalFingerprint(withPublished, "summary fallback"))
                .isEqualTo("s:ab96c53c2e1d4196");

        // Go 那次调用传的是 &Item{GUID: "g", UpdatedParsed: t2}，GUID 是 "g" 不是空串
        assertThat(RssUtil.feedSignalFingerprint(item("g", "", "", utc(2006, 1, 3, 15, 4, 5), null), ""))
                .isEqualTo("s:d0e7f3fb77983285");

        // 非 UTC 的 zone 先 .UTC() 再 RFC3339 —— 与上一行同一个瞬时，指纹必须相同
        OffsetDateTime cst = OffsetDateTime.of(2006, 1, 2, 23, 4, 5, 0,
                ZoneOffset.ofHours(8));
        assertThat(RssUtil.feedSignalFingerprint(item("", "", "", cst, null), ""))
                .isEqualTo("s:31567da82d89663c");

        // 零值时间与"没有时间"在 Go 里输出同一串（都是空段），所以指纹相同
        OffsetDateTime goZero = OffsetDateTime.of(1, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        assertThat(RssUtil.feedSignalFingerprint(item("", "", "", goZero, null), ""))
                .isEqualTo("s:7c370d9536d7d0d6");
        assertThat(RssUtil.feedSignalFingerprint(item("", "", "", null, null), ""))
                .isEqualTo("s:7c370d9536d7d0d6");
    }

    // ── itemExternalID ────────────────────────────────────────────────────

    @Test
    void itemExternalIdMatchesGo() {
        // Go 实录：("https://a.com/feed", "guid-1") -> "https://a.com/feed:guid-1"; ("", "") -> ":"
        assertThat(RssUtil.itemExternalID("https://a.com/feed", "guid-1"))
                .isEqualTo("https://a.com/feed:guid-1");
        assertThat(RssUtil.itemExternalID("", "")).isEqualTo(":");
    }

    // ── firstNonEmpty ─────────────────────────────────────────────────────

    @Test
    void firstNonEmptyMatchesGo() {
        // Go 实录：
        //   ("","","")     -> ""
        //   ("  ","x","y") -> "x"
        //   ("  a  ","b")  -> "  a  "    ← 返回原值，不 trim
        //   ()             -> ""
        assertThat(RssUtil.firstNonEmpty("", "", "")).isEmpty();
        assertThat(RssUtil.firstNonEmpty("  ", "x", "y")).isEqualTo("x");
        assertThat(RssUtil.firstNonEmpty("  a  ", "b")).isEqualTo("  a  ");
        assertThat(RssUtil.firstNonEmpty()).isEmpty();
    }

    // ── sanitizeFileName ──────────────────────────────────────────────────

    @Test
    void sanitizeFileNameMatchesGo() {
        // Go 实录（len 是**字节**长度）：
        //   ""                    -> "untitled" (8)
        //   "   "                 -> "untitled" (8)
        //   "hello"               -> "hello" (5)
        //   "a/b\\c:d*e?f\"g<h>i|j" -> "a_b_c_d_e_f_g_h_i_j" (19)
        //   "line\nbreak\ttab"    -> "line break tab" (14)   ← RSS 这份换行变空格
        //   "///"                 -> "___" (3)
        assertThat(RssUtil.sanitizeFileName("")).isEqualTo("untitled");
        assertThat(RssUtil.sanitizeFileName("   ")).isEqualTo("untitled");
        assertThat(RssUtil.sanitizeFileName("hello")).isEqualTo("hello");
        assertThat(RssUtil.sanitizeFileName("a/b\\c:d*e?f\"g<h>i|j"))
                .isEqualTo("a_b_c_d_e_f_g_h_i_j");
        assertThat(RssUtil.sanitizeFileName("line\nbreak\ttab")).isEqualTo("line break tab");
        assertThat(RssUtil.sanitizeFileName("///")).isEqualTo("___");
        assertThat(RssUtil.sanitizeFileName(null)).isEqualTo("untitled");
    }

    @Test
    void sanitizeFileNameTruncatesOnUtf8BoundaryInBytes() {
        // Go 实录：
        //   100 个 "中"（300 字节）        -> 198 字节（66 个字，第 67 个被截掉两个残字节）
        //   250 个 "a"                     -> 200 字节
        //   66 个 "中" + "abcdef"          -> 200 字节（66*3=198，再取 "ab"）
        //   199 个 "x" + "中" + "yyyy"      -> 199 字节（200 字节处落在 "中" 的中间）
        //   198 个 "x" + "中" + "zz"        -> 198 字节（同上，回退到完整 rune 边界）
        String cjk100 = RssUtil.sanitizeFileName("中".repeat(100));
        assertThat(cjk100.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(198);

        String ascii250 = RssUtil.sanitizeFileName("a".repeat(250));
        assertThat(ascii250).hasSize(200);

        String mixed = RssUtil.sanitizeFileName("中".repeat(66) + "abcdef");
        assertThat(mixed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(200);
        assertThat(mixed).endsWith("ab");

        String splitAt199 = RssUtil.sanitizeFileName("x".repeat(199) + "中" + "yyyy");
        assertThat(splitAt199.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(199);

        String splitAt198 = RssUtil.sanitizeFileName("x".repeat(198) + "中" + "zz");
        assertThat(splitAt198.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(198);
    }

    // ── goTrim ────────────────────────────────────────────────────────────

    @Test
    void goTrimCoversUnicodeSpacesJavaWouldMiss() {
        // Go 的 unicode.IsSpace 含 U+00A0 / U+2007 / U+202F，Java 的 strip() 与 trim() 都不管它们。
        assertThat(RssUtil.goTrim(" a ")).isEqualTo("a");
        assertThat(RssUtil.goTrim(" b ")).isEqualTo("b");
        assertThat(RssUtil.goTrim("\t\nc\r ")).isEqualTo("c");
        assertThat(RssUtil.goTrim(null)).isEmpty();
        assertThat(RssUtil.goTrim("   ")).isEmpty();
    }

    // ── Config.feedURLList ────────────────────────────────────────────────

    @Test
    void feedUrlListMatchesGo() {
        // Go 实录：
        //   "https://a.com/f, https://b.com/f\nhttps://a.com/f\n" -> ["https://a.com/f","https://b.com/f"]
        //   "a,b,\r\nc" -> ["a","b","c"]
        //   "\n\n"      -> []
        //   ""          -> []
        //   "  a ,  a " -> ["a"]
        //   "a\n\n\nb"  -> ["a","b"]
        //   "x,y,z,a,x" -> ["x","y","z","a"]
        assertThat(new RssConfig("https://a.com/f, https://b.com/f\nhttps://a.com/f\n", null)
                .feedUrlList())
                .containsExactly("https://a.com/f", "https://b.com/f");
        assertThat(new RssConfig("a,b,\r\nc", null).feedUrlList()).containsExactly("a", "b", "c");
        assertThat(new RssConfig("\n\n", null).feedUrlList()).isEmpty();
        assertThat(new RssConfig("", null).feedUrlList()).isEmpty();
        assertThat(new RssConfig("  a ,  a ", null).feedUrlList()).containsExactly("a");
        assertThat(new RssConfig("a\n\n\nb", null).feedUrlList()).containsExactly("a", "b");
        assertThat(new RssConfig("x,y,z,a,x", null).feedUrlList()).containsExactly("x", "y", "z", "a");
    }

    // ── Config.parseHeaders ───────────────────────────────────────────────

    @Test
    void parseHeadersMatchesGo() {
        // Go 实录：
        //   ""                                             -> nil
        //   "   "                                          -> nil
        //   "Authorization: Bearer x\nX-Foo:  bar \nbroken-line\n: noname"
        //                                                  -> {Authorization:"Bearer x", X-Foo:"bar"}
        //   "A: 1\nA: 2"                                   -> {A:"2"}
        //   "Nosep"                                        -> nil
        //   "  Name :  Value  "                            -> {Name:"Value"}
        //   ":"                                            -> nil
        //   "a:b:c"                                        -> {a:"b:c"}
        RssConfig cfg = new RssConfig(null,
                "Authorization: Bearer x\nX-Foo:  bar \nbroken-line\n: noname");
        assertThat(cfg.parseHeaders())
                .containsExactlyInAnyOrderEntriesOf(Map.of("Authorization", "Bearer x",
                        "X-Foo", "bar"));

        assertThat(new RssConfig(null, "").parseHeaders()).isNull();
        assertThat(new RssConfig(null, "   ").parseHeaders()).isNull();
        assertThat(new RssConfig(null, "Nosep").parseHeaders()).isNull();
        assertThat(new RssConfig(null, ":").parseHeaders()).isNull();
        assertThat(new RssConfig(null, "A: 1\nA: 2").parseHeaders())
                .containsExactlyInAnyOrderEntriesOf(Map.of("A", "2"));
        assertThat(new RssConfig(null, "  Name :  Value  ").parseHeaders())
                .containsExactlyInAnyOrderEntriesOf(Map.of("Name", "Value"));
        assertThat(new RssConfig(null, "a:b:c").parseHeaders())
                .containsExactlyInAnyOrderEntriesOf(Map.of("a", "b:c"));
    }

    // ── feedURLsFromSettings ──────────────────────────────────────────────

    @Test
    void feedUrlsFromSettingsMatchesGo() {
        // Go 实录：nil/空 -> ""；无键 -> ""；非字符串 -> ""；"  https://a/f  " -> "https://a/f"；"   " -> ""
        assertThat(RssConfig.feedUrlsFromSettings(null)).isEmpty();
        assertThat(RssConfig.feedUrlsFromSettings(Map.of())).isEmpty();
        assertThat(RssConfig.feedUrlsFromSettings(Map.of("x", 1))).isEmpty();
        assertThat(RssConfig.feedUrlsFromSettings(mapOfNullable("feed_urls", 12))).isEmpty();
        assertThat(RssConfig.feedUrlsFromSettings(mapOfNullable("feed_urls", "  https://a/f  ")))
                .isEqualTo("https://a/f");
        assertThat(RssConfig.feedUrlsFromSettings(mapOfNullable("feed_urls", "   "))).isEmpty();
    }

    private static Map<String, Object> mapOfNullable(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    // ── copyFeedCursor ────────────────────────────────────────────────────

    @Test
    void copyFeedCursorMatchesGo() {
        RssCursor prev = new RssCursor();
        prev.setLastSyncTime(utc(2006, 1, 2, 15, 4, 5));
        prev.setFeedItems(new LinkedHashMap<>(Map.of(
                "f1", new LinkedHashMap<>(Map.of("i1", "h1")),
                "f2", new LinkedHashMap<>())));
        prev.setFeedSignals(new LinkedHashMap<>(Map.of(
                "f1", new LinkedHashMap<>(Map.of("i1", "s1")))));

        // Go 实录：f1 -> {"feed_items":{"f1":{"i1":"h1"}},"feed_signals":{"f1":{"i1":"s1"}}}
        RssCursor dst = new RssCursor();
        dst.setLastSyncTime(utc(2006, 1, 2, 15, 4, 5));
        dst.setFeedItems(new LinkedHashMap<>());
        dst.setFeedSignals(new LinkedHashMap<>());
        RssUtil.copyFeedCursor(dst, prev, "f1");
        assertThat(dst.getFeedItems()).containsOnlyKeys("f1");
        assertThat(dst.getFeedItems().get("f1")).containsEntry("i1", "h1");
        assertThat(dst.getFeedSignals()).containsOnlyKeys("f1");
        assertThat(dst.getFeedSignals().get("f1")).containsEntry("i1", "s1");

        // f2 的源 map 是**空 map**：Go 的 len(src) > 0 不成立，所以什么都不搬。
        RssCursor dst2 = new RssCursor();
        dst2.setFeedItems(new LinkedHashMap<>());
        dst2.setFeedSignals(new LinkedHashMap<>());
        RssUtil.copyFeedCursor(dst2, prev, "f2");
        assertThat(dst2.getFeedItems()).isEmpty();
        assertThat(dst2.getFeedSignals()).isEmpty();

        // 不存在的 feed：同样什么都不搬。
        RssCursor dst3 = new RssCursor();
        dst3.setFeedItems(new LinkedHashMap<>());
        RssUtil.copyFeedCursor(dst3, prev, "f3");
        assertThat(dst3.getFeedItems()).isEmpty();

        // nil dst / nil prev：Go 的首行 no-op，两边都不能抛。
        RssUtil.copyFeedCursor(null, prev, "f1");
        RssCursor dst4 = new RssCursor();
        dst4.setFeedItems(new LinkedHashMap<>());
        RssUtil.copyFeedCursor(dst4, null, "f1");
        assertThat(dst4.getFeedItems()).isEmpty();
    }

    // ── parseConfig 的四条错误分支 ────────────────────────────────────────

    @Test
    void parseConfigRejectsNullConfig() {
        // Go：fmt.Errorf("%w: config is nil", ErrInvalidConfig)
        assertThatThrownBy(() -> RssConfig.parse(null))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration: config is nil");
    }

    @Test
    void parseConfigRequiresFeedUrls() {
        // Go 实录（实测）：`invalid credentials: feed_urls is required`
        DataSourceConfig config = new DataSourceConfig();
        config.setSettings(mapOfNullable("feed_urls", "   "));
        config.setCredentials(new LinkedHashMap<>());
        assertThatThrownBy(() -> RssConfig.parse(config))
                .isInstanceOf(ConnectorException.InvalidCredentials.class)
                .hasMessage("invalid credentials: feed_urls is required");
    }

    @Test
    void parseConfigSettingsOverrideCredentials() {
        DataSourceConfig config = new DataSourceConfig();
        config.setSettings(mapOfNullable("feed_urls", "https://settings.example/feed.xml"));
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("feed_urls", "https://legacy.example/feed.xml");
        config.setCredentials(credentials);
        assertThat(RssConfig.parse(config).feedUrlList())
                .containsExactly("https://settings.example/feed.xml");
    }

    @Test
    void parseConfigFallsBackToLegacyCredentials() {
        DataSourceConfig config = new DataSourceConfig();
        config.setSettings(new LinkedHashMap<>());
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("feed_urls", "https://legacy.example/feed.xml");
        config.setCredentials(credentials);
        assertThat(RssConfig.parse(config).feedUrlList())
                .containsExactly("https://legacy.example/feed.xml");
    }

    @Test
    void parseConfigRejectsNonStringFeedUrlsInCredentials() {
        // Go 的 json.Unmarshal 对 "数字 -> string 字段" 报错；Jackson 默认会强转，故 mapper 关掉了它。
        DataSourceConfig config = new DataSourceConfig();
        config.setSettings(new LinkedHashMap<>());
        config.setCredentials(mapOfNullable("feed_urls", 12));
        assertThatThrownBy(() -> RssConfig.parse(config))
                .isInstanceOf(ConnectorException.class)
                .hasMessageStartingWith("parse rss credentials: ");
    }

    @Test
    void parseConfigParsesAuthHeaders() {
        DataSourceConfig config = new DataSourceConfig();
        config.setSettings(mapOfNullable("feed_urls", "https://a.example/f"));
        config.setCredentials(mapOfNullable("auth_headers", "X-Test-Auth: secret"));
        assertThat(RssConfig.parse(config).parseHeaders()).containsEntry("X-Test-Auth", "secret");
    }

    // ── itemExternalID 在 walk 里的去重作用 ───────────────────────────────

    @Test
    void differentFeedsWithSameGuidGetDifferentExternalIds() {
        assertThat(RssUtil.itemExternalID("https://a/f", "g")).isNotEqualTo(
                RssUtil.itemExternalID("https://b/f", "g"));
    }

    // ── 列表不可变性的小护栏（RssCursor.toMap 的返回） ─────────────────────

    @Test
    void rssCursorToMapIsMutableCopy() {
        RssCursor cursor = new RssCursor();
        cursor.setLastSyncTime(utc(2006, 1, 2, 15, 4, 5));
        Map<String, Object> map = cursor.toMap();
        map.put("extra", "x");
        assertThat(cursor.toMap()).doesNotContainKey("extra");
    }

    // ── 未使用的 import 防护：List 在这里被用到（保持编译期依赖显式） ────────

    @Test
    void emptyFeedUrlListIsEmptyNotNil() {
        List<String> list = new RssConfig("", null).feedUrlList();
        assertThat(list).isNotNull().isEmpty();
    }
}
