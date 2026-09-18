package com.ragagent.datasource.connector.rss;

import java.util.List;

import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * <b>"异常与结果同时有效"</b>的载体——本模块最重要的一条设计决定。
 *
 * <h2>问题</h2>
 * <p>Go 的 {@code walk} 返回三个值 {@code (items, newCursor, err)}，而且
 * <b>部分失败时三个都非空</b>：
 * {@code return out, newCursor, &datasource.PartialFetchError{Details: feedErrors}}。
 * 调用方（service 层）要先拿 items 与 cursor，再看错误类型决定这次同步记
 * {@code partial} 还是 {@code failed}。</p>
 * <p>Java 的异常会<b>中断返回</b>，{@code FetchIncrementalResult} 根本到不了调用方手上。
 * 所以这里把"结果"塞进<b>异常本身</b>：抛出的那一刻，{@link #items()} 与
 * {@link #cursor()} 已经填好。</p>
 *
 * <h2>调用方怎么用（下一步 service 层照着写）</h2>
 * <pre>
 *   try {
 *       Connector.FetchIncrementalResult r = connector.fetchIncremental(config, cursor);
 *       // 全部成功
 *   } catch (ConnectorException.PartialFetch e) {   // 部分成功
 *       List&lt;FetchedItem&gt; items  = ((RssFetchState) e).items();
 *       SyncCursor          cursor = ((RssFetchState) e).cursor();
 *       // ……照常灌入 items、持久化 cursor，再把 getDetails() 记成"部分同步"
 *   } catch (RssFetchState e) {                     // 全部 feed 都失败
 *       // items 恒为 null（对照 Go 的 nil slice）；cursor 仍有值，可持久化
 *   }
 * </pre>
 * <p><b>先 catch 子类</b>（{@link PartialFetchException}）——{@link AllFeedsFailedException}
 * 不是它的子类，两个分支是并列的，与 Go 里"普通 error vs PartialFetchError"的区分一致。</p>
 *
 * <h2>为什么不是"抛一个统一类型 + 一个标志位"</h2>
 * <p>因为 service 层是用 <b>{@code instanceof PartialFetch}</b> 判部分成功的
 * （对照 Go 的 {@code errors.As(err, &PartialFetchError{})}）。
 * 把"全部失败"也做成 {@code PartialFetch} 的子类，会让 total failure 被记成
 * {@code partial} 状态——这是真实的行为分叉，不能为了接口好看而牺牲。</p>
 *
 * <h2>两个实现分别对应 Go 的哪一条返回</h2>
 * <ul>
 *   <li>{@link PartialFetchException} ← {@code return out, newCursor, &PartialFetchError{...}}</li>
 *   <li>{@link AllFeedsFailedException} ← {@code return nil, newCursor, fmt.Errorf("all feeds failed: %s", ...)}</li>
 * </ul>
 * <p>另有一条 {@code return nil, nil, err}（{@code parseConfig} 失败）：那条<b>两个字段都为
 * null</b>，在 Java 里走的是 {@code ConnectorException.InvalidConfig} 的直接抛出，
 * 不经过本接口。</p>
 */
public interface RssFetchState {

    /**
     * 已经抓到的条目，<b>恒非 {@code null}</b>（没有条目时是空列表）。
     *
     * <p>⚠️ 与 Go 的细微差别：Go 在"一条都没抓到"时返回 nil slice，Java 侧归一成空列表
     * ——对迭代与长度判断等价，只有显式的 {@code == nil} 会分叉（没有调用方需要它）。</p>
     */
    List<FetchedItem> items();

    /**
     * 已经建好的新游标。<b>可能为 {@code null}</b>——只有
     * {@code FetchAll} 那条路径会丢（Go 用 {@code _} 显式丢弃），
     * {@code FetchIncremental} 恒非 null。
     */
    SyncCursor cursor();
}
