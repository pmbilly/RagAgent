package com.ragagent.agent.tools;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;

/**
 * database_query 工具（对照 Go {@code database_query.go}，逐字移植）。
 *
 * <p>SQL 校验与安全注入在 {@link SqlGuard}（对照 {@code internal/utils/inject.go}；
 * 手写轻量解析器替代 pg_query，解析错误文案差异列报告——工具 error 只透
 * Message，故输出逐字一致）。</p>
 *
 * <p>DB 执行经 {@link SqlQueryExecutor} seam（对照 {@code t.db.Raw(securedSQL).Rows()}：
 * 4.5c 装配期接真实数据源；回放测试用 JDBC 实现连同一 dev PG 端到端验证）。
 * tenant_id 对照 Go 从 ctx 读 {@code types.TenantIDContextKey}（缺省 0）——Java 无
 * ctx 挂键，改为构造期注入 {@link LongSupplier}（接线列报告）。</p>
 *
 * <p>已知差异：Go {@code json.Marshal}/时间格式与 Java 序列化不同（语料避开
 * timestamp 列）；查询执行失败的 driver 错误文案不录语料（pgjdbc vs lib/pq）。</p>
 */
public class DatabaseQueryTool extends BaseTool {

    /** schema 字节即契约：Go 实录 {@code utils.GenerateSchema[DatabaseQueryInput]()}（探针 _schema 语料）。 */
    private static final String SCHEMA_JSON = """
            {"type":"object","properties":{"sql":{"type":"string","description":"The SELECT SQL query to execute. DO NOT include tenant_id condition - it will be automatically added for security."}},"required":["sql"],"additionalProperties":false}""";

    private static final String DESCRIPTION = "Execute SQL queries to retrieve information from the database.\n"
            + "\n"
            + "## Security Features\n"
            + "- Automatic tenant_id injection: All queries are automatically filtered by the logged-in user's tenant_id\n"
            + "- Automatic soft-delete filtering: All queries are automatically filtered to include only records with deleted_at IS NULL\n"
            + "- Read-only queries: Only SELECT statements are allowed\n"
            + "- Safe tables: Only allow queries on authorized tables (knowledge_bases, knowledges, chunks)\n"
            + "\n"
            + "## Available Tables and Columns\n"
            + "\n"
            + "### knowledge_bases\n"
            + "- id (VARCHAR): Knowledge base ID\n"
            + "- name (VARCHAR): Knowledge base name\n"
            + "- description (TEXT): Description\n"
            + "- tenant_id (INTEGER): Owner tenant ID\n"
            + "- embedding_model_id, summary_model_id, rerank_model_id (VARCHAR): Model IDs\n"
            + "- vlm_config (JSON): Includes VLM settings such as enabled flag and model_id\n"
            + "- created_at, updated_at, deleted_at (TIMESTAMP)\n"
            + "\n"
            + "### knowledges (documents)\n"
            + "- id (VARCHAR): Document ID\n"
            + "- tenant_id (INTEGER): Owner tenant ID\n"
            + "- knowledge_base_id (VARCHAR): Parent knowledge base ID\n"
            + "- type (VARCHAR): Document type\n"
            + "- title (VARCHAR): Document title\n"
            + "- description (TEXT): Description\n"
            + "- source (VARCHAR): Source location\n"
            + "- parse_status (VARCHAR): Processing status (unprocessed/processing/completed/failed)\n"
            + "- enable_status (VARCHAR): Enable status (enabled/disabled)\n"
            + "- file_name, file_type (VARCHAR): File information\n"
            + "- file_size, storage_size (BIGINT): Size in bytes\n"
            + "- created_at, updated_at, processed_at, deleted_at (TIMESTAMP)\n"
            + "\n"
            + "\n"
            + "\n"
            + "### chunks\n"
            + "- id (VARCHAR): Chunk ID\n"
            + "- tenant_id (INTEGER): Owner tenant ID\n"
            + "- knowledge_base_id (VARCHAR): Parent knowledge base ID\n"
            + "- knowledge_id (VARCHAR): Parent document ID\n"
            + "- content (TEXT): Chunk content\n"
            + "- chunk_index (INTEGER): Index in document\n"
            + "- is_enabled (BOOLEAN): Enable status\n"
            + "- chunk_type (VARCHAR): Type (text/image/table)\n"
            + "- created_at, updated_at, deleted_at (TIMESTAMP)\n"
            + "\n"
            + "## Usage Examples\n"
            + "\n"
            + "Query knowledge base information:\n"
            + "{\n"
            + "  \"sql\": \"SELECT id, name, description FROM knowledge_bases ORDER BY created_at DESC LIMIT 10\"\n"
            + "}\n"
            + "\n"
            + "Count documents by status:\n"
            + "{\n"
            + "  \"sql\": \"SELECT parse_status, COUNT(*) as count FROM knowledges GROUP BY parse_status\"\n"
            + "}\n"
            + "\n"
            + "Get storage usage:\n"
            + "{\n"
            + "  \"sql\": \"SELECT SUM(storage_size) as total_storage FROM knowledges\"\n"
            + "}\n"
            + "\n"
            + "Join knowledge bases and documents:\n"
            + "{\n"
            + "  \"sql\": \"SELECT kb.name as kb_name, COUNT(k.id) as doc_count FROM knowledge_bases kb LEFT JOIN knowledges k ON kb.id = k.knowledge_base_id GROUP BY kb.id, kb.name\"\n"
            + "}\n"
            + "\n"
            + "## Important Notes\n"
            + "- DO NOT include tenant_id in WHERE clause - it's automatically added\n"
            + "- DO NOT include deleted_at filtering manually unless needed - default query already enforces deleted_at IS NULL\n"
            + "- Only SELECT queries are allowed\n"
            + "- Limit results with LIMIT clause for better performance\n"
            + "- Use appropriate JOINs when querying across tables\n"
            + "- All timestamps are in UTC with time zone";

