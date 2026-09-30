package com.ragagent.knowledge.domain;

import java.util.List;
import com.ragagent.common.web.JsonMappers;

/**
 * 知识库分块配置（jsonb 列 {@code config} 的形状）：分块尺寸与重叠、分隔符、
 * 解析器引擎规则、父子块、token 上限等。
 *
 * <p>字段一律输出（{@code separators} 为 null 时输出 JSON null）。
 */
public class KnowledgeBaseChunkingConfig {

    private int chunkSize;
    private int chunkOverlap;
    private List<String> separators;
    private List<ParserEngineRule> parserEngineRules;
    private boolean enableParentChild;
    private int parentChunkSize;
    private int childChunkSize;
    private String strategy;
    private int tokenLimit;
    private List<String> languages;
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

        /** 单条解析器引擎匹配规则：按文件扩展名选引擎，可带 xlsx 首行作表头开关。 */
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

    /** 从 KB 配置 jsonb 读取（null/解析失败 → 默认值）。 */
    public static KnowledgeBaseChunkingConfig from(com.fasterxml.jackson.databind.JsonNode node) {
    if (node == null || node.isNull()) {
        return new KnowledgeBaseChunkingConfig();
    }
    try {
        KnowledgeBaseChunkingConfig parsed = JsonMappers.lenient().convertValue(node, KnowledgeBaseChunkingConfig.class);
        return parsed == null ? new KnowledgeBaseChunkingConfig() : parsed;
    } catch (IllegalArgumentException e) {
        return new KnowledgeBaseChunkingConfig();
    }
    }
}
