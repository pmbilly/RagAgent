package com.ragagent.datasource.connector.rss;

import java.util.List;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * 所有 feed 都失败了（对照 Go 的
 * {@code return nil, newCursor, fmt.Errorf("all feeds failed: %s", strings.Join(feedErrors, "; "))}）。
 *
 * <p>判定条件与 Go 逐字一致：{@code len(out) == 0 && len(feedErrors) == len(feedURLs)}
 * ——<b>"一条都没抓到"且"每个 feed 都报了错"</b>。只有成功的 feed 恰好 0 条时不算
 * （那走 {@link PartialFetchException}）。</p>
 *
 * <h2>⚠️ 它不是 {@link ConnectorException.PartialFetch} 的子类</h2>
 * <p>这是刻意的：Go 侧这条返回的是<b>普通 error</b>，service 层用
 * {@code errors.As(err, &PartialFetchError{})} 认不出它，于是会把 sync log 记成
 * {@code failed} 而不是 {@code partial}。让 Java 的这条也继承 {@code PartialFetch}
 * 会让"全部失败"在日志与状态上退化成"部分成功"——是真实的行为分叉。</p>
 *
 * <h2>为什么它也要带 cursor</h2>
 * <p>因为 Go 的 {@code FetchIncremental} 在这条路径上返回的是
 * {@code (nil, syncCursor, err)} —— <b>cursor 是有值的</b>，里面用
 * {@code copyFeedCursor} 前滚了每个失败 feed 上一轮的指纹。
 * 丢掉它就会让下次同步把整个 feed 当新内容重灌一遍。
 * （{@code FetchAll} 路径上 Go 用 {@code _} 丢弃了它，所以 Java 侧那里也是 {@code null}。）</p>
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
     * 恒为<b>空列表</b>。
     *
     * <p>⚠️ 与 Go 的细微差别：Go 在这条分支上返回的是 <b>nil slice</b>，
     * Java 侧归一成空列表——两者对迭代/长度判断等价，只有显式的 {@code == nil} 判据会分叉。
     * 本模块选择"永不为 null"，因为调用方（service 层）在异常路径上多一个 null 判据
     * 就多一个 NPE 的机会，而这里没有任何观察点需要区分 nil 与空。</p>
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
