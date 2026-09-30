package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.llm.ToolResult;

/**
 * wiki 小件（wiki_support 行为 + write/replace/delete/rename/flag/read_issue/update_issue）
 * 的 Go 实录回放。FakeWiki 镜像 /tmp/toolrec45b 探针的 zzFakeWiki。
 */
class WikiSmallRecordingTest {

    // ==================== FakeWiki（镜像 zzFakeWiki） ====================

    static final class FakeWiki implements WikiPages {
        final Map<String, PageView> pages = new LinkedHashMap<>();
        final Map<String, List<IssueView>> issues = new LinkedHashMap<>();
        final Map<String, RuntimeException> getErr = new LinkedHashMap<>();
        final Map<String, RuntimeException> updateErr = new LinkedHashMap<>();
        final Map<String, RuntimeException> deleteErr = new LinkedHashMap<>();
        final Map<String, RuntimeException> createErr = new LinkedHashMap<>();
        final Map<String, RepairResult> repair = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();

        static String key(String kb, String slug) {
            return kb + "|" + slug;
        }

        @Override
        public PageView getPageBySlug(String kbId, String slug) {
            calls.add("get:" + key(kbId, slug));
            RuntimeException err = getErr.get(key(kbId, slug));
            if (err != null) {
                throw err;
            }
            return pages.get(key(kbId, slug)); // null = not found
        }

        @Override
        public PageView createPage(PageView page, String editSource) {
            calls.add("create:" + key(page.knowledgeBaseId(), page.slug()));
            RuntimeException err = createErr.get(key(page.knowledgeBaseId(), page.slug()));
            if (err != null) {
                throw err;
            }
            PageView cp = page.copy();
            pages.put(key(page.knowledgeBaseId(), page.slug()), cp);
            return cp;
        }

        @Override
        public void updatePage(PageView page, String editSource) {
            calls.add("update:" + key(page.knowledgeBaseId(), page.slug()));
            RuntimeException err = updateErr.get(key(page.knowledgeBaseId(), page.slug()));
            if (err != null) {
                throw err;
            }
            pages.put(key(page.knowledgeBaseId(), page.slug()), page.copy());
        }

        @Override
        public void updateAutoLinkedContent(PageView page, String editSource) {
            calls.add("auto:" + key(page.knowledgeBaseId(), page.slug()));
            RuntimeException err = updateErr.get(key(page.knowledgeBaseId(), page.slug()));
            if (err != null) {
                throw err;
            }
            pages.put(key(page.knowledgeBaseId(), page.slug()), page.copy());
        }

        @Override
        public void deletePage(String kbId, String slug, String editSource) {
            calls.add("delete:" + key(kbId, slug));
            RuntimeException err = deleteErr.get(key(kbId, slug));
            if (err != null) {
                throw err;
            }
            pages.remove(key(kbId, slug));
        }

        @Override
        public RepairResult repairContentLinks(String kbId, String slug, String content) {
            calls.add("repair:" + key(kbId, slug));
            RepairResult out = repair.get(content);
            return out != null ? out : new RepairResult(content, false);
        }

        @Override
        public void injectCrossLinks(String kbId, List<String> slugs) {
            calls.add("inject:" + kbId + ":" + String.join(",", slugs));
        }

        @Override
        public void rebuildIndexPage(String kbId) {
            calls.add("rebuild:" + kbId);
        }

        @Override
        public List<IssueView> listIssues(String kbId, String slug, String status) {
            calls.add("issues:" + kbId + ":" + slug + ":" + status);
            List<IssueView> out = new ArrayList<>();
            for (IssueView issue : issues.getOrDefault(kbId, List.of())) {
                if (slug != null && !slug.isEmpty() && !slug.equals(issue.slug())) {
                    continue;
                }
                if (status != null && !status.isEmpty() && !status.equals(issue.status())) {
                    continue;
                }
                out.add(issue);
            }
            return out;
        }

