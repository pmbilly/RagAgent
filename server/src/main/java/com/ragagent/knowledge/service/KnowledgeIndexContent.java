package com.ragagent.knowledge.service;

/**
 * 索引文本 = 文档 title 前缀 + 内容（标题与疑问式查询的语义对齐）。
 * 自定义元数据保持文档级作用域——只喂给答案模型/摘要模型一次，
 * 不重复进每个 chunk 的向量。
 */
public final class KnowledgeIndexContent {

    private KnowledgeIndexContent() {}

    /** title（trim 后）为空则原样返回。 */
    public static String build(com.ragagent.knowledge.domain.Knowledge knowledge, String content) {
        if (knowledge == null) {
            return content;
        }
        String title = knowledge.getTitle() == null ? "" : knowledge.getTitle().strip();
        if (title.isEmpty()) {
            return content;
        }
        return title + "\n" + content;
    }
}
