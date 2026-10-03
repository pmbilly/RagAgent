package com.ragagent.agent.modelcontext;

/**
 * HTML 实体转义/反转义两个函数（本包引用的全部面）。
 *
 * <p>EscapeString 只转义五个字符（{@code " ' & < >} → {@code &#34; &#39; &amp; &lt; &gt;}）。</p>
 *
 * <p>完整的 HTML5 实体表很大（含不带分号的 HTML5 形态）。
 * 本包的消费面只有「模型写出的标签属性值」与「MCP 结果里扫出的 URL」，实际
 * 出现的是五个基本实体与数字引用；这里覆盖它们加常用命名实体，更冷僻的
 * HTML5 命名实体按原样保留（已知差异）。</p>
 */
final class HtmlEntities {

    private HtmlEntities() {
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("&#34;");
                case '\'' -> sb.append("&#39;");
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semicolon = s.indexOf(';', i + 1);
            int maxLen = Math.min(semicolon < 0 ? n : semicolon + 1, i + 32);
            String candidate = s.substring(i, Math.min(maxLen, n));
            String decoded = decodeEntity(candidate);
            if (decoded != null) {
                sb.append(decoded);
                i += candidate.length();
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String decodeEntity(String entity) {
        if (entity.length() < 3 || !entity.endsWith(";")) {
            return null;
        }
        String body = entity.substring(1, entity.length() - 1);
        if (body.startsWith("#x") || body.startsWith("#X")) {
            try {
                int cp = Integer.parseInt(body.substring(2), 16);
                return validCodePoint(cp);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (body.startsWith("#")) {
            try {
                int cp = Integer.parseInt(body.substring(1));
                return validCodePoint(cp);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return switch (body) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "nbsp" -> "\u00a0";
            default -> null;
        };
    }

    private static String validCodePoint(int cp) {
        if (cp < 0 || (cp >= 0xD800 && cp <= 0xDFFF) || cp > 0x10FFFF) {
            return "\uFFFD";
        }
        return new String(Character.toChars(cp));
    }
}