        @Override
        public IssueView createIssue(IssueView issue) {
            calls.add("createIssue:" + issue.knowledgeBaseId() + ":" + issue.slug());
            RuntimeException err = createErr.get("issue");
            if (err != null) {
                throw err;
            }
            IssueView cp = copyIssue(issue);
            issues.computeIfAbsent(issue.knowledgeBaseId(), k -> new ArrayList<>()).add(cp);
            return cp;
        }

        @Override
        public void updateIssueStatus(String issueId, String status) {
            calls.add("updateIssue:" + issueId + ":" + status);
            RuntimeException err = createErr.get("updateIssue");
            if (err != null) {
                throw err;
            }
            for (List<IssueView> list : issues.values()) {
                for (IssueView issue : list) {
                    if (issue.id().equals(issueId)) {
                        issue.setStatus(status);
                    }
                }
            }
        }
    }

    static IssueView copyIssue(IssueView issue) {
        IssueView cp = new IssueView();
        cp.setId(issue.id());
        cp.setTenantId(issue.tenantId());
        cp.setKnowledgeBaseId(issue.knowledgeBaseId());
        cp.setSlug(issue.slug());
        cp.setIssueType(issue.issueType());
        cp.setDescription(issue.description());
        cp.setSuspectedKnowledgeIds(new ArrayList<>(issue.suspectedKnowledgeIds()));
        cp.setStatus(issue.status());
        cp.setReportedBy(issue.reportedBy());
        cp.setCreatedAt(issue.createdAt());
        cp.setUpdatedAt(issue.updatedAt());
        cp.setDeletedAtValid(issue.deletedAtValid());
        cp.setDeletedAt(issue.deletedAt());
        return cp;
    }

    static IssueView newIssue(String id, String kb, String slug, String typ, String desc, String status) {
        IssueView issue = new IssueView();
        issue.setId(id);
        issue.setTenantId(10002);
        issue.setKnowledgeBaseId(kb);
        issue.setSlug(slug);
        issue.setIssueType(typ);
        issue.setDescription(desc);
        issue.setStatus(status);
        issue.setSuspectedKnowledgeIds(new ArrayList<>(List.of("d1", "d2")));
        issue.setReportedBy("wiki-researcher-agent");
        issue.setCreatedAt("2026-03-15T08:30:00Z");
        issue.setUpdatedAt("2026-03-15T08:30:00Z");
        return issue;
    }

    static PageView page(String kb, String slug, String title, String content) {
        PageView p = PageView.of(kb, slug);
        p.setTitle(title);
        p.setContent(content);
        return p;
    }

    // ==================== 回放框架 ====================

