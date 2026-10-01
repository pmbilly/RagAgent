package com.ragagent.datasource.dto;

import java.util.LinkedHashMap;
import java.util.Map;


/**
 * 凭据子资源的响应（对照 Go {@code dto.CredentialsResponse}，
 * internal/handler/dto/mcp.go L176-182）。
 *
 * <p>{@code PUT /datasource/{id}/credentials} 返回
 * {@code {"success":true,"data":{"fields":{"credentials":{"configured":true}}}}}。</p>
 *
 * <p>数据源只有一个逻辑字段 {@code "credentials"}——连接器凭据是一张按连接器而异的
 * <b>原子 map</b>（OAuth token 对、Confluence 的 email+token 组合……），
 * 拆成一个个具名字段会造出「配了一半、根本认证不了」的中间态。
 * 所以 PUT 整张替换、DELETE 整张清空，不做单字段增删。</p>
 */
public record CredentialsResponse(
        Map<String, CredentialFieldMetadata> fields) {

    /** Go 的 {@code map[string]dto.CredentialFieldMetadata{"credentials": {configured}}}。 */
    public static CredentialsResponse credentials(boolean configured) {
        Map<String, CredentialFieldMetadata> fields = new LinkedHashMap<>();
        fields.put("credentials", new CredentialFieldMetadata(configured));
        return new CredentialsResponse(fields);
    }
}
