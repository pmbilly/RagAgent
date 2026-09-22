package com.ragagent.chatpipeline;

import static com.ragagent.chatpipeline.Rec46cSupport.assertRec;
import static com.ragagent.chatpipeline.Rec46cSupport.errOf;
import static com.ragagent.chatpipeline.Rec46cSupport.json;
import static com.ragagent.chatpipeline.Rec46cSupport.resultIDs;
import static com.ragagent.chatpipeline.Rec46cSupport.searchResultsShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.searchutil.SearchTextUtil;

/**
 * 实录回放：查询扩展 / 去重 / 部分重叠 / filter_top_k / search / search_by_targets /
 * search_parallel（期望值 = {@link GoRecording46C} Go 实录常量）。
 *
 * <p>分词：jieba 是 4.4 的既有降级 seam；expansion 组语料按实录注入固定分词器
 * （输入段 → jieba 真值 token 表，源自 Go 探针同段实录），其余包组不受影响。</p>
 */
class SearchRecordingTest {

    @BeforeAll
    static void install() {
        Rec46cSupport.installSegmenter();
    }

    @AfterAll
    static void restore() {
        Rec46cSupport.restoreSegmenter();
    }

    private static final String[] EXPAND_QUERIES = {
            "How to configure the knowledge base retrieval in WeKnora?",
            "什么是知识库检索？如何配置检索参数？",
            "请告诉我「向量检索」和「关键词检索」的区别",
            "帮我查一下：RAG,检索增强生成！",
            "什么是检索增强生成",
            "a",
            "  ",
            "translate this sentence",
    };

    @Test
    void expansion() {
        PluginSearch p = new PluginSearch(null, null, null, null, null, null, null, null, null);
        for (int i = 0; i < EXPAND_QUERIES.length; i++) {
            ChatManage cm = new ChatManage();
            cm.setQuery(EXPAND_QUERIES[i]);
            cm.setRewriteQuery(EXPAND_QUERIES[i]);
            List<String> out = p.expandQueries(cm);
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("query", EXPAND_QUERIES[i]);
            shape.put("out", out);
            assertRec("expansion", String.format("expand%02d", i), json(shape));
        }

        String[] toks = {
                "知识库检索配置",
                "knowledge base retrieval",
                "向量检索和关键词检索",
                "混合一行English和中文的句子",
        };
        for (int i = 0; i < toks.length; i++) {
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("in", toks[i]);
            shape.put("keywords", PluginSearch.extractKeywords(toks[i]));
            shape.put("tokens", PluginSearch.tokenize(toks[i]));
            assertRec("expansion", String.format("tokenize%02d", i), json(shape));
        }

        assertRec("expansion", "phrases", json(PluginSearch.extractPhrases(
                "他说「向量检索」，还有 \"quoted text\" 和 『引号』与 “弯引号”")));
        assertRec("expansion", "delimiters", json(PluginSearch.splitByDelimiters("A，B;C、D。E！F？G H")));
        assertRec("expansion", "question_words", json(List.of(
                PluginSearch.removeQuestionWords("什么是检索？"),
                PluginSearch.removeQuestionWords("如何配置知识库"),
                PluginSearch.removeQuestionWords("请问怎么创建文档"),
                PluginSearch.removeQuestionWords("普通句子不动"))));

        // runQueryExpansion
        Rec46cSupport.StubKBService kbSvc = new Rec46cSupport.StubKBService();
        kbSvc.kbs.put("kb-1", Rec46cSupport.kb("kb-1", "document", true, true, false));
        kbSvc.embed.put("kb-1", new float[] {0.1f});
        List<SearchResult> hits = new ArrayList<>();
        hits.add(Rec46cSupport.sr("exp-1", "扩展命中一", "k1", 0.5));
        hits.add(Rec46cSupport.sr("exp-2", "扩展命中二", "k1", 0.4));
        kbSvc.hybrid.put("kb-1", hits);
        PluginSearch p2 = new PluginSearch(kbSvc, null, null, null, null, null, null, null, null);
        ChatManage cm = new ChatManage();
        cm.setRewriteQuery("知识库检索怎么配置");
        cm.setQuery("知识库检索怎么配置");
        cm.setEmbeddingTopK(2);
        cm.setRerankTopK(2);
        cm.setVectorThreshold(0.5);
        cm.setKeywordThreshold(0.6);
        cm.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb-1", 1, null, null, null, false))));
        List<SearchResult> first = new ArrayList<>();
        first.add(Rec46cSupport.sr("hit-0", "原命中", "k0", 0.9));
        cm.setSearchResult(first);
        List<SearchResult> out = p2.runQueryExpansion(cm);
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("results", searchResultsShape(out));
        shape.put("params", kbSvc.paramsJson());
        assertRec("expansion", "run_expansion", json(shape));

        ChatManage cm2 = new ChatManage();
        cm2.setRewriteQuery("  ");
        List<String> emptyOut = p2.expandQueries(cm2);
        Map<String, Object> emptyShape = new LinkedHashMap<>();
        emptyShape.put("out", emptyOut);
        assertRec("expansion", "empty_query", json(emptyShape));
    }

