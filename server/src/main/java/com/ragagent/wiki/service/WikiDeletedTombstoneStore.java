package com.ragagent.wiki.service;

/**
 * 「某知识库下的某文档最近被删了」的墓碑（对照 Go 的
 * {@code wiki:deleted:<kbID>:<knowledgeID>} 键，wiki_ingest.go L163-173 与
 * {@code isKnowledgeGone} 的快路径 L2801-2805）。
 *
 * <p>由 {@code cleanupWikiOnKnowledgeDelete} 写入，让任何仍在飞行（或排队）的
 * wiki_ingest 任务<b>不必查库</b>就能快速跳过。TTL 大于 {@code wikiIngestDelay}，
 * 保证它必然熬过任何在途的 ingest。</p>
 *
 * <p><b>⚠️ 多实例差异</b>：进程内实现的墓碑只在写了它的那个 JVM 里可见。
 * 多副本下"删除后另一副本的批次仍处理该文档"会短暂发生——但 {@link
 * com.ragagent.wiki.service.WikiIngestService#isKnowledgeGone} 在墓碑未命中时
 * <b>会回落到数据库查询</b>，因此正确性不依赖墓碑（墓碑只是快路径，不是唯一防线）。
 * 这一点与 Go 相同：Go 的 DB 回落同样是兜底。</p>
 */
public interface WikiDeletedTombstoneStore {

    /**
     * 对照 Go {@code redirectClient.Exists(key) > 0}：该文档是否有未过期的删除墓碑。
     */
    boolean exists(String kbId, String knowledgeId);

    /**
     * 写墓碑。TTL 由实现按 {@link WikiIngestConstants#DELETED_TTL} 处理
     * （对照 Go 写入方使用的 {@code wikiDeletedTTL}）。
     */
    void markDeleted(String kbId, String knowledgeId);

    /**
     * 清除墓碑（测试 / 误删恢复的运维操作；Go 侧靠 TTL 自然过期）。
     */
    void clear(String kbId, String knowledgeId);
}
