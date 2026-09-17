package com.ragagent.mcp.protocol;

/**
 * initialize 握手结果（对照 Go internal/mcp/types.go 的 {@code InitializeResult}）。
 *
 * <p>{@code instructions} 是服务端级的 MCP 文档，会被缓存在客户端并随面向模型的工具一起
 * 提供（Go 的 {@code ServerInstructions()}，client.go:448-454）——它<b>不是凭据</b>，
 * 不参与脱敏决策。</p>
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
