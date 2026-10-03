package com.ragagent.datasource.connector.rss;

import java.util.List;

import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * <b>"异常与结果同时有效"</b>的载体——本模块最重要的一条设计决定。
 *
 * <h2>问题</h2>
 * <p>部分失败时，<b>items 与 cursor 仍然有效</b>：调用方（service 层）要先拿它们，
 * 再看错误类型决定这次同步记 {@code partial} 还是 {@code failed}。</p>
 * <p>但异常会<b>中断返回</b>，{@code FetchIncrementalResult} 根本到不了调用方手上。
 * 所以这里把"结果"塞进<b>异常本身</b>：抛出的那一刻，{@link #items()} 与
 * {@link #cursor()} 已经填好。</p>
 *
 * <h2>调用方怎么用（service 层照着写）</h2>
 * <pre>
 *   try {
 *       Connector.FetchIncrementalResult r = connector.fetchIncremental(config, cursor);
 *       // 全部成功
 *   } catch (ConnectorException.PartialFetch e) {   // 部分成功
 *       List&lt;FetchedItem&gt; items  = ((RssFetchState) e).items();
 *       SyncCursor          cursor = ((RssFetchState) e).cursor();
 *       // ……照常灌入 items、持久化 cursor，再把 getDetails() 记成"部分同步"
 *   } catch (RssFetchState e) {                     // 全部 feed 都失败
 *       // items 恒为 null；cursor 仍有值，可持久化
 *   }
 * </pre>
 * <p><b>先 catch 子类</b>（{@link PartialFetchException}）——{@link AllFeedsFailedException}
 * 不是它的子类，两个分支是并列的（部分成功 vs 全部失败是两种不同结局）。</p>
 *
 * <h2>为什么不是"抛一个统一类型 + 一个标志位"</h2>
 * <p>因为 service 层是用 <b>{@code instanceof PartialFetch}</b> 判部分成功的。
 * 把"全部失败"也做成 {@code PartialFetch} 的子类，会让 total failure 被记成
 * {@code partial} 状态——这是真实的行为分叉，不能为了接口好看而牺牲。</p>
 *
 * <h2>两个实现</h2>
 * <ul>
 *   <li>{@link PartialFetchException}：部分 feed 失败（items/cursor 都有效）；</li>
 *   <li>{@link AllFeedsFailedException}：全部 feed 失败（items 为 null，cursor 仍有效）。</li>
 * </ul>
 * <p>另有一条配置解析失败路径：那<b>两个字段都为
 * null</b>，走的是 {@code ConnectorException.InvalidConfig} 的直接抛出，
 * 不经过本接口。</p>
 */
public interface RssFetchState {

    /**
     * 已经抓到的条目，<b>恒非 {@code null}</b>（没有条目时是空列表）。
     */
    List<FetchedItem> items();

    /**
     * 已经建好的新游标。<b>可能为 {@code null}</b>——只有
     * 全量路径会丢（该路径不使用游标），
     * 增量路径恒非 null。
     */
    SyncCursor cursor();
}
