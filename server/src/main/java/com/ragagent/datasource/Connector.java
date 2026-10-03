package com.ragagent.datasource;

import java.util.List;

import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * 所有外部数据源连接器必须实现的接口。
 *
 * <h2>取消与超时</h2>
 * <p>方法不带 context 参数，取消依赖<b>线程中断</b>：
 * 连接器内部的重试退避一律走
 * {@link #sleep(long)}，被中断时抛 {@link ConnectorException}。
 * 调用方（service 层）用虚拟线程跑同步、用
 * {@code Future.cancel(true)} / {@code ExecutorService.shutdownNow()} 施加取消。</p>
 *
 * <p>请求级超时落在每个 HTTP 客户端自己的 {@code HttpRequest.timeout} 上
 * （各连接器构造 {@link ConnectorHttp.Client} 时传入）；
 * 任务级超时由调用方的线程池兜底。</p>
 *
 * <h2>null 列表语义</h2>
 * <p>{@code resourceIds} 的 <b>{@code null} 与空列表在多数连接器里等价</b>，
 * 但 RSS 有"len==0 就回落到全部已配置 feed"的分支，
 * 而 wiki 会把调用方传进来的值原样透传（不做非空检查）。
 * 所以 {@code resourceIds} 允许为 {@code null}，各连接器按此分支处理。</p>
 */
public interface Connector {

    /**
     * 连接器类型标识（例如 {@code "feishu"} / {@code "notion"}）。
     *
     * <p>方法名不带 {@code get} 前缀，
     * 避免它被 Jackson 当成属性。实现返回的字符串
     * 一律取自 {@link com.ragagent.datasource.domain.DataSourceConstants}。</p>
     */
    String type();

    /**
     * 校验给定配置是否可用：真实连一次外部 API、检查凭据。
     * 失败时抛 {@link ConnectorException}（典型是
     * {@link ConnectorException.InvalidCredentials}）。
     */
    void validate(DataSourceConfig config);

    /**
     * 列出可同步的资源（文档 / 空间 / 文件夹 …）。
     *
     * <p>{@code parentId} 控制层级资源的<b>惰性加载</b>：</p>
     * <ul>
     *   <li>{@code parentId == null} 或空串 → 返回顶层资源（例如飞书 wiki 空间）；</li>
     *   <li>非空 → 只返回该资源的<b>直接子项</b>。</li>
     * </ul>
     * <p>本身已经扁平、或一次调用就返回整棵树的连接器，可以对根调用忽略
     * {@code parentId}、并对任何非空 {@code parentId} 回一个空列表。</p>
     */
    List<Resource> listResources(DataSourceConfig config, String parentId);

    /**
     * 对每个给定资源 ID，解析出"为了让惰性加载的选择器能展开到一个已存在的
     * （可能很深的）选择，必须去加载其直接子项的所有祖先"的 ExternalID 集合。
     * 返回的集合<b>去重且无序</b>。
     *
     * <p>它存在的理由是：一级一级加载树的连接器（如飞书 wiki）要能在
     * O(depth)/选择 的代价下给出回到根的路径，而不是重新遍历整棵树。
     * 已经返回整棵树（Notion）或扁平列表（语雀）的连接器无需揭示任何东西，
     * 回空列表。</p>
     */
    List<String> resolveResourceAncestors(DataSourceConfig config, List<String> resourceIds);

    /**
     * 全量同步指定资源，返回这些资源下的全部条目。
     *
     * @param resourceIds 允许 {@code null}（语义见类注释）
     */
    List<FetchedItem> fetchAll(DataSourceConfig config, List<String> resourceIds);

    /**
     * 基于给定 cursor 做增量同步：返回自上次同步以来变化了的条目、
     * 供下次同步用的新 cursor。
     *
     * <p>三返回值 {@code (items, cursor, error)} 的形态：前两者进 record，错误走异常。
     * <b>异常与结果可以同时有效</b>——{@link ConnectorException.PartialFetch}
     * 抛出时 {@link FetchIncrementalResult#items()} 与
     * {@link FetchIncrementalResult#cursor()} 已经填好了。
     * 调用方必须先取结果、再按异常类型决定"部分成功"还是"整体失败"。</p>
     */
    FetchIncrementalResult fetchIncremental(DataSourceConfig config, SyncCursor cursor);

    /**
     * 增量同步的条目与续读游标。
     *
     * <p>两个字段都允许为 {@code null}："所有 feed 都失败"时
     * items 为 {@code null} 但 cursor 有值，致命失败时两者皆为 {@code null}。</p>
     */
    record FetchIncrementalResult(List<FetchedItem> items, SyncCursor cursor) {
    }

    // ── 共享工具：带中断语义的退避休眠 ────────────────────────────────────

    /**
     * 睡满 {@code millis} 毫秒，被中断就提前结束。
     *
     * <p>被中断时抛
     * {@link ConnectorException}（而<b>不是</b>把 {@code InterruptedException}
     * 直接漏出去）——调用方的重试循环把它当成"任务被取消，立刻返回"。</p>
     */
    static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("interrupted while waiting " + millis + "ms", e);
        }
    }
}
