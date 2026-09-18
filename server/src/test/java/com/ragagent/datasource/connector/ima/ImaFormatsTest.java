package com.ragagent.datasource.connector.ima;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * IMA 纯函数的对等测试（对照 Go {@code ima/types_test.go}）。
 *
 * <h2>期望值全部是 Go 实录</h2>
 * <p>把 Go 的 {@code logicalKey} / {@code Config.GetBaseURL} /
 * {@code extensionForContentType} / {@code mimeForExtension} /
 * {@code extensionForMediaType} / {@code isSkippableMediaType} /
 * {@code sanitizeFileName} / {@code redact} / {@code apiEnvelope} 连同它们依赖的
 * 常量<b>原样抄进</b>一个独立 Go 程序（{@code gochk-iy}）跑出真值，再抄回断言。
 * 这种做法在本项目已经抓到过两个"只看代码看不出来"的坑（见 HANDOFF §3.6），
 * 所以凡是字节级对齐的模块都沿用。</p>
 */
class ImaFormatsTest {

    // ── logicalKey（跨语言身份契约） ─────────────────────────────────────

    @Test
    @DisplayName("logicalKey 确定、带 ima_ 前缀、36 字符（塞得进 varchar(64)）")
    void logicalKeyIsStableAndScoped() {
        String base = ImaFormats.logicalKey("kb1", "f1", "Doc");

        assertThat(base).isEqualTo(ImaFormats.logicalKey("kb1", "f1", "Doc"));
        assertThat(base).startsWith("ima_").hasSize(36);

        assertThat(ImaFormats.logicalKey("kb2", "f1", "Doc")).isNotEqualTo(base);
        assertThat(ImaFormats.logicalKey("kb1", "f2", "Doc")).isNotEqualTo(base);
        assertThat(ImaFormats.logicalKey("kb1", "f1", "Other")).isNotEqualTo(base);
    }

    /**
     * Go 实录：14 组 {@code (kb_id, parent_folder_id, title)} → 逻辑键。
     *
     * <p>这些值一旦变了，Go 已写进 {@code knowledges.external_id} 的行就再也认不出来
     * ——会被当成新文档重复灌入、旧文档被当成已删除。所以逐字节钉住。</p>
     */
    @ParameterizedTest
    @CsvSource({
            "kb1, f1, Doc,           ima_74c34e9cb26c61ee7eaa5f8d1b1a7dda",
            "kb2, f1, Doc,           ima_1d3061d75fc24923887670108e4204c5",
            "kb1, f2, Doc,           ima_be8347764f13afca7682fedf6012cc38",
            "kb1, f1, Other,         ima_4d0545d5cc0ec9ac849c6680e24f51cc",
            "kb,  a,  bc,            ima_7d8f86571256c665d36429dd1ffc2575",
            "kb,  ab, c,             ima_a0bc443396f0b71bb3696a0c69c40484",
            "kb1, '', Good,          ima_bd2621dd005e8300efce69b0c91dd2be",
            "kb1, '', Flaky,         ima_28e2d7e6f9941074ae109cbe91bdfb08",
            "kb1, '', Drop,          ima_d1c7f917fa5789897da0ffe9723be034",
            "kb1, f2, Deep,          ima_f58cac3515101e46edffcce2894af27d",
            "kb1, '', Root,          ima_20abcc0e4d5ac19afd412563742cc24e",
            "kb1, '', Meeting notes, ima_4d79b5a2110a05cf936386c3745f8078",
            "kb1, '', Article,       ima_78014cc4ddb39728e9068a7b4158b057",
            "kb1, '', Photo,         ima_4fbb87e7c7c78ce28f9031010facf7b6",
    })
    void logicalKeyMatchesGoRecordings(String kbId, String folderId, String title, String want) {
        assertThat(ImaFormats.logicalKey(kbId, folderId, title)).isEqualTo(want);
    }

    /** 对照 Go {@code TestLogicalKey_DelimiterIsUnambiguous}：朴素拼接会碰撞。 */
    @Test
    void logicalKeyDelimiterIsUnambiguous() {
        assertThat(ImaFormats.logicalKey("kb", "a", "bc"))
                .isNotEqualTo(ImaFormats.logicalKey("kb", "ab", "c"));
    }

    // ── Config.GetBaseURL ────────────────────────────────────────────────

