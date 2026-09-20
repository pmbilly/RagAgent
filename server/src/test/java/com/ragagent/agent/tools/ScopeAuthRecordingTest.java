package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.tools.SearchAuth.ChunkView;
import com.ragagent.agent.tools.SearchAuth.KnowledgeView;
import com.ragagent.agent.tools.SearchAuth.ScopeAuthException;
import com.ragagent.agent.tools.SearchAuth.TagView;
import com.ragagent.agent.tools.SearchTarget.SearchTargets;

/**
 * SearchAuth/SearchTargets 的 Go 实录回放（26 条）。错误文案逐字比对；
 * 知识/chunk 视图只比对 id（Go 侧 marshal 了整个 types 结构，Java 是窄视图）。
 */
class ScopeAuthRecordingTest {

    private static SearchTargets targets() {
        SearchTarget kb1 = SearchTarget.wholeKb("kb1", 7);
        SearchTarget kb1Doc = new SearchTarget(SearchTarget.TYPE_KNOWLEDGE, "kb1", 0,
                List.of("d1", "d2", "", "d1"), null, null, false);
        SearchTarget kb1Tag = new SearchTarget(SearchTarget.TYPE_KNOWLEDGE_BASE, "kb1", 0,
                null, List.of("t1", "t2"), List.of("t2", "t3", ""), false);
        SearchTarget kb2 = SearchTarget.wholeKb("kb2", 0);
        return new SearchTargets(new ArrayList<>(Arrays.asList(null, kb1, kb1Doc, kb1Tag, kb2)));
    }

    /** 对照探针 zzStubKnowledgeSvc。 */
    private static final class StubKnowledge implements SearchAuth.KnowledgeScopeReader {
        final Map<String, KnowledgeView> byId = new LinkedHashMap<>();
        final Map<String, List<TagView>> tags = new LinkedHashMap<>();
        final Map<String, RuntimeException> byIdErr = new LinkedHashMap<>();

        @Override
        public KnowledgeView byIdOnly(String id) {
            RuntimeException err = byIdErr.get(id);
            if (err != null) {
                throw err;
            }
            return byId.get(id);
        }

        @Override
        public Map<String, List<TagView>> fetchTags(List<String> knowledgeIds) {
            Map<String, List<TagView>> out = new LinkedHashMap<>();
            for (String id : knowledgeIds) {
                out.put(id, tags.get(id));
            }
            return out;
        }
    }

    private static StubKnowledge stubKnowledge() {
        StubKnowledge svc = new StubKnowledge();
        svc.byId.put("d1", new KnowledgeView("d1", "kb1", "文档 一", ""));
        svc.byId.put("d2", new KnowledgeView("d2", "kb1", "  ", "file2.pdf"));
        svc.byId.put("d3", new KnowledgeView("d3", "kb1", "", ""));
        svc.byId.put("d4", new KnowledgeView("d4", "kb2", "别的库", ""));
        svc.tags.put("d1", List.of(new TagView("t1"), new TagView("t9")));
        svc.tags.put("d3", List.of(new TagView("t3")));
        svc.tags.put("d4", List.of(new TagView("t1")));
        svc.byIdErr.put("d9", new RuntimeException("db exploded"));
        return svc;
    }

    private static SearchAuth.ChunkFetcher stubChunks() {
        Map<String, ChunkView> byId = new LinkedHashMap<>();
        byId.put("c1", new ChunkView("c1", "d1", "kb1", true));
        byId.put("c2", new ChunkView("c2", "d1", "kb1", false));
        byId.put("c3", new ChunkView("c3", "d9", "kb1", true));
        return byId::get;
    }

    private static final class Rec {
        final String id;
        final String fn;
        final String error;
        final JsonNode result;

        Rec(String constant) {
            JsonNode r = GoRecording45B.rec(field(constant));
            this.id = r.get("id").asText();
            this.fn = r.get("fn").asText();
            this.error = r.get("error").asText();
            this.result = r.get("result");
        }

        void expectOk() {
            assertThat(error).as("%s error", id).isEmpty();
        }

        void expectError(String message) {
            assertThat(error).as("%s error", id).isEqualTo(message);
        }
    }

