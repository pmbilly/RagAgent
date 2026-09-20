package com.ragagent.agent.tools;

/**
 * sandbox 路径与 shell 约定（对照 Go internal/sandbox 的 skill_paths.go / workspace_path.go /
 * shell_quote.go / session_manager.go 根常量——工具包用到的子集，纯函数逐字移植）。
 *
 * <p>这个类只做<b>规范化与词法判定</b>；真正的特权边界是 sandbox 本身（shell_exec 能摸到
 * 同样的文件），这里的检查是工具作用域约定。真 Manager 实现波 4.6 装配。</p>
 */
public final class SandboxPaths {

    /** 对照 sandbox.SessionWorkspaceRoot。 */
    public static final String SESSION_WORKSPACE_ROOT = "/workspace";
    /** 对照 sandbox.SessionInputRoot。 */
    public static final String SESSION_INPUT_ROOT = "/workspace/input";
    /** 对照 sandbox.SessionOutputRoot。 */
    public static final String SESSION_OUTPUT_ROOT = "/workspace/output";
    /**
     * 已安装 skill 在快照镜像里的位置（对照 sandbox.SkillsImageRoot）。故意在 /workspace 之外：
     * /workspace 是每会话的草稿区，快照前会被清空。
     */
    public static final String SKILLS_IMAGE_ROOT = "/opt/weknora/tenant/skills";
    /** 对照 sandbox.ErrTimeout 的文案（Killed 且无 Error 时写进 result.Error）。 */
    public static final String ERR_TIMEOUT = "execution timed out";

    private SandboxPaths() {
    }

    // ---- Go path 包的子集（GoPath.java 是 4.5a 既有文件不可扩展，这里补本波要用的三个）----

    /** path.IsAbs：以 / 开头。 */
    public static boolean isAbs(String path) {
        return path != null && path.startsWith("/");
    }

    /** path.Base：去尾斜杠后取最后一段；"" → "."；全斜杠 → "/"。 */
    public static String base(String path) {
        if (path == null || path.isEmpty()) {
            return ".";
        }
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        path = path.substring(0, end);
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            path = path.substring(slash + 1);
        }
        return path.isEmpty() ? "/" : path;
    }

    /** path.Join：跳过空元素后 Clean(Join(elem, "/"))；全空 → ""。 */
    public static String join(String... elem) {
        for (String e : elem) {
            if (e != null && !e.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < elem.length; i++) {
                    if (i > 0) {
                        sb.append('/');
                    }
                    sb.append(elem[i] == null ? "" : elem[i]);
                }
                return GoPath.clean(sb.toString());
            }
        }
        return "";
    }

    /**
     * 给文件工具与命令一致的相对路径语义（对照 ResolveWorkspacePath）。只规范化；
     * 读写根仍由调用方强制。
     */
    public static String resolveWorkspacePath(String value) {
        value = value == null ? "" : value.strip();
        if (!value.startsWith("/")) {
            return join(SESSION_WORKSPACE_ROOT, value);
        }
        return GoPath.clean(value);
    }

    /** clean 是否就在 root 上或其下（对照 isUnderRoot；两参必须都已 clean）。 */
    public static boolean isUnderRoot(String clean, String root) {
        if (clean.equals(root)) {
            return true;
        }
        String rootWithSep = root.endsWith("/") ? root : root + "/";
        return clean.startsWith(rootWithSep);
    }

    /** name 是否是 SkillsImageRoot 下单个合法目录段（对照 IsValidSkillName）。 */
    public static boolean isValidSkillName(String name) {
        name = name == null ? "" : name.strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return false;
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            return false;
        }
        return base(GoPath.clean(name)).equals(name);
    }

    /** skill 名 → 镜像目录（对照 SkillDirFor）。非法名返回 null（Go 返回 error）。 */
    public static String skillDirFor(String skillName) {
        if (!isValidSkillName(skillName)) {
            return null;
        }
        return join(SKILLS_IMAGE_ROOT, skillName);
    }

    /**
     * skillDir 是否恰好是 SkillsImageRoot 下一个已安装 skill 的目录
     * （对照 ValidatedImageSkillDir）。合法返回 clean 形态，否则 null。
     */
    public static String validatedImageSkillDir(String skillDir) {
        String clean = GoPath.clean(skillDir == null ? "" : skillDir.strip());
        String expected = skillDirFor(base(clean));
        if (expected == null || !expected.equals(clean)) {
            return null;
        }
        return clean;
    }

    /**
     * p 是否位于 SkillsImageRoot 内（对照 SkillNameFromImagePath）。根本身返回 ("", true)；
     * skill 目录或其中文件返回 (skillName, true)；镜像外返回 null。
     */
    public static ImageSkill skillNameFromImagePath(String p) {
        String clean = GoPath.clean(p == null ? "" : p.strip());
        String root = GoPath.clean(SKILLS_IMAGE_ROOT);
        if (clean.equals(root)) {
            return new ImageSkill("", true);
        }
        String prefix = root + "/";
        if (!clean.startsWith(prefix)) {
            return null;
        }
        String rest = clean.substring(prefix.length());
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        if (!isValidSkillName(name)) {
            return null;
        }
        return new ImageSkill(name, true);
    }

    /** （name, inImage）二元组（Go 多返回值）。 */
    public record ImageSkill(String name, boolean inImage) {
    }

    /**
     * 把 s 渲染成 /bin/sh 的一个字面 word（对照 ShellQuote）。单引号是唯一能让所有
     * 元字符失效的构造；非 ASCII 字节原样通过，CJK 文件名在 sandbox 里仍是同一个文件。
     */
    public static String shellQuote(String s) {
        if (s == null || s.isEmpty()) {
            return "''";
        }
        if (isShellSafe(s)) {
            return s;
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static boolean isShellSafe(String s) {
        for (int i = 0; i < s.length(); i++) {
            char r = s.charAt(i);
            boolean ok = (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9')
                    || r == '-' || r == '_' || r == '/' || r == '.' || r == ',' || r == ':'
                    || r == '=' || r == '+';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
