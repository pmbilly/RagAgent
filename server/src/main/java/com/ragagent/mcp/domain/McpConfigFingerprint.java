package com.ragagent.mcp.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MCP 服务配置的规范化 JSON 指纹。
 *
 * <p>摘要排除展示文案与启用状态：改文档不改上游身份。但 <b>秘密影响身份</b>——
 * api_key / token 参与摘要（只存摘要，不存秘密本身）。</p>
 *
 * <p>⚠️ 规范化 JSON 的字节形态是稳定契约：顶层字段序固定为
 * {@code Transport, URL, Headers, Auth, Stdio, Env}，顶层键名原样保留、
 * 即使是 null 也输出 {@code null}（无 omitempty）；嵌套对象则按各自的 omitempty
 * 规则省略。摘要字节形态一旦变化，已有 mcp_metadata 行的 Stale 判定就会误报。</p>
 *
 * <p>字符串编码开启 HTML 转义（{@code < > &} 三字符转成 unicode 转义）
 * 与 U+2028/U+2029 转义。</p>
 */
public final class McpConfigFingerprint {

    private McpConfigFingerprint() {}

    /**
     * 摘要算法：SHA-256 十六进制小写。
     *
     * @param service 服务配置（调用方总是传非 null；null 时返回 null）
     */
    public static String of(McpService service) {
        if (service == null) {
            return null;
        }
        String raw = canonicalJson(service);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sum = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(sum.length * 2);
            for (byte b : sum) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 暴露规范化 JSON，便于测试比对。 */
    public static String canonicalJson(McpService s) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"Transport\":").append(quote(s.getTransportType() == null ? "" : s.getTransportType()));
        sb.append(",\"URL\":").append(s.getUrl() == null ? "null" : quote(s.getUrl()));
        sb.append(",\"Headers\":").append(stringMap(s.getHeaders()));
        sb.append(",\"Auth\":").append(authConfig(s.getAuthConfig()));
        sb.append(",\"Stdio\":").append(stdioConfig(s.getStdioConfig()));
        sb.append(",\"Env\":").append(stringMap(s.getEnvVars()));
        sb.append('}');
        return sb.toString();
    }

    /** 嵌套鉴权配置的规范化形态（snake_case 键名 + omitempty，含 custom_headers 的键排序） */
    private static String authConfig(McpAuthConfig c) {
        if (c == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        first = appendString(sb, first, "auth_type", c.getAuthType() == null ? "" : c.getAuthType().value());
        first = appendString(sb, first, "api_key", c.getApiKey());
        first = appendString(sb, first, "api_key_header", c.getApiKeyHeader());
        first = appendString(sb, first, "token", c.getToken());
        // omitempty 对 map 的作用：nil 与空 map 都省略
        if (c.getCustomHeaders() != null && !c.getCustomHeaders().isEmpty()) {
            appendName(sb, first, "custom_headers");
            first = false;
            sb.append(stringMap(c.getCustomHeaders()));
        }
        // omitempty 对 slice 的作用：nil 与空 slice 都省略
        if (c.getScopes() != null && !c.getScopes().isEmpty()) {
            appendName(sb, first, "scopes");
            first = false;
            sb.append('[');
            for (int i = 0; i < c.getScopes().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(quote(c.getScopes().get(i)));
            }
            sb.append(']');
        }
        appendString(sb, first, "auth_server_metadata_url", c.getAuthServerMetadataUrl());
        sb.append('}');
        return sb.toString();
    }

    /** stdio 配置的规范化形态：command / args **都无 omitempty**，null args → null */
    private static String stdioConfig(McpStdioConfig c) {
        if (c == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        // 未赋值时按空串处理（无 omitempty，恒输出）
        sb.append("{\"command\":").append(quote(c.getCommand() == null ? "" : c.getCommand()));
        sb.append(",\"args\":");
        if (c.getArgs() == null) {
            sb.append("null");
        } else {
            sb.append('[');
            for (int i = 0; i < c.getArgs().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(quote(c.getArgs().get(i)));
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    /** 字符串 map 的规范化形态：键按字节序排序，null → null，空 map → {} */
    private static String stringMap(Map<String, String> m) {
        if (m == null) {
            return "null";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            sorted.put(e.getKey() == null ? "" : e.getKey(), e.getValue());
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    private static boolean appendString(StringBuilder sb, boolean first, String name, String value) {
        if (value == null || value.isEmpty()) {
            return first;
        }
        appendName(sb, first, name);
        sb.append(quote(value));
        return false;
    }

    private static void appendName(StringBuilder sb, boolean first, String name) {
        if (!first) {
            sb.append(',');
        }
        sb.append(quote(name)).append(':');
    }

    /**
     * 字符串编码（HTML 转义开启）：
     * 引号与反斜杠转义，控制字符转 unicode 转义（换行/回车/制表符用短形式），
     * {@code < > &} 转 unicode 转义，U+2028/U+2029 同样转义。
     */
    static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int width = Character.charCount(cp);
            i += width;
            switch (cp) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case 0x3c -> sb.append("\\u003c");
                case 0x3e -> sb.append("\\u003e");
                case 0x26 -> sb.append("\\u0026");
                case 0x2028 -> sb.append("\\u2028");
                case 0x2029 -> sb.append("\\u2029");
                default -> {
                    if (cp < 0x20) {
                        sb.append(String.format("\\u%04x", cp));
                    } else if (cp >= 0xD800 && cp <= 0xDFFF) {
                        // 未配对代理项：替换为 U+FFFD
                        sb.append('�');
                    } else {
                        sb.appendCodePoint(cp);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 供测试/调试：列出全部顶层键（固定键序） */
    static List<String> fingerprintKeys() {
        List<String> keys = new ArrayList<>();
        keys.add("Transport");
        keys.add("URL");
        keys.add("Headers");
        keys.add("Auth");
        keys.add("Stdio");
        keys.add("Env");
        return keys;
    }
}
