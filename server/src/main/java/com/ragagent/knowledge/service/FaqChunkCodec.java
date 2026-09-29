package com.ragagent.knowledge.service;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.dto.FaqEntryDtos.FaqEntry;
import org.springframework.stereotype.Component;

/**
 * FAQ 条目与 chunk 行之间的双向编解码：metadata JSON 的解析与写回（含 content_hash
 * 重算）、条目视图转换、索引内容拼装、KB 索引模式的缺省值兜底。全部为纯转换，
 * 不触碰数据库。
 */
@Component
public class FaqChunkCodec {

    /**
     * 解析 chunk metadata 并做 sanitize；解析失败返回 null（调用方按"无 metadata"
     * 语义处理，不抛错）。
     */
    public FaqChunkMetadata sanitizedFaqMetadata(Chunk chunk) {
        FaqChunkMetadata meta = FaqChunkMetadata.fromJson(chunk.getMetadata());
        if (meta != null) {
            meta.sanitize();
        }
        return meta;
    }

    /**
     * 更新路径的当前 metadata 读取：与 {@link #sanitizedFaqMetadata} 同语义
     * （解析失败视为无既有 metadata，不影响更新）。
     */
    public FaqChunkMetadata currentFaqMetadata(Chunk chunk) {
        return sanitizedFaqMetadata(chunk);
    }

    /**
     * 将 metadata 写回 chunk 行：sanitize 后序列化，并同步重算 content_hash
     * （hash 覆盖 normalize 后的全部语义字段——不变量：hash 与内容必须一致）。
     */
    public void setFaqMetadata(Chunk chunk, FaqChunkMetadata meta) {
        meta.sanitize();
        chunk.setMetadata(meta.toJsonNode());
        chunk.setContentHash(FaqChunkMetadata.calculateContentHash(meta.normalize()));
    }

    /**
     * chunk 行 → 条目视图。metadata 缺失时以 chunk 内容为标准问兜底。
     *
     * <p>不变量：相似问/反例问/答案的空列表必须保持 null 而非归一成 []——
     * 条目契约里两者序列化形态不同。</p>
     */
    public FaqEntry chunkToFAQEntry(Chunk chunk, KnowledgeBase kb, Map<String, Long> tagSeqIdMap) {
        FaqChunkMetadata meta = sanitizedFaqMetadata(chunk);
        if (meta == null) {
            meta = new FaqChunkMetadata();
            meta.standardQuestion = chunk.getContent();
        }
        String answerStrategy = meta.answerStrategy == null || meta.answerStrategy.isEmpty()
                ? "all" : meta.answerStrategy;
        long tagSeqId = 0;
        if (!chunk.getTagId().isEmpty() && tagSeqIdMap != null) {
            tagSeqId = tagSeqIdMap.getOrDefault(chunk.getTagId(), 0L);
        }
        return new FaqEntry(
                chunk.getSeqId() == null ? 0 : chunk.getSeqId(),
                chunk.getId(),
                chunk.getKnowledgeId(),
                chunk.getKnowledgeBaseId(),
                tagSeqId,
                "",
                chunk.isIsEnabled(),
                (chunk.getFlags() & 1) != 0,
                meta.standardQuestion,
                meta.similarQuestions,
                meta.negativeQuestions,
                meta.answers,
                answerStrategy,
                faqIndexMode(kb),
                chunk.getUpdatedAt(),
                chunk.getCreatedAt(),
                0,
                0,
                chunk.getChunkType(),
                "");
    }

    /**
     * 拼装条目的索引内容："Q: 标准问" 起头，相似问与（question_answer 模式下的）
     * 答案逐行列出；纯索引文本，不参与展示。
     */
    public String buildFAQChunkContent(FaqChunkMetadata meta, String mode) {
        StringBuilder builder = new StringBuilder();
        builder.append("Q: ").append(meta.standardQuestion).append('\n');
        if (meta.similarQuestions != null && !meta.similarQuestions.isEmpty()) {
            builder.append("Similar Questions:\n");
            for (String q : meta.similarQuestions) {
                builder.append("- ").append(q).append('\n');
            }
        }
        if ("question_answer".equals(mode) && meta.answers != null && !meta.answers.isEmpty()) {
            builder.append("Answers:\n");
            for (String ans : meta.answers) {
                builder.append("- ").append(ans).append('\n');
            }
        }
        return builder.toString();
    }

    /**
     * KB 的 FAQ 索引模式，缺省 question_answer（决定索引内容是否包含答案）。
     */
    public String faqIndexMode(KnowledgeBase kb) {
        JsonNode cfg = kb.getFaqConfig();
        String mode = cfg == null ? "" : cfg.path("index_mode").asText("");
        return mode.isEmpty() ? "question_answer" : mode;
    }

    /**
     * KB 的生成问题索引模式，缺省 combined。
     */
    public String faqQuestionIndexMode(KnowledgeBase kb) {
        JsonNode cfg = kb.getFaqConfig();
        String mode = cfg == null ? "" : cfg.path("question_index_mode").asText("");
        return mode.isEmpty() ? "combined" : mode;
    }
}
