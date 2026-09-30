package com.ragagent.knowledge.dto.doc;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;


/** 更新「上次导入结果」显示状态的请求。 */
public record UpdateLastImportDisplayStatusRequest(
        @NotBlank(message = "displayStatus: 不能为空")
        @Pattern(regexp = "open|close", message = "displayStatus: 必须为 open 或 close")
        String displayStatus) {
}
