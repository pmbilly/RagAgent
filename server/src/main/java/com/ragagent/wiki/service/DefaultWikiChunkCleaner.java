package com.ragagent.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.mapper.ChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link WikiChunkCleaner} 的生产实现：wiki 页面删除时同步删除其镜像 chunk
 * （id = {@code "wp-" + pageId}，谓词 tenant_id + id）。
 *
 * <p>此前该端口没有实现 bean，{@code WikiPageServiceImpl.deletePage} 恒走"跳过"
 * 分支——被删页面的镜像 chunk 永久残留，可能被检索命中为幽灵来源。
 * chunk 清理失败只记 WARN，不让页面删除失败。</p>
 */
@Component
public class DefaultWikiChunkCleaner implements WikiChunkCleaner {

    private static final Logger log = LoggerFactory.getLogger(DefaultWikiChunkCleaner.class);

    private final ChunkMapper chunkMapper;

    public DefaultWikiChunkCleaner(ChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    @Override
    public void deleteWikiPageChunk(Long tenantId, String chunkId) {
        if (tenantId == null || chunkId == null || chunkId.isEmpty()) {
            return;
        }
        try {
            chunkMapper.delete(new LambdaQueryWrapper<Chunk>()
                    .eq(Chunk::getTenantId, tenantId)
                    .eq(Chunk::getId, chunkId));
        } catch (RuntimeException e) {
            // 清理失败仅记 WARN：不阻断页面删除流程
            log.warn("wiki: failed to delete chunk {} for tenant {}: {}",
                    chunkId, tenantId, e.toString());
        }
    }
}
