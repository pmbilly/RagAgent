package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.Reranker;
import com.ragagent.retrieval.HybridSearchService;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.common.pipeline.SearchParams;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageSearchResult;
import com.ragagent.session.domain.MessageWithSession;
import com.ragagent.session.mapper.MessageRepository;
import com.ragagent.session.mapper.MessageSuggestionRepository;
import com.ragagent.session.mapper.SessionRepository;

/**
 * 消息搜索的**向量路径**（对照 Go {@code vectorSearchViaKB} / {@code rerankResults} /
 * {@code getChatHistoryConfig} / {@code getRetrievalConfig}，message.go L344-368 + L651-773）。
 *
 * <p>HybridSearchService 与仓储全部 mock——这组测试钉的是**管线语义**，
 * 不是 SQL：未配置跳过、vector-only 参数映射、失败降级/上抛的分模式行为、
 * knowledge_id 关联回消息、SessionIDs 过滤、rerank 的阈值/topK/值拷贝语义。</p>
 */
class MessageServiceVectorSearchTest {

    private static final long TENANT = 10002L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageRepository messageRepository;
    private TenantService tenantService;
    private HybridSearchService hybridSearchService;
    private ModelRuntimeFactory modelRuntimeFactory;
    private MessageService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        tenantService = mock(TenantService.class);
        hybridSearchService = mock(HybridSearchService.class);
        modelRuntimeFactory = mock(ModelRuntimeFactory.class);
        service = new MessageService(
                mock(SessionRepository.class),
                messageRepository,
                mock(MessageSuggestionRepository.class),
                mock(com.ragagent.knowledge.service.KnowledgeService.class),
                tenantService,
                mock(com.ragagent.knowledge.service.KnowledgeBaseService.class),
                hybridSearchService,
                modelRuntimeFactory);
        TenantContext.set(TENANT, null, null, false, "u-1", false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private static ObjectNode chatHistoryConfig(boolean enabled, String embeddingModelId,
            String kbId) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("enabled", enabled);
        n.put("embedding_model_id", embeddingModelId);
        n.put("knowledge_base_id", kbId);
        return n;
    }

    private void tenantWith(com.fasterxml.jackson.databind.JsonNode chatHistory,
            com.fasterxml.jackson.databind.JsonNode retrieval) {
        Tenant t = new Tenant();
        t.setId(TENANT);
        t.setChatHistoryConfig(chatHistory);
        t.setRetrievalConfig(retrieval);
        when(tenantService.getTenantById(TENANT)).thenReturn(t);
    }

    private static SearchResult kbHit(String chunkId, String knowledgeId, double score,
            String content) {
        SearchResult r = new SearchResult();
        r.setId(chunkId);
        r.setKnowledgeId(knowledgeId);
        r.setScore(score);
        r.setContent(content);
        return r;
    }

    private static Message message(String id, String sessionId, String requestId, String role,
            String content, String knowledgeId) {
        Message m = new Message();
        m.setId(id);
        m.setSessionId(sessionId);
        m.setRequestId(requestId);
        m.setRole(role);
        m.setContent(content);
        m.setKnowledgeId(knowledgeId);
        return m;
    }

    private static MessageWithSession mws(Message m, String title) {
        return new MessageWithSession(m, title);
    }

    private void ownedAll(String... sessionIds) {
        Map<String, Boolean> owned = new HashMap<>();
        for (String s : sessionIds) {
            owned.put(s, true);
        }
        when(messageRepository.ownedSessionIds(eq(TENANT), eq("u-1"), anyList()))
                .thenReturn(owned);
    }

    // ── 未配置：向量路径恒空，行为与 Go 的 nil, nil 分支一致 ─────────────────

