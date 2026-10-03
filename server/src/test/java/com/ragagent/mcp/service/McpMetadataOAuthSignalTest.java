package com.ragagent.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ragagent.common.error.BizException;
import com.ragagent.mcp.protocol.McpOAuthRequiredException;

/**
 * 刷新失败归一：**"上游要求 OAuth 授权"的信号必须保留**。
 *
 * <p>实案（2026-10-03 点检）：tencent-docs 未授权时，元数据刷新只回通用文案
 * "Failed to refresh MCP tools. Check the connection and try again."，
 * 用户看不出要先去授权（真正原因只写在后端日志里）。</p>
 */
class McpMetadataOAuthSignalTest {

    @Test
    void oauthRequiredSignalSurvivesWrapping() {
        RuntimeException upstream = new RuntimeException("connect failed",
                new McpOAuthRequiredException(
                        "https://docs.qq.com/openapi/mcp/.well-known/oauth-protected-resource",
                        new RuntimeException("authorization required")));

        BizException normalized = McpMetadataService.refreshFailure(
                "could not refresh MCP directory: ", upstream);

        assertTrue(normalized instanceof McpMetadataException,
                "应归一成 McpMetadataException（handler 才能按 Kind 映射）");
        assertEquals(McpMetadataException.Kind.OAUTH_REQUIRED,
                ((McpMetadataException) normalized).kind());
        assertTrue(String.valueOf(normalized.appError().message()).contains("OAuth"),
                "文案要可操作: " + normalized.appError().message());
    }

    @Test
    void genericFailureKeepsInternalSemantics() {
        BizException normalized = McpMetadataService.refreshFailure(
                "could not refresh MCP directory: ", new RuntimeException("boom"));

        assertFalse(normalized instanceof McpMetadataException,
                "普通失败不改变语义（仍 internal）");
        assertTrue(String.valueOf(normalized.appError().message())
                .startsWith("could not refresh MCP directory: "));
    }
}
