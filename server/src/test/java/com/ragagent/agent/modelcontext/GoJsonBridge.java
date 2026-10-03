package com.ragagent.agent.modelcontext;

import com.fasterxml.jackson.databind.JsonNode;

/** 测试辅助：把测试里的 JSON 文本解析成树 + Go 风格字符串转义（encoding/json 语义）。 */
final class GoJsonBridge {

    private GoJsonBridge() {
    }

    static JsonNode parseTree(String json) {
        return JsonValues.parse(json);
    }

    /** Go encoding/json 的字符串体转义（HTML 恒开 + 控制字符小写十六进制 + U+2028/29）。 */
    static String goString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<' -> sb.append("\\u003c");
                case '>' -> sb.append("\\u003e");
                case '&' -> sb.append("\\u0026");
                case 0x2028 -> sb.append("\\u2028");
                case 0x2029 -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
