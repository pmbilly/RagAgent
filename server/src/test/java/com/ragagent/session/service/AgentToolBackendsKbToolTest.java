package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.config.ConversationProperties;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.service.MemorySearchResult;
import com.ragagent.memory.service.MemoryService;
import com.ragagent.retrieval.HybridSearchService;
import com.ragagent.session.domain.MessageSearchGroupItem;
import com.ragagent.session.domain.MessageSearchResult;
import com.ragagent.wiki.service.WikiPageService;

/**
 * 工具接线钉（2026-09-23 接线批：切片 1 KB 五件 + 切片 2a 会话/记忆/DB 三件）。
 *
 * <p>此前 {@code registerTools} 对这些名字只落 {@code default → "Unknown tool"}——
 * 工具类与执行面都已就位但从未构造。本测试钉住「可构造且名字一致」+ 关键映射。</p>
 */
class AgentToolBackendsKbToolTest {

    private AgentToolBackends backends;
    private MessageService messageService;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        memoryService = mock(MemoryService.class);
        backends = new AgentToolBackends(
                mock(KnowledgeBaseService.class),
                mock(KnowledgeService.class),
                mock(ChunkRepository.class),
                mock(HybridSearchService.class),
                mock(ConversationProperties.class),
                messageService,
                memoryService,
                mock(WikiPageService.class),
                mock(com.ragagent.websearch.service.WebSearchService.class),
                mock(com.ragagent.auth.service.TenantService.class),
                mock(com.ragagent.knowledge.service.TenantFileStorage.class),
                mock(DataSource.class));
    }

    @Test
    void toolFamilyIsConstructible() {
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(
                List.of(SearchTarget.wholeKb("kb1", 10002)));
        for (String name : List.of("knowledge_search", "grep_chunks",
                "list_knowledge_chunks", "query_knowledge_graph", "get_document_info",
                "search_conversations", "search_memory", "database_query", "data_schema")) {
            var tool = backends.createTool(name, targets, null, "owner-1", "session-1");
            assertThat(tool).as("工具 %s 必须可构造（此前恒走 Unknown tool）", name).isNotNull();
            assertThat(tool.getName()).as("工具名与注册名一致").isEqualTo(name);
        }
    }

    @Test
    void nonKbNamesReturnNull() {
        assertThat(backends.createTool("web_search", null, null, "o", "s")).isNull();
        assertThat(backends.createTool("wiki_read_page", null, null, "o", "s")).isNull();
        assertThat(backends.createTool("not_a_tool", null, null, "o", "s")).isNull();
    }

    /** 切片 2d：web 两件经 createWebTool 可构造且名字一致（Go L1071-1082）。 */
    @Test
    void webToolsAreConstructible() {
        for (String name : List.of("web_search", "web_fetch")) {
            var tool = backends.createWebTool(name, 5, "prov-1");
            assertThat(tool).as("工具 %s 必须可构造（此前恒走 Unknown tool）", name).isNotNull();
            assertThat(tool.getName()).isEqualTo(name);
        }
        assertThat(backends.createWebTool("not_a_tool", 5, "")).isNull();
    }

    /** 会话检索映射：MessageSearchGroupItem → ExchangeView（date 取 LocalDate）。 */
    @Test
    void conversationSearchMapsGroupItems() {
        MessageSearchGroupItem item = new MessageSearchGroupItem();
        item.setSessionId("s-1");
        item.setSessionTitle("会话一");
        item.setQueryContent("问句");
        item.setAnswerContent("答句");
        item.setCreatedAt(OffsetDateTime.parse("2026-09-01T08:00:00+08:00"));
        MessageSearchResult result = new MessageSearchResult();
        result.setItems(List.of(item));
        when(messageService.searchMessages(anyString(), anyString(), anyInt(), any(), anyString()))
                .thenReturn(result);

        var views = backends.conversationSearch().search("问句", 5, "owner-1");
        assertThat(views).hasSize(1);
        assertThat(views.get(0).sessionId()).isEqualTo("s-1");
        assertThat(views.get(0).sessionTitle()).isEqualTo("会话一");
        assertThat(views.get(0).createdAt()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(views.get(0).queryContent()).isEqualTo("问句");
        assertThat(views.get(0).answerContent()).isEqualTo("答句");
    }

    /** 记忆检索映射：MemoryItem → MemoryItemView（validFrom 取 LocalDate）。 */
    @Test
    void memorySearchMapsItems() {
        MemoryItem item = new MemoryItem();
        item.setKind("fact");
        item.setTopic("主题");
        item.setContent("内容");
        item.setValidFrom(OffsetDateTime.parse("2026-08-01T00:00:00+08:00"));
        when(memoryService.searchMemory(anyString(), anyInt()))
                .thenReturn(new MemorySearchResult(true, List.of(item)));

        var result = backends.memorySearch().search("内容", 5);
        assertThat(result.available()).isTrue();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).kind()).isEqualTo("fact");
        assertThat(result.items().get(0).validFrom()).isEqualTo(LocalDate.of(2026, 8, 1));
    }
}
