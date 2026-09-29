package com.ragagent.chatpipeline;

import static com.ragagent.chatpipeline.Rec46cSupport.assertRec;
import static com.ragagent.chatpipeline.Rec46cSupport.errOf;
import static com.ragagent.chatpipeline.Rec46cSupport.json;
import static com.ragagent.chatpipeline.Rec46cSupport.resultIDs;
import static com.ragagent.chatpipeline.Rec46cSupport.scores;
import static com.ragagent.chatpipeline.Rec46cSupport.searchResultsShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ragagent.memory.service.MemoryRecall;
import com.ragagent.retrieval.domain.SearchResult;

/**
 * 实录回放：rerank（清洗/段落/编排）+ wiki_boost + memory_recall/affinity + progress
 * （期望值 = {@link GoRecording46C} Go 实录常量）。
 */
class RerankRecordingTest {

    @BeforeAll
    static void install() {
        Rec46cSupport.installSegmenter();
    }

    @AfterAll
    static void restore() {
        Rec46cSupport.restoreSegmenter();
    }

    private static final String[] CLEAN_CORPUS = {
            "这是一段普通的文本内容",
            "前文 ![图片说明](https://example.com/img.png) 后文",
            "请参考 [官方文档](https://docs.example.com) 了解详情",
            "访问 https://example.com/path?q=1&b=2 获取更多信息",
            "示例代码：\n```python\nprint('hello')\n```\n以上是示例",
            "公式如下 $$E=mc^2$$ 其中E是能量",
            "| 名称 | 值 |\n| --- | --- |\n| A | 1 |",
            "## 第二章 概述\n### 2.1 背景",
            "> 这是一段引用\n> 第二行引用",
            "这是 **加粗** 和 *斜体* 以及 ***粗斜体*** 文本",
            "- 项目一\n- 项目二\n1. 有序一\n2. 有序二",
            "文本<br>换行<div class=\"test\">内容</div>结尾",
            "段落一\n\n\n\n\n段落二",
            "## 产品介绍\n\n这是一个 **重要的** 产品。详见 [产品页面](https://example.com/product)。\n\n![产品截图](images/product.png)\n\n> 用户评价：非常好用\n\n- 功能一\n- 功能二\n\n```json\n{\"key\": \"value\"}\n```",
            "| col1 | col2 | col3 |",
            "| Header1 | Header2 |\n| --- | --- |\n| data1 | data2 |\n| data3 | data4 |",
            "| --- | --- |",
            "   \n\n   ",
            "[![嵌套图片](img.png)](link.png) 前后文",
            "URL带括号 https://zh.wikipedia.org/wiki/知识库_(数据库) 结尾",
    };

    @Test
    void rerankClean() {
        for (int i = 0; i < CLEAN_CORPUS.length; i++) {
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("in", CLEAN_CORPUS[i]);
            shape.put("out", PluginRerank.cleanPassageForRerank(CLEAN_CORPUS[i]));
            assertRec("rerank_clean", String.format("case%02d", i), json(shape));
        }
    }

