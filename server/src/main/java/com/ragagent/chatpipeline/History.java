package com.ragagent.chatpipeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.ragagent.common.retrieval.SearchResult;

/**
 * 一轮问答历史。
 *
 * <p>本类型不直接对外序列化。</p>
 */
public final class History {

    /** 用户问题文本。 */
    private String query = "";
    /** 助手回答（load_history 阶段已剥离 &lt;think&gt; 标签）。 */
    private String answer = "";
    /** 该轮创建时间。 */
    private Instant createAt;
    /** 该轮回答引用的知识块。 */
    private List<SearchResult> knowledgeReferences;

    public String getQuery() { return query; }
    public void setQuery(String v) { query = v == null ? "" : v; }

    public String getAnswer() { return answer; }
    public void setAnswer(String v) { answer = v == null ? "" : v; }

    public Instant getCreateAt() { return createAt; }
    public void setCreateAt(Instant v) { createAt = v; }

    public List<SearchResult> getKnowledgeReferences() { return knowledgeReferences; }
    public void setKnowledgeReferences(List<SearchResult> v) { knowledgeReferences = v; }

    /** 浅拷贝（新建列表，元素引用共享）。 */
    public History copy() {
        History h = new History();
        h.query = query;
        h.answer = answer;
        h.createAt = createAt;
        h.knowledgeReferences = knowledgeReferences == null ? null : new ArrayList<>(knowledgeReferences);
        return h;
    }
}
