package com.ragagent.agent.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Agent Skills（对照 Go internal/agent/skills/skill.go，全文移植），遵循 Claude 的
 * Progressive Disclosure 模式：skill 是通过指令文件扩展 agent 能力的模块化能力。
 *
 * <ul>
 *   <li>Level 1（元数据）：Name / Description 恒加载；</li>
 *   <li>Level 2（指令）：SKILL.md 正文，按需加载；</li>
 *   <li>Level 3（资源）：skill 目录里的其余文件，按需加载。</li>
 * </ul>
 */
public final class Skill {

    /** Claude 规范的校验常量（对照 MaxNameLength / MaxDescriptionLength / SkillFileName）。 */
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 1024;
    public static final String SKILL_FILE_NAME = "SKILL.md";

    /** skill 名不能包含的保留词。 */
    private static final String[] RESERVED_WORDS = {"anthropic", "claude"};

    /**
     * 安装身份：单段路径的字母（任意文字）/数字/连字符/下划线。展示标题
     * （"Word / DOCX"）不是它——那些经 slug / slugify 重写后再 Validate。
     * 斜杠留在门外，名字走不进 SkillsImageRoot。
     */
    static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_-]+$");
    static final Pattern SKILL_NAME_SEP = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** 检测内容里的 XML 标签。 */
    private static final Pattern XML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    /** 元数据（Level 1）——恒加载。 */
    public String name = "";
    public String description = "";
    /**
     * 可选的文件系统安全 id。ClawHub / SkillHub 常把展示标题放 name（"Word / DOCX"）、
     * 安装 id 放 slug（"word-docx"）。name 不是合法安装身份时 slug 优先。
     */
    public String slug = "";

    /** 文件系统信息：skill 目录绝对路径。 */
    public String basePath = "";
    /** SKILL.md 绝对路径。 */
    public String filePath = "";

    /** 指令（Level 2）——SKILL.md 正文（frontmatter 之后），按需加载。 */
    public String instructions = "";
    /** Level 2 指令是否已加载。 */
    public boolean loaded;

    /**
     * frontmatter 在 --- 标记之间的 YAML 需要修复（键嵌在标量下、或未加引号的冒号）
     * 才能解析时为 true。原 SKILL.md 不动；安装该归档的调用方应告知用户去修。
     */
    public boolean frontmatterRepaired;

    /** 解析/校验失败（错误文案对照 Go，逐字进上层响应）。 */
    public static final class SkillValidationException extends RuntimeException {
        public SkillValidationException(String message) {
            super(message);
        }
    }

    /** 校验 skill 元数据是否符合 Claude 规范（对照 Validate）。 */
    public void validate() {
        if (name == null || name.isEmpty()) {
            throw new SkillValidationException("skill name is required");
        }
        int n = name.codePointCount(0, name.length());
        if (n > MAX_NAME_LENGTH) {
            throw new SkillValidationException(
                    "skill name is " + n + " characters; maximum is " + MAX_NAME_LENGTH);
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new SkillValidationException(
                    "skill name must contain only letters, numbers, hyphens, and underscores");
        }
        for (String reserved : RESERVED_WORDS) {
            if (name.contains(reserved)) {
                throw new SkillValidationException("skill name cannot contain reserved word: " + reserved);
            }
        }
        if (XML_TAG_PATTERN.matcher(name).find()) {
            throw new SkillValidationException("skill name cannot contain XML tags");
        }
        if (description == null || description.isEmpty()) {
            throw new SkillValidationException("skill description is required");
        }
        int d = description.codePointCount(0, description.length());
        if (d > MAX_DESCRIPTION_LENGTH) {
            throw new SkillValidationException(
                    "skill description is " + d + " characters; maximum is " + MAX_DESCRIPTION_LENGTH);
        }
        if (XML_TAG_PATTERN.matcher(description).find()) {
            throw new SkillValidationException("skill description cannot contain XML tags");
        }
    }

    /**
     * 选定目录 / 工具身份（对照 applyInstallName）。第三方 SKILL.md 常把展示标题放
     * name（"Word / DOCX"）、kebab-case id 放 slug（"word-docx"）。已是合法身份的
     * 标题保持不动，所以 律师助手 这类中文名保持原样。
     */
    void applyInstallName() {
        if (installableSkillName(name)) {
            name = name.strip();
            return;
        }
        if (installableSkillName(slug)) {
            name = slug.strip();
            return;
        }
        String derived = slugifySkillName(name);
        if (installableSkillName(derived)) {
            name = derived;
            return;
        }
        throw new SkillValidationException(
                "skill name must contain only letters, numbers, hyphens, and underscores (or set slug)");
    }

    static boolean installableSkillName(String candidate) {
        candidate = candidate == null ? "" : candidate.strip();
        return !candidate.isEmpty() && NAME_PATTERN.matcher(candidate).matches();
    }

    static String slugifySkillName(String candidate) {
        String s = (candidate == null ? "" : candidate.strip()).toLowerCase(Locale.ROOT);
        s = SKILL_NAME_SEP.matcher(s).replaceAll("-");
        return trimChars(s, "-_");
    }

    /** Go strings.Trim 的等价（按 cutset 字符去首尾）。 */
    private static String trimChars(String s, String cutset) {
        int start = 0;
        int end = s.length();
        while (start < end && cutset.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && cutset.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    /** 轻量元数据表示（对照 ToMetadata / SkillMetadata；Level 1，skill 发现期用）。 */
    public record SkillMetadata(String name, String description, String basePath) {
    }

    public SkillMetadata toMetadata() {
        return new SkillMetadata(name, description, basePath);
    }

    /** skill 目录里额外文件（Level 3；对照 SkillFile）。 */
    public record SkillFile(String name, String path, String content, boolean script) {
    }

    /**
     * 解析 SKILL.md 内容，抽取元数据与正文（对照 ParseSkillFile）。
     * 处理 --- 定界的 YAML frontmatter。
     */
    public static Skill parseSkillFile(String content) {
        Skill skill = new Skill();
        // 某些编辑器带 UTF-8 BOM，strings.TrimSpace 去不掉
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        if (!content.strip().startsWith("---")) {
            throw new SkillValidationException("SKILL.md must start with YAML frontmatter (---)");
        }

        List<String> frontmatterLines = new ArrayList<>();
        List<String> bodyLines = new ArrayList<>();
        boolean inFrontmatter = false;
        boolean frontmatterEnded = false;
        // Go bufio.Scanner（ScanLines）按 \n 分行并去尾部 \r
        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            String trimmed = line.strip();
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
            throw new SkillValidationException("SKILL.md frontmatter is not properly closed with ---");
        }

        String frontmatter = String.join("\n", frontmatterLines);
        try {
            skill.frontmatterRepaired = SkillFrontmatter.unmarshalSkillFrontmatter(frontmatter, skill);
        } catch (RuntimeException e) {
            throw new SkillValidationException("failed to parse YAML frontmatter: " + e.getMessage());
        }
        skill.applyInstallName();
        skill.instructions = String.join("\n", bodyLines).strip();
        skill.loaded = true;
        try {
            skill.validate();
        } catch (SkillValidationException e) {
            // Go: fmt.Errorf("skill validation failed: %w", err)
            throw new SkillValidationException("skill validation failed: " + e.getMessage());
        }
        return skill;
    }

    /** 只解析 SKILL.md 的元数据（Level 1 轻量操作；对照 ParseSkillMetadata）。 */
    public static SkillMetadata parseSkillMetadata(String content) {
        return parseSkillFile(content).toMetadata();
    }

    /**
     * path 是否是首用依赖安装器（scripts/install_deps.py 之类）（对照
     * IsOnDemandInstallerPath）。skill 自带这些把可选包推迟到聊天时装——时机不对：
     * 每个从镜像建的会话都重复装、且沙箱无出口时必挂。
     */
    public static boolean isOnDemandInstallerPath(String scriptPath) {
        String base = basename(scriptPath == null ? "" : scriptPath.strip()).toLowerCase(Locale.ROOT);
        if (base.contains("install_dep")) {
            return true;
        }
        return base.equals("setup_deps.py") || base.equals("bootstrap_deps.py");
    }

    /** path 是否是可执行脚本（对照 IsScript）。 */
    public static boolean isScript(String path) {
        String ext = fileExt(path == null ? "" : path);
        return SCRIPT_EXTENSIONS.contains(ext);
    }

    /** 返回脚本文件的解释器（对照 GetScriptLanguage）。 */
    public static String getScriptLanguage(String path) {
        String ext = fileExt(path == null ? "" : path);
        String lang = SCRIPT_LANGUAGES.get(ext);
        return lang != null ? lang : "unknown";
    }

    /** Go filepath.Ext：最后一段路径里最后一个点起的尾缀（含点）；无点为 ""。 */
    static String fileExt(String path) {
        String base = basename(path);
        int dot = base.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return base.substring(dot);
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static final Set<String> SCRIPT_EXTENSIONS = Set.of(
            ".py", ".sh", ".bash", ".js", ".mjs", ".cjs", ".ts", ".rb", ".pl", ".php");

    private static final Map<String, String> SCRIPT_LANGUAGES = Map.of(
            ".py", "python",
            ".sh", "bash",
            ".bash", "bash",
            ".js", "node",
            ".mjs", "node",
            ".cjs", "node",
            ".ts", "ts-node",
            ".rb", "ruby",
            ".pl", "perl",
            ".php", "php");
}