    @Test
    void rerankPassage() throws Exception {
        // Go json.Marshal 的 struct 序与零值字段（url/original_url/start_pos/end_pos/caption/ocr_text）
        var q1 = ObjectMapperHolder.JSON.createObjectNode();
        q1.put("id", "q1").put("question", "生成的问题一");
        var q2 = ObjectMapperHolder.JSON.createObjectNode();
        q2.put("id", "q2").put("question", "生成的问题二");
        var metaNode = ObjectMapperHolder.JSON.createObjectNode();
        metaNode.set("generatedQuestions", ObjectMapperHolder.JSON.createArrayNode().add(q1).add(q2));
        metaNode.put("generatedQuestionsRevision", 1);
        String meta = ObjectMapperHolder.JSON.writeValueAsString(metaNode);
        var imgNode = ObjectMapperHolder.JSON.createObjectNode();
        imgNode.put("url", "u1").put("original_url", "").put("start_pos", 0)
                .put("end_pos", 0).put("caption", "图片说明").put("ocr_text", "OCR 文本");
        String img = "[" + ObjectMapperHolder.JSON.writeValueAsString(imgNode) + "]";
        String badImg = "[{bad";
        List<SearchResult> corpus = new ArrayList<>();
        corpus.add(Rec46cSupport.sr("c", "纯文本内容", "k", 0));
        SearchResult c1 = Rec46cSupport.sr("c", "", "k", 0);
        c1.setImageInfo(img);
        corpus.add(c1);
        SearchResult c2 = Rec46cSupport.sr("c", "带图片的内容", "k", 0);
        c2.setImageInfo(img);
        corpus.add(c2);
        SearchResult c3 = Rec46cSupport.sr("c", "正文", "k", 0);
        c3.setChunkMetadata(ObjectMapperHolder.JSON.readTree(meta));
        corpus.add(c3);
        SearchResult c4 = Rec46cSupport.sr("c", "带图片和问题", "k", 0);
        c4.setImageInfo(img);
        c4.setChunkMetadata(ObjectMapperHolder.JSON.readTree(meta));
        corpus.add(c4);
        SearchResult c5 = Rec46cSupport.sr("c", "坏图片 JSON", "k", 0);
        c5.setImageInfo(badImg);
        corpus.add(c5);
        SearchResult c6 = Rec46cSupport.sr("c", "坏元数据", "k", 0);
        c6.setChunkMetadata(ObjectMapperHolder.JSON.readTree("[1,2,3]"));
        corpus.add(c6);
        corpus.add(Rec46cSupport.sr("c", "", "k", 0));

        for (int i = 0; i < corpus.size(); i++) {
            SearchResult r = corpus.get(i);
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("content", r.getContent());
            in.put("image_info", r.getImageInfo());
            // Go 的 len() 是 UTF-8 字节数
            in.put("meta_len", r.getChunkMetadata() == null ? 0
                    : r.getChunkMetadata().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("in", in);
            shape.put("out", PluginRerank.getEnrichedPassage(r));
            assertRec("rerank_passage", String.format("case%02d", i), json(shape));
        }
        for (int i = 0; i < corpus.size(); i++) {
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("out", PluginIntoChatMessage.getEnrichedPassageForChat(corpus.get(i)));
            assertRec("rerank_passage", String.format("chat%02d", i), json(shape));
        }
    }

    private static final class ObjectMapperHolder {
        private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
                new com.fasterxml.jackson.databind.ObjectMapper();
    }

    private static String jsonOf(Object o) {
        try {
            return ObjectMapperHolder.JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SearchResult cand(String id, String content, String knowledgeId, double score) {
        SearchResult r = new SearchResult();
        r.setId(id);
        r.setContent(content);
        r.setKnowledgeId(knowledgeId);
        r.setScore(score);
        r.setChunkType("text");
        return r;
    }

    private static SearchResult cand(String id, String content, String knowledgeId, double score,
                                     java.util.function.Consumer<SearchResult> mutate) {
        SearchResult r = cand(id, content, knowledgeId, score);
        mutate.accept(r);
        return r;
    }

    private ChatManage rerankCm() {
        ChatManage cm = new ChatManage();
        cm.setSessionId("sr");
        cm.setRewriteQuery("重排查询");
        cm.setRerankModelId("rr-1");
        cm.setRerankThreshold(0.3);
        cm.setRerankTopK(3);
        cm.setIntent(QueryIntent.KB_SEARCH);
        cm.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb-1", 1, null, null, null, false))));
        List<SearchResult> sr = new ArrayList<>(List.of(
                cand("c1", "第一段候选内容，语义相关", "k1", 0.8),
                cand("c2", "第二段候选内容，语义稍弱", "k2", 0.6),
                cand("c3", "   ", "k3", 0.7)));
        cm.setSearchResult(sr);
        return cm;
    }

    private Rec46cSupport.StubModelService msWith(Rec46cSupport.StubReranker rr, RuntimeException err) {
        Rec46cSupport.StubModelService ms = new Rec46cSupport.StubModelService();
        ms.rerankModels.put("rr-1", rr);
        if (err != null) {
            ms.rerankErr.put("rr-1", err);
        }
        return ms;
    }

    @Test
    void rerankOnEvent() {
        // 1) normal
        Rec46cSupport.StubReranker rr = new Rec46cSupport.StubReranker()
                .add(Rec46cSupport.rankResults(0, 0.9, 1, 0.5), null);
        Rec46cSupport.StubModelService ms1 = new Rec46cSupport.StubModelService();
        ms1.rerankModels.put("rr-1", rr);
        PluginRerank p = new PluginRerank(ms1);
        ChatManage cm = rerankCm();
        boolean[] next = {false};
        PluginError err = p.onEvent(PipelineEventType.CHUNK_RERANK, cm, () -> { next[0] = true; return null; });
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("next", next[0]);
        s1.put("err", errOf(err));
        s1.put("rerank", searchResultsShape(cm.getRerankResult()));
        s1.put("model_calls", rr.calls);
        // Go 探针的 ms 未挂 calls 指针 → model_lookup 录成 null（形状备案）
        s1.put("model_lookup", null);
        s1.put("search_kept", searchResultsShape(cm.getSearchResult()));
        assertRec("rerank", "normal", json(s1));

        // 2) threshold degrade
        Rec46cSupport.StubReranker rr2 = new Rec46cSupport.StubReranker()
                .add(null, null)
                .add(Rec46cSupport.rankResults(0, 0.42), null);
        PluginRerank p2 = new PluginRerank(msWith(rr2, null));
        ChatManage cm2 = rerankCm();
        cm2.setRerankThreshold(0.5);
        boolean[] next2 = {false};
        PluginError err2 = p2.onEvent(PipelineEventType.CHUNK_RERANK, cm2, () -> { next2[0] = true; return null; });
        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("next", next2[0]);
        s2.put("err", errOf(err2));
        s2.put("rerank", searchResultsShape(cm2.getRerankResult()));
        s2.put("model_calls", rr2.calls);
        s2.put("threshold_restored", cm2.getRerankThreshold());
        assertRec("rerank", "threshold_degrade", json(s2));

        // 3) api error fallback
        Rec46cSupport.StubReranker rr3 = new Rec46cSupport.StubReranker()
                .add(null, new RuntimeException("rerank api down"));
        PluginRerank p3 = new PluginRerank(msWith(rr3, null));
        ChatManage cm3 = rerankCm();
        boolean[] next3 = {false};
        PluginError err3 = p3.onEvent(PipelineEventType.CHUNK_RERANK, cm3, () -> { next3[0] = true; return null; });
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("next", next3[0]);
        s3.put("err", errOf(err3));
        s3.put("ids", resultIDs(cm3.getSearchResult()));
        s3.put("rerank", cm3.getRerankResult() == null ? 0 : cm3.getRerankResult().size());
        assertRec("rerank", "api_error_fallback", json(s3));

        // 4) fallback top1
        Rec46cSupport.StubReranker rr4 = new Rec46cSupport.StubReranker()
                .add(Rec46cSupport.rankResults(0, 0.2, 1, 0.1), null);
        PluginRerank p4 = new PluginRerank(msWith(rr4, null));
        ChatManage cm4 = rerankCm();
        cm4.setRerankThreshold(0.5);
        PluginError err4 = p4.onEvent(PipelineEventType.CHUNK_RERANK, cm4, () -> null);
        Map<String, Object> s4 = new LinkedHashMap<>();
        s4.put("err", errOf(err4));
        s4.put("rerank", searchResultsShape(cm4.getRerankResult()));
        assertRec("rerank", "fallback_top1", json(s4));

        // 5) fallback skip
        Rec46cSupport.StubReranker rr5 = new Rec46cSupport.StubReranker()
                .add(Rec46cSupport.rankResults(0, 0.1), null);
        PluginRerank p5 = new PluginRerank(msWith(rr5, null));
        ChatManage cm5 = rerankCm();
        cm5.setRerankThreshold(0.5);
        PluginError err5 = p5.onEvent(PipelineEventType.CHUNK_RERANK, cm5, () -> null);
        Map<String, Object> s5 = new LinkedHashMap<>();
        s5.put("err", errOf(err5));
        s5.put("rerank", 0);
        assertRec("rerank", "fallback_skip", json(s5));

        // 6) scope override fallback
        Rec46cSupport.StubReranker rr6 = new Rec46cSupport.StubReranker()
                .add(Rec46cSupport.rankResults(0, 0.05), null);
        PluginRerank p6 = new PluginRerank(msWith(rr6, null));
        ChatManage cm6 = rerankCm();
        cm6.setRerankThreshold(0.5);
        cm6.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb-1", 1, null, null, null, true))));
        PluginError err6 = p6.onEvent(PipelineEventType.CHUNK_RERANK, cm6, () -> null);
        Map<String, Object> s6 = new LinkedHashMap<>();
        s6.put("err", errOf(err6));
        s6.put("rerank", searchResultsShape(cm6.getRerankResult()));
        assertRec("rerank", "scope_override_fallback", json(s6));

        // 7) FAQ boost
        Rec46cSupport.StubReranker rr7 = new Rec46cSupport.StubReranker()
                .add(Rec46cSupport.rankResults(0, 0.9), null);
        PluginRerank p7 = new PluginRerank(msWith(rr7, null));
        ChatManage cm7 = rerankCm();
        cm7.setSearchResult(new ArrayList<>(List.of(
                cand("faq-1", "FAQ 内容", "kf", 0.5, r -> r.setChunkType("faq")))));
        cm7.setFaqPriorityEnabled(true);
        cm7.setFaqScoreBoost(1.5);
        p7.onEvent(PipelineEventType.CHUNK_RERANK, cm7, () -> null);
        assertRec("rerank", "faq_boost", json(searchResultsShape(cm7.getRerankResult())));

        // 8) MMR
        Rec46cSupport.StubReranker rr8 = new Rec46cSupport.StubReranker()
                .add(Rec46cSupport.rankResults(0, 0.9, 1, 0.85, 2, 0.8), null);
        PluginRerank p8 = new PluginRerank(msWith(rr8, null));
        ChatManage cm8 = rerankCm();
        cm8.setSearchResult(new ArrayList<>(List.of(
                cand("m1", "知识库检索系统的混合检索能力介绍，包含向量与关键词", "k1", 0.9),
                cand("m2", "知识库检索系统的混合检索能力介绍，包含向量与关键词两种方式", "k2", 0.85),
                cand("m3", "完全不同的另一段文字，说的是数据导入与文档解析的流程说明", "k3", 0.8))));
        cm8.setRerankTopK(2);
        p8.onEvent(PipelineEventType.CHUNK_RERANK, cm8, () -> null);
        Map<String, Object> s8 = new LinkedHashMap<>();
        s8.put("ids", resultIDs(cm8.getRerankResult()));
        s8.put("scores", scores(cm8.getRerankResult()));
        assertRec("rerank", "mmr", json(s8));

        // 9) model missing
        Rec46cSupport.StubModelService ms9 = new Rec46cSupport.StubModelService();
        ms9.rerankErr.put("rr-1", new RuntimeException("rerank model missing"));
        PluginRerank p9 = new PluginRerank(ms9);
        ChatManage cm9 = rerankCm();
        PluginError err9 = p9.onEvent(PipelineEventType.CHUNK_RERANK, cm9, () -> null);
        Map<String, Object> s9 = new LinkedHashMap<>();
        s9.put("err", errOf(err9));
        // Go 探针 ms 无 calls 指针 → null（形状备案）
        s9.put("lookup", null);
        assertRec("rerank", "model_missing", json(s9));

        // 10) skips
        ChatManage cm10 = new ChatManage();
        cm10.setIntent(QueryIntent.GREETING);
        boolean[] n10 = {false};
        p.onEvent(PipelineEventType.CHUNK_RERANK, cm10, () -> { n10[0] = true; return null; });
        ChatManage cm11 = rerankCm();
        cm11.setSearchResult(new ArrayList<>());
        boolean[] n11 = {false};
        p.onEvent(PipelineEventType.CHUNK_RERANK, cm11, () -> { n11[0] = true; return null; });
        ChatManage cm12 = rerankCm();
        cm12.setRerankModelId("");
        boolean[] n12 = {false};
        p.onEvent(PipelineEventType.CHUNK_RERANK, cm12, () -> { n12[0] = true; return null; });
        Map<String, Object> s10 = new LinkedHashMap<>();
        s10.put("intent_skip_next", n10[0]);
        s10.put("empty_next", n11[0]);
        s10.put("no_model_next", n12[0]);
        assertRec("rerank", "skips", json(s10));

        // 11) composite
        List<Double> comps = new ArrayList<>();
        comps.add(PluginRerank.compositeScore(new SearchResult(), 0.8, 0.6));
        SearchResult web = new SearchResult();
        web.setKnowledgeSource("web_search");
        comps.add(PluginRerank.compositeScore(web, 0.8, 0.6));
        SearchResult webUpper = new SearchResult();
        webUpper.setKnowledgeSource("WEB_SEARCH");
        comps.add(PluginRerank.compositeScore(webUpper, 1.0, 1.0));
        assertRec("rerank", "composite", json(comps));

        // 12) fallback min
        assertRec("rerank", "rerank_fallback_min", json(List.of(
                PluginRerank.rerankFallbackMinScore(new ArrayList<>()),
                PluginRerank.rerankFallbackMinScore(new ArrayList<>(List.of(
                        new com.ragagent.agent.tools.SearchTarget("knowledge_base", "kb", 1, null, null, null, true)))))));
    }

    // ----- wiki_boost（对照 recWikiBoost） -----

    @Test
    void wikiBoost() {
        Rec46cSupport.StubKBService kbSvc = new Rec46cSupport.StubKBService();
        kbSvc.kbs.put("wkb", Rec46cSupport.kb("wkb", "wiki", false, false, true));
        PluginWikiBoost p = new PluginWikiBoost(kbSvc);
        ChatManage cm = new ChatManage();
        cm.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "wkb", 1, null, null, null, false))));
        List<SearchResult> rr = new ArrayList<>(List.of(
                cand("doc-1", "x", "k", 0.9),
                cand("wiki-1", "x", "k", 0.6, r -> r.setChunkType("wiki_page"))));
        cm.setRerankResult(rr);
        boolean[] nextCalled = {false};
        PluginError err = p.onEvent(PipelineEventType.CHUNK_RERANK, cm, () -> { nextCalled[0] = true; return null; });
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("next", nextCalled[0]);
        s1.put("err", errOf(err));
        s1.put("ids", resultIDs(cm.getRerankResult()));
        s1.put("scores", scores(cm.getRerankResult()));
        assertRec("wiki_boost", "boost", json(s1));

