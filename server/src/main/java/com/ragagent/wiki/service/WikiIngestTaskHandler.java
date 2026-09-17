package com.ragagent.wiki.service;

/**
 * wiki 后台任务的<b>处理端口</b>（对照 Go {@code wikiIngestService.Handle} 分派到的
 * {@code ProcessWikiIngest} / {@code ProcessWikiFinalize}，wiki_ingest.go L671-678）。
 *
 * <p><b>这是留给 wiki_ingest_batch.go 翻译任务的接缝</b>：Go 里
 * {@code Handle}（本任务翻译）按任务类型分派到两个方法，而这两个方法定义在
 * {@code wiki_ingest_batch.go}（<b>不在本任务范围</b>）。Java 侧因此把分派目标抽成本端口，
 * 队列实现在收到任务后调用它；batch 的翻译任务只需实现本接口并注册为 bean，
 * <b>无需改动任何本任务产出的文件</b>。</p>
 *
 * <p>没有实现 bean 时，队列会记录一条 warn 并<u>丢弃</u>任务——这与 Go 里
 * "任务类型未注册"的表现一致（任务不会被静默当成功，但也不会让应用起不来），
 * 同时让本模块可以独立编译、独立跑测试。</p>
 */
public interface WikiIngestTaskHandler {

    /**
     * 对照 Go {@code ProcessWikiIngest}（wiki_ingest_batch.go L192）：
     * 认领/窥视一批 pending op、跑 Map/Reduce、结算认领并链式安排后续批次。
     *
     * @param payload 触发载荷（KB 级；真正的文档在 task_pending_ops 里）
     */
    void processWikiIngest(WikiIngestPayload payload);

    /**
     * 对照 Go {@code ProcessWikiFinalize}（wiki_ingest_batch.go L916）：
     * 排空 finalize 通道的 slug / change / folder_prune 行，重建索引导语、
     * 清理死链、注入交叉链接、剪掉空目录。
     *
     * @param payload 触发载荷
     */
    void processWikiFinalize(WikiIngestPayload payload);
}
