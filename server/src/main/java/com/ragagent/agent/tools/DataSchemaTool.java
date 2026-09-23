package com.ragagent.agent.tools;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;

/**
 * data_schema 工具（对照 Go {@code data_schema.go}，逐字移植）。
 *
 * <p>读 DuckDB 已载入表格文件的元信息：表摘要 chunk + 列 chunk 拼接返回。依赖两个
 * 知识域切片（对照 Go 注入的 interfaces.KnowledgeService / interfaces.ChunkRepository），
 * Java 侧用两个函数式接口表达（4.5b 的知识工具装配时接真实实现）：</p>
 * <ul>
 *   <li>{@link KnowledgeLookup}：按 ID 取知识（含租户语义）；</li>
 *   <li>{@link ChunkLister}：按知识 ID + chunk 类型列分页 chunk。</li>
 * </ul>
 *
 * <p><b>作用域授权接缝</b>（对照 WithSearchTargets）：Go 的 scopeEnforced 路径走
 * scope_authorization.go 的 authorizeKnowledgeInSearchTargets（随波 4.5b 落地）；
 * 本批以 {@link #withScopeAuthorizer(ScopeAuthorizer)} 注入等价回调——设置了授权器
 * 就不再走无约束的 GetKnowledgeByIDOnly 回路（Agent 回合无检索目标时必须拒绝所有文档）。</p>
 */
public class DataSchemaTool extends BaseTool {

    /** 按文档 ID 取知识（对照 GetKnowledgeByIDOnly；返回 null = 不存在）。 */
    public interface KnowledgeLookup {
        KnowledgeView byId(String knowledgeId);
    }

    /** 作用域授权回调（对照 authorizeKnowledgeInSearchTargets 的位置；4.5b 装配）。 */
    @FunctionalInterface
    public interface ScopeAuthorizer {
        /** 返回知识视图；拒绝时抛异常或返回 null（错误经 errorFormat 输出）。 */
        KnowledgeView authorize(String knowledgeId);
    }

    /** 知识视图的最小切片（对照 types.Knowledge 的被用字段）。 */
    public record KnowledgeView(String knowledgeId, long tenantId) {
    }

    /** chunk 切片（对照 ChunkRepository.ListPagedChunksByKnowledgeID 的被用语义）。 */
    public interface ChunkLister {
        List<ChunkView> listPaged(String knowledgeId, int page, int pageSize,
                                  List<String> chunkTypes, boolean enabled);
    }

    /** chunk 视图（对照 types.Chunk 的被用字段）。 */
    public record ChunkView(String chunkType, String content) {
    }

    /** 键序对照 Go GenerateSchema 输出（字母序：additionalProperties < properties < required < type）。 */
    private static final String SCHEMA_JSON =
            "{\"additionalProperties\":false,\"properties\":{\"knowledge_id\":"
                    + "{\"description\":\"short dN document ID to query\",\"type\":\"string\"}},"
                    + "\"required\":[\"knowledge_id\"],\"type\":\"object\"}";

    private static final String DESCRIPTION =
            "Use this tool to get the schema information of a CSV or Excel file loaded into DuckDB. "
                    + "It returns the table name, columns, and row count.";

    private final KnowledgeLookup knowledgeLookup;
    private final ChunkLister chunkLister;
    private final List<String> targetChunkTypes;
    private ScopeAuthorizer scopeAuthorizer;

    public DataSchemaTool(KnowledgeLookup knowledgeLookup, ChunkLister chunkLister, String... targetChunkTypes) {
        super(ToolDefinitions.TOOL_DATA_SCHEMA, DESCRIPTION, SCHEMA_JSON);
        this.knowledgeLookup = knowledgeLookup;
        this.chunkLister = chunkLister;
        this.targetChunkTypes = targetChunkTypes.length > 0
                ? List.of(targetChunkTypes)
                : List.of("table_summary", "table_column"); // 对照 ChunkTypeTableSummary / ChunkTypeTableColumn
    }

    /** 启用 Agent 请求作用域授权（对照 WithSearchTargets；链式）。 */
    public DataSchemaTool withScopeAuthorizer(ScopeAuthorizer authorizer) {
        this.scopeAuthorizer = authorizer;
        return this;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        String knowledgeId = args.path("knowledge_id").asText("");

        // 取知识以拿租户（对照：IDOnly 以支持跨租户共享 KB；scopeEnforced 走授权器）
        KnowledgeView knowledge;
        try {
            knowledge = scopeAuthorizer != null
                    ? scopeAuthorizer.authorize(knowledgeId)
                    : knowledgeLookup.byId(knowledgeId);
        } catch (RuntimeException e) {
            return failure("Failed to get knowledge '" + knowledgeId + "': " + e.getMessage());
        }
        if (knowledge == null) {
            return failure("Failed to get knowledge '" + knowledgeId + "': knowledge service returned an empty result");
        }

        // 只取表摘要与列 chunk（PageSize 100 对 schema chunk 而言足够）
        List<ChunkView> chunks;
        try {
            chunks = chunkLister.listPaged(knowledgeId, 1, 100, targetChunkTypes, true);
        } catch (RuntimeException e) {
            return failure("Failed to list chunks for knowledge ID '" + knowledgeId + "': " + e.getMessage());
        }

        String summaryContent = null;
        String columnContent = null;
        for (ChunkView chunk : chunks) {
            if ("table_summary".equals(chunk.chunkType())) {
                summaryContent = chunk.content();
            } else if ("table_column".equals(chunk.chunkType())) {
                columnContent = chunk.content();
            }
        }

        boolean noSummary = summaryContent == null || summaryContent.isEmpty();
        boolean noColumn = columnContent == null || columnContent.isEmpty();
        if (noSummary || noColumn) {
            ToolResult r = failure("No table schema information found for knowledge ID '" + knowledgeId + "'");
            return r;
        }

        String output = summaryContent + "\n\n" + columnContent;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", summaryContent);
        data.put("columns", columnContent);

        ToolResult result = new ToolResult();
        result.setSuccess(true);
        result.setOutput(output);
        result.setData(data);
        return result;
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
