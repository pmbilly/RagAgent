package com.ragagent.chatpipeline;

import static com.ragagent.chatpipeline.Rec46cSupport.assertRec;
import static com.ragagent.chatpipeline.Rec46cSupport.json;
import static com.ragagent.chatpipeline.Rec46cSupport.searchResultsShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.knowledge.domain.FaqChunkMetadata;

/**
 * 实录回放：merge 五件（classify / sequential / group / parent / expand / faq / history）
 * （期望值 = {@link GoRecording46C} Go 实录常量）。
 */
class MergeRecordingTest {

    @BeforeAll
    static void install() {
        Rec46cSupport.installSegmenter();
    }

    @AfterAll
    static void restore() {
        Rec46cSupport.restoreSegmenter();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 就地设值的小工具（对照 Go 的复合字面量）。 */
    private static SearchResult sr(String id, String content, double score,
                                   java.util.function.Consumer<SearchResult> mutate) {
        SearchResult r = sr(id, content, score);
        mutate.accept(r);
        return r;
    }

    private static SearchResult sr(String id, String content, double score) {
        SearchResult r = new SearchResult();
        r.setId(id);
        r.setContent(content);
        r.setScore(score);
        return r;
    }

    // ----- classify（对照 recMergeClassify） -----

    private static SearchResult mk(String content, int start, int end, int revision,
                                   boolean rewritten, int index) {
        SearchResult r = new SearchResult();
        r.setContent(content);
        r.setStartAt(start);
        r.setEndAt(end);
        r.setContentRevision(revision);
        r.setContentRewritten(rewritten);
        r.setChunkIndex(index);
        return r;
    }

    @Test
    void mergeClassify() {
        record Case(String key, SearchResult last, int lastIx, SearchResult cur) {}
        List<Case> cases = List.of(
                new Case("trusted_gap", mk("abcdef", 0, 6, 0, false, 0), 0, mk("uvwxyz", 10, 16, 0, false, 1)),
                new Case("trusted_extend", mk("abcdef", 0, 6, 0, false, 0), 0, mk("cdefgh", 2, 8, 0, false, 1)),
                new Case("trusted_subsume", mk("abcdefgh", 0, 8, 0, false, 0), 0, mk("cdef", 2, 6, 0, false, 1)),
                new Case("trusted_join_distinct", mk("abcdefgh", 0, 8, 0, false, 0), 0, mk("cdXY", 2, 6, 0, false, 1)),
                new Case("untrusted_text_contained", mk("hello world", 0, 0, 3, false, 0), 0, mk("hello", 0, 0, 0, false, 1)),
                new Case("untrusted_sequential", mk("one", 0, 0, 0, false, 0), 0, mk("two", 0, 0, 0, false, 1)),
                new Case("untrusted_separate", mk("one", 0, 0, 0, false, 0), 0, mk("two", 0, 0, 0, false, 5)),
                new Case("untrusted_reverse_contained", mk("short", 0, 0, 0, false, 0), 0,
                        mk("a much longer body that contains short inside", 0, 0, 0, false, 9)));
        for (Case c : cases) {
            PluginMerge.MergeSituation sit = PluginMerge.classifyMerge(c.last(), c.lastIx(), c.cur());
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("situation", sit.ordinal());
            shape.put("trusted_last", PluginMerge.chunkTrusted(c.last()));
            shape.put("trusted_cur", PluginMerge.chunkTrusted(c.cur()));
            assertRec("merge_classify", c.key(), json(shape));
        }
    }

    // ----- sequential（对照 recMergeSequential） -----

    private PluginMerge plugin() {
        return new PluginMerge(new Rec46cSupport.StubChunkRepo(), null);
    }

    @Test
    void mergeSequential() {
        PluginMerge p = plugin();

        List<SearchResult> extend = new ArrayList<>(List.of(
                sr("c1", "首段内容甲乙丙", 0.6, r -> { r.setStartAt(0); r.setEndAt(7); r.setChunkIndex(0); r.setKnowledgeId("k"); }),
                sr("c2", "甲乙丙丁戊", 0.8, r -> { r.setStartAt(4); r.setEndAt(9); r.setChunkIndex(1); })));
        assertRec("merge_sequential", "extend", json(searchResultsShape(p.mergeSequentialChunks("k", extend))));

        List<SearchResult> subsume = new ArrayList<>(List.of(
                sr("c1", "完整的一段话包含子串", 0.7, r -> { r.setStartAt(0); r.setEndAt(9); r.setChunkIndex(0); }),
                sr("c2", "完整的一段话", 0.9, r -> { r.setStartAt(0); r.setEndAt(5); r.setChunkIndex(1); })));
        List<SearchResult> out2 = p.mergeSequentialChunks("k", subsume);
        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("results", searchResultsShape(out2));
        s2.put("subs", out2.get(0).getSubChunkId());
        assertRec("merge_sequential", "subsume", json(s2));

        List<SearchResult> joinText = new ArrayList<>(List.of(
                sr("c1", "已被编辑过的父段内容", 0.6, r -> { r.setContentRevision(2); r.setChunkIndex(0); }),
                sr("c2", "父段", 0.8, r -> r.setChunkIndex(1))));
        List<SearchResult> out3 = p.mergeSequentialChunks("k", joinText);
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("results", searchResultsShape(out3));
        s3.put("subs", out3.get(0).getSubChunkId());
        assertRec("merge_sequential", "join_text", json(s3));

        List<SearchResult> separate = new ArrayList<>(List.of(
                sr("c1", "第一块", 0.5, r -> r.setChunkIndex(0)),
                sr("c2", "完全不同的第二块", 0.9, r -> r.setChunkIndex(7))));
        assertRec("merge_sequential", "separate",
                json(searchResultsShape(p.mergeSequentialChunks("k", separate))));

        String imgA = jsonOf(List.of(Map.of("url", "u1", "caption", "c1")));
        String imgB = jsonOf(List.of(Map.of("url", "u1", "caption", "c1-dup"), Map.of("url", "u2", "ocr_text", "o2")));
        List<SearchResult> joinImg = new ArrayList<>(List.of(
                sr("c1", "一段包含图片的正文", 0.6, r -> { r.setContentRevision(1); r.setChunkIndex(0); r.setImageInfo(imgA); }),
                sr("c2", "一段包含图片的正文补充", 0.8, r -> { r.setContentRevision(1); r.setChunkIndex(1); r.setImageInfo(imgB); })));
        assertRec("merge_sequential", "image_info_merge",
                json(searchResultsShape(p.mergeSequentialChunks("k", joinImg))));

        Map<String, Object> s5 = new LinkedHashMap<>();
        var exact = com.ragagent.retrieval.support.SearchChunkMerge.appendWithExactOverlap(
                "直接重叠的甲乙丙", "甲乙丙丁", 3);
        s5.put("ok", com.ragagent.retrieval.support.SearchChunkMerge.appendWithOverlap(
                "HTML &amp; 实体头部内容", "实体头部内容加后续", 12));
        s5.put("exact", exact.value());
        assertRec("merge_sequential", "append_fallback", json(s5));
    }

    private static String jsonOf(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ----- group（对照 recMergeGroup） -----

    @Test
    void mergeGroup() {
        PluginMerge p = plugin();
        List<SearchResult> rs = new ArrayList<>(List.of(
                groupResult("k1-2", "k1", "text", 2, "第二段", 0.5),
                groupResult("k2-0", "k2", "text", 0, "另一文档", 0.8),
                groupResult("k1-0", "k1", "text", 0, "第一段", 0.6),
                groupResult("k1-faq", "k1", "faq", 0, "FAQ 条目", 0.7)));
        assertRec("merge_group", "two_kb", json(searchResultsShape(p.groupAndMergeCurrentContent(rs))));
    }

    private static SearchResult groupResult(String id, String knowledgeId, String chunkType,
                                            int index, String content, double score) {
        SearchResult r = sr(id, content, score);
        r.setKnowledgeId(knowledgeId);
        r.setChunkType(chunkType);
        r.setChunkIndex(index);
        return r;
    }

    // ----- parent（对照 recMergeParent） -----

    @Test
    void mergeParent() {
        Chunk parent = new Chunk();
        parent.setId("parent");
        parent.setChunkType("parent_text");
        parent.setChunkIndex(7);
        parent.setContent("手工插入的前缀\n\n![one](u1)\n\n父块正文\n\n![two](u2)");
        Rec46cSupport.StubChunkRepo repo = new Rec46cSupport.StubChunkRepo();
        repo.chunks.put("parent", parent);
        PluginMerge p = new PluginMerge(repo, null);
        // 缺省租户走 chatManage.TenantID 兜底（Java 无 ctx 传值）
        ChatManage tenantCm = new ChatManage();
        tenantCm.setTenantId(1);

        String childImg = jsonOf(List.of(Map.of("url", "u2", "ocr_text", "two")));
        SearchResult res = sr("child", "当前被编辑过的子块正文", 0);
        res.setKnowledgeId("doc");
        res.setChunkType("text");
        res.setParentChunkId("parent");
        res.setStartAt(999);
        res.setEndAt(1001);
        res.setImageInfo(childImg);
        List<SearchResult> got = p.resolveParentChunks(tenantCm, new ArrayList<>(List.of(res)));
        assertRec("merge_parent", "text_to_parent", json(searchResultsShape(got)));

        String ii = jsonOf(List.of(Map.of("url", "u1", "ocr_text", "matched image")));
        Chunk text = new Chunk();
        text.setId("text");
        text.setParentChunkId("parent");
        text.setChunkType("text");
        text.setChunkIndex(4);
        text.setContent("当前编辑过的文本子块\n\n![matched](u1)");
        text.setStartAt(900);
        text.setEndAt(910);
        Chunk grandparent = new Chunk();
        grandparent.setId("parent");
        grandparent.setChunkType("parent_text");
        grandparent.setContent("祖父上文\n\n![matched](u1)\n\n祖父下文\n\n![sibling](u2)");
        grandparent.setStartAt(0);
        grandparent.setEndAt(100);
        Rec46cSupport.StubChunkRepo repo2 = new Rec46cSupport.StubChunkRepo();
        repo2.chunks.put("text", text);
        repo2.chunks.put("parent", grandparent);
        PluginMerge p2 = new PluginMerge(repo2, null);
        SearchResult res2 = sr("image", "matched image", 0);
        res2.setKnowledgeId("doc");
        res2.setChunkType("image_ocr");
        res2.setParentChunkId("text");
        res2.setImageInfo(ii);
        res2.setStartAt(500);
        res2.setEndAt(510);
        List<SearchResult> got2 = p2.resolveParentChunks(tenantCm, new ArrayList<>(List.of(res2)));
        assertRec("merge_parent", "image_grandparent", json(searchResultsShape(got2)));

        List<SearchResult> got3 = p2.resolveParentChunks(new ChatManage(), new ArrayList<>(List.of(res2)));
        assertRec("merge_parent", "no_tenant", json(searchResultsShape(got3)));

        List<SearchResult> got4 = p2.resolveParentChunks(tenantCm, new ArrayList<>(List.of(res2)));
        Map<String, Object> s4 = new LinkedHashMap<>();
        s4.put("n", got4.size());
        assertRec("merge_parent", "chat_manage_tenant", json(s4));

        Rec46cSupport.StubChunkRepo repoBad = new Rec46cSupport.StubChunkRepo();
        repoBad.listErr = true;
        repoBad.chunks.put("parent", parent);
        PluginMerge p5 = new PluginMerge(repoBad, null);
        // Go 探针复用了 text_to_parent 已就地改写的 res 指针
        List<SearchResult> got5 = p5.resolveParentChunks(tenantCm, new ArrayList<>(List.of(res)));
        assertRec("merge_parent", "repo_error", json(searchResultsShape(got5)));
    }

    // ----- expand（对照 recMergeExpand） -----

    @Test
    void mergeExpand() {
        Rec46cSupport.StubChunkRepo repo = new Rec46cSupport.StubChunkRepo();
        repo.chunks.put("base", chunk("base", "doc", "text", "基础块内容", 0, "prev", "next"));
        repo.chunks.put("prev", chunk("prev", "doc", "text", "前一块的内容，提供了上文的语境交代", 0, "prev2", ""));
        repo.chunks.put("prev2", chunk("prev2", "doc", "text", "更早一块，含开头介绍与背景交代说明", 0, "prev3", ""));
        repo.chunks.put("next", chunk("next", "doc", "text", "后一块内容，包含下文展开", 0, "", "next2"));
        repo.chunks.put("next2", chunk("next2", "doc", "text", "更后一块，包含结尾与总结内容说明", 0, "", ""));
        repo.chunks.put("orphan", chunk("orphan", "other-doc", "text", "别的文档的块", 0, "", ""));
        PluginMerge p = new PluginMerge(repo, null);
        ChatManage tenantCm = new ChatManage();
        tenantCm.setTenantId(1);

        SearchResult res = sr("base", "基础块内容", 0, r -> { r.setKnowledgeId("doc"); r.setChunkType("text"); });
        List<SearchResult> got = p.expandShortContextWithNeighbors(tenantCm, new ArrayList<>(List.of(res)));
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("results", searchResultsShape(got));
        s1.put("repo_calls", repo.calls);
        assertRec("merge_expand", "chain", json(s1));

        Rec46cSupport.StubChunkRepo repo2 = new Rec46cSupport.StubChunkRepo();
        PluginMerge p2 = new PluginMerge(repo2, null);
        SearchResult res2 = sr("missing", "短", 0);
        res2.setKnowledgeId("doc");
        res2.setChunkType("text");
        List<SearchResult> got2 = p2.expandShortContextWithNeighbors(tenantCm, new ArrayList<>(List.of(res2)));
        assertRec("merge_expand", "base_missing", json(searchResultsShape(got2)));

        SearchResult res3 = sr("faq1", "FAQ", 0);
        res3.setKnowledgeId("doc");
        res3.setChunkType("faq");
        List<SearchResult> got3 = p.expandShortContextWithNeighbors(tenantCm, new ArrayList<>(List.of(res3)));
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("content", got3.get(0).getContent());
        assertRec("merge_expand", "non_text", json(s3));

        Map<String, Object> s4 = new LinkedHashMap<>();
        s4.put("merged", PluginMerge.mergeOrderedContent("前文甲乙丙丁", "中段内容", "后文戊己庚辛", 12));
        s4.put("norunes", PluginMerge.runeLen("中文四字"));
        s4.put("within", PluginMerge.mergeOrderedContent("短前", "中", "短后", 100));
        assertRec("merge_expand", "ordered_truncate", json(s4));
    }

    private static Chunk chunk(String id, String knowledgeId, String type, String content,
                               int index, String pre, String next) {
        Chunk c = new Chunk();
        c.setId(id);
        c.setKnowledgeId(knowledgeId);
        c.setChunkType(type);
        c.setContent(content);
        c.setChunkIndex(index);
        c.setPreChunkId(pre);
        c.setNextChunkId(next);
        return c;
    }

    // ----- faq（对照 recMergeFAQ） -----

    @Test
    void mergeFaq() throws Exception {
        String meta = JSON.writeValueAsString(Map.of(
                "standardQuestion", "退货政策是什么？",
                "answers", List.of("七天内可退货", "需保留包装")));
        Rec46cSupport.StubChunkRepo repo = new Rec46cSupport.StubChunkRepo();
        Chunk faq1 = new Chunk();
        faq1.setId("faq1");
        faq1.setKnowledgeId("doc");
        faq1.setChunkType("faq");
        faq1.setMetadata(JSON.readTree(meta));
        Chunk faq2 = new Chunk();
        faq2.setId("faq2");
        faq2.setKnowledgeId("doc");
        faq2.setChunkType("faq");
        faq2.setMetadata(JSON.readTree("\"not json\""));
        Chunk plain = new Chunk();
        plain.setId("plain");
        plain.setKnowledgeId("doc");
        plain.setChunkType("text");
        plain.setContent("普通块");
        repo.chunks.put("faq1", faq1);
        repo.chunks.put("faq2", faq2);
        repo.chunks.put("plain", plain);
        PluginMerge p = new PluginMerge(repo, null);
        ChatManage tenantCm = new ChatManage();
        tenantCm.setTenantId(1);

        SearchResult r1 = sr("faq1", "旧内容一", 0);
        r1.setChunkType("faq");
        SearchResult r1dup = sr("faq1", "旧内容一重复", 0);
        r1dup.setChunkType("faq");
        SearchResult r2 = sr("faq2", "坏元数据", 0);
        r2.setChunkType("faq");
        SearchResult r3 = sr("plain", "普通块", 0);
        r3.setChunkType("text");
        List<SearchResult> out = p.populateFAQAnswers(tenantCm,
                new ArrayList<>(List.of(r1, r1dup, r2, r3)));
        assertRec("merge_faq", "populate", json(searchResultsShape(out)));

        assertRec("merge_faq", "build_content", json(List.of(
                PluginMerge.buildFAQAnswerContent(null),
                PluginMerge.buildFAQAnswerContent(new FaqChunkMetadata()),
                faq("  只有问题  ", null),
                faq(null, new String[] {"  答案一 ", "", "  "}),
                faq("问", new String[] {"答一", "答二"}))));
    }

    private static String faq(String question, String[] answers) {
        FaqChunkMetadata m = new FaqChunkMetadata();
        m.standardQuestion = question == null ? "" : question;
        if (answers != null) {
            m.answers = new ArrayList<>(List.of(answers));
        }
        return PluginMerge.buildFAQAnswerContent(m);
    }

    // ----- history filter（对照 recMergeHistoryFilter） -----

    @Test
    void mergeHistoryFilter() {
        List<SearchResult> hrefs = new ArrayList<>(List.of(
                histRef("h1", "k1", "WeKnora 是一个企业知识库检索系统，支持多种检索方式与重排序能力", 0.8),
                histRef("h2", "k2", "完全无关的历史内容，讲的是买菜做饭和天气", 0.7),
                histRef("h3", "k3", "企业知识库的检索方式包括向量与关键词混合", 0.6),
                histRef("h1dup", "k1", "WeKnora 是一个企业知识库检索系统，支持多种检索方式与重排序能力", 0.9)));
        ChatManage cm = new ChatManage();
        cm.setQuery("知识库检索");
        cm.setRewriteQuery("企业知识库 检索方式");
        History h = new History();
        h.setQuery("旧问题");
        h.setAnswer("旧回答");
        h.setKnowledgeReferences(hrefs);
        cm.setHistory(new ArrayList<>(List.of(h)));

        List<SearchResult> filtered = PluginMerge.filterHistoryResults(cm,
                new ArrayList<>(List.of(histRef("h1", "k1", "", 0))));
        assertRec("merge_history", "filter", json(searchResultsShape(filtered)));

        ChatManage cm2 = new ChatManage();
        assertRec("merge_history", "empty",
                json(searchResultsShape(PluginMerge.filterHistoryResults(cm2, new ArrayList<>()))));

        List<SearchResult> raw = SearchSupport.getSearchResultFromHistory(cm);
        assertRec("merge_history", "from_history", json(searchResultsShape(raw)));
    }

    private static SearchResult histRef(String id, String knowledgeId, String content, double score) {
        SearchResult r = Rec46cSupport.sr(id, content, knowledgeId, score);
        r.setChunkType("text");
        return r;
    }
}
