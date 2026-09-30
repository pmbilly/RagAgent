package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.chatpipeline.EventManager;
import com.ragagent.llm.extract.PipelineConfig;
import com.ragagent.retrieval.graph.RetrieveGraphRepository;
import com.ragagent.chatpipeline.PluginChatCompletion;
import com.ragagent.chatpipeline.PluginChatCompletionStream;
import com.ragagent.chatpipeline.PluginExtractEntity;
import com.ragagent.chatpipeline.PluginFilterTopK;
import com.ragagent.chatpipeline.PluginIntoChatMessage;
import com.ragagent.chatpipeline.PluginLoadHistory;
import com.ragagent.chatpipeline.PluginMemoryAffinity;
import com.ragagent.chatpipeline.PluginMemoryRecall;
import com.ragagent.chatpipeline.PluginMerge;
import com.ragagent.chatpipeline.PluginQueryUnderstand;
import com.ragagent.chatpipeline.PluginRerank;
import com.ragagent.chatpipeline.PluginSearchParallel;
import com.ragagent.chatpipeline.PluginWebFetch;
import com.ragagent.chatpipeline.PluginWikiBoost;
import com.ragagent.common.context.TenantContext;
import com.ragagent.retrieval.HybridSearchService;
import com.ragagent.common.settings.ConversationProperties;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.chat.LlmChatClients;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.ollama.OllamaService;
import com.ragagent.memory.service.MemoryService;
import com.ragagent.model.domain.Model;
import com.ragagent.model.service.ModelService;
import com.ragagent.rerank.Reranker;
import com.ragagent.rerank.RerankerConfig;
import com.ragagent.rerank.RerankerFactory;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageImage;
import com.ragagent.websearch.service.WebSearchService;
import com.ragagent.model.service.ModelRuntimeConfigs;
import com.ragagent.chatpipeline.PipelinePorts;

/**
 * chat 管线 + QA 面装配（波 4.6d；docs/known-issues/05-wave-4.md 原 §9「波 4.6c 补充」的 11 seam 清单）。
 *
 * <p>全部 adapter 是<b>纯新增</b>文件里的静态/内部类，不改既有 service。占位 seam
 * （TenantService/SessionService/WebSearchStateService/WebSearchProviderRepository）
 * Go 侧只判 nil 或存而不读——Java 传 null 等价。</p>
 *
 * <h2>已知差异（备案）</h2>
 * <ul>
 *   <li><b>HybridSearch 执行面（检索引擎批 2026-09-22 已接入）</b>：adapter 委托
 *       {@code HybridSearchService}（pgvector + ParadeDB BM25 + RRF 融合 +
 *       FAQ 迭代/负例过滤 + 富化装配，对照 knowledgebase_search*.go 全族）。
 *       外部向量店（ES/milvus/…）绑定仍按 2201 unavailable 同形拒绝（provider 批）。</li>
 *   <li><b>RetrieveGraphRepository（D 批已接线）</b>：注入 {@code Neo4jGraphConfig} 提供的
 *       {@code Neo4jGraphRepository}——NEO4J_ENABLE 未启用时其 driver 为 null，检索返回
 *       null（Go 的 nil driver 分支；ExtractEntity/SearchEntity 同样有 neo4jEnabled 闸门）。</li>
 *   <li><b>WebSearchStateService / WebSearchProviderRepository</b>：Go 当前存而不读，
 *       传 null。</li>
 * </ul>
 */
@Configuration
public class QaWiring {

