package com.ragagent.wiki.service.ingest;

/**
 * 「某知识库下的某文档最近被删了」的墓碑。
 *
 * <p>由文档删除清理写入，让任何仍在飞行（或排队）的
 * wiki_ingest 任务<b>不必查库</b>就能快速跳过。TTL 大于 ingest 延迟，
 * 保证它必然熬过任何在途的 ingest。</p>
 *
 * <p><b>⚠️ 多实例差异</b>：进程内实现的墓碑只在写了它的那个 JVM 里可见。
 * 多副本下"删除后另一副本的批次仍处理该文档"会短暂发生——但 {@link
 * com.ragagent.wiki.service.ingest.WikiIngestService#isKnowledgeGone} 在墓碑未命中时
 * <b>会回落到数据库查询</b>，因此正确性不依赖墓碑（墓碑只是快路径，不是唯一防线）。</p>
 */
public interface WikiDeletedTombstoneStore {

    /**
     * 该文档是否有未过期的删除墓碑。
     */
    boolean exists(String kbId, String knowledgeId);

    /**
     * 写墓碑。TTL 由实现按 {@link WikiIngestConstants#DELETED_TTL} 处理。
     */
    void markDeleted(String kbId, String knowledgeId);

    /**
     * 清除墓碑（测试 / 误删恢复的运维操作）。
     */
    void clear(String kbId, String knowledgeId);
}
