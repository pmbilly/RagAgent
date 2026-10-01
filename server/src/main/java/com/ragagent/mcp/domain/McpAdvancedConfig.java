package com.ragagent.mcp.domain;


/**
 * MCP 服务高级配置（对照 Go types.MCPAdvancedConfig）。
 * 默认值见 {@link #defaults()}（对照 Go GetDefaultAdvancedConfig）。
 *
 * <p>⚠️ 必须保留 {@code retry_count} / {@code retry_delay} 这两个线上的键名：
 * Go 的 json tag 是蛇形，DB 的 jsonb 列里存的也是蛇形。若按 Java 字段名输出
 * （retryCount），既与 Go 写的行不兼容，也会让响应体偏离契约。</p>
 */
public class McpAdvancedConfig {

    /** 超时（秒），默认 30 */
    private int timeout;
    /** 重试次数，默认 3 */
    private int retryCount;
    /** 重试间隔（秒），默认 1 */
    private int retryDelay;

    public McpAdvancedConfig() {
    }

    public McpAdvancedConfig(int timeout, int retryCount, int retryDelay) {
        this.timeout = timeout;
        this.retryCount = retryCount;
        this.retryDelay = retryDelay;
    }

    /** 对照 Go GetDefaultAdvancedConfig：30 / 3 / 1 */
    public static McpAdvancedConfig defaults() {
        return new McpAdvancedConfig(30, 3, 1);
    }

    public int getTimeout() { return timeout; }
    public void setTimeout(int v) { timeout = v; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int v) { retryCount = v; }
    public int getRetryDelay() { return retryDelay; }
    public void setRetryDelay(int v) { retryDelay = v; }
}
