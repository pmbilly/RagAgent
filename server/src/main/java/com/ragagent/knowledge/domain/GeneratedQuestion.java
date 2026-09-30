package com.ragagent.knowledge.domain;


/**
 * 生成问题。
 *
 * <p>{@code chunk.metadata}（jsonb）里 {@code generatedQuestions} 数组的元素，同时是
 * {@code PUT} / {@code POST .../questions/regenerate} 类响应体的元素。
 *
 * <p>所有字段一律输出；{@code contentRevision} 的 0 是有意义的值（文档从未被编辑过时即 0）。
 */
public class GeneratedQuestion {

    private String id;

    private String question;

    private Integer contentRevision;

    public GeneratedQuestion() { }

    public GeneratedQuestion(String id, String question, Integer contentRevision) {
        this.id = id;
        this.question = question;
        this.contentRevision = contentRevision;
    }

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public String getQuestion() { return question; }
    public void setQuestion(String v) { question = v; }
    public Integer getContentRevision() { return contentRevision; }
    public void setContentRevision(Integer v) { contentRevision = v; }
}
