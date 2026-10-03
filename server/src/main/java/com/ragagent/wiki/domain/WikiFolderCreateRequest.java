package com.ragagent.wiki.domain;


/**
 * 在 parentID 下新建（初始为空）文件夹的请求。JSON 键为 snake（前端按此解析）。
 */
public record WikiFolderCreateRequest( String parentId, String name) {
}
