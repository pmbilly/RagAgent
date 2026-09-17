package com.ragagent.mcp.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 对照 Go types.MCPConfigFingerprint（internal/types/mcp_metadata.go:57-68）。
 *
 * <p><b>golden 来源</b>：用与 Go 同构的独立程序（复刻 MCPService/MCPAuthConfig/
 * MCPStdioConfig 的字段声明序与 json tag）跑 {@code json.Marshal} + SHA-256 得到，
 * 与 Java 实现逐字节比对。指纹跨语言必须一致——否则同一行 mcp_metadata
 * 在 Go 实例与 Java 实例之间会被互相判成 stale。</p>
 */
class McpConfigFingerprintTest {

    private static McpService bare(String transport) {
        McpService s = new McpService();
        s.setTransportType(transport);
        return s;
    }

    static Stream<Arguments> goGoldens() {
        McpService empty = new McpService();

        McpService bareSse = new McpService();
        bareSse.setId("svc");
        bareSse.setTenantId(1L);
        bareSse.setName("n");
        bareSse.setEnabled(true);
        bareSse.setTransportType("sse");

        McpService urlHeaders = bare("http-streamable");
        urlHeaders.setUrl("https://example.com/mcp?a=1&b=2");
        urlHeaders.setHeaders(new java.util.LinkedHashMap<>(Map.of("X-A", "1", "X-B", "2")));

        McpService authAll = bare("sse");
        authAll.setUrl("");
        McpAuthConfig a = new McpAuthConfig();
        a.setAuthType(McpAuthType.OAUTH);
        a.setApiKey("sk-1");
        a.setApiKeyHeader("X-API-Key");
        a.setToken("tok");
        a.setCustomHeaders(new java.util.LinkedHashMap<>(Map.of("A", "a", "Z", "z")));
        a.setScopes(List.of("read", "write"));
        a.setAuthServerMetadataUrl("https://as.example.com/.well-known");
        authAll.setAuthConfig(a);

        McpService authEmpty = bare("sse");
        authEmpty.setAuthConfig(new McpAuthConfig());

        McpService escaped = bare("sse");
        McpAuthConfig esc = new McpAuthConfig();
        esc.setApiKey("a<b>&c\"d\\e" + "\n" + "\t");
        escaped.setAuthConfig(esc);

        McpService stdioEnv = bare("stdio");
        McpStdioConfig sc = new McpStdioConfig();
        sc.setCommand("npx");
        sc.setArgs(List.of("-y", "x"));
        stdioEnv.setStdioConfig(sc);
        stdioEnv.setEnvVars(new java.util.LinkedHashMap<>(Map.of("A", "1", "B", "2")));

        McpService stdioZero = bare("stdio");
        stdioZero.setStdioConfig(new McpStdioConfig());

        McpService emptyMaps = bare("sse");
        emptyMaps.setHeaders(new java.util.LinkedHashMap<>());
        emptyMaps.setEnvVars(new java.util.LinkedHashMap<>());

        McpService unicode = bare("sse");
        unicode.setName("中文");
        unicode.setHeaders(new java.util.LinkedHashMap<>(Map.of("名称", "值")));
        unicode.setAuthConfig(new McpAuthConfig());
        unicode.getAuthConfig().setScopes(List.of());

        return Stream.of(
                Arguments.of("empty", empty,
                        "{\"Transport\":\"\",\"URL\":null,\"Headers\":null,\"Auth\":null,"
                                + "\"Stdio\":null,\"Env\":null}",
                        "ec7a962c126d6a5256c935cf79829cb58437488bb5652cb6e143001e84440cc5"),
                Arguments.of("bare-sse", bareSse,
                        "{\"Transport\":\"sse\",\"URL\":null,\"Headers\":null,\"Auth\":null,"
                                + "\"Stdio\":null,\"Env\":null}",
                        "71243ceeed2f5f1000351085c61722aac89ce709dc1ffc8c8f48d29ac333928f"),
                Arguments.of("url+headers", urlHeaders,
                        "{\"Transport\":\"http-streamable\","
                                + "\"URL\":\"https://example.com/mcp?a=1\\u0026b=2\","
                                + "\"Headers\":{\"X-A\":\"1\",\"X-B\":\"2\"},"
                                + "\"Auth\":null,\"Stdio\":null,\"Env\":null}",
                        "75d13b4ebe01c2a3fe20632766f132ac71f1ed92696420b11cbf3e24ef400afe"),
                Arguments.of("auth-all", authAll,
                        "{\"Transport\":\"sse\",\"URL\":\"\",\"Headers\":null,"
                                + "\"Auth\":{\"auth_type\":\"oauth\",\"api_key\":\"sk-1\","
                                + "\"api_key_header\":\"X-API-Key\",\"token\":\"tok\","
                                + "\"custom_headers\":{\"A\":\"a\",\"Z\":\"z\"},"
                                + "\"scopes\":[\"read\",\"write\"],"
                                + "\"auth_server_metadata_url\":\"https://as.example.com/.well-known\"},"
                                + "\"Stdio\":null,\"Env\":null}",
                        "8572c65654546894abf6424e7c58d11015648789b3216acf14a2d28c5bc0ae44"),
                Arguments.of("auth-none-empty", authEmpty,
                        "{\"Transport\":\"sse\",\"URL\":null,\"Headers\":null,\"Auth\":{},"
                                + "\"Stdio\":null,\"Env\":null}",
                        "4c3cd4b49c831ef4a09ee308d13f78a3da09e0174944431d15193ecb4c44f62f"),
                Arguments.of("auth-escapes", escaped,
                        "{\"Transport\":\"sse\",\"URL\":null,\"Headers\":null,"
                                + "\"Auth\":{\"api_key\":\"a\\u003cb\\u003e\\u0026c\\\"d\\\\e\\n\\t\"},"
                                + "\"Stdio\":null,\"Env\":null}",
                        "824801af69bd2c929b1b8f111a221e0faf77a38b8402d769ef165ac9bff641e0"),
                Arguments.of("stdio+env", stdioEnv,
                        "{\"Transport\":\"stdio\",\"URL\":null,\"Headers\":null,\"Auth\":null,"
                                + "\"Stdio\":{\"command\":\"npx\",\"args\":[\"-y\",\"x\"]},"
                                + "\"Env\":{\"A\":\"1\",\"B\":\"2\"}}",
                        "0d592cd794db84fa724cfa56f4ba8da7391ff51ed4ff5d16c9625fc92297863c"),
                Arguments.of("stdio-zero", stdioZero,
                        "{\"Transport\":\"stdio\",\"URL\":null,\"Headers\":null,\"Auth\":null,"
                                + "\"Stdio\":{\"command\":\"\",\"args\":null},\"Env\":null}",
                        "d6da0d45683a9c74f1a49851667388cfdf388999e1a988fbd16157fe22a8be16"),
                Arguments.of("empty-headers-map", emptyMaps,
                        "{\"Transport\":\"sse\",\"URL\":null,\"Headers\":{},\"Auth\":null,"
                                + "\"Stdio\":null,\"Env\":{}}",
                        "336d8a98b6032d5d62c2528ce9e4d94f04530e1f42b7a66d96d45aa0eabff9f4"),
                Arguments.of("unicode", unicode,
                        "{\"Transport\":\"sse\",\"URL\":null,\"Headers\":{\"名称\":\"值\"},"
                                + "\"Auth\":{},\"Stdio\":null,\"Env\":null}",
                        "c2f44ce26be14c87bd3fba908ef33576e791866fb68d84c070227a0e7245b9d6"));
    }

