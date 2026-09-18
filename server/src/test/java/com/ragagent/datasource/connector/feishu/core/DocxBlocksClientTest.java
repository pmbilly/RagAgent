package com.ragagent.datasource.connector.feishu.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.datasource.ConnectorHttp;
import com.ragagent.datasource.connector.feishu.core.DocxBlocks.DocxBlock;

/**
 * 对照 Go {@code core/blocks_test.go} 全文：docx blocks / sheets-v2 / bitable-v1 的
 * 读取、分页、截断与防御性 break。
 *
 * <p>桩服务器用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer}（绑 127.0.0.1、
 * 端口 0），喂与 Go {@code httptest} 完全同形的 JSON。</p>
 */
class DocxBlocksClientTest {

    private static FeishuTestServer server;

    @BeforeAll
    static void start() throws IOException {
        FeishuTestSupport.allowLoopback();
        server = new FeishuTestServer();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
        FeishuTestSupport.restoreSsrf();
    }

    private static FeishuClient client() {
        return new FeishuClient(server.baseUrl(), "a", "s", FeishuConfig.resolveLocation(""),
                ConnectorHttp.newConnectorHttpClient(Duration.ofSeconds(10)));
    }

    private static final String TOKEN_PATH = "/open-apis/auth/v3/tenant_access_token/internal";

