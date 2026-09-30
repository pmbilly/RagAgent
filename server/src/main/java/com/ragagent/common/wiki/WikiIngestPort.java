package com.ragagent.common.wiki;

/**
 * wiki 摄取端口：知识处理完成后把文档投递给 wiki（维基）侧异步摄取。
 *
 * <p>{@code WikiIngestService}（1198 行、依赖 wiki 整套服务）不能进 common；knowledge 只用到它的
 * 一个方法与一个返回载荷，故抽成最窄接口（端口定式：只读/写的最小契约放最底层）。</p>
 */
public interface WikiIngestPort {

    /** 入队结果：{@code accepted} = 能否推进（如 KB 已删则 false）；{@code error} 仅作诊断。 */
    record EnqueueResult(boolean accepted, Exception error) {
    }

    /** 把（租户, KB, 知识）投递给 wiki 摄取。 */
    EnqueueResult enqueueWikiIngest(long tenantId, String kbId, String knowledgeId);
}
