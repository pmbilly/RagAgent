package com.ragagent.wiki.service;
import com.ragagent.wiki.service.ingest.WikiIngestService;

/**
 * 知识文档的「wiki 子任务已终结」记账端口。
 *
 * <p><b>为什么是端口</b>：finalizing 计数归 knowledge 模块所有；把它抽成这个窄端口，
 * 让 {@link WikiIngestService#requeueFailedOps} 能在 op 到达终态（映射成功或进了死信）
 * 时释放该文档在 finalizing 计数里的槽位，而不必依赖 knowledge 模块的内部实现。</p>
 *
 * <h2>业务语义</h2>
 * <p>对应的 +1 是由知识处理链路在「即将生成 wiki」时播种的。调用方<b>只能对 ingest op</b> 调用它——
 * retract op 针对的是已删除的文档，没有计数器需要排空。</p>
 *
 * <p>对一个已经完成、或计数已为 0 的行调用是<b>安全的</b>：
 * 递减（{@code count > 0}）与晋升
 * （{@code parse_status = finalizing AND count = 0}）两个条件同时守住，
 * 因此在计数器机制上线之前入队的 op 调用它是无害的 no-op。</p>
 *
 * <p>实现必须使用<b>脱钩的执行路径</b>：
 * wiki 批次 worker 可能在应用关闭中、或调用上下文已被取消；此时吞掉失败会把父文档永久
 * 留在 "finalizing" 状态。</p>
 */
public interface WikiKnowledgeFinalizer {

    /**
     * 把该知识文档的 wiki 子任务标记为终结。
     *
     * @param knowledgeId 知识文档 id
     */
    void finalizeWikiSubtask(String knowledgeId);
}
