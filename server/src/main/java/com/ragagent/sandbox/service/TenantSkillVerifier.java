package com.ragagent.sandbox.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agent.skills.SkillEnvResolver;
import com.ragagent.agent.tools.GoPath;
import com.ragagent.agent.tools.SandboxExecuteResult;
import com.ragagent.agent.tools.SandboxInstallCommandExecutor;
import com.ragagent.agent.tools.SandboxPaths;
import com.ragagent.agent.tools.ShellExecOptions;
import com.ragagent.sandbox.service.SkillBundleParser.SkillBundle;

/**
 * 安装的最后一道门：server 自己的校验（对照 Go internal/application/service/
 * tenant_skill_verify.go 全文 + tenant_skill_runtime_verify.go 全文 +
 * install.go 的 describeExecFailure，波 5 W5β 逐行翻译）。
 *
 * <p>只检查文件能定夺的事，从不检查运行时才能决定的事：bundle 点名的文件都在、
 * 安装器被要求创建的隔离依赖树存在、每个源文件能被将要运行它的解释器解析、
 * manifest 点名的每个发行版已安装。import 解析刻意缺席（见校验器文件头）。
 * findings 分级而非一律致命：只拒绝"安装本身坏了"的证据。</p>
 *
 * <p><b>与 Go 的形状差异（诚实声明）</b>：Go 的这些方法挂在 *TenantSkillService 上、
 * 从 sandbox.Manager 取能力（installExecutor 的能力断言 + SessionFileReader 的类型
 * 断言）；Java 侧会话沙箱 Manager 尚未翻译（provider 执行体，随管线批），执行面与
 * 读文件面以 {@link SandboxInstallCommandExecutor} / {@link SessionFileReader} 两个
 * seam 显式传入，Go 的"能力断言失败"收敛为 null 检查（文案逐字保留）。全部逻辑
 * 无状态，故为静态函数族；管线体落地后由 TenantSkillService 调用。</p>
 *
 * <p>命令构造是字节契约（发给沙箱的 shell 串）：单测钉 Go 实录
 * （overlay 探针 2026-09-21，含 python 校验器的 base64 全文）。</p>
 */
final class TenantSkillVerifier {

    private TenantSkillVerifier() {
    }

    /**
     * 校验门需要的唯一读能力（对照 sandbox.SessionFileReader，capabilities.go
     * L108-111 的窄能力接口原文）。会话 Manager 落地后由其适配实现；测试用内存 fake。
     */
    interface SessionFileReader {
        byte[] readSessionFile(String sessionId, String filePath) throws Exception;
    }

    /** 对照 {@code skillVerifyRepairableExit}：每个 finding 都是"本镜像缺依赖"时的退出码。 */
    static final int SKILL_VERIFY_REPAIRABLE_EXIT = 2;
    /** 对照 {@code skillTreeVerifyDirExit}：skill 目录本身没了；exit 1 是"每条 finding 一行 stderr"，exit 0 是全在。 */
    static final int SKILL_TREE_VERIFY_DIR_EXIT = 3;
    /** 对照 {@code skillVerifyNotePrefix}：报告但不拒绝的行（走 stdout，非零退出保持无歧义）。 */
    static final String SKILL_VERIFY_NOTE_PREFIX = "note: ";
    /** 对照 {@code skillVerifyOptionalFlag}：入口文件与 auxiliary 文件的 argv 分隔（校验器自己的契约）。 */
    static final String SKILL_VERIFY_OPTIONAL_FLAG = "--optional";

    /** 对照 installCommandTimeout（tenant_skill_install.go L32）。 */
    static final Duration INSTALL_COMMAND_TIMEOUT = Duration.ofMinutes(10);

    /** 对照 {@code allScriptExtensions}：运行时知道怎么执行的每个后缀。 */
    private static final List<String> ALL_SCRIPT_EXTENSIONS =
            List.of(".py", ".js", ".mjs", ".cjs", ".sh");

