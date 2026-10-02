package com.ragagent.agent.tools.data;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.GoJsonCodec;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.agent.tools.BaseTool;
import com.ragagent.agent.tools.Cleanable;
import com.ragagent.agent.tools.knowledge.KnowledgeSearchTool;
import com.ragagent.agent.tools.SearchAuth;
import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.agent.tools.sql.SqlGuard;
import com.ragagent.agent.tools.ToolDefinitions;
import com.ragagent.agent.tools.ToolRequest;

/**
 * data_analysis 工具。
 *
 * <p>DuckDB 访问经 {@link AnalysisDuckDb} seam（CREATE TABLE 装载 / DESCRIBE 取 schema /
 * COUNT / 用户查询 / st_read_meta 枚举 sheet——整段 driver 交互在 seam 实现侧，
 * 回放测试用 duckdb_jdbc 内存库实现）。知识文件经 {@link KnowledgeLoader}（按 ID 取知识）
 * 与 {@link KnowledgeFileMaterializer}（取文件 + 临时文件物化；storage 后端解析在实现侧）
 * 两个接缝。</p>
 *
 * <p>SQL 校验复用 {@link SqlGuard} 的可配置入口（单表白名单 + 单语句 + 无危险函数
 * ——无 select-only/子查询/CTE/schema/系统列检查）。parse 错误文案差异
 * 见 SqlGuard 文档；DuckDB driver 错误文案（1.5.2 vs 1.1.3）差异已知。</p>
 */
public class DataAnalysisTool extends BaseTool implements Cleanable {

    /** schema 字节即契约：以探针 _schema 语料钉死的字节形态为准。 */
    private static final String SCHEMA_JSON = """
            {"type":"object","properties":{"knowledge_id":{"type":"string","description":"short dN document ID to query"},"sql":{"type":"string","description":"SQL to be executed on knowledge"}},"required":["knowledge_id","sql"],"additionalProperties":false}""";

    private static final String DESCRIPTION = "Use this tool when the knowledge is CSV or Excel files. It loads the data into memory and executes SQL for data analysis. "
            + "For Excel files with multiple sheets, every sheet is loaded into the same table and the source sheet name is exposed as a '__sheet_name' column so you can filter/aggregate per sheet. "
            + "If the user's question requires data statistics, convert the question into SQL and execute it.";

    static final String EXCEL_SHEET_NAME_COLUMN = "__sheet_name";

    /** 知识视图（跨模块最小字段集）。 */
    public record KnowledgeData(String id, String knowledgeBaseId, long tenantId, String fileType,
            String filePath) {
    }

    /** 按 ID 取知识：返回 null = "empty result"（记入失败文案）；抛异常 = 装载失败。 */
    public interface KnowledgeLoader {
        KnowledgeData byIdOnly(String knowledgeId);
    }

    /**
     * 把知识文件物化成带正确扩展名的本地临时文件（工具侧用后即删）。
     * storage 后端解析在实现侧。
     */
    public interface KnowledgeFileMaterializer {
        Path materialize(KnowledgeData knowledge);
    }

    /** DuckDB 访问 seam（本工具的全部 driver 交互）。 */
    public interface AnalysisDuckDb {
        /** 执行写语句（CREATE TABLE / DROP TABLE）。 */
        void exec(String sql);

        /** 执行查询并返回列名/行值（null = SQL NULL）。 */
        QueryResult query(String sql);

        /** 枚举 xlsx 的 sheet（st_read_meta；失败时调用点回退首 sheet）。 */
        List<String> listSheets(String xlsxPath);
    }

    /** 一次查询的列名 + 行值（null = SQL NULL）。 */
    public record QueryResult(List<String> columns, List<List<Object>> rows) {
    }

    /** 列信息（名/类型/可空）。 */
    public record ColumnInfo(String name, String type, String nullable) {
    }

    /** 表 schema（表名 + 列 + 行数）。 */
    public record TableSchema(String tableName, List<ColumnInfo> columns, long rowCount) {
    }

    private final KnowledgeLoader knowledgeLoader;
    private final KnowledgeFileMaterializer materializer;
    private final AnalysisDuckDb duckDb;
    private final String sessionID;
    private final List<String> createdTables = new ArrayList<>();
    private SearchTarget.SearchTargets searchTargets;
    private boolean scopeEnforced;

