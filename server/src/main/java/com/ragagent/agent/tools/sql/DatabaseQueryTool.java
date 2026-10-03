package com.ragagent.agent.tools.sql;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.ToolJson;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.agent.tools.BaseTool;
import com.ragagent.agent.tools.knowledge.KnowledgeSearchTool;
import com.ragagent.agent.tools.SearchAuth;
import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.agent.tools.ToolDefinitions;
import com.ragagent.agent.tools.ToolRequest;

/**
 * database_query 工具。
 *
 * <p>SQL 校验与安全注入在 {@link SqlGuard}（手写轻量解析器，解析错误文案与
 * 原生 parser 有差异——工具 error 只透 Message，输出文案不受影响）。</p>
 *
 * <p>DB 执行经 {@link SqlQueryExecutor} seam（回放测试用 JDBC 实现连同一 dev PG
 * 端到端验证）。tenant_id 由构造期注入 {@link LongSupplier}（缺省 0）。</p>
 *
 * <p>已知差异：时间格式与 Java 序列化不同（语料避开 timestamp 列）；查询执行失败
 * 的 driver 错误文案不录语料。</p>
 */
public class DatabaseQueryTool extends BaseTool {

    /** schema 字节即契约（探针 _schema 语料钉死）；键按字母序：additionalProperties < properties < required < type。 */
    private static final String SCHEMA_JSON = """
            {"additionalProperties":false,"properties":{"sql":{"description":"The SELECT SQL query to execute. DO NOT include tenant_id condition - it will be automatically added for security.","type":"string"}},"required":["sql"],"type":"object"}""";

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

    /** 查询执行 seam。 */
    public interface SqlQueryExecutor {
        /**
         * 执行已注入安全条件的 SELECT，返回列名（有序）与行（每行按列序的值）。
         * 值类型约定：文本→String、整型→Long、浮点→Double、数值→BigDecimal
         * （由工具侧转字符串形态）、布尔→Boolean。
         */
        QueryResult query(String securedSQL);
    }

    /** 列名（有序）+ 行切片。 */
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

        // 取 "sql" 参数；空判断无 trim。
        JsonNode sqlNode = args == null ? null : args.get("sql");
        String sql = sqlNode == null || sqlNode.isNull() ? "" : sqlNode.asText();
        if (sql.isEmpty()) {
            return failure("Missing or invalid 'sql' parameter");
        }

        String securedSQL;
        try {
            securedSQL = validateAndSecureSQL(sql, tenantID);
        } catch (SqlGuard.SqlGuardException e) {
            // 失败文案即校验器的首条 Message。
            return failure("SQL validation failed: " + e.getMessage());
        } catch (RuntimeException e) {
            return failure("SQL validation failed: " + e.getMessage());
        }

        QueryResult queryResult;
        try {
            queryResult = queryExecutor.query(securedSQL);
        } catch (RuntimeException e) {
            // driver 错误文案因驱动而异——已知差异，语料不录执行失败。
            return failure("Query execution failed: " + e.getMessage());
        }
        List<String> columns = queryResult.columns() == null ? List.of() : queryResult.columns();
        List<List<Object>> rawRows = queryResult.rows() == null ? List.of() : queryResult.rows();

        // 行扫描：byte[]→string、numeric→字符串文本，其他原样。
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

    /** scope 空 → "no effective Agent knowledge scope is available"。 */
    String validateAndSecureSQL(String sqlQuery, long tenantID) throws SqlGuard.SqlGuardException {
        List<SqlGuard.SearchScope> searchScopes = searchScopesFromTargets(searchTargets);
        if (searchScopes.isEmpty()) {
            throw new SqlGuard.SqlGuardException(scopeUnavailableResult());
        }
        return SqlGuard.validateAndSecure(sqlQuery, tenantID, searchScopes);
    }

    private static SqlGuard.SqlValidationResult scopeUnavailableResult() {
        SqlGuard.SqlValidationResult r = new SqlGuard.SqlValidationResult();
        // 借用 result 承载（message 直接作异常文案；不进 Errors 序列化）。
        r.errors.add(new SqlGuard.SqlValidationError(
                "", "no effective Agent knowledge scope is available", ""));
        r.valid = false;
        return r;
    }

    /** 从检索目标取 scope（授权复用 SearchAuth）。 */
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

    /** 结果渲染；非 string/byte[] 值走 JSON 编码形态（经 ToolJson）。 */
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
                    // numeric 值即 PG 数值文本。
                    formattedValue = bd.toPlainString();
                } else {
                    // JSON 编码形态（经 ToolJson）。
                    formattedValue = ToolJson.write(
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
