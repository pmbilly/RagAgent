package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.KnowledgeSearchTool.ChunkInfoBackend;
import com.ragagent.agent.tools.KnowledgeSearchTool.HybridParams;
import com.ragagent.agent.tools.KnowledgeSearchTool.KBView;
import com.ragagent.agent.tools.KnowledgeSearchTool.KnowledgeSearchBackend;
import com.ragagent.agent.tools.KnowledgeSearchTool.RankResult;
import com.ragagent.agent.tools.KnowledgeSearchTool.RerankerModel;
import com.ragagent.agent.tools.KnowledgeSearchTool.SearchResultView;
import com.ragagent.knowledge.domain.Chunk;

/**
 * 波 4.5b 回放：knowledge_search（对照 Go 实录，探针 {@code zz_rec45b_ksearch_test.go}）。
 * 纯 fake backend（seam 已隔离）。语料设计约束（Go 侧本就不定的序）：
 * 单文档、分数各异、英文内容——final sort 在 (score, knowledgeID) 唯一时两边一致。
 */
class KnowledgeSearchRecordingTest {

    // ==================== Fakes ====================

    static final class FakeBackend implements KnowledgeSearchBackend {
        final Map<String, String> kbTypes = new LinkedHashMap<>();
        final Map<String, RuntimeException> kbErr = new LinkedHashMap<>();
        List<KBView> kbList = new ArrayList<>();
        final Map<String, List<SearchResultView>> hybrid = new LinkedHashMap<>();
        final Map<String, RuntimeException> hybridErr = new LinkedHashMap<>();
        Map<String, String> modelKeys = new LinkedHashMap<>();
        Map<String, float[]> embeddings = new LinkedHashMap<>();
        java.util.function.BiFunction<String, HybridParams, List<SearchResultView>> hybridFn;

        @Override
        public KBView getKnowledgeBaseById(String kbId) {
            RuntimeException err = kbErr.get(kbId);
            if (err != null) {
                throw err;
            }
            String type = kbTypes.get(kbId);
            if (type == null) {
                throw new RuntimeException("not found");
            }
            return new KBView(kbId, type, false, false);
        }

        @Override
        public List<KBView> getKnowledgeBasesByIdsOnly(List<String> ids) {
            return kbList;
        }

        @Override
        public Map<String, String> resolveEmbeddingModelKeys(List<KBView> kbs) {
            return modelKeys;
        }

        @Override
        public float[] getQueryEmbedding(String kbId, String queryText) {
            float[] emb = embeddings.get(kbId + "|" + queryText);
            return emb != null ? emb : new float[] {0.1f, 0.2f, 0.3f};
        }

        @Override
        public List<SearchResultView> hybridSearch(String kbId, HybridParams params) {
            if (hybridFn != null) {
                return hybridFn.apply(params.knowledgeBaseIDs() == null ? "" : String.join(",", params.knowledgeBaseIDs()), params);
            }
            // 对照 Go HybridSearch(ctx, kbID, params)：kbID 由调用点显式传入
            // （whole-KB 分支 = fullKBIDs[0]；定向分支 = target.KnowledgeBaseID）。
            RuntimeException err = hybridErr.get(kbId);
            if (err != null) {
                throw err;
            }
            return hybrid.get(kbId + "|" + params.queryText());
        }
    }

    static final class FakeChunks implements ChunkInfoBackend {
        final Map<String, Chunk> byID = new LinkedHashMap<>();
        final Map<String, Long> totals = new LinkedHashMap<>();

        @Override
        public Chunk faqChunkById(String chunkId) {
            return byID.get(chunkId);
        }

        @Override
        public long totalChunks(long tenantId, String knowledgeId) {
            return totals.getOrDefault(knowledgeId, 0L);
        }
    }

    static final class FakeReranker implements RerankerModel {
        final List<RankResult> results;
        final RuntimeException err;

        FakeReranker(List<RankResult> results, RuntimeException err) {
            this.results = results;
            this.err = err;
        }

        @Override
        public List<RankResult> rerank(String query, List<String> passages) {
            if (err != null) {
                throw err;
            }
            return results;
        }
    }

