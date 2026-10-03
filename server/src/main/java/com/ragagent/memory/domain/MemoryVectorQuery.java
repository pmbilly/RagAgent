package com.ragagent.memory.domain;

import java.util.List;

/**
 * 一次针对某主体已存向量的语义查找。
 *
 * @param modelId 钉死向量空间。别的模型产生的向量会被**跳过**而不是拿来打分，
 *                因为两者之间的距离没有意义。
 * @param vector  已嵌入的查询向量。
 * @param kinds   限制能返回哪些种类的记忆；空表示所有种类。
 * @param minScore 余弦下限，低于它就不算命中。
 * @param limit   返回多少条命中，最优在前。{@code <= 0} → 20。
 */
public record MemoryVectorQuery(String modelId, float[] vector, List<String> kinds,
                                double minScore, int limit) {

    public static final int DEFAULT_LIMIT = 20;

    public MemoryVectorQuery {
        if (modelId == null) {
            modelId = "";
        }
        if (kinds == null) {
            kinds = List.of();
        }
    }

    /**
     * 前置判断：{@code modelId} 为空 **或** {@code vector} 为 null/长度 0。
     *
     * <p>名字不带 {@code is}/{@code get} 前缀，Jackson 不会误认（本类型也不出响应）。</p>
     */
    public boolean unusable() {
        return modelId.isEmpty() || vector == null || vector.length == 0;
    }

    /** {@code limit <= 0} 时回默认值 20。 */
    public int effectiveLimit() {
        return limit <= 0 ? DEFAULT_LIMIT : limit;
    }
}
