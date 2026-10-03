package com.ragagent.agent.tools;

/**
 * 双引号字符串形态（registry/MCP 侧文案需要）：仅转义 {@code " \ \n \r \t}，
 * 其余控制字符作 U+00XX 形态的转义，非 ASCII 原样保留。
 */
public final class JsonQuoting {

    private JsonQuoting() {
    }

    public static String quoteGo(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
