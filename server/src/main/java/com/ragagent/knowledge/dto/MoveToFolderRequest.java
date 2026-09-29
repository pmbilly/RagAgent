package com.ragagent.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** 文档移入文件夹请求。 */
public record MoveToFolderRequest(
        @NotBlank(message = "kbId: 不能为空")
        String kbId,
        List<String> knowledgeIds,
        String folderPath) {
}
