package com.ragagent.datasource.connector.rss;

import java.util.List;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * 所有 feed 都失败了。
 *
 * <p>判定条件：{@code items 为空 && 失败数 == feed 总数}
 * ——<b>"一条都没抓到"且"每个 feed 都报了错"</b>。只有成功的 feed 恰好 0 条时不算
 * （那走 {@link PartialFetchException}）。</p>
 *
 * <h2>⚠️ 它不是 {@link ConnectorException.PartialFetch} 的子类</h2>
 * <p>这是刻意的：service 层用 {@code instanceof PartialFetch} 认"部分成功"，
 * 让"全部失败"也继承 {@code PartialFetch}
 * 会在日志与状态上退化成"部分成功"——是真实的行为分叉。</p>
 *
 * <h2>为什么它也要带 cursor</h2>
 * <p>增量路径上 <b>cursor 是有值的</b>，里面用
 * {@code copyFeedCursor} 前滚了每个失败 feed 上一轮的指纹。
 * 丢掉它就会让下次同步把整个 feed 当新内容重灌一遍。
 * （全量路径不使用游标，所以那里恒为 {@code null}。）</p>
 */
public class AllFeedsFailedException extends ConnectorException implements RssFetchState {

    private static final long serialVersionUID = 1L;

    private final transient SyncCursor cursor;

    /**
     * @param detail 形如 {@code "all feeds failed: <url>: <err>; <url>: <err>"}
     * @param cursor 新游标；{@code FetchAll} 路径恒为 {@code null}
     */
    public AllFeedsFailedException(String detail, SyncCursor cursor) {
        super(detail);
        this.cursor = cursor;
    }

    /**
     * 恒为<b>空列表</b>（本模块的约定："永不为 null"——调用方在异常路径上
     * 少一个 null 判据，而没有任何观察点需要区分"没有条目"与空列表）。
     */
    @Override
    public List<FetchedItem> items() {
        return List.of();
    }

    @Override
    public SyncCursor cursor() {
        return cursor;
    }
}
