package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.DataAnalysisTool;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.retrieval.domain.SearchResult;

/**
 * DATA_ANALYSIS 阶段插件（对照 Go chat_pipeline/data_analysis.go）：
 * MergeResult 里出现 CSV/Excel 命中 → 过滤表格列/摘要块 → 经 DataAnalysisTool
 * 装载数据 → LLM 生成 (knowledge_id, sql) → 执行并把分析结果并回 MergeResult。
 *
 * <p>工具构造走 {@link PipelinePorts.DataAnalysisToolFactory} seam（Go 侧五依赖
 * 直接传给 tools.NewDataAnalysisTool；Java 侧 4.5b 的 DataAnalysisTool 以
 * KnowledgeLoader/Materializer/AnalysisDuckDb 三个 seam 构造，装配期打包成工厂）。
 * format schema（utils.GenerateSchema[DataAnalysisInput]）逐字节取 4.5b 的
 * SCHEMA_JSON 常量（该常量本身即 Go 实录）。</p>
 */
public final class PluginDataAnalysis implements Plugin {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 对照 utils.GenerateSchema[tools.DataAnalysisInput]()（4.5b 的字节实录常量）。 */
    public static final String FORMAT_SCHEMA_JSON = """
            {"type":"object","properties":{"knowledge_id":{"type":"string","description":"short dN document ID to query"},"sql":{"type":"string","description":"SQL to be executed on knowledge"}},"required":["knowledge_id","sql"],"additionalProperties":false}""";

    private final PipelinePorts.ModelService modelService;
    private final PipelinePorts.KnowledgeService knowledgeService;
    private final PipelinePorts.DataAnalysisSessionFactory toolFactory;

    public PluginDataAnalysis(PipelinePorts.ModelService modelService,
                              PipelinePorts.KnowledgeService knowledgeService,
                              PipelinePorts.DataAnalysisSessionFactory toolFactory) {
        this.modelService = modelService;
        this.knowledgeService = knowledgeService;
        this.toolFactory = toolFactory;
    }

    @Override
    public String[] activationEvents() {
        return new String[] {PipelineEventType.DATA_ANALYSIS};
    }

    @Override
    public PluginError onEvent(String eventType, ChatManage chatManage, Plugin.Chain next) {
        if (!chatManage.needsRetrieval()) {
            return next.next();
        }
        // 1. 找 CSV/Excel 命中
        List<SearchResult> dataFiles = new ArrayList<>();
        List<SearchResult> mergeResult = chatManage.getMergeResult();
        for (SearchResult result : mergeResult) {
            if (isDataFile(result.getKnowledgeFilename())) {
                dataFiles.add(result);
            }
        }

        // 过滤表格列/摘要块
        chatManage.setMergeResult(filterOutTableChunks(mergeResult));

        if (dataFiles.isEmpty()) {
            return next.next();
        }

        // 2. 只处理首个数据文件
        SearchResult targetFile = dataFiles.get(0);

        Knowledge knowledge;
        try {
            knowledge = knowledgeService.getKnowledgeById(targetFile.getKnowledgeId());
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("knowledge_id", targetFile.getKnowledgeId());
            f.put("error", e.getMessage());
            PipelineLog.error("DataAnalysis", "get_knowledge", f);
            return next.next();
        }

        // 3. 工具会话：装载 → LLM 生成 SQL → 执行
        PipelinePorts.DataAnalysisSession tool = toolFactory.create(chatManage.getSessionId());
        try {
            DataAnalysisTool.TableSchema schema;
            try {
                schema = tool.loadFromKnowledge(new DataAnalysisTool.KnowledgeData(
                        knowledge.getId(), knowledge.getKnowledgeBaseId(), knowledge.getTenantId(),
                        knowledge.getFileType(), knowledge.getFilePath()));
            } catch (RuntimeException e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("error", e.getMessage());
                PipelineLog.error("DataAnalysis", "load_data", f);
                return next.next();
            }

            com.ragagent.llm.LlmChatClient chatModel;
            try {
                chatModel = modelService.getChatModel(chatManage.getChatModelId());
            } catch (RuntimeException e) {
                return PluginError.GET_CHAT_MODEL.withError(e);
            }

            String analysisPrompt = "\nUser Question: " + chatManage.getQuery()
                    + "\nKnowledge ID: " + knowledge.getId()
                    + "\nTable Schema: " + tableSchemaDescription(schema)
                    + "\n\nDetermine if the user's question requires data analysis (e.g., statistics, aggregation, filtering) on this table."
                    + "\nIf YES, generate a DuckDB SQL query to answer the user's question and fill in the knowledge_id and sql fields."
                    + "\nIf NO, leave the sql field empty."
                    + "\n\nReturn your response in the specified JSON format.";

            ChatOptions opt = new ChatOptions();
            opt.setTemperature(0.1);
            try {
                opt.setFormat(JSON.readTree(FORMAT_SCHEMA_JSON));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }

            com.ragagent.llm.domain.ChatResponse response;
            try {
                response = chatModel.chat(List.of(new ChatMessage("user", analysisPrompt)), opt);
            } catch (RuntimeException e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("error", e.getMessage());
                PipelineLog.error("DataAnalysis", "generate_analysis", f);
                return next.next();
            }

            ToolResult toolResult;
            try {
                JsonNode args = JSON.readTree(response.getContent());
                toolResult = tool.execute(args);
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("error", e.getMessage());
                PipelineLog.error("DataAnalysis", "execute_sql", f);
                return next.next();
            }

            // 5. 结果并回 MergeResult
            SearchResult analysisResult = new SearchResult();
            analysisResult.setId("analysis_" + knowledge.getId());
            analysisResult.setContent(toolResult.getOutput());
            analysisResult.setScore(1.0);
            analysisResult.setMatchType(MatchTypes.DATA_ANALYSIS);
            analysisResult.setKnowledgeId(knowledge.getId());
            analysisResult.setKnowledgeTitle(knowledge.getTitle());
            analysisResult.setKnowledgeFilename(knowledge.getFileName());
            analysisResult.setKnowledgeDescription(knowledge.getDescription());
            chatManage.getMergeResult().add(analysisResult);
        } finally {
            tool.cleanup();
        }

        return next.next();
    }

    /** 对照 isDataFile。 */
    static boolean isDataFile(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".csv") || lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    /** 对照 filterOutTableChunks。 */
    static List<SearchResult> filterOutTableChunks(List<SearchResult> results) {
        List<SearchResult> filtered = new ArrayList<>(results.size());
        for (SearchResult result : results) {
            if (ChunkTypes.TABLE_COLUMN.equals(result.getChunkType())
                    || ChunkTypes.TABLE_SUMMARY.equals(result.getChunkType())) {
                continue;
            }
            filtered.add(result);
        }
        return filtered;
    }

    /** 对照 tools.(*TableSchema).Description（data_analysis.go:772-790）。 */
    static String tableSchemaDescription(DataAnalysisTool.TableSchema schema) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.format("Table name: %s\n", schema.tableName()));
        builder.append(String.format("Columns: %d\n", schema.columns().size()));
        builder.append(String.format("Rows: %d\n\n", schema.rowCount()));
        builder.append("Column info:\n");
        for (DataAnalysisTool.ColumnInfo col : schema.columns()) {
            builder.append(String.format("- %s (%s)\n", col.name(), col.type()));
        }
        return builder.toString();
    }
}