    public DataAnalysisTool(KnowledgeLoader knowledgeLoader, KnowledgeFileMaterializer materializer,
            AnalysisDuckDb duckDb, String sessionID) {
        super(ToolDefinitions.TOOL_DATA_ANALYSIS, DESCRIPTION, SCHEMA_JSON);
        this.knowledgeLoader = knowledgeLoader;
        this.materializer = materializer;
        this.duckDb = duckDb;
        this.sessionID = sessionID == null ? "" : sessionID;
    }

    /** 启用作用域授权（scopeEnforced 独立于列表长度：无 target 全拒）。 */
    public DataAnalysisTool withSearchTargets(SearchTarget.SearchTargets searchTargets) {
        this.searchTargets = searchTargets;
        this.scopeEnforced = true;
        return this;
    }

    // ==================== 装载路径 ====================

    boolean recordCreatedTable(String tableName) {
        if (createdTables.contains(tableName)) {
            return false;
        }
        createdTables.add(tableName);
        return true;
    }

    public void cleanup() {
        for (String tableName : new ArrayList<>(createdTables)) {
            try {
                duckDb.exec(String.format("DROP TABLE IF EXISTS \"%s\"", tableName));
            } catch (RuntimeException e) {
                // 单表失败继续清其它表。
            }
        }
        createdTables.clear();
    }

    TableSchema loadFromKnowledgeID(String knowledgeID) {
        KnowledgeData knowledge;
        try {
            knowledge = knowledgeLoader.byIdOnly(knowledgeID);
        } catch (RuntimeException e) {
            throw new RuntimeException("failed to get knowledge by ID: " + e.getMessage(), e);
        }
        if (knowledge == null) {
            throw new RuntimeException(
                    "failed to get knowledge by ID: knowledge service returned an empty result");
        }
        return loadFromKnowledge(knowledge);
    }

