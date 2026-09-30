package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.agent.tools.DocChunkSupport.ChunkPage;
import com.ragagent.agent.tools.DocChunkSupport.ImageInfoCollector;
import com.ragagent.agent.tools.DocChunkSupport.KnowledgeInfoReader;
import com.ragagent.agent.tools.DocChunkSupport.KnowledgeInfoView;
import com.ragagent.agent.tools.DocChunkSupport.PagedChunks;
import com.ragagent.agent.tools.SearchAuth.TagView;
import com.ragagent.knowledge.domain.Chunk;

/**
 * 波 4.5b 回放：wiki_read_source_doc / get_document_info / list_knowledge_chunks
 * （对照 Go 实录，探针 {@code zz_rec45b_doc_tools_test.go}）。
 * 图片富化 collector 返回 Go CollectImageInfoByChunkIDs 真实合并产物
 * （struct 全字段序列化串）。
 */
class DocToolsRecordingTest {

    /** Go types.ImageInfo 全字段序列化（json.Marshal struct 序）。 */
    static final String MERGED_P1 =
            "[{\"url\":\"http://x/1.png\",\"original_url\":\"\",\"start_pos\":0,\"end_pos\":0,"
                    + "\"caption\":\"  图 一  \",\"ocr_text\":\"识别文字\"}]";

    // ==================== Fakes ====================

    static final class FakeKnowledge implements KnowledgeInfoReader {
        final Map<String, KnowledgeInfoView> byID = new LinkedHashMap<>();
        final Map<String, RuntimeException> byIDErr = new LinkedHashMap<>();

        @Override
        public KnowledgeInfoView byIdOnly(String knowledgeId) {
            RuntimeException err = byIDErr.get(knowledgeId);
            if (err != null) {
                throw err;
            }
            return byID.get(knowledgeId);
        }

        @Override
        public Map<String, List<TagView>> fetchTags(List<String> knowledgeIds) {
            Map<String, List<TagView>> out = new LinkedHashMap<>();
            for (String id : knowledgeIds) {
                out.put(id, List.of());
            }
            return out;
        }
    }

    static final class FakeRepo implements PagedChunks {
        final Map<String, List<Chunk>> byKnowledge = new LinkedHashMap<>();
        final Map<String, Long> totals = new LinkedHashMap<>();
        final Map<String, RuntimeException> listErr = new LinkedHashMap<>();

        @Override
        public ChunkPage listPaged(long tenantId, String knowledgeId, int page, int pageSize) {
            RuntimeException err = listErr.get(knowledgeId);
            if (err != null) {
                throw err;
            }
            List<Chunk> all = byKnowledge.getOrDefault(knowledgeId, List.of());
            long total = totals.getOrDefault(knowledgeId, 0L);
            int start = (page - 1) * pageSize;
            if (start < 0 || start >= all.size()) {
                return new ChunkPage(List.of(), total);
            }
            int end = Math.min(start + pageSize, all.size());
            return new ChunkPage(new ArrayList<>(all.subList(start, end)), total);
        }
    }

    /** 对照 zzFakeChunkRepo.children + Go CollectImageInfoByChunkIDs 产物。 */
    static final class FakeImageCollector implements ImageInfoCollector {
        final Map<String, String> merged = new LinkedHashMap<>();

        @Override
        public Map<String, String> collect(long tenantId, List<String> chunkIds) {
            Map<String, String> out = new LinkedHashMap<>();
            for (String id : chunkIds) {
                String v = merged.get(id);
                if (v != null) {
                    out.put(id, v);
                }
            }
            return out.isEmpty() ? null : out;
        }
    }

    static Chunk textChunk(String id, String knowledgeId, String kb, int idx, String content) {
        Chunk c = new Chunk();
        c.setId(id);
        c.setKnowledgeId(knowledgeId);
        c.setKnowledgeBaseId(kb);
        c.setTenantId(10002L);
        c.setChunkIndex(idx);
        c.setChunkType("text");
        c.setContent(content);
        c.setIsEnabled(true);
        c.setStartAt(idx * 100);
        c.setEndAt(idx * 100 + 99);
        return c;
    }

    static Chunk faqChunkC1() {
        Chunk c = new Chunk();
        c.setId("c1");
        c.setKnowledgeId("d1");
        c.setKnowledgeBaseId("kb1");
        c.setTenantId(10002L);
        c.setChunkIndex(0);
        c.setChunkType("faq");
        c.setIsEnabled(true);
        c.setMetadata(readNode("{\"standardQuestion\":\"  如何申请退款？  \","
                + "\"answers\":[\"原路退回\",\"余额退回\"],"
                + "\"similarQuestions\":[\"怎么退款\",\"退款要多久\",\"q3\",\"q4\",\"q5\",\"q6\",\"q7\"]}"));
        return c;
    }

