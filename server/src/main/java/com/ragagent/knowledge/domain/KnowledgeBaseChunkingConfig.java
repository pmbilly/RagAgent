package com.ragagent.knowledge.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * ChunkingConfig。
 * 非指针值类型：chunk_size/chunk_overlap/separators 恒输出（separators null → JSON null）；
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KnowledgeBaseChunkingConfig {

    private int chunkSize;
    private int chunkOverlap;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private List<String> separators;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private List<ParserEngineRule> parserEngineRules;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean enableParentChild;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int parentChunkSize;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int childChunkSize;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String strategy;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int tokenLimit;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private List<String> languages;
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

        public static class ParserEngineRule {
        private List<String> fileTypes;
        private String engine;
        private Boolean xlsxFirstRowAsHeader;

        public List<String> getFileTypes() { return fileTypes; }
        public void setFileTypes(List<String> v) { fileTypes = v; }
        public String getEngine() { return engine; }
        public void setEngine(String v) { engine = v; }
        public Boolean getXlsxFirstRowAsHeader() { return xlsxFirstRowAsHeader; }
        public void setXlsxFirstRowAsHeader(Boolean v) { xlsxFirstRowAsHeader = v; }
    }
}
