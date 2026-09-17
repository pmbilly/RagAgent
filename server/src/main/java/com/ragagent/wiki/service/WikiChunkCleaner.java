package com.ragagent.wiki.service;

/**
 * wiki 页面删除时清理其同步 chunk 的可插拔端口。
 *
 * <p>对照 Go {@code wikiPageService.deleteChunkForPage}（wiki_page.go L1240-1248）：
 * chunk 同步是<b>可选接线</b>——没装 chunk 仓储的 service 直接跳过，而不是让删除失败。
 * Go 用 {@code if s.chunkRepo == nil { return }} 实现这一条。</p>
 *
 * <p>Java 侧 knowledge 模块的 chunk 同步（wiki 页 → chunks 表）尚未翻译，
 * 因此这里定义一个窄端口；Spring 没有实现 bean 时 {@code deletePage} 保持 Go 的
 * 「跳过」行为（不是报错）。</p>
 *
 * <p>Go 的实参：{@code chunkID = "wp-" + page.ID}，
 * 删除谓词 {@code WHERE tenant_id = ? AND id = ?}（硬删，见
 * repository/chunk.go L523-525 {@code DeleteChunk}）。</p>
 */
public interface WikiChunkCleaner {

    /**
     * 对照 Go {@code deleteChunkForPage}：删除 id 为 {@code "wp-"+pageId} 的 chunk。
     *
     * @param tenantId 页面所属租户（Go 的 {@code page.TenantID}）
     * @param chunkId  调用方已拼好的 chunk id
     */
    void deleteWikiPageChunk(Long tenantId, String chunkId);

    /** 拼装 chunk id：对照 Go {@code "wp-" + page.ID} */
    static String chunkIdFor(String pageId) {
        return "wp-" + pageId;
    }
}
