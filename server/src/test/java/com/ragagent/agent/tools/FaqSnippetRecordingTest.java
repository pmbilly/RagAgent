package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;

/**
 * FaqSnippet 的 Go 实录（10 条，探针原样调用 Go {@code writeFAQ*XML/
 * faqMatchSnippetFromQueries/searchQueryTokens/truncateSimilarQuestionsForDisplay/
 * appendFAQChunkData}）。XML 投影逐字节比对（含相似问截断的 omitted 标记）。
 */
class FaqSnippetRecordingTest {

    @Test
    void metadataXmlMatchesGoRecording() {
        JsonNode r = RecordingSupport.rec(field("R_FAQ_METADATA_XML"));
        FaqChunkMetadata meta = new FaqChunkMetadata();
        meta.standardQuestion = "如何创建知识库？";
        meta.similarQuestions = List.of("怎么创建知识库？");
        meta.answers = List.of("在控制台点击新建知识库。");
        StringBuilder b = new StringBuilder();
        FaqSnippet.writeFaqMetadataXml(b, meta);
        assertThat(b.toString()).isEqualTo(r.get("out").asText());
    }

    @Test
    void similarQuestionsXmlMatchesGoRecording() {
        JsonNode r8 = RecordingSupport.rec(field("R_FAQ_SIMILAR_8"));
        List<String> qs = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            qs.add("相似问" + i);
        }
        StringBuilder b = new StringBuilder();
        FaqSnippet.writeSimilarQuestionsXml(b, qs);
        assertThat(b.toString()).isEqualTo(r8.get("out").asText());

        JsonNode rNil = RecordingSupport.rec(field("R_FAQ_SIMILAR_NIL"));
        StringBuilder b2 = new StringBuilder();
        FaqSnippet.writeSimilarQuestionsXml(b2, null);
        assertThat(b2.toString()).isEqualTo(rNil.get("out").asText());
    }

    @Test
    void entryXmlMatchesGoRecording() {
        JsonNode r = RecordingSupport.rec(field("R_FAQ_ENTRY_XML"));
        Chunk chunk = faqChunk();
        StringBuilder b = new StringBuilder();
        FaqSnippet.writeFaqEntryXml(b, chunk);
        assertThat(b.toString()).isEqualTo(r.get("out").asText());
        assertThat(b.toString()).doesNotContain("<chunk");
    }

    @Test
    void matchSnippetsMatchGoRecording() {
        FaqChunkMetadata meta = new FaqChunkMetadata();
        meta.standardQuestion = "如何创建知识库？";
        meta.similarQuestions = List.of("怎么创建知识库？");
        meta.answers = List.of("在控制台点击新建知识库。");

        JsonNode hit = RecordingSupport.rec(field("R_FAQ_MATCH_SNIPPET_QUERIES"));
        assertThat(FaqSnippet.faqMatchSnippetFromQueries(meta, List.of("如何创建")))
                .isEqualTo(hit.get("out").asText());

        JsonNode miss = RecordingSupport.rec(field("R_FAQ_MATCH_SNIPPET_QUERIES_MISS"));
        assertThat(FaqSnippet.faqMatchSnippetFromQueries(meta, List.of("无关问题")))
                .isEqualTo(miss.get("out").asText());
    }

    @Test
    void tokensMatchGoRecording() {
        JsonNode r = RecordingSupport.rec(field("R_FAQ_TOKENS"));
        List<String> got = FaqSnippet.searchQueryTokens(
                List.of("Hello, World! 你好 world", "a b", ""));
        assertThat(RecordingSupport.PLAIN.valueToTree(got).toString())
                .isEqualTo(r.get("out").asText());
    }

    @Test
    void truncateDisplayMatchesGoRecording() {
        JsonNode r3 = RecordingSupport.rec(field("R_FAQ_TRUNCATE_DISPLAY_3"));
        FaqSnippet.SimilarQuestionsDisplay d3 =
                FaqSnippet.truncateSimilarQuestionsForDisplay(List.of("q1", "q2", "q3"));
        assertThat(d3.display()).containsExactly("q1", "q2", "q3");
        assertThat(d3.omitted()).isEqualTo(r3.get("omitted").asInt());

        JsonNode r0 = RecordingSupport.rec(field("R_FAQ_TRUNCATE_DISPLAY_0"));
        FaqSnippet.SimilarQuestionsDisplay d0 = FaqSnippet.truncateSimilarQuestionsForDisplay(null);
        assertThat(d0.display()).isNullOrEmpty();
        assertThat(d0.omitted()).isEqualTo(r0.get("omitted").asInt());
    }

    @Test
    void appendChunkDataMatchesGoRecording() {
        JsonNode r = RecordingSupport.rec(field("R_FAQ_APPEND_CHUNK_DATA"));
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("content", "x");
        FaqSnippet.appendFaqChunkData(data, faqChunk());
        assertThat(RecordingSupport.goJsonOfData(data)).isEqualTo(r.get("out").asText());
    }

    /** 对照探针的 chunkFaq（metadata 由 FaqChunkMetadata.toJsonNode 落进 chunk.metadata）。 */
    private static Chunk faqChunk() {
        FaqChunkMetadata meta = new FaqChunkMetadata();
        meta.standardQuestion = "如何创建知识库？";
        meta.similarQuestions = List.of("怎么创建知识库？");
        meta.answers = List.of("在控制台点击新建知识库。");
        Chunk chunk = new Chunk();
        chunk.setId("faq-chunk-1");
        chunk.setChunkType("faq");
        chunk.setChunkIndex(0);
        chunk.setKnowledgeId("kb-doc-1");
        chunk.setContent("Q: test\nSimilar Questions:\n- alt");
        chunk.setMetadata(meta.toJsonNode());
        return chunk;
    }

    private static String field(String name) {
        try {
            return (String) GoRecording45A.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
