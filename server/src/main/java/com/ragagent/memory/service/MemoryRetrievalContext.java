package com.ragagent.memory.service;

import java.util.List;

import com.ragagent.memory.domain.MemoryItem;

/**
 * 记忆贡献给**检索**（而不是回答提示词）的那部分：这个人是谁、他反复在问什么、
 * 他依赖哪些文档。
 *
 * <p>这是记忆在一个知识库产品里最挣工分的部分。同一个问题对不同的人意味着不同的东西——
 * 做医疗影像的人问"分割怎么调"不该检索到与做自动驾驶的人相同的段落——
 * 而这个差异必须在**检索之前**被用上，不是之后。</p>
 *
 * <h2>⚠️ 命名</h2>
 * <p>类名带 {@code Memory} 前缀以示
 * 它归本模块（检索模块另有大量以 {@code Retrieval*} 起头的类型，
 * 不加前缀会与 {@code retrieval.domain} 的东西混淆）。</p>
 *
 * @param background 这个人的一段紧凑描述，给查询改写器用。
 * @param interests  他反复回来的那些主题。
 * @param documents  他依赖的文档标题，作为改写器的词汇。
 * @param items      以上内容背后的记忆条目，好让界面能显示是什么影响了检索，
 *                   而不是让这件事隐形地发生。
 */
public record MemoryRetrievalContext(String background, List<String> interests,
                                     List<String> documents, List<MemoryItem> items) {

    /** 零值。 */
    public static final MemoryRetrievalContext EMPTY =
            new MemoryRetrievalContext("", null, null, null);

    /**
     * 是否没有贡献。
     *
     * <p>注意判据**不含 items**：三个展示项都空就是"没有贡献"，
     * 哪怕条目列表非空（实际不会发生，但口径如此）。</p>
     */
    public boolean empty() {
        return (background == null || background.isEmpty())
                && (interests == null || interests.isEmpty())
                && (documents == null || documents.isEmpty());
    }
}