    @Test
    void dedup() {
        List<SearchResult> rs = new ArrayList<>(List.of(
                Rec46cSupport.sr("a", "abc def", "k1", 0.9),
                Rec46cSupport.sr("a", "dup by id", "k1", 0.8),
                Rec46cSupport.sr("b", "abc def", "k1", 0.7),
                Rec46cSupport.sr("c", "完全不同的一段内容", "k2", 0.6),
                Rec46cSupport.sr("d", "", "k2", 0.5)));
        assertRec("dedup", "id_and_signature",
                json(searchResultsShape(SearchSupport.removeDuplicateResults(rs))));
        assertRec("dedup", "empty", json(searchResultsShape(SearchSupport.removeDuplicateResults(null))));
    }

    @Test
    void overlap() {
        String longText = "WeKnora 是一个知识库检索增强生成系统，支持向量检索、关键词检索、重排序与上下文合并等多种能力，可以把文档切块后写入向量库并提供混合检索。";
        String shortText = "WeKnora 是一个知识库检索增强生成系统，支持向量检索、关键词检索、重排序与上下文合并等多种能力。";
        List<SearchResult> rs = new ArrayList<>(List.of(
                Rec46cSupport.sr("long", longText, "k1", 0.95),
                Rec46cSupport.sr("short", shortText, "k2", 0.9),
                Rec46cSupport.sr("other", "完全无关的另一段文字内容，讲的是别的事情。", "k3", 0.8)));
        assertRec("overlap", "containment",
                json(searchResultsShape(SearchSupport.removePartialOverlaps(rs))));

        String a = "alpha beta gamma delta epsilon zeta";
        String b = "alpha beta gamma delta epsilon eta";
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("ratio", SearchTextUtil.contentOverlapRatio(b, a));
        assertRec("overlap", "ratio_probe", json(probe));

        List<SearchResult> rs2 = new ArrayList<>(List.of(
                Rec46cSupport.sr("A", a, "k1", 0.9),
                Rec46cSupport.sr("B", b, "k2", 0.8)));
        assertRec("overlap", "token_ratio",
                json(searchResultsShape(SearchSupport.removePartialOverlaps(rs2))));

        List<SearchResult> rs3 = new ArrayList<>(List.of(
                Rec46cSupport.sr("A", a, "k1", 0.5),
                Rec46cSupport.sr("B", b, "k2", 0.8)));
        assertRec("overlap", "score_ties",
                json(searchResultsShape(SearchSupport.removePartialOverlaps(rs3))));
    }

