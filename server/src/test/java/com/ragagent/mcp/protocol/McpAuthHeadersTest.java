package com.ragagent.mcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/mcp/client_test.go 全文：
 * {@code TestAsOAuthRequired} / {@code TestApplyAuthHeaders} / {@code TestMCPTextPreview}。
 */
class McpAuthHeadersTest {

    /** 对照 Go TestApplyAuthHeaders 的表驱动用例。 */
    @Nested
    @DisplayName("applyAuthHeaders 只注入被选中的策略")
    class ApplyAuthHeaders {

        @Test
        @DisplayName("nil 配置不注入任何头")
        void nilConfigInjectsNothing() {
            Map<String, String> headers = new LinkedHashMap<>();
            McpAuthHeaders.applyAuthHeaders(headers, null);
            assertEquals(Map.of(), headers);
        }

        @Test
        @DisplayName("api_key 默认用 X-API-Key")
        void apiKeyUsesDefaultHeader() {
            McpAuthConfig ac = auth(McpAuthType.API_KEY);
            ac.setApiKey("k1");
            assertEquals(Map.of("X-API-Key", "k1"), apply(ac));
        }

        @Test
        @DisplayName("api_key 尊重自定义头名（如把裸 token 放进 Authorization）")
        void apiKeyHonorsCustomHeaderName() {
            McpAuthConfig ac = auth(McpAuthType.API_KEY);
            ac.setApiKey("f7bfde");
            ac.setApiKeyHeader("Authorization");
            assertEquals(Map.of("Authorization", "f7bfde"), apply(ac));
        }

        @Test
        @DisplayName("bearer 补 Bearer 前缀")
        void bearerAddsPrefix() {
            McpAuthConfig ac = auth(McpAuthType.BEARER);
            ac.setToken("t1");
            assertEquals(Map.of("Authorization", "Bearer t1"), apply(ac));
        }

        @Test
        @DisplayName("选中策略互斥——陈旧 token 不会被一起发出去（双重认证修复）")
        void selectedStrategyIsExclusive() {
            McpAuthConfig ac = auth(McpAuthType.API_KEY);
            ac.setApiKey("k1");
            ac.setToken("stale");
            assertEquals(Map.of("X-API-Key", "k1"), apply(ac));
        }

        @Test
        @DisplayName("空/None 策略保留历史行为（从字段推断）")
        void emptyAuthTypeKeepsLegacyBehavior() {
            McpAuthConfig ac = auth(McpAuthType.NONE);
            ac.setApiKey("k1");
            ac.setToken("t1");
            assertEquals(Map.of("X-API-Key", "k1", "Authorization", "Bearer t1"), apply(ac));
        }

        @Test
        @DisplayName("custom headers 恒叠加且可覆盖策略头")
        void customHeadersAreLayeredOnTop() {
            McpAuthConfig ac = auth(McpAuthType.BEARER);
            ac.setToken("t1");
            ac.setCustomHeaders(Map.of("X-Trace", "abc"));
            assertEquals(Map.of("Authorization", "Bearer t1", "X-Trace", "abc"), apply(ac));
        }

        @Test
        @DisplayName("custom header 覆盖策略头（同 key 后者胜）")
        void customHeadersOverrideStrategyHeader() {
            McpAuthConfig ac = auth(McpAuthType.API_KEY);
            ac.setApiKey("k1");
            ac.setCustomHeaders(Map.of("X-API-Key", "override"));
            assertEquals(Map.of("X-API-Key", "override"), apply(ac));
        }

        @Test
        @DisplayName("oauth 策略不发任何静态头（由 OAuth 运行时接管）")
        void oauthStrategyEmitsNoStaticHeader() {
            McpAuthConfig ac = auth(McpAuthType.OAUTH);
            ac.setToken("ignored");
            assertEquals(Map.of(), apply(ac));
        }

        @Test
        @DisplayName("service.headers 打底，鉴权头叠加在其上")
        void serviceHeadersAreMerged() {
            McpAuthConfig ac = auth(McpAuthType.BEARER);
            ac.setToken("t1");
            Map<String, String> headers = McpAuthHeaders.buildHeaders(Map.of("X-Base", "1"), ac);
            assertEquals(Map.of("X-Base", "1", "Authorization", "Bearer t1"), headers);
        }
    }

