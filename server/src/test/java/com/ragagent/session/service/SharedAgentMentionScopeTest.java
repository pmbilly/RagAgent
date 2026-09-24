package com.ragagent.session.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agentm.domain.CustomAgentEntity;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 共享 agent 的 @mention 收敛测试（对照 Go {@code restrictMentionsToAgentScope} /
 * {@code restrictTagScopesToAgentScope}，session_qa_helpers.go L295-340 / L62-86）：
 * 调用方注入范围外 KB/知识/tag 时必须被拦下——这是共享 agent 的越权边界。
 */
class SharedAgentMentionScopeTest {

    private SessionKnowledgeQaService service;
    private KnowledgeService knowledgeService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        knowledgeService = mock(KnowledgeService.class);
        ObjectProvider<com.ragagent.org.service.KbShareService> shareProvider =
                mock(ObjectProvider.class);
        when(shareProvider.getIfAvailable()).thenReturn(null);
        service = new SessionKnowledgeQaService(null, null, null, knowledgeService,
                mock(KnowledgeBaseService.class), null, null, null, null, shareProvider);
    }

    /** agent 配置：selected 模式 + 指定的允许 KB 集（无需 DB）。 */
    private static ObjectNode agentConfig(String... allowedKbs) {
        ObjectNode cfg = JsonNodeFactory.instance.objectNode();
        cfg.put("kb_selection_mode", "selected");
        ArrayNode arr = cfg.putArray("knowledge_bases");
        for (String kb : allowedKbs) {
            arr.add(kb);
        }
        return cfg;
    }

    private static CustomAgentEntity agent(long tenantId) {
        CustomAgentEntity a = new CustomAgentEntity();
        a.setId("agent-1");
        a.setTenantId(tenantId);
        return a;
    }

    private static Knowledge knowledge(String id, String kbId) {
        Knowledge k = new Knowledge();
        k.setId(id);
        k.setKnowledgeBaseId(kbId);
        return k;
    }

    @Test
    @DisplayName("@mention：范围外的 KB 被拦下，知识按其所属 KB 判定")
    void mentionsRestrictedToAllowedKbs() {
        when(knowledgeService.getKnowledgeBatch(anyLong(), anyList()))
                .thenReturn(List.of(knowledge("doc-1", "kb-1"), knowledge("doc-2", "kb-2")));

        SessionKnowledgeQaService.MentionScope scope = service.restrictMentionsToAgentScope(
                agent(9L), agentConfig("kb-1"), 7L,
                List.of("kb-1", "kb-2"), List.of("doc-1", "doc-2"));

        assertEquals(List.of("kb-1"), scope.kbIds());
        assertEquals(List.of("doc-1"), scope.knowledgeIds());
    }

    @Test
    @DisplayName("@mention：agent 允许集为空 → 全部拦下（含 KB 与知识）")
    void emptyAllowedScopeBlocksEverything() {
        SessionKnowledgeQaService.MentionScope scope = service.restrictMentionsToAgentScope(
                agent(9L), agentConfig(), 7L,
                List.of("kb-1"), List.of("doc-1"));

        assertTrue(scope.kbIds().isEmpty());
        assertTrue(scope.knowledgeIds().isEmpty());
    }

    @Test
    @DisplayName("tag 范围：按允许 KB 过滤；空输入返回空列表")
    void tagScopesFiltered() {
        QaSupport.TagScope ok = new QaSupport.TagScope();
        ok.knowledgeBaseId = "kb-1";
        ok.tagIds = new ArrayList<>(List.of("t-1"));
        QaSupport.TagScope blocked = new QaSupport.TagScope();
        blocked.knowledgeBaseId = "kb-2";
        blocked.tagIds = new ArrayList<>(List.of("t-2"));

        List<QaSupport.TagScope> filtered = service.restrictTagScopesToAgentScope(
                agent(9L), agentConfig("kb-1"), 7L, List.of(ok, blocked));

        assertEquals(1, filtered.size());
        assertEquals("kb-1", filtered.get(0).knowledgeBaseId);
        assertTrue(service.restrictTagScopesToAgentScope(
                agent(9L), agentConfig("kb-1"), 7L, List.of()).isEmpty());
    }
}
