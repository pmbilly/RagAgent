package com.ragagent.wiki.domain;


/**
 * 把 slug 标识的页面移动到 folderID 的请求。folderID 为 "" 表示根。
 * JSON 键为 snake（§11 登记边界，前端按此解析）。
 *
 * <p>slug 走请求体而非路径：wiki slug 是层级化的（"entity/acme"），
 * 放进路径会与 catch-all 通配路由冲突。</p>
 */
public record WikiPageMoveRequest( String slug, String folderId) {
}
