package com.ragagent.retrieval.engine;

/**
 * 分数归一化——对照 Go {@code internal/application/service/retriever/normalizer.go}
 * 全文（ScoreNormalizer 接口 + EngineAwareNormalizer + clamp01）。
 *
 * <h2>照抄点（Go 注释原文的语义）</h2>
 * <ul>
 *   <li>只归一化<b>向量</b>分；关键词（BM25）分是正数无界，重缩放会塌掉长尾——
 *       原样透传（下游 RRF 按秩融合，对刻度免疫）。</li>
 *   <li>Milvus 是唯一把<b>带符号</b>余弦 [-1,1] 暴露给归一器的引擎 →
 *       {@code (score+1)/2} 后再 clamp01（防 1.0000002 之类越界值漏出）。</li>
 *   <li>其余已落地的引擎（elasticsearch/opensearch/weaviate/postgres/sqlite/qdrant/
 *       tencent_vectordb/doris + 两个死枚举 elastic_faiss/infinity）到达时已在 [0,1]
 *       ——逐引擎的推导见 Go normalizer.go 结构体 godoc（Lucene script_score 非负不变量 /
 *       OpenSearch k-NN scoreTranslation / Weaviate certainty / pgvector 等的
 *       IR 归一化旁注）。</li>
 *   <li>未知引擎防御性 clamp；"未知引擎"的 WARN 由扇出调用方按请求去重发出
 *       （{@code Normalize} 本身保持无锁无 IO）。</li>
 * </ul>
 *
 * <p>实现必须并发安全且不做 IO（热循环内调用，不落日志不阻塞）。</p>
 */
public final class EngineAwareNormalizer implements ScoreNormalizer {

    /** 对照 {@code EngineAwareNormalizer{}}（无状态单例语义）。 */
    public static final EngineAwareNormalizer INSTANCE = new EngineAwareNormalizer();

    @Override
    public double normalize(double score, String retrieverType, String engineType) {
        if (!EngineTypes.RETRIEVER_VECTOR.equals(retrieverType)) {
            // BM25 与其他非向量检索：透传。RRF 按秩融合对混合刻度输入是正确的。
            return score;
        }
        switch (engineType == null ? "" : engineType) {
            case EngineTypes.ENGINE_MILVUS:
                // 原始余弦 [-1,1] → [0,1]。行为不端的引擎返回 1.0000002 也会被
                // clamp01 再拦一道（调用方随后按分排序）。
                return clamp01((score + 1) / 2);
            case EngineTypes.ENGINE_ELASTICSEARCH:
            case "elastic_faiss":
            case EngineTypes.ENGINE_OPENSEARCH:
            case EngineTypes.ENGINE_WEAVIATE:
            case EngineTypes.ENGINE_POSTGRES:
            case EngineTypes.ENGINE_SQLITE:
            case EngineTypes.ENGINE_QDRANT:
            case "infinity":
            case EngineTypes.ENGINE_TENCENT_VECTORDB:
            case EngineTypes.ENGINE_DORIS:
                // 到达时已在 [0,1]。elastic_faiss / infinity 是死枚举引用——case 保留
                // 是为了对声明过的引擎常量穷尽，但生产代码不会返回它们。
                return clamp01(score);
            default:
                // 未知引擎。防御性 clamp；WARN 由调用方发。
                return clamp01(score);
        }
    }

    /**
     * 对照 {@code clamp01}：任何 double 安全落入 [0,1]，包括 NaN/Inf
     * （否则会破坏下游排序的严格弱序）。±Inf 分别被 {@code <=0} / {@code >=1} 覆盖，
     * 与 Go 的 IsInf 分支等价。
     */
    static double clamp01(double s) {
        if (Double.isNaN(s)) {
            return 0;
        }
        if (s <= 0) {
            return 0;
        }
        if (s >= 1) {
            return 1;
        }
        return s;
    }
}
