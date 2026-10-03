package com.ragagent.mcp.protocol;

/**
 * initialize 握手结果。
 *
 * <p>{@code instructions} 是服务端级的 MCP 文档，会被缓存在客户端并随面向模型的工具一起
 * 提供——它<b>不是凭据</b>，不参与脱敏决策。</p>
 */
public record InitializeResult(
        String protocolVersion,
        ServerCapabilities capabilities,
        ServerInfo serverInfo,
        String instructions) {

    public InitializeResult {
        protocolVersion = protocolVersion == null ? "" : protocolVersion;
        capabilities = capabilities == null ? ServerCapabilities.empty() : capabilities;
        serverInfo = serverInfo == null ? ServerInfo.empty() : serverInfo;
        instructions = instructions == null ? "" : instructions;
    }
}
