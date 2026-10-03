package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.ragagent.TestSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 工具接线批的适配器冒烟（H2 真实库）：grep_chunks 的 scope/regex/计数回填、
 * 分页 total、标签聚合——这些是新写的 SQL/映射面，此前零覆盖。
 *
 * <p>SQL 方言：PG {@code ~*} / H2 {@code REGEXP_LIKE(...,'i')}（见
 * {@code AgentToolBackends.regexDialect}）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentToolBackendsDbTest {

    private static final long TENANT = 10077L;

    @Autowired
    private AgentToolBackends backends;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.update("INSERT INTO tenants (id, name, description, business, status) "
                + "VALUES (?, 'tools-tenant', '', '', 'active')", TENANT);
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, description, "
                + "creator_id, chunking_config, embedding_model_id, summary_model_id, "
                + "created_at, updated_at) VALUES "
                + "('atb-kb', 'atb kb', ?, 'document', '', 'u1', '{}', '', '', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')", TENANT);
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "description, source, parse_status, enable_status) VALUES "
                + "('atb-k1', ?, 'atb-kb', 'document', '图片素材', '', 'file', 'completed', 'enabled'), "
                + "('atb-k2', ?, 'atb-kb', 'document', '其他文档', '', 'file', 'completed', 'enabled')",
                TENANT, TENANT);
        insertChunk("atb-c1", "atb-k1", "这里介绍图片素材的处理", true, 0);
        insertChunk("atb-c2", "atb-k1", "另一个图片相关段落（禁用）", false, 1);
        insertChunk("atb-c3", "atb-k2", "图片也出现在这里", true, 0);
        jdbc.update("INSERT INTO knowledge_tag_relations (knowledge_id, tag_id) VALUES "
                + "('atb-k1', 'tag-a'), ('atb-k1', 'tag-b')");
    }

    private void insertChunk(String id, String knowledgeId, String content, boolean enabled, int index) {
        jdbc.update("INSERT INTO chunks (id, seq_id, tenant_id, knowledge_id, knowledge_base_id, "
                + "content, chunk_type, is_enabled, flags, status, chunk_index, start_at, end_at, "
                + "metadata, created_at, updated_at) VALUES "
                + "(?, ?, ?, ?, 'atb-kb', ?, 'text', ?, 1, 2, ?, 0, 0, '{}', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')",
                id, System.nanoTime(), TENANT, knowledgeId, content, enabled, index);
    }

    /** 定向 scope（knowledge_id IN）：只回该 knowledge 的 enabled 命中 + 计数回填。 */
    @Test
    void grepSearchScopesByKnowledgeAndBackfillsCounts() {
        var rows = backends.grepChunkSearch().search(
                List.of("图片"), List.of(), List.of("atb-k1"), List.of(), Map.of());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).id).isEqualTo("atb-c1");
        assertThat(rows.get(0).knowledgeId).isEqualTo("atb-k1");
        assertThat(rows.get(0).knowledgeTitle).isEqualTo("图片素材");
        // 计数回填是 enabled 口径（不含禁用的 atb-c2）
        assertThat(rows.get(0).totalChunkCount).isEqualTo(1);
    }

    /** whole-KB scope（kb + tenant 对）：跨 knowledge 命中，各自计数。 */
    @Test
    void grepSearchScopesByKbTenantPair() {
        var rows = backends.grepChunkSearch().search(
                List.of("图片"), List.of("atb-kb"), List.of(), List.of(),
                Map.of("atb-kb", TENANT));
        assertThat(rows).extracting(r -> r.id).containsExactlyInAnyOrder("atb-c1", "atb-c3");
        assertThat(rows).allSatisfy(r -> assertThat(r.totalChunkCount).isEqualTo(1));
    }

    /** 无有效 scope（kbTenantMap 缺租户对）→ 空表（两处入口都早退）。 */
    @Test
    void grepSearchWithoutValidScopeReturnsEmpty() {
        assertThat(backends.grepChunkSearch().search(
                List.of("图片"), List.of("atb-kb"), List.of(), List.of(), Map.of())).isEmpty();
        assertThat(backends.grepChunkSearch().search(
                List.of("图片"), List.of(), List.of(), List.of(), Map.of())).isEmpty();
    }

    /** 分页（text+faq + enabled）+ total。 */
    @Test
    void pagedChunksAndTotals() {
        var page = backends.pagedChunks().listPaged(TENANT, "atb-k1", 1, 10);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.chunks()).extracting(c -> c.getId()).containsExactly("atb-c1");
        assertThat(backends.chunkInfoBackend().totalChunks(TENANT, "atb-k1")).isEqualTo(1);
        assertThat(backends.chunkInfoBackend().faqChunkById("atb-c1")).isNotNull();
    }

    /** database_query 的行扫描：列名有序 + 值类型约定（整型→Long、布尔→Boolean）。 */
    @Test
    void sqlQueryExecutorScansColumnsAndRows() {
        var result = backends.sqlQueryExecutor().query(
                "SELECT id, chunk_index, is_enabled FROM chunks WHERE id = 'atb-c1'");
        // H2 未加引号的标识符返回大写列标签，PG（生产）返回小写——按大小写不敏感断言
        assertThat(result.columns()).map(String::toLowerCase)
                .containsExactly("id", "chunk_index", "is_enabled");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).get(0)).isEqualTo("atb-c1");
        assertThat(result.rows().get(0).get(1)).isInstanceOf(Long.class);
        assertThat(result.rows().get(0).get(2)).isInstanceOf(Boolean.class);
    }

    /** 标签聚合（knowledge_tag_relations → Map<knowledgeID, List<TagView>>）。 */
    @Test
    void fetchTagsAggregates() {
        var tags = backends.knowledgeInfoReader().fetchTags(List.of("atb-k1", "atb-k2"));
        assertThat(tags.get("atb-k1")).extracting(t -> t.id())
                .containsExactlyInAnyOrder("tag-a", "tag-b");
        assertThat(tags).doesNotContainKey("atb-k2");
    }
}