    @ParameterizedTest(name = "go golden: {0}")
    @MethodSource("goGoldens")
    void matchesGoJsonAndDigest(String name, McpService service, String goJson, String goSha) {
        assertEquals(goJson, McpConfigFingerprint.canonicalJson(service),
                "规范化 JSON 必须与 Go json.Marshal 逐字节一致");
        assertEquals(goSha, McpConfigFingerprint.of(service), "SHA-256 必须与 Go 一致");
    }

    // ── 语义：什么进摘要、什么不进 ─────────────────────────────────────

    @Test
    void secretsAffectIdentitySoTheyParticipate() {
        McpService withKey = bare("sse");
        withKey.setAuthConfig(new McpAuthConfig());
        withKey.getAuthConfig().setApiKey("sk-a");
        McpService otherKey = bare("sse");
        otherKey.setAuthConfig(new McpAuthConfig());
        otherKey.getAuthConfig().setApiKey("sk-b");

        assertNotEquals(McpConfigFingerprint.of(withKey), McpConfigFingerprint.of(otherKey),
                "秘密影响身份：换 key 必须改变指纹（只存摘要，不存秘密本身）");
    }

    @Test
    void displayTextAndEnabledStateDoNotChangeUpstreamIdentity() {
        McpService base = bare("sse");
        base.setUsageInstructions("guidance");
        base.setDescription("overview");
        base.setEnabled(true);
        base.setName("n");

        McpService edited = bare("sse");
        edited.setUsageInstructions("EDITED guidance");
        edited.setDescription("EDITED overview");
        edited.setEnabled(false);
        edited.setName("renamed");

        assertEquals(McpConfigFingerprint.of(base), McpConfigFingerprint.of(edited),
                "usageInstructions / description / enabled 不进指纹：改文档不改上游身份");
    }
}