    /** 查询执行 seam（对照 {@code db.Raw(securedSQL).Rows()} 的行扫描）。 */
    public interface SqlQueryExecutor {
        /**
         * 执行已注入安全条件的 SELECT，返回列名（有序）与行（每行按列序的值）。
         * 值类型约定对齐 Go 的 {@code rows.Scan(interface{})} + {@code []byte→string}：
         * 文本→String、整型→Long、浮点→Double、数值→BigDecimal（由工具侧转 Go 字符串形态）、
         * 布尔→Boolean。
         */
        QueryResult query(String securedSQL);
    }

    /** 对照 {@code rows.Columns()} + 行切片。 */
    public record QueryResult(List<String> columns, List<List<Object>> rows) {
    }

    private final SqlQueryExecutor queryExecutor;
    private final SearchTarget.SearchTargets searchTargets;
    private final LongSupplier tenantIdProvider;

    public DatabaseQueryTool(SqlQueryExecutor queryExecutor, SearchTarget.SearchTargets searchTargets,
            LongSupplier tenantIdProvider) {
        super(ToolDefinitions.TOOL_DATABASE_QUERY, DESCRIPTION, SCHEMA_JSON);
        this.queryExecutor = queryExecutor;
        this.searchTargets = searchTargets;
        this.tenantIdProvider = tenantIdProvider;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();

        long tenantID = tenantIdProvider != null ? tenantIdProvider.getAsLong() : 0;

        // 对照 Go json.Unmarshal(args, &input) + input.SQL == ""（无 trim）。
        JsonNode sqlNode = args == null ? null : args.get("sql");
        String sql = sqlNode == null || sqlNode.isNull() ? "" : sqlNode.asText();
        if (sql.isEmpty()) {
            return failure("Missing or invalid 'sql' parameter");
        }

        // 对照 validateAndSecureSQL。
        String securedSQL;
        try {
            securedSQL = validateAndSecureSQL(sql, tenantID);
        } catch (SqlGuard.SqlGuardException e) {
            // 对照 Execute：fmt.Sprintf("SQL validation failed: %v", err)
            // —— err 即 ValidateAndSecureSQL 的 Errors[0].Message。
            return failure("SQL validation failed: " + e.getMessage());
        } catch (RuntimeException e) {
            return failure("SQL validation failed: " + e.getMessage());
        }

        // 对照 db.Raw(securedSQL).Rows()。
        QueryResult queryResult;
        try {
            queryResult = queryExecutor.query(securedSQL);
        } catch (RuntimeException e) {
            // 对照 fmt.Sprintf("Query execution failed: %v", err)
            // （driver 错误文案 pgjdbc vs lib/pq 不同——已知差异，语料不录执行失败）。
            return failure("Query execution failed: " + e.getMessage());
        }
        List<String> columns = queryResult.columns() == null ? List.of() : queryResult.columns();
        List<List<Object>> rawRows = queryResult.rows() == null ? List.of() : queryResult.rows();

        // 对照行扫描：[]byte→string、numeric（Go 侧同样经 []byte）→string，其他原样。
        List<Map<String, Object>> results = new ArrayList<>();
        for (List<Object> rowValues : rawRows) {
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                Object val = i < rowValues.size() ? rowValues.get(i) : null;
                if (val instanceof byte[] b) {
                    rowMap.put(columns.get(i), new String(b, java.nio.charset.StandardCharsets.UTF_8));
                } else if (val instanceof BigDecimal bd) {
                    rowMap.put(columns.get(i), bd.toPlainString());
                } else {
                    rowMap.put(columns.get(i), val);
                }
            }
            results.add(rowMap);
        }