    /** 对照 Go TestAsOAuthRequired。 */
    @Nested
    @DisplayName("asOAuthRequired 只认带 metadata URL 的 401")
    class AsOAuthRequired {

        @Test
        @DisplayName("nil 错误")
        void nilError() {
            assertNull(McpAuthHeaders.asOAuthRequired(null));
        }

        @Test
        @DisplayName("带 RFC 9728 metadata 的 401 → OAuth required")
        void withMetadataIsOAuthRequired() {
            String meta = "https://example.com/.well-known/oauth-protected-resource";
            RuntimeException err = new RuntimeException("wrap",
                    new McpAuthorizationRequiredException(meta));
            McpOAuthRequiredException got = McpAuthHeaders.asOAuthRequired(err);
            assertTrue(got != null, "期望非 null 的 OAuthRequired");
            assertEquals(meta, got.metadataUrl());
        }

        @Test
        @DisplayName("裸 401（无 metadata）不算 OAuth required")
        void bareUnauthorizedIsNotOAuthRequired() {
            assertNull(McpAuthHeaders.asOAuthRequired(new McpAuthorizationRequiredException("")));
        }

        @Test
        @DisplayName("无关错误被忽略")
        void unrelatedErrorIsIgnored() {
            assertNull(McpAuthHeaders.asOAuthRequired(new RuntimeException("connection refused")));
        }
    }

    /** 对照 Go TestMCPTextPreview。 */
    @Nested
    @DisplayName("mcpTextPreview 截断纪律")
    class TextPreview {

        @Test
        @DisplayName("先脱敏（换行 → 空格）")
        void sanitizesNewlines() {
            assertEquals("hello world", McpLog.preview("hello\nworld", 80));
        }

        @Test
        @DisplayName("按字符（码点）截断并追加省略号")
        void truncatesByCodePoints() {
            assertEquals("一二三...", McpLog.preview("一二三四五", 3));
        }

        @Test
        @DisplayName("长度为 0 或负 → 空串")
        void nonPositiveLimitReturnsEmpty() {
            assertEquals("", McpLog.preview("abc", 0));
            assertEquals("", McpLog.preview("abc", -1));
        }

        @Test
        @DisplayName("刚好等于上限不截断")
        void exactlyAtLimitIsNotTruncated() {
            assertEquals("abc", McpLog.preview("abc", 3));
        }
    }

    /** 对照 mcp-go 的 WWW-Authenticate 解析（streamable_http.go:347-400 的用例）。 */
    @Nested
    @DisplayName("WWW-Authenticate 的 resource_metadata 提取")
    class ResourceMetadata {

        @Test
        @DisplayName("带引号的 resource_metadata")
        void quotedValue() {
            assertEquals("https://example.com", McpAuthHeaders.extractResourceMetadataUrl(
                    List.of("Bearer resource_metadata=\"https://example.com\"")));
        }

        @Test
        @DisplayName("多参数 + 大小写不敏感的参数名")
        void multipleParams() {
            assertEquals("https://example.com/metadata", McpAuthHeaders.extractResourceMetadataUrl(
                    List.of("Bearer realm=\"example\", Resource_Metadata=\"https://example.com/metadata\", scope=\"read write\"")));
        }

        @Test
        @DisplayName("无 resource_metadata 参数 → 空串")
        void absentParameter() {
            assertEquals("", McpAuthHeaders.extractResourceMetadataUrl(List.of("Bearer realm=\"example\"")));
        }
    }

    private static McpAuthConfig auth(McpAuthType type) {
        McpAuthConfig ac = new McpAuthConfig();
        ac.setAuthType(type);
        return ac;
    }

    private static Map<String, String> apply(McpAuthConfig ac) {
        Map<String, String> headers = new LinkedHashMap<>();
        McpAuthHeaders.applyAuthHeaders(headers, ac);
        return headers;
    }
}
