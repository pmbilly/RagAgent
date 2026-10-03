package com.ragagent.event.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 上下文压缩报告。
 * emit 点：ObservePhase（{@code generateEventID("compaction")}），见包注释 emit 表 #6。
 *
 * <p>压缩改变了 agent 记住的东西，所以展示而非隐藏：答案忘了早先的指令，
 * 否则与模型无视指令无法区分。{@code degraded}/{@code split_turn}
 * 为 false 省略，其余七个字段恒输出。</p>
 */
@JsonPropertyOrder({"reason", "round", "tokens_before", "tokens_after", "messages_before",
        "messages_after", "summary", "degraded", "split_turn"})
public class ContextCompactedData {

    /** threshold | overflow */
    @JsonProperty("reason")
    private String reason = "";

    @JsonProperty("round")
    private int round;

    @JsonProperty("tokens_before")
    private int tokensBefore;

    @JsonProperty("tokens_after")
    private int tokensAfter;

    @JsonProperty("messages_before")
    private int messagesBefore;

    @JsonProperty("messages_after")
    private int messagesAfter;

    @JsonProperty("summary")
    private String summary = "";

    /** 摘要来自机械归档（summarizer 失败）的降级标记；false 省略 */
    @JsonProperty("degraded")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean degraded;

    /** 切口落在单个 turn 内的标记；false 省略 */
    @JsonProperty("split_turn")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean splitTurn;

    public ContextCompactedData() {
    }

    public ContextCompactedData(String reason, int round, int tokensBefore, int tokensAfter,
                                int messagesBefore, int messagesAfter, String summary,
                                boolean degraded, boolean splitTurn) {
        this.reason = QueryData.orEmpty(reason);
        this.round = round;
        this.tokensBefore = tokensBefore;
        this.tokensAfter = tokensAfter;
        this.messagesBefore = messagesBefore;
        this.messagesAfter = messagesAfter;
        this.summary = QueryData.orEmpty(summary);
        this.degraded = degraded;
        this.splitTurn = splitTurn;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String v) {
        this.reason = QueryData.orEmpty(v);
    }

    public int getRound() {
        return round;
    }

    public void setRound(int v) {
        this.round = v;
    }

    public int getTokensBefore() {
        return tokensBefore;
    }

    public void setTokensBefore(int v) {
        this.tokensBefore = v;
    }

    public int getTokensAfter() {
        return tokensAfter;
    }

    public void setTokensAfter(int v) {
        this.tokensAfter = v;
    }

    public int getMessagesBefore() {
        return messagesBefore;
    }

    public void setMessagesBefore(int v) {
        this.messagesBefore = v;
    }

    public int getMessagesAfter() {
        return messagesAfter;
    }

    public void setMessagesAfter(int v) {
        this.messagesAfter = v;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String v) {
        this.summary = QueryData.orEmpty(v);
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void setDegraded(boolean v) {
        this.degraded = v;
    }

    public boolean isSplitTurn() {
        return splitTurn;
    }

    public void setSplitTurn(boolean v) {
        this.splitTurn = v;
    }
}
