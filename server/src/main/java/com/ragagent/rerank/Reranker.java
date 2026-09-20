package com.ragagent.rerank;

import java.util.List;

/**
 * 文档重排客户端（对照 Go {@code rerank.Reranker} 接口）。
 */
public interface Reranker {

    /** 对照 {@code Rerank}：按与 query 的相关性重排 documents。 */
    List<RankResult> rerank(String query, List<String> documents);

    String getModelName();

    String getModelID();
}
