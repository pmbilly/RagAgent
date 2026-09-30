package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.agent.tools.SearchAuth.KnowledgeTagsFetcher;
import com.ragagent.agent.tools.SearchAuth.TagView;

/**
 * 波 4.5b 回放：wiki_read_page / wiki_search（对照 Go 实录，
 * 探针 {@code zz_rec45b_wiki_tools_test.go}）。Fake 行为与 Go 的 zzFakeWiki2
 * 逐一对齐（含派生搜索：小写子串匹配 slug\u0000title\u0000content\u0000summary，
 * 按 slug 排序后截断）。
 */
class WikiToolsRecordingTest {

    // ==================== Fake（对照 zzFakeWiki2） ====================

    static final class FakeWiki2 implements WikiPages {
        final Map<String, PageView> pages = new LinkedHashMap<>();
        final Map<String, RuntimeException> getErr = new LinkedHashMap<>();
        final Map<String, List<PageView>> searchResults = new LinkedHashMap<>();
        final Map<String, RuntimeException> searchErr = new LinkedHashMap<>();
        final Map<String, IndexOverviewView> indexViews = new LinkedHashMap<>();

        static String pageKey(String kb, String slug) {
            return kb + "|" + slug;
        }

        static String searchKey(String kb, String query) {
            return kb + "\0" + query;
        }

        @Override
        public PageView getPageBySlug(String kbId, String slug) {
            RuntimeException err = getErr.get(pageKey(kbId, slug));
            if (err != null) {
                throw err;
            }
            return pages.get(pageKey(kbId, slug));
        }

        @Override
        public List<PageView> searchPages(String kbId, String query, int limit) {
            RuntimeException err = searchErr.get(searchKey(kbId, query));
            if (err != null) {
                throw err;
            }
            if (searchResults.containsKey(searchKey(kbId, query))) {
                return searchResults.get(searchKey(kbId, query));
            }
            String q = query.toLowerCase();
            List<PageView> matched = new ArrayList<>();
            for (Map.Entry<String, PageView> e : pages.entrySet()) {
                if (!e.getKey().startsWith(kbId + "|")) {
                    continue;
                }
                PageView p = e.getValue();
                String hay = (p.slug() + "\0" + p.title() + "\0" + p.content() + "\0" + p.summary())
                        .toLowerCase();
                if (hay.contains(q)) {
                    matched.add(p);
                }
            }
            matched.sort(Comparator.comparing(PageView::slug));
            if (limit > 0 && matched.size() > limit) {
                return new ArrayList<>(matched.subList(0, limit));
            }
            return matched;
        }

        @Override
        public IndexOverviewView getIndexView(String kbId, int topK) {
            IndexOverviewView view = indexViews.get(kbId);
            return view != null ? view : new IndexOverviewView("", List.of());
        }

        // ---- 以下方法本测试族不用（对照 zzFakeWiki2 未覆盖即嵌入接口 panic） ----

