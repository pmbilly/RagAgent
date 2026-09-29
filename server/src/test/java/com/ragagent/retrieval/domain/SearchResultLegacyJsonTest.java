package com.ragagent.retrieval.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 存量兼容：{@code SearchResult} 的线格式已改 camelCase（契约决策①），但它同时是
 * {@code messages.knowledge_references} 这个 jsonb 列的元素类型——旧行仍是 Go 期的 snake 键。
 * 因此字段上保留 {@code @JsonAlias(旧 snake 名)}：旧行照读，写入一律新格式。
 *
 * <p>Mapper 与 {@code AbstractJsonListTypeHandler} 内部一致（忽略未知属性 + JavaTime 模块），
 * 否则不能代表真实回读路径。</p>
 */
class SearchResultLegacyJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @DisplayName("旧行（snake 键）回读：关键字段不丢")
    void readsLegacySnakePayload() throws Exception {
        String legacy = """
                {"id":"chunk-1","content":"正文","knowledge_id":"doc-1","chunk_index":3,
                 "knowledge_title":"手册.pdf","start_at":10,"end_at":20,"seq":2,"score":0.75,
                 "match_type":1,"sub_chunk_id":["sub-1"],"chunk_type":"text",
                 "parent_chunk_id":"p-1","image_info":"","knowledge_filename":"manual.pdf",
                 "knowledge_source":"file","knowledge_channel":"web","matched_content":"命中片段",
                 "knowledge_description":"描述","knowledge_base_id":"kb-1"}
                """;
        SearchResult sr = MAPPER.readValue(legacy, SearchResult.class);

        assertThat(sr.getId()).isEqualTo("chunk-1");
        assertThat(sr.getKnowledgeId()).isEqualTo("doc-1");
        assertThat(sr.getKnowledgeTitle()).isEqualTo("手册.pdf");
        assertThat(sr.getChunkIndex()).isEqualTo(3);
        assertThat(sr.getStartAt()).isEqualTo(10);
        assertThat(sr.getEndAt()).isEqualTo(20);
        assertThat(sr.getMatchType()).isEqualTo(1);
        assertThat(sr.getSubChunkId()).containsExactly("sub-1");
        assertThat(sr.getParentChunkId()).isEqualTo("p-1");
        assertThat(sr.getKnowledgeFilename()).isEqualTo("manual.pdf");
        assertThat(sr.getKnowledgeBaseId()).isEqualTo("kb-1");
        assertThat(sr.getMatchedContent()).isEqualTo("命中片段");
    }

    @Test
    @DisplayName("新格式（camelCase）与旧格式（snake）读到同一对象")
    void camelAndSnakeAreEquivalent() throws Exception {
        String camel = """
                {"id":"chunk-1","knowledgeId":"doc-1","knowledgeTitle":"手册.pdf","chunkIndex":3,
                 "startAt":10,"endAt":20,"matchType":1,"knowledgeBaseId":"kb-1"}
                """;
        String snake = """
                {"id":"chunk-1","knowledge_id":"doc-1","knowledge_title":"手册.pdf","chunk_index":3,
                 "start_at":10,"end_at":20,"match_type":1,"knowledge_base_id":"kb-1"}
                """;
        SearchResult a = MAPPER.readValue(camel, SearchResult.class);
        SearchResult b = MAPPER.readValue(snake, SearchResult.class);

        assertThat(a.getKnowledgeId()).isEqualTo(b.getKnowledgeId());
        assertThat(a.getKnowledgeTitle()).isEqualTo(b.getKnowledgeTitle());
        assertThat(a.getChunkIndex()).isEqualTo(b.getChunkIndex());
        assertThat(a.getKnowledgeBaseId()).isEqualTo(b.getKnowledgeBaseId());
    }

    @Test
    @DisplayName("写入一律新格式（camelCase），不再产出 snake")
    void writesCamelCaseOnly() throws Exception {
        SearchResult sr = new SearchResult();
        sr.setId("chunk-1");
        sr.setKnowledgeTitle("手册.pdf");

        String json = MAPPER.writeValueAsString(sr);
        assertThat(json).contains("\"knowledgeTitle\":\"手册.pdf\"");
        assertThat(json).doesNotContain("knowledge_title");
    }
}