    /** 对照 Go {@code TestConfig_GetBaseURL}（含实测的真值）。 */
    @ParameterizedTest
    @CsvSource({
            "'',                          https://ima.qq.com",
            "'   ',                       https://ima.qq.com",
            "https://ima.example.com/,    https://ima.example.com",
            "ima.example.com,             https://ima.example.com",
            "http://ima.example.com//,    http://ima.example.com",
            "https://ima.qq.com,          https://ima.qq.com",
    })
    void configGetBaseUrlMatchesGo(String in, String want) {
        ImaConfig cfg = new ImaConfig();
        cfg.setBaseUrl(in);
        assertThat(cfg.baseURL()).isEqualTo(want);
    }

    /** {@code GetBaseURL} 是 Go 的**方法**：不得成为 JSON 属性（约定 §7.5 第 2 条）。 */
    @Test
    void configGetBaseUrlIsNotAJsonProperty() throws Exception {
        ImaConfig cfg = new ImaConfig();
        cfg.setClientId("cid");
        cfg.setApiKey("key");
        cfg.setBaseUrl("https://ima.example.com");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(cfg);
        assertThat(json).contains("\"client_id\"").contains("\"api_key\"");
        assertThat(json).doesNotContain("baseURL").doesNotContain("baseUrl");
    }

    // ── 媒体类型映射 ─────────────────────────────────────────────────────

    /** 对照 Go 的 {@code extensionForMediaType} 全表（含没有扩展名的那些类型）。 */
    @ParameterizedTest
    @CsvSource({
            "0,  ''", "1,  pdf", "2,  ''", "3,  docx", "4,  pptx", "5,  xlsx", "6,  ''",
            "7,  md", "8,  ''", "9,  png", "10, ''", "11, ''", "12, ''", "13, txt",
            "14, xmind", "15, mp3", "16, ''", "17, ''", "20, html", "21, epub", "99, ''",
    })
    void extensionForMediaTypeMatchesGo(int mediaType, String want) {
        // CsvSource 把空串写成 ''，转成 Java 后是 "''"？不——空项在 CSV 里就是空串。
        String expected = want == null ? "" : want;
        assertThat(ImaFormats.extensionForMediaType(mediaType)).isEqualTo(expected);
    }

    /** 对照 Go {@code TestExtensionForContentType}。 */
    @ParameterizedTest
    @CsvSource({
            "image/jpeg,               jpg",
            "image/JPEG,               jpg",
            "'image/png; charset=binary', png",
            "image/webp,               webp",
            "image/gif,                gif",
            "image/bmp,                bmp",
            "image/jpg,                jpg",
            "application/pdf,          ''",
            "'',                       ''",
            "'  IMAGE/PNG  ',          png",
            "image/png;charset=utf-8,  png",
            "image/svg+xml,            ''",
            "text/html,                ''",
    })
    void extensionForContentTypeMatchesGo(String in, String want) {
        assertThat(ImaFormats.extensionForContentType(in)).isEqualTo(want == null ? "" : want);
    }

    /** 对照 Go {@code mimeForExtension} 全表。 */
    @ParameterizedTest
    @CsvSource({
            "'',      application/octet-stream",
            "pdf,     application/pdf",
            ".pdf,    application/pdf",
            "doc,     application/msword",
            "docx,    application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "ppt,     application/vnd.ms-powerpoint",
            "pptx,    application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "xls,     application/vnd.ms-excel",
            "xlsx,    application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "md,      text/markdown",
            "txt,     text/plain",
            "html,    text/html",
            "htm,     text/html",
            "png,     image/png",
            "jpg,     image/jpeg",
            "jpeg,    image/jpeg",
            "webp,    image/webp",
            "gif,     image/gif",
            "bmp,     image/bmp",
            "epub,    application/epub+zip",
            "xmind,   application/x-xmind",
            "mp3,     audio/mpeg",
            "m4a,     audio/x-m4a",
            "wav,     audio/wav",
            "aac,     audio/aac",
            "PDF,     application/pdf",
            "unknown, application/octet-stream",
    })
    void mimeForExtensionMatchesGo(String ext, String want) {
        assertThat(ImaFormats.mimeForExtension(ext)).isEqualTo(want);
    }

    /** 对照 Go {@code TestIsSkippableMediaType}：AI 会话与视频跳过，笔记**不**跳过。 */
    @Test
    void isSkippableMediaTypeMatchesGo() {
        assertThat(ImaFormats.isSkippableMediaType(ImaFormats.MEDIA_TYPE_AI_SESSION)).isTrue();
        assertThat(ImaFormats.isSkippableMediaType(ImaFormats.MEDIA_TYPE_VIDEO)).isTrue();
        for (int mt : new int[]{ImaFormats.MEDIA_TYPE_PDF, ImaFormats.MEDIA_TYPE_MARKDOWN,
                ImaFormats.MEDIA_TYPE_WEB, ImaFormats.MEDIA_TYPE_NOTE}) {
            assertThat(ImaFormats.isSkippableMediaType(mt)).isFalse();
        }
    }

