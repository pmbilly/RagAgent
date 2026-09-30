package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.common.graph.GraphNode;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.llm.extract.EntityExtraction;
import com.ragagent.llm.extract.PipelineConfig;

/**
 * QUERY_UNDERSTAND 附加插件（对照 Go chat_pipeline/extract_entity.go 的 PluginExtractEntity）：
 * 图谱抽取（NEO4J_ENABLE=true 才生效）——按 ExtractConfig 命中的知识库跑实体抽取，
 * 抽出的实体名挂到 chatManage.Entity（ENTITY_SEARCH 阶段消费）。
 *
 * <p>Go 侧的 map 迭代（kbIDSet/entityKnowledge）是无序的；Java 侧 LinkedHashMap
 * 保出现序（EntityKBIDs/EntityKnowledge 的顺序确定性备案，消费端 search_entity
 * 不依赖顺序）。模板经 config.ExtractManager.ExtractEntity 传入，例子仅取
 * Description/Examples（Go 的 NewPluginExtractEntity 同样裁剪）。</p>
 */
public final class PluginExtractEntity implements Plugin {

    private final PipelinePorts.ModelService modelService;
    private final PipelineConfig.PromptTemplateStructured template;
    private final PipelinePorts.KnowledgeBaseRepository knowledgeBaseRepo;
    private final PipelinePorts.KnowledgeService knowledgeService;
    private final PipelinePorts.KnowledgeRepository knowledgeRepo;
    private final boolean neo4jEnabled;

    public PluginExtractEntity(PipelinePorts.ModelService modelService,
                               PipelineConfig.PromptTemplateStructured template,
                               PipelinePorts.KnowledgeBaseRepository knowledgeBaseRepo,
                               PipelinePorts.KnowledgeService knowledgeService,
                               PipelinePorts.KnowledgeRepository knowledgeRepo,
                               boolean neo4jEnabled) {
        this.modelService = modelService;
        this.template = template;
        this.knowledgeBaseRepo = knowledgeBaseRepo;
        this.knowledgeService = knowledgeService;
        this.knowledgeRepo = knowledgeRepo;
        // Go: strings.ToLower(os.Getenv("NEO4J_ENABLE")) != "true" → skip；
        // 环境开关在装配期解析传入（4.6d）
        this.neo4jEnabled = neo4jEnabled;
    }

    @Override
    public String[] activationEvents() {
        return new String[] {PipelineEventType.QUERY_UNDERSTAND};
    }

    @Override
    public PluginError onEvent(String eventType, ChatManage chatManage, Plugin.Chain next) {
        if (!neo4jEnabled) {
            return next.next();
        }

        String query = chatManage.getQuery();

        com.ragagent.llm.LlmChatClient model;
        try {
            model = modelService.getChatModel(chatManage.getChatModelId());
        } catch (RuntimeException e) {
            return next.next();
        }

        // 收集全部 KB ID（含共享 KB 文档）
        Map<String, Boolean> kbIDSet = new LinkedHashMap<>();
        for (String id : chatManage.getKnowledgeBaseIds()) {
            kbIDSet.put(id, Boolean.TRUE);
        }

        Map<String, String> knowledgeToKBMap = new LinkedHashMap<>();
        if (chatManage.getKnowledgeIds() != null && !chatManage.getKnowledgeIds().isEmpty()) {
            List<Knowledge> knowledges;
            try {
                knowledges = knowledgeService.getKnowledgeBatchWithSharedAccess(
                        chatManage.getTenantId(), chatManage.getKnowledgeIds());
            } catch (RuntimeException e) {
                return next.next();
            }
            for (Knowledge k : knowledges) {
                kbIDSet.put(k.getKnowledgeBaseId(), Boolean.TRUE);
                knowledgeToKBMap.put(k.getId(), k.getKnowledgeBaseId());
            }
        }

        List<KnowledgeBase> kbs;
        try {
            kbs = knowledgeBaseRepo.getKnowledgeBaseByIDs(new ArrayList<>(kbIDSet.keySet()));
        } catch (RuntimeException e) {
            return next.next();
        }

        Map<String, Boolean> enabledKBSet = new LinkedHashMap<>();
        for (KnowledgeBase kb : kbs) {
            if (extractEnabled(kb)) {
                enabledKBSet.put(kb.getId(), Boolean.TRUE);
            }
        }
        if (enabledKBSet.isEmpty()) {
            return next.next();
        }

        chatManage.setEntityKbIds(new ArrayList<>(enabledKBSet.keySet()));

        Map<String, String> entityKnowledge = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : knowledgeToKBMap.entrySet()) {
            if (enabledKBSet.containsKey(e.getValue())) {
                entityKnowledge.put(e.getKey(), e.getValue());
            }
        }
        chatManage.setEntityKnowledge(entityKnowledge);

        PipelineConfig.PromptTemplateStructured tpl = new PipelineConfig.PromptTemplateStructured();
        tpl.setDescription(template == null ? "" : template.getDescription());
        tpl.setExamples(template == null ? null : template.getExamples());
        EntityExtraction.Extractor extractor = new EntityExtraction.Extractor(model, tpl);
        EntityExtraction.EntityGraph graph;
        try {
            graph = extractor.extract(query);
        } catch (RuntimeException e) {
            return next.next();
        }
        List<String> nodes = new ArrayList<>();
        for (GraphNode node : graph.node) {
            nodes.add(node.getName());
        }
        chatManage.setEntity(nodes);
        return next.next();
    }

    /** 对照 kb.ExtractConfig != nil && kb.ExtractConfig.Enabled（Java 侧 jsonb 判定）。 */
    private static boolean extractEnabled(KnowledgeBase kb) {
        var cfg = kb.getExtractConfig();
        return cfg != null && !cfg.isNull()
                && cfg.has("enabled") && cfg.path("enabled").asBoolean(false);
    }
}
