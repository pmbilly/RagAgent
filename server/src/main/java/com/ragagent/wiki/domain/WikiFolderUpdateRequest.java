package com.ragagent.wiki.domain;


/**
 * 重命名 / 移动文件夹的请求。JSON 键为 snake（§11 登记边界，前端按此解析）。
 *
 * <p>{@code parentId} <b>只在 moveParent 为 true 时生效</b>，这样纯重命名不必重发
 * （可能是根 "" 的）父 id，避免意外的移动。</p>
 */
public record WikiFolderUpdateRequest( String name, String parentId, boolean moveParent) {
}