    TableSchema loadFromKnowledge(KnowledgeData knowledge) {
        String tableName = tableName(knowledge);
        String fileType = knowledge.fileType() == null ? "" : knowledge.fileType().toLowerCase(Locale.ROOT);

        Path localPath;
        try {
            localPath = materializer.materialize(knowledge);
        } catch (RuntimeException e) {
            throw new RuntimeException(
                    String.format("failed to materialize knowledge '%s' for DuckDB: %s", knowledge.id(),
                            e.getMessage()), e);
        }
        try {
            return switch (fileType) {
                case "csv" -> loadFromCSV(localPath.toString(), tableName);
                case "xlsx", "xls" -> loadFromExcel(localPath.toString(), tableName);
                default -> throw new RuntimeException(
                        String.format("unsupported file type: %s (supported types: csv, xlsx, xls)", fileType));
            };
        } finally {
            deleteQuietly(localPath);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (java.io.IOException e) {
            // best-effort：删除失败不影响装载结果。
        }
    }

    TableSchema loadFromCSV(String filename, String tableName) {
        if (recordCreatedTable(tableName)) {
            String createTableSQL = String.format(
                    "CREATE TABLE \"%s\" AS SELECT * FROM read_csv_auto('%s', header=true, all_varchar=true)",
                    tableName, sqlSingleQuoteEscape(filename));
            try {
                duckDb.exec(createTableSQL);
            } catch (RuntimeException e) {
                throw new RuntimeException("failed to create table from CSV: " + e.getMessage(), e);
            }
        }
        return loadFromTable(tableName);
    }

    /** sheet 枚举失败回退首 sheet。 */
    TableSchema loadFromExcel(String filename, String tableName) {
        if (recordCreatedTable(tableName)) {
            List<String> sheetNames;
            try {
                sheetNames = duckDb.listSheets(filename);
            } catch (RuntimeException e) {
                sheetNames = List.of();
            }
            String createTableSQL = buildExcelCreateTableSQL(tableName, filename, sheetNames);
            try {
                duckDb.exec(createTableSQL);
            } catch (RuntimeException e) {
                throw new RuntimeException(String.format(
                        "failed to create table from Excel file (sheets=%s): %s",
                        goSliceString(sheetsOrEmpty(sheetNames)), e.getMessage()), e);
            }
        }
        return loadFromTable(tableName);
    }

    private static List<String> sheetsOrEmpty(List<String> sheetNames) {
        return sheetNames == null ? List.of() : sheetNames;
    }

    /** 纯函数：按 sheet 数量（无/单/多）生成建表 SQL。 */
    static String buildExcelCreateTableSQL(String tableName, String filename, List<String> sheetNames) {
        String escFile = sqlSingleQuoteEscape(filename);

        // No sheet info (enumeration failed or empty): read the first sheet only.
        if (sheetNames == null || sheetNames.isEmpty()) {
            return String.format(
                    "CREATE TABLE \"%s\" AS SELECT * FROM read_xlsx('%s', header=true, all_varchar=true)",
                    tableName, escFile);
        }

        // Single sheet: keep it simple but still tag the source for consistency
        // with the multi-sheet path.
        if (sheetNames.size() == 1) {
            String escSheet = sqlSingleQuoteEscape(sheetNames.get(0));
            return String.format(
                    "CREATE TABLE \"%s\" AS SELECT *, '%s' AS %s FROM read_xlsx('%s', sheet = '%s', header=true, all_varchar=true)",
                    tableName, escSheet, EXCEL_SHEET_NAME_COLUMN, escFile, escSheet);
        }

        // Multiple sheets: UNION ALL BY NAME tolerates schema differences
        // between sheets (missing columns become NULL, conflicting types are
        // widened).
        List<String> parts = new ArrayList<>(sheetNames.size());
        for (String sheet : sheetNames) {
            String escSheet = sqlSingleQuoteEscape(sheet);
            parts.add(String.format(
                    "SELECT *, '%s' AS %s FROM read_xlsx('%s', sheet = '%s', header=true, all_varchar=true)",
                    escSheet, EXCEL_SHEET_NAME_COLUMN, escFile, escSheet));
        }
        return String.format("CREATE TABLE \"%s\" AS %s", tableName,
                String.join("\nUNION ALL BY NAME\n", parts));
    }

    /** DESCRIBE 取列 + COUNT 取行数，组装表 schema。 */
    TableSchema loadFromTable(String tableName) {
        QueryResult describe;
        try {
            describe = duckDb.query(String.format("DESCRIBE \"%s\"", tableName));
        } catch (RuntimeException e) {
            throw new RuntimeException("failed to get table schema: " + e.getMessage(), e);
        }
        List<ColumnInfo> columns = new ArrayList<>();
        for (List<Object> row : describe.rows()) {
            String colName = row.size() > 0 ? (String) row.get(0) : null;
            String colType = row.size() > 1 ? (String) row.get(1) : null;
            String nullable = row.size() > 2 ? (String) row.get(2) : null;
            columns.add(new ColumnInfo(colName, colType, nullable));
        }

        long rowCount;
        try {
            QueryResult count = duckDb.query(String.format("SELECT COUNT(*) FROM \"%s\"", tableName));
            Object v = count.rows().get(0).get(0);
            rowCount = v instanceof Number num ? num.longValue() : 0L;
        } catch (RuntimeException e) {
            throw new RuntimeException("failed to get row count: " + e.getMessage(), e);
        }
        return new TableSchema(tableName, columns, rowCount);
    }

    static String tableName(KnowledgeData knowledge) {
        return "k_" + (knowledge.id() == null ? "" : knowledge.id()).replace("-", "_");
    }

    // ==================== Execute ====================

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        String knowledgeID = args == null ? "" : args.path("knowledge_id").asText("");
        String sql = args == null ? "" : args.path("sql").asText("");

        if (scopeEnforced) {
            try {
                SearchAuth.authorizeKnowledgeInSearchTargets(searchTargets, knowledgeID,
                        knowledgeScopeReader());
            } catch (SearchAuth.ScopeAuthException e) {
                return failure(e.getMessage());
            }
        }

        TableSchema schema;
        try {
            schema = loadFromKnowledgeID(knowledgeID);
        } catch (RuntimeException e) {
            return failure(String.format("Failed to load knowledge ID '%s': %s", knowledgeID, e.getMessage()));
        }

        // Replace knowledge ID with table name（含字符串
        // 字面量内出现的 ID 也替换的怪癖照录）。
        sql = sql.replace(knowledgeID, schema.tableName());
        ReconcileResult reconciled = reconcileSQLColumnsWithSchema(sql, schema);
        sql = reconciled.sql();

        // Check if this is a read-only query
        String normalizedSQL = sql.trim().toLowerCase(Locale.ROOT);
        boolean isReadOnly = normalizedSQL.startsWith("select")
                || normalizedSQL.startsWith("show")
                || normalizedSQL.startsWith("describe")
                || normalizedSQL.startsWith("explain")
                || normalizedSQL.startsWith("pragma");

        if (!isReadOnly) {
            return failure("DuckDB tool only supports read-only queries (SELECT, SHOW, DESCRIBE, EXPLAIN, PRAGMA)."
                    + " Modification operations (INSERT, UPDATE, DELETE, CREATE, DROP, etc.) are not allowed.");
        }

        SqlGuard.SqlValidationResult validation =
                SqlGuard.validate(sql, SqlGuard.GuardConfig.dataAnalysis(schema.tableName()));
        if (!validation.isValid()) {
            return failure("SQL validation failed: " + goFormatValidationErrors(validation.getErrors()));
        }

        List<Map<String, String>> results;
        try {
            results = executeSingleQuery(sql);
        } catch (RuntimeException e) {
            String suggestion = buildMissingColumnSuggestion(e.getMessage(), schema);
            if (!suggestion.isEmpty()) {
                return failure(String.format("Query execution failed: %s. %s", e.getMessage(), suggestion));
            }
            return failure("Query execution failed: " + e.getMessage());
        }

        String queryOutput = formatQueryResults(results, sql);
        ToolResult result = new ToolResult();
        result.setSuccess(true);
        result.setOutput(queryOutput);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", results);
        data.put("row_count", results.size());
        data.put("query", sql);
        data.put("display_type", ToolDefinitions.TOOL_DATA_ANALYSIS);
        data.put("session_id", sessionID);
        result.setData(data);
        return result;
    }

