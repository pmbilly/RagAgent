package com.ragagent.knowledge.dto.doc;


/** 文件夹移动响应：新路径 + 移动条数。 */
public record FolderMoveResponse(String folderPath, long movedCount) {
}
