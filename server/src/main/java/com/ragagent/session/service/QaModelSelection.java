package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.model.domain.Model;
import com.ragagent.session.domain.Session;

/**
 * {@code SessionQaResolution} 的**模型选择簇**（§14.9c 刀 8）：按请求/知识库/会话推导 chat 模型 id
 * （findModel/selectChatModelId 内部实现）与 KB 查找（findKb/findKnowledgeBase）。
 *
 * <p>依赖只有 {@code SessionKnowledgeQaService service}（与宿主同款风格）；门面对**每个搬走的成员**留一行
 * 薄委托，宿主与同族剩余成员的调用点零改动。</p>
 */
final class QaModelSelection {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QaModelSelection.class);

    private final SessionKnowledgeQaService service;

    QaModelSelection(SessionKnowledgeQaService service) {
        this.service = service;
    }

    public String resolveChatModelId(QaSupport.QaRequest req, List<String> knowledgeBaseIds,



            List<String> knowledgeIds) {



        String summaryModelId = req.summaryModelId == null ? "" : req.summaryModelId.trim();



        String configuredAgentModelId = "";



        if (req.agentConfig != null) {



            configuredAgentModelId = req.agentConfig.path("model_id").asText("").trim();



            if (configuredAgentModelId.isEmpty()



                    && !"builtin-wiki-fixer".equals(req.agentRow.getId())) {



                throw new RuntimeException("chat model is not configured: please set model_id on agent "



                        + req.agentRow.getId());



            }



            if (!configuredAgentModelId.isEmpty()) {



                Model model = findModel(configuredAgentModelId);



                if (model == null || !"KnowledgeQA".equals(model.getType())) {



                    throw new RuntimeException("configured chat model " + configuredAgentModelId



                            + " is unavailable for agent " + req.agentRow.getId());



                }



            }



        }







        if (!summaryModelId.isEmpty()) {



            Model model = findModel(summaryModelId);



            if (model != null && "KnowledgeQA".equals(model.getType())) {



                log.info("Using request's summary model override: {}", summaryModelId);



                return summaryModelId;



            }



            log.warn("Request provided invalid summary model ID {}, falling back", summaryModelId);



        }



        if (!configuredAgentModelId.isEmpty()) {



            log.info("Using custom agent's model_id: {}", configuredAgentModelId);



            return configuredAgentModelId;



        }



        return selectChatModelId(req.session, knowledgeBaseIds, knowledgeIds);



    }
    Model findModel(String id) {



        try {



            return service.modelService.getModelByID(id);



        } catch (RuntimeException e) {



            return null;



        }



    }
    String selectChatModelId(Session session, List<String> knowledgeBaseIds, List<String> knowledgeIds) {



        List<String> kbIds = new ArrayList<>(knowledgeBaseIds);



        if (kbIds.isEmpty() && !knowledgeIds.isEmpty()) {



            long tenantId = SessionKnowledgeQaService.requireTenantId();



            try {



                List<Knowledge> knowledgeList = service.knowledgeService.getKnowledgeBatchWithSharedAccess(



                        tenantId, knowledgeIds);



                Set<String> kbIdSet = new LinkedHashSet<>();



                for (Knowledge k : knowledgeList) {



                    if (k != null && k.getKnowledgeBaseId() != null && !k.getKnowledgeBaseId().isEmpty()) {



                        kbIdSet.add(k.getKnowledgeBaseId());



                    }



                }



                kbIds.addAll(kbIdSet);



                log.info("Derived {} knowledge base IDs from {} knowledge IDs for model selection",



                        kbIds.size(), knowledgeIds.size());



            } catch (RuntimeException e) {



                log.warn("Failed to get knowledge batch for model selection: {}", e.toString());



            }



        }



        if (!kbIds.isEmpty()) {



            for (String kbId : kbIds) {



                KnowledgeBase kb = findKb(kbId);



                if (kb != null && kb.getSummaryModelId() != null && !kb.getSummaryModelId().isEmpty()) {



                    Model model = findModel(kb.getSummaryModelId());



                    if (model != null && "remote".equals(model.getSource())) {



                        log.info("Using Remote summary model from knowledge base");



                        return kb.getSummaryModelId();



                    }



                }



            }



            KnowledgeBase kb = findKb(kbIds.get(0));



            if (kb == null) {



                throw new RuntimeException("failed to get knowledge base " + kbIds.get(0) + ": record not found");



            }



            if (kb.getSummaryModelId() != null && !kb.getSummaryModelId().isEmpty()) {



                log.info("Using summary model from first knowledge base {}: {}", kbIds.get(0), kb.getSummaryModelId());



                return kb.getSummaryModelId();



            }



        }







        List<Model> models = service.modelService.listModels();



        for (Model model : models) {



            if (model != null && "KnowledgeQA".equals(model.getType())) {



                log.info("Using first available KnowledgeQA model: {}", model.getId());



                return model.getId();



            }



        }



        throw new RuntimeException("no chat model ID available: no knowledge bases configured and no available models");



    }
    public KnowledgeBase findKnowledgeBase(String kbId) {



        return findKb(kbId);



    }
    KnowledgeBase findKb(String kbId) {



        try {



            return service.knowledgeBaseService.getAllTenantById(kbId);



        } catch (RuntimeException e) {



            return null;



        }



    }
}
