package com.ragagent.session.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 注入到某条回答里的长期记忆（对照 Go {@code types.UsedMemory}，
 * internal/types/memory.go L1087-1091）。
 *
 * <p>三个键**都无 omitempty**——未用到的也要输出空串。持久化（而不是只走流式）
 * 是为了让重新打开会话时仍能解释"这个答案当时看到了什么"，并允许用户就地删除某一条。</p>
 */
@JsonPropertyOrder({"id", "kind", "content"})
@JsonIgnoreProperties(ignoreUnknown = true)
public class UsedMemory {

    @JsonProperty("id")
    private String id = "";

    @JsonProperty("kind")
    private String kind = "";

    @JsonProperty("content")
    private String content = "";

    public UsedMemory() {
    }

    public String getId() {
        return id;
    }

    public void setId(String v) {
        this.id = v == null ? "" : v;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String v) {
        this.kind = v == null ? "" : v;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String v) {
        this.content = v == null ? "" : v;
    }
}