    private static JsonNode rec(String name) {
        try {
            String json = (String) GoRecording45B.class.getField("R_" + name.toUpperCase(java.util.Locale.ROOT)).get(null);
            return GoRecording45B.rec(json);
        } catch (ReflectiveOperationException e) {
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

    // ==================== wiki_support ====================

    @Test
    void slugNormalization() {
        String[] cases = {
                "wiki_support_slug_ok", "wiki_support_slug_spaces", "wiki_support_slug_cjk",
                "wiki_support_slug_empty", "wiki_support_slug_double_slash", "wiki_support_slug_leading_slash",
                "wiki_support_slug_trailing_slash", "wiki_support_slug_bad_char",
                "wiki_support_slug_bad_char_upper_after_lower",
        };
        for (String name : cases) {
            JsonNode r = rec(name);
            String in = r.get("in").asText();
            String out;
            String error = "";
            try {
                out = WikiSlugs.normalizeAndValidateWikiSlug(in);
            } catch (IllegalArgumentException e) {
                out = "";
                error = e.getMessage();
            }
            assertThat(error).as("%s error", name).isEqualTo(r.get("error").asText());
            assertThat(out).as("%s out", name).isEqualTo(r.get("out").asText());
        }
    }

    @Test
    void resolveUniqueAndCreateKb() {
        // resolve_unique_hit
        FakeWiki wiki = resolveWikiSeed();
        WikiRouteResolver routes = new WikiRouteResolver();
        ResolvedPage hit = WikiRouteResolver.resolveUniqueWikiPage(wiki, "entity/a", List.of("kb1"), routes);
        JsonNode r = rec("wiki_support_resolve_unique_hit");
        assertThat(hit.kbId()).isEqualTo(r.get("kb").asText());
        assertThat(hit.page().title()).isEqualTo(r.get("title").asText());

        // ambiguous（录制在探针里以 Contains 断言记录语义：直接复演错误文案）
        assertThatThrownByWiki(() -> WikiRouteResolver.resolveUniqueWikiPage(
                resolveWikiSeed(), "entity/a", List.of("kb1", "kb2"), new WikiRouteResolver()),
                "wiki page exists in multiple knowledge bases: slug entity/a belongs to kb1, kb2");

        JsonNode missing = rec("wiki_support_resolve_unique_missing");
        assertThatThrownByWiki(() -> WikiRouteResolver.resolveUniqueWikiPage(
                resolveWikiSeed(), "missing/x", List.of("kb1"), new WikiRouteResolver()), missing.get("error").asText());

        JsonNode mismatch = rec("wiki_support_resolve_unique_kb_mismatch");
        assertThatThrownByWiki(() -> WikiRouteResolver.resolveUniqueWikiPage(
                resolveWikiSeed(), "bad/kb", List.of("kb3"), new WikiRouteResolver()), mismatch.get("error").asText());

        FakeWiki errWiki = resolveWikiSeed();
        errWiki.getErr.put(FakeWiki.key("kb1", "missing/x"), new RuntimeException("db down"));
        JsonNode svcErr = rec("wiki_support_resolve_unique_service_err");
        assertThatThrownByWiki(() -> WikiRouteResolver.resolveUniqueWikiPage(
                errWiki, "missing/x", List.of("kb1"), new WikiRouteResolver()), svcErr.get("error").asText());

        // create_kb_provenance：remember("new/page","kb2") → kb2
        WikiRouteResolver r1 = new WikiRouteResolver();
        r1.remember("new/page", "kb2");
        JsonNode prov = rec("wiki_support_create_kb_provenance");
        assertThat(WikiRouteResolver.resolveWikiCreateKb("new/page", List.of("kb1", "kb2"), r1, null))
                .isEqualTo(prov.get("out").asText());

        WikiRouteResolver r2 = new WikiRouteResolver();
        r2.remember("new/page", "kb1");
        r2.remember("new/page", "kb2");
        JsonNode conflict = rec("wiki_support_create_kb_conflict");
        assertThatThrownByWiki(() -> WikiRouteResolver.resolveWikiCreateKb("new/page", List.of("kb1", "kb2"), r2, null),
                conflict.get("error").asText());

        JsonNode single = rec("wiki_support_create_kb_single_scope");
        assertThat(WikiRouteResolver.resolveWikiCreateKb("new/page", List.of("kb1"), new WikiRouteResolver(), null))
                .isEqualTo(single.get("out").asText());

        JsonNode multi = rec("wiki_support_create_kb_multi_scope");
        assertThatThrownByWiki(() -> WikiRouteResolver.resolveWikiCreateKb(
                "new/page", List.of("kb1", "kb2"), new WikiRouteResolver(), null), multi.get("error").asText());
    }

    private static FakeWiki resolveWikiSeed() {
        FakeWiki wiki = new FakeWiki();
        PageView a = page("kb1", "entity/a", "A", "正文 [[entity/b]] 结尾");
        a.setInLinks(new ArrayList<>(List.of("entity/b")));
        a.setOutLinks(new ArrayList<>(List.of("entity/b")));
        wiki.pages.put(FakeWiki.key("kb1", "entity/a"), a);
        wiki.pages.put(FakeWiki.key("kb2", "entity/a"), page("kb2", "entity/a", "A2", ""));
        wiki.pages.put(FakeWiki.key("kb1", "entity/b"),
                page("kb1", "entity/b", "B", "页B [[entity/a]] 与 [[entity/a|别名A]]"));
        PageView bad = page("kb3", "bad/kb", "错", "");
        bad.setKnowledgeBaseId("kb9"); // 服务返回的 KB 与 scope 不符
        wiki.pages.put(FakeWiki.key("kb3", "bad/kb"), bad);
        return wiki;
    }

    private static void assertThatThrownByWiki(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
        org.assertj.core.api.Assertions.assertThatThrownBy(call)
                .isInstanceOf(RuntimeException.class)
                .hasMessage(message);
    }

    @Test
    void resolveIssueAndNamespace() {
        FakeWiki wiki = new FakeWiki();
        wiki.issues.put("kb1", new ArrayList<>(List.of(newIssue("i1", "kb1", "entity/a", "mixed_entities", "两个产品混在一起", "pending"))));
        wiki.issues.put("kb2", new ArrayList<>(List.of(newIssue("i1", "kb2", "entity/a", "out_of_date", "内容过期", "pending"))));
        wiki.issues.put("kb3", new ArrayList<>(List.of(newIssue("i2", "kb3", "entity/a", "other", "别的", "resolved"))));

        IssueView hit = WikiRouteResolver.resolveWikiIssue(wiki, "i2", List.of("kb1", "kb3"));
        JsonNode r = rec("wiki_support_resolve_issue_hit");
        assertThat(hit.id()).isEqualTo(r.get("issue_id").asText());
        assertThat(hit.slug()).isEqualTo(r.get("slug").asText());

        JsonNode ambiguous = rec("wiki_support_resolve_issue_ambiguous");
        assertThatThrownByWiki(() -> WikiRouteResolver.resolveWikiIssue(wiki, "i1", List.of("kb1", "kb2")),
                ambiguous.get("error").asText());

        JsonNode miss = rec("wiki_support_resolve_issue_missing");
        assertThatThrownByWiki(() -> WikiRouteResolver.resolveWikiIssue(wiki, "i9", List.of("kb1")),
                miss.get("error").asText());

        assertThat(WikiSlugs.isSummaryNamespace("summary/abc")).isTrue();
        assertThat(WikiSlugs.isSummaryNamespace("entity/abc")).isFalse();
    }

    // ==================== 工具 execute 回放 ====================

    @Test
    void wikiWritePage() {
        // create：repair 生效 + aliases
        FakeWiki wiki2 = new FakeWiki();
        wiki2.repair.put("正文 [[garbled-uuid]] 结尾", new RepairResult("正文 [[entity/a]] 结尾", true));
        var write1 = new WikiWritePageTool(wiki2, List.of("kb1"), null, new WikiRouteResolver());
        JsonNode r = rec("wiki_write_page_create");
        ToolResult result = write1.execute(ToolRequest.of(RecordingSupport.readTree(r.get("args").asText())));
        assertToolResult("create", result, r);

        // update（页已存在；无 aliases/source_refs）
        PageView exist = page("kb1", "entity/exist", "旧", "旧文");
        exist.setSummary("旧摘要");
        exist.setPageType("entity");
        exist.setAliases(new ArrayList<>(List.of("keep")));
        wiki2.pages.put(FakeWiki.key("kb1", "entity/exist"), exist);
        var write2 = new WikiWritePageTool(wiki2, List.of("kb1", "kb2"), null, new WikiRouteResolver());
        r = rec("wiki_write_page_update");
        result = write2.execute(ToolRequest.of(RecordingSupport.readTree(r.get("args").asText())));
        assertToolResult("update", result, r);

        // summary 拒绝 ×2 / 缺字段 / 多 KB 无解
        for (String name : new String[]{
                "wiki_write_page_reject_summary_ns", "wiki_write_page_reject_summary_type",
                "wiki_write_page_missing_fields", "wiki_write_page_create_multi_kb_unresolved"}) {
            r = rec(name);
            result = write2.execute(ToolRequest.of(RecordingSupport.readTree(r.get("args").asText())));
            assertToolResult(name, result, r);
        }

        // source_refs 富化 + 路由 + 悬空 ref
        StubKnowledge kn = new StubKnowledge();
        kn.byId.put("d1", new SearchAuth.KnowledgeView("d1", "kb1", "源文档一", ""));
        kn.byId.put("d2", new SearchAuth.KnowledgeView("d2", "kb1", "", ""));
        var write3 = new WikiWritePageTool(wiki2, List.of("kb1", "kb2"), kn, new WikiRouteResolver());
        r = rec("wiki_write_page_source_refs_enrich");
        result = write3.execute(ToolRequest.of(RecordingSupport.readTree(r.get("args").asText())));
        assertToolResult("source_refs_enrich", result, r);
        PageView persisted = wiki2.pages.get(FakeWiki.key("kb1", "entity/refed"));
        assertThat(persisted.sourceRefs()).containsExactly("d1|源文档一", "d2", "d1|已有");

        r = rec("wiki_write_page_source_refs_dangling");
        result = write3.execute(ToolRequest.of(RecordingSupport.readTree(r.get("args").asText())));
        assertToolResult("source_refs_dangling", result, r);
    }

    /** 对照探针 zzStubKnowledgeSvc（只 byIdOnly；tags 不在本组用）。 */
    static final class StubKnowledge implements SearchAuth.KnowledgeScopeReader {
        final Map<String, SearchAuth.KnowledgeView> byId = new LinkedHashMap<>();

        @Override
        public SearchAuth.KnowledgeView byIdOnly(String id) {
            return byId.get(id);
        }

        @Override
        public Map<String, List<SearchAuth.TagView>> fetchTags(List<String> knowledgeIds) {
            return Map.of();
        }
    }

    @Test
    void wikiReplaceText() {
        FakeWiki wiki3 = new FakeWiki();
        wiki3.pages.put(FakeWiki.key("kb1", "entity/r"), page("kb1", "entity/r", "R", "甲说旧词，乙也说旧词。"));
        PageView long_ = page("kb1", "entity/long", "L", "头" + "长".repeat(100) + "尾");
        wiki3.pages.put(FakeWiki.key("kb1", "entity/long"), long_);
        var tool = new WikiReplaceTextTool(wiki3, List.of("kb1"), null, new WikiRouteResolver());

        for (String name : new String[]{
                "wiki_replace_text_multi_replace", "wiki_replace_text_not_found",
                "wiki_replace_text_page_missing", "wiki_replace_text_preview_truncate"}) {
            JsonNode r = rec(name);
            ToolResult result = tool.execute(ToolRequest.of(RecordingSupport.readTree(r.get("args").asText())));
            assertToolResult(name, result, r);
        }
    }

    @Test
    void wikiDeletePage() {
        FakeWiki wiki4 = new FakeWiki();
        PageView gone = page("kb1", "entity/gone", "将删", "");
        gone.setInLinks(new ArrayList<>(List.of("entity/in1", "entity/in2")));
        wiki4.pages.put(FakeWiki.key("kb1", "entity/gone"), gone);
        wiki4.pages.put(FakeWiki.key("kb1", "entity/in1"), page("kb1", "entity/in1", "入链1", "见 [[entity/gone]] 页"));
        wiki4.pages.put(FakeWiki.key("kb1", "entity/in2"),
                page("kb1", "entity/in2", "入链2", "见 [[entity/gone|被删页]] 与 [[entity/gone]]"));
        var del = new WikiDeletePageTool(wiki4, List.of("kb1"), new WikiRouteResolver());

        JsonNode r = rec("wiki_delete_page_with_inlinks");
        ToolResult result = del.execute(ToolRequest.of(RecordingSupport.readTree(r.get("args").asText())));
        assertToolResult("with_inlinks", result, r);

        r = rec("wiki_delete_page_page_missing");
        result = del.execute(ToolRequest.of(RecordingSupport.readTree(r.get("args").asText())));
        assertToolResult("page_missing", result, r);

        // 入链更新失败 → 回滚错误聚合
        FakeWiki wiki5 = new FakeWiki();
        PageView gone5 = page("kb1", "entity/gone", "将删", "");
        gone5.setInLinks(new ArrayList<>(List.of("entity/in1")));
        wiki5.pages.put(FakeWiki.key("kb1", "entity/gone"), gone5);
        wiki5.pages.put(FakeWiki.key("kb1", "entity/in1"), page("kb1", "entity/in1", "入链1", "见 [[entity/gone]]"));
        wiki5.updateErr.put(FakeWiki.key("kb1", "entity/in1"), new RuntimeException("write conflict"));
        var del5 = new WikiDeletePageTool(wiki5, List.of("kb1"), new WikiRouteResolver());
        r = rec("wiki_delete_page_inlink_update_fail");
        result = del5.execute(ToolRequest.of(RecordingSupport.readTree(r.get("args").asText())));
        assertToolResult("inlink_update_fail", result, r);
    }

    @Test
    void wikiRenamePage() {
        FakeWiki wiki6 = new FakeWiki();
        PageView old = page("kb1", "entity/old", "旧名", "旧页正文");
        old.setPageType("entity");
        old.setInLinks(new ArrayList<>(List.of("entity/ref")));
        wiki6.pages.put(FakeWiki.key("kb1", "entity/old"), old);
        wiki6.pages.put(FakeWiki.key("kb1", "entity/ref"),
                page("kb1", "entity/ref", "引用页", "链到 [[entity/old]] 与 [[entity/old|旧名]]"));
        var ren = new WikiRenamePageTool(wiki6, List.of("kb1"), new WikiRouteResolver());

        JsonNode r = rec("wiki_rename_page_cascade");
        ToolResult result = ren.execute(ToolRequest.of(RecordingSupport.readTree(r.get("args").asText())));
        assertToolResult("cascade", result, r);

        r = rec("wiki_rename_page_same_slug");
        result = ren.execute(ToolRequest.of(RecordingSupport.readTree(r.get("args").asText())));
        assertToolResult("same_slug", result, r);
    }

    @Test
    void wikiFlagIssue() {
        FakeWiki wiki7 = new FakeWiki();
        PageView polluted = page("kb1", "entity/polluted", "污染页", "");
        polluted.setTenantId(10002);
        wiki7.pages.put(FakeWiki.key("kb1", "entity/polluted"), polluted);
        var flag = new WikiFlagIssueTool(wiki7, List.of("kb1"), new WikiRouteResolver());

        for (String name : new String[]{"wiki_flag_issue_plain", "wiki_flag_issue_bad_slug"}) {
            JsonNode r = rec(name);
            ToolResult result = flag.execute(ToolRequest.of(RecordingSupport.readTree(r.get("args").asText())));
            assertToolResult(name, result, r);
        }
    }

    @Test
    void wikiReadAndUpdateIssue() {
        FakeWiki wiki8 = new FakeWiki();
        wiki8.issues.put("kb1", new ArrayList<>(List.of(newIssue("i1", "kb1", "entity/a", "mixed_entities", "描述文本", "pending"))));
        var read = new WikiReadIssueTool(wiki8, List.of("kb1"));

        for (String name : new String[]{
                "wiki_read_issue_by_id", "wiki_read_issue_by_slug",
                "wiki_read_issue_by_slug_empty", "wiki_read_issue_neither", "wiki_read_issue_id_out_of_scope"}) {
            JsonNode r = rec(name);
            ToolResult result = read.execute(ToolRequest.of(RecordingSupport.readTree(r.get("args").asText())));
            assertToolResult(name, result, r);
        }

        var upd = new WikiUpdateIssueTool(wiki8, List.of("kb1"));
        for (String name : new String[]{"wiki_update_issue_ok", "wiki_update_issue_out_of_scope", "wiki_update_issue_missing_status"}) {
            JsonNode r = rec(name);
            ToolResult result = upd.execute(ToolRequest.of(RecordingSupport.readTree(r.get("args").asText())));
            assertToolResult(name, result, r);
        }
    }
}
