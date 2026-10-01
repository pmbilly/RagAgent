package com.ragagent.session.domain;

import java.util.List;


/**
 * 消息搜索的响应体（对照 Go {@code types.MessageSearchResult}，types/message.go L552-558）。
 *
 * <p>{@code items} 恒输出数组（Go 的 {@code groupByRequestID} 用 {@code make(..., 0, …)}
 * 构造，空结果也是 {@code []} 不是 {@code null}）。</p>
 */
public class MessageSearchResult {

    private List<MessageSearchGroupItem> items = new java.util.ArrayList<>();

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
