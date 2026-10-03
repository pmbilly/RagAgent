package com.ragagent.datasource.connector.notion;

import java.util.Map;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.DataSourceConfig;

/**
 * Notion 连接器自己的配置。
 *
 * <h2>三条错误语义</h2>
 * <ol>
 *   <li>config 为 {@code null} → <b>裸</b> {@code InvalidConfig}
 *       （{@code "invalid configuration"}，**不带细节**）。</li>
 *   <li>credentials 里没有 {@code api_key} 键 → {@code "invalid credentials: missing api_key"}。</li>
 *   <li>{@code api_key} 不是字符串、或是**空串** → 同一个哨兵、同一句
 *       {@code "invalid credentials: api_key must be a non-empty string"}
 *       （数字 42 与 {@code ""} 得到同一句话）。</li>
 * </ol>
 */
public final class NotionConfig {

    /** 内部集成令牌（Internal Integration Token）。 */
    public final String apiKey;

    public NotionConfig(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 解析并校验配置。
     *
     * @throws ConnectorException 详细语义见类注释
     */
    public static NotionConfig parse(DataSourceConfig config) {
        if (config == null) {
            throw new ConnectorException.InvalidConfig();
        }
        Map<String, Object> credentials = config.getCredentials();
        if (credentials == null || !credentials.containsKey("api_key")) {
            throw new ConnectorException.InvalidCredentials("missing api_key");
        }
        Object raw = credentials.get("api_key");
        if (!(raw instanceof String token) || token.isEmpty()) {
            throw new ConnectorException.InvalidCredentials("api_key must be a non-empty string");
        }
        return new NotionConfig(token);
    }
}
