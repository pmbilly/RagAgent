package com.ragagent.datasource;

import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * 可选的连接器能力接口。
 *
 * <p>实现它的连接器让 service 层把「抓取→灌入→检查点」交错起来：大型同步
 * 因此是增量落库的、超时后能续跑，而不是把所有条目攒在内存里、重试时
 * 全部进度归零。不实现它的连接器<b>原样</b>回落到
 * {@link Connector#fetchAll} / {@link Connector#fetchIncremental}。</p>
 *
 * <p>service 层判定走 {@code connector instanceof StreamingConnector}。</p>
 */
public interface StreamingConnector extends Connector {

    /**
     * 从 {@code cursor} 开始遍历已配置资源（{@code null} = 从头 / 全量同步），
     * 对每个变化的条目调用 {@link StreamHandler#emit}，在每个页边界调用
     * {@link StreamHandler#checkpoint}。返回供下次同步用的最终 cursor。
     *
     * <p>已经按当前编辑时间记录在 cursor 里的节点会被跳过——这正是"续跑的同步会收敛"
     * 的机制。</p>
     */
    SyncCursor fetchStream(DataSourceConfig config, SyncCursor cursor, StreamHandler handler);
}
