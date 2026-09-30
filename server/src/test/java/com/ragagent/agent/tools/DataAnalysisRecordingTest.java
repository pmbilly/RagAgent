package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.agent.tools.DataAnalysisTool.AnalysisDuckDb;
import com.ragagent.agent.tools.DataAnalysisTool.KnowledgeData;
import com.ragagent.agent.tools.DataAnalysisTool.KnowledgeFileMaterializer;
import com.ragagent.agent.tools.DataAnalysisTool.KnowledgeLoader;
import com.ragagent.agent.tools.DataAnalysisTool.QueryResult;

/**
 * 波 4.5b 回放：data_analysis（对照 Go 实录，探针 {@code zz_rec45b_danalysis_test.go}）。
 *
 * <p>与 Go 探针同构：duckdb_jdbc 内存库（excel/spatial 扩展需本机预装——
 * {@code INSTALL excel/spatial} 一次性联网，之后回放只 LOAD，缺扩展时显式失败）、
 * 纯 JDK 造同内容 xlsx、stub KnowledgeLoader/FileMaterializer。</p>
 *
 * <p>已知差异：DuckDB driver 错误文案 Go 1.5.2 vs Java 1.1.3（missing-column 的
 * Candidate bindings/LINE 细节不同）——两条 missing_column 语料只断言稳定部分
 * （前缀 + Referenced column 片段 + 建议后缀），其余语料逐字比对。</p>
 */
class DataAnalysisRecordingTest {

    @TempDir
    static Path dir;

    static Map<String, KnowledgeData> knowledge;

    @BeforeAll
    static void fixtures() throws Exception {
        knowledge = new LinkedHashMap<>();

        Path csv = dir.resolve("people.csv");
        Files.writeString(csv,
                "id,name,amount,city\n"
                        + "1,Alice,100,Paris\n"
                        + "2,Bob,250,\n"
                        + "3,Carol,175,Lyon\n"
                        + "4,D&E <Co>,10,Rome\n");
        knowledge.put("da45bcsv1", new KnowledgeData("da45bcsv1", "da45bkb01", 10002, "csv",
                csv.toString()));

        Path spaced = dir.resolve("spaced.csv");
        Files.writeString(spaced, "User Name,amount\nAlice,10\nBob,20\n");
        knowledge.put("da45bspc1", new KnowledgeData("da45bspc1", "da45bkb01", 10002, "csv",
                spaced.toString()));

        Path pdf = dir.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-fake");
        knowledge.put("da45bpdf1", new KnowledgeData("da45bpdf1", "da45bkb01", 10002, "pdf",
                pdf.toString()));

        Path multi = dir.resolve("multi.xlsx");
        zzWriteWorkbook(multi, new LinkedHashMap<>() {
            {
                put("Sales", List.of(
                        List.of("id", "amount"),
                        List.of("1", "100"),
                        List.of("2", "250")));
                put("Inventory", List.of(
                        List.of("sku", "stock"),
                        List.of("A", "5"),
                        List.of("B", "12"),
                        List.of("C", "7")));
            }
        });
        knowledge.put("da45bxlsx1", new KnowledgeData("da45bxlsx1", "da45bkb01", 10002, "xlsx",
                multi.toString()));

        Path single = dir.resolve("single.xlsx");
        zzWriteWorkbook(single, new LinkedHashMap<>() {
            {
                put("Only", List.of(
                        List.of("k", "v"),
                        List.of("x", "1"),
                        List.of("y", "2")));
            }
        });
        knowledge.put("da45bxlsxs1", new KnowledgeData("da45bxlsxs1", "da45bkb01", 10002, "xlsx",
                single.toString()));
    }

