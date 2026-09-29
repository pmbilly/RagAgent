package com.ragagent.agentm.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.chatpipeline.EntityExtraction;
import com.ragagent.chatpipeline.PipelineConfig.PromptTemplateStructured;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;

/**
 * 文本抽取测试端点用例（text-relation/fabri-tag/fabri-text）。
 *
 * <p>initialization 薄层化的产物：端点注解留在 controller，方法体逐字迁移至此。</p>
 */
public final class TextExtractionTestService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExtractPrompts extractPrompts;
    private final ModelConnectivityTestService modelTest;

    public TextExtractionTestService(ExtractPrompts extractPrompts, ModelConnectivityTestService modelTest) {
        this.extractPrompts = extractPrompts;
        this.modelTest = modelTest;
    }

    // ══════════════ W5b：抽取段（对照 ExtractTextRelations / FabriTag / FabriText L2383-2605）══════════════

    /** POST /initialization/extract/text-relation——LLM 驱动的实体关系抽取。 */
    @PostMapping("/api/v1/initialization/extract/text-relation")
    public ResponseEntity<Object> extractTextRelations(@RequestBody(required = false) String rawBody) {
        JsonNode n;
        try {
            n = OllamaManageService.bindJsonObject(rawBody);
        } catch (BizException e) {
            // Go：bind 失败统一是这句固定文案（不是 err.Error()）
            throw new BizException(AppError.badRequest("文本关系提取请求参数错误"));
        }
        String t = ModelConnectivityTestService.text(n, "text");
        List<String> tags = InitializationRequests.toStringList(n.get("tags"));
        String modelId = ModelConnectivityTestService.text(n, "model_id");
        boolean tagsInvalid = n.get("tags") == null || !n.get("tags").isArray() || tags.isEmpty();
        if (t.isEmpty() || tagsInvalid || modelId.isEmpty()) {
            // Go 的 binding:required 三连；text/tags 缺失时同样先落 bind 错误文案
            throw new BizException(AppError.badRequest("文本关系提取请求参数错误"));
        }
        if (t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length == 0) {
            throw new BizException(AppError.badRequest("文本内容不能为空"));
        }
        if (t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 5000) {
            throw new BizException(AppError.badRequest("文本内容长度不能超过5000字符"));
        }
        if (tags.isEmpty()) {
            throw new BizException(AppError.badRequest("至少需要选择一个关系标签"));
        }
        LlmChatClient chatModel = modelTest.getChatModelOr400(modelId);
        PromptTemplateStructured cfg = extractPrompts.extractGraph();
        PromptTemplateStructured template = new PromptTemplateStructured();
        template.setDescription(cfg.getDescription());
        template.setTags(tags);
        template.setExamples(cfg.getExamples());
        EntityExtraction.Extractor extractor = new EntityExtraction.Extractor(chatModel, template);
        EntityExtraction.EntityGraph graph;
        try {
            graph = extractor.extract(t);
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("文本关系提取失败: " + e.getMessage()));
        }
        removeUnknownRelation(graph, tags);
        ObjectNode data = MAPPER.createObjectNode();
        if (graph.node.isEmpty()) {
            data.putNull("nodes");
        } else {
            ArrayNode nodes = data.putArray("nodes");
            for (var node : graph.node) {
                ObjectNode on = nodes.addObject();
                if (!node.getName().isEmpty()) {
                    on.put("name", node.getName());
                }
                if (node.getChunks() != null && !node.getChunks().isEmpty()) {
                    var chunks = on.putArray("chunks");
                    node.getChunks().forEach(chunks::add);
                }
                if (node.getAttributes() != null && !node.getAttributes().isEmpty()) {
                    var attrs = on.putArray("attributes");
                    node.getAttributes().forEach(attrs::add);
                }
            }
        }
        // Go RemoveUnknownRelation 用 make(...,0) 重建 → relations 恒非 null
        ArrayNode relations = data.putArray("relations");
        for (var rel : graph.relation) {
            ObjectNode on = relations.addObject();
            if (!rel.node1().isEmpty()) {
                on.put("node1", rel.node1());
            }
            if (!rel.node2().isEmpty()) {
                on.put("node2", rel.node2());
            }
            if (!rel.type().isEmpty()) {
                on.put("type", rel.type());
            }
        }
        return ModelConnectivityTestService.ok(data);
    }

    /** POST /initialization/extract/fabri-tag——随机标签组（无 LLM 调用）。 */
    @PostMapping("/api/v1/initialization/extract/fabri-tag")
    public ResponseEntity<Object> fabriTag() {
        List<String> tagRandom = randomSelect(TAG_OPTIONS,
                java.util.concurrent.ThreadLocalRandom.current().nextInt(TAG_OPTIONS.size() - 1) + 1);
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode tags = data.putArray("tags");
        tagRandom.forEach(tags::add);
        return ModelConnectivityTestService.ok(data);
    }

    /** POST /initialization/extract/fabri-text——按标签生成示例文本（LLM 驱动）。 */
    @PostMapping("/api/v1/initialization/extract/fabri-text")
    public ResponseEntity<Object> fabriText(@RequestBody(required = false) String rawBody) {
        JsonNode n;
        try {
            n = OllamaManageService.bindJsonObject(rawBody);
        } catch (BizException e) {
            throw new BizException(AppError.badRequest("invalid fabri text request parameters"));
        }
        List<String> tags = InitializationRequests.toStringList(n.get("tags"));
        String modelId = ModelConnectivityTestService.text(n, "model_id");
        if (modelId.isEmpty()) {
            throw new BizException(AppError.badRequest("invalid fabri text request parameters"));
        }
        LlmChatClient chatModel = modelTest.getChatModelOr400(modelId);
        String content = extractPrompts.fabriText().withNoTag();
        if (!tags.isEmpty()) {
            String tagStr;
            try {
                tagStr = MAPPER.writeValueAsString(tags);
            } catch (Exception e) {
                tagStr = "[]";
            }
            content = String.format(extractPrompts.fabriText().withTag(), tagStr);
        }
        ChatOptions opts = new ChatOptions();
        opts.setTemperature(0.3);
        opts.setMaxTokens(4096);
        opts.setThinking(Boolean.FALSE);
        String result;
        try {
            result = chatModel.chat(List.of(new ChatMessage("user", content)), opts).getContent();
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("failed to generate fabri text: " + e.getMessage()));
        }
        ObjectNode data = MAPPER.createObjectNode();
        data.put("text", result);
        return ModelConnectivityTestService.ok(data);
    }

    /** 对照 RandomSelect：洗牌后取前 n（n 钳到上限）。 */
    private static List<String> randomSelect(List<String> strs, int n) {
        if (n <= 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>(strs);
        java.util.Collections.shuffle(result);
        if (n > strs.size()) {
            n = strs.size();
        }
        return new ArrayList<>(result.subList(0, n));
    }

    /** 对照 tagOptions。 */
    private static final List<String> TAG_OPTIONS = List.of(
            "Content", "Culture", "Person", "Event", "Time", "Location",
            "Work", "Author", "Relation", "Attribute");

    /** 对照 Extractor.RemoveUnknownRelation：过滤不在请求 tags 内的关系。 */
    private static void removeUnknownRelation(EntityExtraction.EntityGraph graph, List<String> tags) {
        java.util.Set<String> known = new java.util.HashSet<>(tags);
        List<com.ragagent.chatpipeline.ChatManage.GraphRelation> kept = new ArrayList<>();
        for (var relation : graph.relation) {
            if (known.contains(relation.type())) {
                kept.add(relation);
            }
        }
        graph.relation = kept;
    }

}
