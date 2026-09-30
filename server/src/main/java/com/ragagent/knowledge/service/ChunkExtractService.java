package com.ragagent.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agentm.service.ExtractPrompts;
import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.EntityExtraction;
import com.ragagent.chatpipeline.PipelineConfig;
import com.ragagent.chatpipeline.PipelinePorts;
import com.ragagent.chatpipeline.RetrievalObs;
import com.ragagent.common.prompt.PromptInstructions;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeProcessingSpan;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.tracing.langfuse.LangfuseTaskScope;
import com.ragagent.wiki.service.DefaultWikiKnowledgeFinalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.ragagent.knowledge.domain.ExtractChunkPayload;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 分块图抽取任务：
 * 每个文本/OCR 分块一次 LLM 抽取 → 实体/关系写入图库 → 释放父知识的 finalizing 槽。
 * <ol>
 *   <li><b>supersede 跳过</b>：更新的 attempt 已取代本次 → 在开 span/计数前直接返回
 *       （该分块已被新 attempt 的清理删掉，递减会误耗新计数）；</li>
 *   <li>开 {@code postprocess.graph.chunk[i]} 子 span（父 = 本 attempt 的 postprocess 阶段；
 *       载荷缺 knowledge_id/attempt 的旧任务静默跳过）；</li>
 *   <li>取消/删除短路（{@code deleting}/{@code cancelled} → 记 {@code skipped} 返回）；</li>
 *   <li>取分块 → 记内容形态（{@code chunk_chars}/{@code chunk_preview}）→ 取 KB；</li>
 *   <li>抽取配置闸门（{@code extract_config.enabled=false} → {@code skipped=extract_disabled}）；</li>
 *   <li>按配置组装模板（原模板 + CustomInstructions / Tags / Examples）→ 抽取；</li>
 *   <li>重取分块（抽取期间可能消失 → {@code skipped=chunk_disappeared}）→
 *       {@code node.chunks=[chunk_id]} → {@code AddGraph}；</li>
 *   <li>记账 {@code nodes_added}/{@code relations_added} + 前两个样例；</li>
 *   <li>终态（无论成功、跳过还是失败）在 finally 里<b>释放一个 finalizing 槽</b>
 *       。</li>
 * </ol>
 */
@Service
public class ChunkExtractService {

    private static final Logger log = LoggerFactory.getLogger(ChunkExtractService.class);

    /** 任务观测的 span/根名（{@code 任务队列.<type>}）。 */
    public static final String TASK_TYPE_CHUNK_EXTRACT = "chunk:extract";

    static final int CHUNK_PREVIEW_RUNES = 200;

    private final ModelRuntimeFactory modelRuntimeFactory;
    private final ChunkRepository chunkRepository;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final SpanTracker spanTracker;
    private final PipelinePorts.RetrieveGraphRepository graphRepository;
    private final DefaultWikiKnowledgeFinalizer finalizer;
    private final ExtractPrompts extractPrompts;

    /** 生产构造器（Spring 装配；模板来源用 vendored extract 配置）。 */
    @Autowired
    public ChunkExtractService(ModelRuntimeFactory modelRuntimeFactory,
                               ChunkRepository chunkRepository,
                               KnowledgeMapper knowledgeMapper,
                               KnowledgeBaseMapper kbMapper,
                               SpanTracker spanTracker,
                               PipelinePorts.RetrieveGraphRepository graphRepository,
                               DefaultWikiKnowledgeFinalizer finalizer) {
        this(modelRuntimeFactory, chunkRepository, knowledgeMapper, kbMapper, spanTracker,
                graphRepository, finalizer, new ExtractPrompts());
    }

    /** 测试可注入模板来源。 */
    ChunkExtractService(ModelRuntimeFactory modelRuntimeFactory,
                        ChunkRepository chunkRepository,
                        KnowledgeMapper knowledgeMapper,
                        KnowledgeBaseMapper kbMapper,
                        SpanTracker spanTracker,
                        PipelinePorts.RetrieveGraphRepository graphRepository,
                        DefaultWikiKnowledgeFinalizer finalizer,
                        ExtractPrompts extractPrompts) {
        this.modelRuntimeFactory = modelRuntimeFactory;
        this.chunkRepository = chunkRepository;
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.spanTracker = spanTracker;
        this.graphRepository = graphRepository;
        this.finalizer = finalizer;
        this.extractPrompts = extractPrompts;
    }

