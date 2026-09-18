package com.ragagent.session.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 消息搜索的响应体（对照 Go {@code types.MessageSearchResult}，types/message.go L552-558）。
 *
 * <p>{@code items} 恒输出数组（Go 的 {@code groupByRequestID} 用 {@code make(..., 0, …)}
 * 构造，空结果也是 {@code []} 不是 {@code null}）。</p>
 */
@JsonPropertyOrder({"items", "total"})
public class MessageSearchResult {

    @JsonProperty("items")
    private List<MessageSearchGroupItem> items = new java.util.ArrayList<>();

    @JsonProperty("total")
    private int total;

    public List<MessageSearchGroupItem> getItems() {
        return items;
    }

    public void setItems(List<MessageSearchGroupItem> v) {
        this.items = v == null ? new java.util.ArrayList<>() : v;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int v) {
        this.total = v;
    }
}
