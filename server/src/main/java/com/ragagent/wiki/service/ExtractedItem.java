package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 抽取出的单个 entity / concept（对照 Go {@code extractedItem}，
 * wiki_ingest.go L2040-2053）。
 *
 * <p>{@code SourceChunks} 存放源文档里<b>实质性讨论</b>该项的稳定 chunk ID，
 * 由 chunk-citation 阶段填充；非空时 Reduce 阶段用这些 chunk 的<b>逐字原文</b>
 * 作为证据，而不是较短的 {@code Description} / {@code Details} 字段。</p>
 *
 * <p><b>可变 POJO 而非 record</b>：Go 侧多处就地改 {@code item.Slug}
 * （去重合并、身份认领、id 重映射），Java 侧照抄这一形态以便逐行对照。</p>
 */
@JsonPropertyOrder({"name", "slug", "aliases", "description", "details", "source_chunks"})
public class ExtractedItem {

    @JsonProperty("name")
    private String name = "";

    @JsonProperty("slug")
    private String slug = "";

    @JsonProperty("aliases")
    private List<String> aliases = new ArrayList<>();

    @JsonProperty("description")
    private String description = "";

    @JsonProperty("details")
    private String details = "";

    /** 对照 Go 的 {@code source_chunks,omitempty} */
    @JsonProperty("source_chunks")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> sourceChunks;

    public ExtractedItem() {}

    public ExtractedItem(String name, String slug, List<String> aliases,
                         String description, String details) {
        this.name = name == null ? "" : name;
        this.slug = slug == null ? "" : slug;
        setAliases(aliases);
        this.description = description == null ? "" : description;
        this.details = details == null ? "" : details;
    }

    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }

    public String getSlug() { return slug; }
    public void setSlug(String v) { slug = v == null ? "" : v; }

    public List<String> getAliases() { return aliases; }
    public void setAliases(List<String> v) { aliases = v == null ? new ArrayList<>() : v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { description = v == null ? "" : v; }

    public String getDetails() { return details; }
    public void setDetails(String v) { details = v == null ? "" : v; }

    public List<String> getSourceChunks() { return sourceChunks; }
    public void setSourceChunks(List<String> v) { sourceChunks = v; }

    /** 对照 Go 里对 {@code item.SourceChunks} 的 nil 容忍遍历 */
    @JsonIgnore
    public List<String> sourceChunksOrEmpty() {
        return sourceChunks == null ? List.of() : sourceChunks;
    }
}
