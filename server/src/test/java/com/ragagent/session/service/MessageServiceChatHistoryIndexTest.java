package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.retrieval.HybridSearchService;
import com.ragagent.session.mapper.MessageRepository;
import com.ragagent.session.mapper.MessageSuggestionRepository;
import com.ragagent.session.mapper.SessionRepository;

/**
 * 聊天历史 KB 索引的验收（对照 Go {@code IndexMessageToKB}，message.go L374-412）：
 * think 剥离、Q/A 全空短路、配置三要素不全跳过、passage 文案逐字、knowledge_id
 * 回写（无 session_id 条件）、失败只警告不抛。
 */
class MessageServiceChatHistoryIndexTest {

    private static final long TENANT = 10002L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageRepository messageRepository;
    private TenantService tenantService;
    private KnowledgeService knowledgeService;
    private MessageService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        tenantService = mock(TenantService.class);
        knowledgeService = mock(KnowledgeService.class);
        service = new MessageService(
                mock(SessionRepository.class),
                messageRepository,
                mock(MessageSuggestionRepository.class),
                knowledgeService,
                tenantService,
                mock(com.ragagent.knowledge.service.KnowledgeBaseService.class),
                mock(HybridSearchService.class),
                mock(ModelRuntimeFactory.class));
        TenantContext.set(TENANT, null, null, false, "u-1", false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void tenantWith(ObjectNode chatHistory) {
        Tenant t = new Tenant();
        t.setId(TENANT);
        t.setChatHistoryConfig(chatHistory);
        when(tenantService.getTenantById(TENANT)).thenReturn(t);
    }

    private static ObjectNode configured(String embeddingModelId, String kbId) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("enabled", true);
        n.put("embedding_model_id", embeddingModelId);
        n.put("knowledge_base_id", kbId);
        return n;
    }

    private static Knowledge knowledge(String id) {
        Knowledge k = new Knowledge();
        k.setId(id);
        return k;
    }

    @Test
    void indexesPairWithThinkStrippedAndLinksMessage() {
        tenantWith(configured("emb-1", "kb-1"));
        when(knowledgeService.createFromPassageSync(eq("kb-1"), anyList(), eq("")))
                .thenReturn(knowledge("k-1"));

        service.indexMessageToKb("问题", "先想一下<think>中间推理</think>最终答案", "m-1", "s-1");

        // passage 文案照 Go：剥 think 后 TrimSpace，userQuery 保持原样
        verify(knowledgeService).createFromPassageSync("kb-1",
                List.of("[Session: s-1]\nQ: 问题\nA: 先想一下最终答案"), "");
        verify(messageRepository).updateKnowledgeId("m-1", "k-1");
    }

    @Test
    void blankPairIsSkippedBeforeAnyLookup() {
        tenantWith(configured("emb-1", "kb-1"));

        service.indexMessageToKb("   ", "<think>只剥到这里</think>", "m-1", "s-1");

        verifyNoInteractions(knowledgeService);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void unconfiguredTenantSkipsIndexing() {
        // enabled 但缺 embedding 模型 → IsConfigured false（照 Go 的三要素）
        ObjectNode n = MAPPER.createObjectNode();
        n.put("enabled", true);
        n.put("embedding_model_id", "");
        n.put("knowledge_base_id", "kb-1");
        tenantWith(n);

        service.indexMessageToKb("q", "a", "m-1", "s-1");

        verifyNoInteractions(knowledgeService);
    }

    @Test
    void creationFailureOnlyWarns() {
        tenantWith(configured("emb-1", "kb-1"));
        when(knowledgeService.createFromPassageSync(anyString(), anyList(), anyString()))
                .thenThrow(new RuntimeException("段落 1 包含非法内容"));

        assertThatCode(() -> service.indexMessageToKb("q", "a", "m-1", "s-1"))
                .doesNotThrowAnyException();
        verify(messageRepository, never()).updateKnowledgeId(anyString(), anyString());
    }

    @Test
    void knowledgeIdWritebackFailureOnlyWarns() {
        tenantWith(configured("emb-1", "kb-1"));
        when(knowledgeService.createFromPassageSync(anyString(), anyList(), anyString()))
                .thenReturn(knowledge("k-1"));
        doThrow(new RuntimeException("db down"))
                .when(messageRepository).updateKnowledgeId("m-1", "k-1");

        assertThatCode(() -> service.indexMessageToKb("q", "a", "m-1", "s-1"))
                .doesNotThrowAnyException();
    }
}