    @Test
    void hybridModeWithoutChatHistoryConfigNeverTouchesTheVectorPath() {
        tenantWith(null, null);
        when(messageRepository.searchMessagesByKeyword(TENANT, "u-1", "spring boot", null, 60))
                .thenReturn(List.of(
                        mws(message("m1", "s1", "r1", "user", "spring boot tips", null), "S1")));
        when(messageRepository.getMessagesByRequestIds(List.of("r1")))
                .thenReturn(List.of(
                        mws(message("m2", "s1", "r1", "assistant", "just use search", null),
                                "S1")));
        ownedAll("s1");

        MessageSearchResult result = service.searchMessages("spring boot", "hybrid", 20, null);

        verifyNoInteractions(hybridSearchService, modelRuntimeFactory);
        assertThat(result.getTotal()).isEqualTo(1);
        // hybrid 模式的分值是 RRF 融合分 1/(60+1)；partner 补对的 "" 与 "keyword"
        // 不同 → matchType 升 hybrid（golden 钉过的同款契约）
        assertThat(result.getItems().get(0).getScore()).isEqualTo(1.0 / 61);
        assertThat(result.getItems().get(0).getMatchType()).isEqualTo("hybrid");
    }

    @Test
    void disabledChatHistoryConfigAlsoSkipsVectorSearch() {
        tenantWith(chatHistoryConfig(false, "emb-1", "kb-chat-1"), null);

        MessageSearchResult result = service.searchMessages("q", "vector", 20, null);

        verifyNoInteractions(hybridSearchService);
        assertThat(result.getTotal()).isZero();
    }

    // ── vector 模式：参数映射 + knowledge_id 回映射 + 按分排序 ───────────────

