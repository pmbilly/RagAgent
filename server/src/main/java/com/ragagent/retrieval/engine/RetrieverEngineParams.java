package com.ragagent.retrieval.engine;

/**
 * 对照 Go {@code types.RetrieverEngineParams}（tenant.go）：租户有效引擎的一条
 * 「检索类型 → 引擎类型」映射。
 *
 * <p>组件序保持与既有 {@code HybridSearchService.EngineParams} 一致
 * （{@code retrieverType} 在前），JSON 键名沿用 Go 的 {@code retriever_type} /
 * {@code retriever_engine_type}。</p>
 */
public record RetrieverEngineParams(String retrieverType, String retrieverEngineType) {
}
