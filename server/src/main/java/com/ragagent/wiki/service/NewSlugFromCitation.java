package com.ragagent.wiki.service;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * {@code WikiChunkCitationPrompt} 的 {@code "new_slugs"} 数组里的一项。
 *
 * <p>与 {@link ExtractedItem} 镜像，但多一个 {@code type} 标签——因为该 prompt
 * 把 entities 与 concepts 放在同一个数组里输出。</p>
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

    /** sourceChunks 的数量（null 容忍） */
    public int sourceChunkCount() {
        return sourceChunks == null ? 0 : sourceChunks.size();
    }
}
