package com.ragagent.knowledge.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 文档 Chunk 的 metadata 形状。
 *
 * <p>存于 {@code chunks.metadata}（jsonb），由 {@link PgJsonTypeHandler} 以 JsonNode 透传落库；
 * 本类型用于 service 层读写（Upsert/Delete/Regenerate 生成问题）。生成问题在响应侧只以
 * {@link GeneratedQuestion} 元素出现，本类型本身不出 HTTP 响应。
 *
 * <p>两个键恒输出：{@code generatedQuestions}（可为 null 或空列表）、
 * {@code generatedQuestionsRevision}（0 有意义——表示从未重新生成过）。
 *
 * <p>派生状态（如处理中标记）按需在 service 内联判断：本类刻意不提供同名访问器，
 * 避免触发「isXxx 派生方法必须 {@code @JsonIgnore}」的复发坑（本仓约定）。
 */
public class DocumentChunkMetadata {

    private List<GeneratedQuestion> generatedQuestions;

    private int generatedQuestionsRevision;

    public DocumentChunkMetadata() { }

    public List<GeneratedQuestion> getGeneratedQuestions() { return generatedQuestions; }
    public void setGeneratedQuestions(List<GeneratedQuestion> v) { generatedQuestions = v; }
    public int getGeneratedQuestionsRevision() { return generatedQuestionsRevision; }
    public void setGeneratedQuestionsRevision(int v) { generatedQuestionsRevision = v; }

    @JsonIgnore
    public boolean questionCurrent(GeneratedQuestion q, int chunkRevision) {
        if (q.getContentRevision() != null) {
            return q.getContentRevision() == chunkRevision;
        }
        return generatedQuestionsRevision == chunkRevision;
    }
}
