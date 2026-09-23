package com.ragagent.sandbox.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话沙箱路径/shell 约定（对照 Go internal/sandbox 的 shell_quote.go、workspace_path.go、
 * skill_paths.go 本管理器用到的子集，纯函数逐字移植）。
 *
 * <p>注意：{@code com.ragagent.agent.tools.SandboxPaths} 是工具侧的同源拷贝（波 4.5c），
 * runtime 包不得反向依赖 agent 包，故此处独立成类；两份实现必须保持逐字一致。
 * 唯一的运行期 Manager 在此包，路径判定的权威实现以后以此为准。</p>
 */
public final class SessionSandboxPaths {

    // ── session_manager.go 的根常量 ─────────────────────────────────────

    /** 用户附件区（file storage 还原），生成产物不得写入。 */
    public static final String SESSION_INPUT_ROOT = "/workspace/input";
    /** skill 脚本写产物供收集的目录（经 skillOutputEnvVar 注入）。 */
    public static final String SESSION_OUTPUT_ROOT = "/workspace/output";
    /** 沙箱内可写工作区根；shell_exec 的 work_dir 必须位于其下。 */
    public static final String SESSION_WORKSPACE_ROOT = "/workspace";
    /** 对照 skillOutputEnvVar（skills manager 的 WEKNORA_SKILL_OUTPUT_DIR）。 */
    public static final String SKILL_OUTPUT_ENV_VAR = "WEKNORA_SKILL_OUTPUT_DIR";
    /** 对照 sessionInputEnvVar（skills manager 的 WEKNORA_SESSION_INPUT_DIR）。 */
    public static final String SESSION_INPUT_ENV_VAR = "WEKNORA_SESSION_INPUT_DIR";
    /**
     * 已安装 skill 在快照镜像里的位置（对照 SkillsImageRoot）。故意在 /workspace 之外：
     * /workspace 是每会话草稿区，快照前清空。
     */
    public static final String SKILLS_IMAGE_ROOT = "/opt/weknora/tenant/skills";

    /** 对照 skillShellArgv0。 */
    public static final String SKILL_SHELL_ARGV0 = "weknora-skill";

    private SessionSandboxPaths() {
    }

    // ---- Go path 包的子集 ----

    /** path.Clean 的等价实现（复用 agent.tools.GoPath 的算法逐字移植）。 */
    public static String clean(String path) {
        if (path == null || path.isEmpty()) {
            return ".";
        }
        boolean rooted = path.startsWith("/");
        int out = rooted ? 1 : 0; // 输出写指针（字符数组索引）
        int dotdot = 0;
        char[] buf = new char[path.length()];
        if (rooted) {
            buf[0] = '/';
        }
        int r = 0;
        while (r < path.length()) {
            if (path.charAt(r) == '/') {
                r++;
            } else if (path.charAt(r) == '.' && (r + 1 == path.length() || path.charAt(r + 1) == '/')) {
                r++;
            } else if (path.charAt(r) == '.' && path.charAt(r + 1) == '.'
                    && (r + 2 == path.length() || path.charAt(r + 2) == '/')) {
                r += 2;
                if (out > dotdot) {
                    out--;
                    while (out > dotdot && buf[out] != '/') {
                        out--;
                    }
                } else if (!rooted) {
                    if (out > 0) {
                        buf[out++] = '/';
                    }
                    buf[out++] = '.';
                    buf[out++] = '.';
                    dotdot = out;
                }
            } else {
                if ((rooted && out != 1) || (!rooted && out != 0)) {
                    buf[out++] = '/';
                }
                while (r < path.length() && path.charAt(r) != '/') {
                    buf[out++] = path.charAt(r);
                    r++;
                }
            }
        }
        if (out == 0) {
            return ".";
        }
        return new String(buf, 0, out);
    }

