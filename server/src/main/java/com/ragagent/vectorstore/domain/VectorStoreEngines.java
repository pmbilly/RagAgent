package com.ragagent.vectorstore.domain;

import java.util.Set;

/**
 * 对照 Go {@code validEngineTypes}（types/vectorstore.go L87-95）：可注册为
 * DB 向量库的引擎白名单。postgres/sqlite 只能经 env store（RETRIEVE_DRIVER）出现。
 */
public final class VectorStoreEngines {

    private VectorStoreEngines() {
    }

    public static final Set<String> VALID_ENGINE_TYPES = Set.of(
            "elasticsearch", "qdrant", "milvus", "weaviate", "doris",
            "tencent_vectordb", "opensearch");

    public static boolean isValidEngineType(String t) {
        return t != null && VALID_ENGINE_TYPES.contains(t);
    }
}
