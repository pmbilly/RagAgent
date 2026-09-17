package com.ragagent.mcp.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 对照 Go types.MCPConfigFingerprint（internal/types/mcp_metadata.go:57-68）。
 *
 * <p>摘要排除展示文案与启用状态：改文档不改上游身份。但 <b>秘密影响身份</b>——
 * api_key / token 参与摘要（只存摘要，不存秘密本身）。</p>
 *
 * <p>⚠️ 保真要点：Go 用 {@code json.Marshal} 序列化一个**匿名结构体**，字段序为
 * {@code Transport, URL, Headers, Auth, Stdio, Env}，且该匿名结构体的字段**没有 json tag**，
 * 所以输出的是 Go 字段名本身、并且即使是 nil 也输出 {@code null}（无 omitempty）。
 * 嵌套对象则用各自 tag 的 omitempty 规则。Java 侧手工构造同样的字节串，使
 * <b>摘要与 Go 逐字节一致</b>（同一行 mcp_metadata 跨语言读写时 Stale 判定才不会误报）。</p>
 *
 * <p>同时复刻 Go encoding/json 的 HTML 转义（{@code < > &} → {@code < > &}）
 * 与 U+2028/U+2029 转义。</p>
 */
public final class McpConfigFingerprint {

    private McpConfigFingerprint() {}

    /**
     * 对照 Go MCPConfigFingerprint：SHA-256 十六进制小写。
     *
     * @param service 不可为 null（Go 会 panic 于 nil 解引用；Java 显式返回 null 更安全，
     *                但调用方按 Go 语义总是传非 nil）
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

    /** 暴露规范化 JSON 便于测试对照 Go 实录（Go 的 json.Marshal 输出）。 */
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

    /** 对照 Go MCPAuthConfig 的 json tag + omitempty（含 custom_headers 的键排序） */
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

    /** 对照 Go MCPStdioConfig：command / args **都无 omitempty**，nil args → null */
    private static String stdioConfig(McpStdioConfig c) {
        if (c == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        // Go 的零值是 ""，Java 未赋值时是 null——同一语义（无 omitempty，恒输出）
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

    /** 对照 Go map[string]string 编码：键按字节序排序，nil → null，空 map → {} */
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
     * 对照 Go encoding/json 的字符串编码（HTMLEscape 默认开启）：
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
                        // 未配对代理项：Go 用 U+FFFD 替换
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

    /** 供测试/调试：列出全部键（保留以便对照 Go 输出的键序） */
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