    /** 对照 {@code auxiliaryScriptDirs}：测试/示例/文档的生态通用目录名。 */
    private static final Set<String> AUXILIARY_SCRIPT_DIRS = Set.of(
            "test", "tests", "testing", "__tests__",
            "example", "examples", "sample", "samples",
            "benchmark", "benchmarks", "fixtures",
            "doc", "docs");

    /** 对照 {@code skillRuntimeCommandName}。 */
    private static final Pattern SKILL_RUNTIME_COMMAND_NAME =
            Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.+-]*$");

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 对照 go:embed 的 {@code skillPythonVerifier}；资源与 Go 文件逐字节相同（cmp 钉住）。 */
    private static final byte[] PYTHON_VERIFIER = loadPythonVerifier();

    private static byte[] loadPythonVerifier() {
        try (var in = TenantSkillVerifier.class.getResourceAsStream(
                "/sandbox/tenant_skill_verify.py")) {
            if (in == null) {
                throw new IllegalStateException(
                        "sandbox/tenant_skill_verify.py resource is missing");
            }
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("cannot load skill python verifier", e);
        }
    }

    // ── 入口（verifySkill 编排，Go L84-98）────────────────────────────────

    /**
     * install 管线的最后一道门：坏安装必须让上一个快照继续服役。
     * 树 → 声明的依赖树 → 逐语言 parse → 运行时前提。
     * notes 从 parse pass 累积；失败的 pass 也读它的 notes。
     */
    record VerifyResult(List<String> notes, Exception error) {
    }

    static VerifyResult verifySkill(SandboxInstallCommandExecutor executor,
            SessionFileReader reader, String sessionID, String skillDir, SkillBundle bundle) {
        Exception err = verifySkillTree(executor, sessionID, skillDir, bundle);
        if (err != null) {
            return new VerifyResult(List.of(), err);
        }
        err = verifyDeclaredDependencies(executor, sessionID, skillDir, bundle);
        if (err != null) {
            return new VerifyResult(List.of(), err);
        }
        ScriptsParseOutcome parse = verifyScriptsParse(executor, sessionID, skillDir, bundle);
        if (parse.error() != null) {
            return new VerifyResult(parse.notes(), parse.error());
        }
        return new VerifyResult(parse.notes(),
                verifyRuntimePrerequisites(executor, reader, sessionID, skillDir));
    }

    /**
     * 对照 {@code verifySkillTree}：agent 拿到的文件仍是镜像携带的文件（agent 在此目录
     * 有 root shell，"写于 agent 运行前"不是它存活的证据）。整棵树一次命令一轮往返，
     * 缺文件逐行报告不中断。
     */
    static Exception verifySkillTree(SandboxInstallCommandExecutor executor,
            String sessionID, String skillDir, SkillBundle bundle) {
        InstallExec res = execInstall(executor, sessionID,
                skillTreeVerifyCommand(skillDir, sortedScriptPaths(bundle, ALL_SCRIPT_EXTENSIONS)));
        if (res.failure() == null) {
            return null;
        }
        if (res.result() != null) {
            if (res.result().exitCode() == SKILL_TREE_VERIFY_DIR_EXIT) {
                // 目录本身没了；里面任何文件都探不到，命令已经把这句话说清楚了。
                return new RuntimeException("skill directory is incomplete after install");
            }
            if (res.result().exitCode() == 1) {
                // 命令自己的协议：exit 1 = 每条 finding 一行 stderr，行就是最终消息。
                List<String> lines = verificationProblems(res.result().stderr());
                if (!lines.isEmpty()) {
                    return new RuntimeException(String.join("; ", lines));
                }
            }
        }
        return new RuntimeException("skill tree verification failed: " + res.failure());
    }

    /**
     * 对照 {@code verifyDeclaredDependencies}：安装器被要求创建的隔离树真实存在。
     * 种子源文件活下来不是 pip/npm 跑过的证据——那些文件是 agent 之前 server 写的。
     */
    static Exception verifyDeclaredDependencies(SandboxInstallCommandExecutor executor,
            String sessionID, String skillDir, SkillBundle bundle) {
        if (bundleHasPythonDeps(bundle)) {
            String venvPython = SandboxPaths.join(skillDir, ".venv", "bin", "python");
            InstallExec res = execInstall(executor, sessionID,
                    "test -x " + SandboxPaths.shellQuote(venvPython));
            if (res.failure() != null) {
                return new RuntimeException("python dependencies were not installed into "
                        + skillDir + "/.venv: " + res.failure());
            }
        }
        if (bundleHasNodeDeps(bundle)) {
            String nodeModules = SandboxPaths.join(skillDir, "node_modules");
            InstallExec res = execInstall(executor, sessionID,
                    "test -d " + SandboxPaths.shellQuote(nodeModules));
            if (res.failure() != null) {
                return new RuntimeException("node dependencies were not installed into "
                        + skillDir + "/node_modules: " + res.failure());
            }
        }
        return null;
    }

    record ScriptsParseOutcome(List<String> notes, Exception error) {
    }

    /**
     * 对照 {@code verifyScriptsParse}：bundle 里出现的每种语言各跑一遍，每遍覆盖该语言
     * 的每个文件（parse 是文件的属性，不是猜出来的入口点的属性）。三个 pass 都是
     * parse-only，从不执行 skill 的代码。只有 python pass 带入口/辅助拆分。
     */
    static ScriptsParseOutcome verifyScriptsParse(SandboxInstallCommandExecutor executor,
            String sessionID, String skillDir, SkillBundle bundle) {
        List<String> notes = new ArrayList<>();
        List<String> py = sortedScriptPaths(bundle, ".py");
        if (!py.isEmpty()) {
            ScriptSplit split = splitAuxiliaryScripts(py);
            VerifyOutcome o = execVerify(executor, sessionID, skillDir, "python",
                    skillPythonVerifyCommand(skillDir, split.entry(), split.auxiliary()));
            notes.addAll(o.notes());
            if (o.error() != null) {
                return new ScriptsParseOutcome(notes, o.error());
            }
        }
        List<String> js = sortedScriptPaths(bundle, ".js", ".mjs", ".cjs");
        if (!js.isEmpty()) {
            VerifyOutcome o = execVerify(executor, sessionID, skillDir, "node",
                    skillNodeVerifyCommand(skillDir, js, nodeDependencyNames(bundle)));
            notes.addAll(o.notes());
            if (o.error() != null) {
                return new ScriptsParseOutcome(notes, o.error());
            }
        }
        List<String> sh = sortedScriptPaths(bundle, ".sh");
        if (!sh.isEmpty()) {
            VerifyOutcome o = execVerify(executor, sessionID, skillDir, "shell",
                    skillShellVerifyCommand(skillDir, sh));
            notes.addAll(o.notes());
            if (o.error() != null) {
                return new ScriptsParseOutcome(notes, o.error());
            }
        }
        return new ScriptsParseOutcome(notes, null);
    }

    /**
     * 对照 {@code execVerify}：走普通执行路径与 workspace 引导，工作目录与 skill 环境
     * 与会话调用相同。失败 pass 的 notes 也读（因缺包被拒的安装可能同时注意到了
     * 某个它没有据此拒绝的文件）。
     */
    record VerifyOutcome(List<String> notes, Exception error) {
    }

    static VerifyOutcome execVerify(SandboxInstallCommandExecutor executor,
            String sessionID, String skillDir, String label, String command) {
        if (executor == null) {
            // Go installExecutor 的能力断言失败原文（无 label 包装）。
            return new VerifyOutcome(List.of(),
                    new IllegalStateException("sandbox backend does not support install-mode shell"));
        }
        Map<String, String> env = Map.of(
                SkillEnvResolver.SKILL_DIR_ENV_VAR, skillDir,
                SkillEnvResolver.ARTIFACT_OUTPUT_ENV_VAR, SandboxPaths.SESSION_OUTPUT_ROOT);
        SandboxExecuteResult res;
        try {
            res = executor.execShellCommandWithOptions(sessionID, command,
                    ShellExecOptions.of(SandboxPaths.SESSION_WORKSPACE_ROOT,
                            INSTALL_COMMAND_TIMEOUT, env, null));
        } catch (Exception e) {
            return new VerifyOutcome(List.of(), new RuntimeException(
                    label + " verification: " + failureMessage(e), e));
        }
        List<String> notes = verificationNotes(res.stdout());
        if (res.exitCode() != 0) {
            return new VerifyOutcome(notes, new SkillVerificationException(
                    label,
                    res.exitCode() == SKILL_VERIFY_REPAIRABLE_EXIT,
                    verificationProblems(res.stderr()),
                    describeExecFailure(res)));
        }
        return new VerifyOutcome(notes, null);
    }

    /**
     * 对照 {@code verifyRuntimePrerequisites}（tenant_skill_runtime_verify.go 全文）：
     * agent 发现前提，这道门独立核查可执行文件的存在性并拒绝明确未解决的环境问题。
     */
    static Exception verifyRuntimePrerequisites(SandboxInstallCommandExecutor executor,
            SessionFileReader reader, String sessionID, String skillDir) {
        if (reader == null) {
            // Go 的 mgr.(sandbox.SessionFileReader) 断言失败原文。
            return new RuntimeException("sandbox backend cannot read the install report");
        }
        byte[] raw;
        try {
            raw = reader.readSessionFile(sessionID,
                    SandboxPaths.join(skillDir, ".weknora", "install-report.json"));
        } catch (Exception e) {
            return runtimeGate("Write .weknora/install-report.json after assessing CLI and "
                    + "external runtime prerequisites: " + failureMessage(e));
        }
        if (raw.length > 64 * 1024) {
            return runtimeGate("install-report.json exceeds 64 KiB");
        }
        RuntimeReport report;
        try {
            report = JSON.readValue(raw, RuntimeReport.class);
        } catch (Exception e) {
            report = null;
        }
        if (report == null || report.commands == null || report.blockers == null) {
            return runtimeGate("Write a valid .weknora/install-report.json with commands and "
                    + "blockers arrays of strings");
        }
        if (report.commands.size() > 100 || report.blockers.size() > 100) {
            return runtimeGate("install report has too many entries");
        }
        for (String name : report.commands) {
            if (!SKILL_RUNTIME_COMMAND_NAME.matcher(name).matches() || name.length() > 128) {
                return runtimeGate(
                        "commands must contain bare executable names, without paths or arguments");
            }
        }
        for (String blocker : report.blockers) {
            if (blocker.strip().isEmpty()) {
                return runtimeGate("blockers must contain non-empty explanations");
            }
        }
        if (!report.blockers.isEmpty()) {
            return new SkillVerificationException("runtime prerequisites", false,
                    List.of("Unresolved runtime prerequisites: " + String.join("; ", report.blockers)),
                    "");
        }
        if (report.commands.isEmpty()) {
            return null;
        }
        StringBuilder command = new StringBuilder();
        command.append("export PATH=")
                .append(SandboxPaths.shellQuote(SandboxPaths.skillCommandPath(skillDir)))
                .append(":\"$PATH\"; status=0");
        for (String name : report.commands) {
            command.append("; command -v ").append(SandboxPaths.shellQuote(name))
                    .append(" >/dev/null 2>&1 || { echo ")
                    .append(SandboxPaths.shellQuote("required runtime command is missing: " + name))
                    .append(" >&2; status=2; }");
        }
        command.append("; exit $status");
        return execVerify(executor, sessionID, skillDir, "runtime commands",
                command.toString()).error();
    }

    /** report 必填两数组（Go {@code skillRuntimeReport}）；缺任一（或 null）都是无效报告。 */
    static final class RuntimeReport {
        public List<String> commands;
        public List<String> blockers;
    }

    private static SkillVerificationException runtimeGate(String problem) {
        return new SkillVerificationException("runtime prerequisites", true, List.of(problem), "");
    }

    /** 对照 installExecutor：每个安装命令都走的唯一执行器；null = 无该能力（原文报错）。 */
    static String installExecutorUnavailable() {
        return "sandbox backend does not support install-mode shell";
    }

    /**
     * 对照 {@code execInstall}（install.go L1136-1153）：以 root 跑一条命令、允许 skills root。
     * Java 侧 root/allowlist 由 executor 实现自证（ShellExecOptions 的两个旗标属于会话
     * Manager，本批未到）。退出码非零 → (result, "command failed (...)")，与 Go 同形。
     */
    record InstallExec(SandboxExecuteResult result, String failure) {
    }

    static InstallExec execInstall(SandboxInstallCommandExecutor executor,
            String sessionID, String command) {
        if (executor == null) {
            return new InstallExec(null, installExecutorUnavailable());
        }
        SandboxExecuteResult res;
        try {
            res = executor.execShellCommandWithOptions(sessionID, command,
                    new ShellExecOptions(null, null, INSTALL_COMMAND_TIMEOUT, null, true, true));
        } catch (Exception e) {
            return new InstallExec(null, failureMessage(e));
        }
        if (res.exitCode() != 0) {
            return new InstallExec(res, "command failed (" + describeExecFailure(res) + ")");
        }
        return new InstallExec(res, null);
    }

    private static String failureMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    // ── 纯函数：结果解析 ────────────────────────────────────────────────

    /** 对照 {@code verificationNotes}：从 stdout 拉出"报告但不拒绝"的 findings。不去重——去重是校验器自己的事。 */
    static List<String> verificationNotes(String stdout) {
        List<String> notes = new ArrayList<>();
        for (String line : stdout.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith(SKILL_VERIFY_NOTE_PREFIX)) {
                notes.add(trimmed.substring(SKILL_VERIFY_NOTE_PREFIX.length()));
            }
        }
        return notes;
    }

    /** 对照 {@code verificationProblems}：失败 pass 的 stderr 按 finding 拆行；空行丢弃，重复保留。 */
    static List<String> verificationProblems(String stderr) {
        List<String> problems = new ArrayList<>();
        for (String line : stderr.split("\n", -1)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                problems.add(trimmed);
            }
        }
        return problems;
    }

    /**
     * 对照 {@code describeExecFailure}（install.go L1103-1122）：把执行器对失败命令知道的
     * 一切渲染出来。传输失败与超时以 exit -1 / 空 stderr / cause in Error 到达。
     */
    static String describeExecFailure(SandboxExecuteResult res) {
        if (res == null) {
            return "no result from the sandbox";
        }
        List<String> parts = new ArrayList<>();
        parts.add("exit " + res.exitCode());
        if (res.killed()) {
            parts.add("killed");
        }
        String cause = res.error().strip();
        if (!cause.isEmpty()) {
            parts.add("error: " + cause);
        }
        String stderr = res.stderr().strip();
        if (!stderr.isEmpty()) {
            parts.add("stderr: " + stderr);
        }
        return String.join("; ", parts);
    }

    // ── 纯函数：命令构造（字节契约，单测钉 Go 实录）──────────────────────

    /** 对照 {@code skillTreeVerifyCommand}（Go L145-161），路径只经 ShellQuote 进命令。 */
    static String skillTreeVerifyCommand(String skillDir, List<String> scripts) {
        StringBuilder b = new StringBuilder();
        b.append("cd ").append(SandboxPaths.shellQuote(skillDir))
                .append(" || { echo 'skill directory is incomplete after install' >&2; exit ")
                .append(SKILL_TREE_VERIFY_DIR_EXIT).append("; }");
        b.append("; status=0");
        b.append("; [ -f 'SKILL.md' ] || { echo 'SKILL.md is missing after install' >&2; status=1; }");
        if (scripts != null && !scripts.isEmpty()) {
            b.append("; for f in");
            for (String rel : scripts) {
                b.append(' ');
                b.append(SandboxPaths.shellQuote(rel));
            }
            b.append("; do [ -f \"$f\" ] || { echo \"script $f is missing after install\" >&2; status=1; }; done");
        }
        b.append("; exit $status");
        return b.toString();
    }

    /**
     * 对照 {@code skillPythonVerifyCommand}（Go L305-327）：校验器经 stdin 以 base64 进
     * 同一个解释器，文件名单在命令行点名（显式列举，绝不目录遍历——不碰 agent 装出来的
     * .venv/node_modules）。auxiliary 以 --optional 命名。
     */
    static String skillPythonVerifyCommand(String skillDir, List<String> entry,
            List<String> auxiliary) {
        String venv = SandboxPaths.join(skillDir, ".venv", "bin", "python");
        String quotedVenv = SandboxPaths.shellQuote(venv);
        List<String> args = new ArrayList<>();
        args.add(SandboxPaths.shellQuote(skillDir));
        if (entry != null) {
            for (String rel : entry) {
                args.add(SandboxPaths.shellQuote(rel));
            }
        }
        if (auxiliary != null && !auxiliary.isEmpty()) {
            args.add(SKILL_VERIFY_OPTIONAL_FLAG);
            for (String rel : auxiliary) {
                args.add(SandboxPaths.shellQuote(rel));
            }
        }
        return "if [ -x " + quotedVenv + " ]; then py=" + quotedVenv + "; else py=python3; fi; "
                + "printf %s '" + Base64.getEncoder().encodeToString(PYTHON_VERIFIER)
                + "' | base64 -d | \"$py\" - " + String.join(" ", args);
    }

    /**
     * 对照 {@code skillNodeVerifyCommand}（Go L335-351）：parse 每个文件；声明的运行时依赖
     * 逐个核查（缺 → repairable 退出码，唯一还能靠一轮安装修复的事）。
     */
    static String skillNodeVerifyCommand(String skillDir, List<String> scripts,
            List<String> deps) {
        List<String> parts = new ArrayList<>(2);
        if (deps != null && !deps.isEmpty()) {
            List<String> quoted = new ArrayList<>(deps.size());
            for (String dep : deps) {
                quoted.add(SandboxPaths.shellQuote(dep));
            }
            parts.add("for d in " + String.join(" ", quoted) + "; do [ -e "
                    + SandboxPaths.shellQuote(SandboxPaths.join(skillDir, "node_modules"))
                    + "/\"$d\" ] || { echo \"package.json declares $d but node_modules/$d is missing\" >&2; exit "
                    + SKILL_VERIFY_REPAIRABLE_EXIT + "; }; done");
        }
        parts.add(forEachScript(skillDir, scripts, "node --check \"$f\""));
        return String.join("; ", parts);
    }

    /**
     * 对照 {@code skillShellVerifyCommand}（Go L361-364）：用将要运行它的 shell 解析
     * （bash 优先；/bin/sh 在 Debian 是 dash，检查用 sh 会冤枉能跑的脚本）。{@code -n}
     * 只读文件不执行。
     */
    static String skillShellVerifyCommand(String skillDir, List<String> scripts) {
        return "if command -v bash >/dev/null 2>&1; then parser=bash; else parser=sh; fi; "
                + forEachScript(skillDir, scripts, "\"$parser\" -n \"$f\"");
    }

    /** 对照 {@code forEachScript}（Go L415-421）。 */
    static String forEachScript(String skillDir, List<String> scripts, String check) {
        List<String> quoted = new ArrayList<>(scripts.size());
        for (String rel : scripts) {
            quoted.add(SandboxPaths.shellQuote(SandboxPaths.join(skillDir, rel)));
        }
        return "for f in " + String.join(" ", quoted) + "; do " + check + " || exit 1; done";
    }

    // ── 纯函数：bundle 视图 ─────────────────────────────────────────────

    /**
     * 对照 {@code skillAuxiliaryScript}（Go L383-400）：规则是语言社区共有的命名约定，
     * 不是一个个 skill 长出来的清单——生态留给测试/示例/文档的目录，或 pytest 与
     * setuptools 已认领的文件名。
     */
    static boolean skillAuxiliaryScript(String rel) {
        String[] segments = GoPath.clean(rel).split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (AUXILIARY_SCRIPT_DIRS.contains(segments[i].toLowerCase())) {
                return true;
            }
        }
        String base = segments[segments.length - 1].toLowerCase();
        String stem = stripExt(base);
        return stem.equals("conftest") || stem.equals("setup")
                || stem.startsWith("test_") || stem.endsWith("_test");
    }

    /** Go path.Ext + strings.TrimSuffix 的合成：去掉最后一个扩展名段。 */
    private static String stripExt(String base) {
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(0, dot) : base;
    }

    record ScriptSplit(List<String> entry, List<String> auxiliary) {
    }

    /** 对照 {@code splitAuxiliaryScripts}：失败即安装失败的文件与只报告的文件分开，保持调用方顺序。 */
    static ScriptSplit splitAuxiliaryScripts(List<String> scripts) {
        List<String> entry = new ArrayList<>();
        List<String> auxiliary = new ArrayList<>();
        for (String rel : scripts) {
            if (skillAuxiliaryScript(rel)) {
                auxiliary.add(rel);
                continue;
            }
            entry.add(rel);
        }
        return new ScriptSplit(entry, auxiliary);
    }

    /**
     * 对照 {@code sortedScriptPaths}（Go L429-444）：bundle 里带任一后缀的文件，稳定排序
     * 让产出的命令确定。Go 从 map 出发乱序收名单后 sort；Java 的 map 序无所谓，结果同。
     */
    static List<String> sortedScriptPaths(SkillBundle bundle, List<String> suffixes) {
        if (bundle == null) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String rel : bundle.files.keySet()) {
            for (String suffix : suffixes) {
                if (rel.endsWith(suffix)) {
                    matches.add(rel);
                    break;
                }
            }
        }
        matches.sort(String::compareTo);
        return matches;
    }

    /** List 变参入口（Go 变参语义）。 */
    static List<String> sortedScriptPaths(SkillBundle bundle, String... suffixes) {
        return sortedScriptPaths(bundle, List.of(suffixes));
    }

    /**
     * 对照 {@code nodeDependencyNames}（Go L449-474）：package.json 声明的运行时依赖。
     * devDependencies 排除（构建期的事，镜像不被要求携带）。读不了的 package.json 是
     * 安装器 agent 要报告的问题，不是校验因此失败的理由 → 空名单。
     */
    static List<String> nodeDependencyNames(SkillBundle bundle) {
        if (bundle == null) {
            return List.of();
        }
        byte[] raw = bundle.files.get("package.json");
        if (raw == null) {
            return List.of();
        }
        Map<String, String> dependencies;
        try {
            dependencies = JSON.readValue(raw, NodeManifest.class).dependencies;
        } catch (Exception e) {
            return List.of();
        }
        if (dependencies == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>(dependencies.size());
        for (String name : dependencies.keySet()) {
            if (!name.strip().isEmpty()) {
                names.add(name);
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    static final class NodeManifest {
        public Map<String, String> dependencies;
    }

    /** 对照 {@code bundleHasPythonDeps}。 */
    static boolean bundleHasPythonDeps(SkillBundle bundle) {
        if (bundle == null) {
            return false;
        }
        return bundle.files.containsKey("requirements.txt")
                || bundle.files.containsKey("pyproject.toml");
    }

    /** 对照 {@code bundleHasNodeDeps}。 */
    static boolean bundleHasNodeDeps(SkillBundle bundle) {
        if (bundle == null) {
            return false;
        }
        return bundle.files.containsKey("package.json");
    }

    // 未用但保留对齐说明：Go 的 SkillInstallRuntimeInstructions（runtime_verify.go L14-37）
    // 只被安装 prompt 构造消费（buildInstallPrompt / buildRepairPrompt），属 installer
    // agent 管线（provider 执行体批），不属校验门本身——随管线一起翻，避免无消费者的
    // 大段常量先漂移。
}
