package com.ragagent.datasource.connector.feishu.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.DataSourceConfig;

/**
 * 对照 Go {@code core/region_test.go} + {@code wiki/connector_test.go} 的纯函数段
 * （{@code IsSupportedDocType} / {@code SanitizeFileName} / {@code ParseFeishuTimestamp} /
 * {@code ParseFeishuConfig} / {@code ObjTypeToExportMappings} / {@code SupportedImageExt} /
 * {@code resolveLocation}）。
 *
 * <p>所有"期望值"都是 Go 实录：把 Go 源码逐字抄进 {@code /tmp/gochk-feishu} 独立程序，
 * 用真 Go 运行时跑出来（任务书约束第 4 条）。凡是实录值都会在用例注释里写明来源。</p>
 */
class FeishuSupportTest {

    @BeforeAll
    static void ssrf() {
        FeishuTestSupport.allowLoopback();
    }

    @AfterAll
    static void restore() {
        FeishuTestSupport.restoreSsrf();
    }

    // ──────────────────────────────────────────────────────────────────
    // Region（对照 region_test.go）
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Region（region_test.go）")
    class Regions {

        @Test
        void regionsAreDistinct() {
            assertThat(FeishuRegion.FEISHU.connectorType())
                    .isNotEqualTo(FeishuRegion.LARK.connectorType());
            assertThat(FeishuRegion.FEISHU.openBaseUrl())
                    .isNotEqualTo(FeishuRegion.LARK.openBaseUrl());
            assertThat(FeishuRegion.FEISHU.webBaseUrl())
                    .isNotEqualTo(FeishuRegion.LARK.webBaseUrl());
            assertThat(FeishuRegion.FEISHU.connectorType()).isEqualTo("feishu");
            assertThat(FeishuRegion.LARK.connectorType()).isEqualTo("lark");
            assertThat(FeishuRegion.FEISHU_DRIVE.connectorType()).isEqualTo("feishu_drive");
            assertThat(FeishuRegion.LARK_DRIVE.connectorType()).isEqualTo("lark_drive");
        }

        @Test
        void wikiUrlPointsAtTheRightCloud() {
            assertThat(FeishuRegion.FEISHU.wikiUrl("spc123")).isEqualTo("https://feishu.cn/wiki/spc123");
            assertThat(FeishuRegion.LARK.wikiUrl("spc123")).isEqualTo("https://larksuite.com/wiki/spc123");
            // A Lark link must never point at the Feishu host — that was the bug.
            assertThat(FeishuRegion.LARK.wikiUrl("x")).doesNotContain("feishu");
        }

        @Test
        void driveFolderUrl() {
            assertThat(FeishuRegion.FEISHU_DRIVE.driveFolderUrl("fld1"))
                    .isEqualTo("https://feishu.cn/drive/folder/fld1");
            assertThat(FeishuRegion.LARK_DRIVE.driveFolderUrl("fld1"))
                    .isEqualTo("https://larksuite.com/drive/folder/fld1");
        }

