package com.ragagent.knowledge.service;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * chat 解析引擎的规则解析。
 * <p>两份规则来源语义一致：agent 配置（上传时回落）与租户配置（parse 时最终回落）。
 * {@code TrimSpace(engine)}；都不命中或无规则 → 类型默认（仅 ppt/pptx → markitdown，
 * Java 侧不设该钩子。</p>
 */
public final class ParserEngineRules {

    private ParserEngineRules() {
    }

    /** TrimSpace + 小写 + 去前导点。 */
    public static String normalize(String fileType) {
        String s = fileType == null ? "" : trimSpace(fileType).toLowerCase(Locale.ROOT);
        return s.startsWith(".") ? s.substring(1) : s;
    }

    /** defaultParserEngineByType 仅 ppt/pptx → markitdown。 */
    public static String defaultEngine(String fileType) {
        String ft = normalize(fileType);
        return "ppt".equals(ft) || "pptx".equals(ft) ? "markitdown" : "";
    }

    /** 规则数组形态（agent config 的 chat_parser_engine_rules）。 */
    public static String resolve(JsonNode rules, String fileType) {
        if (rules != null && rules.isArray()) {
            String normalized = normalize(fileType);
            for (JsonNode rule : rules) {
                if (rule == null || !rule.isObject()) {
                    continue;
                }
                String engine = trimSpace(rule.path("engine").asText(""));
                JsonNode fileTypes = rule.get("file_types");
                if (fileTypes == null || !fileTypes.isArray()) {
                    continue;
                }
                for (JsonNode candidate : fileTypes) {
                    if (normalize(candidate.asText("")).equals(normalized)) {
                        return engine;
                    }
                }
            }
        }
        return defaultEngine(fileType);
    }

    static String trimSpace(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && isGoSpace(s.codePointAt(start))) {
            start += Character.charCount(s.codePointAt(start));
        }
        while (end > start && isGoSpace(s.codePointBefore(end))) {
            end -= Character.charCount(s.codePointBefore(end));
        }
        return s.substring(start, end);
    }

    private static boolean isGoSpace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r'
                || c == 0x85 || c == 0xA0
                || Character.isSpaceChar(c)
                || Character.getType(c) == Character.SPACE_SEPARATOR
                || Character.getType(c) == Character.LINE_SEPARATOR
                || Character.getType(c) == Character.PARAGRAPH_SEPARATOR;
    }
}
