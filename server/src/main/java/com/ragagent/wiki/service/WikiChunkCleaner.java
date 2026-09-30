package com.ragagent.wiki.service;

/**
 * wiki 页面删除时清理其同步 chunk 的可插拔端口。
 *
 * <p>chunk 同步是<b>可选接线</b>——没装 chunk 仓储的 service 直接跳过，而不是让删除失败。</p>
 *
 * <p>knowledge 模块的 chunk 同步（wiki 页 → chunks 表）尚未接线，
 * Spring 没有实现 bean 时 {@code deletePage} 保持「跳过」行为（不是报错）。</p>
 *
 * <p>实参约定：chunkID = {@code "wp-" + pageId}，
 * 删除谓词 {@code WHERE tenant_id = ? AND id = ?}（硬删）。</p>
 */
public interface WikiChunkCleaner {

    /**
     * 删除 id 为 {@code "wp-"+pageId} 的 chunk。
     *
     * @param tenantId 页面所属租户
     * @param chunkId  调用方已拼好的 chunk id
     */
    void deleteWikiPageChunk(Long tenantId, String chunkId);

    /** 拼装 chunk id：{@code "wp-" + pageId} */
    static String chunkIdFor(String pageId) {
        return "wp-" + pageId;
    }
}
