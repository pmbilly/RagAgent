package com.ragagent.mcp.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ragagent.common.error.BizException;
import com.ragagent.mcp.service.McpMetadataException;

/**
 * 目录快照异常 → HTTP 文案的映射。
 *
 * <p>钉子：OAuth 授权场景**不得**落到刷新的通用文案
 * （"Failed to refresh MCP tools. Check the connection and try again."）——
 * 那是 2026-10-03 点检暴露的问题。</p>
 */
class McpMetadataErrorMappingTest {

    @Test
    void oauthRequiredKeepsActionableMessage() {
        BizException mapped = McpServiceController.mcpMetadataAppError(
                McpMetadataException.oauthRequired("authorization required"), true);

        String message = String.valueOf(mapped.appError().message());
        assertTrue(message.contains("OAuth"), "文案要指出 OAuth 授权: " + message);
        assertFalse(message.contains("Failed to refresh MCP tools"),
                "不得回落通用文案: " + message);
    }
}
