package com.ragagent.knowledge.service;

import java.util.List;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;

/**
 * chunk 读取门面：控制器只与本类交互，不直接碰仓储。
 *
 * <p>分层的意义在这里很直观——分页查询有一串透传参数（类型过滤、排序键等）、
 * 单行查询有"查不到抛什么异常"的区别、摘要重载是 best-effort。把这些细节留在本层，
 * 控制器只表达"我要什么"，仓储则只表达"怎么查"。</p>
 */
@Service
public class ChunkReadService {

    private final ChunkRepository chunkRepository;
    private final KnowledgeMapper knowledgeMapper;

    public ChunkReadService(ChunkRepository chunkRepository, KnowledgeMapper knowledgeMapper) {
        this.chunkRepository = chunkRepository;
        this.knowledgeMapper = knowledgeMapper;
    }

    /** 分页结果（items 恒非 null）。 */
    public record ChunkPageView(List<Chunk> items, long total) {
    }

    /**
     * 按文档分页取 chunk。
     *
     * @param types chunk 类型过滤（调用方已填默认值，如 {@code text}）
     */
    public ChunkPageView listPagedChunks(long tenantId, String knowledgeId, int offset, int limit,
                                        List<String> types) {
        ChunkRepository.ChunkPage page = chunkRepository.listPagedChunksByKnowledgeId(
                tenantId, knowledgeId, offset, limit, types, null, "", "", "", "", null);
        return new ChunkPageView(page.items(), page.total());
    }

    /** 按 chunk id 取（不存在 → {@code ChunkNotFoundException}，由调用方决定 404 文案）。 */
    public Chunk getChunkByIdOnly(String chunkId) {
        return chunkRepository.getChunkByIdOnly(chunkId);
    }

    /** 按租户 + chunk id 取（不存在 → {@code ChunkNotFoundException}）。 */
    public Chunk getChunkById(long tenantId, String chunkId) {
        return chunkRepository.getChunkById(tenantId, chunkId);
    }

    /**
     * 摘要状态重载用：取文档行；异常交给调用方处理（调用点只 WARN，不阻断主流程）。
     */
    public Knowledge findForSummaryReload(String knowledgeId, long tenantId) {
        return knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, LogSanitizer.sanitize(knowledgeId))
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
    }
}
