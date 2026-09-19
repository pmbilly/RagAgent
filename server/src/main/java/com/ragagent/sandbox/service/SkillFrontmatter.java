package com.ragagent.sandbox.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 对照 Go {@code internal/agent/skills} 的 {@code ParseSkillFile}（skill.go L161-228）、
 * {@code Validate}（L82-114）、{@code applyInstallName}（L120-148）与
 * {@code UnmarshalSkillFrontmatter}（skill_frontmatter.go 全文）。
 *
 * <p>按 Claude 的 Progressive Disclosure 约定解析 SKILL.md：YAML frontmatter（---
 * 定界）承载 Level 1 元数据，正文是 Level 2 指令。</p>
 *
 * <h2>frontmatter 的两步保守修复</h2>
 * 第三方 skill 常把 {@code version}/{@code description} 缩进在 {@code name:} 之下，
 * 或在未加引号的标量里留冒号。严格 YAML 对两者都报错。第一次解析失败后按序重试
 * 修复候选（outdent → 引号包冒号 → 两者），有效的 frontmatter 不会被改动；
 * 修复候选成功时 {@link Skill#frontmatterRepaired} 为真（原文：SKILL.md 本体不变，
 * 安装该归档的调用方应告诉用户去修）。
 */
public final class SkillFrontmatter {

    /** 对照 skills.MaxNameLength / MaxDescriptionLength（Claude 规范常量）。 */
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 1024;
    public static final String SKILL_FILE_NAME = "SKILL.md";

    /** 保留词：skill 名不能包含。 */
    private static final String[] RESERVED_WORDS = {"anthropic", "claude"};

    /** 安装身份：单段路径，字母（任意文字）/数字/连字符/下划线。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_-]+$");
    private static final Pattern SKILL_NAME_SEP = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern XML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    /** 解析出的一个 skill（对照 Go 的 skills.Skill 元数据面）。 */
    public static final class Skill {
        public String name = "";
        public String description = "";
        public String slug = "";
        /** SKILL.md 正文（frontmatter 之后）。 */
        public String instructions = "";
        /** YAML 需要修复才能解析时为真；原文件未被改动。 */
        public boolean frontmatterRepaired;

        /** 对照 Validate：Claude 规范校验，错误文案逐字对应 Go。 */
        public void validate() {
            if (name.isEmpty()) {
                throw new SkillFrontmatterException("skill name is required");
            }
            int n = name.codePointCount(0, name.length());
            if (n > MAX_NAME_LENGTH) {
                throw new SkillFrontmatterException(
                        "skill name is " + n + " characters; maximum is " + MAX_NAME_LENGTH);
            }
            if (!NAME_PATTERN.matcher(name).matches()) {
                throw new SkillFrontmatterException(
                        "skill name must contain only letters, numbers, hyphens, and underscores");
            }
            for (String reserved : RESERVED_WORDS) {
                if (name.contains(reserved)) {
                    throw new SkillFrontmatterException(
                            "skill name cannot contain reserved word: " + reserved);
                }
            }
            if (XML_TAG_PATTERN.matcher(name).find()) {
                throw new SkillFrontmatterException("skill name cannot contain XML tags");
            }
            if (description.isEmpty()) {
                throw new SkillFrontmatterException("skill description is required");
            }
            int d = description.codePointCount(0, description.length());
            if (d > MAX_DESCRIPTION_LENGTH) {
                throw new SkillFrontmatterException("skill description is " + d
                        + " characters; maximum is " + MAX_DESCRIPTION_LENGTH);
            }
            if (XML_TAG_PATTERN.matcher(description).find()) {
                throw new SkillFrontmatterException("skill description cannot contain XML tags");
            }
        }

        /** 对照 applyInstallName：目录/工具身份的选取（display title → slug → slugify）。 */
        public void applyInstallName() {
            if (installableSkillName(name)) {
                name = name.trim();
                return;
            }
            if (installableSkillName(slug)) {
                name = slug.trim();
                return;
            }
            String derived = slugifySkillName(name);
            if (installableSkillName(derived)) {
                name = derived;
                return;
            }
            throw new SkillFrontmatterException("skill name must contain only letters, numbers, "
                    + "hyphens, and underscores (or set slug)");
        }
    }

    /** frontmatter 解析失败（错误文案进 "skill bundle is invalid: ..." 的 400）。 */
    public static class SkillFrontmatterException extends RuntimeException {
        public SkillFrontmatterException(String message) {
            super(message);
        }
    }

    private SkillFrontmatter() {
    }

    /**
     * 对照 {@code ParseSkillFile}：解析 SKILL.md 内容，抽取元数据与正文。
     * YAML frontmatter 以 --- 定界。
     */
    public static Skill parseSkillFile(String content) {
        Skill skill = new Skill();
        // 某些编辑器会带 UTF-8 BOM，strings.TrimSpace 去不掉（Go 注释原文）
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        if (!content.trim().startsWith("---")) {
            throw new SkillFrontmatterException("SKILL.md must start with YAML frontmatter (---)");
        }

        List<String> frontmatterLines = new ArrayList<>();
        List<String> bodyLines = new ArrayList<>();
        boolean inFrontmatter = false;
        boolean frontmatterEnded = false;
        for (String line : content.split("\n", -1)) {
            // Go bufio.Scanner 以 \n 与 \r\n 分行，这里统一去掉 \r 尾
            line = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            String trimmed = line.trim();
            if (!inFrontmatter && !frontmatterEnded && trimmed.equals("---")) {
                inFrontmatter = true;
                continue;
            }
            if (inFrontmatter && trimmed.equals("---")) {
                inFrontmatter = false;
                frontmatterEnded = true;
                continue;
            }
            if (inFrontmatter) {
                frontmatterLines.add(line);
            } else if (frontmatterEnded) {
                bodyLines.add(line);
            }
        }
        if (!frontmatterEnded) {
            throw new SkillFrontmatterException("SKILL.md frontmatter is not properly closed with ---");
        }

        String frontmatter = String.join("\n", frontmatterLines);
        skill.frontmatterRepaired = unmarshalSkillFrontmatter(frontmatter, skill);
        skill.applyInstallName();
        skill.instructions = String.join("\n", bodyLines).trim();
        skill.validate();
        return skill;
    }

    /**
     * 对照 {@code UnmarshalSkillFrontmatter}：解码 --- 之间的 YAML。解码先落在
     * 临时值上、成功才拷贝，失败的候选不会写坏 dest；修复候选成功时返回 true。
     */
    static boolean unmarshalSkillFrontmatter(String frontmatter, Skill dest) {
        return frontmatterRepairLoop(frontmatter, src -> decodeInto(src, dest)).repaired();
    }

    /**
     * frontmatter 修复管线的公共循环。Go 侧 parseSkillBundleVersion 也走
     * UnmarshalSkillFrontmatter（带修复的解码器）——版本抽取共用这里。
     * 修复候选（≠原文）成功 = repaired。
     */
    private static RepairResult frontmatterRepairLoop(String frontmatter,
            java.util.function.Consumer<String> decode) {
        Exception firstErr;
        try {
            decode.accept(frontmatter);
            return new RepairResult(false);
        } catch (Exception e) {
            firstErr = e;
        }
        for (String candidate : frontmatterRepairCandidates(frontmatter)) {
            if (candidate.equals(frontmatter)) {
                continue;
            }
            try {
                decode.accept(candidate);
                return new RepairResult(true);
            } catch (Exception ignored) {
                // 试下一个候选
            }
        }
        throw new SkillFrontmatterException("failed to parse YAML frontmatter: "
                + firstErr.getMessage());
    }

    private record RepairResult(boolean repaired) {
    }

    /**
     * 对照 parseSkillBundleVersion 里的解码目标（struct{Version string}）：
     * 同一修复管线，抽取 {@code version} 标量。
     */
    public static String frontmatterVersion(String frontmatter) {
        String[] box = {""};
        frontmatterRepairLoop(frontmatter, src -> {
            Object parsed = yamlLoad(src);
            if (parsed instanceof Map<?, ?> map && map.get("version") instanceof String v) {
                box[0] = v;
            }
        });
        return box[0];
    }

    private static Object yamlLoad(String src) {
        try {
            return new Yaml(new SafeConstructor(new org.yaml.snakeyaml.LoaderOptions())).load(src);
        } catch (YAMLException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** 对照 {@code unmarshalFrontmatterCopy}：snakeyaml 安全构造器 + name/slug/description 抽取。 */
    private static void decodeInto(String src, Skill dest) {
        Object parsed = yamlLoad(src);
        if (parsed == null) {
            // 空 frontmatter（对照 yaml.Unmarshal 的空输入 → 零值，不报错）
            dest.name = dest.name == null ? "" : dest.name;
            return;
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("yaml: unmarshal errors:\n"
                    + "  cannot unmarshal into frontmatter fields");
        }
        String name = stringField(map, "name");
        String slug = stringField(map, "slug");
        String description = stringField(map, "description");
        if (name == null && slug == null && description == null) {
            return; // 没有任何已知键：保持原值（与 Go 解构零字段等价）
        }
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

    private static String stringField(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    /** 对照 {@code frontmatterRepairCandidates}：outdented → quoted → both。 */
    static List<String> frontmatterRepairCandidates(String frontmatter) {
        String outdented = repairAccidentalNestedFrontmatter(frontmatter);
        String quoted = quoteColonInUnquotedScalars(frontmatter);
        String both = quoteColonInUnquotedScalars(outdented);
        return List.of(outdented, quoted, both);
    }

    /**
     * 对照 {@code repairAccidentalNestedFrontmatter}：把缩进在纯标量键下的键 outdent。
     * 真嵌套映射（键行无值，如 {@code compatibility:}）是合法 YAML，此步不动它。
     */
    static String repairAccidentalNestedFrontmatter(String src) {
        String[] lines = src.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            out.add(line);
            i++;
            if (!isPlainScalarMapping(line.trim())) {
                continue;
            }
            int indent = leadingWS(line);
            if (i >= lines.length) {
                break;
            }
            String next = lines[i];
            while (i < lines.length && lines[i].trim().isEmpty()) {
                out.add(lines[i]);
                i++;
                if (i < lines.length) {
                    next = lines[i];
                }
            }
            if (i >= lines.length) {
                break;
            }
            int nextIndent = leadingWS(next);
            if (nextIndent <= indent || !looksLikeYAMLKey(next.trim())) {
                continue;
            }
            int extra = nextIndent - indent;
            while (i < lines.length) {
                String cur = lines[i];
                if (cur.trim().isEmpty()) {
                    out.add(cur);
                    i++;
                    continue;
                }
                int curIndent = leadingWS(cur);
                if (curIndent < nextIndent) {
                    break;
                }
                out.add(stripLeadingWS(cur, extra));
                i++;
            }
        }
        return String.join("\n", out);
    }

    private static final Pattern UNQUOTED_COLON_SCALAR = Pattern.compile(
            "^(\\s*(?:name|description)\\s*:\\s*)([^\"'|>{\\[\\s#].*:.+)$");

    /**
     * 对照 {@code quoteColonInUnquotedScalars}：把含冒号的 name/description 值包上引号，
     * 让 {@code description: Foo: bar} 不被当成嵌套映射。
     */
    static String quoteColonInUnquotedScalars(String src) {
        String[] lines = src.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            var m = UNQUOTED_COLON_SCALAR.matcher(lines[i]);
            if (!m.matches()) {
                continue;
            }
            String value = m.group(2).trim();
            if (value.startsWith("\"") || value.startsWith("'")) {
                continue;
            }
            String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
            lines[i] = m.group(1) + "\"" + escaped + "\"";
        }
        return String.join("\n", lines);
    }

    static boolean isPlainScalarMapping(String trimmed) {
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return false;
        }
        int cut = trimmed.indexOf(':');
        if (cut < 0 || trimmed.substring(0, cut).trim().isEmpty()) {
            return false;
        }
        String value = trimmed.substring(cut + 1).trim();
        if (value.isEmpty() || value.startsWith("#")) {
            return false;
        }
        return !(value.equals("|") || value.equals(">")
                || value.startsWith("|") || value.startsWith(">")
                || value.startsWith("{") || value.startsWith("["));
    }

    static boolean looksLikeYAMLKey(String trimmed) {
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
            return false;
        }
        int cut = trimmed.indexOf(':');
        if (cut < 0) {
            return false;
        }
        String key = trimmed.substring(0, cut).trim();
        if (key.isEmpty()) {
            return false;
        }
        if (key.startsWith("\"") || key.startsWith("'")) {
            return true;
        }
        for (int i = 0; i < key.length(); i++) {
            char r = key.charAt(i);
            if (i == 0 && !Character.isLetter(r) && r != '_') {
                return false;
            }
            if (!Character.isLetter(r) && !Character.isDigit(r) && r != '_' && r != '-' && r != '.') {
                return false;
            }
        }
        return true;
    }

    static int leadingWS(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    static String stripLeadingWS(String s, int n) {
        if (n <= 0) {
            return s;
        }
        int i = 0;
        while (i < s.length() && i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return s.substring(i);
    }

    static boolean installableSkillName(String name) {
        name = name == null ? "" : name.trim();
        return !name.isEmpty() && NAME_PATTERN.matcher(name).matches();
    }

    static String slugifySkillName(String name) {
        String s = SKILL_NAME_SEP.matcher(name.trim().toLowerCase(Locale.ROOT)).replaceAll("-");
        return trimDashes(s);
    }

    private static String trimDashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '-' || s.charAt(start) == '_')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == '-' || s.charAt(end - 1) == '_')) {
            end--;
        }
        return s.substring(start, end);
    }
}