    static SearchResultView result(String id, String knowledgeID, String kb, String title, int idx,
            String content, double score, int matchType) {
        SearchResultView r = new SearchResultView();
        r.id = id;
        r.content = content;
        r.knowledgeId = knowledgeID;
        r.knowledgeBaseId = kb;
        r.knowledgeTitle = title;
        r.chunkIndex = idx;
        r.chunkType = "text";
        r.startAt = idx * 100;
        r.endAt = idx * 100 + 50;
        r.score = score;
        r.matchType = matchType;
        return r;
    }

    static FakeBackend kbSvc() {
        FakeBackend s = new FakeBackend();
        s.kbTypes.put("skb1", "document");
        s.kbTypes.put("skb2", "document");
        s.kbTypes.put("skb3", "faq");
        s.kbList = new ArrayList<>(List.of(
                new KBView("skb1", "document", true, false),
                new KBView("skb2", "document", false, true),
                new KBView("skb3", "faq", true, false),
                new KBView("skbW", "document", false, false)));
        s.modelKeys = new LinkedHashMap<>(Map.of("skb1", "model-a", "skb2", "model-a", "skb3", "model-b"));
        s.embeddings.put("skb1|What is RAG retrieval?", new float[] {0.9f, 0.1f});
        s.hybrid.put("skb1|What is RAG retrieval?", new ArrayList<>(List.of(
                result("sc1", "skd1", "skb1", "RAG Guide", 0,
                        "A RAG system retrieves relevant context before generation.", 0.033, 0),
                result("sc2", "skd1", "skb1", "RAG Guide", 1,
                        "Retrieval augmented generation combines search with LLMs.", 0.028, 1),
                result("sc3", "skd1", "skb1", "RAG Guide", 2,
                        "Chunking strategy affects retrieval quality deeply.", 0.020, 6),
                result("sc4", "skd1", "skb1", "RAG Guide", 3,
                        "Embedding models map text into vector space.", 0.011, 0))));
        return s;
    }

    static FakeChunks chunkSvc() {
        FakeChunks c = new FakeChunks();
        c.totals.put("skd1", 4L);
        c.totals.put("skd2", 3L);
        Chunk faq = new Chunk();
        faq.setId("sf1");
        faq.setKnowledgeId("skd2");
        faq.setChunkType("faq");
        faq.setMetadata(readNode("{\"standard_question\":\"How do I reset the pipeline?\","
                + "\"similar_questions\":[\"pipeline reset steps\"],"
                + "\"answers\":[\"Press the red button.\",\"Wait ten seconds.\"]}"));
        c.byID.put("sf1", faq);
        return c;
    }