    /** path.Join。 */
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
                return clean(sb.toString());
            }
        }
        return "";
    }

    /** path.Ext。 */
    public static String ext(String path) {
        int i = path.lastIndexOf('.');
        if (i < 0 || i <= path.lastIndexOf('/')) {
            return "";
        }
        return path.substring(i);
    }

    /** path.Base。 */
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

    /** clean 是否就在 root 上或其下（两参必须都已 clean）。 */
    public static boolean isUnderRoot(String clean, String root) {
        if (clean.equals(root)) {
            return true;
        }
        return clean.startsWith(root + "/");
    }

    // ---- shell_quote.go ----

    /** 把 s 渲染成 /bin/sh 的一个字面 word（对照 ShellQuote）。 */
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

    // ---- workspace_path.go ----

    /** 给文件工具与命令一致的相对路径语义（对照 ResolveWorkspacePath）。 */
    public static String resolveWorkspacePath(String value) {
        value = value == null ? "" : value.strip();
        if (!value.startsWith("/")) {
            return join(SESSION_WORKSPACE_ROOT, value);
        }
        return clean(value);
    }

    // ---- skill_paths.go（本管理器用到的子集） ----

    /** 对照 RunnableWorkspaceScript：/workspace 下的会话可写脚本（排除 input 与根目录）。 */
    public static RunnablePath runnableWorkspaceScript(String scriptPath) {
        String clean = clean(scriptPath == null ? "" : scriptPath.strip());
        if (clean.equals(SESSION_WORKSPACE_ROOT) || clean.equals(SESSION_OUTPUT_ROOT)
                || clean.equals(SESSION_INPUT_ROOT)) {
            return null;
        }
        if (!isUnderRoot(clean, SESSION_WORKSPACE_ROOT)) {
            return null;
        }
        if (clean.startsWith(SESSION_INPUT_ROOT + "/")) {
            return null;
        }
        return new RunnablePath(clean);
    }

    /** （path, ok）二元组。 */
    public record RunnablePath(String path) {
    }

    /** 对照 ValidatedImageSkillDir。 */
    public static String validatedImageSkillDir(String skillDir) {
        String clean = clean(skillDir == null ? "" : skillDir.strip());
        String expected = join(SKILLS_IMAGE_ROOT, base(clean));
        if (!isValidSkillName(base(clean)) || !expected.equals(clean)) {
            return null;
        }
        return clean;
    }

    static boolean isValidSkillName(String name) {
        name = name == null ? "" : name.strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return false;
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            return false;
        }
        return base(clean(name)).equals(name);
    }

    /**
     * 对照 InterpreterSkillDir：remote 脚本该用哪个 skill 目录的解释器。
     * 镜像内脚本永远从路径派生；/workspace 脚本要求显式且合法的 skillDir。
     */
    public static String interpreterSkillDir(String remotePath, String skillDir) {
        String dir = skillDirForImageScript(remotePath);
        if (dir != null) {
            return dir;
        }
        if (runnableWorkspaceScript(remotePath) == null) {
            return null;
        }
        return validatedImageSkillDir(skillDir);
    }

    /** 对照 SkillNameFromImagePath / SkillDirForImageScript 的合并形态。 */
    static String skillDirForImageScript(String scriptPath) {
        String clean = clean(scriptPath == null ? "" : scriptPath.strip());
        String root = clean(SKILLS_IMAGE_ROOT);
        if (!clean.equals(root) && !clean.startsWith(root + "/")) {
            return null;
        }
        String rest = clean.equals(root) ? "" : clean.substring(root.length() + 1);
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        if (!isValidSkillName(name) || name.isEmpty()) {
            return null;
        }
        String dir = join(SKILLS_IMAGE_ROOT, name);
        return clean.equals(dir) ? null : dir;
    }

    /** 对照 SkillVenvPython。 */
    public static String skillVenvPython(String skillDir) {
        return join(skillDir, ".venv", "bin", "python");
    }

    /** 对照 SkillInterpreterCommand：(command, args) 二元组。 */
    public static InterpreterCommand skillInterpreterCommand(String skillDir, String scriptPath) {
        switch (ext(scriptPath).toLowerCase()) {
            case ".py": {
                String venvPython = skillVenvPython(skillDir);
                String script = shellQuote(scriptPath);
                return new InterpreterCommand("/bin/sh", List.of("-c", String.format(
                        "if [ -x %s ]; then exec %s %s \"$@\"; else exec python3 %s \"$@\"; fi",
                        shellQuote(venvPython), shellQuote(venvPython), script, script)),
                        SKILL_SHELL_ARGV0);
            }
            case ".js", ".mjs", ".cjs":
                return new InterpreterCommand("node", List.of(scriptPath), null);
            case ".sh": {
                String script = shellQuote(scriptPath);
                return new InterpreterCommand("/bin/sh", List.of("-c", String.format(
                        "if command -v bash >/dev/null 2>&1; then exec bash %s \"$@\"; else exec sh %s \"$@\"; fi",
                        script, script)), SKILL_SHELL_ARGV0);
            }
            default:
                return new InterpreterCommand("/bin/sh", List.of(scriptPath), null);
        }
    }

    /** （command, args, argv0）三元组；argv0 仅 shell -c 形态携带。 */
    public record InterpreterCommand(String command, List<String> args, String argv0) {
        public InterpreterCommand {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }

    // ---- session_manager.go 的词法校验族 ----

    /** 对照 cleanSessionInputPath。 */
    public static String cleanSessionInputPath(String filePath) {
        String clean = clean(filePath == null ? "" : filePath.strip());
        if (clean.equals(SESSION_INPUT_ROOT) || clean.startsWith(SESSION_INPUT_ROOT + "/")) {
            return clean;
        }
        throw SandboxException.internal(String.format(
                "sandbox: session input path %s is outside %s",
                quoteGo(filePath), SESSION_INPUT_ROOT));
    }

    /**
     * 对照 cleanSessionWorkspaceWritePath：模型 authored 写入必须落在 /workspace 内
     * 且避开附件树。校验是词法的（Clean + 前缀），与 cleanSessionWorkDir 一致。
     */
    public static String cleanSessionWorkspaceWritePath(String filePath) {
        String clean = resolveWorkspacePath(filePath);
        if (!clean.startsWith("/") || clean.equals(".") || clean.equals("/")) {
            throw SandboxException.internal(String.format(
                    "sandbox: workspace write path %s must be an absolute file path",
                    quoteGo(filePath)));
        }
        if (clean.equals(SESSION_WORKSPACE_ROOT) || clean.equals(SESSION_OUTPUT_ROOT)
                || clean.equals(SESSION_INPUT_ROOT)) {
            throw SandboxException.internal(String.format(
                    "sandbox: workspace write path %s is a directory, not a file",
                    quoteGo(filePath)));
        }
        if (!clean.startsWith(SESSION_WORKSPACE_ROOT + "/")) {
            throw SandboxException.internal(String.format(
                    "sandbox: workspace write path %s is outside %s",
                    quoteGo(filePath), SESSION_WORKSPACE_ROOT));
        }
        if (clean.startsWith(SESSION_INPUT_ROOT + "/")) {
            throw SandboxException.internal(String.format(
                    "sandbox: session input %s is read-only", SESSION_INPUT_ROOT));
        }
        return clean;
    }

    /**
     * 对照 cleanSessionWorkDir：普通会话只允许 /workspace；allowSkillsRoot 把
     * skills 镜像根加入白名单（安装/维护会话用）。词法校验，symlink 有意不查——
     * 真正的隔离边界是远程沙箱本身。
     */
    public static String cleanSessionWorkDir(String workDir, boolean allowSkillsRoot) {
        String clean = clean(workDir == null ? "" : workDir.strip());
        if (clean.equals(SESSION_WORKSPACE_ROOT) || clean.startsWith(SESSION_WORKSPACE_ROOT + "/")) {
            return clean;
        }
        if (allowSkillsRoot
                && (clean.equals(SKILLS_IMAGE_ROOT) || clean.startsWith(SKILLS_IMAGE_ROOT + "/"))) {
            return clean;
        }
        String allowed = SESSION_WORKSPACE_ROOT;
        if (allowSkillsRoot) {
            allowed = SESSION_WORKSPACE_ROOT + ", " + SKILLS_IMAGE_ROOT;
        }
        throw SandboxException.internal(String.format(
                "sandbox: work dir %s is outside allowed roots (%s)",
                quoteGo(workDir), allowed));
    }

    /** 对照 ValidatedSessionOutputDir（走 cleanSessionWorkDir 的单一闸门）。 */
    public static String validatedSessionOutputDir(String dir) {
        try {
            return cleanSessionWorkDir(dir, false);
        } catch (SandboxException e) {
            return null;
        }
    }

    /** 对照 workspaceBootstrapCommand：以执行身份物化目录（symlink 拒绝 + mkdir -p + 可写检查）。 */
    public static String workspaceBootstrapCommand(String... dirs) {
        List<String> quoted = new ArrayList<>(dirs.length);
        for (String dir : dirs) {
            quoted.add(shellQuote(dir));
        }
        return String.format(
                "set -e; for d in %s; do "
                        + "if [ -L \"$d\" ]; then echo \"workspace directory is a symlink: $d\" >&2; exit 1; fi; "
                        + "mkdir -p -- \"$d\"; "
                        + "if [ ! -d \"$d\" ] || [ ! -w \"$d\" ] || [ ! -x \"$d\" ]; then "
                        + "echo \"workspace directory is not writable/searchable: $d\" >&2; exit 1; fi; done",
                String.join(" ", quoted));
    }

    /**
     * Go 的 %q 引号近似（路径错误文案用；ASCII 足够，非 ASCII 直出）。
     * 文案里含 CJK/引号的路径按 Go 的 strconv.Quote 规则加双引号并转义反斜杠与双引号。
     */
    static String quoteGo(String s) {
        if (s == null) {
            return "<null>";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
}
