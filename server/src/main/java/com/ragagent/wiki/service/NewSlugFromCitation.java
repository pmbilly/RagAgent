package com.ragagent.wiki.service;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * {@code WikiChunkCitationPrompt} 的 {@code "new_slugs"} 数组里的一项
 * （对照 Go {@code newSlugFromCitation}，wiki_ingest_cite.go L39-50）。
 *
 * <p>与 {@link ExtractedItem} 镜像，但多一个 {@code type} 标签——因为该 prompt
 * 把 entities 与 concepts 放在同一个数组里输出。</p>
 *
 * <p><b>为什么这个类型在 wiki_ingest.go 的所有者手里</b>：Go 的
 * {@code previewNewSlugs}（wiki_ingest.go L1486-1504，本任务范围）要消费它，
 * 所以 Java 侧把它作为共享类型放在同一包内，cite 的翻译直接复用。</p>
 */
@JsonPropertyOrder({"type", "name", "slug", "aliases", "description", "details", "source_chunks"})
public record NewSlugFromCitation(
        @JsonProperty("type") String type,
        @JsonProperty("name") String name,
        @JsonProperty("slug") String slug,
        @JsonProperty("aliases") List<String> aliases,
        @JsonProperty("description") String description,
        @JsonProperty("details") String details,
        @JsonProperty("source_chunks") @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<String> sourceChunks) {

    /** 对照 Go {@code len(it.SourceChunks)}（nil 容忍） */
    public int sourceChunkCount() {
        return sourceChunks == null ? 0 : sourceChunks.size();
    }
}
