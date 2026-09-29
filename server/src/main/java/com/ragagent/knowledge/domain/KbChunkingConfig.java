package com.ragagent.knowledge.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * ChunkingConfig。
 * 非指针值类型：chunk_size/chunk_overlap/separators 恒输出（separators null → JSON null）；
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "chunk_size", "chunk_overlap", "separators", "parser_engine_rules",
        "enable_parent_child", "parent_chunk_size", "child_chunk_size",
        "strategy", "token_limit", "languages", "table_metadata_instructions"
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbChunkingConfig {

    @JsonProperty("chunk_size")
    private int chunkSize;
    @JsonProperty("chunk_overlap")
    private int chunkOverlap;
    @JsonProperty("separators")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private List<String> separators;
    @JsonProperty("parser_engine_rules")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private List<ParserEngineRule> parserEngineRules;
    @JsonProperty("enable_parent_child")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean enableParentChild;
    @JsonProperty("parent_chunk_size")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int parentChunkSize;
    @JsonProperty("child_chunk_size")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int childChunkSize;
    @JsonProperty("strategy")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String strategy;
    @JsonProperty("token_limit")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int tokenLimit;
    @JsonProperty("languages")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private List<String> languages;
    @JsonProperty("table_metadata_instructions")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String tableMetadataInstructions;

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int v) { chunkSize = v; }
    public int getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(int v) { chunkOverlap = v; }
    public List<String> getSeparators() { return separators; }
    public void setSeparators(List<String> v) { separators = v; }
    public List<ParserEngineRule> getParserEngineRules() { return parserEngineRules; }
    public void setParserEngineRules(List<ParserEngineRule> v) { parserEngineRules = v; }
    public boolean isEnableParentChild() { return enableParentChild; }
    public void setEnableParentChild(boolean v) { enableParentChild = v; }
    public int getParentChunkSize() { return parentChunkSize; }
    public void setParentChunkSize(int v) { parentChunkSize = v; }
    public int getChildChunkSize() { return childChunkSize; }
    public void setChildChunkSize(int v) { childChunkSize = v; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String v) { strategy = v; }
    public int getTokenLimit() { return tokenLimit; }
    public void setTokenLimit(int v) { tokenLimit = v; }
    public List<String> getLanguages() { return languages; }
    public void setLanguages(List<String> v) { languages = v; }
    public String getTableMetadataInstructions() { return tableMetadataInstructions; }
    public void setTableMetadataInstructions(String v) { tableMetadataInstructions = v; }

    @JsonPropertyOrder({"file_types", "engine", "xlsx_first_row_as_header"})
    public static class ParserEngineRule {
        @JsonProperty("file_types")
        private List<String> fileTypes;
        @JsonProperty("engine")
        private String engine;
        @JsonProperty("xlsx_first_row_as_header")
        private Boolean xlsxFirstRowAsHeader;

        public List<String> getFileTypes() { return fileTypes; }
        public void setFileTypes(List<String> v) { fileTypes = v; }
        public String getEngine() { return engine; }
        public void setEngine(String v) { engine = v; }
        public Boolean getXlsxFirstRowAsHeader() { return xlsxFirstRowAsHeader; }
        public void setXlsxFirstRowAsHeader(Boolean v) { xlsxFirstRowAsHeader = v; }
    }
}
