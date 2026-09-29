package com.ragagent.agent.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * SKILL.md 的 --- 标记之间 YAML 的解码。
 *
 * <p>第三方 skill（ClawHub / SkillHub）常把 {@code version} / {@code description}
 * 缩进在 {@code name:} 之下，或在未加引号的标量里留冒号。严格 YAML 对两者都报
 * "mapping values are not allowed in this context"。第一次解析失败后按序重试两个
 * 保守修复，让这些归档仍可安装；合法 frontmatter 不变。解码总是先落在临时值上、
 * 成功才拷入 dest，失败的候选不会写坏 dest。修复候选成功时 repaired 为 true。</p>
 *
 * <p>实现说明：用 snakeyaml 安全构造器 + 显式 name/slug/description 抽取
 * （与 {@code sandbox.service.SkillFrontmatter} 保持一致）；已知键出现非字符串
 * 标量时显式报错。</p>
 */
final class SkillFrontmatter {

    private SkillFrontmatter() {
    }

    /** 解码 frontmatter；修复候选成功时返回 true，全部失败抛出首次错误。 */
    static boolean unmarshalSkillFrontmatter(String frontmatter, Skill dest) {
        RuntimeException firstErr;
        try {
            unmarshalFrontmatterCopy(frontmatter, dest);
            return false;
        } catch (RuntimeException e) {
            firstErr = e;
        }
        for (String candidate : frontmatterRepairCandidates(frontmatter)) {
            if (candidate.equals(frontmatter)) {
                continue;
            }
            try {
                unmarshalFrontmatterCopy(candidate, dest);
                return true;
            } catch (RuntimeException ignored) {
                // 试下一个候选
            }
        }
        throw firstErr;
    }

    private static void unmarshalFrontmatterCopy(String src, Skill dest) {
        Object parsed = yamlLoad(src);
        if (parsed == null) {
            // 空 frontmatter：yaml.Unmarshal 空输入 → 零值、不报错
            return;
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("yaml: unmarshal errors:\n  cannot unmarshal into frontmatter fields");
        }
        String name = stringField(map, "name");
        String slug = stringField(map, "slug");
        String description = stringField(map, "description");
        if (name != null) {
            dest.name = name;
        }
        if (slug != null) {
            dest.slug = slug;
        }
        if (description != null) {
            dest.description = description;
        }
    }

    /** 已知键取字符串标量；键存在但非字符串（嵌套 map/序列/数字）→ 类型错误。 */
    private static String stringField(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("yaml: unmarshal errors:\n  cannot unmarshal into string field " + key);
    }

    private static Object yamlLoad(String src) {
        try {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(src);
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    static List<String> frontmatterRepairCandidates(String frontmatter) {
        String outdented = repairAccidentalNestedFrontmatter(frontmatter);
        String quoted = quoteColonInUnquotedScalars(frontmatter);
        String both = quoteColonInUnquotedScalars(outdented);
        return List.of(outdented, quoted, both);
    }

    /**
     * 把缩进在纯标量下的键 outdent，如
     * {@code name: 命理大师} 下一行缩进的 {@code version: 1.2.6}。真嵌套映射
     * （键行无值，如 {@code compatibility:}）是合法 YAML，此步不动它。
     */
    static String repairAccidentalNestedFrontmatter(String src) {
        String[] lines = src.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            out.add(line);
            i++;
            if (!isPlainScalarMapping(line.strip())) {
                continue;
            }
            int indent = leadingWs(line);
            if (i >= lines.length) {
                break;
            }
            String next = lines[i];
            while (i < lines.length && lines[i].strip().isEmpty()) {
                out.add(lines[i]);
                i++;
                if (i < lines.length) {
                    next = lines[i];
                }
            }
            if (i >= lines.length) {
                break;
            }
            int nextIndent = leadingWs(next);
            if (nextIndent <= indent || !looksLikeYamlKey(next.strip())) {
                continue;
            }
            int extra = nextIndent - indent;
            while (i < lines.length) {
                String cur = lines[i];
                if (cur.strip().isEmpty()) {
                    out.add(cur);
                    i++;
                    continue;
                }
                int curIndent = leadingWs(cur);
                if (curIndent < nextIndent) {
                    break;
                }
                out.add(stripLeadingWs(cur, extra));
                i++;
            }
        }
        return String.join("\n", out);
    }

    private static final Pattern UNQUOTED_COLON_SCALAR = Pattern.compile(
            "^(\\s*(?:name|description)\\s*:\\s*)([^\"'|>{\\[\\s#].*:.+)$");

    /** 把含冒号的 name/description 值包上引号，避免 {@code description: Foo: bar} 被解析成嵌套映射。 */
    static String quoteColonInUnquotedScalars(String src) {
        String[] lines = src.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            java.util.regex.Matcher m = UNQUOTED_COLON_SCALAR.matcher(lines[i]);
            if (!m.matches()) {
                continue;
            }
            String value = m.group(2).strip();
            if (value.startsWith("\"") || value.startsWith("'")) {
                continue;
            }
            String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
            lines[i] = m.group(1) + "\"" + escaped + "\"";
        }
        return String.join("\n", lines);
    }

    private static boolean isPlainScalarMapping(String trimmed) {
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return false;
        }
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String key = trimmed.substring(0, colon);
        String value = trimmed.substring(colon + 1).strip();
        if (key.strip().isEmpty() || value.isEmpty() || value.startsWith("#")) {
            return false;
        }
        if (value.equals("|") || value.equals(">")
                || value.startsWith("|") || value.startsWith(">")
                || value.startsWith("{") || value.startsWith("[")) {
            return false;
        }
        return true;
    }

    private static boolean looksLikeYamlKey(String trimmed) {
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
            return false;
        }
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String key = trimmed.substring(0, colon).strip();
        if (key.isEmpty()) {
            return false;
        }
        if (key.startsWith("\"") || key.startsWith("'")) {
            return true;
        }
        for (int i = 0; i < key.length(); i++) {
            int cp = key.codePointAt(i);
            if (i == 0 && !Character.isLetter(cp) && cp != '_') {
                return false;
            }
            if (!Character.isLetter(cp) && !Character.isDigit(cp) && cp != '_' && cp != '-' && cp != '.') {
                return false;
            }
        }
        return true;
    }

    /** 行首空白字符数。 */
    private static int leadingWs(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static String stripLeadingWs(String s, int n) {
        if (n <= 0) {
            return s;
        }
        int i = 0;
        while (i < s.length() && i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return s.substring(i);
    }
}