        String output = formatQueryResults(columns, results);

        ToolResult result = new ToolResult();
        result.setSuccess(true);
        result.setOutput(output);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("columns", columns);
        data.put("rows", results);
        data.put("row_count", results.size());
        data.put("display_type", "database_query");
        result.setData(data);
        return result;
    }

    private ToolResult failure(String error) {
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(error);
        return r;
    }

    /** 对照 validateAndSecureSQL（scope 空 → "no effective Agent knowledge scope is available"）。 */
    String validateAndSecureSQL(String sqlQuery, long tenantID) throws SqlGuard.SqlGuardException {
        List<SqlGuard.SearchScope> searchScopes = searchScopesFromTargets(searchTargets);
        if (searchScopes.isEmpty()) {
            throw new SqlGuard.SqlGuardException(scopeUnavailableResult());
        }
        return SqlGuard.validateAndSecure(sqlQuery, tenantID, searchScopes);
    }

    private static SqlGuard.SqlValidationResult scopeUnavailableResult() {
        SqlGuard.SqlValidationResult r = new SqlGuard.SqlValidationResult();
        // 借用 result 承载（message 即 Go 的 fmt.Errorf 文案；不进 Errors 序列化）。
        r.errors.add(new SqlGuard.SqlValidationError(
                "", "no effective Agent knowledge scope is available", ""));
        r.valid = false;
        return r;
    }

    /** 对照 searchScopesFromTargets（scope 授权复用 SearchAuth）。 */
    static List<SqlGuard.SearchScope> searchScopesFromTargets(SearchTarget.SearchTargets searchTargets) {
        List<SqlGuard.SearchScope> scopes = new ArrayList<>();
        if (searchTargets == null) {
            return scopes;
        }
        for (SearchTarget target : searchTargets.list()) {
            if (target == null || target.knowledgeBaseId() == null || target.knowledgeBaseId().isEmpty()) {
                continue;
            }
            SearchAuth.Scope scope = SearchAuth.searchTargetScope(target);
            List<String> knowledgeIDs = scope.knowledgeIds();
            List<String> tagIDs = scope.tagIds();
            if (!SearchAuth.searchTargetIsWholeKb(target)
                    && (knowledgeIDs == null || knowledgeIDs.isEmpty())
                    && (tagIDs == null || tagIDs.isEmpty())) {
                continue;
            }
            scopes.add(new SqlGuard.SearchScope(target.knowledgeBaseId(), knowledgeIDs, tagIDs));
        }
        return scopes;
    }

    /** 对照 formatQueryResults（逐字；非 string/[]byte 值走 Go json.Marshal 形态）。 */
    String formatQueryResults(List<String> columns, List<Map<String, Object>> results) {
        StringBuilder output = new StringBuilder("=== Query Results ===\n\n");
        output.append(String.format("Returned %d rows\n\n", results.size()));

        if (results.isEmpty()) {
            output.append("No matching records found.\n");
            return output.toString();
        }

        output.append("=== Data Details ===\n\n");

        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> row = results.get(i);
            output.append(String.format("--- Record #%d ---\n", i + 1));
            for (String col : columns) {
                Object value = row.get(col);
                String formattedValue;
                if (value == null) {
                    formattedValue = "<NULL>";
                } else if (value instanceof String s) {
                    formattedValue = s;
                } else if (value instanceof byte[] b) {
                    formattedValue = new String(b, java.nio.charset.StandardCharsets.UTF_8);
                } else if (value instanceof BigDecimal bd) {
                    // Go 侧 numeric 经 []byte→string：值即 PG 数值文本。
                    formattedValue = bd.toPlainString();
                } else {
                    // 对照 json.Marshal 的 Go 格式（经 GoJsonCodec）。
                    formattedValue = GoJsonCodec.write(
                            KnowledgeSearchTool.RecordingSupportHolder.MAPPER.valueToTree(value));
                }
                output.append(String.format("  %s: %s\n", col, formattedValue));
            }
            output.append('\n');
        }

        if (results.size() > 10) {
            output.append(String.format(
                    "Note: Showing %d records out of %d total. Consider using a LIMIT clause to restrict the result count.\n",
                    results.size(), results.size()));
        }

        return output.toString();
    }
}