        @Override
        public PageView createPage(PageView page, String editSource) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updatePage(PageView page, String editSource) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateAutoLinkedContent(PageView page, String editSource) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deletePage(String kbId, String slug, String editSource) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RepairResult repairContentLinks(String kbId, String slug, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void injectCrossLinks(String kbId, List<String> slugs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void rebuildIndexPage(String kbId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IssueView> listIssues(String kbId, String slug, String status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IssueView createIssue(IssueView issue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateIssueStatus(String issueId, String status) {
            throw new UnsupportedOperationException();
        }
    }

    static PageView page(String kb, String slug, String title, String pageType, String summary, String content) {
        PageView p = PageView.of(kb, slug);
        p.setTitle(title);
        p.setPageType(pageType);
        p.setSummary(summary);
        p.setContent(content);
        return p;
    }

    static List<String> listOf(String... items) {
        List<String> l = new ArrayList<>();
        for (String s : items) {
            l.add(s);
        }
        return l;
    }

    static KnowledgeTagsFetcher knTags = ids -> {
        Map<String, List<TagView>> out = new LinkedHashMap<>();
        for (String id : ids) {
            if ("d1".equals(id)) {
                out.put(id, List.of(new TagView("t1")));
            } else {
                out.put(id, List.of());
            }
        }
        return out;
    };

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

    private static ToolRequest req(String argsJson, int budget) {
        try {
            return new ToolRequest(RecordingSupport.PLAIN.readTree(argsJson), null,
                    ToolCancellation.LIVE, budget);
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

    // ==================== wiki_read_page ====================

    @Test
    void wikiReadPage() {
        FakeWiki2 wiki = new FakeWiki2();
        PageView a = page("kb1", "entity/a", "甲公司", "entity", "甲公司的摘要", "甲公司正文，提到关键词。");
        a.setAliases(listOf("别名甲"));
        a.setSourceRefs(listOf("d1|文档一", "d2"));
        a.setOutLinks(listOf("entity/b", "entity/ghost"));
        a.setInLinks(listOf("entity/b"));
        wiki.pages.put(FakeWiki2.pageKey("kb1", "entity/a"), a);

        PageView b = page("kb1", "entity/b", "乙产品", "entity",
                "乙产品摘要" + "长".repeat(200), "乙正文 [[entity/a]]。");
        b.setSourceRefs(listOf("d1"));
        wiki.pages.put(FakeWiki2.pageKey("kb1", "entity/b"), b);

        PageView a2 = page("kb2", "entity/a", "甲公司KB2", "entity", "kb2 摘要", "kb2 正文");
        a2.setSourceRefs(listOf("d9"));
        wiki.pages.put(FakeWiki2.pageKey("kb2", "entity/a"), a2);

        // 同一工具实例跨调用复用（对照探针 readTool 复用，seenLinks 会话级持久）
        WikiReadPageTool readTool = new WikiReadPageTool(wiki, null,
                List.of(WikiScope.kb("kb1"), WikiScope.kb("kb2")), new WikiRouteResolver());

        ToolResult res = readTool.execute(req("{\"slugs\":[\"entity/a\"]}"));
        assertToolResult("read_basic", res, rec("wiki_read_page_read_basic"));

        res = readTool.execute(req("{\"slug\":\"entity/b\",\"slugs\":[\"entity/b\",\"entity/a\"]}"));
        assertToolResult("read_slug_string", res, rec("wiki_read_page_read_slug_string"));

        res = readTool.execute(req("{\"slugs\":[\"entity/none\"]}"));
        assertToolResult("read_missing", res, rec("wiki_read_page_read_missing"));

        res = readTool.execute(req("{\"slugs\":[\"entity/b\",\"entity/none\"]}"));
        assertToolResult("read_mixed", res, rec("wiki_read_page_read_mixed"));

        res = readTool.execute(req("{}"));
        assertToolResult("read_empty", res, rec("wiki_read_page_read_empty"));
    }

    @Test
    void wikiReadPageErrors() {
        // read_kb_mismatch
        FakeWiki2 bad = new FakeWiki2();
        PageView badPage = page("kb1", "bad/kb", "错", "", "", "");
        badPage.setKnowledgeBaseId("kb9");
        bad.pages.put(FakeWiki2.pageKey("kb1", "bad/kb"), badPage);
        WikiReadPageTool badTool = new WikiReadPageTool(bad, null,
                List.of(WikiScope.kb("kb1")), new WikiRouteResolver());
        assertToolResult("read_kb_mismatch", badTool.execute(req("{\"slugs\":[\"bad/kb\"]}")),
                rec("wiki_read_page_read_kb_mismatch"));

        // read_service_err
        FakeWiki2 errWiki = new FakeWiki2();
        errWiki.getErr.put(FakeWiki2.pageKey("kb1", "entity/x"),
                new RuntimeException("db exploded"));
        WikiReadPageTool errTool = new WikiReadPageTool(errWiki, null,
                List.of(WikiScope.kb("kb1")), new WikiRouteResolver());
        assertToolResult("read_service_err", errTool.execute(req("{\"slugs\":[\"entity/x\"]}")),
                rec("wiki_read_page_read_service_err"));
    }

    @Test
    void wikiReadPageIndex() {
        // read_index
        FakeWiki2 wiki = new FakeWiki2();
        PageView index = page("kb1", "index", "目录", "index", "",
                "# 旧式目录\n## 应被裁剪的遗留目录\n- [[entity/a]]");
        wiki.pages.put(FakeWiki2.pageKey("kb1", "index"), index);
        PageView a = page("kb1", "entity/a", "甲", "entity", "甲摘要", "");
        a.setSourceRefs(listOf("d1"));
        wiki.pages.put(FakeWiki2.pageKey("kb1", "entity/a"), a);

        IndexOverviewView overview = new IndexOverviewView(
                "Wiki 导言\n## 旧目录\n[[entity/a]]",
                List.of(
                        new IndexGroupView("entity", 25, List.of(
                                new IndexEntryView("entity/a", "甲", "甲摘要"),
                                new IndexEntryView("entity/b", "", ""))),
                        new IndexGroupView("concept", 0, List.of())));
        wiki.indexViews.put("kb1", overview);

        WikiReadPageTool idxTool = new WikiReadPageTool(wiki, null,
                List.of(WikiScope.kb("kb1")), new WikiRouteResolver());
        assertToolResult("read_index", idxTool.execute(req("{\"slugs\":[\"index\"]}")),
                rec("wiki_read_page_read_index"));

        // read_empty_index
        FakeWiki2 emptyWiki = new FakeWiki2();
        PageView emptyIndex = page("kb1", "index", "目录", "index", "", "intro only");
        emptyWiki.pages.put(FakeWiki2.pageKey("kb1", "index"), emptyIndex);
        emptyWiki.indexViews.put("kb1", new IndexOverviewView("  ", List.of()));
        WikiReadPageTool emptyTool = new WikiReadPageTool(emptyWiki, null,
                List.of(WikiScope.kb("kb1")), new WikiRouteResolver());
        assertToolResult("read_empty_index", emptyTool.execute(req("{\"slugs\":[\"index\"]}")),
                rec("wiki_read_page_read_empty_index"));
    }

    @Test
    void wikiReadPageScope() {
        FakeWiki2 wiki = new FakeWiki2();
        PageView in = page("kb1", "entity/in", "在范围内", "entity", "s", "c");
        in.setSourceRefs(listOf("d1"));
        PageView out = page("kb1", "entity/out", "范围外", "entity", "s", "c");
        out.setSourceRefs(listOf("d9"));
        PageView uncited = page("kb1", "entity/uncited", "无出处", "entity", "s", "c");
        wiki.pages.put(FakeWiki2.pageKey("kb1", "entity/in"), in);
        wiki.pages.put(FakeWiki2.pageKey("kb1", "entity/out"), out);
        wiki.pages.put(FakeWiki2.pageKey("kb1", "entity/uncited"), uncited);

        WikiReadPageTool tool = new WikiReadPageTool(wiki, null,
                List.of(new WikiScope("kb1", listOf("d1"), null)), new WikiRouteResolver());
        assertToolResult("read_scope_doc_filter",
                tool.execute(req("{\"slugs\":[\"entity/in\",\"entity/out\",\"entity/uncited\"]}")),
                rec("wiki_read_page_read_scope_doc_filter"));

        // read_scope_tag_pass
        FakeWiki2 tagWiki = new FakeWiki2();
        PageView tagged = page("kb1", "entity/tagged", " tagged", "entity", "s", "c");
        tagged.setSourceRefs(listOf("d1"));
        tagWiki.pages.put(FakeWiki2.pageKey("kb1", "entity/tagged"), tagged);
        WikiReadPageTool tagTool = new WikiReadPageTool(tagWiki, knTags,
                List.of(new WikiScope("kb1", null, listOf("t1"))), new WikiRouteResolver());
        assertToolResult("read_scope_tag_pass",
                tagTool.execute(req("{\"slugs\":[\"entity/tagged\"]}")),
                rec("wiki_read_page_read_scope_tag_pass"));

        // read_scope_structural：doc scope 下 index 页被过滤
        FakeWiki2 structWiki = new FakeWiki2();
        PageView structIndex = page("kb1", "index", "目录", "index", "", "c");
        structWiki.pages.put(FakeWiki2.pageKey("kb1", "index"), structIndex);
        WikiReadPageTool structTool = new WikiReadPageTool(structWiki, null,
                List.of(new WikiScope("kb1", listOf("d1"), null)), new WikiRouteResolver());
        assertToolResult("read_scope_structural",
                structTool.execute(req("{\"slugs\":[\"index\"]}")),
                rec("wiki_read_page_read_scope_structural"));
    }

    @Test
    void wikiReadPageBudget() {
        FakeWiki2 wiki = new FakeWiki2();
        String bigBody1 = "甲".repeat(900);
        String bigBody2 = "乙".repeat(900);
        wiki.pages.put(FakeWiki2.pageKey("kb1", "entity/big1"),
                page("kb1", "entity/big1", "大1", "entity", "s1", bigBody1));
        wiki.pages.put(FakeWiki2.pageKey("kb1", "entity/big2"),
                page("kb1", "entity/big2", "大2", "entity", "s2", bigBody2));

        WikiReadPageTool tool = new WikiReadPageTool(wiki, null,
                List.of(WikiScope.kb("kb1")), new WikiRouteResolver());
        assertToolResult("read_budget",
                tool.execute(req("{\"slugs\":[\"entity/big1\",\"entity/big2\"]}", 1600)),
                rec("wiki_read_page_read_budget"));
        assertToolResult("read_budget_tiny",
                tool.execute(req("{\"slugs\":[\"entity/big1\",\"entity/big2\"]}", 700)),
                rec("wiki_read_page_read_budget_tiny"));
    }

    // ==================== wiki_search ====================

    private static FakeWiki2 searchSeed() {
        FakeWiki2 wiki = new FakeWiki2();
        PageView a = page("kb1", "entity/a", "甲公司", "entity", "甲摘要", "第一段讲甲公司。\n\n第二段讲别的。");
        a.setAliases(listOf("别名甲", "AliasA"));
        a.setSourceRefs(listOf("d1"));
        wiki.pages.put(FakeWiki2.pageKey("kb1", "entity/a"), a);

        wiki.pages.put(FakeWiki2.pageKey("kb1", "concept/rag"),
                page("kb1", "concept/rag", "RAG 概念", "concept", "RAG 摘要", "RAG 检索增强生成。"));

        PageView a2 = page("kb2", "entity/a", "甲公司KB2", "entity", "kb2 摘要", "kb2 的甲公司内容。");
        a2.setSourceRefs(listOf("d9"));
        wiki.pages.put(FakeWiki2.pageKey("kb2", "entity/a"), a2);
        return wiki;
    }

    @Test
    void wikiSearch() {
        FakeWiki2 wiki = searchSeed();
        // 同一实例跨调用复用（对照探针 searchTool 复用，seenSlugs 会话级持久）
        WikiSearchTool searchTool = new WikiSearchTool(wiki, null,
                List.of(WikiScope.kb("kb1"), WikiScope.kb("kb2")), new WikiRouteResolver());

        assertToolResult("search_basic", searchTool.execute(req("{\"queries\":[\"甲公司\"]}")),
                rec("wiki_search_search_basic"));
        assertToolResult("search_seen_dedupe",
                searchTool.execute(req("{\"queries\":[\"甲公司\",\"公司\"]}")),
                rec("wiki_search_search_seen_dedupe"));
        assertToolResult("search_empty",
                searchTool.execute(req("{\"queries\":[\"不存在的词xyz\"]}")),
                rec("wiki_search_search_empty"));
        assertToolResult("search_kb_restrict",
                searchTool.execute(req("{\"queries\":[\"甲公司\"],\"knowledge_base_id\":\"kb2\"}")),
                rec("wiki_search_search_kb_restrict"));
        assertToolResult("search_kb_out_of_scope",
                searchTool.execute(req("{\"queries\":[\"x\"],\"knowledge_base_id\":\"kb9\"}")),
                rec("wiki_search_search_kb_out_of_scope"));
        assertToolResult("search_missing", searchTool.execute(req("{}")),
                rec("wiki_search_search_missing"));
        assertToolResult("search_limit", searchTool.execute(req("{\"query\":\"甲公司\",\"limit\":1}")),
                rec("wiki_search_search_limit"));
    }

    @Test
    void wikiSearchErrors() {
        // search_service_err
        FakeWiki2 errWiki = new FakeWiki2();
        errWiki.searchErr.put(FakeWiki2.searchKey("kb1", "boom"),
                new RuntimeException("search backend down"));
        WikiSearchTool errTool = new WikiSearchTool(errWiki, null,
                List.of(WikiScope.kb("kb1")), new WikiRouteResolver());
        assertToolResult("search_service_err", errTool.execute(req("{\"queries\":[\"boom\"]}")),
                rec("wiki_search_search_service_err"));

        // search_partial_err
        FakeWiki2 partialWiki = new FakeWiki2();
        PageView a2 = page("kb2", "entity/a", "甲", "entity", "s", "c");
        a2.setSourceRefs(listOf("d9"));
        partialWiki.pages.put(FakeWiki2.pageKey("kb2", "entity/a"), a2);
        partialWiki.searchErr.put(FakeWiki2.searchKey("kb1", "甲"),
                new RuntimeException("kb1 down"));
        WikiSearchTool partialTool = new WikiSearchTool(partialWiki, null,
                List.of(WikiScope.kb("kb1"), WikiScope.kb("kb2")), new WikiRouteResolver());
        assertToolResult("search_partial_err", partialTool.execute(req("{\"queries\":[\"甲\"]}")),
                rec("wiki_search_search_partial_err"));

        // search_kb_mismatch
        FakeWiki2 mismatchWiki = new FakeWiki2();
        PageView bad = page("kb1", "bad/kb", "错", "entity", "s", "甲公司");
        bad.setKnowledgeBaseId("kb9");
        mismatchWiki.pages.put(FakeWiki2.pageKey("kb1", "bad/kb"), bad);
        WikiSearchTool mismatchTool = new WikiSearchTool(mismatchWiki, null,
                List.of(WikiScope.kb("kb1")), new WikiRouteResolver());
        assertToolResult("search_kb_mismatch",
                mismatchTool.execute(req("{\"queries\":[\"甲公司\"]}")),
                rec("wiki_search_search_kb_mismatch"));
    }

    @Test
    void wikiSearchScope() {
        FakeWiki2 wiki = new FakeWiki2();
        PageView tagged = page("kb1", "entity/tagged", "t", "entity", "s", "甲公司相关。");
        tagged.setSourceRefs(listOf("d1"));
        PageView bare = page("kb1", "entity/bare", "b", "entity", "s", "甲公司相关。");
        wiki.pages.put(FakeWiki2.pageKey("kb1", "entity/tagged"), tagged);
        wiki.pages.put(FakeWiki2.pageKey("kb1", "entity/bare"), bare);

        WikiSearchTool tool = new WikiSearchTool(wiki, knTags,
                List.of(new WikiScope("kb1", null, listOf("t1"))), new WikiRouteResolver());
        assertToolResult("search_scope_filter", tool.execute(req("{\"queries\":[\"甲公司\"]}")),
                rec("wiki_search_search_scope_filter"));
    }
}
