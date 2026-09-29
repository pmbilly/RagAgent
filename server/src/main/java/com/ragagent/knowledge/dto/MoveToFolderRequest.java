package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

/** 文档移入文件夹请求。 */
public record MoveToFolderRequest(
        @NotBlank(message = "kbId: 不能为空")
        String kbId,
        List<String> knowledgeIds,
        String folderPath) {
}
