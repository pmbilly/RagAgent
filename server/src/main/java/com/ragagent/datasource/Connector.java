package com.ragagent.datasource;

import java.util.List;

import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * 所有外部数据源连接器必须实现的接口（对照 Go {@code datasource.Connector}，
 * internal/datasource/connector.go L11-52）。
 *
 * <h2>签名映射：{@code ctx} 去哪了</h2>
 * <p>Go 的每个方法第一个参数都是 {@code context.Context}——它承载
 * 取消信号（{@code sleepCtx} / {@code http.NewRequestWithContext}）、
 * asynq 的 2h 任务超时，以及 langfuse 的追踪上下文。按约定 §5
 * （"不要把 Context 当参数层层传"）Java 侧<b>去掉该参数</b>，
 * 取消改为依赖<b>线程中断</b>：连接器内部的重试退避一律走
 * {@link #sleep(long)}，被中断时抛 {@link ConnectorException}。
 * 调用方（下一步的 service 层）用虚拟线程跑同步、用
 * {@code Future.cancel(true)} / {@code ExecutorService.shutdownNow()} 施加取消。</p>
 *
 * <p><b>已知差异</b>：Go 的 {@code ctx} 还兼顾"单次请求超时"（{@code context.WithTimeout}），
 * Java 侧改为落在每个 HTTP 客户端自己的 {@code HttpRequest.timeout} 上
 * （各连接器构造 {@link ConnectorHttp.Client} 时传入，与 Go 的
 * {@code requestTimeout} 常量一一对应）。两级超时（请求级 vs 任务级）在 Java 侧
 * 只剩请求级，任务级由调用方的线程池兜底。</p>
 *
 * <h2>nil slice 语义（必须照抄）</h2>
 * <p>Go 里 {@code resourceIDs []string} 的 <b>nil 与空切片在多数连接器里等价</b>，
 * 但 RSS 的 {@code walk} 有"len==0 就回落到全部已配置 feed"的分支，
 * 而 wiki 的 {@code FetchAll} 会把调用方传进来的值原样透传（不做非空检查）。
 * 所以 Java 侧 {@code resourceIds} 允许为 {@code null}，各连接器按 Go 的分支逐条对照。</p>
 */
public interface Connector {

    /**
     * 连接器类型标识（例如 {@code "feishu"} / {@code "notion"}）。
     *
     * <p>Java 侧方法名不带 {@code get} 前缀——对照 Go 的 {@code Type()}，
     * 避免它被 Jackson 当成属性（约定 §7.5 第 2 条那类坑）。实现返回的字符串
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
     * @param resourceIds 允许 {@code null}（对照 Go 的 nil slice）
     */
    List<FetchedItem> fetchAll(DataSourceConfig config, List<String> resourceIds);

    /**
     * 基于给定 cursor 做增量同步：返回自上次同步以来变化了的条目、
     * 供下次同步用的新 cursor。
     *
     * <p>Go 的三返回值 {@code (items, cursor, error)} 用一个 record 表达。
     * <b>异常与结果可以同时有效</b>——{@link ConnectorException.PartialFetch}
     * 抛出时 {@link FetchIncrementalResult#items()} 与
     * {@link FetchIncrementalResult#cursor()} 已经填好了（照抄 Go 的
     * {@code return items, syncCursor, &PartialFetchError{...}}）。
     * 调用方必须先取结果、再按异常类型决定"部分成功"还是"整体失败"。</p>
     */
    FetchIncrementalResult fetchIncremental(DataSourceConfig config, SyncCursor cursor);

    /**
     * 对照 Go {@code ([]types.FetchedItem, *types.SyncCursor, error)} 的三返回值。
     *
     * <p>两个字段都允许为 {@code null}：Go 的 {@code FetchIncremental} 在
     * "所有 feed 都失败"时返回 {@code nil, newCursor, err}（items 为 nil 但
     * cursor 有值），在致命失败时返回 {@code nil, nil, err}。</p>
     */
    record FetchIncrementalResult(List<FetchedItem> items, SyncCursor cursor) {
    }

    // ── 共享工具：带中断语义的退避休眠 ────────────────────────────────────

    /**
     * 对照 Go 各连接器里那个 {@code sleepCtx(ctx, d)}：睡 {@code d}，被中断就提前结束。
     *
     * <p>Java 侧的中断对应 Go 的 ctx 取消，所以被中断时抛
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
