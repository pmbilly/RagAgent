package com.ragagent.chatpipeline;

/**
 * 匹配类型常量（对照 Go {@code types.MatchType} int 枚举，internal/types/embedding.go:13-27）。
 * Java 的 {@code retrieval.domain.SearchResult.matchType} 是 int，常量按 Go 的 iota 值。
 */
public final class MatchTypes {

    private MatchTypes() {}

    public static final int EMBEDDING = 0;
    public static final int KEYWORDS = 1;
    public static final int NEAR_BY_CHUNK = 2;
    public static final int HISTORY = 3;
    public static final int PARENT_CHUNK = 4;
    public static final int RELATION_CHUNK = 5;
    public static final int GRAPH = 6;
    public static final int WEB_SEARCH = 7;
    public static final int DIRECT_LOAD = 8; // Deprecated: 保留序列化枚举值
    public static final int DATA_ANALYSIS = 9;
}
