package com.ragagent.knowledge.chunker;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切分配置（对照 Go internal/infrastructure/chunker/splitter.go:62 SplitterConfig）。
 *
 * <p>Strategy 与 TokenLimit 由 strategy.go 入口消费；legacy 的 SplitText 路径
 * 只使用 chunkSize / chunkOverlap / separators。</p>
 *
 * <p>注意：enable_parent_child / parent_chunk_size / parser_engine_rules /
 * table_metadata_instructions / keep_separator 属于知识库级配置
 * （Go internal/types/knowledgebase.go ChunkingConfig），不在 chunker 包内，
 * 故不包含在本类中。</p>
 */
public class SplitterConfig {

    /** 对照 Go DefaultChunkSize（splitter.go:104）。 */
    public static final int DEFAULT_CHUNK_SIZE = 512;
    /** 对照 Go DefaultChunkOverlap（splitter.go:105）。 */
    public static final int DEFAULT_CHUNK_OVERLAP = 80;
    /** 对照 Go DefaultConfig 默认分隔符（splitter.go:113）。 */
    public static final List<String> DEFAULT_SEPARATORS = List.of("\n\n", "\n", "。");

    private int chunkSize;
    private int chunkOverlap;
    private List<String> separators = new ArrayList<>();
    /** 空串 = legacy（向后兼容），见 strategy.go:18-24 合法值。 */
    private String strategy = "";
    /** 0 = 使用 ChunkSize 字符数（strategy.go:70）。 */
    private int tokenLimit;
    private List<String> languages = new ArrayList<>();

    public SplitterConfig() {
    }

    /** 复制构造（对照 Go 结构体值拷贝语义）。 */
    public SplitterConfig(SplitterConfig other) {
        this.chunkSize = other.chunkSize;
        this.chunkOverlap = other.chunkOverlap;
        this.separators = new ArrayList<>(other.separators);
        this.strategy = other.strategy;
        this.tokenLimit = other.tokenLimit;
        this.languages = new ArrayList<>(other.languages);
    }

    /** 对照 Go DefaultConfig()（splitter.go:109）。 */
    public static SplitterConfig defaultConfig() {
        SplitterConfig cfg = new SplitterConfig();
        cfg.chunkSize = DEFAULT_CHUNK_SIZE;
        cfg.chunkOverlap = DEFAULT_CHUNK_OVERLAP;
        cfg.separators = new ArrayList<>(DEFAULT_SEPARATORS);
        return cfg;
    }

    /**
     * 知识库配置 → chunker 配置（对照 Go buildSplitterConfig，
     * internal/application/service/knowledge.go：KB 配置 0 值由 ensureDefaults 回退 512/80）。
     * parent/child 派生见 {@link Chunker#deriveParentChildConfigs}。
     */
    public static SplitterConfig fromKb(com.ragagent.knowledge.domain.KbChunkingConfig kb) {
        SplitterConfig cfg = new SplitterConfig();
        if (kb == null) {
            return cfg;
        }
        cfg.chunkSize = kb.getChunkSize();
        cfg.chunkOverlap = kb.getChunkOverlap();
        if (kb.getSeparators() != null) {
            cfg.separators = new ArrayList<>(kb.getSeparators());
        }
        cfg.strategy = kb.getStrategy() == null ? "" : kb.getStrategy();
        cfg.tokenLimit = kb.getTokenLimit();
        if (kb.getLanguages() != null) {
            cfg.languages = new ArrayList<>(kb.getLanguages());
        }
        return cfg;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public List<String> getSeparators() {
        return separators;
    }

    public void setSeparators(List<String> separators) {
        this.separators = separators == null ? new ArrayList<>() : new ArrayList<>(separators);
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy == null ? "" : strategy;
    }

    public int getTokenLimit() {
        return tokenLimit;
    }

    public void setTokenLimit(int tokenLimit) {
        this.tokenLimit = tokenLimit;
    }

    public List<String> getLanguages() {
        return languages;
    }

    public void setLanguages(List<String> languages) {
        this.languages = languages == null ? new ArrayList<>() : new ArrayList<>(languages);
    }
}