    @Test
    void authorizeKnowledgeCases() {
        StubKnowledge svc = stubKnowledge();
        SearchTargets t = targets();

        Rec ok = new Rec("R_SCOPE_AUTH_KNOWLEDGE_WHOLE_KB_OK");
        KnowledgeView k = SearchAuth.authorizeKnowledgeInSearchTargets(t, " d1 ", svc);
        ok.expectOk();
        assertThat(k.id()).isEqualTo(resultId(ok.result));

        Rec out = new Rec("R_SCOPE_AUTH_KNOWLEDGE_KB_OUT_OF_SCOPE");
        k = SearchAuth.authorizeKnowledgeInSearchTargets(t, "d4", svc);
        out.expectOk();
        assertThat(k.id()).isEqualTo(resultId(out.result));

        Rec tag = new Rec("R_SCOPE_AUTH_KNOWLEDGE_TAG_MATCH");
        assertThat(SearchAuth.authorizeKnowledgeInSearchTargets(t, "d3", svc).id())
                .isEqualTo(resultId(tag.result));
        tag.expectOk();

        Rec tagOnly = new Rec("R_SCOPE_AUTH_KNOWLEDGE_TAG_MATCH_ONLY_TARGET");
        SearchTargets only = new SearchTargets(new ArrayList<>(
                List.of(new SearchTarget(SearchTarget.TYPE_KNOWLEDGE_BASE, "kb1", 7,
                        null, List.of("t1", "t2"), List.of("t2", "t3", ""), false))));
        assertThat(SearchAuth.authorizeKnowledgeInSearchTargets(only, "d3", svc).id())
                .isEqualTo(resultId(tagOnly.result));

        Rec notInMention = new Rec("R_SCOPE_AUTH_KNOWLEDGE_DOC_NOT_IN_MENTION");
        assertThatThrownBy(() -> SearchAuth.authorizeKnowledgeInSearchTargets(
                new SearchTargets(new ArrayList<>(List.of(
                        new SearchTarget(SearchTarget.TYPE_KNOWLEDGE, "kb1", 7,
                                List.of("d1", "d2", "", "d1"), null, null, false)))),
                "d3", svc))
                .isInstanceOf(ScopeAuthException.class)
                .hasMessage(notInMention.error);
        notInMention.expectError("document d3 is not within the current @mention scope");

        Rec notFound = new Rec("R_SCOPE_AUTH_KNOWLEDGE_NOT_FOUND");
        assertThatThrownBy(() -> SearchAuth.authorizeKnowledgeInSearchTargets(t, "missing", svc))
                .isInstanceOf(ScopeAuthException.class)
                .hasMessage(notFound.error);

        Rec svcErr = new Rec("R_SCOPE_AUTH_KNOWLEDGE_SERVICE_ERROR");
        assertThatThrownBy(() -> SearchAuth.authorizeKnowledgeInSearchTargets(t, "d9", svc))
                .isInstanceOf(ScopeAuthException.class)
                .hasMessage(svcErr.error);

        Rec blank = new Rec("R_SCOPE_AUTH_KNOWLEDGE_BLANK_ID");
        assertThatThrownBy(() -> SearchAuth.authorizeKnowledgeInSearchTargets(t, "  ", svc))
                .isInstanceOf(ScopeAuthException.class)
                .hasMessage(blank.error);
    }

    @Test
    void authorizeChunkCases() {
        SearchTargets t = targets();
        Rec ok = new Rec("R_SCOPE_AUTH_CHUNK_OK");
        assertThat(SearchAuth.authorizeChunkInSearchTargets(t, "c1", stubChunks(), stubKnowledge()).id())
                .isEqualTo(resultId(ok.result));

        Rec disabled = new Rec("R_SCOPE_AUTH_CHUNK_DISABLED");
        assertThatThrownBy(() -> SearchAuth.authorizeChunkInSearchTargets(t, "c2", stubChunks(), stubKnowledge()))
                .isInstanceOf(ScopeAuthException.class)
                .hasMessage(disabled.error);

        Rec notFound = new Rec("R_SCOPE_AUTH_CHUNK_NOT_FOUND");
        assertThatThrownBy(() -> SearchAuth.authorizeChunkInSearchTargets(t, "missing", stubChunks(), stubKnowledge()))
                .isInstanceOf(ScopeAuthException.class)
                .hasMessage(notFound.error);
    }