    static com.fasterxml.jackson.databind.JsonNode readNode(String json) {
        try {
            return RecordingSupport.PLAIN.readTree(json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static FakeKnowledge docKnowledge() {
        FakeKnowledge kn = new FakeKnowledge();
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("作者", "张三");
        kn.byID.put("d1", new KnowledgeInfoView("d1", 10002, "kb1", "源文档一", "",
                "file", "upload", "report.pdf", "pdf", 2048, "completed", md));
        kn.byID.put("d2", new KnowledgeInfoView("d2", 10002, "kb1", "网页文档", "",
                "url", "https://example.com/a", "", "", 0, "processing", null));
        kn.byID.put("ds1", new KnowledgeInfoView("ds1", 10002, "kb1", "小文件", "",
                "file", "", "a.bin", "bin", 512, "pending", null));
        kn.byID.put("ds2", new KnowledgeInfoView("ds2", 10002, "kb1", "无大小", "",
                "file", "", "b.bin", "bin", 0, "failed", null));
        kn.byID.put("ds3", new KnowledgeInfoView("ds3", 10002, "kb1", "三兆文件", "",
                "file", "", "c.bin", "bin", 3 * 1024 * 1024, "success", null));
        kn.byIDErr.put("d9", new RuntimeException("db exploded"));
        return kn;
    }

    static SearchTarget.SearchTargets kb1Targets() {
        return new SearchTarget.SearchTargets(List.of(SearchTarget.wholeKb("kb1", 10002)));
    }

    static SearchTarget.SearchTargets kb2OnlyTargets() {
        return new SearchTarget.SearchTargets(List.of(SearchTarget.wholeKb("kb2", 10002)));
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

    // ==================== wiki_read_source_doc ====================

    private static FakeRepo srcRepo() {
        FakeRepo repo = new FakeRepo();
        List<Chunk> chunks = List.of(
                textChunk("p1", "d1", "kb1", 0, "第一段：订单介绍。\n包含多行。"),
                textChunk("p2", "d1", "kb1", 1, "第二段：退款流程说明。"),
                textChunk("p3", "d1", "kb1", 2, "第三段：联系方式。"));
        repo.byKnowledge.put("d1", chunks);
        repo.totals.put("d1", 3L);
        return repo;
    }

    @Test
    void wikiReadSourceDoc() {
        FakeKnowledge kn = docKnowledge();
        FakeRepo repo = srcRepo();
        FakeImageCollector collector = new FakeImageCollector();
        collector.merged.put("p1", MERGED_P1);

        WikiReadSourceDocTool tool = new WikiReadSourceDocTool(kn, repo, collector, null);

        assertToolResult("src_preview", tool.execute(req("{\"knowledge_id\":\"d1\"}")),
                rec("wiki_read_source_doc_src_preview"));
        assertToolResult("src_query", tool.execute(req("{\"knowledge_id\":\"d1\",\"query\":\"退款\"}")),
                rec("wiki_read_source_doc_src_query"));
        assertToolResult("src_query_nomatch",
                tool.execute(req("{\"knowledge_id\":\"d1\",\"query\":\"不存在xyz\"}")),
                rec("wiki_read_source_doc_src_query_nomatch"));
        assertToolResult("src_range",
                tool.execute(req("{\"knowledge_id\":\"d1\",\"start_chunk_index\":2,\"end_chunk_index\":3}")),
                rec("wiki_read_source_doc_src_range"));
        assertToolResult("src_range_window",
                tool.execute(req("{\"knowledge_id\":\"d1\",\"start_chunk_index\":2}")),
                rec("wiki_read_source_doc_src_range_window"));
        assertToolResult("src_range_oob",
                tool.execute(req("{\"knowledge_id\":\"d1\",\"start_chunk_index\":10,\"end_chunk_index\":12}")),
                rec("wiki_read_source_doc_src_range_oob"));
        assertToolResult("src_range_clamp",
                tool.execute(req("{\"knowledge_id\":\"d1\",\"start_chunk_index\":1,\"end_chunk_index\":99}")),
                rec("wiki_read_source_doc_src_range_clamp"));
    }

    @Test
    void wikiReadSourceDocImagesAndCap() {
        FakeKnowledge kn = docKnowledge();

        // src_images
        FakeRepo imgRepo = new FakeRepo();
        Chunk q1 = textChunk("q1", "d1", "kb1", 0, "带图段落。");
        Chunk q2 = textChunk("q2", "d1", "kb1", 1, "已有图的段落。");
        q2.setImageInfo("[{\"url\":\"http://x/2.png\",\"caption\":\"已有图\",\"ocr_text\":\"\"}]");
        imgRepo.byKnowledge.put("d1", List.of(q1, q2));
        imgRepo.totals.put("d1", 2L);
        FakeImageCollector imgCollector = new FakeImageCollector();
        imgCollector.merged.put("q1", MERGED_P1);
        WikiReadSourceDocTool imgTool = new WikiReadSourceDocTool(kn, imgRepo, imgCollector, null);
        assertToolResult("src_images", imgTool.execute(req("{\"knowledge_id\":\"d1\"}")),
                rec("wiki_read_source_doc_src_images"));

        // src_cap20
        FakeRepo capRepo = new FakeRepo();
        List<Chunk> capChunks = new ArrayList<>();
        String alphabet = "abcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i < 22; i++) {
            capChunks.add(textChunk("z" + "a".repeat(i % 3) + alphabet.charAt(i % 26), "d1", "kb1", i,
                    "目标词 第" + (char) ('0' + i / 10) + (char) ('0' + i % 10) + " 段"));
        }
        capRepo.byKnowledge.put("d1", capChunks);
        capRepo.totals.put("d1", 22L);
        WikiReadSourceDocTool capTool = new WikiReadSourceDocTool(kn, capRepo, null, null);
        assertToolResult("src_cap20", capTool.execute(req("{\"knowledge_id\":\"d1\",\"query\":\"目标词\"}")),
                rec("wiki_read_source_doc_src_cap20"));
    }

    @Test
    void wikiReadSourceDocErrors() {
        FakeKnowledge kn = docKnowledge();
        FakeRepo repo = srcRepo();

        WikiReadSourceDocTool tool = new WikiReadSourceDocTool(kn, repo, null, null);
        assertToolResult("src_blank_id", tool.execute(req("{}")),
                rec("wiki_read_source_doc_src_blank_id"));
        assertToolResult("src_service_err", tool.execute(req("{\"knowledge_id\":\"d9\"}")),
                rec("wiki_read_source_doc_src_service_err"));

        FakeKnowledge missing = new FakeKnowledge();
        WikiReadSourceDocTool missingTool = new WikiReadSourceDocTool(missing, repo, null, null);
        assertToolResult("src_not_found", missingTool.execute(req("{\"knowledge_id\":\"d1\"}")),
                rec("wiki_read_source_doc_src_not_found"));

        WikiReadSourceDocTool scoped = new WikiReadSourceDocTool(kn, repo, null, kb2OnlyTargets());
        assertToolResult("src_scope_denied", scoped.execute(req("{\"knowledge_id\":\"d1\"}")),
                rec("wiki_read_source_doc_src_scope_denied"));

        FakeImageCollector collector = new FakeImageCollector();
        collector.merged.put("p1", MERGED_P1);
        WikiReadSourceDocTool allowed = new WikiReadSourceDocTool(kn, repo, collector, kb1Targets());
        assertToolResult("src_scope_allowed",
                allowed.execute(req("{\"knowledge_id\":\"d1\",\"query\":\"退款\"}")),
                rec("wiki_read_source_doc_src_scope_allowed"));
    }

    // ==================== get_document_info ====================

    @Test
    void getDocumentInfo() {
        FakeKnowledge kn = docKnowledge();
        Chunk c1 = faqChunkC1();
        Chunk c2 = new Chunk();
        c2.setId("c2");
        c2.setKnowledgeId("d1");
        c2.setKnowledgeBaseId("kb1");
        c2.setTenantId(10002L);
        c2.setChunkIndex(1);
        c2.setChunkType("faq");
        c2.setIsEnabled(false);

        FakeRepo repo = new FakeRepo();
        repo.byKnowledge.put("d1", List.of(
                textChunk("e1", "d1", "kb1", 0, "x"), textChunk("e2", "d1", "kb1", 1, "y")));
        repo.totals.put("d1", 7L);
        repo.byKnowledge.put("ds1", List.of(textChunk("f1", "ds1", "kb1", 0, "x")));
        repo.totals.put("ds1", 1L);
        repo.byKnowledge.put("ds2", List.of());
        repo.totals.put("ds2", 0L);
        repo.byKnowledge.put("ds3", List.of());
        repo.totals.put("ds3", 3L);

        Map<String, Chunk> byID = new LinkedHashMap<>();
        byID.put("c1", c1);
        byID.put("c2", c2);

        GetDocumentInfoTool tool = new GetDocumentInfoTool(kn, byID::get, repo, kb1Targets());

        assertToolResult("info_basic", tool.execute(req("{\"knowledge_ids\":[\"d1\",\"d2\"]}")),
                rec("get_document_info_info_basic"));
        assertToolResult("info_faq", tool.execute(req("{\"faq_ids\":[\"c1\"]}")),
                rec("get_document_info_info_faq"));
        assertToolResult("info_mixed",
                tool.execute(req("{\"knowledge_ids\":[\"d1\",\"d9\"],\"faq_ids\":[\"c1\",\"c2\"]}")),
                rec("get_document_info_info_mixed"));
        assertToolResult("info_all_fail", tool.execute(req("{\"knowledge_ids\":[\"d9\"]}")),
                rec("get_document_info_info_all_fail"));
        assertToolResult("info_empty", tool.execute(req("{}")),
                rec("get_document_info_info_empty"));
        assertToolResult("info_filesize",
                tool.execute(req("{\"knowledge_ids\":[\"ds1\",\"ds2\",\"ds3\"]}")),
                rec("get_document_info_info_filesize"));
    }

    // ==================== list_knowledge_chunks ====================

    @Test
    void listKnowledgeChunks() {
        FakeKnowledge kn = docKnowledge();
        Chunk c1 = faqChunkC1();

        FakeRepo repo = new FakeRepo();
        repo.byKnowledge.put("d1", List.of(
                textChunk("g1", "d1", "kb1", 0, "第一块内容"),
                textChunk("g2", "d1", "kb1", 1, "第二块内容"),
                textChunk("g3", "d1", "kb1", 2, "")));
        repo.totals.put("d1", 3L);
        Chunk imgListChunk = textChunk("g4", "d2", "kb1", 0, "图片块");
        imgListChunk.setImageInfo("[{\"url\":\"http://x/3.png\",\"caption\":\"清单图\",\"ocr_text\":\"清单OCR\"},"
                + "{\"url\":\"\",\"caption\":\"仅说明\",\"ocr_text\":\"\"}]");
        repo.byKnowledge.put("d2", List.of(imgListChunk));
        repo.totals.put("d2", 1L);
        repo.byKnowledge.put("d0", List.of());
        repo.totals.put("d0", 0L);

        Map<String, Chunk> byID = new LinkedHashMap<>();
        byID.put("c1", c1);
        byID.put("g9", textChunk("g9", "d1", "kb1", 5, "单块内容"));

        ListKnowledgeChunksTool tool = new ListKnowledgeChunksTool(kn, byID::get, repo,
                new FakeImageCollector(), kb1Targets());

        assertToolResult("list_knowledge", tool.execute(req("{\"knowledge_id\":\"d1\",\"limit\":2}")),
                rec("list_knowledge_chunks_list_knowledge"));
        assertToolResult("list_paged",
                tool.execute(req("{\"knowledge_id\":\"d1\",\"limit\":1,\"offset\":2}")),
                rec("list_knowledge_chunks_list_paged"));
        assertToolResult("list_oob",
                tool.execute(req("{\"knowledge_id\":\"d1\",\"limit\":2,\"offset\":5}")),
                rec("list_knowledge_chunks_list_oob"));
        assertToolResult("list_empty", tool.execute(req("{\"knowledge_id\":\"d0\"}")),
                rec("list_knowledge_chunks_list_empty"));
        assertToolResult("list_images", tool.execute(req("{\"knowledge_id\":\"d2\"}")),
                rec("list_knowledge_chunks_list_images"));
        assertToolResult("list_faq", tool.execute(req("{\"faq_id\":\"c1\"}")),
                rec("list_knowledge_chunks_list_faq"));
        assertToolResult("list_chunk", tool.execute(req("{\"chunk_id\":\"g9\"}")),
                rec("list_knowledge_chunks_list_chunk"));
        assertToolResult("list_missing", tool.execute(req("{}")),
                rec("list_knowledge_chunks_list_missing"));

        ListKnowledgeChunksTool kb2Tool = new ListKnowledgeChunksTool(kn, byID::get, repo,
                new FakeImageCollector(), kb2OnlyTargets());
        assertToolResult("list_unauthorized", kb2Tool.execute(req("{\"knowledge_id\":\"d1\"}")),
                rec("list_knowledge_chunks_list_unauthorized"));
    }
}