    static JsonNode readNode(String json) {
        try {
            return RecordingSupport.PLAIN.readTree(json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static SearchTarget.SearchTargets skb1Targets() {
        return new SearchTarget.SearchTargets(List.of(SearchTarget.wholeKb("skb1", 10002)));
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
    void basic() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(kbSvc(), chunkSvc(), null, null,
                skb1Targets(), null);
        assertToolResult("basic", tool.execute(req("{\"queries\":[\"What is RAG retrieval?\"]}")),
                rec("knowledge_search_basic"));
    }

    @Test
    void multiQuery() {
        // 跨 query 同 id chunk 在 Go 侧因 goroutine 追加序不定（已知差异），语料避开。
        FakeBackend svc = kbSvc();
        svc.hybrid.put("skb1|RAG benefits overview", new ArrayList<>(List.of(
                result("sc5", "skd1", "skb1", "RAG Guide", 4,
                        "Benefits include freshness and citation grounding.", 0.027, 1),
                result("sc6", "skd1", "skb1", "RAG Guide", 5,
                        "Hybrid indexes blend sparse and dense signals.", 0.021, 0))));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(svc, chunkSvc(), null, null,
                skb1Targets(), null);
        assertToolResult("multi_query", tool.execute(
                req("{\"queries\":[\"What is RAG retrieval?\",\"RAG benefits overview\"]}")),
                rec("knowledge_search_multi_query"));
    }

    @Test
    void alreadySeen() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(kbSvc(), chunkSvc(), null, null,
                skb1Targets(), null);
        assertToolResult("already_seen_first", tool.execute(req("{\"queries\":[\"What is RAG retrieval?\"]}")),
                rec("knowledge_search_already_seen_first"));
        assertToolResult("already_seen_second", tool.execute(req("{\"queries\":[\"What is RAG retrieval?\"]}")),
                rec("knowledge_search_already_seen_second"));
    }

    @Test
    void noResults() {
        FakeBackend svc = kbSvc();
        svc.hybrid.put("skb1|What is RAG retrieval?", null);
        KnowledgeSearchTool tool = new KnowledgeSearchTool(svc, chunkSvc(), null, null,
                skb1Targets(), null);
        assertToolResult("no_results", tool.execute(req("{\"queries\":[\"What is RAG retrieval?\"]}")),
                rec("knowledge_search_no_results"));
    }

    @Test
    void kbFilter() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(kbSvc(), chunkSvc(), null, null,
                skb1Targets(), null);
        assertToolResult("kb_filter", tool.execute(
                req("{\"queries\":[\"What is RAG retrieval?\"],\"knowledge_base_ids\":[\"skb1\"]}")),
                rec("knowledge_search_kb_filter"));
    }

    @Test
    void kbFilterOutside() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(kbSvc(), chunkSvc(), null, null,
                skb1Targets(), null);
        assertToolResult("kb_filter_outside", tool.execute(
                req("{\"queries\":[\"q\"],\"knowledge_base_ids\":[\"nope\"]}")),
                rec("knowledge_search_kb_filter_outside"));
    }

