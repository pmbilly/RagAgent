package com.ragagent.apikey.mapper;

/**
 * 对照 Go {@code repository.ErrTenantAPIKeyNotFound}
 * （internal/application/repository/tenant_api_key.go L13）。
 *
 * <p>Go 用 {@code errors.New} 哨兵 + {@code errors.Is} 判定；Java 用专用受检外的
 * 运行时异常类型，语义等价且免除调用方比字符串。</p>
 *
 * <p>映射到 HTTP 的两个位置：认证通道（{@code APIKeyAuthChannel}）把它翻成
 * 401 {@code Unauthorized: invalid API key}——**撤销 / 过期的 Key 与不存在的 Key
 * 得到完全相同的响应**，这是刻意的信息隐藏；管理端点（Update/Delete）把它翻成
 * 404 {@code API key not found}。</p>
 */
public class TenantAPIKeyNotFoundException extends RuntimeException {

    public TenantAPIKeyNotFoundException() {
        super("tenant api key not found");
    }
}
