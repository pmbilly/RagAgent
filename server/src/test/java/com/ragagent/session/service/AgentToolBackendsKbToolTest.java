package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.config.ConversationProperties;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.retrieval.HybridSearchService;

/**
 * KB 检索族接线钉（2026-09-23 接线批）。
 *
 * <p>此前 {@code SessionAgentQaService.registerTools} 对 knowledge_search /
 * grep_chunks / list_knowledge_chunks / query_knowledge_graph / get_document_info
 * 只落 {@code default → "Unknown tool"}——工具类与检索执行面都已就位但从未构造。
 * 本测试钉住「5 件均可构造且名字一致」，修复回退即红。</p>
 */
class AgentToolBackendsKbToolTest {

    private AgentToolBackends backends;

    @BeforeEach
    void setUp() {
        backends = new AgentToolBackends(
                mock(KnowledgeBaseService.class),
                mock(KnowledgeService.class),
                mock(ChunkRepository.class),
                mock(HybridSearchService.class),
                mock(ConversationProperties.class),
                mock(DataSource.class));
    }

    @Test
    void kbToolFamilyIsConstructible() {
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(
                List.of(SearchTarget.wholeKb("kb1", 10002)));
        for (String name : List.of("knowledge_search", "grep_chunks",
                "list_knowledge_chunks", "query_knowledge_graph", "get_document_info")) {
            var tool = backends.createKbTool(name, targets, null);
            assertThat(tool).as("工具 %s 必须可构造（此前恒走 Unknown tool）", name).isNotNull();
            assertThat(tool.getName()).as("工具名与注册名一致").isEqualTo(name);
        }
    }

    @Test
    void nonKbNamesReturnNull() {
        assertThat(backends.createKbTool("search_memory", null, null)).isNull();
        assertThat(backends.createKbTool("web_search", null, null)).isNull();
        assertThat(backends.createKbTool("not_a_tool", null, null)).isNull();
    }
}
