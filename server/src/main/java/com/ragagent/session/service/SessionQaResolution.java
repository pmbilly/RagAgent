package com.ragagent.session.service;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.model.domain.Model;
import com.ragagent.session.domain.Session;

/**
 * 知识库/模型/租户解析协作者:mention 与 tag 范围收敛到 agent 授权面、chat 模型选择、
 * 检索租户判定、搜索目标视图构建与自定义 agent 提示词解析。
 *
 * <p>持有 {@link SessionKnowledgeQaService} 回引以访问其依赖与共享 helpers;本类不得独立实例化。</p>
 */
final class SessionQaResolution {


    private static final ObjectMapper JSON = new ObjectMapper();

    private final SessionKnowledgeQaService service;

    /** agent 覆盖簇。 */
    private final QaSearchTargets searchTargets;

    public List<SessionKnowledgeQaService.SearchTargetView> buildSearchTargets(long tenantId, List<String> knowledgeBaseIds, List<String> knowledgeIds, List<QaSupport.TagScope> tagScopes) {
        return searchTargets.buildSearchTargets(tenantId, knowledgeBaseIds, knowledgeIds, tagScopes);
    }

    /** agent 覆盖簇。 */
    private final QaChatManageOverrides chatOverrides;

    void applyAgentOverridesToChatManage(QaSupport.QaRequest req, ChatManage cm) {
        chatOverrides.applyAgentOverridesToChatManage(req, cm);
    }

    Prompts resolveCustomAgentPrompts(com.ragagent.agent.management.domain.CustomAgentEntity agent, ObjectNode c) {
        return chatOverrides.resolveCustomAgentPrompts(agent, c);
    }

    /** mention/tag 收敛簇。 */
    private final QaMentionTagScope mentionTagScope;

    public SessionKnowledgeQaService.KnowledgeResolution resolveKnowledgeBases(QaSupport.QaRequest req) {
        return mentionTagScope.resolveKnowledgeBases(req);
    }

    /**
     * 把 @mention 的 KB/知识收窄到共享 agent 的允许范围——允许集为空则**全部拦下**；
     * 知识按其所属 KB 是否在允许集内判定（批量取按 **agent 的租户**查）。
     */
    public MentionScope restrictMentionsToAgentScope(com.ragagent.agent.management.domain.CustomAgentEntity agent, ObjectNode agentCfg, long sessionTenantId, List<String> kbIds, List<String> knowledgeIds) {
        return mentionTagScope.restrictMentionsToAgentScope(agent, agentCfg, sessionTenantId, kbIds, knowledgeIds);
    }

    /** 按允许 KB 集过滤 tag 范围；空输入返回空列表。 */
    public List<QaSupport.TagScope> restrictTagScopesToAgentScope(com.ragagent.agent.management.domain.CustomAgentEntity agent, ObjectNode agentCfg, long sessionTenantId, List<QaSupport.TagScope> tagScopes) {
        return mentionTagScope.restrictTagScopesToAgentScope(agent, agentCfg, sessionTenantId, tagScopes);
    }

    /** KB 范围簇。 */
    private final QaKbScope kbScope;

    /** 按 agent 配置的 kbSelectionMode 解析允许 KB 集（all 模式做工具能力过滤）。 */
    public List<String> resolveKnowledgeBasesFromAgent(com.ragagent.agent.management.domain.CustomAgentEntity agent, ObjectNode agentCfg, long sessionTenantId) {
        return kbScope.resolveKnowledgeBasesFromAgent(agent, agentCfg, sessionTenantId);
    }

    static boolean kbSatisfiesAgentRequirements(KnowledgeBase kb, ObjectNode agentCfg) {
        return QaKbScope.kbSatisfiesAgentRequirements(kb, agentCfg);
    }

    public long resolveRetrievalTenantId(QaSupport.QaRequest req) {
        return kbScope.resolveRetrievalTenantId(req);
    }

    /** 作用域能否读该 KB：① API-key 作用域（拒绝路径）→ ② 仅本租户可读。 */
    boolean callerCanReadKb(String kbId, long ownerTenantId, long retrievalTenantId) {
        return kbScope.callerCanReadKb(kbId, ownerTenantId, retrievalTenantId);
    }

    /** 模型选择簇。 */
    private final QaModelSelection modelSelection;

    public String resolveChatModelId(QaSupport.QaRequest req, List<String> knowledgeBaseIds, List<String> knowledgeIds) {
        return modelSelection.resolveChatModelId(req, knowledgeBaseIds, knowledgeIds);
    }

    Model findModel(String id) {
        return modelSelection.findModel(id);
    }

    String selectChatModelId(Session session, List<String> knowledgeBaseIds, List<String> knowledgeIds) {
        return modelSelection.selectChatModelId(session, knowledgeBaseIds, knowledgeIds);
    }

    public KnowledgeBase findKnowledgeBase(String kbId) {
        return modelSelection.findKnowledgeBase(kbId);
    }

    KnowledgeBase findKb(String kbId) {
        return modelSelection.findKb(kbId);
    }

    /** mention 解析结果(agent 授权面收敛后)。 */
    public record MentionScope(List<String> kbIds, List<String> knowledgeIds) {}

    SessionQaResolution(SessionKnowledgeQaService service) {
        this.service = service;
            this.modelSelection = new QaModelSelection(service);
        this.kbScope = new QaKbScope(service);
        this.mentionTagScope = new QaMentionTagScope(service, this.kbScope);
        this.chatOverrides = new QaChatManageOverrides(service);
        this.searchTargets = new QaSearchTargets(service, this.kbScope);
}

    /** {@link #resolveCustomAgentPrompts} 的返回：system / context 两段提示词。 */
    record Prompts(String system, String context) {}

    /**
     * agent 是否为 agent-chat 模式：{@code agentMode == "smart-reasoning"}。
     *
     * <p>⚠️ 前端/内置/IM 写的都是 {@code smart-reasoning}，判别取值不要改——
     * 写错会让 agent-chat 被误判进 RAG 快答分支。</p>
     */
    static boolean isAgentMode(ObjectNode c) {
        return "smart-reasoning".equals(c.path("agentMode").asText(""));
    }

    static String templateContentByIdAndFile(String id, String file) {
        try (java.io.InputStream in = SessionKnowledgeQaService.class.getClassLoader()
                .getResourceAsStream("agent/management/prompt_templates/" + file)) {
            if (in == null) {
                return null;
            }
            Object raw = new org.yaml.snakeyaml.Yaml().load(in);
            JsonNode root = JSON.valueToTree(raw);
            JsonNode list = root.get("templates");
            if (list == null || !list.isArray()) {
                return null;
            }
            for (JsonNode t : list) {
                if (id.equals(t.path("id").asText(""))) {
                    return t.path("content").asText("");
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