    @Test
    void validation() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(kbSvc(), chunkSvc(), null, null,
                skb1Targets(), null);
        assertToolResult("no_queries", tool.execute(req("{\"queries\":[]}")),
                rec("knowledge_search_no_queries"));
        KnowledgeSearchTool noTargets = new KnowledgeSearchTool(kbSvc(), chunkSvc(), null, null,
                null, null);
        assertToolResult("no_targets", noTargets.execute(req("{\"queries\":[\"q\"]}")),
                rec("knowledge_search_no_targets"));
    }

    @Test
    void mmrTrim() {
        FakeBackend svc = kbSvc();
        String[] words = {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf"};
        List<SearchResultView> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seven.add(result(String.format("mc%d", i + 1), "skd1", "skb1", "RAG Guide", i,
                    "topic " + words[i] + " discussion notes", 0.031 - i * 0.003, 0));
        }
        svc.hybrid.put("skb1|What is RAG retrieval?", seven);
        KnowledgeSearchTool tool = new KnowledgeSearchTool(svc, chunkSvc(), null, null,
                skb1Targets(), null);
        assertToolResult("mmr_trim", tool.execute(req("{\"queries\":[\"What is RAG retrieval?\"]}")),
                rec("knowledge_search_mmr_trim"));
    }

    @Test
    void rerankPass() {
        FakeBackend svc = kbSvc();
        FakeReranker rr = new FakeReranker(List.of(
                new RankResult(0, 0.91),
                new RankResult(1, 0.22),
                new RankResult(2, 0.87),
                new RankResult(3, 0.10)), null);
        KnowledgeSearchTool tool = new KnowledgeSearchTool(svc, chunkSvc(), null, rr,
                skb1Targets(), null);
        assertToolResult("rerank_pass", tool.execute(req("{\"queries\":[\"What is RAG retrieval?\"]}")),
                rec("knowledge_search_rerank_pass"));
    }

    @Test
    void rerankAllBelow() {
        FakeBackend svc = kbSvc();
        svc.hybrid.put("skb1|What is RAG retrieval?", new ArrayList<>(List.of(
                result("lc1", "skd1", "skb1", "RAG Guide", 0, "low rank one", 0.03, 0),
                result("lc2", "skd1", "skb1", "RAG Guide", 1, "low rank two", 0.02, 0))));
        FakeReranker rr = new FakeReranker(List.of(
                new RankResult(0, 0.05),
                new RankResult(1, 0.09)), null);
        KnowledgeSearchTool tool = new KnowledgeSearchTool(svc, chunkSvc(), null, rr,
                skb1Targets(), null);
        assertToolResult("rerank_all_below", tool.execute(req("{\"queries\":[\"What is RAG retrieval?\"]}")),
                rec("knowledge_search_rerank_all_below"));
    }

    @Test
    void rerankPreserveTop() {
        FakeBackend svc = kbSvc();
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                new SearchTarget(SearchTarget.TYPE_KNOWLEDGE_BASE, "skb1", 10002, null, null, null, true)));
        // 已知差异（Go 非确定性）：Go dedup 第二轮按 map 迭代序重建 slice
        // （knowledge_search.go deduplicateResults 的 for range seenByID），rerank 输入
        // 顺序每次运行随机；语料锁住的采样里 originals[2]=sc2。Java dedup 用
        // LinkedHashMap 保插入序（确定性），故 stub 的 RankResult.index 改用 1
        // 指向同一 chunk sc2——args→output 契约不变，仍端到端逐字回放。
        FakeReranker rr = new FakeReranker(List.of(new RankResult(1, 0.05)), null);
        KnowledgeSearchTool tool = new KnowledgeSearchTool(svc, chunkSvc(), null, rr, targets, null);
        assertToolResult("rerank_preserve_top", tool.execute(req("{\"queries\":[\"What is RAG retrieval?\"]}")),
                rec("knowledge_search_rerank_preserve_top"));
    }

    @Test
    void faqKb() {
        FakeBackend svc = kbSvc();
        SearchResultView faq = new SearchResultView();
        faq.id = "sf1";
        faq.content = "Press the red button.";
        faq.knowledgeId = "skd2";
        faq.knowledgeBaseId = "skb3";
        faq.knowledgeTitle = "运维 FAQ";
        faq.chunkIndex = 0;
        faq.chunkType = "faq";
        faq.startAt = 0;
        faq.endAt = 40;
        faq.score = 0.029;
        faq.matchType = 0;
        svc.hybrid.put("skb3|pipeline reset", new ArrayList<>(List.of(faq)));
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                SearchTarget.wholeKb("skb3", 10002)));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(svc, chunkSvc(), null, null, targets, null);
        assertToolResult("faq_kb", tool.execute(req("{\"queries\":[\"pipeline reset\"]}")),
                rec("knowledge_search_faq_kb"));
    }

    @Test
    void imageInfo() {
        FakeBackend svc = kbSvc();
        SearchResultView r = result("ic1", "skd1", "skb1", "RAG Guide", 0,
                "Architecture with diagrams.", 0.033, 0);
        r.imageInfo = "[{\"url\":\"http://x/a.png\",\"caption\":\"  架构图  \",\"ocr_text\":\"系统架构\"}]";
        svc.hybrid.put("skb1|What is RAG retrieval?", new ArrayList<>(List.of(r)));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(svc, chunkSvc(), null, null,
                skb1Targets(), null);
        assertToolResult("image_info", tool.execute(req("{\"queries\":[\"What is RAG retrieval?\"]}")),
                rec("knowledge_search_image_info"));
    }

    @Test
    void knowledgeTarget() {
        FakeBackend svc = kbSvc();
        svc.hybridFn = (kbID, params) -> new ArrayList<>(List.of(
                result("kc1", "skd1", "skb1", "RAG Guide", 0, "Targeted doc result.", 0.029, 0)));
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                new SearchTarget(SearchTarget.TYPE_KNOWLEDGE, "skb1", 10002,
                        List.of("skd1"), null, null, false)));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(svc, chunkSvc(), null, null, targets, null);
        assertToolResult("knowledge_target", tool.execute(req("{\"queries\":[\"What is RAG retrieval?\"]}")),
                rec("knowledge_search_knowledge_target"));
    }

    @Test
    void nonSearchableKb() {
        FakeBackend svc = kbSvc();
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                SearchTarget.wholeKb("skbW", 10002)));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(svc, chunkSvc(), null, null, targets, null);
        assertToolResult("non_searchable_kb", tool.execute(req("{\"queries\":[\"wiki only\"]}")),
                rec("knowledge_search_non_searchable_kb"));
    }
}