    /** SearchAuth 的 KnowledgeScopeReader 适配（tag 查询不在本工具语料路径，返回空表）。 */
    private SearchAuth.KnowledgeScopeReader knowledgeScopeReader() {
        return new SearchAuth.KnowledgeScopeReader() {
            @Override
            public SearchAuth.KnowledgeView byIdOnly(String knowledgeId) {
                KnowledgeData k = knowledgeLoader == null ? null : knowledgeLoader.byIdOnly(knowledgeId);
                if (k == null) {
                    return null;
                }
                return new SearchAuth.KnowledgeView(k.id(), k.knowledgeBaseId(), "", "");
            }

            @Override
            public Map<String, List<SearchAuth.TagView>> fetchTags(List<String> knowledgeIds) {
                return Map.of();
            }
        };
    }

    /** 行扫描：byte[]→UTF-8 文本，其余按 {@link #goFormatV} 形态；null → "&lt;nil&gt;"。 */
    List<Map<String, String>> executeSingleQuery(String sqlQuery) {
        QueryResult qr;
        try {
            qr = duckDb.query(sqlQuery);
        } catch (RuntimeException e) {
            throw new RuntimeException("query execution failed: " + e.getMessage(), e);
        }
        List<String> columns = qr.columns() == null ? List.of() : qr.columns();
        List<Map<String, String>> results = new ArrayList<>();
        for (List<Object> rowValues : qr.rows()) {
            Map<String, String> rowMap = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                Object val = i < rowValues.size() ? rowValues.get(i) : null;
                rowMap.put(columns.get(i), goFormatV(val));
            }
            results.add(rowMap);
        }
        return results;
    }

    /** 值的输出形态：null→"&lt;nil&gt;"，byte[]→UTF-8 文本，浮点去尾零，其余 toString。 */
    static String goFormatV(Object val) {
        if (val == null) {
            return "<nil>";
        }
        if (val instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        if (val instanceof String s) {
            return s;
        }
        if (val instanceof Boolean bool) {
            return bool.toString();
        }
        if (val instanceof Integer || val instanceof Long) {
            return val.toString();
        }
        if (val instanceof Double d) {
            // 浮点形态：最短表示、去尾零。
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        if (val instanceof Float f) {
            return BigDecimal.valueOf(f.doubleValue()).stripTrailingZeros().toPlainString();
        }
        if (val instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        return val.toString();
    }

    /** 结果渲染（record 行为 JSONL；行内键按字节序排序 + HTML 转义）。 */
    String formatQueryResults(List<Map<String, String>> results, String query) {
        StringBuilder output = new StringBuilder();
        output.append("=== DuckDB Query Results ===\n\n");
        output.append(String.format("Executed SQL: %s\n\n", query));
        output.append(String.format("Returned %d rows\n\n", results.size()));

        if (results.isEmpty()) {
            output.append("No matching records found.\n");
            return output.toString();
        }

        output.append("=== Data Details ===\n\n");
        if (results.size() > 10) {
            output.append(String.format("Showing all %d records. Consider using a LIMIT clause to restrict"
                    + " the result count for better performance.\n\n", results.size()));
        }

        for (int i = 0; i < results.size(); i++) {
            Map<String, String> record = results.get(i);
            // record 行内键按字节序排序 + HTML 转义（<>& → \u003c…）。
            String recordStr = GoJsonCodec.write(
                    KnowledgeSearchTool.RecordingSupportHolder.MAPPER.valueToTree(new TreeMap<>(record)));
            output.append(String.format("record %d: %s\n", i + 1, recordStr));
        }
        return output.toString();
    }

    private ToolResult failure(String error) {
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(error);
        return r;
    }

    // ==================== 标识符调和与错误建议 ====================

    /** 单引号翻倍，防 SQL 字符串字面量逃逸。 */
    static String sqlSingleQuoteEscape(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    /** 标识符匹配归一化：trim + 小写 + 去半/全角空格。 */
    static String normalizeIdentifierForMatch(String s) {
        String normalized = s.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replace(" ", "");
        normalized = normalized.replace("　", "");
        return normalized;
    }

    /** 调和结果：改写后的 SQL 与替换清单。 */
    record ReconcileResult(String sql, List<String> fixes) {
    }

    /** 双引号标识符调和：按归一化名替换为 schema 中的规范名。 */
    static ReconcileResult reconcileSQLColumnsWithSchema(String sqlText, TableSchema schema) {
        if (schema == null || schema.columns().isEmpty()) {
            return new ReconcileResult(sqlText, List.of());
        }

        Map<String, String> normalizedToCanonical = new LinkedHashMap<>();
        for (ColumnInfo col : schema.columns()) {
            String key = normalizeIdentifierForMatch(col.name());
            if (key.isEmpty()) {
                continue;
            }
            normalizedToCanonical.putIfAbsent(key, col.name());
        }

        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(sqlText);
        List<String> fixes = new ArrayList<>();
        StringBuilder rewritten = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String canonical = normalizedToCanonical.get(normalizeIdentifierForMatch(name));
            if (canonical == null || canonical.equals(name)) {
                matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group()));
            } else {
                fixes.add(String.format("\"%s\" -> \"%s\"", name, canonical));
                matcher.appendReplacement(rewritten,
                        Matcher.quoteReplacement(String.format("\"%s\"", canonical)));
            }
        }
        matcher.appendTail(rewritten);
        return new ReconcileResult(rewritten.toString(), fixes);
    }

    /** 从 driver 的 "Referenced column … not found" 错误生成"你是否想要"建议。 */
    static String buildMissingColumnSuggestion(String errMsg, TableSchema schema) {
        if (errMsg == null || schema == null) {
            return "";
        }
        if (!errMsg.contains("Referenced column \"") || !errMsg.contains("not found")) {
            return "";
        }

        Matcher matcher = Pattern.compile("Referenced column \"([^\"]+)\" not found").matcher(errMsg);
        if (!matcher.find()) {
            return "";
        }

        String missing = matcher.group(1);
        String normalizedMissing = normalizeIdentifierForMatch(missing);
        if (normalizedMissing.isEmpty()) {
            return "";
        }

        for (ColumnInfo col : schema.columns()) {
            if (normalizeIdentifierForMatch(col.name()).equals(normalizedMissing)) {
                return String.format(
                        "Column \"%s\" does not exist. Did you mean \"%s\"? Please use the exact column name from schema.",
                        missing, col.name());
            }
        }
        return "";
    }

    /** 列表的输出形态：[a b]。 */
    private static String goSliceString(List<String> items) {
        return "[" + String.join(" ", items) + "]";
    }

    /** 校验错误列表的输出形态：[{type message details} …]（空 details 留尾空格）。 */
    static String goFormatValidationErrors(List<SqlGuard.SqlValidationError> errors) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            SqlGuard.SqlValidationError e = errors.get(i);
            sb.append('{').append(e.type()).append(' ')
                    .append(e.message()).append(' ')
                    .append(e.details()).append('}');
        }
        return sb.append(']').toString();
    }
}
