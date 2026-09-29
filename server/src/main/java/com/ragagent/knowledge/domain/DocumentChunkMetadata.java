package com.ragagent.knowledge.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 文档 Chunk 的 metadata 形状。
 * <p>存于 {@code chunks.metadata}（jsonb），由 {@link PgJsonTypeHandler} 以
 * JsonNode 透传落库；本类型用于 service 层读写（Upsert/Delete/Regenerate 生成问题）。
 * 响应侧生成问题只以 {@link GeneratedQuestion} 元素出现，本类型本身不出 HTTP 响应。</p>
 * <ul>
 *   <li>{@code generated_questions}：列表的空值省略语义 —— null <b>或空列表</b>都省略
 *   <li>{@code generated_questions_revision}：整型的空值省略语义 —— 0 省略
 *       （{@code NON_DEFAULT}，原始 int 的 0 即默认值），非 0 恒输出。</li>
 * </ul>
 * 不出响应）；Java 侧按需在 service 内联实现，本类刻意不提供同名访问器，
 * 避免触发「isXxx 派生方法必须 @JsonIgnore」的复发坑（本仓约定）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentChunkMetadata {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("generated_questions")
    private List<GeneratedQuestion> generatedQuestions;

    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("generated_questions_revision")
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