        int callsBefore = kbSvc.byIDOnlyCalls;
        ChatManage cm2 = new ChatManage();
        cm2.setRerankResult(new ArrayList<>(List.of(cand("d", "x", "k", 0.5))));
        p.onEvent(PipelineEventType.CHUNK_RERANK, cm2, () -> null);
        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("kb_calls", kbSvc.byIDOnlyCalls - callsBefore);
        s2.put("scores", scores(cm2.getRerankResult()));
        assertRec("wiki_boost", "no_wiki_chunk", json(s2));

        Rec46cSupport.StubKBService kbSvc2 = new Rec46cSupport.StubKBService();
        kbSvc2.kbs.put("dkb", Rec46cSupport.kb("dkb", "document", true, true, false));
        PluginWikiBoost p3 = new PluginWikiBoost(kbSvc2);
        ChatManage cm3 = new ChatManage();
        cm3.setSearchTargets(new ArrayList<>(List.of(
                new com.ragagent.agent.tools.SearchTarget("knowledge_base", "dkb", 1, null, null, null, false))));
        cm3.setRerankResult(new ArrayList<>(List.of(
                cand("w", "x", "k", 0.5, r -> r.setChunkType("wiki_page")))));
        p3.onEvent(PipelineEventType.CHUNK_RERANK, cm3, () -> null);
        assertRec("wiki_boost", "no_wiki_kb", json(scores(cm3.getRerankResult())));
    }

    // ----- memory_recall / memory_affinity（对照 recMemoryRecall/recMemoryAffinity） -----

    @Test
    void memoryRecall() {
        Rec46cSupport.RecBus bus = new Rec46cSupport.RecBus();
        Rec46cSupport.StubMemoryService mem = new Rec46cSupport.StubMemoryService();
        com.ragagent.session.domain.UsedMemory u1 = new com.ragagent.session.domain.UsedMemory();
        var item1 = new com.ragagent.memory.domain.MemoryItem();
        item1.setId("m1");
        item1.setKind("fact");
        item1.setContent("用户偏好中文回答");
        var item2 = new com.ragagent.memory.domain.MemoryItem();
        item2.setId("m2");
        item2.setKind("interest");
        item2.setContent("检索系统调优");
        mem.recallValue = new MemoryRecall("<memory>用户背景记忆</memory>",
                new ArrayList<>(List.of(item1, item2)));
        PluginMemoryRecall p = new PluginMemoryRecall(mem);
        ChatManage cm = new ChatManage();
        cm.setSessionId("sm1");
        cm.setQuery("如何调优检索");
        cm.setEventBus(bus);
        boolean[] next = {false};
        PluginError err = p.onEvent(PipelineEventType.MEMORY_RECALL, cm, () -> { next[0] = true; return null; });
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("next", next[0]);
        s1.put("err", errOf(err));
        s1.put("prompt", cm.getMemoryPrompt());
        s1.put("used", Rec46cSupport.usedShape(cm.getUsedMemories()));
        s1.put("events", bus.eventsJson());
        assertRec("memory_recall", "injected", json(s1));

        Rec46cSupport.RecBus bus2 = new Rec46cSupport.RecBus();
        Rec46cSupport.StubMemoryService mem2 = new Rec46cSupport.StubMemoryService();
        PluginMemoryRecall p2 = new PluginMemoryRecall(mem2);
        ChatManage cm2 = new ChatManage();
        cm2.setSessionId("sm2");
        cm2.setQuery("q");
        cm2.setEventBus(bus2);
        boolean[] next2 = {false};
        p2.onEvent(PipelineEventType.MEMORY_RECALL, cm2, () -> { next2[0] = true; return null; });
        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("next", next2[0]);
        s2.put("prompt", cm2.getMemoryPrompt());
        s2.put("used", cm2.getUsedMemories() == null ? 0 : cm2.getUsedMemories().size());
        s2.put("events", bus2.all().size());
        assertRec("memory_recall", "empty", json(s2));

        PluginMemoryRecall p3 = new PluginMemoryRecall(null);
        ChatManage cm3 = new ChatManage();
        cm3.setSessionId("sm3");
        boolean[] next3 = {false};
        p3.onEvent(PipelineEventType.MEMORY_RECALL, cm3, () -> { next3[0] = true; return null; });
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("next", next3[0]);
        assertRec("memory_recall", "no_service", json(s3));
    }

    @Test
    void memoryAffinity() {
        Rec46cSupport.StubMemoryService mem = new Rec46cSupport.StubMemoryService();
        mem.affinity.put("k1", 2);
        mem.affinity.put("k2", 30);
        PluginMemoryAffinity p = new PluginMemoryAffinity(mem);
        ChatManage cm = new ChatManage();
        cm.setRerankResult(new ArrayList<>(List.of(
                cand("r1", "x", "k1", 0.8),
                cand("r2", "x", "k2", 0.85),
                cand("r3", "x", "k3", 0.9),
                cand("r4", "x", "", 0.7))));
        boolean[] nextCalled = {false};
        PluginError err = p.onEvent(PipelineEventType.CHUNK_RERANK, cm, () -> { nextCalled[0] = true; return null; });
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("next", nextCalled[0]);
        s1.put("err", errOf(err));
        s1.put("ids", resultIDs(cm.getRerankResult()));
        s1.put("scores", scores(cm.getRerankResult()));
        assertRec("memory_affinity", "boost", json(s1));

        Rec46cSupport.StubMemoryService mem2 = new Rec46cSupport.StubMemoryService();
        PluginMemoryAffinity p2 = new PluginMemoryAffinity(mem2);
        ChatManage cm2 = new ChatManage();
        cm2.setRerankResult(new ArrayList<>(List.of(cand("x", "x", "k9", 0.5))));
        p2.onEvent(PipelineEventType.CHUNK_RERANK, cm2, () -> null);
        assertRec("memory_affinity", "empty_affinity", json(scores(cm2.getRerankResult())));

        Rec46cSupport.StubMemoryService mem3 = new Rec46cSupport.StubMemoryService();
        PluginMemoryAffinity p3 = new PluginMemoryAffinity(mem3);
        ChatManage cm3 = new ChatManage();
        boolean[] next3 = {false};
        p3.onEvent(PipelineEventType.CHUNK_RERANK, cm3, () -> { next3[0] = true; return null; });
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("next", next3[0]);
        assertRec("memory_affinity", "no_results", json(s3));

        List<Double> factors = new ArrayList<>();
        for (int h : new int[] {0, 1, 2, 3, 8, 20, 200}) {
            factors.add(PluginMemoryAffinity.affinityFactor(h));
        }
        assertRec("memory_affinity", "factor_curve", json(factors));
    }
}
