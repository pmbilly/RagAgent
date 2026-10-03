package com.ragagent.chatpipeline.support;

/**
 * 匹配类型常量（int 枚举）。
 * {@code com.ragagent.common.retrieval.SearchResult.matchType} 为 int，常量值即其取值。
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