    // ── sanitizeFileName / redact ────────────────────────────────────────

    @Test
    void sanitizeFileNameMatchesGo() {
        assertThat(ImaFormats.sanitizeFileName("")).isEqualTo("untitled");
        assertThat(ImaFormats.sanitizeFileName(null)).isEqualTo("untitled");
        assertThat(ImaFormats.sanitizeFileName("a/b\\c:d*e?f\"g<h>i|j"))
                .isEqualTo("a_b_c_d_e_f_g_h_i_j");
    }

    /**
     * 长中文标题必须在 rune 边界上截断（Go 实录：{@code strings.Repeat("知识", 200)}
     * → 66 个「知识」= 198 字节，且结果仍是合法 UTF-8 前缀）。
     */
    @Test
    void sanitizeFileNameTruncatesAtRuneBoundary() {
        String longName = "知识".repeat(200);
        String got = ImaFormats.sanitizeFileName(longName);

        assertThat(got.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(200);
        assertThat(got.getBytes(StandardCharsets.UTF_8).length).isEqualTo(198);
        assertThat(got).hasSize(66);
        assertThat(longName).startsWith(got);
    }

    @Test
    void sanitizeFileNameKeepsShortNames() {
        assertThat(ImaFormats.sanitizeFileName("Plain title")).isEqualTo("Plain title");
        assertThat(ImaFormats.sanitizeFileName("a".repeat(200))).isEqualTo("a".repeat(200));
        assertThat(ImaFormats.sanitizeFileName("a".repeat(201)).getBytes(StandardCharsets.UTF_8).length)
                .isEqualTo(200);
    }

    /**
     * 对照 Go {@code redact}（Go 实录）：
     * {@code len < 12 → "***"}，否则前 6 + {@code "..."} + 后 4。
     */
    @ParameterizedTest
    @CsvSource({
            "'',                    ***",
            "short,                 ***",
            "abcdefghijkl,          abcdef...ijkl",
            "abcdef1234567890,      abcdef...7890",
            "abcdef1234567890xyz,   abcdef...0xyz",
            "12345678901,           ***",
    })
    void redactMatchesGo(String in, String want) {
        assertThat(ImaClient.redact(in)).isEqualTo(want);
    }

    // ── apiEnvelope 的两种拼法 ───────────────────────────────────────────

    /**
     * 对照 Go {@code TestAPIEnvelope_AcceptsBothSpellings}：只读 {@code code} 的话，
     * 非零 {@code retcode} 会被解成 0、每个 API 错误都被静默当成成功。
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"code\":110030,\"msg\":\"无权限\"}                 | 110030 | 无权限",
            "{\"retcode\":110030,\"errmsg\":\"无权限\"}           | 110030 | 无权限",
            "{\"code\":0,\"msg\":\"ok\"}                          | 0      | ok",
            "{\"retcode\":0,\"errmsg\":\"成功\"}                  | 0      | 成功",
            "{\"code\":0,\"retcode\":110021,\"errmsg\":\"限频\"}  | 110021 | 限频",
            "{\"code\":110001,\"msg\":\"\",\"errmsg\":\"x\"}      | 110001 | x",
            "{\"msg\":\"only msg\"}                               | 0      | only msg",
            "{}                                                  | 0      | ''",
    })
    void apiEnvelopeAcceptsBothSpellings(String body, int wantCode, String wantMsg) throws Exception {
        ImaApiTypes.ApiEnvelope env = FakeIma.MAPPER.readValue(
                body, ImaApiTypes.ApiEnvelope.class);
        assertThat(env.statusCode()).isEqualTo(wantCode);
        assertThat(env.message()).isEqualTo(wantMsg == null ? "" : wantMsg);
    }

    /** 上限 36 字符这个不变式单独钉一条（Go 的 {@code len(base) != 36} 断言）。 */
    @ParameterizedTest
    @ValueSource(strings = {"kb1", "a-very-long-knowledge-base-id-0123456789", "知识库"})
    void logicalKeyAlways36Chars(String kbId) {
        assertThat(ImaFormats.logicalKey(kbId, "f", "t")).hasSize(36);
    }
}