    private static final Logger log = LoggerFactory.getLogger(QaWiring.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public QaWiring() {
    }

    // ── conversation 配置（对照 Go config.yaml 的 conversation 段） ────────────
    // ConversationProperties 自 2026-09-28 起由 @ConfigurationPropertiesScan 注册
    // （yml conversation.* 真正绑定进实例），模板回填在其 @PostConstruct 完成；
    // 此处的手工 @Bean 会让绑定面变成死键，已删除。

    // ── 11 seam 的生产 adapter ────────────────────────────────────────────────

    /** 对照 interfaces.ModelService：GetChatModel/GetRerankModel 的运行时工厂。 */
    @Bean
    public PipelinePorts.ModelService qaPipelineModelService(
            ModelService modelService,
            ObjectProvider<OllamaService> ollamaService,
            ConcurrencyGovernor concurrencyGovernor) {
        return new PipelinePorts.ModelService() {
            @Override
            public LlmChatClient getChatModel(String modelId) {
                Model model = modelService.getModelByID(modelId);
                if (model == null) {
                    return null;
                }
                var p = model.getParameters();
                ChatConfig config = ModelRuntimeConfigs.chatConfig(model,
                        p == null ? null : p.getAppId(),
                        p == null ? null : p.getAppSecret());
                return LlmChatClients.create(config, ollamaService.getIfAvailable(), concurrencyGovernor);
            }

            @Override
            public Reranker getRerankModel(String modelId) {
                Model model = modelService.getModelByID(modelId);
                if (model == null) {
                    return null;
                }
                var p = model.getParameters();
                RerankerConfig config = ModelRuntimeConfigs.rerankerConfig(model,
                        p == null ? null : p.getAppId(),
                        p == null ? null : p.getAppSecret());
                return RerankerFactory.newReranker(config);
            }
        };
    }

    /**
     * 对照 ctx TenantInfo 的 chat_pipeline 子集（2026-09-25 评审批接线）：
     * PluginSearch 的租户 web 配置读取——TenantContext 实时值 + 租户行合并。
     */
    @Bean
    public PipelinePorts.TenantService qaPipelineTenantService(
            com.ragagent.auth.service.TenantService tenantService) {
        return new PipelinePorts.TenantService() {
            @Override
            public com.ragagent.auth.domain.tenantconfig.WebSearchConfig currentWebSearchConfig() {
                Long tid = com.ragagent.common.context.TenantContext.currentTenantId();
                if (tid == null) {
                    return null;
                }
                try {
                    com.ragagent.auth.domain.Tenant tenant = tenantService.getTenantById(tid);
                    if (tenant == null || tenant.getWebSearchConfig() == null
                            || tenant.getWebSearchConfig().isNull()) {
                        return null;
                    }
                    return new ObjectMapper().treeToValue(tenant.getWebSearchConfig(),
                            com.ragagent.auth.domain.tenantconfig.WebSearchConfig.class);
                } catch (RuntimeException | JsonProcessingException e) {
                    return null;
                }
            }
        };
    }

    /** 对照 interfaces.KnowledgeBaseService 的 chat_pipeline 子集。 */
    @Bean
    public PipelinePorts.KnowledgeBaseService qaPipelineKnowledgeBaseService(
            KnowledgeBaseService kbService, HybridSearchService hybridSearchService) {
        return new PipelinePorts.KnowledgeBaseService() {
            @Override
            public KnowledgeBase getKnowledgeBaseByIdOnly(String id) {
                KnowledgeBase kb = kbService.getAllTenantById(id);
                if (kb == null) {
                    // 对照 Go：插件取 KB 元数据失败 → BizError(1003) → 管线 500 信封
                    // （错误文案与 BizException.getMessage 的 Go AppError 形态一致）
                    throw new PipelinePorts.PipelinePortException(
                            "error code: 1003, error message: knowledge base not found");
                }
                return kb;
            }

            @Override
            public List<KnowledgeBase> getKnowledgeBasesByIdsOnly(List<String> ids) {
                return ids.stream().map(kbService::getAllTenantById)
                        .filter(kb -> kb != null).collect(java.util.stream.Collectors.toList());
            }

            @Override
            public List<SearchResult> hybridSearch(String knowledgeBaseId, com.ragagent.common.pipeline.SearchParams params) {
                // KB 元数据缺失时保持 Go 同形的 1003 错误（A/B 场景 kse-unknown-kb 依赖）。
                if (kbService.getAllTenantById(knowledgeBaseId) == null) {
                    throw new PipelinePorts.PipelinePortException(
                            "error code: 1003, error message: knowledge base not found");
                }
                // Go 侧 params 是值拷贝（归一化不回传调用方）——Java 显式浅拷贝。
                com.ragagent.common.pipeline.SearchParams local = new com.ragagent.common.pipeline.SearchParams();
                local.setQueryText(params.getQueryText());
                local.setQueryEmbedding(params.getQueryEmbedding());
                local.setVectorThreshold(params.getVectorThreshold());
                local.setKeywordThreshold(params.getKeywordThreshold());
                local.setMatchCount(params.getMatchCount());
                local.setDisableKeywordsMatch(params.isDisableKeywordsMatch());
                local.setDisableVectorMatch(params.isDisableVectorMatch());
                local.setSkipContextEnrichment(params.isSkipContextEnrichment());
                local.setKnowledgeIds(params.getKnowledgeIds());
                local.setTagIds(params.getTagIds());
                local.setKnowledgeBaseIds(params.getKnowledgeBaseIds());
                return hybridSearchService.hybridSearch(knowledgeBaseId, local);
            }

            @Override
            public float[] getQueryEmbedding(String kbId, String queryText) {
                return hybridSearchService.getQueryEmbedding(kbId, queryText);
            }

            @Override
            public Map<String, String> resolveEmbeddingModelKeys(List<KnowledgeBase> kbs) {
                return hybridSearchService.resolveEmbeddingModelKeys(kbs);
            }
        };
    }

    /** 对照 interfaces.KnowledgeService 的 chat_pipeline 子集。 */
    @Bean
    public PipelinePorts.KnowledgeService qaPipelineKnowledgeService(KnowledgeService knowledgeService) {
        return new PipelinePorts.KnowledgeService() {
            @Override
            public Knowledge getKnowledgeById(String id) {
                // ctx 租户过滤（Go GetKnowledgeByID 带 ctx 过滤；Java 从 TenantContext 取）
                return knowledgeService.getKnowledgeInTenant(TenantContext.currentTenantId(), id);
            }

            @Override
            public List<Knowledge> getKnowledgeBatch(long tenantId, List<String> ids) {
                return knowledgeService.getKnowledgeBatch(tenantId, ids);
            }

            @Override
            public List<Knowledge> getKnowledgeBatchWithSharedAccess(long tenantId, List<String> ids) {
                return knowledgeService.getKnowledgeBatchWithSharedAccess(tenantId, ids);
            }
        };
    }

    /** 对照 interfaces.ChunkRepository 的 chat_pipeline 子集。 */
    @Bean
    public PipelinePorts.ChunkRepository qaPipelineChunkRepository(ChunkRepository chunkRepository) {
        return new PipelinePorts.ChunkRepository() {
            @Override
            public List<Chunk> listChunksById(long tenantId, List<String> ids) {
                return chunkRepository.listChunksById(tenantId, ids);
            }

            @Override
            public List<Chunk> listChunksByParentIds(long tenantId, List<String> parentIds) {
                List<Chunk> out = new ArrayList<>();
                for (String parentId : parentIds) {
                    out.addAll(chunkRepository.listChunkByParentId(tenantId, parentId));
                }
                return out;
            }
        };
    }

    /** 对照 interfaces.KnowledgeRepository（GetKnowledgeBatch）。 */
    @Bean
    public PipelinePorts.KnowledgeRepository qaPipelineKnowledgeRepository(KnowledgeService knowledgeService) {
        return new PipelinePorts.KnowledgeRepository() {
            @Override
            public List<Knowledge> getKnowledgeBatch(long tenantId, List<String> ids) {
                return knowledgeService.getKnowledgeBatch(tenantId, ids);
            }
        };
    }

    /** 对照 interfaces.KnowledgeBaseRepository（GetKnowledgeBaseByIDs）。 */
    @Bean
    public PipelinePorts.KnowledgeBaseRepository qaPipelineKnowledgeBaseRepository(
            KnowledgeBaseService kbService) {
        return new PipelinePorts.KnowledgeBaseRepository() {
            @Override
            public List<KnowledgeBase> getKnowledgeBaseByIDs(List<String> ids) {
                return ids.stream().map(kbService::getAllTenantById)
                        .filter(kb -> kb != null).collect(java.util.stream.Collectors.toList());
            }
        };
    }

    /** 对照 interfaces.MessageService 的 chat_pipeline 子集。 */
    @Bean
    public PipelinePorts.MessageService qaPipelineMessageService(MessageService messageService) {
        return new PipelinePorts.MessageService() {
            @Override
            public Message getMessage(String sessionId, String messageId) {
                return messageService.getMessage(sessionId, messageId);
            }

            @Override
            public List<Message> getRecentMessagesBySession(String sessionId, int limit) {
                return messageService.getRecentMessages(sessionId, limit);
            }

            @Override
            public void updateMessageImages(String sessionId, String messageId, List<MessageImage> images) {
                messageService.updateMessageImages(sessionId, messageId, images);
            }

            @Override
            public void updateMessageRenderedContent(String sessionId, String messageId, String renderedContent) {
                messageService.updateMessageRenderedContent(sessionId, messageId, renderedContent);
            }
        };
    }

    /** 对照 interfaces.MemoryService：签名与 memory.service.MemoryService 已对齐，直接委托。 */
    @Bean
    public PipelinePorts.MemoryService qaPipelineMemoryService(MemoryService memoryService) {
        return new PipelinePorts.MemoryService() {
            @Override
            public com.ragagent.memory.service.MemoryRecall recall(String query) {
                return memoryService.recall(query);
            }

            @Override
            public com.ragagent.memory.service.MemoryRetrievalContext retrievalContextFor() {
                return memoryService.retrievalContextFor();
            }

            @Override
            public Map<String, Integer> documentAffinity(List<String> knowledgeIds) {
                return memoryService.documentAffinity(knowledgeIds);
            }
        };
    }

    /** 对照 interfaces.WebSearchService.Search。 */
    @Bean
    public PipelinePorts.WebSearch qaPipelineWebSearch(WebSearchService webSearchService) {
        return new PipelinePorts.WebSearch() {
            @Override
            public List<com.ragagent.retrieval.domain.WebSearchResult> search(
                    String providerId, WebSearchService.WebSearchConfig config, String query) {
                Long tenantId = com.ragagent.common.context.TenantContext.currentTenantId();
                return webSearchService.search(tenantId == null ? 0L : tenantId, providerId, config, query);
            }
        };
    }

    // ── EventManager（对照 container.go L379-397 的插件注册序） ───────────────

    @Bean
    public PipelineConfig chatPipelineConfig(ConversationProperties conv) {
        PipelineConfig config = new PipelineConfig();
        config.setRewritePromptSystem(conv.getRewritePromptSystem());
        config.setRewritePromptUser(conv.getRewritePromptUser());
        // intent prompts：intent_prompts.yaml 的 id→content（Go config.go L1024-1027）。
        // 空表时 query_understand 走缺省意图提示——两侧 dev 缺省一致。
        config.setIntentSystemPrompts(loadIntentPrompts());
        // ExtractEntity 的结构化模板：neo4j 未启用时插件在入口直通，模板留空。
        return config;
    }

    private static Map<String, String> loadIntentPrompts() {
        Map<String, String> out = new LinkedHashMap<>();
        try (java.io.InputStream in = QaWiring.class.getClassLoader()
                .getResourceAsStream("agentm/prompt_templates/intent_prompts.yaml")) {
            if (in == null) {
                return out;
            }
            Object raw = new org.yaml.snakeyaml.Yaml().load(in);
            JsonNode root = MAPPER.valueToTree(raw);
            JsonNode list = root.get("templates");
            if (list != null && list.isArray()) {
                for (JsonNode t : list) {
                    String id = t.path("id").asText("");
                    if (!id.isEmpty()) {
                        out.put(id, t.path("content").asText(""));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("load intent prompts failed: {}", e.toString());
        }
        return out;
    }

    /**
     * 对照 container.Provide(chatpipeline.NewEventManager) + L380-397 的 Invoke 注册序。
     * RetrieveGraphRepository / TenantService / SessionService / WebSearchStateService /
     * WebSearchProviderRepository 传 null（Go 侧 nil 分支，见类注释）。
     * DataAnalysisSessionFactory：dev 无分析面，工厂恒返回不可用会话——
     * DATA_ANALYSIS 阶段只在 MergeResult 出现 CSV/Excel 命中时进入。
     */
    @Bean
    public EventManager chatPipelineEventManager(
            PipelinePorts.ModelService modelService,
            PipelinePorts.KnowledgeBaseService knowledgeBaseService,
            PipelinePorts.KnowledgeService knowledgeService,
            PipelinePorts.ChunkRepository chunkRepository,
            PipelinePorts.KnowledgeRepository knowledgeRepository,
            PipelinePorts.KnowledgeBaseRepository knowledgeBaseRepository,
            PipelinePorts.MessageService messageService,
            PipelinePorts.MemoryService memoryService,
            PipelinePorts.WebSearch webSearch,
            PipelinePorts.TenantService tenantService,
            RetrieveGraphRepository retrieveGraphRepository,
            PipelineConfig config) {

        EventManager mgr = new EventManager();
        // neo4j 未翻译 → extractEntity 关闭（Go 的 neo4jEnabled=false 同样直通）
        boolean neo4jEnabled = false;
        var extractEntityTemplate = new PipelineConfig.PromptTemplateStructured();

        // 对照 container.go L380-396 的 Invoke 顺序（注册序=执行链序）
        mgr.register(new com.ragagent.chatpipeline.PluginSearch(knowledgeBaseService, knowledgeService,
                null, config, webSearch, tenantService, null, null, null));
        mgr.register(new PluginRerank(modelService));
        mgr.register(new PluginWebFetch());
        mgr.register(new PluginMerge(chunkRepository, null));
        mgr.register(new com.ragagent.chatpipeline.PluginDataAnalysis(modelService, knowledgeService,
                new com.ragagent.chatpipeline.DataAnalysisSessionFactoryAdapter()));
        mgr.register(new PluginIntoChatMessage(messageService));
        mgr.register(new PluginChatCompletion(modelService));
        mgr.register(new PluginChatCompletionStream(modelService));
        mgr.register(new PluginFilterTopK());
        mgr.register(new PluginQueryUnderstand(modelService, messageService, memoryService, config));
        mgr.register(new PluginLoadHistory(messageService, config));
        mgr.register(new PluginMemoryRecall(memoryService));
        mgr.register(new PluginExtractEntity(modelService, extractEntityTemplate,
                knowledgeBaseRepository, knowledgeService, knowledgeRepository, neo4jEnabled));
        mgr.register(new com.ragagent.chatpipeline.PluginSearchEntity(
                retrieveGraphRepository, chunkRepository, knowledgeRepository));
        mgr.register(new PluginSearchParallel(mgr, knowledgeBaseService, knowledgeService,
                null, config,
                webSearch, tenantService, null, null, null,
                retrieveGraphRepository,
                chunkRepository, knowledgeRepository));
        mgr.register(new PluginWikiBoost(knowledgeBaseService));
        mgr.register(new PluginMemoryAffinity(memoryService));
        return mgr;
    }
}
