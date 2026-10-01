package com.ragagent.session.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 注入到某条回答里的长期记忆（对照 Go {@code types.UsedMemory}，
 * internal/types/memory.go L1087-1091）。
 *
 * <p>三个键都恒输出——未用到的也要输出空串。持久化（而不是只走流式）
 * 是为了让重新打开会话时仍能解释"这个答案当时看到了什么"，并允许用户就地删除某一条。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UsedMemory {

    private String id = "";

    private String kind = "";

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
