package com.ragagent.datasource.connector.yuque;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.ragagent.common.web.ZeroTimeSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 语雀纯函数的对等测试（对照 Go {@code yuque/types_test.go} 与
 * {@code connector.go} 里的 {@code buildDocURL}）。
 *
 * <p>期望值全部来自 Go 实录程序（把 Go 的 {@code Config.GetBaseURL} /
 * {@code buildDocURL} / {@code parseContentUpdatedAt} / {@code sanitizeFileName} /
 * {@code redactToken} / {@code parseRetryAfter} 原样抄进独立程序跑出真值）。</p>
 */
class YuqueFormatsTest {

    // ── GetBaseURL ───────────────────────────────────────────────────────

    /** 对照 Go {@code TestGetBaseURL_*} 四条（含实录的真值）。 */
    @ParameterizedTest
    @CsvSource({
            "'',                        https://www.yuque.com",
            "'   ',                     https://www.yuque.com",
            "https://x.yuque.com/,      https://x.yuque.com",
            "company.yuque.com,         https://company.yuque.com",
            "http://x.yuque.com//,      http://x.yuque.com",
            "https://www.yuque.com,     https://www.yuque.com",
    })
    void getBaseUrlMatchesGo(String in, String want) {
        YuqueConfig cfg = new YuqueConfig();
        cfg.setBaseUrl(in);
        assertThat(cfg.baseURL()).isEqualTo(want);
    }

    /** {@code GetBaseURL} 是 Go 的**方法**：不得成为 JSON 属性（约定 §7.5 第 2 条）。 */
    @Test
    void getBaseUrlIsNotAJsonProperty() throws Exception {
        YuqueConfig cfg = new YuqueConfig();
        cfg.setApiToken("tok");
        cfg.setBaseUrl("https://company.yuque.com");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(cfg);
        assertThat(json).contains("\"api_token\"").contains("\"base_url\"");
        assertThat(json).doesNotContain("baseURL").doesNotContain("baseUrl\"");
    }

    // ── buildDocURL ──────────────────────────────────────────────────────

    /** 对照 Go 实录：namespace 为空时只回基地址。 */
    @ParameterizedTest
    @CsvSource({
            "https://www.yuque.com, alice/demo, hello,   https://www.yuque.com/alice/demo/hello",
            "'https://x.yuque.com', team-a/ab,  slug-1,  https://x.yuque.com/team-a/ab/slug-1",
    })
    void buildDocUrlMatchesGo(String base, String namespace, String slug, String want) {
        assertThat(YuqueFormats.buildDocURL(base, namespace, slug)).isEqualTo(want);
    }

    @Test
    void buildDocUrlFallsBackToBaseWhenNamespaceEmpty() {
        assertThat(YuqueFormats.buildDocURL("https://www.yuque.com", "", "hello"))
                .isEqualTo("https://www.yuque.com");
    }

    // ── parseContentUpdatedAt ────────────────────────────────────────────

    /** 对照 Go 实录：解析失败/空一律回**零值时间**（不是 null、不抛错）。 */
    @Test
    void parseContentUpdatedAtMatchesGo() {
        assertThat(ZeroTimeSerializer.isZeroValue(YuqueFormats.parseContentUpdatedAt(""))).isTrue();
        assertThat(ZeroTimeSerializer.isZeroValue(YuqueFormats.parseContentUpdatedAt(null))).isTrue();
        assertThat(ZeroTimeSerializer.isZeroValue(YuqueFormats.parseContentUpdatedAt("not-a-time"))).isTrue();
        // Go 的 RFC3339 不接受"只有日期"
        assertThat(ZeroTimeSerializer.isZeroValue(YuqueFormats.parseContentUpdatedAt("2026-04-20"))).isTrue();

        assertThat(YuqueFormats.parseContentUpdatedAt("2026-04-20T10:00:00Z").toInstant())
                .isEqualTo(Instant.parse("2026-04-20T10:00:00Z"));

        OffsetDateTime withOffset = YuqueFormats.parseContentUpdatedAt("2026-04-20T10:00:00+08:00");
        assertThat(withOffset.toInstant()).isEqualTo(Instant.parse("2026-04-20T02:00:00Z"));
        assertThat(withOffset.getOffset()).isEqualTo(ZoneOffset.ofHours(8));

        assertThat(YuqueFormats.parseContentUpdatedAt("2026-04-20T10:00:00.123Z").toInstant())
                .isEqualTo(Instant.parse("2026-04-20T10:00:00.123Z"));
    }

    // ── sanitizeFileName ─────────────────────────────────────────────────

    @Test
    void sanitizeFileNameMatchesGo() {
        assertThat(YuqueFormats.sanitizeFileName("")).isEqualTo("untitled");
        assertThat(YuqueFormats.sanitizeFileName(null)).isEqualTo("untitled");
        assertThat(YuqueFormats.sanitizeFileName("Hello")).isEqualTo("Hello");
        assertThat(YuqueFormats.sanitizeFileName("a/b\\c:d*e?f\"g<h>i|j"))
                .isEqualTo("a_b_c_d_e_f_g_h_i_j");
    }

    /**
     * 对照 Go {@code TestSanitizeFileName_TruncatesAtRuneBoundary}：
     * 长中文标题按字节截断必须落在 rune 边界上，否则下游的
     * {@code utf8.ValidString} 会以"文件名包含非法字符"拒绝。
     */
    @Test
    void sanitizeFileNameTruncatesAtRuneBoundary() {
        String longName = "测试".repeat(100); // 600 字节
        String got = YuqueFormats.sanitizeFileName(longName);

        assertThat(got.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(200);
        assertThat(got.getBytes(StandardCharsets.UTF_8).length).isEqualTo(198);
        assertThat(got).isNotEmpty();
        assertThat(longName).startsWith(got);
    }

    // ── redactToken ──────────────────────────────────────────────────────

    /** 对照 Go {@code TestRedactToken}。 */
    @ParameterizedTest
    @CsvSource({
            "short,            ***",
            "abcdef1234567890, abcdef...7890",
            "abcdefghijkl,     abcdef...ijkl",
    })
    void redactTokenMatchesGo(String in, String want) {
        assertThat(YuqueClient.redactToken(in)).isEqualTo(want);
    }
}
