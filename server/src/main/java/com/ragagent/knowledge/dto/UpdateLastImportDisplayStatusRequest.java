package com.ragagent.knowledge.dto;


/** 更新「上次导入结果」显示状态的请求。 */
public record UpdateLastImportDisplayStatusRequest(
        @jakarta.validation.constraints.NotBlank(message = "displayStatus: 不能为空")
        @jakarta.validation.constraints.Pattern(regexp = "open|close", message = "displayStatus: 必须为 open 或 close")
        String displayStatus) {
}
