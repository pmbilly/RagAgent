package com.ragagent.datasource;

import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * 流式抓取过程中接收条目与进度检查点的回调。
 *
 * <p>service 层实现它，从而「每到一个条目就立刻灌入」（内存被限制在单个条目而不是
 * 整个 wiki），并在<b>页边界</b>持久化连接器 cursor——这样一次超时中断的同步
 * 能从最后一个检查点续跑，而不是从头再来。</p>
 */
public interface StreamHandler {

    /**
     * 灌入单个已抓取的条目。返回 / 抛异常即中止整条流：连接器会停止抓取并向上传播，
     * 因为"灌入失败"意味着这次同步正在失败，继续调用外部 API 只是浪费配额。
     *
     * <p>失败用<b>抛异常</b>表达。连接器侧一律捕获实现抛出的任何
     * {@code RuntimeException} 并作为终止信号向外传。</p>
     */
    void emit(FetchedItem item);

    /**
     * 持久化"到目前为止"的 cursor。
     *
     * <p>cursor 只在本次调用期间有效（连接器随后可能继续改它背后的 map），
     * 所以实现必须<b>同步</b>序列化——不能只存引用。</p>
     *
     * <p>cursor 必须是<b>完整的可续跑快照</b>，而不是增量：从它续跑必须能重现
     * 到目前为止的全部进度。这正是 service 层敢把一个检查点当成安全重启点、
     * 并在全量同步时丢掉旧基线而不丢失已同步状态的原因。</p>
     */
    void checkpoint(SyncCursor cursor);
}
