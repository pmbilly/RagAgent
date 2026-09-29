package com.ragagent.knowledge.service;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.domain.KnowledgeBase;

/**
 * FAQ 索引行组装。2026-09-22 走查批接线：此前的
 * 索引步是「embedding runtime is not available」占位。
 * <p><b>两种索引模式</b>（kb.faq_config）：</p>
 * <ul>
 *   <li>question_index_mode=combined（默认）：单行，content = 标准问 + 相似问 +
 *       （question_answer 模式时）答案；source_id = chunkID；</li>
 *   <li>question_index_mode=separate：标准问一行（source_id=chunkID）+ 每个相似问
 *       一行（source_id = {@code chunkID-<序号>}）。</li>
 * </ul>
 */
final class FaqIndexRows {

    private FaqIndexRows() {}

    static List<VectorStoreService.IndexRow> build(KnowledgeBase kb, Chunk chunk) {
        String indexMode = faqIndexMode(kb);
        String questionIndexMode = faqQuestionIndexMode(kb);

        FaqChunkMetadata meta = FaqChunkMetadata.fromJson(chunk.getMetadata());
        if (meta != null) {
            meta.sanitize();
        }
        if (meta == null) {
            meta = new FaqChunkMetadata();
            meta.standardQuestion = chunk.getContent();
        }
        String tagId = chunk.getTagId() == null ? "" : chunk.getTagId();
        boolean enabled = chunk.isIsEnabled();
        String standardQuestion = meta.standardQuestion == null ? "" : meta.standardQuestion;
        List<String> answers = meta.answers == null ? List.of() : meta.answers;

        if ("combined".equals(questionIndexMode)) {
            return List.of(new VectorStoreService.IndexRow(chunk.getId(), chunk.getId(),
                    chunk.getKnowledgeId(), chunk.getKnowledgeBaseId(),
                    buildFAQIndexContent(meta, indexMode), enabled, tagId));
        }

        // 分别索引模式：标准问 + 每个相似问各一行
        List<VectorStoreService.IndexRow> rows = new ArrayList<>();
        String standardContent = standardQuestion;
        if ("question_answer".equals(indexMode) && !answers.isEmpty()) {
            StringBuilder sb = new StringBuilder(standardQuestion);
            for (String ans : answers) {
                sb.append("\n").append(ans);
            }
            standardContent = sb.toString();
        }
        rows.add(new VectorStoreService.IndexRow(chunk.getId(), chunk.getId(),
                chunk.getKnowledgeId(), chunk.getKnowledgeBaseId(),
                standardContent, enabled, tagId));

        List<String> similarQuestions = meta.similarQuestions == null ? List.of() : meta.similarQuestions;
        for (int i = 0; i < similarQuestions.size(); i++) {
            String similarQ = similarQuestions.get(i);
            String similarContent = similarQ;
            if ("question_answer".equals(indexMode) && !answers.isEmpty()) {
                StringBuilder sb = new StringBuilder(similarQ);
                for (String ans : answers) {
                    sb.append("\n").append(ans);
                }
                similarContent = sb.toString();
            }
            // sourceID = chunkID-{i}
            rows.add(new VectorStoreService.IndexRow(chunk.getId() + "-" + i, chunk.getId(),
                    chunk.getKnowledgeId(), chunk.getKnowledgeBaseId(),
                    similarContent, enabled, tagId));
        }
        return rows;
    }

    /**
     * 标准问 + 相似问（换行相连）+
     * question_answer 模式时追加答案。
     */
    static String buildFAQIndexContent(FaqChunkMetadata meta, String indexMode) {
        StringBuilder sb = new StringBuilder(meta.standardQuestion == null ? "" : meta.standardQuestion);
        if (meta.similarQuestions != null) {
            for (String q : meta.similarQuestions) {
                sb.append("\n").append(q);
            }
        }
        if ("question_answer".equals(indexMode) && meta.answers != null) {
            for (String ans : meta.answers) {
                sb.append("\n").append(ans);
            }
        }
        return sb.toString();
    }

    static String faqIndexMode(KnowledgeBase kb) {
        com.fasterxml.jackson.databind.JsonNode cfg = kb.getFaqConfig();
        String mode = cfg == null ? "" : cfg.path("index_mode").asText("");
        return mode.isEmpty() ? "question_answer" : mode;
    }

    /** combined。 */
    static String faqQuestionIndexMode(KnowledgeBase kb) {
        com.fasterxml.jackson.databind.JsonNode cfg = kb.getFaqConfig();
        String mode = cfg == null ? "" : cfg.path("question_index_mode").asText("");
        return mode.isEmpty() ? "combined" : mode;
    }
}
