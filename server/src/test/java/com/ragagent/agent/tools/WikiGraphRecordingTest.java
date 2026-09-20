package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.QueryKnowledgeGraphTool.ExtractConfigView;
import com.ragagent.agent.tools.QueryKnowledgeGraphTool.GraphNodeView;
import com.ragagent.agent.tools.QueryKnowledgeGraphTool.GraphRelationView;
import com.ragagent.agent.tools.QueryKnowledgeGraphTool.GraphSearch;
import com.ragagent.agent.tools.QueryKnowledgeGraphTool.KnowledgeBaseView;
import com.ragagent.agent.tools.QueryKnowledgeGraphTool.SearchResultView;
import com.ragagent.agent.tools.SearchAuth.KnowledgeTagsFetcher;
import com.ragagent.agent.tools.SearchAuth.TagView;

/**
 * 波 4.5b 回放：query_knowledge_graph（对照 Go 实录，探针 {@code zz_rec45b_graph_test.go}）。
 * 纯 fake GraphSearch（seam 已隔离）；多成功 KB 的输出文本因 Go map 迭代序随机，
 * 探针/回放均只用单成功 KB 场景做文本比对（已知差异，见报告）。
 */
class WikiGraphRecordingTest {

    // ==================== Fakes ====================

    static final class FakeGraphSearch implements GraphSearch {
        final Map<String, KnowledgeBaseView> kbs = new LinkedHashMap<>();
        final Map<String, RuntimeException> kbErr = new LinkedHashMap<>();
        final Map<String, List<SearchResultView>> results = new LinkedHashMap<>();
        final Map<String, RuntimeException> searchErr = new LinkedHashMap<>();
        final Map<String, String> gotQuery = new LinkedHashMap<>();

        @Override
        public KnowledgeBaseView getKnowledgeBaseByIdOnly(String kbId) {
            RuntimeException err = kbErr.get(kbId);
            if (err != null) {
                throw err;
            }
            return kbs.get(kbId);
        }

        @Override
        public List<SearchResultView> hybridSearch(String kbId, String queryText, int matchCount) {
            gotQuery.put(kbId, queryText + "|" + matchCount);
            RuntimeException err = searchErr.get(kbId);
            if (err != null) {
                throw err;
            }
            return results.get(kbId);
        }
    }

    static FakeGraphSearch graphKBs() {
        ExtractConfigView cfgFull = new ExtractConfigView(
                List.of(new GraphNodeView("Technology"), new GraphNodeView("Tool"),
                        new GraphNodeView("Concept"), new GraphNodeView("Tool")),
                List.of(new GraphRelationView("depends_on"), new GraphRelationView("uses"),
                        new GraphRelationView("contains"), new GraphRelationView("uses")));
        ExtractConfigView cfgNodesOnly = new ExtractConfigView(
                List.of(new GraphNodeView("Zeta"), new GraphNodeView("Alpha")), List.of());
        FakeGraphSearch s = new FakeGraphSearch();
        s.kbs.put("gkb1", new KnowledgeBaseView("gkb1", cfgFull));
        s.kbs.put("gkb2", new KnowledgeBaseView("gkb2", null));
        s.kbs.put("gkb4", new KnowledgeBaseView("gkb4", cfgNodesOnly));
        s.kbs.put("gkb5", new KnowledgeBaseView("gkb5", cfgFull));
        s.kbs.put("gkb6", new KnowledgeBaseView("gkb6", new ExtractConfigView(List.of(), List.of())));
        s.kbErr.put("gkb3", new RuntimeException("boom"));
        s.searchErr.put("gkb5", new RuntimeException("search boom"));
        s.results.put("gkb1", new ArrayList<>(List.of(
                new SearchResultView("gc2", 0.62, "Kubernetes orchestrates containers", "gd2",
                        "gkb1", "K8s 文档", 3, "text", 1),
                new SearchResultView("gc1", 0.91, "Docker builds images", "gd1",
                        "gkb1", "容器指南", 0, "text", 6),
                new SearchResultView("gc3", 0.31, "Containers share the kernel", "gd1",
                        "gkb1", "容器指南", 1, "text", 0))));
        s.results.put("gkb4", new ArrayList<>());
        return s;
    }