    /** 队列入口：JSON 载荷 → 任务作用域→ 处理。 */
    public void handleJson(String payloadJson) {
        ExtractChunkPayload p = ExtractChunkPayload.fromJson(payloadJson);
        try (LangfuseTaskScope scope = LangfuseTaskScope.start(TASK_TYPE_CHUNK_EXTRACT, p.tracing(),
                Map.of("chunk_id", p.chunkId(), "knowledge_id", p.knowledgeId()),
                LangfuseTaskScope.previewPayload(payloadJson))) {
            handle(p);
        }
    }

    public void handle(ExtractChunkPayload p) {
        // 1) supersede 跳过
        if (spanTracker.isAttemptSuperseded(p.knowledgeId(), p.attempt())) {
            log.info("graph extract: attempt {} superseded for {}, skipping stale enrichment",
                    p.attempt(), p.knowledgeId());
            return;
        }

        // 2) postprocess.graph.chunk[i] 子 span
        SpanTracker.SpanHandle gSpan = null;
        if (!p.knowledgeId().isEmpty() && p.attempt() > 0) {
            SpanTracker.SpanHandle parent = spanTracker.lookupStage(p.knowledgeId(), p.attempt(),
                    KnowledgeProcessingSpan.STAGE_POST_PROCESS);
            if (parent != null) {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("chunk_id", p.chunkId());
                input.put("chunk_index", p.chunkIndex());
                input.put("model_id", p.modelId());
                gSpan = spanTracker.beginSubSpan(parent,
                        "postprocess.graph.chunk[" + p.chunkIndex() + "]",
                        KnowledgeProcessingSpan.KIND_SUB_SPAN, input);
            }
        }

        Map<String, Object> graphOut = new LinkedHashMap<>();
        String handleErr = null;
        try {
            handleErr = runExtract(p, graphOut);
        } catch (RuntimeException e) {
            handleErr = e.getMessage() == null ? e.toString() : e.getMessage();
            throw e;
        } finally {
            // 3) 终态释放槽位
            drainSubtask(p.knowledgeId(), "graph_chunk[" + p.chunkIndex() + "]");
            // 4) 子 span 收尾
            if (gSpan != null) {
                if (handleErr != null) {
                    spanTracker.failSpan(gSpan, "GRAPH_EXTRACT_FAILED", handleErr, null);
                } else {
                    spanTracker.endSpan(gSpan, graphOut);
                }
            }
        }
    }