        @Test
        void labels() {
            assertThat(FeishuRegion.FEISHU.label()).isEqualTo("Feishu");
            assertThat(FeishuRegion.FEISHU_DRIVE.label()).isEqualTo("FeishuDrive");
            assertThat(FeishuRegion.LARK_DRIVE.label()).isEqualTo("LarkDrive");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // ParseFeishuConfig
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ParseFeishuConfig")
    class ConfigParsing {

        @Test
        void valid() {
            Map<String, Object> creds = new LinkedHashMap<>();
            creds.put("app_id", "id1");
            creds.put("app_secret", "sec1");
            creds.put("base_url", "https://open.feishu.cn");
            DataSourceConfig ds = new DataSourceConfig();
            ds.setCredentials(creds);

            FeishuConfig cfg = FeishuSupport.parseFeishuConfig(ds, FeishuRegion.FEISHU);
            assertThat(cfg.getAppId()).isEqualTo("id1");
            assertThat(cfg.getAppSecret()).isEqualTo("sec1");
            assertThat(cfg.getBaseUrl()).isEqualTo("https://open.feishu.cn");
        }

        @Test
        void baseUrlDefaultsToRegion() {
            String[][] cases = {
                    {"https://open.feishu.cn", null},
                    {"https://open.larksuite.com", null},
                    {"https://open.larksuite.com", "https://open.larksuite.com"},
                    {"https://open.feishu.cn", "https://open.feishu.cn"},
            };
            FeishuRegion[] regions = {
                    FeishuRegion.FEISHU, FeishuRegion.LARK, FeishuRegion.FEISHU, FeishuRegion.LARK};

            for (int i = 0; i < cases.length; i++) {
                Map<String, Object> creds = new LinkedHashMap<>();
                creds.put("app_id", "cli_x");
                creds.put("app_secret", "s");
                if (cases[i][1] != null) {
                    creds.put("base_url", cases[i][1]);
                }
                DataSourceConfig ds = new DataSourceConfig();
                ds.setCredentials(creds);
                FeishuConfig cfg = FeishuSupport.parseFeishuConfig(ds, regions[i]);
                assertThat(cfg.resolveBaseUrl()).isEqualTo(cases[i][0]);
            }
        }

        @Test
        void nilConfig() {
            assertThatThrownBy(() -> FeishuSupport.parseFeishuConfig(null, FeishuRegion.FEISHU))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("config is nil");
        }

        @Test
        void missingCredentials() {
            Map<String, Object> creds = new LinkedHashMap<>();
            creds.put("app_id", "id1"); // 缺 app_secret
            DataSourceConfig ds = new DataSourceConfig();
            ds.setCredentials(creds);

            assertThatThrownBy(() -> FeishuSupport.parseFeishuConfig(ds, FeishuRegion.FEISHU))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("feishu app_id and app_secret are required");
        }

        @Test
        void nilCredentialsIsRejectedNotNpe() {
            DataSourceConfig ds = new DataSourceConfig();
            assertThatThrownBy(() -> FeishuSupport.parseFeishuConfig(ds, FeishuRegion.LARK))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("lark app_id and app_secret are required");
        }

        @Test
        @DisplayName("timezone 从 settings 里取，且被 trim（对照 Go 的 strings.TrimSpace）")
        void timezoneFromSettings() {
            Map<String, Object> creds = new LinkedHashMap<>();
            creds.put("app_id", "id1");
            creds.put("app_secret", "sec1");
            DataSourceConfig ds = new DataSourceConfig();
            ds.setCredentials(creds);
            ds.setSettings(new LinkedHashMap<>(Map.of("timezone", "  Asia/Shanghai  ")));

            FeishuConfig cfg = FeishuSupport.parseFeishuConfig(ds, FeishuRegion.FEISHU);
            assertThat(cfg.getTimezone()).isEqualTo("Asia/Shanghai");
        }

        @Test
        @DisplayName("settings.timezone 非字符串时忽略（对照 Go 的 type assertion）")
        void timezoneNonStringIgnored() {
            Map<String, Object> creds = new LinkedHashMap<>();
            creds.put("app_id", "id1");
            creds.put("app_secret", "sec1");
            DataSourceConfig ds = new DataSourceConfig();
            ds.setCredentials(creds);
            ds.setSettings(new LinkedHashMap<>(Map.of("timezone", 8)));

            FeishuConfig cfg = FeishuSupport.parseFeishuConfig(ds, FeishuRegion.FEISHU);
            assertThat(cfg.getTimezone()).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // IsSupportedDocType
    // ──────────────────────────────────────────────────────────────────

    @Test
    void isSupportedDocType() {
        assertThat(FeishuSupport.isSupportedDocType("docx")).isTrue();
        assertThat(FeishuSupport.isSupportedDocType("doc")).isTrue();
        assertThat(FeishuSupport.isSupportedDocType("sheet")).isTrue();
        assertThat(FeishuSupport.isSupportedDocType("bitable")).isTrue();
        assertThat(FeishuSupport.isSupportedDocType("file")).isTrue();
        assertThat(FeishuSupport.isSupportedDocType("mindnote")).isFalse();
        assertThat(FeishuSupport.isSupportedDocType("slides")).isFalse();
        assertThat(FeishuSupport.isSupportedDocType("unknown")).isFalse();
        assertThat(FeishuSupport.isSupportedDocType("")).isFalse();
        assertThat(FeishuSupport.isSupportedDocType(null)).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────
    // SanitizeFileName（Go 实录）
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SanitizeFileName（期望值是 Go 实录，见任务书约束第 4 条）")
    class Sanitize {

        @Test
        void basicCases() {
            assertThat(FeishuSupport.sanitizeFileName("hello")).isEqualTo("hello");
            assertThat(FeishuSupport.sanitizeFileName("")).isEqualTo("untitled");
            assertThat(FeishuSupport.sanitizeFileName(null)).isEqualTo("untitled");
            assertThat(FeishuSupport.sanitizeFileName("a/b\\c:d*e")).isEqualTo("a_b_c_d_e");
            assertThat(FeishuSupport.sanitizeFileName("normal file.docx")).isEqualTo("normal file.docx");
            assertThat(FeishuSupport.sanitizeFileName("a.b.c.d")).isEqualTo("a.b.c.d");
            assertThat(FeishuSupport.sanitizeFileName("trailing.")).isEqualTo("trailing.");
            assertThat(FeishuSupport.sanitizeFileName(".hidden")).isEqualTo(".hidden");
            assertThat(FeishuSupport.sanitizeFileName("a?b\"c<d>e|f"))
                    .isEqualTo("a_b_c_d_e_f");
        }

        @Test
        @DisplayName("300 个 a → Go 实录就是 200 个 a（无扩展名）")
        void truncatesAscii() {
            String got = FeishuSupport.sanitizeFileName("a".repeat(300));
            assertThat(got).isEqualTo("a".repeat(200));
            assertThat(FeishuSupport.utf8Length(got)).isEqualTo(200);
        }

        @Test
        @DisplayName("每 3 字节的『测』×100 → Go 实录 198 字节（不劈开码点）")
        void truncatesAtRuneBoundary() {
            String got = FeishuSupport.sanitizeFileName("测试".repeat(100));
            assertThat(got).hasSize(66); // 66 * 3 bytes = 198
            assertThat(FeishuSupport.utf8Length(got)).isEqualTo(198);
            assertThat(new String(got.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                    .isEqualTo(got);
            assertThat(got).doesNotContain("�");
        }

        @Test
        @DisplayName("『报告』×150 + \".pdf\" → Go 实录保留 .pdf，总长 199 字节")
        void preservesExtensionOnTruncation() {
            String long_ = "报告".repeat(150) + ".pdf";
            String got = FeishuSupport.sanitizeFileName(long_);
            assertThat(got).endsWith(".pdf");
            assertThat(FeishuSupport.utf8Length(got)).isEqualTo(199);
            assertThat(FeishuSupport.utf8Length(got)).isLessThanOrEqualTo(200);
        }

        @Test
        @DisplayName("『名』×300 + \".tar.gz\" → Go 实录保留最后一段扩展名 .gz，198 字节")
        void extensionIsOnlyTheLastDot() {
            String got = FeishuSupport.sanitizeFileName("名".repeat(300) + ".tar.gz");
            assertThat(got).endsWith(".gz");
            assertThat(FeishuSupport.utf8Length(got)).isEqualTo(198);
        }

        @Test
        @DisplayName("≈200 字节边界：198+『.pdf』=202 → 裁到 200 并保留 .pdf")
        void justOverTheLimit() {
            String got = FeishuSupport.sanitizeFileName("x".repeat(198) + ".pdf");
            assertThat(FeishuSupport.utf8Length(got)).isEqualTo(200);
            assertThat(got).endsWith(".pdf");

            String got2 = FeishuSupport.sanitizeFileName("x".repeat(197) + ".pdf");
            assertThat(FeishuSupport.utf8Length(got2)).isEqualTo(200);
            assertThat(got2).endsWith(".pdf");
        }

        @Test
        @DisplayName("『名』×70 = 210 字节 → Go 实录 198 字节（截断后无残缺码点）")
        void cjkOnly() {
            String got = FeishuSupport.sanitizeFileName("名".repeat(70));
            assertThat(FeishuSupport.utf8Length(got)).isEqualTo(198);
            assertThat(got).isEqualTo("名".repeat(66));
        }

        @Test
        @DisplayName("病态：扩展名自己就超预算 → 丢掉扩展名（Go 的 ext = \"\" 分支）")
        void pathologicalExtensionDropped() {
            String name = "a" + "." + "b".repeat(250);
            String got = FeishuSupport.sanitizeFileName(name);
            assertThat(FeishuSupport.utf8Length(got)).isLessThanOrEqualTo(200);
            // Go 实录：ext（251 字节）超预算被丢掉，再对整串裁到 200 字节
            assertThat(got).isEqualTo("a." + "b".repeat(198));
            assertThat(FeishuSupport.utf8Length(got)).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("truncateUtf8（Go 实录：『测』×10 截 8 → 2 个『测』；截 9 → 3 个）")
    void truncateUtf8() {
        assertThat(FeishuSupport.truncateUtf8("测".repeat(10), 8)).isEqualTo("测测");
        assertThat(FeishuSupport.truncateUtf8("测".repeat(10), 9)).isEqualTo("测测测");
        assertThat(FeishuSupport.truncateUtf8("abcdef", 3)).isEqualTo("abc");
        assertThat(FeishuSupport.truncateUtf8("abc", 10)).isEqualTo("abc");
        assertThat(FeishuSupport.truncateUtf8("abc", 0)).isEmpty();
    }

    @Test
    void fileExtMatchesGoFilepathExt() {
        assertThat(FeishuSupport.fileExt("a.txt")).isEqualTo(".txt");
        assertThat(FeishuSupport.fileExt("a.tar.gz")).isEqualTo(".gz");
        assertThat(FeishuSupport.fileExt("noext")).isEmpty();
        assertThat(FeishuSupport.fileExt(".hidden")).isEqualTo(".hidden");
        assertThat(FeishuSupport.fileExt("trailing.")).isEqualTo(".");
    }

    // ──────────────────────────────────────────────────────────────────
    // ParseFeishuTimestamp
    // ──────────────────────────────────────────────────────────────────

    @Test
    void parseFeishuTimestamp() {
        OffsetDateTime t = FeishuSupport.parseFeishuTimestamp("1711468800");
        assertThat(t).isNotNull();
        assertThat(t.toEpochSecond()).isEqualTo(1711468800L);

        assertThat(FeishuSupport.parseFeishuTimestamp("")).isNull();
        assertThat(FeishuSupport.parseFeishuTimestamp("invalid")).isNull();
        assertThat(FeishuSupport.parseFeishuTimestamp(null)).isNull();
        // Go 的 "0" 是合法的 epoch，不是零值
        assertThat(FeishuSupport.parseFeishuTimestamp("0")).isNotNull();
        assertThat(FeishuSupport.parseFeishuTimestamp("0").toEpochSecond()).isZero();
        assertThat(FeishuSupport.parseFeishuTimestamp("-5").toEpochSecond()).isEqualTo(-5L);
        assertThat(FeishuSupport.parseFeishuTimestamp("1711468800.5")).isNull();
        assertThat(FeishuSupport.parseFeishuTimestamp("99999999999999999999")).isNull();
    }

    @Test
    void orGoZeroFallsBackToGoZeroLiteral() {
        assertThat(FeishuSupport.orGoZero(null).toInstant())
                .isEqualTo(java.time.Instant.parse("0001-01-01T00:00:00Z"));
        OffsetDateTime t = FeishuSupport.parseFeishuTimestamp("1711468800");
        assertThat(FeishuSupport.orGoZero(t)).isSameAs(t);
    }

    // ──────────────────────────────────────────────────────────────────
    // 时区解析
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveLocation：空与未知名字都回落固定 GMT+8 (28800s)")
    void resolveLocationFallsBackToGmt8() {
        assertThat(FeishuConfig.resolveLocation("").getRules().getOffset(
                java.time.Instant.parse("2024-04-01T00:00:00Z")).getTotalSeconds())
                .isEqualTo(28800);
        assertThat(FeishuConfig.resolveLocation("Not/AZone").getRules().getOffset(
                java.time.Instant.parse("2024-04-01T00:00:00Z")).getTotalSeconds())
                .isEqualTo(28800);
        assertThat(FeishuConfig.resolveLocation("Asia/Shanghai").getId())
                .isEqualTo("Asia/Shanghai");
        assertThat(FeishuConfig.resolveLocation(null).getId())
                .isEqualTo(ZoneOffset.ofTotalSeconds(28800).getId());
    }

    // ──────────────────────────────────────────────────────────────────
    // 导出类型映射
    // ──────────────────────────────────────────────────────────────────

    @Test
    void objTypeToExportMappings() {
        for (String ot : List.of("docx", "doc", "sheet", "bitable")) {
            assertThat(FeishuConfig.OBJ_TYPE_TO_EXPORT_FILE_EXTENSION).containsKey(ot);
            assertThat(FeishuConfig.OBJ_TYPE_TO_EXPORT_TYPE).containsKey(ot);
        }
        for (String ot : List.of("file", "mindnote", "slides")) {
            assertThat(FeishuConfig.OBJ_TYPE_TO_EXPORT_FILE_EXTENSION).doesNotContainKey(ot);
        }
        assertThat(FeishuConfig.OBJ_TYPE_TO_EXPORT_FILE_EXTENSION.get("sheet")).isEqualTo("xlsx");
        assertThat(FeishuConfig.OBJ_TYPE_TO_EXPORT_FILE_EXTENSION.get("doc")).isEqualTo("docx");
        assertThat(FeishuConfig.OBJ_TYPE_TO_EXPORT_TYPE.get("bitable")).isEqualTo("bitable");
    }

    @Test
    void exportFileExtToSuffix() {
        assertThat(FeishuConfig.EXPORT_FILE_EXT_TO_SUFFIX.get(FeishuConfig.EXPORT_TYPE_DOCX))
                .isEqualTo(".docx");
        assertThat(FeishuConfig.EXPORT_FILE_EXT_TO_SUFFIX.get(FeishuConfig.EXPORT_TYPE_XLSX))
                .isEqualTo(".xlsx");
        assertThat(FeishuConfig.EXPORT_FILE_EXT_TO_SUFFIX.get(FeishuConfig.EXPORT_TYPE_PDF))
                .isEqualTo(".pdf");
    }

    // ──────────────────────────────────────────────────────────────────
    // 图片嗅探（覆盖 sniffer 的每一支）
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SupportedImageExt：png/jpg/gif 通过；webp/文本/空 不通过但仍返回探测到的 content type")
    void supportedImageExt() {
        // 期望值 = Go 实录（http.DetectContentType）
        byte[] png = concat(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'},
                "rest".getBytes(StandardCharsets.UTF_8));
        assertThat(FeishuSupport.supportedImageExt(png).ext()).isEqualTo(".png");
        assertThat(FeishuSupport.supportedImageExt(png).contentType()).isEqualTo("image/png");
        assertThat(FeishuSupport.supportedImageExt(png).ok()).isTrue();

        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 'J', 'F', 'I', 'F'};
        assertThat(FeishuSupport.supportedImageExt(jpeg).ext()).isEqualTo(".jpg");
        assertThat(FeishuSupport.supportedImageExt(jpeg).contentType()).isEqualTo("image/jpeg");
        assertThat(FeishuSupport.supportedImageExt(jpeg).ok()).isTrue();

        byte[] gif = new byte[]{'G', 'I', 'F', '8', '9', 'a', 0x01, 0x00, 0x01, 0x00};
        assertThat(FeishuSupport.supportedImageExt(gif).ext()).isEqualTo(".gif");
        assertThat(FeishuSupport.supportedImageExt(gif).ok()).isTrue();

        byte[] gif87 = new byte[]{'G', 'I', 'F', '8', '7', 'a', 0x01, 0x00, 0x01, 0x00};
        assertThat(FeishuSupport.supportedImageExt(gif87).ext()).isEqualTo(".gif");

        // Go 的签名是通配模式 "RIFF????WEBPVP"（中间 4 字节被掩掉）
        byte[] webp = concat(
                new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '},
                new byte[8]);
        assertThat(FeishuSupport.supportedImageExt(webp).ok()).isFalse();
        assertThat(FeishuSupport.supportedImageExt(webp).ext()).isEmpty();
        assertThat(FeishuSupport.supportedImageExt(webp).contentType()).isEqualTo("image/webp");

        byte[] text = "just some plain text, not an image at all".getBytes(StandardCharsets.UTF_8);
        assertThat(FeishuSupport.supportedImageExt(text).ok()).isFalse();
        assertThat(FeishuSupport.supportedImageExt(text).contentType())
                .isEqualTo("text/plain; charset=utf-8");

        assertThat(FeishuSupport.supportedImageExt(new byte[0]).contentType())
                .isEqualTo("text/plain; charset=utf-8");
        assertThat(FeishuSupport.supportedImageExt(new byte[]{0x00, 0x01, 0x02}).contentType())
                .isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("URL 转义与 Go 的 PathEscape / QueryEscape 同形")
    void urlEscaping() {
        assertThat(FeishuSupport.queryEscape("a b")).isEqualTo("a+b");
        assertThat(FeishuSupport.queryEscape("a/b?c=d&e")).isEqualTo("a%2Fb%3Fc%3Dd%26e");
        assertThat(FeishuSupport.queryEscape("tok-1_2.3~4")).isEqualTo("tok-1_2.3~4");
        assertThat(FeishuSupport.pathEscape("a b")).isEqualTo("a%20b");
        assertThat(FeishuSupport.pathEscape("a/b")).isEqualTo("a%2Fb");
        // Go 的 encodePathSegment 不转义这几个子分隔符
        assertThat(FeishuSupport.pathEscape("a+b:c=d@e&f$g")).isEqualTo("a+b:c=d@e&f$g");
        assertThat(FeishuSupport.queryEscape("测")).isEqualTo("%E6%B5%8B");
    }

    @Test
    void cutMatchesGoStringsCut() {
        assertThat(FeishuSupport.cut("a:b:c", ":")).containsExactly("a", "b:c");
        assertThat(FeishuSupport.cut("abc", ":")).containsExactly("abc", "");
        assertThat(FeishuSupport.cut(":x", ":")).containsExactly("", "x");
        assertThat(FeishuSupport.cut(null, ":")).containsExactly("", "");
    }

    @Test
    void truncateAppendsEllipsis() {
        assertThat(FeishuSupport.truncate("hello", 3)).isEqualTo("hel...");
        assertThat(FeishuSupport.truncate("hi", 3)).isEqualTo("hi");
        assertThat(FeishuSupport.truncate(null, 3)).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // fetchTally（对照 tally_test.go + Go 实录的 summary 串）
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("summary 与 Go 的 %v 同形：by_type=map[mindnote:2 slides:1]（键按字典序）")
    void fetchTallyCountsAndSummary() {
        FetchTally tally = new FetchTally(13);
        tally.fetch();
        tally.fetch();
        tally.fetch();
        tally.skip("mindnote");
        tally.skip("mindnote");
        tally.skip("slides");
        tally.fail();

        assertThat(tally.skipped()).isEqualTo(3);
        String summary = tally.summary();
        assertThat(summary).isEqualTo(
                "discovered=13 fetched=3 failed=1 skipped_unsupported=3 by_type=map[mindnote:2 slides:1]");
        assertThat(summary).contains("discovered=13", "fetched=3", "failed=1",
                "skipped_unsupported=3", "mindnote:2", "slides:1");
    }

    @Test
    void fetchTallyEmptyHasNoSkips() {
        FetchTally tally = new FetchTally(0);
        assertThat(tally.skipped()).isZero();
        assertThat(tally.summary()).isEqualTo(
                "discovered=0 fetched=0 failed=0 skipped_unsupported=0 by_type=map[]");
    }

    @Test
    @DisplayName("by_type 的键序是字典序（Go 的 fmt 对 map 恒排序），与插入顺序无关")
    void fetchTallySummarySortsKeys() {
        FetchTally tally = new FetchTally(2);
        tally.skip("slides");
        tally.skip("mindnote");
        assertThat(tally.summary()).endsWith("by_type=map[mindnote:1 slides:1]");
    }

    // ──────────────────────────────────────────────────────────────────
    // 附件白名单常量
    // ──────────────────────────────────────────────────────────────────

    @Test
    void attachmentWhitelistAndMinSize() {
        assertThat(FeishuSupport.MIN_ATTACHMENT_BYTES).isEqualTo(2048);
        for (String ext : List.of(".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
                ".txt", ".md", ".csv")) {
            assertThat(FeishuSupport.PARSEABLE_ATTACHMENT_EXTS).containsKey(ext);
        }
        assertThat(FeishuSupport.PARSEABLE_ATTACHMENT_EXTS).doesNotContainKey(".png");
        assertThat(FeishuSupport.PARSEABLE_ATTACHMENT_EXTS).hasSize(10);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