    static KnowledgeTagsFetcher tagsOf(Map<String, List<TagView>> tags) {
        return knowledgeIds -> {
            Map<String, List<TagView>> out = new LinkedHashMap<>();
            for (String id : knowledgeIds) {
                out.put(id, tags.getOrDefault(id, List.of()));
            }
            return out;
        };
    }

    // ==================== 回放框架 ====================

    private static JsonNode rec(String name) {
        try {
            String json = (String) GoRecording45B.class
                    .getField("R_" + name.toUpperCase(java.util.Locale.ROOT)).get(null);
            return GoRecording45B.rec(json);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolRequest req(String argsJson) {
        try {
            return ToolRequest.of(RecordingSupport.PLAIN.readTree(argsJson));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertToolResult(String label, ToolResult result, JsonNode r) {
        assertThat(result.isSuccess()).as("%s success", label).isEqualTo(r.get("success").asBoolean());
        assertThat(result.getOutput()).as("%s output", label).isEqualTo(r.get("output").asText());
        String wantError = r.hasNonNull("error") ? r.get("error").asText() : "";
        if (!wantError.isEmpty()) {
            assertThat(result.getError()).as("%s error", label).isEqualTo(wantError);
        }
        JsonNode wantData = r.get("data");
        if (wantData == null || wantData.isNull()) {
            assertThat(result.getData()).as("%s data", label).isNull();
        } else {
            assertThat(RecordingSupport.canonicalJson(RecordingSupport.PLAIN.valueToTree(result.getData())))
                    .as("%s data", label)
                    .isEqualTo(RecordingSupport.canonicalJson(wantData));
        }
    }

    // ==================== 用例 ====================

    @Test
    void graphHit() {
        QueryKnowledgeGraphTool tool = new QueryKnowledgeGraphTool(graphKBs(), null, null);
        assertToolResult("graph_hit", tool.execute(req(
                "{\"knowledge_base_ids\":[\"gkb1\"],\"query\":\"Docker 和 Kubernetes 的关系\"}")),
                rec("query_knowledge_graph_graph_hit"));
    }

    @Test
    void graphNoConfig() {
        QueryKnowledgeGraphTool tool = new QueryKnowledgeGraphTool(graphKBs(), null, null);
        assertToolResult("graph_no_config", tool.execute(req(
                "{\"knowledge_base_ids\":[\"gkb2\"],\"query\":\"anything\"}")),
                rec("query_knowledge_graph_graph_no_config"));
    }

    @Test
    void graphEmptyConfig() {
        QueryKnowledgeGraphTool tool = new QueryKnowledgeGraphTool(graphKBs(), null, null);
        assertToolResult("graph_empty_config", tool.execute(req(
                "{\"knowledge_base_ids\":[\"gkb6\"],\"query\":\"anything\"}")),
                rec("query_knowledge_graph_graph_empty_config"));
    }

    @Test
    void graphEmpty() {
        QueryKnowledgeGraphTool tool = new QueryKnowledgeGraphTool(graphKBs(), null, null);
        assertToolResult("graph_empty", tool.execute(req(
                "{\"knowledge_base_ids\":[\"gkb4\"],\"query\":\"nothing matches\"}")),
                rec("query_knowledge_graph_graph_empty"));
    }

    @Test
    void graphKbMissing() {
        QueryKnowledgeGraphTool tool = new QueryKnowledgeGraphTool(graphKBs(), null, null);
        assertToolResult("graph_kb_missing", tool.execute(req(
                "{\"knowledge_base_ids\":[\"gkb3\"],\"query\":\"anything\"}")),
                rec("query_knowledge_graph_graph_kb_missing"));
    }

    @Test
    void graphSearchError() {
        QueryKnowledgeGraphTool tool = new QueryKnowledgeGraphTool(graphKBs(), null, null);
        assertToolResult("graph_search_error", tool.execute(req(
                "{\"knowledge_base_ids\":[\"gkb5\"],\"query\":\"anything\"}")),
                rec("query_knowledge_graph_graph_search_error"));
    }

    @Test
    void graphMixed() {
        QueryKnowledgeGraphTool tool = new QueryKnowledgeGraphTool(graphKBs(), null, null);
        assertToolResult("graph_mixed", tool.execute(req(
                "{\"knowledge_base_ids\":[\"gkb1\",\"gkb2\"],\"query\":\"Docker\"}")),
                rec("query_knowledge_graph_graph_mixed"));
    }

    @Test
    void validation() {
        QueryKnowledgeGraphTool tool = new QueryKnowledgeGraphTool(graphKBs(), null, null);
        assertToolResult("validation_no_kb", tool.execute(req(
                "{\"knowledge_base_ids\":[],\"query\":\"x\"}")),
                rec("query_knowledge_graph_validation_no_kb"));
        assertToolResult("validation_too_many", tool.execute(req(
                "{\"knowledge_base_ids\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\",\"k\"],\"query\":\"x\"}")),
                rec("query_knowledge_graph_validation_too_many"));
        assertToolResult("validation_empty_query", tool.execute(req(
                "{\"knowledge_base_ids\":[\"gkb1\"],\"query\":\"\"}")),
                rec("query_knowledge_graph_validation_empty_query"));
    }

    @Test
    void scopeKbOutside() {
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                SearchTarget.wholeKb("gkb1", 10002)));
        QueryKnowledgeGraphTool tool = new QueryKnowledgeGraphTool(graphKBs(), targets, null);
        assertToolResult("scope_kb_outside", tool.execute(req(
                "{\"knowledge_base_ids\":[\"gkb2\"],\"query\":\"x\"}")),
                rec("query_knowledge_graph_scope_kb_outside"));
    }

    @Test
    void scopeWholeKb() {
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                SearchTarget.wholeKb("gkb1", 10002)));
        QueryKnowledgeGraphTool tool = new QueryKnowledgeGraphTool(graphKBs(), targets,
                tagsOf(Map.of()));
        assertToolResult("scope_whole_kb", tool.execute(req(
                "{\"knowledge_base_ids\":[\"gkb1\"],\"query\":\"Docker\"}")),
                rec("query_knowledge_graph_scope_whole_kb"));
    }