    /** @return 失败消息（null = 成功或"按设计跳过"） */
    private String runExtract(ExtractChunkPayload p, Map<String, Object> graphOut) {
        // 取消/删除短路：图抽取是管线里最贵的富化 fan-out，取消即跳
        if (!p.knowledgeId().isEmpty()) {
            Knowledge k = knowledgeMapper.selectById(p.knowledgeId());
            if (k != null && k.isAborted()) {
                log.info("graph extract: knowledge {} aborted ({}), skipping chunk {}",
                        p.knowledgeId(), k.getParseStatus(), p.chunkId());
                graphOut.put("skipped", "knowledge_" + k.getParseStatus());
                return null;
            }
        }

        Chunk chunk = chunkRepository.getChunkById(p.tenantId(), p.chunkId());
        if (chunk == null) {
            log.error("failed to get chunk: {}", p.chunkId());
            return "failed to get chunk: " + p.chunkId();
        }
        // 内容形态：让 trace 回答"这次 LLM 调用看到了什么"
        graphOut.put("chunk_chars", chunk.getContent().codePointCount(0, chunk.getContent().length()));
        graphOut.put("chunk_preview", RetrievalObs.truncateRunes(chunk.getContent(), CHUNK_PREVIEW_RUNES));

        KnowledgeBase kb = kbMapper.selectById(chunk.getKnowledgeBaseId());
        if (kb == null) {
            log.error("failed to get knowledge base: {}", chunk.getKnowledgeBaseId());
            return "failed to get knowledge base: " + chunk.getKnowledgeBaseId();
        }

        JsonNode extractCfg = kb.getExtractConfig();
        if (extractCfg == null || !extractCfg.path("enabled").asBoolean(false)) {
            log.warn("extract config not enabled");
            graphOut.put("skipped", "extract_disabled");
            return null;
        }

        LlmChatClient chatModel;
        try {
            chatModel = modelRuntimeFactory.getChatModel(p.modelId());
        } catch (RuntimeException e) {
            log.error("failed to get chat model: {}", e.toString());
            return "failed to get chat model: " + e.getMessage();
        }

        EntityExtraction.EntityGraph graph;
        try {
            graph = new EntityExtraction.Extractor(chatModel, buildTemplate(extractCfg))
                    .extract(chunk.getContent());
        } catch (RuntimeException e) {
            log.error("failed to extract entities: {}", e.toString());
            return "failed to extract entities: " + e.getMessage();
        }

        Chunk again = chunkRepository.getChunkById(p.tenantId(), p.chunkId());
        if (again == null) {
            // 抽取期间分块消失（新 attempt 清理）→ 不当失败，记 skipped 即返回
            log.warn("graph ignore chunk {}: chunk disappeared", p.chunkId());
            graphOut.put("skipped", "chunk_disappeared");
            return null;
        }

        List<ChatManage.GraphNode> nodes = graph.node == null ? new ArrayList<>() : graph.node;
        List<ChatManage.GraphRelation> relations = graph.relation == null
                ? new ArrayList<>() : graph.relation;
        for (ChatManage.GraphNode node : nodes) {
            node.setChunks(List.of(again.getId()));
        }
        try {
            graphRepository.addGraph(
                    new ChatManage.NameSpace(again.getKnowledgeBaseId(), again.getKnowledgeId()),
                    List.of(new ChatManage.GraphData(nodes, relations)));
        } catch (RuntimeException e) {
            log.error("failed to add graph: {}", e.toString());
            return "failed to add graph: " + e.getMessage();
        }

        graphOut.put("nodes_added", nodes.size());
        graphOut.put("relations_added", relations.size());
        // 各取前两个样例（再多会撑爆 span 行；全图另可查询）
        if (!nodes.isEmpty()) {
            List<ChatManage.GraphNode> samples = nodes.size() > 2 ? nodes.subList(0, 2) : nodes;
            List<String> names = new ArrayList<>();
            for (ChatManage.GraphNode n : samples) {
                names.add(n.getName());
            }
            graphOut.put("sample_nodes", names);
        }
        if (!relations.isEmpty()) {
            List<ChatManage.GraphRelation> samples = relations.size() > 2
                    ? relations.subList(0, 2) : relations;
            List<String> rendered = new ArrayList<>();
            for (ChatManage.GraphRelation r : samples) {
                rendered.add(r.node1() + " --[" + r.type() + "]--> " + r.node2());
            }
            graphOut.put("sample_relations", rendered);
        }
        return null;
    }

    /**
     * 组装抽取模板：原 {@code extract_graph} 模板的 Description +
     * 载荷的 {@code customInstructions}（标签 {@code graph_extraction}）+
     * Tags + 单条 Example（{@code text}/{@code nodes}/{@code relations} 来自 KB 配置）。
     */
    PipelineConfig.PromptTemplateStructured buildTemplate(JsonNode cfg) {
        PipelineConfig.PromptTemplateStructured template = new PipelineConfig.PromptTemplateStructured();
        template.setDescription(PromptInstructions.appendCustomPromptInstructions(
                extractPrompts.extractGraph().getDescription(),
                text(cfg, "customInstructions"), "graph_extraction"));
        template.setTags(stringList(cfg, "tags"));

        PipelineConfig.PromptTemplateStructured.Example example =
                new PipelineConfig.PromptTemplateStructured.Example();
        example.setText(text(cfg, "text"));
        List<ChatManage.GraphNode> nodes = new ArrayList<>();
        for (JsonNode node : cfg.path("nodes")) {
            nodes.add(new ChatManage.GraphNode(text(node, "name"), null, null));
        }
        example.setNode(nodes);
        List<ChatManage.GraphRelation> relations = new ArrayList<>();
        for (JsonNode rel : cfg.path("relations")) {
            relations.add(new ChatManage.GraphRelation(
                    text(rel, "node1"), text(rel, "node2"), text(rel, "type")));
        }
        example.setRelation(relations);
        template.setExamples(List.of(example));
        return template;
    }

    /** knowledge 为空则空转（旧版在飞任务）。 */
    private void drainSubtask(String knowledgeId, String source) {
        if (knowledgeId == null || knowledgeId.isEmpty()) {
            return;
        }
        try {
            finalizer.finalizeSubtask(knowledgeId);
        } catch (RuntimeException e) {
            // best-effort：递减失败不破坏任务语义（行由 housekeeping sweep 兜底）
            log.warn("finalize subtask decrement failed source={} knowledge={} err={}",
                    source, knowledgeId, e.toString());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static List<String> stringList(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        JsonNode arr = node == null ? null : node.get(field);
        if (arr != null && arr.isArray()) {
            for (JsonNode item : arr) {
                out.add(item.asText(""));
            }
        }
        return out;
    }
}
