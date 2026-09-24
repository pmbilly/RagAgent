package com.ragagent.evaluation.metric;

import java.util.List;

/**
 * 对照 Go {@code types.MetricInput}（internal/types/evaluation.go）：检索真值
 * （多查询，每查询一个相关 ID 集合）+ 命中 ID 序列 + 生成文本/参考答案。
 *
 * <p>Go 以字段名直用（struct 字面量）；Java 保持 public 字段，默认值为 Go 零值
 * 语义（{@code nil} 切片 → 空列表、{@code ""} 空串）。</p>
 */
public final class MetricInput {

    /** 对照 RetrievalGT [][]int（每查询一个相关 ID 组）。 */
    public List<List<Integer>> retrievalGT = List.of();
    /** 对照 RetrievalIDs []int（命中序列，跨查询共享）。 */
    public List<Integer> retrievalIDs = List.of();
    /** 对照 GeneratedTexts（待评文本）。 */
    public String generatedTexts = "";
    /** 对照 GeneratedGT（参考答案）。 */
    public String generatedGT = "";

    public MetricInput() {
    }

    public MetricInput(List<List<Integer>> retrievalGT, List<Integer> retrievalIDs,
                       String generatedTexts, String generatedGT) {
        this.retrievalGT = retrievalGT == null ? List.of() : retrievalGT;
        this.retrievalIDs = retrievalIDs == null ? List.of() : retrievalIDs;
        this.generatedTexts = generatedTexts == null ? "" : generatedTexts;
        this.generatedGT = generatedGT == null ? "" : generatedGT;
    }

    /** 检索类指标输入（对照 Go 测试只填 RetrievalGT/RetrievalIDs 的用法）。 */
    public static MetricInput retrieval(List<List<Integer>> gt, List<Integer> ids) {
        return new MetricInput(gt, ids, "", "");
    }

    /** 生成类指标输入（对照只填 GeneratedTexts/GeneratedGT 的用法）。 */
    public static MetricInput generation(String texts, String gt) {
        return new MetricInput(List.of(), List.of(), texts, gt);
    }
}