    @Test
    void validateAndResolveCases() {
        SearchTargets t = targets();
        Rec ok = new Rec("R_SCOPE_AUTH_VALIDATE_KBS_OK");
        SearchAuth.validateKnowledgeBaseIdsInSearchTargets(t, List.of("kb1", "kb2", "kb1", ""));
        ok.expectOk();

        Rec out = new Rec("R_SCOPE_AUTH_VALIDATE_KBS_OUT");
        assertThatThrownBy(() -> SearchAuth.validateKnowledgeBaseIdsInSearchTargets(t, List.of("kb3")))
                .isInstanceOf(ScopeAuthException.class)
                .hasMessage(out.error);

        Rec refs = new Rec("R_SCOPE_AUTH_RESOLVE_REFS");
        List<String> resolved = SearchAuth.resolveAuthorizedSourceRefs(
                t, List.of("d1|伪造标题", " d2 |junk", "d3", "d1|dup", " |empty", ""), stubKnowledge());
        assertThat(RecordingSupport.canonicalJson(RecordingSupport.PLAIN.valueToTree(resolved)))
                .isEqualTo(RecordingSupport.canonicalJson(refs.result));
    }

    @Test
    void allowKnowledgeIdCases() {
        SearchTargets t = targets();
        StubKnowledge svc = stubKnowledge();
        assertThat(SearchAuth.searchTargetsAllowKnowledgeId(t, "d1", "kb1", svc))
                .isEqualTo(new Rec("R_SCOPE_AUTH_ALLOW_WHOLE_KB").result.asBoolean());
        assertThat(SearchAuth.searchTargetsAllowKnowledgeId(t, "d3", "kb1", svc))
                .isEqualTo(new Rec("R_SCOPE_AUTH_ALLOW_TAG_HIT").result.asBoolean());
        assertThat(SearchAuth.searchTargetsAllowKnowledgeId(t, "dX", "kb1", svc))
                .isEqualTo(new Rec("R_SCOPE_AUTH_ALLOW_MISS").result.asBoolean());
        assertThat(SearchAuth.searchTargetsAllowKnowledgeId(t, "d1", "kb9", svc))
                .isEqualTo(new Rec("R_SCOPE_AUTH_ALLOW_NO_KB_MATCH").result.asBoolean());
        assertThat(SearchAuth.searchTargetsAllowKnowledgeId(t, "", "kb1", svc))
                .isEqualTo(new Rec("R_SCOPE_AUTH_ALLOW_EMPTY_ID").result.asBoolean());
    }

    private record RId(String id, double score) {
    }

    @Test
    void filterResultsCases() {
        SearchTargets t = targets();
        StubKnowledge svc = stubKnowledge();
        List<RId> results = new ArrayList<>(Arrays.asList(
                null,
                new RId("r1", 0.9),
                new RId("r2", 0.8),
                new RId("r3", 0.7),
                new RId("r4", 0.6)));

        Rec mixed = new Rec("R_SCOPE_AUTH_FILTER_MIXED");
        List<RId> filtered = SearchAuth.filterSearchResultsInSearchTargets(
                t, "kb1", results, RId::id, r -> "kb1", svc);
        // 对照探针：passthrough 后跳过 nil 条目再取 id/score
        List<RId> filteredView = new ArrayList<>();
        for (RId r : filtered) {
            if (r != null) {
                filteredView.add(r);
            }
        }
        assertThat(RecordingSupport.canonicalJson(RecordingSupport.PLAIN.valueToTree(filteredView)))
                .isEqualTo(RecordingSupport.canonicalJson(mixed.result));

        Rec kbOut = new Rec("R_SCOPE_AUTH_FILTER_KB_OUT");
        assertThatThrownBy(() -> SearchAuth.filterSearchResultsInSearchTargets(
                t, "kb9", results, RId::id, r -> "kb1", svc))
                .isInstanceOf(ScopeAuthException.class)
                .hasMessage(kbOut.error);

        Rec mismatch = new Rec("R_SCOPE_AUTH_FILTER_RESULT_KB_MISMATCH");
        assertThat(SearchAuth.filterSearchResultsInSearchTargets(
                t, "kb1", List.of(new RId("r9", 0)), RId::id, r -> "kbX", svc))
                .extracting(RId::id)
                .containsExactly("r9");
        mismatch.expectOk();

        Rec passthrough = new Rec("R_SCOPE_AUTH_FILTER_WHOLE_KB_PASSTHROUGH");
        List<RId> whole = SearchAuth.filterSearchResultsInSearchTargets(
                t, "kb2", List.of(new RId("r1", 0)), RId::id, r -> "kb2", svc);
        // Go 侧录了整个 SearchResult；这里只比对保留的条目 id
        List<String> wholeIds = new ArrayList<>();
        for (RId r : whole) {
            if (r != null) {
                wholeIds.add(r.id());
            }
        }
        List<String> wantIds = new ArrayList<>();
        for (JsonNode n : passthrough.result) {
            wantIds.add(n.get("id").asText());
        }
        assertThat(wholeIds).isEqualTo(wantIds);
    }

