package com.ragagent.agent.tools;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型构造的 shell 命令里的 NAME=value 提取与掩码（对照 Go {@code shell_env_extract.go}，逐字移植）。
 * 只用于模型构造的命令，绝不用于用户聊天文本。
 */
public final class ShellEnvExtractor {

    /**
     * assignmentPattern 找 NAME=value。名字必须 UPPER_SNAKE_CASE——
     * 这样 --model 之类的旗标和 URL 才不会被当成环境变量。
     * （对照 Go 同名正则；Java 语法 1:1。）
     */
    private static final Pattern ASSIGNMENT_PATTERN = Pattern.compile(
            "(?:^|[;|&\\s])(?:export\\s+)?([A-Z_][A-Z0-9_]{0,127})=(?:\"([^\"]*)\"|'([^']*)'|([^\\s;|&]+))");

    private ShellEnvExtractor() {
    }

    /** 提取命令里的导出赋值（实录：export 与前缀两种形态都收；旗标/URL 不收）。 */
    public static Map<String, String> extractExportedEnv(String command) {
        Map<String, String> out = new HashMap<>();
        Matcher m = ASSIGNMENT_PATTERN.matcher(command);
        while (m.find()) {
            String name = m.group(1);
            String value = orEmpty(m.group(2)) + orEmpty(m.group(3)) + orEmpty(m.group(4));
            if (name.isEmpty() || value.isEmpty()) {
                continue;
            }
            out.put(name, value);
        }
        return out;
    }

    /** 命令里的导出叠加工具环境（工具值覆盖命令值；工具值为空白的不算使用）。 */
    public static Map<String, String> collectUsedSkillEnv(String command, Map<String, String> toolEnv) {
        Map<String, String> out = extractExportedEnv(command);
        for (Map.Entry<String, String> e : toolEnv.entrySet()) {
            if (e.getValue().strip().isEmpty()) {
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * 把每个 NAME=value 赋值的值换成占位符。命令按 Info 记日志，而内联传凭据是给 skill
     * 递钥匙的成文方式——原始串绝不能进日志。
     */
    public static String maskCommandAssignments(String command) {
        Matcher m = ASSIGNMENT_PATTERN.matcher(command);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String match = m.group();
            int eq = match.indexOf('=');
            String replacement = eq < 0 ? match : match.substring(0, eq + 1) + "***";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 测试/调试用的稳定序视图（Go 实录的 map 输出按字母序）。 */
    static Map<String, String> sortedView(Map<String, String> m) {
        return new java.util.TreeMap<>(m);
    }
}