    // ──────────────────────────────────────────────────────────────────
    // listDocumentBlocks
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("分页拉取：page_size=500，顺序保持，page_token 依次推进")
    void listDocumentBlocksPaginates() {
        server.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"tenant_access_token\":\"t\",\"expire\":7200}"));
        server.handle("/open-apis/docx/v1/documents/doc123/blocks", (ex, body) -> {
            assertThat(ex.getRequestURI().getQuery()).contains("page_size=500");
            if (ex.getRequestURI().getQuery().contains("page_token=p2")) {
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{"
                        + "\"items\":[{\"block_id\":\"b3\",\"block_type\":2}],\"has_more\":false}}");
            } else {
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{"
                        + "\"items\":[{\"block_id\":\"b1\",\"block_type\":2},"
                        + "{\"block_id\":\"b2\",\"block_type\":2}],\"has_more\":true,\"page_token\":\"p2\"}}");
            }
        });

        List<DocxBlock> blocks = client().listDocumentBlocks("doc123");
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).getBlockId()).isEqualTo("b1");
        assertThat(blocks.get(2).getBlockId()).isEqualTo("b3");

        // Go 断言 gotTokens == ['', 'p2']，也就是恰好两次调用
        assertThat(server.countPath("/open-apis/docx/v1/documents/doc123/blocks")).isEqualTo(2);
    }

    @Test
    @DisplayName("空页防御性终止：items 空 + has_more=true + page_token 非空 → 只调一次")
    void listDocumentBlocksEmptyPageTerminates() {
        server.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"tenant_access_token\":\"t\",\"expire\":7200}"));
        server.handle("/open-apis/docx/v1/documents/doc-empty/blocks", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{"
                        + "\"has_more\":true,\"page_token\":\"loop\",\"items\":[]}}"));

        int before = server.countPath("/open-apis/docx/v1/documents/doc-empty/blocks");
        List<DocxBlock> blocks = client().listDocumentBlocks("doc-empty");
        assertThat(blocks).isEmpty();
        assertThat(server.countPath("/open-apis/docx/v1/documents/doc-empty/blocks") - before)
                .isEqualTo(1);
    }

    // ──────────────────────────────────────────────────────────────────
    // readSheetRange
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("按最后一个下划线拆 token，路径与单元格字符串化都正确")
    void readSheetRangeSplitsTokenAndReadsValues() {
        server.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"tenant_access_token\":\"t\",\"expire\":7200}"));
        server.handle("/open-apis/sheets/v2/spreadsheets/sht_abc/values/0", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{\"valueRange\":{\"values\":["
                        + "[\"名称\",\"数量\"],[\"苹果\",3],[\"梨\",null]]}}}"));

        FeishuClient.SheetRange range = client().readSheetRange("sht_abc_0");
        assertThat(server.countPath("/open-apis/sheets/v2/spreadsheets/sht_abc/values/0"))
                .isEqualTo(1);
        assertThat(range.truncated()).isFalse();
        assertThat(range.rows()).hasSize(3);
        assertThat(range.rows().get(0).get(0)).isEqualTo("名称");
        // Go 实录：float64(3) → "3"（'f' 格式、最短表示），nil → ""
        assertThat(range.rows().get(1).get(1)).isEqualTo("3");
        assertThat(range.rows().get(2).get(1)).isEmpty();
    }

    @Test
    @DisplayName("600 行 → 截到 500 行，truncated=true")
    void readSheetRangeTruncatesLargeTable() {
        server.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"tenant_access_token\":\"t\",\"expire\":7200}"));
        StringBuilder values = new StringBuilder("[");
        for (int i = 0; i < 600; i++) {
            if (i > 0) {
                values.append(',');
            }
            values.append('[').append(i).append(']');
        }
        values.append(']');
        server.handle("/open-apis/sheets/v2/spreadsheets/sht_x/values/0", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{\"valueRange\":{\"values\":"
                        + values + "}}}"));

        FeishuClient.SheetRange range = client().readSheetRange("sht_x_0");
        assertThat(range.truncated()).isTrue();
        assertThat(range.rows()).hasSize(500);
    }

    @Test
    @DisplayName("token 里没有下划线 → invalid sheet embed token（对照 Go 的 LastIndex < 0）")
    void readSheetRangeRejectsTokenWithoutUnderscore() {
        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> client().readSheetRange("nosep")))
                .isInstanceOf(com.ragagent.datasource.ConnectorException.class)
                .hasMessageContaining("invalid sheet embed token");
    }

    // ──────────────────────────────────────────────────────────────────
    // bitable
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("日期列：type==5 + date_formatter 决定只日期还是带时间，渲染在客户端时区（GMT+8）")
    void readBitableRecordsDateColumnUsesFormatterAndTimezone() {
        server.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"tenant_access_token\":\"t\",\"expire\":7200}"));
        server.handle("/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/fields", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{\"items\":["
                        + "{\"field_name\":\"截止日期\",\"type\":5,"
                        + "\"property\":{\"date_formatter\":\"yyyy/MM/dd\"}},"
                        + "{\"field_name\":\"提醒时间\",\"type\":5,"
                        + "\"property\":{\"date_formatter\":\"yyyy-MM-dd HH:mm\"}}]}}"));
        server.handle("/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/records/search",
                (ex, body) -> FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{\"has_more\":false,"
                        + "\"items\":[{\"fields\":{\"截止日期\":1711900800000,"
                        + "\"提醒时间\":1719802800000}}]}}"));

        FeishuClient.BitableTable table = client().readBitableRecords("bascabc_tblxyz");
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(1).get(0)).isEqualTo("2024-04-01");
        assertThat(table.rows().get(1).get(1)).isEqualTo("2024-07-01 11:00");
    }

    @Test
    @DisplayName("构造表格：字段名做表头，records 用 POST search 端点（不是废弃的 GET）")
    void readBitableRecordsSplitsTokenAndBuildsTable() {
        server.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"tenant_access_token\":\"t\",\"expire\":7200}"));
        server.handle("/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/fields", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{\"items\":["
                        + "{\"field_name\":\"任务\"},{\"field_name\":\"状态\"}]}}"));
        server.handle("/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/records/search",
                (ex, body) -> {
                    assertThat(ex.getRequestMethod()).isEqualTo("POST");
                    FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{\"has_more\":false,"
                            + "\"items\":[{\"fields\":{\"任务\":\"写文档\",\"状态\":\"进行中\"}}]}}");
                });

        FeishuClient.BitableTable table = client().readBitableRecords("bascabc_tblxyz");
        assertThat(table.truncated()).isFalse();
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0)).containsExactly("任务", "状态");
        assertThat(table.rows().get(1)).containsExactly("写文档", "进行中");
    }

    @Test
    @DisplayName("字段分页：page_size 必须是 100（接口上限），150 个字段要两页取全")
    void readBitableRecordsPaginatesFieldsAtMax100() {
        server.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"tenant_access_token\":\"t\",\"expire\":7200}"));
        server.handle("/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/fields", (ex, body) -> {
            assertThat(ex.getRequestURI().getQuery()).contains("page_size=100");
            String pageToken = queryParam(ex, "page_token");
            StringBuilder items = new StringBuilder("[");
            if (pageToken.isEmpty()) {
                for (int i = 0; i < 100; i++) {
                    if (i > 0) {
                        items.append(',');
                    }
                    items.append("{\"field_name\":\"f").append(i).append("\"}");
                }
                items.append(']');
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{\"items\":" + items
                        + ",\"has_more\":true,\"page_token\":\"f2\"}}");
            } else {
                for (int i = 100; i < 150; i++) {
                    if (i > 100) {
                        items.append(',');
                    }
                    items.append("{\"field_name\":\"f").append(i).append("\"}");
                }
                items.append(']');
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{\"items\":" + items
                        + ",\"has_more\":false}}");
            }
        });
        server.handle("/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/records/search",
                (ex, body) -> FeishuTestServer.sendJson(ex,
                        "{\"code\":0,\"data\":{\"has_more\":false,\"items\":[]}}"));

        FeishuClient.BitableTable table = client().readBitableRecords("bascabc_tblxyz");
        List<String> header = table.rows().get(0);
        assertThat(header).hasSize(150);
        assertThat(header.get(0)).isEqualTo("f0");
        assertThat(header.get(149)).isEqualTo("f149");
    }

    @Test
    @DisplayName("记录分页 + 超过 maxTableRows 截断（300 + 250 → 500 + 表头，truncated=true）")
    void readBitableRecordsPaginatesAndTruncatesRecords() {
        server.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"tenant_access_token\":\"t\",\"expire\":7200}"));
        server.handle("/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/fields", (ex, body) ->
                FeishuTestServer.sendJson(ex,
                        "{\"code\":0,\"data\":{\"items\":[{\"field_name\":\"col\"}]}}"));
        server.handle("/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/records/search",
                (ex, body) -> {
                    if (queryParam(ex, "page_token").isEmpty()) {
                        FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{\"has_more\":true,"
                                + "\"page_token\":\"r2\",\"items\":" + bitableRows(300) + "}}");
                    } else {
                        assertThat(queryParam(ex, "page_token")).isEqualTo("r2");
                        FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{\"has_more\":false,"
                                + "\"items\":" + bitableRows(250) + "}}");
                    }
                });

        FeishuClient.BitableTable table = client().readBitableRecords("bascabc_tblxyz");
        assertThat(table.rows()).hasSize(DocxBlocks.MAX_TABLE_ROWS + 1);
        assertThat(table.truncated()).isTrue();
    }

    @Test
    @DisplayName("恰好 500 条不算截断（上限处的 off-by-one 守卫）")
    void readBitableRecordsExactly500NotTruncated() {
        server.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"tenant_access_token\":\"t\",\"expire\":7200}"));
        server.handle("/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/fields", (ex, body) ->
                FeishuTestServer.sendJson(ex,
                        "{\"code\":0,\"data\":{\"items\":[{\"field_name\":\"col\"}]}}"));
        server.handle("/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/records/search",
                (ex, body) -> FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{\"has_more\":false,"
                        + "\"items\":" + bitableRows(DocxBlocks.MAX_TABLE_ROWS) + "}}"));

        FeishuClient.BitableTable table = client().readBitableRecords("bascabc_tblxyz");
        assertThat(table.truncated()).isFalse();
        assertThat(table.rows()).hasSize(DocxBlocks.MAX_TABLE_ROWS + 1);
    }

    @Test
    @DisplayName("记录空页防御性终止：has_more=true + 空 items → 只调一次（否则循环到任务截止）")
    void readBitableRecordsEmptyRecordPageTerminates() {
        server.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"tenant_access_token\":\"t\",\"expire\":7200}"));
        server.handle("/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/fields", (ex, body) ->
                FeishuTestServer.sendJson(ex,
                        "{\"code\":0,\"data\":{\"items\":[{\"field_name\":\"col\"}]}}"));
        server.handle("/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/records/search",
                (ex, body) -> FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{"
                        + "\"has_more\":true,\"page_token\":\"loop\",\"items\":[]}}"));

        int before = server.countPath(
                "/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/records/search");
        FeishuClient.BitableTable table = client().readBitableRecords("bascabc_tblxyz");
        assertThat(server.countPath(
                "/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/records/search") - before)
                .isEqualTo(1);
        assertThat(table.rows()).hasSize(1); // 只有表头
    }

    @Test
    @DisplayName("字段空页防御性终止：这个循环自身没有大小上限，必须 break")
    void readBitableRecordsEmptyFieldsPageTerminates() {
        server.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"tenant_access_token\":\"t\",\"expire\":7200}"));
        server.handle("/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/fields", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"data\":{"
                        + "\"has_more\":true,\"page_token\":\"loop\",\"items\":[]}}"));
        server.handle("/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/records/search",
                (ex, body) -> FeishuTestServer.sendJson(ex,
                        "{\"code\":0,\"data\":{\"has_more\":false,\"items\":[]}}"));

        int before = server.countPath(
                "/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/fields");
        client().readBitableRecords("bascabc_tblxyz");
        assertThat(server.countPath(
                "/open-apis/bitable/v1/apps/bascabc/tables/tblxyz/fields") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("bitable token 没有下划线 → invalid bitable embed token")
    void readBitableRecordsRejectsTokenWithoutUnderscore() {
        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> client().readBitableRecords("nosep")))
                .isInstanceOf(com.ragagent.datasource.ConnectorException.class)
                .hasMessageContaining("invalid bitable embed token");
    }

    // ── 助手 ────────────────────────────────────────────────────────────

    private static String queryParam(com.sun.net.httpserver.HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return "";
        }
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            if (k.equals(name)) {
                return i < 0 ? "" : java.net.URLDecoder.decode(kv.substring(i + 1),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String bitableRows(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"fields\":{\"col\":\"v").append(i).append("\"}}");
        }
        return sb.append(']').toString();
    }
}