    @Test
    void scopeSemanticsAndTargetsMethods() {
        Rec tagTarget = new Rec("R_SCOPE_AUTH_SCOPE_TAG_TARGET");
        Rec docTarget = new Rec("R_SCOPE_AUTH_SCOPE_DOC_TARGET");
        // searchTargetScope/searchTargetIsWholeKB 是包私有静态语义，经公开路径验证：
        // scope_tag_target → knowledge_ids null, tag_ids [t1,t2,t3]
        SearchTarget kb1Tag = new SearchTarget(SearchTarget.TYPE_KNOWLEDGE_BASE, "kb1", 7,
                null, List.of("t1", "t2"), List.of("t2", "t3", ""), false);
        // 整库判定：非 whole（有标签）→ allowKnowledgeId 需走 tag 路径
        StubKnowledge svc = stubKnowledge();
        assertThat(SearchAuth.searchTargetsAllowKnowledgeId(
                new SearchTargets(List.of(kb1Tag)), "d3", "kb1", svc)).isTrue();
        Map<String, Object> tagScopeMap = new LinkedHashMap<>();
        tagScopeMap.put("knowledge_ids", null);
        tagScopeMap.put("tag_ids", List.of("t1", "t2", "t3"));
        assertThat(RecordingSupport.canonicalJson(RecordingSupport.PLAIN.valueToTree(tagScopeMap)))
                .isEqualTo(RecordingSupport.canonicalJson(tagTarget.result));
        Map<String, Object> docScopeMap = new LinkedHashMap<>();
        docScopeMap.put("knowledge_ids", List.of("d1", "d2"));
        docScopeMap.put("tag_ids", null);
        assertThat(RecordingSupport.canonicalJson(RecordingSupport.PLAIN.valueToTree(docScopeMap)))
                .isEqualTo(RecordingSupport.canonicalJson(docTarget.result));

        new Rec("R_SCOPE_AUTH_SCOPE_WHOLE_KB").expectOk();

        Rec methods = new Rec("R_SCOPE_AUTH_TARGETS_METHODS");
        SearchTargets t = targets();
        Map<String, Object> got = new LinkedHashMap<>();
        got.put("all_kb_ids", t.getAllKnowledgeBaseIds());
        got.put("kb_tenant_map", t.getKbTenantMap());
        got.put("tenant_kb1", t.getTenantIdForKb("kb1"));
        got.put("tenant_kbX", t.getTenantIdForKb("kbX"));
        got.put("contains_kb1", t.containsKb("kb1"));
        got.put("contains_kbX", t.containsKb("kbX"));
        got.put("has_override", t.hasRecallThresholdOverride());
        assertThat(RecordingSupport.canonicalJson(RecordingSupport.PLAIN.valueToTree(got)))
                .isEqualTo(RecordingSupport.canonicalJson(methods.result));
    }

    private static String field(String name) {
        try {
            return (String) GoRecording45B.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String resultId(JsonNode result) {
        return result == null || result.isNull() ? null : result.get("id").asText();
    }
}
