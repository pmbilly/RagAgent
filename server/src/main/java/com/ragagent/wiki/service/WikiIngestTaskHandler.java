package com.ragagent.wiki.service;

/**
 * wiki 后台任务的<b>处理端口</b>。
 *
 * <p>队列实现在收到任务后按类型调用本端口；处理逻辑实现本接口并注册为 bean 即可接入，
 * 无需改动队列与本模块的其他文件。</p>
 *
 * <p>没有实现 bean 时，队列会记录一条 warn 并<u>丢弃</u>任务——任务不会被静默当成功，
 * 但也不会让应用起不来；这让本模块可以独立编译、独立跑测试。</p>
 */
public interface WikiIngestTaskHandler {

    /**
     * 认领/窥视一批 pending op、跑 Map/Reduce、结算认领并链式安排后续批次。
     *
     * @param payload 触发载荷（KB 级；真正的文档在 task_pending_ops 里）
     */
    void processWikiIngest(WikiIngestPayload payload);

    /**
     * 排空 finalize 通道的 slug / change / folder_prune 行，重建索引导语、
     * 清理死链、注入交叉链接、剪掉空目录。
     *
     * @param payload 触发载荷
     */
    void processWikiFinalize(WikiIngestPayload payload);
}