    @Test
    void scopeWhitelist() {
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                new SearchTarget(SearchTarget.TYPE_KNOWLEDGE, "gkb1", 10002,
                        List.of("gd1"), null, null, false)));
        QueryKnowledgeGraphTool tool = new QueryKnowledgeGraphTool(graphKBs(), targets, tagsOf(Map.of()));
        assertToolResult("scope_whitelist", tool.execute(req(
                "{\"knowledge_base_ids\":[\"gkb1\"],\"query\":\"Docker\"}")),
                rec("query_knowledge_graph_scope_whitelist"));
    }

    @Test
    void scopeTag() {
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                new SearchTarget(SearchTarget.TYPE_KNOWLEDGE_BASE, "gkb1", 10002, null,
                        List.of("gt1"), null, false)));
        Map<String, List<TagView>> tags = Map.of("gd2", List.of(new TagView("gt1")));
        QueryKnowledgeGraphTool tool = new QueryKnowledgeGraphTool(graphKBs(), targets, tagsOf(tags));
        assertToolResult("scope_tag", tool.execute(req(
                "{\"knowledge_base_ids\":[\"gkb1\"],\"query\":\"Docker\"}")),
                rec("query_knowledge_graph_scope_tag"));
    }

    @Test
    void scopeKbMismatch() {
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                SearchTarget.wholeKb("gkb1", 10002)));
        FakeGraphSearch kb = graphKBs();
        kb.results.put("gkb1", new ArrayList<>(List.of(
                new SearchResultView("gc9", 0.9, "wrong kb", "gd9", "gkbX", "别的库", 0, "text", 6))));
        QueryKnowledgeGraphTool tool = new QueryKnowledgeGraphTool(kb, targets, tagsOf(Map.of()));
        assertToolResult("scope_kb_mismatch", tool.execute(req(
                "{\"knowledge_base_ids\":[\"gkb1\"],\"query\":\"Docker\"}")),
                rec("query_knowledge_graph_scope_kb_mismatch"));
    }
}
