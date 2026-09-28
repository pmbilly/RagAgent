package com.ragagent.agent.tools;

/**
 * Go %q 的普通串形态（registry/MCP 侧文案需要）。原随沙箱工具族，MCP 三处
 * quoteGo 委托它，落位为共享工具件。
 */
public final class GoQuoting {

    private GoQuoting() {
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