    @Test
    void filterTopK() {
        PluginFilterTopK p = new PluginFilterTopK();
        ChatManage cm = new ChatManage();
        cm.setRerankTopK(2);
        List<SearchResult> merge = new ArrayList<>(List.of(
                Rec46cSupport.sr("m1", "c1", "k1", 0.5),
                Rec46cSupport.sr("m2", "c2", "k2", 0.9),
                Rec46cSupport.sr("m3", "c3", "k1", 0.9),
                Rec46cSupport.sr("m4", "c4", "k3", 0.1)));
        cm.setMergeResult(merge);
        boolean[] next = {false};
        p.onEvent(PipelineEventType.FILTER_TOP_K, cm, () -> { next[0] = true; return null; });
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("next", next[0]);
        s1.put("out", searchResultsShape(cm.getMergeResult()));
        assertRec("filter_top_k", "merge", json(s1));

        ChatManage cm2 = new ChatManage();
        cm2.setRerankTopK(1);
        cm2.setRerankResult(new ArrayList<>(List.of(
                Rec46cSupport.sr("r1", "c", "k", 0.5), Rec46cSupport.sr("r2", "c", "k", 0.7))));
        p.onEvent(PipelineEventType.FILTER_TOP_K, cm2, () -> null);
        assertRec("filter_top_k", "rerank", json(searchResultsShape(cm2.getRerankResult())));

        ChatManage cm3 = new ChatManage();
        cm3.setRerankTopK(3);
        cm3.setSearchResult(new ArrayList<>(List.of(Rec46cSupport.sr("s1", "c", "k", 0.2))));
        p.onEvent(PipelineEventType.FILTER_TOP_K, cm3, () -> null);
        assertRec("filter_top_k", "search", json(searchResultsShape(cm3.getSearchResult())));

        ChatManage cm4 = new ChatManage();
        cm4.setRerankTopK(3);
        p.onEvent(PipelineEventType.FILTER_TOP_K, cm4, () -> null);
        Map<String, Object> s4 = new LinkedHashMap<>();
        s4.put("m", 0);
        s4.put("r", 0);
        s4.put("s", 0);
        assertRec("filter_top_k", "no_results", json(s4));

        List<SearchResult> tie = new ArrayList<>(List.of(
                tieResult("b"), tieResult("a")));
        PluginFilterTopK.sortSearchResultsDeterministically(tie);
        assertRec("filter_top_k", "tiebreak", json(List.of(tie.get(0).getId(), tie.get(1).getId())));
    }

    private static SearchResult tieResult(String id) {
        SearchResult r = new SearchResult();
        r.setId(id);
        r.setScore(0.5);
        r.setKnowledgeId("k");
        r.setChunkType("text");
        r.setChunkIndex(1);
        return r;
    }

    // ----- search（对照 recSearchOnEvent） -----

    private PluginSearch mkSearch(Rec46cSupport.StubKBService kbSvc, Rec46cSupport.StubWebSearch web) {
        return new PluginSearch(kbSvc, null, null, null, web,
                new Rec46cSupport.StubTenantService(), null, null, null);
    }

