package com.ragagent.model.dto;

/**
 * WeKnoraCloud 凭证状态：{@code hasModels}=凭证已配置（appId + appSecret 均非空）；
 * {@code needsReinit} 当前实现恒 false（宽容解密链路下不可达，见 service 类注释）；
 * {@code reason} 当前恒 null（保留字段位）。
 */
public record WeKnoraCloudStatusResponse(
        boolean hasModels,
        boolean needsReinit,
        String reason) {
}