    @Test
    void vectorModeMapsKbHitsToMessagesByKnowledgeIdAndSortsByScore() {
        ObjectNode rc = MAPPER.createObjectNode();
        rc.put("embeddingTopK", 7);
        rc.put("vectorThreshold", 0.25);
        tenantWith(chatHistoryConfig(true, "emb-1", "kb-chat-1"), rc);

        when(hybridSearchService.hybridSearch(eq("kb-chat-1"), any(SearchParams.class)))
                .thenReturn(new ArrayList<>(List.of(
                        kbHit("c1", "k1", 0.9, "doc one"),
                        kbHit("c2", "k2", 0.7, "doc two"))));
        // 库序故意与分数序相反——结果必须按 KB 分数降序重排
        when(messageRepository.getMessagesByKnowledgeIds(List.of("k1", "k2")))
                .thenReturn(List.of(
                        mws(message("m-b", "s1", "", "assistant", "answer two", "k2"), "S1"),
                        mws(message("m-a", "s1", "", "user", "question one", "k1"), "S1")));
        ownedAll("s1");

        MessageSearchResult result = service.searchMessages("question", "vector", 20, null);

        // vector-only 参数逐字段对照 Go vectorSearchViaKB
        ArgumentCaptor<SearchParams> captor = ArgumentCaptor.forClass(SearchParams.class);
        verify(hybridSearchService).hybridSearch(eq("kb-chat-1"), captor.capture());
        SearchParams sp = captor.getValue();
        assertThat(sp.getQueryText()).isEqualTo("question");
        assertThat(sp.getMatchCount()).isEqualTo(7);
        assertThat(sp.getVectorThreshold()).isEqualTo(0.25);
        assertThat(sp.isDisableKeywordsMatch()).isTrue();
        assertThat(sp.isDisableVectorMatch()).isFalse();
        // rerank_model_id 为空 → 不取模型（Go: rc.RerankModelID == "" 直接返回）
        verifyNoInteractions(modelRuntimeFactory);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getItems().get(0).getScore()).isEqualTo(0.9);
        assertThat(result.getItems().get(0).getMatchType()).isEqualTo("vector");
        assertThat(result.getItems().get(0).getSessionId()).isEqualTo("s1");
        assertThat(result.getItems().get(0).getSessionTitle()).isEqualTo("S1");
        assertThat(result.getItems().get(0).getQueryContent()).isEqualTo("question one");
        assertThat(result.getItems().get(1).getScore()).isEqualTo(0.7);
        assertThat(result.getItems().get(1).getAnswerContent()).isEqualTo("answer two");
        // 所有权复核按剩余会话 id 收窄
        verify(messageRepository).ownedSessionIds(eq(TENANT), eq("u-1"), anyList());
    }

    // ── 失败路径：vector 上抛、hybrid 降级 ──────────────────────────────────

    @Test
    void vectorModePropagatesKbSearchFailure() {
        tenantWith(chatHistoryConfig(true, "emb-1", "kb-chat-1"), null);
        when(hybridSearchService.hybridSearch(eq("kb-chat-1"), any(SearchParams.class)))
                .thenThrow(new HybridSearchService.RetrievalException("kb engine offline"));

        assertThatThrownBy(() -> service.searchMessages("q", "vector", 20, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("KB hybrid search failed: kb engine offline");
        // Go：vector 模式不做关键词兜底，直接 return nil, err
        verify(messageRepository, never()).searchMessagesByKeyword(anyLong(), anyString(),
                anyString(), anyList(), anyInt());
    }

    @Test
    void hybridModeFallsBackToKeywordOnlyWhenKbSearchFails() {
        tenantWith(chatHistoryConfig(true, "emb-1", "kb-chat-1"), null);
        when(hybridSearchService.hybridSearch(eq("kb-chat-1"), any(SearchParams.class)))
                .thenThrow(new HybridSearchService.RetrievalException("kb engine offline"));
        when(messageRepository.searchMessagesByKeyword(TENANT, "u-1", "q", null, 60))
                .thenReturn(List.of(
                        mws(message("m1", "s1", "r1", "user", "q body", null), "S1")));
        when(messageRepository.getMessagesByRequestIds(List.of("r1"))).thenReturn(List.of());
        ownedAll("s1");

        MessageSearchResult result = service.searchMessages("q", "hybrid", 20, null);

        assertThat(result.getTotal()).isEqualTo(1);
        // hybrid 分值 = RRF 1/(60+1)（keyword 线性分值只出现在 keyword 模式）
        assertThat(result.getItems().get(0).getScore()).isEqualTo(1.0 / 61);
        assertThat(result.getItems().get(0).getMatchType()).isEqualTo("keyword");
    }

    // ── SessionIDs 过滤只保留指定会话的向量命中 ─────────────────────────────

    @Test
    void sessionFilterRestrictsVectorHitsToGivenSessions() {
        tenantWith(chatHistoryConfig(true, "emb-1", "kb-chat-1"), null);
        when(hybridSearchService.hybridSearch(eq("kb-chat-1"), any(SearchParams.class)))
                .thenReturn(new ArrayList<>(List.of(
                        kbHit("c1", "k1", 0.9, "d1"),
                        kbHit("c2", "k2", 0.7, "d2"))));
        when(messageRepository.getMessagesByKnowledgeIds(List.of("k1", "k2")))
                .thenReturn(List.of(
                        mws(message("m-a", "s1", "", "user", "a1", "k1"), "S1"),
                        mws(message("m-b", "s2", "", "user", "b2", "k2"), "S2")));
        ownedAll("s2");

        MessageSearchResult result = service.searchMessages("q", "vector", 20, List.of("s2"));

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getItems().get(0).getSessionId()).isEqualTo("s2");
        assertThat(result.getItems().get(0).getScore()).isEqualTo(0.7);
        // 归属复核只查过滤后剩下的会话（Go：sessionIDs 从过滤后的 items 里收集）
        verify(messageRepository).ownedSessionIds(TENANT, "u-1", List.of("s2"));
    }

    // ── rerank：阈值过滤 + topK 截断 + 分值替换（值拷贝不改原命中） ──────────

    @Test
    void rerankModelFiltersByThresholdTopKAndReplacesScores() {
        ObjectNode rc = MAPPER.createObjectNode();
        // 0 → 有效缺省：EmbeddingTopK=50、VectorThreshold=0.15（GetEffective* 语义）
        rc.put("embeddingTopK", 0);
        rc.put("vectorThreshold", 0);
        rc.put("rerankModelId", "rr-1");
        rc.put("rerankTopK", 1);
        rc.put("rerankThreshold", 0.5);
        tenantWith(chatHistoryConfig(true, "emb-1", "kb-chat-1"), rc);

        SearchResult hit1 = kbHit("c1", "k1", 0.9, "doc one");
        SearchResult hit2 = kbHit("c2", "k2", 0.8, "doc two");
        SearchResult hit3 = kbHit("c3", "k3", 0.7, "doc three");
        when(hybridSearchService.hybridSearch(eq("kb-chat-1"), any(SearchParams.class)))
                .thenReturn(new ArrayList<>(List.of(hit1, hit2, hit3)));

        Reranker reranker = mock(Reranker.class);
        when(modelRuntimeFactory.getRerankModel("rr-1")).thenReturn(reranker);
        RankResult rr1 = new RankResult();
        rr1.setIndex(2);
        rr1.setRelevanceScore(0.6);
        RankResult rr2 = new RankResult();
        rr2.setIndex(0);
        rr2.setRelevanceScore(0.4); // < threshold 0.5 → 被滤
        when(reranker.rerank("q", List.of("doc one", "doc two", "doc three")))
                .thenReturn(List.of(rr1, rr2));

        when(messageRepository.getMessagesByKnowledgeIds(List.of("k3")))
                .thenReturn(List.of(
                        mws(message("m-c", "s1", "", "assistant", "c3 answer", "k3"), "S1")));
        ownedAll("s1");

        MessageSearchResult result = service.searchMessages("q", "vector", 20, null);

        // SearchParams 用有效缺省值
        ArgumentCaptor<SearchParams> captor = ArgumentCaptor.forClass(SearchParams.class);
        verify(hybridSearchService).hybridSearch(eq("kb-chat-1"), captor.capture());
        assertThat(captor.getValue().getMatchCount()).isEqualTo(50);
        assertThat(captor.getValue().getVectorThreshold()).isEqualTo(0.15);

        // 重排后只剩 index=2 那条（threshold 滤掉 0.4、topK=1 截断），score 换成重排分
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getItems().get(0).getScore()).isEqualTo(0.6);
        assertThat(result.getItems().get(0).getMatchType()).isEqualTo("vector");
        assertThat(result.getItems().get(0).getAnswerContent()).isEqualTo("c3 answer");
        // Go 是 struct 值拷贝：原命中对象的分不被改写
        assertThat(hit1.getScore()).isEqualTo(0.9);
        assertThat(hit3.getScore()).isEqualTo(0.7);
    }

    @Test
    void rerankCallFailureFallsBackToOriginalKbHits() {
        ObjectNode rc = MAPPER.createObjectNode();
        rc.put("rerankModelId", "rr-1");
        tenantWith(chatHistoryConfig(true, "emb-1", "kb-chat-1"), rc);

        when(hybridSearchService.hybridSearch(eq("kb-chat-1"), any(SearchParams.class)))
                .thenReturn(new ArrayList<>(List.of(
                        kbHit("c1", "k1", 0.9, "d1"),
                        kbHit("c2", "k2", 0.7, "d2"))));
        Reranker reranker = mock(Reranker.class);
        when(modelRuntimeFactory.getRerankModel("rr-1")).thenReturn(reranker);
        when(reranker.rerank(anyString(), anyList()))
                .thenThrow(new RuntimeException("rerank endpoint down"));

        when(messageRepository.getMessagesByKnowledgeIds(List.of("k1", "k2")))
                .thenReturn(List.of(
                        mws(message("m-a", "s1", "", "user", "a1", "k1"), "S1"),
                        mws(message("m-b", "s1", "", "assistant", "b2", "k2"), "S1")));
        ownedAll("s1");

        MessageSearchResult result = service.searchMessages("q", "vector", 20, null);

        // Go：Rerank 失败 Warnf 后 return results——两条原始命中原样走完管线
        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getItems().get(0).getScore()).isEqualTo(0.9);
        assertThat(result.getItems().get(1).getScore()).isEqualTo(0.7);
    }
}
