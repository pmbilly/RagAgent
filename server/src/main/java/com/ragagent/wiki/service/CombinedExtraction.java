package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * {@code WikiKnowledgeExtractPrompt} / {@code WikiCandidateSlugPrompt} 输出的解析结果：
 * 实体与概念两个列表。
 */
@JsonPropertyOrder({"entities", "concepts"})
public class CombinedExtraction {

    @JsonProperty("entities")
    private List<ExtractedItem> entities = new ArrayList<>();

    @JsonProperty("concepts")
    private List<ExtractedItem> concepts = new ArrayList<>();

    public CombinedExtraction() {}

    public CombinedExtraction(List<ExtractedItem> entities, List<ExtractedItem> concepts) {
        setEntities(entities);
        setConcepts(concepts);
    }

    public List<ExtractedItem> getEntities() { return entities; }
    public void setEntities(List<ExtractedItem> v) { entities = v == null ? new ArrayList<>() : v; }

    public List<ExtractedItem> getConcepts() { return concepts; }
    public void setConcepts(List<ExtractedItem> v) { concepts = v == null ? new ArrayList<>() : v; }
}
