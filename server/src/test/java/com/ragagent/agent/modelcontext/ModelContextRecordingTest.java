package com.ragagent.agent.modelcontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static com.ragagent.agent.modelcontext.GoRecording46A.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.common.web.ToolJson;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.llm.domain.FunctionCall;
import com.ragagent.llm.domain.ToolCall;
import com.ragagent.common.retrieval.SearchResult;

/**
 * 4.6a modelcontext 包的 Go 实录回放（探针见 /tmp/toolrec46a/internal/modelcontext/
 * zz_rec46a_mc_test.go；常量见 {@link GoRecording46A}——字节断言为准）。
 * model_output 组严格按探针的 registry 复用顺序回放（句柄编号跨用例累积）。
 */
class ModelContextRecordingTest {

    private static JsonNode rec(String constant) {
        return GoRecording46A.rec(constant);
    }

    private static String out(String constant) {
        return rec(constant).get("out").asText();
    }

    private static List<String> listOfStrings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        for (JsonNode n : node) {
            out.add(n.isNull() ? null : n.asText());
        }
        return out;
    }

    private static List<String> listOrEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 解析 Go %v 的 []string 记录形态 "[a b c]"。 */
    private static String goSliceString(String goSlice, int index) {
        String inner = goSlice.substring(1, goSlice.length() - 1);
        return inner.split(" ")[index];
    }

    private static ToolCall call(String name, String arguments) {
        ToolCall call = new ToolCall();
        call.setFunction(new FunctionCall(name, arguments));
        return call;
    }

    private static SourceRegistry.ChunkReference chunkRef(String chunkId, String title) {
        SourceRegistry.ChunkReference ref = new SourceRegistry.ChunkReference();
        ref.chunkId = chunkId;
        ref.documentTitle = title;
        return ref;
    }

    // ---- protocol ----

    @Test
    void protocolPromptForms() {
        assertThat(SourceRegistry.sourceProtocolPrompt(false)).isEqualTo(out(R_PROTOCOL_OFF));
        assertThat(SourceRegistry.sourceProtocolPrompt(true)).isEqualTo(out(R_PROTOCOL_ON));
        assertThat(new Registry(true).protocolPrompt()).isEqualTo(out(R_PROTOCOL_REGISTRY));
        assertThat(new Registry(false).protocolPrompt()).isEqualTo(out(R_PROTOCOL_REGISTRY_OFF));
    }

    // ---- handle_table ----

    @Test
    void handleTableAllocationAndLookup() {
        HandleTable ht = new HandleTable("c", 3, 0);
        String h1 = ht.register("11111111-1111-1111-1111-111111111111");
        String h2 = ht.register("22222222-2222-2222-2222-222222222222");
        String h1b = ht.register("  11111111-1111-1111-1111-111111111111  ");
        assertThat(List.of(h1, h2, h1b))
                .containsExactlyElementsOf(listOfStrings(rec(R_HANDLE_TABLE_C000_ALLOC).get("out")));

        assertThat(ht.handle("22222222-2222-2222-2222-222222222222"))
                .isEqualTo(listOfStrings(rec(R_HANDLE_TABLE_HANDLE_LOOKUP).get("out")).get(0));
        assertThat(ht.handle("nope"))
                .isEqualTo(listOfStrings(rec(R_HANDLE_TABLE_HANDLE_LOOKUP).get("out")).get(1));

        String h1Again = ht.register("11111111-1111-1111-1111-111111111111");
        HandleTable.Resolved v = ht.resolve(h1Again);
        JsonNode resolveRec = rec(R_HANDLE_TABLE_RESOLVE).get("out");
        assertThat(v.value()).isEqualTo(resolveRec.get("v").asText());
        assertThat(v.ok()).isEqualTo(resolveRec.get("ok").asBoolean());
        HandleTable.Resolved vu = ht.resolve("c777");
        assertThat(vu.value()).isEqualTo(resolveRec.get("vu").asText());
        assertThat(vu.ok()).isEqualTo(resolveRec.get("oku").asBoolean());

        JsonNode lenRec = rec(R_HANDLE_TABLE_LEN_EMPTY).get("out");
        assertThat(ht.len()).isEqualTo(lenRec.get("len").asInt());
        assertThat(ht.empty()).isEqualTo(lenRec.get("empty").asBoolean());
    }

    @Test
    void handleTableEncodeDecode() {
        HandleTable ht = new HandleTable("c", 3, 0);
        ht.register("11111111-1111-1111-1111-111111111111");
        ht.register("22222222-2222-2222-2222-222222222222");
        String enc = ht.encodeKnownText(
                "see 11111111-1111-1111-1111-111111111111 and also 22222222-2222-2222-2222-222222222222 tail");
        assertThat(enc).isEqualTo(out(R_HANDLE_TABLE_ENCODE_KNOWN));
        assertThat(ht.decodeKnownText("token c000 and xc000 and c000x stay, c000 alone maps back"))
                .isEqualTo(out(R_HANDLE_TABLE_DECODE_WORD_BOUNDED));
    }

    @Test
    void handleTableDollarValueAndEmpty() {
        HandleTable ht2 = new HandleTable("ref-", 0, 1);
        String rh = ht2.register("cost $100 and ${x}");
        assertThat(List.of(rh, ht2.decodeKnownText("prefix ref-1 suffix")))
                .containsExactlyElementsOf(listOfStrings(rec(R_HANDLE_TABLE_DOLLAR_VALUE).get("out")));

        HandleTable ht3 = new HandleTable("ref-", 0, 1);
        assertThat(List.of(ht3.register(""), ht3.register("  "), ht3.register(" x "),
                ht3.encodeKnownText(""), ht3.decodeKnownText("")))
                .containsExactlyElementsOf(listOfStrings(rec(R_HANDLE_TABLE_EMPTY_REGISTER).get("out")));
    }

    // ---- citations ----

    @Test
    void citationsExpandKb() {
        SourceRegistry r = new SourceRegistry(true);
        SourceRegistry.ChunkReference ref = new SourceRegistry.ChunkReference();
        ref.chunkId = "chunk-uuid-1";
        ref.knowledgeId = "doc-uuid-1";
        ref.knowledgeBaseId = "kb-uuid-1";
        ref.documentTitle = "Title \"q\" & <x>";
        ref.chunkIndex = 2;
        ref.chunkType = "text";
        String h = r.registerChunk(ref);
        assertThat(h).isEqualTo(rec(R_CITATIONS_EXPAND_KB).get("handle").asText());
        String text = "before <ref id=\"" + h.toLowerCase() + "\"/> mid <ref id=\"c999\"/> after"
                + " <kb doc=\"z\" chunk_id=\"w\"> <web url=\"http://a\">";
        assertThat(r.expandText(text)).isEqualTo(out(R_CITATIONS_EXPAND_KB));
    }

    @Test
    void citationsExpandDisabled() {
        SourceRegistry rOff = new SourceRegistry(false);
        String hOff = rOff.registerChunk(chunkRef("chunk-uuid-2", ""));
        assertThat(rOff.expandText("a <ref id=\"" + hOff.toLowerCase() + "\"/> b"))
                .isEqualTo(out(R_CITATIONS_EXPAND_DISABLED));
    }

    @Test
    void citationsCompactRoundtrips() {
        SourceRegistry rc = new SourceRegistry(true);
        String comp = rc.compactPublicCitations(
                "<kb doc=\"doc-uuid-3\" chunk_id=\"chunk-uuid-3\">x</kb> then <kb chunk_id=\"chunk-uuid-3\" kb_id=\"kb-uuid-3\">", true);
        String expanded = rc.expandText(comp + " " + comp);
        JsonNode expected = rec(R_CITATIONS_COMPACT_KB_ROUNDTRIP).get("out");
        assertThat(comp).isEqualTo(expected.get("compact").asText());
        assertThat(expanded).isEqualTo(expected.get("expand").asText());

        assertThat(rc.compactPublicCitations("<kb doc=\"d\">", true)).isEqualTo(out(R_CITATIONS_COMPACT_NO_CHUNK));

        SourceRegistry rw = new SourceRegistry(true);
        String cw = rw.compactPublicCitations(
                "<web url=\"https://example.com/p?x=1#frag\" title=\"T1\"> and <web url=\"https://example.com/p?x=1#other\" title=\"T2\">", true);
        String ew = rw.expandText(cw);
        JsonNode web = rec(R_CITATIONS_COMPACT_WEB).get("out");
        assertThat(cw).isEqualTo(web.get("compact").asText());
        assertThat(ew).isEqualTo(web.get("expand").asText());

        SourceRegistry rh2 = new SourceRegistry(true);
        String ch = rh2.compactPublicCitations("<kb chunk_id=\"a&amp;b\" doc=\"t\">", true);
        JsonNode unesc = rec(R_CITATIONS_COMPACT_ATTR_UNESCAPE).get("out");
        assertThat(ch).isEqualTo(unesc.get("compact").asText());
        assertThat(rh2.expandText(ch)).isEqualTo(unesc.get("expand").asText());
    }

    @Test
    void citationsLegacyAndLabeledRefs() {
        SourceRegistry rl = new SourceRegistry(true);
        rl.registerLegacyToolReferences(
                "x <chunk knowledge_id=\"doc-9\" chunk_id=\"ch-9\" knowledge_base_id=\"kb-9\" knowledge_title=\"Doc9\"> <faq faq_id=\"fq-1\" doc=\"d-f\">", false);
        String ch9 = rl.chunkHandle("ch-9").toLowerCase();
        String fq1 = rl.chunkHandle("fq-1").toLowerCase();
        assertThat(rl.expandText("<ref id=\"" + ch9 + "\"/><ref id=\"" + fq1 + "\"/>"))
                .isEqualTo(rec(R_CITATIONS_LEGACY_REFS).get("out").get("expand").asText());

        SourceRegistry rm = new SourceRegistry(true);
        rm.registerLabeledReferences("a knowledge_id=\"doc-elem-1\" <knowledge_id> doc-elem-2 </knowledge_id>"
                + " kb_id=\"kb-elem-1\" <knowledge_base_id> kb-elem-2 </knowledge_base_id>"
                + " plain 12345678-1234-1234-1234-123456789abc text");
        JsonNode labeled = rec(R_CITATIONS_LABELED_REFS).get("out");
        // 探针用 fmt.Sprintf("%v", []string{...}) 记录 → "[d1 d2]" 字符串
        assertThat(goSliceString(labeled.get("docs").asText(), 0)).isEqualTo(rm.docsHandle("doc-elem-1"));
        assertThat(goSliceString(labeled.get("docs").asText(), 1)).isEqualTo(rm.docsHandle("doc-elem-2"));
        assertThat(goSliceString(labeled.get("kbs").asText(), 0)).isEqualTo(rm.kbsHandle("kb-elem-1"));
        assertThat(goSliceString(labeled.get("kbs").asText(), 1)).isEqualTo(rm.kbsHandle("kb-elem-2"));
    }

    // ---- citation_stream ----

    @Test
    void citationStreamExpander() {
        SourceRegistry r = new SourceRegistry(true);
        String h = r.registerChunk(chunkRef("chunk-stream-1", "DT")).toLowerCase();

        CitationStreamExpander d = new CitationStreamExpander(r);
        StringBuilder sb1 = new StringBuilder();
        sb1.append(d.feed("before <ref ")).append(d.feed("id=\"" + h + "\"/> after <kb partial")).append(d.flush());
        assertThat(sb1.toString()).isEqualTo(out(R_CITATION_STREAM_SPLIT_REF));

        CitationStreamExpander d2 = new CitationStreamExpander(r);
        StringBuilder sb2 = new StringBuilder();
        sb2.append(d2.feed("a <web x>b <ref ")).append(d2.feed("id=\"" + h + "\"/>")).append(d2.flush());
        assertThat(sb2.toString()).isEqualTo(out(R_CITATION_STREAM_WEB_DROP));

        CitationStreamExpander d3 = new CitationStreamExpander(r);
        StringBuilder sb3 = new StringBuilder();
        sb3.append(d3.feed("tail r")).append(d3.feed("e")).append(d3.feed("s not a tag")).append(d3.flush());
        assertThat(sb3.toString()).isEqualTo(out(R_CITATION_STREAM_PROSE_LT));

        CitationStreamExpander d4 = new CitationStreamExpander(r);
        d4.feed("ends with <ref id=\"");
        assertThat(d4.flush()).isEqualTo(out(R_CITATION_STREAM_FLUSH_PENDING_DROPPED));

        CitationStreamExpander d5 = new CitationStreamExpander(r);
        String p = d5.feed("plain tail without tags");
        assertThat(p + d5.flush()).isEqualTo(out(R_CITATION_STREAM_FLUSH_PLAIN));
    }

    // ---- decode_output ----

    @Test
    void decodeOutputResourceOrphanSlugIssue() {
        Registry r = new Registry(true);
        String resRef = "minio://bucket/some/path/file.pdf";
        assertThat(r.compactKnownText("see " + resRef + " inline")).isEqualTo(out(R_DECODE_OUTPUT_RESOURCE_ENCODE));
        assertThat(r.decodeOutputText(out(R_DECODE_OUTPUT_RESOURCE_ENCODE)))
                .isEqualTo(out(R_DECODE_OUTPUT_RESOURCE_DECODE));
        String encodedRef = out(R_DECODE_OUTPUT_RESOURCE_ENCODE).replace("see ", "").replace(" inline", "");
        assertThat(r.decodeOutputText("ghost res://9999 token and " + encodedRef))
                .isEqualTo(out(R_DECODE_OUTPUT_ORPHAN_STRIP));

        String slug = "summary/11111111-1111-1111-1111-111111111111";
        String encSlug = r.compactKnownText("[[x|" + slug + "]]");
        assertThat(encSlug).isEqualTo(out(R_DECODE_OUTPUT_SLUG_ENCODE));
        assertThat(r.decodeOutputText(encSlug)).isEqualTo(out(R_DECODE_OUTPUT_SLUG_DECODE));

        String issOut = r.encodeToolPrivateResult("wiki_read_issue", "{\"id\":\"ISSUE-42\",\"title\":\"t\"}");
        assertThat(issOut).isEqualTo(out(R_DECODE_OUTPUT_ISSUE_ENCODE));
        assertThat(r.decodeOutputText(issOut)).isEqualTo(out(R_DECODE_OUTPUT_ISSUE_DECODE_TEXT));

        assertThat(r.decodeOutputText("use ms2 and mt3 directories")).isEqualTo(out(R_DECODE_OUTPUT_MCP_HANDLE_PASSTHROUGH));
        assertThat(r.decodeOutputText("item i1 stays")).isEqualTo(out(R_DECODE_OUTPUT_UNKNOWN_ISSUE_STAYS));
    }

    // ---- tool_policy ----

    @Test
    void toolPolicyTables() {
        JsonNode argAllowed = rec(R_TOOL_POLICY_ARG_ALLOWED).get("out");
        argAllowed.fields().forEachRemaining(e -> {
            String[] parts = e.getKey().split("\\|", -1);
            assertThat(ToolPolicy.sourceArgumentAllowed(parts[0], parts[1]))
                    .as("arg %s|%s", parts[0], parts[1])
                    .isEqualTo(e.getValue().asBoolean());
        });
        JsonNode outputAllowed = rec(R_TOOL_POLICY_OUTPUT_ALLOWED).get("out");
        outputAllowed.fields().forEachRemaining(e ->
                assertThat(ToolPolicy.sourceOutputAllowed(e.getKey()))
                        .as("output %s", e.getKey())
                        .isEqualTo(e.getValue().asBoolean()));
        JsonNode compactionAllowed = rec(R_TOOL_POLICY_COMPACTION_ALLOWED).get("out");
        compactionAllowed.fields().forEachRemaining(e ->
                assertThat(ToolPolicy.sourceCompactionAllowed(e.getKey()))
                        .as("compaction %s", e.getKey())
                        .isEqualTo(e.getValue().asBoolean()));
        JsonNode hasPolicy = rec(R_TOOL_POLICY_HAS_POLICY).get("out");
        hasPolicy.fields().forEachRemaining(e ->
                assertThat(ToolPolicy.hasToolPolicy(e.getKey())).isEqualTo(e.getValue().asBoolean()));
    }

    @Test
    void toolPolicyDecodeStates() {
        Registry r = new Registry(true);
        r.registerKnowledgeBase("kb-real-uuid-1");
        r.registerDocument("doc-real-uuid-1");
        List<ToolCall> calls = new ArrayList<>();
        calls.add(call("knowledge_search", "{\"query\":\"q\",\"knowledge_base_ids\":[\"c1\"]}"));
        calls.add(call("knowledge_search", "{\"query\":\"q\",\"knowledge_base_ids\":[\"c77\"]}"));
        calls.add(call("knowledge_search", "{\"query\":\"q\",\"knowledge_base_ids\":[\"c1\",\"c77\"]}"));
        calls.add(call("knowledge_search", "{\"query\":\"q\",\"knowledge_base_ids\":[\"kb-real-uuid-1\"]}"));
        r.decodeToolCalls(calls);
        JsonNode expected = rec(R_TOOL_POLICY_DECODE_STATES).get("out");
        for (int i = 0; i < calls.size(); i++) {
            JsonNode exp = expected.get(i);
            assertThat(calls.get(i).getFunction().getArguments()).as("case %d args", i).isEqualTo(exp.get("args").asText());
            assertThat(calls.get(i).getArgumentResolution()).as("case %d res", i).isEqualTo(exp.get("res").asText());
            assertThat(listOrEmpty(calls.get(i).getUnresolvedHandles()))
                    .containsExactlyElementsOf(listOfStrings(exp.get("unres")));
            assertThat(calls.get(i).getModelArguments()).as("case %d modelarg", i).isEqualTo(exp.get("modelarg").asText());
        }
    }

    @Test
    void toolPolicyWebFetchAndMcpNormalize() {
        Registry r2 = new Registry(true);
        r2.registerWeb("https://w1.example/a", "A");
        List<ToolCall> c2 = new ArrayList<>();
        c2.add(call("web_fetch", "{\"items\":\"[{\\\"url\\\":\\\"w1\\\",\\\"id\\\":\\\"w1\\\"}]\"}"));
        ToolPolicy.normalizeWebFetchItems(c2);
        assertThat(c2.get(0).getFunction().getArguments()).isEqualTo(out(R_TOOL_POLICY_WEBFETCH_ITEMS));

        List<ToolCall> c3 = new ArrayList<>();
        c3.add(call("call_mcp_tool",
                "{\"server_id\":\"srv\",\"name\":\"n\",\"arguments\":\"{\\\"limit\\\":1.0,\\\"q\\\":\\\"x\\\",\\\"big\\\":12345678901234567890}\"}"));
        ToolPolicy.normalizeMCPCallArguments(c3);
        assertThat(c3.get(0).getFunction().getArguments()).isEqualTo(out(R_TOOL_POLICY_MCP_NORMALIZE));
    }

    @Test
    void toolPolicySqlQuotedAndIssue() {
        Registry r3 = new Registry(true);
        r3.registerDocument("doc-sql-uuid");
        List<ToolCall> c4 = new ArrayList<>();
        c4.add(call("database_query", "{\"sql\":\"SELECT * FROM d1 WHERE c='d1' AND t=\\\"d1\\\" OR z=d1\"}"));
        r3.decodeToolCalls(c4);
        assertThat(c4.get(0).getFunction().getArguments()).isEqualTo(out(R_TOOL_POLICY_SQL_QUOTED));

        Registry r4 = new Registry(true);
        String ih = r4.encodeToolPrivateResult("wiki_read_issue", "{\"id\":\"PROJ-7\"}");
        List<ToolCall> c5 = new ArrayList<>();
        c5.add(call("wiki_update_issue", "{\"issue_id\":\"i1\"}"));
        r4.decodeToolCalls(c5);
        List<ToolCall> c5b = new ArrayList<>();
        c5b.add(call("wiki_update_issue", "{\"issue_id\":\"i9\"}"));
        r4.decodeToolCalls(c5b);
        JsonNode expected = rec(R_TOOL_POLICY_ISSUE_DECODE).get("out");
        assertThat(ih).isEqualTo(expected.get("encoded").asText());
        assertThat(c5.get(0).getFunction().getArguments()).isEqualTo(expected.get("ok_args").asText());
        assertThat(c5.get(0).getArgumentResolution()).isEqualTo(expected.get("ok_res").asText());
        assertThat(c5b.get(0).getFunction().getArguments()).isEqualTo(expected.get("miss_args").asText());
        assertThat(c5b.get(0).getArgumentResolution()).isEqualTo(expected.get("miss_res").asText());
        assertThat(listOrEmpty(c5b.get(0).getUnresolvedHandles()))
                .containsExactlyElementsOf(listOfStrings(expected.get("miss_unres")));

        List<String> un = ToolPolicy.unresolvedPrivateToolHandles(r3, "database_query", "{\"sql\":\"SELECT 'd9' AS a\"}");
        List<String> un2 = ToolPolicy.unresolvedPrivateToolHandles(r3, "no_such", "{\"sql\":\"SELECT 'd9'\"}");
        JsonNode unresolved = rec(R_TOOL_POLICY_UNRESOLVED_PRIVATE).get("out");
        assertThat(listOrEmpty(un)).containsExactlyElementsOf(listOfStrings(unresolved.get("sql")));
        assertThat(unresolved.get("none").isNull()).isTrue();
        assertThat(listOrEmpty(un2)).isEmpty();
    }

    // ---- model_output（共享 registry，严格探针顺序）----

    @Test
    void modelOutputSharedRegistrySequence() {
        SourceRegistry r = new SourceRegistry(true);

        // search（c1..c4 / d1 / b1）
        ToolResult search = new ToolResult();
        search.setSuccess(true);
        search.setOutput("rows fallback");
        Map<String, Object> searchData = new HashMap<>();
        searchData.put("display_type", "search_results");
        searchData.put("results", List.of(
                Map.of("chunk_id", "s-chunk-1", "knowledge_id", "s-doc-1", "knowledge_base_id", "s-kb-1",
                        "knowledge_title", "Search Doc", "content", "full content 中文", "score", 0.42, "chunk_index", 1),
                Map.of("faq_id", "f-chunk-1", "knowledge_id", "s-doc-1", "knowledge_title", "Search Doc",
                        "faq_question", "Q?", "faq_answers", List.of("A1", "A2"), "chunk_type", ""),
                Map.of("id", "only-id", "title", "T", "content", ""),
                Map.of("chunk_id", "m-chunk", "knowledge_title", "Match Doc", "matched_content", "snippet only")));
        search.setData(searchData);
        assertThat(ModelOutput.modelOutput(r, search)).isEqualTo(out(R_MODEL_OUTPUT_SEARCH));

        // grep
        ToolResult grep = new ToolResult();
        grep.setSuccess(true);
        grep.setOutput("fallback");
        grep.setData(Map.of(
                "display_type", "grep_results",
                "chunk_results", List.of(
                        Map.of("chunk_id", "g-chunk-1", "knowledge_id", "g-doc-1", "knowledge_base_id", "g-kb-1",
                                "knowledge_title", "Grep Doc", "matched_content", "匹配 snippet & <tag>",
                                "chunk_index", 3, "chunk_type", "text"))));
        assertThat(ModelOutput.modelOutput(r, grep)).isEqualTo(out(R_MODEL_OUTPUT_GREP));

        // graph
        ToolResult graph = new ToolResult();
        graph.setSuccess(true);
        graph.setOutput("fallback");
        graph.setData(Map.of(
                "display_type", "graph_query_results",
                "results", List.of(Map.of("chunk_id", "gr-chunk", "knowledge_id", "gr-doc", "knowledge_title", "Graph Doc"))));
        assertThat(ModelOutput.modelOutput(r, graph)).isEqualTo(out(R_MODEL_OUTPUT_GRAPH));

        // chunks_list
        ToolResult chunks = new ToolResult();
        chunks.setSuccess(true);
        chunks.setOutput("fallback");
        Map<String, Object> chunksData = new HashMap<>();
        chunksData.put("display_type", "knowledge_chunks_list");
        chunksData.put("chunks", List.of(
                Map.of("chunk_id", "kc-1", "content", "body", "chunk_index", 1),
                Map.of("chunk_id", "kc-2", "chunk_index", 2)));
        chunksData.put("knowledge_id", "kc-doc");
        chunksData.put("knowledge_title", "KC Title");
        chunksData.put("total_chunks", 25);
        chunksData.put("fetched_chunks", 10);
        chunksData.put("page", 2);
        chunksData.put("page_size", 10);
        chunks.setData(chunksData);
        assertThat(ModelOutput.modelOutput(r, chunks)).isEqualTo(out(R_MODEL_OUTPUT_CHUNKS_LIST));

        // doc_info
        ToolResult docInfo = new ToolResult();
        docInfo.setSuccess(true);
        docInfo.setOutput("fallback");
        docInfo.setData(Map.of(
                "display_type", "document_info",
                "documents", List.of(
                        Map.of("knowledge_id", "di-doc-1", "is_faq", true, "faq_id", "di-faq-1", "faq_question", "FAQ Q",
                                "faq_answers", List.of("ans1"), "title", "ignored"),
                        Map.of("knowledge_id", "di-doc-2", "is_faq", false, "title", "Doc Two", "type", "docx",
                                "file_type", ".docx", "chunk_count", 7, "description", "desc & <b>"),
                        Map.of("knowledge_id", "di-doc-3", "is_faq", true, "faq_id", ""))));
        assertThat(ModelOutput.modelOutput(r, docInfo)).isEqualTo(out(R_MODEL_OUTPUT_DOC_INFO));

        // doc_info_empty
        ToolResult docInfoEmpty = new ToolResult();
        docInfoEmpty.setSuccess(true);
        docInfoEmpty.setOutput("no docs");
        docInfoEmpty.setData(Map.of("display_type", "document_info", "documents", List.of()));
        assertThat(ModelOutput.modelOutput(r, docInfoEmpty)).isEqualTo(out(R_MODEL_OUTPUT_DOC_INFO_EMPTY));

        // failed_registry（同一个 r）
        ToolResult failed = new ToolResult();
        failed.setSuccess(false);
        failed.setOutput("stdout text");
        failed.setError("exited with code 1");
        assertThat(ModelOutput.modelOutput(r, failed)).isEqualTo(out(R_MODEL_OUTPUT_FAILED_REGISTRY));

        // web_search（同一个 r：s.example/* → w1..w4）
        ToolResult ws = new ToolResult();
        ws.setSuccess(true);
        ws.setOutput("fallback");
        ws.setData(Map.of(
                "display_type", "web_search_results",
                "results", List.of(
                        Map.of("url", "https://s.example/one", "title", "One", "snippet", "snip one",
                                "content", "content one differs", "age", "2 days ago"),
                        Map.of("url", "https://s.example/two", "title", "Two", "snippet", "snip two"),
                        Map.of("url", "https://s.example/three", "title", "Three", "snippet", "s3",
                                "page_verified", true, "page_content", "verified page body", "full_output_path", ""),
                        Map.of("url", "https://s.example/four", "title", "Four", "page_status", "failed",
                                "page_error", "fetch refused"),
                        Map.of("url", "", "title", "NoURL", "snippet", "x", "published_at", "2026-01-01"))));
        assertThat(ModelOutput.modelOutput(r, ws)).isEqualTo(out(R_MODEL_OUTPUT_WEB_SEARCH));

        // web_fetch（同一个 r：f.example/* → w5..）
        ToolResult wf = new ToolResult();
        wf.setSuccess(true);
        wf.setOutput("fallback");
        wf.setData(Map.of(
                "display_type", "web_fetch_results",
                "results", List.of(
                        Map.of("url", "https://f.example/a", "title", "A", "status", "success", "summary", "sum a",
                                "raw_content", "x".repeat(12000), "offset", 0, "content_length", 12000, "truncated", false),
                        Map.of("url", "https://f.example/b", "status", "failed", "retryable", false,
                                "error_code", "dns", "error_message", "no such host"),
                        Map.of("url", "https://f.example/c", "raw_content", "legacy body",
                                "full_output_path", "/workspace/output/c.md", "storage_error", "store down"),
                        Map.of("url", "https://f.example/d", "title", "D", "status", "success", "summary", "",
                                "summary_status", "failed", "summary_error_code", "timeout",
                                "summary_error_message", "sum timed out"))));
        assertThat(ModelOutput.modelOutput(r, wf)).isEqualTo(out(R_MODEL_OUTPUT_WEB_FETCH));

        // web_fetch_big（同一个 r）
        ToolResult big = new ToolResult();
        big.setSuccess(true);
        big.setOutput("fallback");
        big.setData(Map.of(
                "display_type", "web_fetch_results",
                "results", List.of(Map.of("url", "https://f.example/big", "status", "success",
                        "raw_content", "字".repeat(9000), "offset", 0, "content_length", 0))));
        assertThat(ModelOutput.modelOutput(r, big)).isEqualTo(out(R_MODEL_OUTPUT_WEB_FETCH_BIG));
    }

    @Test
    void modelOutputDbQuery() {
        SourceRegistry rdb = new SourceRegistry(true);
        ToolResult db = new ToolResult();
        db.setSuccess(true);
        db.setOutput("row: db-doc-uuid / db-chunk-uuid");
        db.setData(Map.of(
                "display_type", "database_query",
                "rows", List.of(Map.of("knowledge_id", "db-doc-uuid", "n", 5, "chunk_id", "db-chunk-uuid"))));
        assertThat(ModelOutput.modelOutput(rdb, db)).isEqualTo(out(R_MODEL_OUTPUT_DB_QUERY));
        assertThat(rdb.expandText("d1 c1")).isEqualTo(out(R_MODEL_OUTPUT_DB_QUERY_EXPANDED));
    }

    @Test
    void modelOutputDefaultBranch() {
        SourceRegistry rdef = new SourceRegistry(true);
        ToolResult def = new ToolResult();
        def.setSuccess(true);
        def.setOutput("{\"knowledge_id\":\"def-doc-1\",\"kb\":\"b1\"} meta knowledge_id=\"def-doc-2\" tail");
        assertThat(ModelOutput.modelOutput(rdef, def)).isEqualTo(out(R_MODEL_OUTPUT_DEFAULT_BRANCH));
        // 探针的四个 ref：def-doc-1 与 b1 未注册（整串不是 JSON，结构化注册早退；
        // labeled 只认 attr 形态的 def-doc-2 → d1），后者不在 citable → 全部丢弃
        String d1 = nullToEmpty(rdef.docsHandle("def-doc-2")).toLowerCase();
        String b1 = nullToEmpty(rdef.kbsHandle("b1")).toLowerCase();
        String d2 = nullToEmpty(rdef.docsHandle("def-doc-1")).toLowerCase();
        assertThat(rdef.expandText("<ref id=\"" + d2 + "\"/><ref id=\"" + b1 + "\"/><ref id=\"" + d1 + "\"/><ref id=\"d7\"/>"))
                .isEqualTo(out(R_MODEL_OUTPUT_DEFAULT_EXPANDED));
    }

    @Test
    void modelOutputFailedTextAndOutputFiles() {
        assertThat(ModelOutput.failedToolModelText("", "")).isEqualTo(out(R_MODEL_OUTPUT_FAILED_BOTH_EMPTY));
        assertThat(ModelOutput.failedToolModelText("", "boom")).isEqualTo(out(R_MODEL_OUTPUT_FAILED_ERR_ONLY));
        assertThat(ModelOutput.failedToolModelText("  out with boom inside  ", "boom"))
                .isEqualTo(out(R_MODEL_OUTPUT_FAILED_OUT_CONTAINS_ERR));
        assertThat(ModelOutput.failedToolModelText("out", "boom")).isEqualTo(out(R_MODEL_OUTPUT_FAILED_OUT_PLUS_ERR));

        Registry rOut = new Registry(true);
        ToolResult files = new ToolResult();
        files.setSuccess(true);
        files.setOutput("done");
        files.setOutputFiles(List.of("/workspace/output/a.md", "/workspace/output/b.png"));
        assertThat(rOut.modelToolResult(files)).isEqualTo(out(R_MODEL_OUTPUT_OUTPUT_FILES));
    }

    @Test
    void modelOutputTruncationAndToolResultVariants() {
        String longText = "汉".repeat(30) + "a".repeat(10);
        ModelOutput.Truncated limited = ModelOutput.truncateModelEvidence(longText, 25);
        JsonNode expected = rec(R_MODEL_OUTPUT_TRUNCATE_RUNES).get("out");
        assertThat(limited.value()).isEqualTo(expected.get("out").asText());
        assertThat(limited.value().codePointCount(0, limited.value().length())).isEqualTo(expected.get("n").asInt());
        assertThat(limited.truncated()).isEqualTo(expected.get("trunc").asBoolean());

        Registry rmr = new Registry(true);
        ToolResult nonSource = new ToolResult();
        nonSource.setSuccess(true);
        nonSource.setOutput("did doc-uuid-todo-1");
        assertThat(rmr.modelToolResultForTool("todo_write", nonSource))
                .isEqualTo(out(R_MODEL_OUTPUT_TOOL_RESULT_NON_SOURCE));

        Registry rmr2 = new Registry(true);
        ToolResult sourceFallback = new ToolResult();
        sourceFallback.setSuccess(true);
        sourceFallback.setOutput("plain grep note: doc-uuid-grep-1 in it");
        assertThat(rmr2.modelToolResultForTool("grep_chunks", sourceFallback))
                .isEqualTo(out(R_MODEL_OUTPUT_TOOL_RESULT_SOURCE_FALLBACK));
        ToolResult failedCall = new ToolResult();
        failedCall.setSuccess(false);
        failedCall.setOutput("err out");
        failedCall.setError("bad arg doc-uuid-grep-2");
        assertThat(rmr2.modelToolResultForTool("grep_chunks", failedCall))
                .isEqualTo(out(R_MODEL_OUTPUT_TOOL_RESULT_FAILED));
        ToolResult errorEncoded = new ToolResult();
        errorEncoded.setSuccess(false);
        errorEncoded.setError("invalid doc-uuid-grep-2");
        assertThat(rmr2.modelToolResultForTool("todo_write", errorEncoded))
                .isEqualTo(out(R_MODEL_OUTPUT_TOOL_RESULT_ERROR_ENCODED));
    }

    // ---- mcp ----

    @Test
    void mcpSourceCandidates() {
        Registry r = new Registry(true);
        String payload = "{\"result\":\"read https://docs.example.com/wiki/Function_(mathematics) and https://a.example/x?y=1,"
                + " plus https://b.example. Also ftp://no.example/f and https://user:pw@c.example/p and javascript:alert(1)"
                + " and https://d.example/#frag\"}";
        assertThat(r.mcpSourceCandidates(payload)).isEqualTo(out(R_MCP_CANDIDATES));
        Registry rOff = new Registry(false);
        assertThat(rOff.mcpSourceCandidates(payload)).isEqualTo(out(R_MCP_CANDIDATES_DISABLED));
        // 探针里 nonjson 与 candidates 共用同一个 registry（w 编号延续 → w5）
        assertThat(r.mcpSourceCandidates("plain prose link https://p.example/q end"))
                .isEqualTo(out(R_MCP_CANDIDATES_NONJSON));
    }

    @Test
    void mcpEncodeTools() {
        Registry r = new Registry(true);
        List<ChatTool> enc = r.encodeTools(List.of(
                tool("discover_mcp_tools", "List tools",
                        "{\"properties\":{\"server_id\":{\"enum\":[\"srv-uuid-1\",\"srv-uuid-2\"],\"type\":\"string\"},\"q\":{\"type\":\"string\"}},\"type\":\"object\"}"),
                tool("call_mcp_tool", "Call",
                        "{\"properties\":{\"server_id\":{\"type\":\"string\"},\"tool_ref\":{\"enum\":[\"tool-uuid-9\"],\"type\":\"string\"}}}")));
        assertThat(goToolsJson(enc)).isEqualTo(out(R_MCP_ENCODE_TOOLS_ENUM));

        ToolResult dir = new ToolResult();
        dir.setSuccess(true);
        dir.setOutput("{\"servers\":[{\"name\":\"S1\",\"server_id\":\"srv-uuid-1\"}],\"tools\":[{\"server_id\":\"srv-uuid-1\",\"tool_ref\":\"tool-uuid-9\"}]}");
        String dir1 = r.modelToolResultForTool("discover_mcp_tools", dir);
        assertThat(dir1).isEqualTo(out(R_MCP_DIRECTORY_ENCODED));

        List<ChatTool> encTools2 = r.encodeTools(List.of(
                tool("discover_mcp_tools", "List tools",
                        "{\"properties\":{\"server_id\":{\"enum\":[\"srv-uuid-1\",\"srv-uuid-2\"],\"type\":\"string\"},\"q\":{\"type\":\"string\"}},\"type\":\"object\"}")));
        assertThat(goToolsJson(encTools2)).isEqualTo(out(R_MCP_ENCODE_TOOLS_AFTER_REGISTER));
    }

    @Test
    void mcpDescriptionRewriteAndRoutingText() {
        Registry r2 = new Registry(true);
        ToolResult reg = new ToolResult();
        reg.setSuccess(true);
        reg.setOutput("{\"servers\":[{\"server_id\":\"srv-uuid-7\"}]}");
        String registered = r2.modelToolResultForTool("discover_mcp_tools", reg);
        List<ChatTool> enc2 = r2.encodeTools(List.of(
                tool("mcp_custom", "[MCP service srv-uuid-7 (external)] does things", "{}")));
        JsonNode expected = rec(R_MCP_DESC_EXTERNAL_REWRITE).get("out");
        assertThat(registered).isEqualTo(expected.get("registered").asText());
        assertThat(enc2.get(0).getFunction().getDescription()).isEqualTo(expected.get("desc").asText());

        Registry r3 = new Registry(true);
        ToolResult reg8 = new ToolResult();
        reg8.setSuccess(true);
        reg8.setOutput("{\"servers\":[{\"server_id\":\"srv-uuid-8\"}]}");
        r3.modelToolResultForTool("discover_mcp_tools", reg8);
        assertThat(r3.encodeMCPRoutingText("pick server_id=\"srv-uuid-8\" not server_id=\"other-uuid\""))
                .isEqualTo(out(R_MCP_ROUTING_TEXT));
    }

    private static ChatTool tool(String name, String description, String parameters) {
        ChatTool t = new ChatTool(name, description, GoJsonBridge.parseTree(parameters));
        // Go 探针的字面量 chat.Tool{Function: ...} 没设 Type → 零值 ""
        t.setType("");
        return t;
    }

    /** Go json.Marshal([]chat.Tool)：struct 键序 type→function→(name,description,parameters RawMessage)。 */
    private static String goToolsJson(List<ChatTool> tools) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            ChatTool t = tools.get(i);
            String params = t.getFunction().getParameters() == null ? "null"
                    : ToolJson.write(t.getFunction().getParameters());
            sb.append("{\"type\":\"").append(GoJsonBridge.goString(t.getType()))
                    .append("\",\"function\":{\"name\":\"").append(GoJsonBridge.goString(t.getFunction().getName()))
                    .append("\",\"description\":\"").append(GoJsonBridge.goString(t.getFunction().getDescription()))
                    .append("\",\"parameters\":").append(params).append("}}");
        }
        return sb.append(']').toString();
    }

    // ---- stream ----

    @Test
    void streamDecoderResourceSplit() {
        Registry r = new Registry(true);
        String ref = "minio://b/k/f.pdf";
        String encRef = r.compactKnownText(ref);
        assertThat(encRef).isNotEqualTo(ref);
        String handle = encRef.substring("res://".length());
        StreamDecoder d = r.streamDecoder();
        StringBuilder sb = new StringBuilder();
        sb.append(d.feed("see re"));
        sb.append(d.feed("s://"));
        sb.append(d.feed(handle.substring(0, 2)));
        sb.append(d.feed(handle.substring(2) + " done"));
        sb.append(d.flush());
        assertThat(sb.toString()).isEqualTo(out(R_STREAM_RESOURCE_SPLIT));
    }

    @Test
    void streamDecoderIssueAndOrphan() {
        Registry r2 = new Registry(true);
        r2.encodeToolPrivateResult("wiki_read_issue", "{\"id\":\"TICKET-1\"}");
        StreamDecoder d2 = r2.streamDecoder();
        StringBuilder s2 = new StringBuilder();
        s2.append(d2.feed("issue i"));
        s2.append(d2.feed("1 closed"));
        s2.append(d2.flush());
        assertThat(s2.toString()).isEqualTo(out(R_STREAM_ISSUE_SPLIT));

        StreamDecoder d3 = r2.streamDecoder();
        StringBuilder s3 = new StringBuilder();
        s3.append(d3.feed("fake re"));
        s3.append(d3.feed("s://77"));
        s3.append(d3.feed("77 now"));
        s3.append(d3.flush());
        assertThat(s3.toString()).isEqualTo(out(R_STREAM_ORPHAN_SPLIT));
    }

    @Test
    void streamDecoderFlushAndKbDrop() {
        StreamDecoder d = new Registry(true).streamDecoder();
        StringBuilder s4 = new StringBuilder();
        s4.append(d.feed("word r"));
        s4.append(d.flush());
        assertThat(s4.toString()).isEqualTo(out(R_STREAM_FLUSH_PARTIAL_PREFIX));

        Registry r5 = new Registry(true);
        String h = r5.registerChunk(chunkRef("chunk-st-1", "")).toLowerCase();
        StreamDecoder d5 = r5.streamDecoder();
        StringBuilder s5 = new StringBuilder();
        s5.append(d5.feed("a <kb d"));
        s5.append(d5.feed("ropped> b <ref"));
        s5.append(d5.feed(" id=\"" + h + "\"/> c"));
        s5.append(d5.flush());
        assertThat(s5.toString()).isEqualTo(out(R_STREAM_KB_DROP_REF));
    }

    // ---- sources ----

    @Test
    void sourcesShortHandlesAndDedup() {
        SourceRegistry r = new SourceRegistry(true);
        List<String> echoes = List.of(
                r.registerChunk(chunkRef("c1", "")),
                r.registerDocument("d2"),
                r.registerKnowledgeBase("b3"),
                r.registerWeb("w9", ""),
                r.registerChunk(chunkRef("unknown-chunk", "")));
        assertThat(echoes).containsExactlyElementsOf(listOfStrings(rec(R_SOURCES_SHORT_HANDLE_ECHO).get("out")));

        SourceRegistry r2 = new SourceRegistry(true);
        String h1 = r2.registerWeb("https://e.example/p", "P");
        String h2 = r2.registerWeb("https://e.example/p#z", "P2");
        assertThat(List.of(h1, h2, r2.registerWeb("  ", "")))
                .containsExactlyElementsOf(listOfStrings(rec(R_SOURCES_WEB_DEDUP).get("out")));

        SourceRegistry r3 = new SourceRegistry(true);
        r3.registerSourceIDByKey("source_refs", "doc-ref-uuid|Some Title", true);
        r3.registerSourceIDByKey("url", "res://0001", true);
        r3.registerSourceIDByKey("url", "https://ok.example/x", true);
        r3.registerSourceIDByKey("chunk_id", "  ", true);
        JsonNode keySpaces = rec(R_SOURCES_KEY_SPACES).get("out");
        assertThat(r3.docsHandle("doc-ref-uuid")).isEqualTo(keySpaces.get("doc").asText());
        assertThat(r3.websHandle("https://ok.example/x")).isEqualTo(keySpaces.get("web").asText());
        assertThat(r3.websCount()).isEqualTo(keySpaces.get("nour").asInt());
    }

    @Test
    void sourcesCompactLongestAndQuoted() {
        SourceRegistry r4 = new SourceRegistry(true);
        String docId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        r4.registerDocument(docId);
        String webId = "https://w.example/f/" + docId;
        r4.registerWeb(webId, "");
        String comp = r4.compactKnownText("mix " + webId + " and " + docId);
        assertThat(comp).isEqualTo(out(R_SOURCES_COMPACT_LONGEST));
        assertThat(r4.decodeKnownText(comp)).isEqualTo(out(R_SOURCES_COMPACT_DECODED));

        String quoted = SourceToolCodec.rewriteQuotedText("a 'x''y' \"z\\\"w\" `t` plain 'un", String::toUpperCase);
        assertThat(quoted).isEqualTo(out(R_SOURCES_QUOTED));
    }

    @Test
    void sourcesEncodeMessagesTwoPass() {
        SourceRegistry r5 = new SourceRegistry(true);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(new ChatMessage("user", "q"));
        msgs.add(ChatMessage.tool("", "knowledge_search", "hit doc-msg-uuid content"));
        ChatMessage asst = new ChatMessage("assistant",
                "cites <kb doc=\"doc-msg-uuid\" chunk_id=\"chunk-msg-uuid\"> here");
        asst.setToolCalls(new ArrayList<>(List.of(
                call("knowledge_search", "{\"knowledge_base_ids\":[\"kb-msg-uuid\"]}"))));
        msgs.add(asst);
        List<ChatMessage> out = r5.encodeMessagesWithPolicies(
                msgs, ToolPolicy::sourceArgumentAllowed, ToolPolicy::sourceOutputAllowed);
        JsonNode expected = rec(R_SOURCES_ENCODE_MESSAGES).get("out");
        assertThat(out.get(1).getContent()).isEqualTo(expected.get("tool").asText());
        assertThat(out.get(2).getContent()).isEqualTo(expected.get("asst").asText());
        assertThat(out.get(2).getToolCalls().get(0).getFunction().getArguments()).isEqualTo(expected.get("args").asText());
        assertThat(r5.expandText(out.get(1).getContent())).isEqualTo(expected.get("tool_expand").asText());
    }

    // ---- registry 聚合 ----

    @Test
    void registryContextVsEvidenceAndSearchResults() {
        Registry r = new Registry(true);
        SourceRegistry.ChunkReference ctxRef = new SourceRegistry.ChunkReference();
        ctxRef.chunkId = "ctx-chunk-1";
        ctxRef.knowledgeId = "ctx-doc-1";
        String h = r.registerContextChunk(ctxRef);
        String hEv = r.registerChunk(chunkRef("ctx-chunk-1", ""));
        String text = r.decodeOutputText("<ref id=\"" + h.toLowerCase() + "\"/> <ref id=\"" + hEv.toLowerCase() + "\"/>");
        JsonNode expected = rec(R_REGISTRY_CONTEXT_VS_EVIDENCE).get("out");
        assertThat(h).isEqualTo(expected.get("h").asText());
        assertThat(hEv).isEqualTo(expected.get("hev").asText());
        assertThat(text).isEqualTo(expected.get("text").asText());

        Registry r2 = new Registry(true);
        SearchResult sr = new SearchResult();
        sr.setId("sr-chunk-1");
        sr.setKnowledgeId("sr-doc-1");
        sr.setKnowledgeBaseId("sr-kb-1");
        sr.setKnowledgeTitle("SR Title");
        sr.setChunkIndex(4);
        sr.setChunkType("text");
        r2.registerSearchResults(Arrays.asList(sr, null));
        assertThat(r2.chunkHandle("sr-chunk-1")).isEqualTo(out(R_REGISTRY_SEARCH_RESULTS));
    }

    @Test
    void registryDecodeResponseAndOrphans() {
        Registry r3 = new Registry(true);
        r3.registerWeb("https://resp.example/1", "R");
        com.ragagent.llm.domain.ChatResponse resp = new com.ragagent.llm.domain.ChatResponse();
        resp.setContent("answer res://0000");
        resp.setToolCalls(new ArrayList<>(List.of(call("web_fetch", "{\"urls\":[\"w1\"]}"))));
        r3.decodeResponse(resp);
        JsonNode expected = rec(R_REGISTRY_DECODE_RESPONSE).get("out");
        assertThat(resp.getContent()).isEqualTo(expected.get("content").asText());
        assertThat(resp.getToolCalls().get(0).getFunction().getArguments()).isEqualTo(expected.get("args").asText());

        Registry r4 = new Registry(true);
        assertThat(r4.orphanResourceHandles("a res://7 b res://7 c res://8"))
                .containsExactlyElementsOf(listOfStrings(rec(R_REGISTRY_ORPHANS).get("out")));
    }
}
