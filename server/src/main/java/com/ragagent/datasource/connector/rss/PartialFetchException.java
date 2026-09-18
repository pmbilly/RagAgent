package com.ragagent.datasource.connector.rss;

import java.util.List;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * 部分 feed 成功、部分失败（对照 Go 的
 * {@code return out, newCursor, &datasource.PartialFetchError{Details: feedErrors}}）。
 *
 * <p>继承 {@link ConnectorException.PartialFetch}，这样 service 层可以用
 * {@code catch (ConnectorException.PartialFetch e)} 一次覆盖所有连接器的"部分成功"
 * ——这是模块间共享的判定方式。同时实现 {@link RssFetchState} 把
 * <b>本次仍然有效的 items 与 cursor</b> 随异常带出来，详见该接口的类注释。</p>
 *
 * <p>{@code getMessage()} 继承自 {@link ConnectorException.PartialFetch}：
 * {@code "partial fetch: " + String.join("; ", details)}，每条 detail 是
 * {@code "<feedURL>: <err>"}（由 {@code walk} 拼好）。</p>
 */
public class PartialFetchException extends ConnectorException.PartialFetch
        implements RssFetchState {

    private static final long serialVersionUID = 1L;

    private final transient List<FetchedItem> items;
    private final transient SyncCursor cursor;

    /**
     * @param details 每个失败 feed 的 {@code "<url>: <err>"}
     * @param items   已抓到的条目；允许 {@code null}（对照 Go 的 nil slice）
     * @param cursor  新游标；{@code FetchAll} 路径恒为 {@code null}（Go 用 {@code _} 丢弃）
     */
    public PartialFetchException(List<String> details, List<FetchedItem> items, SyncCursor cursor) {
        super(details);
        this.items = items;
        this.cursor = cursor;
    }

    @Override
    public List<FetchedItem> items() {
        return items;
    }

    @Override
    public SyncCursor cursor() {
        return cursor;
    }
}
