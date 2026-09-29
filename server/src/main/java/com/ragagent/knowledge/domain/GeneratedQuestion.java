package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 生成问题。
 * <p>chunk.metadata（jsonb）里 {@code generated_questions} 数组的元素，
 * 同时是 {@code PUT /chunks/by-id/:id/questions} 与
 * {@code POST .../questions/regenerate} 响应体 {@code data} 的元素。</p>
 * 零值 {@code ""} 也输出）；{@code content_revision} 是 {@code *int} ——nil 省略、
 * <b>非 nil 的 0 也要输出</b>（指针的空值省略只判 null），Java 用
 * {@code Integer + NON_NULL} 表达，service 侧允许放 0（chunk.ContentRevision
 * 从未被编辑过时就是 0）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeneratedQuestion {

    @JsonProperty("id")
    private String id;

    @JsonProperty("question")
    private String question;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("content_revision")
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