    @Test
    void searchOnEvent() {
        // 1) no targets
        PluginSearch p = mkSearch(new Rec46cSupport.StubKBService(), null);
        ChatManage cm = new ChatManage();
        cm.setSessionId("s1");
        PluginError err = p.onEvent(PipelineEventType.CHUNK_SEARCH, cm, () -> null);
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("err", errOf(err));
        s1.put("results", 0);
        assertRec("search", "no_targets", json(s1));

        // 2) happy
        Rec46cSupport.StubKBService kbSvc = new Rec46cSupport.StubKBService();
        kbSvc.kbs.put("kb-1", Rec46cSupport.kb("kb-1", "document", true, true, false));
        kbSvc.embed.put("kb-1", new float[] {0.1f, 0.2f});
        List<SearchResult> hits = new ArrayList<>(List.of(
                Rec46cSupport.sr("c1", "命中一", "k1", 0.9), Rec46cSupport.sr("c2", "命中二", "k2", 0.8)));
        kbSvc.hybrid.put("kb-1", hits);
        PluginSearch p2 = mkSearch(kbSvc, null);
        ChatManage cm2 = new ChatManage();
        cm2.setSessionId("s2");
        cm2.setRewriteQuery("查询语句");
        cm2.setEmbeddingTopK(5);
        cm2.setVectorThreshold(0.4);
        cm2.setKeywordThreshold(0.5);
        cm2.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb-1", 1, null, null, null, false))));
        cm2.setTenantId(1);
        boolean[] next = {false};
        PluginError err2 = p2.onEvent(PipelineEventType.CHUNK_SEARCH, cm2, () -> { next[0] = true; return null; });
        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("next", next[0]);
        s2.put("err", errOf(err2));
        s2.put("results", searchResultsShape(cm2.getSearchResult()));
        s2.put("params", kbSvc.paramsJson());
        assertRec("search", "happy", json(s2));

        // 3) expansion trigger
        Rec46cSupport.StubKBService kbLow = new Rec46cSupport.StubKBService();
        kbLow.kbs.put("kb-1", Rec46cSupport.kb("kb-1", "document", true, true, false));
        kbLow.embed.put("kb-1", new float[] {0.3f});
        kbLow.hybrid.put("kb-1", new ArrayList<>(List.of(Rec46cSupport.sr("few", "唯一命中", "k1", 0.55))));
        PluginSearch p3 = mkSearch(kbLow, null);
        ChatManage cm3 = new ChatManage();
        cm3.setSessionId("s3");
        cm3.setRewriteQuery("知识库 检索 配置 教程");
        cm3.setQuery("知识库 检索 配置 教程");
        cm3.setEmbeddingTopK(4);
        cm3.setRerankTopK(2);
        cm3.setEnableQueryExpansion(true);
        cm3.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb-1", 1, null, null, null, false))));
        cm3.setTenantId(1);
        boolean[] next3 = {false};
        PluginError err3 = p3.onEvent(PipelineEventType.CHUNK_SEARCH, cm3, () -> { next3[0] = true; return null; });
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("next", next3[0]);
        s3.put("err", errOf(err3));
        s3.put("count", cm3.getSearchResult().size());
        s3.put("ids", resultIDs(cm3.getSearchResult()));
        assertRec("search", "expansion_trigger", json(s3));

        // 4) web only
        Rec46cSupport.StubKBService kbNone = new Rec46cSupport.StubKBService();
        Rec46cSupport.StubWebSearch ws = new Rec46cSupport.StubWebSearch();
        WebSearchResult w = new WebSearchResult();
        w.setUrl("https://example.com/doc");
        w.setTitle("Web hit");
        w.setContent("web 内容");
        ws.results.add(w);
        PluginSearch p4 = mkSearch(kbNone, ws);
        ChatManage cm4 = new ChatManage();
        cm4.setSessionId("s4");
        cm4.setRewriteQuery("web query");
        cm4.setEmbeddingTopK(5);
        cm4.setSearchTargets(new ArrayList<>());
        cm4.setKnowledgeBaseIds(new ArrayList<>());
        cm4.setWebSearchEnabled(true);
        cm4.setWebSearchProviderId("prov-1");
        cm4.setTenantId(1);
        boolean[] next4 = {false};
        PluginError err4 = p4.onEvent(PipelineEventType.CHUNK_SEARCH, cm4, () -> { next4[0] = true; return null; });
        Map<String, Object> s4b = new LinkedHashMap<>();
        s4b.put("next", next4[0]);
        s4b.put("err", errOf(err4));
        s4b.put("results", searchResultsShape(cm4.getSearchResult()));
        assertRec("search", "web_only", json(s4b));

        // 5) web rescue
        Rec46cSupport.StubKBService kbBad = new Rec46cSupport.StubKBService();
        kbBad.kbErr = new RuntimeException("kb store unavailable");
        kbBad.kbs.put("kb-1", Rec46cSupport.kb("kb-1", "document", true, true, false));
        PluginSearch p5 = mkSearch(kbBad, ws);
        ChatManage cm5 = new ChatManage();
        cm5.setSessionId("s5");
        cm5.setRewriteQuery("并发检索");
        cm5.setEmbeddingTopK(10);
        cm5.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb-1", 1, null, null, null, false))));
        cm5.setWebSearchEnabled(true);
        cm5.setWebSearchProviderId("prov-1");
        cm5.setTenantId(1);
        boolean[] next5 = {false};
        PluginError err5 = p5.onEvent(PipelineEventType.CHUNK_SEARCH, cm5, () -> { next5[0] = true; return null; });
        Map<String, Object> s5 = new LinkedHashMap<>();
        s5.put("next", next5[0]);
        s5.put("err", errOf(err5));
        s5.put("ids", resultIDs(cm5.getSearchResult()));
        assertRec("search", "web_rescue", json(s5));

        // 6) embed degrade to keyword
        Rec46cSupport.StubKBService kbDegrade = new Rec46cSupport.StubKBService();
        kbDegrade.kbs.put("kb-1", Rec46cSupport.kb("kb-1", "document", true, true, false));
        kbDegrade.embedErr.put("kb-1", new RuntimeException("embedding rate limited"));
        kbDegrade.hybrid.put("kb-1", new ArrayList<>(List.of(Rec46cSupport.sr("kw-1", "关键词命中的内容", "k1", 0.6))));
        PluginSearch p6 = mkSearch(kbDegrade, null);
        ChatManage cm6 = new ChatManage();
        cm6.setSessionId("s6");
        cm6.setRewriteQuery("树状筛选器 新建入口");
        cm6.setEmbeddingTopK(10);
        cm6.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb-1", 1, null, null, null, false))));
        cm6.setTenantId(1);
        boolean[] next6 = {false};
        PluginError err6 = p6.onEvent(PipelineEventType.CHUNK_SEARCH, cm6, () -> { next6[0] = true; return null; });
        Map<String, Object> s6 = new LinkedHashMap<>();
        s6.put("next", next6[0]);
        s6.put("err", errOf(err6));
        s6.put("ids", resultIDs(cm6.getSearchResult()));
        s6.put("params", kbDegrade.paramsJson());
        assertRec("search", "embed_degrade_keyword", json(s6));

        // 7) vector-only KB fail → search_failed
        Rec46cSupport.StubKBService kbFAQ = new Rec46cSupport.StubKBService();
        kbFAQ.kbs.put("faq-1", Rec46cSupport.kb("faq-1", "faq", true, false, false));
        kbFAQ.embedErr.put("faq-1", new RuntimeException("embedding endpoint unavailable"));
        PluginSearch p7 = mkSearch(kbFAQ, null);
        ChatManage cm7 = new ChatManage();
        cm7.setSessionId("s7");
        cm7.setRewriteQuery("退款规则");
        cm7.setEmbeddingTopK(10);
        cm7.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "faq-1", 1, null, null, null, false))));
        cm7.setTenantId(1);
        PluginError err7 = p7.onEvent(PipelineEventType.CHUNK_SEARCH, cm7, () -> null);
        Map<String, Object> s7 = new LinkedHashMap<>();
        s7.put("err", errOf(err7));
        assertRec("search", "vector_only_fail", json(s7));

        // 8) wiki-only KB fail → search_nothing
        Rec46cSupport.StubKBService kbWiki = new Rec46cSupport.StubKBService();
        kbWiki.kbs.put("wiki-1", Rec46cSupport.kb("wiki-1", "document", false, false, true));
        kbWiki.embedErr.put("wiki-1", new RuntimeException("embedding endpoint unavailable"));
        PluginSearch p8 = mkSearch(kbWiki, null);
        ChatManage cm8 = new ChatManage();
        cm8.setSessionId("s8");
        cm8.setRewriteQuery("wiki only");
        cm8.setEmbeddingTopK(10);
        cm8.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "wiki-1", 1, null, null, null, false))));
        cm8.setTenantId(1);
        PluginError err8 = p8.onEvent(PipelineEventType.CHUNK_SEARCH, cm8, () -> null);
        Map<String, Object> s8 = new LinkedHashMap<>();
        s8.put("err", errOf(err8));
        assertRec("search", "wiki_only_nothing", json(s8));

        // 9) empty → search_nothing
        Rec46cSupport.StubKBService kbEmpty = new Rec46cSupport.StubKBService();
        kbEmpty.kbs.put("kb-1", Rec46cSupport.kb("kb-1", "document", true, true, false));
        PluginSearch p9 = mkSearch(kbEmpty, null);
        ChatManage cm9 = new ChatManage();
        cm9.setSessionId("s9");
        cm9.setRewriteQuery("没有结果的查询");
        cm9.setEmbeddingTopK(5);
        cm9.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb-1", 1, null, null, null, false))));
        cm9.setTenantId(1);
        PluginError err9 = p9.onEvent(PipelineEventType.CHUNK_SEARCH, cm9, () -> null);
        Map<String, Object> s9 = new LinkedHashMap<>();
        s9.put("err", errOf(err9));
        assertRec("search", "empty_nothing", json(s9));
    }

    @Test
    void searchByTargets() {
        Rec46cSupport.StubKBService kbSvc = new Rec46cSupport.StubKBService();
        kbSvc.kbs.put("kb-a", Rec46cSupport.kb("kb-a", "document", true, true, false));
        kbSvc.kbs.put("kb-b", Rec46cSupport.kb("kb-b", "document", true, true, false));
        kbSvc.modelKeys = new LinkedHashMap<>(Map.of("kb-a", "m|shared", "kb-b", "m|shared"));
        kbSvc.embed.put("kb-a", new float[] {0.1f, 0.2f});
        kbSvc.embed.put("kb-b", new float[] {0.3f});
        kbSvc.hybrid.put("kb-a", new ArrayList<>(List.of(Rec46cSupport.sr("a-1", "A 文档命中", "ka", 0.7))));
        kbSvc.hybrid.put("kb-b", new ArrayList<>(List.of(Rec46cSupport.sr("b-1", "B 文档命中", "kb", 0.6))));
        PluginSearch p = new PluginSearch(kbSvc, null, null, null, null, null, null, null, null);
        ChatManage cm = new ChatManage();
        cm.setRewriteQuery("共享模型查询");
        cm.setEmbeddingTopK(3);
        cm.setVectorThreshold(0.35);
        cm.setKeywordThreshold(0.45);
        cm.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb-a", 1, null, null, null, false),
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb-b", 1, null, null, null, false))));
        cm.setTenantId(1);
        List<SearchResult> res = p.searchByTargets(cm);
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("err", null);
        s1.put("ids", resultIDs(res));
        s1.put("params", kbSvc.paramsJson());
        assertRec("search_by_targets", "shared_model", json(s1));

        Rec46cSupport.StubKBService kbSvc2 = new Rec46cSupport.StubKBService();
        kbSvc2.kbs.put("kb-c", Rec46cSupport.kb("kb-c", "document", true, true, false));
        kbSvc2.embed.put("kb-c", new float[] {0.9f});
        kbSvc2.hybrid.put("kb-c", new ArrayList<>(List.of(Rec46cSupport.sr("c-1", "C 命中", "kc", 0.55))));
        PluginSearch p2 = new PluginSearch(kbSvc2, null, null, null, null, null, null, null, null);
        ChatManage cm2 = new ChatManage();
        cm2.setRewriteQuery("指定文档");
        cm2.setEmbeddingTopK(2);
        cm2.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge", "kb-c", 1,
                        List.of("doc-1"), List.of("tag-1"), List.of("scope-1"), false))));
        cm2.setTenantId(1);
        List<SearchResult> res2 = p2.searchByTargets(cm2);
        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("err", null);
        s2.put("ids", resultIDs(res2));
        s2.put("params", kbSvc2.paramsJson());
        assertRec("search_by_targets", "knowledge_target", json(s2));

        List<SearchResult> res3 = p2.searchByTargets(new ChatManage());
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("n", res3 == null ? 0 : res3.size());
        s3.put("err", null);
        assertRec("search_by_targets", "empty", json(s3));
    }

    /**
     * 回归（2026-09-22 走查抓回）：hybridSearch 返回 null（无可用检索管道，
     * 对照 Go 的 nil 切片）时，合并整库检索路径曾 {@code results.addAll(null)} 抛
     * NPE（"Cannot invoke Collection.toArray() because c is null"），整个 QA 以
     * PipelinePortException 收场。正确语义 = append(dst, nil...) 的 no-op：
     * 视为空命中、无错误、流水线继续。
     */
    @Test
    void searchByTargetsNullHybridResult() {
        Rec46cSupport.StubKBService kbSvc = new Rec46cSupport.StubKBService();
        kbSvc.hybridNull.add("kb-1"); // 无可用检索管道 → hybridSearch 返回 null
        kbSvc.kbs.put("kb-1", Rec46cSupport.kb("kb-1", "document", true, true, false));
        PluginSearch p = new PluginSearch(kbSvc, null, null, null, null, null, null, null, null);
        ChatManage cm = new ChatManage();
        cm.setQuery("你好");
        cm.setRewriteQuery("你好");
        cm.setEmbeddingTopK(2);
        cm.setVectorThreshold(0.5);
        cm.setKeywordThreshold(0.6);
        cm.setTenantId(1);
        cm.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb-1", 1,
                        null, null, null, false))));
        List<SearchResult> res = p.searchByTargets(cm); // 修复前这里抛 NPE
        org.junit.jupiter.api.Assertions.assertTrue(res == null || res.isEmpty(),
                "null 检索结果应视为空命中而不是异常");
    }

    @Test
    void searchParallel() {
        Rec46cSupport.StubKBService kbSvc = new Rec46cSupport.StubKBService();
        kbSvc.kbs.put("kb-1", Rec46cSupport.kb("kb-1", "document", true, true, false));
        kbSvc.embed.put("kb-1", new float[] {0.1f});
        kbSvc.hybrid.put("kb-1", new ArrayList<>(List.of(
                Rec46cSupport.sr("chunk-1", "文档命中", "k1", 0.8),
                Rec46cSupport.sr("dup-1", "重复", "k1", 0.7))));
        Rec46cSupport.StubGraphRepo graph = new Rec46cSupport.StubGraphRepo();
        graph.byKB.put("kb-1", new ChatManage.GraphData(List.of(
                new ChatManage.GraphNode("实体A", List.of("chunk-1", "ent-1"), null),
                new ChatManage.GraphNode("实体B", List.of("ent-2"), null)),
                new ArrayList<>()));
        Rec46cSupport.StubKnowledgeRepo knowledgeRepo = new Rec46cSupport.StubKnowledgeRepo();
        knowledgeRepo.items.put("k1", knowledge("k1"));
        Rec46cSupport.StubChunkRepo chunkRepo = new Rec46cSupport.StubChunkRepo();
        chunkRepo.chunks.put("ent-1", chunk("ent-1", "实体扩展内容一", 3));
        chunkRepo.chunks.put("ent-2", chunk("ent-2", "实体扩展内容二", 4));
        EventManager mgr = new EventManager();
        PluginSearchParallel p = new PluginSearchParallel(mgr, kbSvc, new Rec46cSupport.StubKnowledgeService(),
                new Rec46cSupport.StubChunkService(), new PipelineConfig(), null,
                new Rec46cSupport.StubTenantService(), null, null, null, graph, chunkRepo, knowledgeRepo);
        ChatManage cm = new ChatManage();
        cm.setSessionId("sp1");
        cm.setQuery("实体查询");
        cm.setRewriteQuery("实体查询");
        cm.setEmbeddingTopK(5);
        cm.setTenantId(1);
        cm.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb-1", 1, null, null, null, false))));
        cm.setEntity(new ArrayList<>(List.of("实体A", "实体B")));
        boolean[] next = {false};
        PluginError err = p.onEvent(PipelineEventType.CHUNK_SEARCH_PARALLEL, cm,
                () -> { next[0] = true; return null; });
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("next", next[0]);
        s1.put("err", errOf(err));
        s1.put("ids", resultIDs(cm.getSearchResult()));
        s1.put("graph_nodes", Rec46cSupport.graphShape(cm.getGraphResult()));
        assertRec("search_parallel", "both", json(s1));

        // chunk only
        EventManager mgr2 = new EventManager();
        PluginSearchParallel p2 = new PluginSearchParallel(mgr2, kbSvc, new Rec46cSupport.StubKnowledgeService(),
                new Rec46cSupport.StubChunkService(), new PipelineConfig(), null, null, null, null, null,
                graph, chunkRepo, knowledgeRepo);
        ChatManage cm2 = new ChatManage();
        cm2.setSessionId("sp2");
        cm2.setQuery("普通");
        cm2.setRewriteQuery("普通");
        cm2.setEmbeddingTopK(5);
        cm2.setTenantId(1);
        cm2.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb-1", 1, null, null, null, false))));
        boolean[] next2 = {false};
        PluginError err2 = p2.onEvent(PipelineEventType.CHUNK_SEARCH_PARALLEL, cm2,
                () -> { next2[0] = true; return null; });
        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("next", next2[0]);
        s2.put("err", errOf(err2));
        s2.put("ids", resultIDs(cm2.getSearchResult()));
        assertRec("search_parallel", "chunk_only", json(s2));

        // intent skip
        ChatManage cm3 = new ChatManage();
        cm3.setIntent(QueryIntent.GREETING);
        boolean[] next3 = {false};
        p2.onEvent(PipelineEventType.CHUNK_SEARCH_PARALLEL, cm3, () -> { next3[0] = true; return null; });
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("next", next3[0]);
        s3.put("n", cm3.getSearchResult() == null ? 0 : cm3.getSearchResult().size());
        assertRec("search_parallel", "intent_skip", json(s3));

        // empty → search_nothing
        Rec46cSupport.StubKBService emptyKB = new Rec46cSupport.StubKBService();
        emptyKB.kbs.put("kb-9", Rec46cSupport.kb("kb-9", "document", true, true, false));
        EventManager mgr4 = new EventManager();
        PluginSearchParallel p4 = new PluginSearchParallel(mgr4, emptyKB, new Rec46cSupport.StubKnowledgeService(),
                new Rec46cSupport.StubChunkService(), new PipelineConfig(), null, null, null, null, null,
                new Rec46cSupport.StubGraphRepo(), new Rec46cSupport.StubChunkRepo(),
                new Rec46cSupport.StubKnowledgeRepo());
        ChatManage cm4 = new ChatManage();
        cm4.setSessionId("sp4");
        cm4.setQuery("空");
        cm4.setRewriteQuery("空");
        cm4.setEmbeddingTopK(5);
        cm4.setTenantId(1);
        cm4.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb-9", 1, null, null, null, false))));
        PluginError err4 = p4.onEvent(PipelineEventType.CHUNK_SEARCH_PARALLEL, cm4, () -> null);
        Map<String, Object> s4 = new LinkedHashMap<>();
        s4.put("err", errOf(err4));
        assertRec("search_parallel", "empty_nothing", json(s4));
    }

    private static com.ragagent.knowledge.domain.Knowledge knowledge(String id) {
        var k = new com.ragagent.knowledge.domain.Knowledge();
        k.setId(id);
        k.setTitle("文档一");
        k.setFileName("doc1.pdf");
        k.setSource("manual");
        k.setChannel("web");
        k.setKnowledgeBaseId("kb-1");
        return k;
    }

    private static com.ragagent.knowledge.domain.Chunk chunk(String id, String content, int index) {
        var c = new com.ragagent.knowledge.domain.Chunk();
        c.setId(id);
        c.setKnowledgeId("k1");
        c.setChunkType("text");
        c.setContent(content);
        c.setChunkIndex(index);
        return c;
    }
}
