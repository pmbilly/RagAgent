package com.ragagent.common.wiki;

/**
 * wiki 收尾端口：知识处理各阶段结束时递减 wiki 侧计数、并在归零时晋升 completed。
 *
 * <p>默认实现 {@code DefaultWikiKnowledgeFinalizer} 依赖知识域实体与 Mapper（wiki → knowledge 的另一半环），
 * 不能进 common；调用方不消费返回值，故端口只暴露 void 形态（实现内部的 {@code finalizeSubtask} 返回 Outcome）。</p>
 */
public interface WikiFinalizePort {

    /** 收尾单个子任务（递减 + 可能晋升）。 */
    void finalizeWikiSubtask(String knowledgeId);
}