    /** 最小 xlsx 写出器（sharedStrings + 数字/字符串单元格；与 Go excelize 造的内容等价）。 */
    static void zzWriteWorkbook(Path path, LinkedHashMap<String, List<List<String>>> sheets)
            throws Exception {
        List<String> shared = new ArrayList<>();
        Map<String, Integer> sharedIdx = new LinkedHashMap<>();
        for (List<List<String>> rows : sheets.values()) {
            for (List<String> row : rows) {
                for (String cell : row) {
                    sharedIdx.putIfAbsent(cell, shared.size());
                    if (!shared.contains(cell)) {
                        shared.add(cell);
                    }
                }
            }
        }

        StringBuilder contentTypes = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                        + "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>");
        StringBuilder workbookSheets = new StringBuilder();
        StringBuilder workbookRels = new StringBuilder();
        Map<String, byte[]> worksheetXmls = new LinkedHashMap<>();
        int sheetNo = 0;
        for (Map.Entry<String, List<List<String>>> e : sheets.entrySet()) {
            sheetNo++;
            contentTypes.append("<Override PartName=\"/xl/worksheets/sheet").append(sheetNo)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            workbookSheets.append("<sheet name=\"").append(xmlEscape(e.getKey()))
                    .append("\" sheetId=\"").append(sheetNo)
                    .append("\" r:id=\"rId").append(sheetNo).append("\"/>");
            workbookRels.append("<Relationship Id=\"rId").append(sheetNo)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""
                            + " Target=\"worksheets/sheet").append(sheetNo).append(".xml\"/>");
            StringBuilder rowsXml = new StringBuilder();
            int r = 0;
            for (List<String> row : e.getValue()) {
                r++;
                rowsXml.append("<row r=\"").append(r).append("\">");
                for (int c = 0; c < row.size(); c++) {
                    String ref = colName(c) + r;
                    String cell = row.get(c);
                    if (cell.matches("-?\\d+")) {
                        rowsXml.append("<c r=\"").append(ref).append("\"><v>").append(cell)
                                .append("</v></c>");
                    } else {
                        rowsXml.append("<c r=\"").append(ref).append("\" t=\"s\"><v>")
                                .append(sharedIdx.get(cell)).append("</v></c>");
                    }
                }
                rowsXml.append("</row>");
            }
            worksheetXmls.put("xl/worksheets/sheet" + sheetNo + ".xml",
                    ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                            + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                            + "<sheetData>" + rowsXml + "</sheetData></worksheet>")
                            .getBytes(StandardCharsets.UTF_8));
        }
        contentTypes.append("</Types>");

        StringBuilder sst = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"")
                .append(shared.size()).append("\" uniqueCount=\"").append(shared.size()).append("\">");
        for (String s : shared) {
            sst.append("<si><t>").append(xmlEscape(s)).append("</t></si>");
        }
        sst.append("</sst>");

        byte[] workbookXml = ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets>" + workbookSheets + "</sheets></workbook>")
                .getBytes(StandardCharsets.UTF_8);
        byte[] rootRels = ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>").getBytes(StandardCharsets.UTF_8);
        byte[] wbRels = ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + workbookRels + "</Relationships>").getBytes(StandardCharsets.UTF_8);

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(contentTypes.toString().getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write(rootRels);
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zip.write(workbookXml);
            zip.putNextEntry(new ZipEntry("xl/_rels/workbook.xml.rels"));
            zip.write(wbRels);
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            zip.write(sst.toString().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> e : worksheetXmls.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
            }
        }
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String colName(int c) {
        StringBuilder sb = new StringBuilder();
        int n = c;
        do {
            sb.append((char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.reverse().toString();
    }

    // ==================== DuckDB JDBC seam ====================

    /** 对照 Go {@code *sql.DB}：内存 DuckDB + excel/spatial 扩展（缺扩展显式失败）。 */
    static final class JdbcDuckDb implements AnalysisDuckDb {
        final Connection conn;

        JdbcDuckDb() {
            try {
                conn = DriverManager.getConnection("jdbc:duckdb:");
                try (Statement st = conn.createStatement()) {
                    st.execute("LOAD spatial;");
                    st.execute("LOAD excel;");
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(
                        "DuckDB excel/spatial 扩展不可用（需先一次性 INSTALL 预装）: " + e.getMessage(), e);
            }
        }

        @Override
        public void exec(String sql) {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            } catch (java.sql.SQLException e) {
                // 已知差异（driver 版本）：DuckDB 1.1.3 的 excel 扩展没有 read_xlsx
                // （1.2+ 才有），改用 spatial 的 GDAL ST_Read 执行等价视图。
                // 仅对"函数不存在"错误做一次性改写重试，其余错误原样抛出。
                String msg = e.getMessage();
                if (msg != null && msg.contains("read_xlsx") && msg.contains("does not exist")) {
                    String rewritten = sql
                            .replaceAll("read_xlsx\\('([^']*)', sheet = '([^']*)', header=true, all_varchar=true\\)",
                                    "ST_Read('$1', layer => '$2')")
                            .replaceAll("read_xlsx\\('([^']*)', header=true, all_varchar=true\\)",
                                    "ST_Read('$1')");
                    try (Statement st = conn.createStatement()) {
                        st.execute(rewritten);
                        return;
                    } catch (java.sql.SQLException e2) {
                        throw new RuntimeException(e2.getMessage(), e2);
                    }
                }
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        @Override
        public QueryResult query(String sql) {
            try (Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    columns.add(md.getColumnName(i));
                }
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= n; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return new QueryResult(columns, rows);
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        @Override
        public List<String> listSheets(String xlsxPath) {
            QueryResult qr = query(
                    "SELECT UNNEST(layers).name FROM st_read_meta('" + xlsxPath + "')");
            List<String> names = new ArrayList<>();
            for (List<Object> row : qr.rows()) {
                Object v = row.get(0);
                if (v == null || v.toString().trim().isEmpty()) {
                    continue;
                }
                names.add(v.toString());
            }
            return names;
        }
    }

    /** 对照 materializeKnowledgeFile：复制到带扩展名的临时文件。 */
    static final class CopyMaterializer implements KnowledgeFileMaterializer {
        @Override
        public Path materialize(KnowledgeData k) {
            try {
                String suffix = k.fileType() == null || k.fileType().isEmpty()
                        ? "" : "." + k.fileType().toLowerCase(java.util.Locale.ROOT);
                Path tmp = Files.createTempFile("weknora-data-analysis-", suffix);
                Files.copy(Path.of(k.filePath()), tmp,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return tmp;
            } catch (java.io.IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    // ==================== 回放框架 ====================

    private static JsonNode rec(String name) {
        try {
            String json = (String) GoRecording45B.class
                    .getField("R_" + name.toUpperCase(java.util.Locale.ROOT)).get(null);
            return GoRecording45B.rec(json);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolRequest req(JsonNode r) {
        try {
            return ToolRequest.of(RecordingSupport.PLAIN.readTree(r.get("args").asText()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertToolResult(String label, ToolResult result, JsonNode r) {
        assertThat(result.isSuccess()).as("%s success", label).isEqualTo(r.get("success").asBoolean());
        assertThat(result.getOutput()).as("%s output", label).isEqualTo(r.get("output").asText());
        String wantError = r.hasNonNull("error") ? r.get("error").asText() : "";
        if (!wantError.isEmpty()) {
            assertThat(result.getError()).as("%s error", label).isEqualTo(wantError);
        }
        JsonNode wantData = r.get("data");
        if (wantData == null || wantData.isNull()) {
            assertThat(result.getData()).as("%s data", label).isNull();
        } else {
            assertThat(RecordingSupport.canonicalJson(RecordingSupport.PLAIN.valueToTree(result.getData())))
                    .as("%s data", label)
                    .isEqualTo(RecordingSupport.canonicalJson(wantData));
        }
    }

    /** missing-column 语料的宽松断言：driver 错误文案（Candidate bindings/LINE）因 DuckDB 版本而异，只锁稳定部分。 */
    private static void assertMissingColumn(String label, ToolResult result, JsonNode r,
            String column, String suggestionSuffix) {
        assertThat(result.isSuccess()).as("%s success", label).isFalse();
        assertThat(result.getOutput()).as("%s output", label).isEqualTo("");
        String recorded = r.get("error").asText();
        assertThat(recorded).startsWith("Query execution failed: query execution failed: Binder Error:");
        // Java 1.1.3 driver 的 SQLException 消息多一层 "java.sql.SQLException: " 包装
        // （已知差异），只锁稳定部分：前缀 + Binder Error + Referenced column 片段。
        assertThat(result.getError()).startsWith("Query execution failed: query execution failed:");
        assertThat(result.getError()).contains("Binder Error: Referenced column \"" + column + "\" not found");
        String wantSuffix = suggestionSuffix == null ? "" : ". " + suggestionSuffix;
        if (!wantSuffix.isEmpty()) {
            assertThat(result.getError()).endsWith(wantSuffix);
            assertThat(recorded).endsWith(wantSuffix);
        }
    }

    private static KnowledgeLoader loader() {
        return id -> knowledge.get(id);
    }

    private static DataAnalysisTool tool() {
        return new DataAnalysisTool(loader(), new CopyMaterializer(), new JdbcDuckDb(), "zz45b");
    }

    // ==================== 用例 ====================

    @Test
    void schemaContract() {
        JsonNode schema = RecordingSupport.readTree(rec("data_analysis_schema").get("schema").asText());
        assertThat(tool().getParameters()).isEqualTo(schema);
    }

    @Test
    void buildExcelCreateTableSqlCorpus() {
        assertThat(DataAnalysisTool.buildExcelCreateTableSQL("tbl", "/tmp/data.xlsx", null))
                .isEqualTo(rec("data_analysis_buildsql_empty").get("sql").asText());
        assertThat(DataAnalysisTool.buildExcelCreateTableSQL("tbl", "/tmp/data.xlsx", List.of("Sheet1")))
                .isEqualTo(rec("data_analysis_buildsql_single").get("sql").asText());
        assertThat(DataAnalysisTool.buildExcelCreateTableSQL("tbl", "/tmp/data.xlsx",
                List.of("Sheet1", "Sheet2", "报表")))
                .isEqualTo(rec("data_analysis_buildsql_multi").get("sql").asText());
        assertThat(DataAnalysisTool.buildExcelCreateTableSQL("tbl", "/tmp/O'Brien/data.xlsx",
                List.of("it's", "a\"b")))
                .isEqualTo(rec("data_analysis_buildsql_quote").get("sql").asText());
    }

    @Test
    void csvPaths() {
        String[] ids = {
                "csv_select_all", "csv_where", "csv_aggregate_ok", "csv_null_cell",
                "csv_html_escape", "empty_result", "spaced_reconcile",
        };
        for (String id : ids) {
            JsonNode r = rec("data_analysis_" + id);
            assertToolResult(id, tool().execute(req(r)), r);
        }
    }

    @Test
    void excelPaths() {
        String[] ids = {"excel_multi_group", "excel_multi_all", "excel_single_sheet"};
        for (String id : ids) {
            JsonNode r = rec("data_analysis_" + id);
            assertToolResult(id, tool().execute(req(r)), r);
        }
    }

    @Test
    void showTables() {
        JsonNode r = rec("data_analysis_show_tables");
        assertToolResult("show_tables", tool().execute(req(r)), r);
    }

    @Test
    void validationErrors() {
        String[] ids = {
                "describe_rejected", "pragma_rejected", "readonly_reject", "multiple_statements",
                "table_not_allowed", "dangerous_function", "range_function_from",
                "union_rejected", "csv_cast_rejected",
        };
        for (String id : ids) {
            JsonNode r = rec("data_analysis_" + id);
            assertToolResult(id, tool().execute(req(r)), r);
        }
    }

    @Test
    void subqueryAllowed() {
        JsonNode r = rec("data_analysis_subquery_allowed");
        assertToolResult("subquery_allowed", tool().execute(req(r)), r);
    }

    @Test
    void missingColumnPlain() {
        JsonNode r = rec("data_analysis_missing_column_plain");
        assertMissingColumn("missing_column_plain", tool().execute(req(r)), r, "nosuchcol", null);
    }

    @Test
    void missingColumnSuggest() {
        // 语料无建议后缀：Go normalize 只去空格不去下划线，"user_name" ≠ "User Name"
        // （username），suggestion 不触发——正是该语料锁的行为。
        JsonNode r = rec("data_analysis_missing_column_suggest");
        assertMissingColumn("missing_column_suggest", tool().execute(req(r)), r, "user_name", null);
    }

    @Test
    void loadFailures() {
        String[] ids = {"load_not_found", "unsupported_filetype"};
        for (String id : ids) {
            JsonNode r = rec("data_analysis_" + id);
            assertToolResult(id, tool().execute(req(r)), r);
        }
    }

    @Test
    void scopeAuth() {
        SearchTarget.SearchTargets allowed = new SearchTarget.SearchTargets(List.of(
                SearchTarget.wholeKb("da45bkb01", 10002)));
        JsonNode r1 = rec("data_analysis_scope_allowed");
        assertToolResult("scope_allowed", tool().withSearchTargets(allowed).execute(req(r1)), r1);

        SearchTarget.SearchTargets denied = new SearchTarget.SearchTargets(List.of(
                SearchTarget.wholeKb("da45bother", 10002)));
        JsonNode r2 = rec("data_analysis_scope_denied");
        assertToolResult("scope_denied", tool().withSearchTargets(denied).execute(req(r2)), r2);

        JsonNode r3 = rec("data_analysis_scope_empty");
        assertToolResult("scope_empty", tool().withSearchTargets(null).execute(req(r3)), r3);
    }

    @Test
    void repeatQuery() {
        DataAnalysisTool shared = tool();
        JsonNode r1 = rec("data_analysis_repeat_first");
        assertToolResult("repeat_first", shared.execute(req(r1)), r1);
        JsonNode r2 = rec("data_analysis_repeat_second");
        assertToolResult("repeat_second", shared.execute(req(r2)), r2);
    }
}
