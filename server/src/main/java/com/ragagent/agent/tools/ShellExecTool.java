package com.ragagent.agent.tools;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;

/**
 * shell_exec：会话 sandbox 内的通用 shell 执行原语（对照 Go {@code shell_exec.go}，逐字移植）。
 *
 * <p>非零退出是正常信号而非工具失败；传输失败/被杀/超时才是 Success=false。
 * 输出截断保头保尾；黑名单拒绝自毁命令；尾随 &amp; 与 nohup 前置拒绝；stdin 作为数据
 * 允许，但作为解释器程序执行时同样过黑名单与 8 KiB 上限。</p>
 */
public class ShellExecTool extends BaseTool {

    static final String DEFAULT_SHELL_EXEC_WORK_DIR = "/workspace";
    static final Duration DEFAULT_SHELL_EXEC_TIMEOUT = Duration.ofSeconds(120);
    static final Duration SHELL_EXEC_MAX_TIMEOUT = Duration.ofSeconds(600);
    static final int SHELL_EXEC_MAX_COMMAND_BYTES = 8 * 1024;
    static final int DEFAULT_SHELL_EXEC_OUTPUT_BYTES = 16 * 1024;
    static final int MAX_SHELL_EXEC_OUTPUT_BYTES = 64 * 1024;
    static final int DEFAULT_SHELL_EXEC_STDERR_BYTES = 8 * 1024;
    static final int MAX_SHELL_EXEC_STDERR_BYTES = 16 * 1024;
    static final int MAX_SHELL_EXEC_ERROR_BYTES = 4 * 1024;
    static final int MAX_SHELL_EXEC_VISIBLE_BYTES = 64 * 1024;

    /** 黑名单条目：名字 + 编译好的正则（对照 shellExecBlacklist；Java 正则与 Go RE2 语法 1:1）。 */
    private record BlacklistEntry(String name, Pattern re) {
    }

    private static final List<BlacklistEntry> SHELL_EXEC_BLACKLIST = List.of(
            new BlacklistEntry("rm_root", Pattern.compile(
                    "(?i)\\brm\\s+(?:-[a-z]*[rR][a-z]*[fF][a-z]*|-[a-z]*[fF][a-z]*[rR][a-z]*|--recursive[^;|&]*--force|--force[^;|&]*--recursive)\\s+(?:--no-preserve-root\\s+)?/(?:\\s|$)")),
            new BlacklistEntry("fork_bomb", Pattern.compile(
                    ":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:")),
            new BlacklistEntry("mkfs", Pattern.compile("(?i)\\bmkfs(\\.[a-z0-9]+)?\\b")),
            new BlacklistEntry("dd_to_device", Pattern.compile("(?i)\\bdd\\b[^;|&]*\\bof=/dev/")),
            new BlacklistEntry("shutdown", Pattern.compile("(?i)\\b(shutdown|reboot|halt|poweroff)\\b")),
            new BlacklistEntry("background_amp", Pattern.compile("(?:^|[^&])&\\s*(?:#.*)?$")),
            new BlacklistEntry("nohup", Pattern.compile("(?i)(^|[;|&\\s])nohup\\b")));

    private static final String DESCRIPTION =
            "Execute a command in the current session's isolated sandbox as root.\n"
                    + "The sandbox belongs to this session alone; nothing here runs on the host.\n"
                    + "- CWD defaults to /workspace on every call; cd does not persist. work_dir selects another directory under /workspace and missing directories are created as the same user.\n"
                    + "- Use ls/find to discover files, grep/awk to search, and cat/head/tail/sed to inspect text. Read known paths directly; no mandatory discovery call.\n"
                    + "- Use write_sandbox_file for scripts or large text; edit_sandbox_file for precise changes. Commands are limited to 8192 bytes. Execution is synchronous (no nohup or trailing &).\n"
                    + "- skill_name selects a listed skill for this call. Installed skills use their Python virtualenv and Node modules;\n"
                    + "  host resources are staged automatically and use the system runtime until a local .venv is created.\n"
                    + "  Scoped credentials apply to both. Example: skill_name=\"pdf\", command=\"python3 report.py\".\n"
                    + "  Run bundled scripts via \"$WEKNORA_SKILL_DIR/scripts/...\". Omit skill_name for system commands.\n"
                    + "- /workspace/input contains user attachments: preserve originals. /workspace/output is the only directory collected\n"
                    + "  for download, so it takes finished deliverables only; keep scratch and intermediate files elsewhere under /workspace.\n"
                    + "  apt-get is available when the sandbox network policy allows it; permanent dependencies belong in the skill installer.\n"
                    + "- Install extras with skill_name set and the default work_dir:\n"
                    + "  `" + SkillRuntimeGuard.SKILL_PYTHON_PACKAGE_INSTALL_COMMAND + "` (no pip needed), or\n"
                    + "  `" + SkillRuntimeGuard.SKILL_NODE_PACKAGE_INSTALL_COMMAND + "`.\n"
                    + "  If .venv is absent, create it with python3 -m venv --without-pip \"${WEKNORA_SKILL_DIR:?}/.venv\".\n"
                    + "  Without uv, run the venv Python with -m ensurepip --upgrade before -m pip install.\n"
                    + "  Changes live and die with this session.\n"
                    + "- Non-zero exit_code is a command result: inspect stderr before deciding whether a corrected call is useful. Transport failures/timeouts are tool failures. Changing tools does not change permissions; do not repeat a denied operation through another tool.\n"
                    + "- stdout/stderr have independent byte limits, preserving head and tail when truncated. Full output is not automatically saved; redirect verbose commands to a workspace log when it must be retained. Binary bytes are suppressed.\n"
                    + "- Reference collected deliverables as ![description](sandbox:<file name>) using the exact filename.";

    private static final String SCHEMA_JSON =
            "{\"type\":\"object\",\"properties\":{\"stdin\":{\"type\":\"string\",\"description\":\"Optional text passed to the command's stdin, up to 65536 bytes. Preserves quotes and newlines exactly; for larger input write a workspace file and redirect from it.\"},"
                    + "\"command\":{\"type\":\"string\",\"description\":\"Shell command to execute (single line, supports pipes and && chaining). Runs under Bash.\"},"
                    + "\"work_dir\":{\"type\":\"string\",\"description\":\"Absolute or relative work dir. Commands already start in /workspace; omit unless the command must run elsewhere.\"},"
                    + "\"timeout_sec\":{\"type\":\"integer\",\"description\":\"Per-call timeout in seconds. Defaults to 120, hard-capped at 600.\"},"
                    + "\"max_output_bytes\":{\"type\":\"integer\",\"description\":\"Maximum bytes returned from stdout. Defaults to 16384, hard-capped at 65536. Stderr defaults to 8192 and is hard-capped at 16384; total visible output is hard-capped at 65536.\"},"
                    + "\"max_stderr_bytes\":{\"type\":\"integer\",\"description\":\"Maximum bytes returned from stderr. Defaults to 8192, hard-capped at 16384.\"},"
                    + "\"env\":{\"type\":\"object\",\"description\":\"Optional extra environment variables, e.g. {\\\"PIP_INDEX_URL\\\":\\\"https://mirrors.example.com/pypi/simple\\\"}.\",\"additionalProperties\":{\"type\":\"string\"}},"
                    + "\"skill_name\":{\"type\":\"string\",\"description\":\"Optional available skill name. Selects its installed runtime or stages its host resources, plus scoped credentials. CWD remains /workspace. Omit for system commands.\"}},"
                    + "\"required\":[\"command\"],\"additionalProperties\":false}";

    /** 捕获钩子：记录一次成功的 shell_exec 已用过的 NAME=value（对照 SkillEnvCapture）。 */
    public interface SkillEnvCapture {
        void capture(String skillName, Map<String, String> pairs);
    }

    private final SandboxCommandExecutor executor;
    private final List<String> workDirRoots;
    private final String defaultWorkDir;
    private final Duration defaultTimeout;
    private final SkillEnvironment.SkillEnvResolver envResolver;
    private SkillEnvCapture envCapture;
    /** 对照 Go 的 skillEnvironment 字段（WithSkillEnvironment 挂载；null = 永不注入）。 */
    private volatile SkillEnvironment skillEnvironment;

    /** 普通会话构造（对照 NewShellExecTool）。executor 不得为 null（装配层做特性开关）。 */
    public ShellExecTool(SandboxCommandExecutor executor, SkillEnvironment.SkillEnvResolver envResolver) {
        super(ToolDefinitions.TOOL_SHELL_EXEC, DESCRIPTION, SCHEMA_JSON);
        this.executor = executor;
        this.envResolver = envResolver;
        this.workDirRoots = List.of(DEFAULT_SHELL_EXEC_WORK_DIR);
        this.defaultWorkDir = "";
        this.defaultTimeout = Duration.ZERO;
    }

    private ShellExecTool(SandboxCommandExecutor executor, List<String> workDirRoots, String defaultWorkDir,
            Duration defaultTimeout, String description) {
        super(ToolDefinitions.TOOL_SHELL_EXEC, description, SCHEMA_JSON);
        this.executor = executor;
        this.envResolver = null;
        this.workDirRoots = workDirRoots;
        this.defaultWorkDir = defaultWorkDir;
        this.defaultTimeout = defaultTimeout;
    }

    /**
     * install 模式变体（对照 NewInstallShellExecTool）：root 运行、可在 skills image root
     * 内工作，只为内建 skill 安装器 agent 注册。skillDir 成为缺省工作目录——空或无法识别
     * 的目录回落 /workspace 而不是猜。
     */
    public static ShellExecTool newInstallShellExecTool(SandboxInstallCommandExecutor executor, String skillDir) {
        String dir = SandboxPaths.validatedImageSkillDir(skillDir);
        String defaultWorkDir = dir != null ? dir : DEFAULT_SHELL_EXEC_WORK_DIR;
        return new ShellExecTool(
                new InstallShellExecutor(executor),
                List.of(DEFAULT_SHELL_EXEC_WORK_DIR, SandboxPaths.SKILLS_IMAGE_ROOT),
                defaultWorkDir,
                SHELL_EXEC_MAX_TIMEOUT,
                installShellExecDescription(defaultWorkDir));
    }

    /** install 适配器（对照 installShellExecutor）：每次调用盖 AsRoot + AllowSkillsRoot 章。 */
    private static final class InstallShellExecutor implements SandboxCommandExecutor {
        private final SandboxInstallCommandExecutor inner;

        InstallShellExecutor(SandboxInstallCommandExecutor inner) {
            this.inner = inner;
        }

        @Override
        public SandboxExecuteResult execShellCommand(String sessionId, String command, String workDir,
                Duration timeout, Map<String, String> env, CommandOutputListener output) throws Exception {
            SandboxExecuteResult r = inner.execShellCommandWithOptions(sessionId, command,
                    new ShellExecOptions(output, workDir, timeout, env, true, true));
            return r;
        }
    }

    /** install 模式描述（对照 installShellExecDescription）。 */
    static String installShellExecDescription(String defaultWorkDir) {
        return "Run a shell command as root inside the skill-install sandbox.\n"
                + "\n"
                + "## Working Directory\n"
                + "- Every command already starts in `" + defaultWorkDir + "`, the skill\n"
                + "  you are installing. Use RELATIVE paths: `ls -la scripts/`,\n"
                + "  `cat requirements.txt`, `uv venv --seed .venv`.\n"
                + "- Do NOT prefix `cd " + defaultWorkDir + " && ` onto your commands.\n"
                + "  You are already there, and the prefix wastes a line on every call.\n"
                + "- Pass `work_dir` only to leave that directory, which an install\n"
                + "  rarely needs.\n"
                + "\n"
                + "## Usage\n"
                + "- Run commands with this tool. Create or change files in the skill directory\n"
                + "  with `write_skill_file` / `edit_skill_file` instead of\n"
                + "  `cat` or a heredoc: a shell redirect truncates at the\n"
                + "  command-length cap and mangles quoting.\n"
                + "- Install Python extras into the skill's `.venv`, Node extras into\n"
                + "  `node_modules`. Prefer `uv pip install` / `python3 -m venv`.\n"
                + "\n"
                + "## Parameters\n"
                + "- `command` (required): the shell one-liner under `/bin/bash -l -c`.\n"
                + "- `work_dir` (optional): defaults to `" + defaultWorkDir + "`.\n"
                + "- `timeout_sec` (optional): defaults to 600 seconds.\n"
                + "\n"
                + "## Returns\n"
                + "- `exit_code`, `stdout`, `stderr`. Non-zero is not a\n"
                + "  tool error — read stderr and adapt.";
    }

    /** 挂可选捕获钩子（对照 WithEnvCapture；null 钩子等价 no-op）。 */
    public ShellExecTool withEnvCapture(SkillEnvCapture capture) {
        this.envCapture = capture;
        return this;
    }

    /** 挂 skill 环境（对照 WithSkillEnvironment；null = 永不注入 skill env）。 */
    public ShellExecTool withSkillEnvironment(SkillEnvironment manager) {
        this.skillEnvironment = manager;
        return this;
    }

    /** 对照 OutputLimitChars：保留 shell_exec 显式有界的输出上限。 */
    public int outputLimitChars(JsonNode args) {
        return MAX_SHELL_EXEC_VISIBLE_BYTES;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        String stdin = args.path("stdin").asText("");
        String commandRaw = args.path("command").asText("");
        String workDirIn = args.path("work_dir").asText("");
        int timeoutSec = args.path("timeout_sec").asInt(0);
        int maxOutputBytes = args.path("max_output_bytes").asInt(0);
        int maxStderrBytes = args.path("max_stderr_bytes").asInt(0);
        Map<String, String> inputEnv = parseEnv(args.get("env"));
        String skillName = args.path("skill_name").asText("");

        SkillEnvironment skillEnvironment = this.skillEnvironment;

        if (executor == null) {
            return failure("shell_exec is not available in this deployment (remote sandbox required)");
        }
        if (!skillName.isEmpty() && skillEnvironment == null) {
            return failure("no skill environment is available for this call; omit skill_name for system commands");
        }

        if (stdin.getBytes(StandardCharsets.UTF_8).length > 65536) {
            return failure("stdin exceeds 65536 bytes; write the input to a workspace file and redirect from it");
        }
        String command = commandRaw.strip();
        if (command.isEmpty()) {
            return failure("command is required");
        }
        if (command.getBytes(StandardCharsets.UTF_8).length > SHELL_EXEC_MAX_COMMAND_BYTES) {
            return failure(String.format(
                    "command too long (%d bytes; max %d). Put the file in write_sandbox_file, then run it with shell_exec",
                    command.getBytes(StandardCharsets.UTF_8).length, SHELL_EXEC_MAX_COMMAND_BYTES));
        }
        String reason = checkShellExecBlacklist(command);
        if (!reason.isEmpty()) {
            return failure("command rejected by shell_exec safety guard: " + reason);
        }
        String stdinReason = rejectExecutableStdin(command, stdin);
        if (!stdinReason.isEmpty()) {
            return failure(stdinReason);
        }
        String sessionId = request.sessionId();
        if (sessionId.isEmpty()) {
            return failure("no session ID in context; shell_exec must run inside an agent turn");
        }

        String workDir = workDirIn.strip();
        if (workDir.isEmpty()) {
            workDir = effectiveDefaultWorkDir();
        }
        if (!SandboxPaths.isAbs(workDir)) {
            workDir = SandboxPaths.join(effectiveDefaultWorkDir(), workDir);
        }
        String cleanWorkDir = GoPath.clean(workDir);
        if (!workDirAllowed(cleanWorkDir)) {
            return failure(String.format(
                    "work_dir %s is outside the allowed sandbox roots %s",
                    ListSandboxFilesTool.quoteGo(workDirIn), String.join(", ", allowedWorkDirRoots())));
        }
        workDir = cleanWorkDir;

        Duration timeout = defaultTimeout;
        if (timeout.isZero() || timeout.isNegative()) {
            timeout = DEFAULT_SHELL_EXEC_TIMEOUT;
        }
        if (timeoutSec > 0) {
            timeout = Duration.ofSeconds(Math.min(timeoutSec, (int) (SHELL_EXEC_MAX_TIMEOUT.toSeconds())));
        }
        if (timeout.compareTo(SHELL_EXEC_MAX_TIMEOUT) > 0) {
            timeout = SHELL_EXEC_MAX_TIMEOUT;
        }

        // config-wide 变量适用每条命令；skill 声明的凭据只在模型点名该 skill 时叠加。
        // 值来自调用方上下文，只活在本进程，叠加时不顶替模型经 env 传入的任何值。
        Map<String, String> env = inputEnv;
        // supplied：本次调用自带的值（env 参数 + 命令里的 NAME=value 赋值）。
        // 它们能满足尚未存储的必填变量，也是 capture 允许持久化的唯一值。
        Map<String, String> supplied = ShellEnvExtractor.collectUsedSkillEnv(command,
                inputEnv == null ? Map.of() : inputEnv);
        if (envResolver != null) {
            SkillEnvironment.SkillEnvResolution resolution;
            try {
                resolution = envResolver.resolveEnv(skillName);
            } catch (Exception e) {
                return failure("failed to resolve environment variables: " + messageOr(e));
            }
            List<String> missing = stillMissing(resolution.missing(), supplied);
            if (!missing.isEmpty()) {
                return failure(String.format(
                        "skill %s needs the environment variable(s) %s, which nobody has set yet. "
                                + "Ask the user for them and pass them in this call's env, "
                                + "or have them set the values under Settings → Sandbox secrets.",
                        ListSandboxFilesTool.quoteGo(skillName), String.join(", ", missing)));
            }
            if (!resolution.env().isEmpty() && env == null) {
                env = new LinkedHashMap<>();
            }
            SkillEnvironment.applyResolvedEnv(env, resolution.env());
            supplied = dropResolvedNames(supplied, resolution.env());
        }
        String execCommand = command;
        if (!skillName.isEmpty() && skillEnvironment != null) {
            SkillEnvironment.PreparedShell prepared;
            try {
                prepared = skillEnvironment.prepareShellEnvironment(sessionId, skillName, command, env);
            } catch (Exception e) {
                return failure(messageOr(e));
            }
            execCommand = prepared.command();
            env = prepared.env();
        }
        if (!stdin.isEmpty()) {
            // base64 让数据脱离 shell 语法并保留尾换行；对整条命令（含复合语法）生效。
            String encoded = Base64.getEncoder().encodeToString(stdin.getBytes(StandardCharsets.UTF_8));
            execCommand = "printf %s " + SandboxPaths.shellQuote(encoded)
                    + " | base64 -d | /bin/bash --noprofile --norc -c " + SandboxPaths.shellQuote(execCommand);
        }
        boolean inspectedOutputs = false;
        Map<String, OutputLinks.DirEntry> beforeOutputs = null;
        Map<String, OutputLinks.DirEntry> snapshotBefore =
                SandboxDiffs.sandboxOutputSnapshot(executor, sessionId);
        if (snapshotBefore != null) {
            inspectedOutputs = true;
            beforeOutputs = snapshotBefore;
        }
        // 命令输出预览（4.5a 的 ShellCommandOutput——这里才是真正触发它的执行路径）。
        ShellCommandOutput output = ShellCommandOutput.start(request.execMeta(), command);
        CommandOutputListener listener = output != null
                ? output::appendOutput
                : CommandOutputListener.none();
        SandboxExecuteResult res;
        try {
            res = executor.execShellCommand(sessionId, execCommand, workDir, timeout, env, listener);
        } catch (Exception e) {
            output.finish();
            FileMutationQueue.noteSandboxMutation();
            String errorText = truncateShellStream("shell_exec failed: " + messageOr(e), MAX_SHELL_EXEC_ERROR_BYTES)[0];
            return failure(errorText);
        }
        output.finish();
        FileMutationQueue.noteSandboxMutation();
        if (res == null) {
            return failure("shell executor returned no result");
        }
        String resError = res.error();
        if (res.killed() && resError.isEmpty()) {
            resError = SandboxPaths.ERR_TIMEOUT;
        }
        final SandboxExecuteResult adjusted = new SandboxExecuteResult(
                res.stdout(), res.stderr(), res.exitCode(), res.duration(), res.killed(), resError);

        maybeCaptureSkillEnv(skillEnvironment, skillName, supplied, res);

        int outputLimit = resolveShellOutputLimit(maxOutputBytes);
        int stderrLimit = resolveShellStderrLimit(maxStderrBytes);
        StreamPrep stdout = prepareShellStream(res.stdout(), outputLimit);
        StreamPrep stderr = prepareShellStream(res.stderr(), stderrLimit);
        String[] errorPrep = truncateShellStream(resError, MAX_SHELL_EXEC_ERROR_BYTES);
        String errorText = errorPrep[0];
        boolean errorTruncated = Boolean.parseBoolean(errorPrep[1]);
        boolean truncated = stdout.truncated() || stderr.truncated() || errorTruncated;

        // 给 LLM 的人读摘要。
        StringBuilder b = new StringBuilder();
        b.append("=== Shell Exec (session=").append(sessionId).append(") ===\n\n");
        b.append("**Command**: `").append(command).append("`\n");
        b.append("**Work Dir**: ").append(workDir).append("\n");
        b.append("**Exit Code**: ").append(res.exitCode()).append("\n");
        b.append("**Duration**: ").append(res.goDuration()).append("\n");
        if (res.killed()) {
            b.append("**Killed**: yes (timeout or terminated)\n");
        }
        if (truncated) {
            b.append("**Truncated**: yes (head+tail kept; full output was not saved. For future commands, redirect verbose output to a workspace log and read that file.)\n");
        }
        if (stdout.binary() || stderr.binary()) {
            b.append("**Binary Output Suppressed**: yes (write binary files to the artifact output directory for download)\n");
        }
        b.append("\n");

        if (!stdout.text().isEmpty()) {
            b.append("## Stdout\n\n```\n");
            b.append(stdout.text());
            if (!stdout.text().endsWith("\n")) {
                b.append("\n");
            }
            b.append("```\n\n");
        }
        if (!stderr.text().isEmpty()) {
            b.append("## Stderr\n\n```\n");
            b.append(stderr.text());
            if (!stderr.text().endsWith("\n")) {
                b.append("\n");
            }
            b.append("```\n\n");
        }
        if (!errorText.isEmpty()) {
            b.append("## Error\n\n");
            b.append(errorText);
            b.append("\n");
        }
        String hint = recoveryHint(skillEnvironment, skillName, res.exitCode(), command, stderr.text());
        if (!hint.isEmpty()) {
            b.append(hint);
            b.append("\n");
        }
        List<String> outputFiles = new ArrayList<>();
        if (inspectedOutputs) {
            Map<String, OutputLinks.DirEntry> afterOutputs =
                    SandboxDiffs.sandboxOutputSnapshot(executor, sessionId);
            if (afterOutputs != null) {
                outputFiles = OutputLinks.changedOutputLinks(beforeOutputs, afterOutputs);
            }
        }
        String visibleOutput = b.toString();
        String[] totalPrep = truncateShellStream(visibleOutput, MAX_SHELL_EXEC_VISIBLE_BYTES);
        visibleOutput = totalPrep[0];
        boolean totalTruncated = Boolean.parseBoolean(totalPrep[1]);
        truncated = truncated || errorTruncated || totalTruncated;

        // 命令非零退出时调用本身仍成功：LLM 需要 stderr/exit_code 作为一等信号来迭代。
        // 传输级故障或进程被杀/超时才置 Success=false。
        Map<String, Object> resultData = buildResultData(sessionId, command, workDir, adjusted,
                stdout, stderr, errorText, errorTruncated, totalTruncated, truncated,
                b.toString().getBytes(StandardCharsets.UTF_8).length,
                visibleOutput.getBytes(StandardCharsets.UTF_8).length, outputLimit, stderrLimit);

        ToolResult r = new ToolResult();
        r.setSuccess(resError.isEmpty() && !res.killed());
        r.setError(errorText);
        r.setOutput(visibleOutput);
        r.setOutputFiles(outputFiles);
        r.setData(resultData);
        return r;
    }

    private static Map<String, Object> buildResultData(String sessionId, String command, String workDir,
            SandboxExecuteResult res, StreamPrep stdout, StreamPrep stderr, String errorText,
            boolean errorTruncated, boolean totalTruncated, boolean truncated,
            int visibleOriginal, int visibleReturned, int outputLimit, int stderrLimit) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("display_type", "shell_exec");
        d.put("session_id", sessionId);
        d.put("command", command);
        d.put("work_dir", workDir);
        d.put("exit_code", res.exitCode());
        d.put("stdout", stdout.text());
        d.put("stderr", stderr.text());
        d.put("duration_ms", res.duration().toMillis());
        d.put("killed", res.killed());
        d.put("truncated", truncated);
        d.put("stdout_truncated", stdout.truncated());
        d.put("stderr_truncated", stderr.truncated());
        d.put("stdout_binary", stdout.binary());
        d.put("stderr_binary", stderr.binary());
        d.put("stdout_bytes", res.stdout().getBytes(StandardCharsets.UTF_8).length);
        d.put("stderr_bytes", res.stderr().getBytes(StandardCharsets.UTF_8).length);
        d.put("stdout_original_bytes", res.stdout().getBytes(StandardCharsets.UTF_8).length);
        d.put("stdout_returned_bytes", stdout.text().getBytes(StandardCharsets.UTF_8).length);
        d.put("stderr_original_bytes", res.stderr().getBytes(StandardCharsets.UTF_8).length);
        d.put("stderr_returned_bytes", stderr.text().getBytes(StandardCharsets.UTF_8).length);
        d.put("error_original_bytes", res.error().getBytes(StandardCharsets.UTF_8).length);
        d.put("error_returned_bytes", errorText.getBytes(StandardCharsets.UTF_8).length);
        d.put("error_truncated", errorTruncated);
        d.put("total_truncated", totalTruncated);
        d.put("visible_original_bytes", visibleOriginal);
        d.put("visible_returned_bytes", visibleReturned);
        d.put("max_output_bytes", outputLimit);
        d.put("max_stderr_bytes", stderrLimit);
        return d;
    }

    private static Map<String, String> parseEnv(JsonNode node) {
        // Go 的 Env 是 map[string]string + omitempty：缺键 = nil map（执行器收到 null）。
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, String> env = new LinkedHashMap<>();
        var it = node.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            env.put(name, node.get(name).asText(""));
        }
        return env;
    }

    private static String messageOr(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    /** 成功的普通命令用过的凭据持久化（对照 maybeCaptureSkillEnv；install 模式绝不触发）。 */
    private void maybeCaptureSkillEnv(SkillEnvironment env, String skillName,
            Map<String, String> pairs, SandboxExecuteResult res) {
        if (envCapture == null || isInstallMode()) {
            return;
        }
        if (res == null || res.exitCode() != 0) {
            return;
        }
        String name = skillName == null ? "" : skillName.strip();
        if (!SandboxPaths.isValidSkillName(name) || pairs.isEmpty()) {
            return;
        }
        envCapture.capture(name, pairs);
    }

    /** 本次调用已提供的名字从 missing 中移除（对照 stillMissing）。 */
    static List<String> stillMissing(List<String> missing, Map<String, String> supplied) {
        if (missing == null || missing.isEmpty() || supplied == null || supplied.isEmpty()) {
            return missing != null ? missing : new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String name : missing) {
            String v = supplied.get(name);
            if (v == null || v.strip().isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }

    /** 还没有任何东西存住的 provided 值（对照 dropResolvedNames）。 */
    static Map<String, String> dropResolvedNames(Map<String, String> supplied, Map<String, String> resolved) {
        if (supplied.isEmpty() || resolved == null || resolved.isEmpty()) {
            return supplied;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : supplied.entrySet()) {
            if (resolved.containsKey(e.getKey())) {
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private boolean isInstallMode() {
        for (String root : workDirRoots) {
            if (root.equals(SandboxPaths.SKILLS_IMAGE_ROOT)) {
                return true;
            }
        }
        return false;
    }

    private String effectiveDefaultWorkDir() {
        if (defaultWorkDir.strip().isEmpty()) {
            return DEFAULT_SHELL_EXEC_WORK_DIR;
        }
        return defaultWorkDir;
    }

    private List<String> allowedWorkDirRoots() {
        if (workDirRoots.isEmpty()) {
            return List.of(DEFAULT_SHELL_EXEC_WORK_DIR);
        }
        return workDirRoots;
    }

    private boolean workDirAllowed(String cleanWorkDir) {
        for (String root : allowedWorkDirRoots()) {
            if (SandboxPaths.isUnderRoot(cleanWorkDir, root)) {
                return true;
            }
        }
        return false;
    }

    /** 对照 shellExecRecoveryHint（无 skill 环境的函数级提示）。 */
    static String shellExecRecoveryHint(int exitCode, String command, String stderr) {
        List<String> parts = new ArrayList<>();
        String h = shellCommandNotFoundHint(exitCode, command, stderr);
        if (!h.isEmpty()) {
            parts.add(h);
        }
        if (SkillRuntimeGuard.isSkillVenvInstallFailure(stderr)) {
            parts.add("Hint: " + SkillRuntimeGuard.skillVenvFailureGuidance(skillNameFromShellCommand(command), stderr));
            return String.join("\n", parts);
        }
        String m = shellMissingModuleHint(command, stderr);
        if (!m.isEmpty()) {
            parts.add(m);
        } else {
            String e = shellInlineEvalHint(command);
            if (!e.isEmpty()) {
                parts.add(e);
            }
        }
        return String.join("\n", parts);
    }

    /** 对照 recoveryHint（带 skill 环境的方法级提示）。 */
    String recoveryHint(SkillEnvironment env, String skillName, int exitCode, String command, String stderr) {
        if (exitCode == 0) {
            return "";
        }
        if (env == null) {
            return shellExecRecoveryHint(exitCode, command, stderr);
        }
        if (SkillRuntimeGuard.isSkillVenvInstallFailure(stderr)) {
            String name = skillName;
            if (name.isEmpty()) {
                name = skillNameFromShellCommand(command);
            }
            return SkillRuntimeGuard.skillVenvFailureGuidance(name, stderr);
        }
        if (SkillRuntimeGuard.isPermissionFailure(stderr) || SkillRuntimeGuard.isReadOnlyFilesystemFailure(stderr)) {
            return "Permission denied: commands and file tools share the same user. "
                    + "Inspect path permissions and mount restrictions; use a writable workspace location. "
                    + "Switching tools or retrying the same write does not grant access.";
        }
        if (isMissingInterpreterModule(stderr)) {
            if (skillName.isEmpty()) {
                return "If this command needs an installed skill's packages, repeat shell_exec with that skill_name to select its runtime. Otherwise install the missing dependency in the writable workspace.";
            }
            if (stderr.contains("Cannot find module") || stderr.contains("MODULE_NOT_FOUND")) {
                return SkillRuntimeGuard.missingSkillPackageGuidance(skillName)
                        + " NODE_PATH supports CommonJS; ESM imports resolve relative to the script, "
                        + "so custom ESM scripts need dependencies in their own workspace project.";
            }
            return "The selected skill runtime lacks this module. " + SkillRuntimeGuard.missingSkillPackageGuidance(skillName);
        }
        return shellCommandNotFoundHint(exitCode, command, stderr);
    }

    static String shellMissingModuleHint(String command, String stderr) {
        if (!isMissingInterpreterModule(stderr)) {
            return "";
        }
        String skill = skillNameFromShellCommand(command);
        String skillArg = "skill_name=<the skill that owns those packages>";
        if (!skill.isEmpty()) {
            skillArg = String.format("skill_name=%s", ListSandboxFilesTool.quoteGo(skill));
        }
        return "Hint: system python3 / node do not see skill packages (docx, pptx, pandas, …). "
                + "Do not pip install them into this session, and do not paste the same program into "
                + "`.venv/bin/python -c`. Write it with write_sandbox_file, then "
                + "shell_exec(" + skillArg + ", command=python3 /workspace/output/inspect.py).";
    }

    static boolean isMissingInterpreterModule(String stderr) {
        if (SkillRuntimeGuard.isSkillVenvInstallFailure(stderr)) {
            return false;
        }
        return stderr.contains("ModuleNotFoundError") || stderr.contains("No module named")
                || stderr.contains("Cannot find module") || stderr.contains("MODULE_NOT_FOUND");
    }

    static String shellInlineEvalHint(String command) {
        if (!isInlineInterpreterProgram(command)) {
            return "";
        }
        String skill = skillNameFromShellCommand(command);
        String skillArg = "skill_name=...";
        if (!skill.isEmpty()) {
            skillArg = String.format("skill_name=%s", ListSandboxFilesTool.quoteGo(skill));
        }
        return "Hint: do not pass a multi-line program through python -c / node -e "
                + "(including a skill venv). Write it with write_sandbox_file, then "
                + "shell_exec(" + skillArg + ", command=python3 /workspace/output/inspect.py).";
    }

    static boolean isInlineInterpreterProgram(String command) {
        if (!hasInlineEvalFlag(command)) {
            return false;
        }
        return command.length() >= 280 || command.chars().filter(c -> c == '\n').count() >= 2;
    }

    static boolean hasInlineEvalFlag(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        boolean pythonEval = lower.contains("python") && lower.contains(" -c");
        boolean nodeEval = lower.contains("node")
                && (lower.contains(" -e") || lower.contains(" --eval"));
        return pythonEval || nodeEval;
    }

    static String skillNameFromShellCommand(String command) {
        int idx = command.indexOf(SandboxPaths.SKILLS_IMAGE_ROOT + "/");
        if (idx < 0) {
            return "";
        }
        String rest = command.substring(idx);
        int end = indexOfAny(rest, " \t\"'");
        if (end > 0) {
            rest = rest.substring(0, end);
        }
        SandboxPaths.ImageSkill hit = SandboxPaths.skillNameFromImagePath(rest);
        if (hit == null || !hit.inImage()) {
            return "";
        }
        return hit.name();
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 127 之后把模型从 apt-get install tree/编辑器引开（对照 shellCommandNotFoundHint）。
     * 那些包不在 slim 镜像里（file 在），会话安装也会被丢弃。
     */
    static String shellCommandNotFoundHint(int exitCode, String command, String stderr) {
        if (exitCode != 127 && !stderr.toLowerCase(Locale.ROOT).contains("command not found")) {
            return "";
        }
        String missing = inferredMissingCommand(command, stderr);
        return switch (missing) {
            case "tree", "less", "more", "nano", "vim", "vi" ->
                "Hint: `" + missing + "` is not in the default sandbox image. Use find/ls, head, sed, and `file`. Skill scripts: `read_file` for skill instructions, then the execution tool named there. Do not apt-get install inspection tools — session packages are discarded.";
            default ->
                "Hint: that command is not installed. Prefer find, ls, head, tail, cat, sed, grep, awk, file. apt-get install only for a package this task actually needs — session installs are discarded.";
        };
    }

    static String inferredMissingCommand(String command, String stderr) {
        String lower = stderr.toLowerCase(Locale.ROOT);
        final String marker = ": command not found";
        int i = lower.indexOf(marker);
        if (i > 0) {
            String head = stderr.substring(0, i).strip();
            int j = lastIndexOfAny(head, ": \t");
            if (j >= 0) {
                head = head.substring(j + 1).strip();
            }
            if (!head.isEmpty()) {
                return SandboxPaths.base(head);
            }
        }
        String[] fields = command.split("\\s+");
        if (fields.length == 0) {
            return "";
        }
        return SandboxPaths.base(fields[0]);
    }

    private static int lastIndexOfAny(String s, String chars) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    static int resolveShellOutputLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_SHELL_EXEC_OUTPUT_BYTES;
        }
        return Math.min(requested, MAX_SHELL_EXEC_OUTPUT_BYTES);
    }

    static int resolveShellStderrLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_SHELL_EXEC_STDERR_BYTES;
        }
        return Math.min(requested, MAX_SHELL_EXEC_STDERR_BYTES);
    }

    /** 流准备结果。 */
    record StreamPrep(String text, boolean truncated, boolean binary) {
    }

    /** 二进制在进 ToolResult 前就抑制；文本流用保头保尾的有界化（对照 prepareShellStream）。 */
    static StreamPrep prepareShellStream(String s, int limit) {
        if (isBinaryShellOutput(s)) {
            return new StreamPrep("", false, true);
        }
        String[] out = truncateShellStream(s, limit);
        return new StreamPrep(out[0], Boolean.parseBoolean(out[1]), false);
    }

    /** Go isBinaryShellOutput：string 形态（JSON 输入恒为合法 UTF-8）。 */
    static boolean isBinaryShellOutput(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        return isBinaryShellOutput(s.getBytes(StandardCharsets.UTF_8));
    }

    /** byte[] 形态（文件下载路径的原始字节；对照 Go 对 string(data) 的同函数）。 */
    static boolean isBinaryShellOutput(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        if (!isValidUtf8(data) || indexOfZero(data) >= 0) {
            return true;
        }
        // 任何非文本控制字节都足以抑制流。ANSI 终端转义仍放行，普通的彩色输出保持可读。
        for (byte b : data) {
            if (b < 0) {
                continue; // 多字节序列的续字节
            }
            if (b < 0x20 && b != '\n' && b != '\r' && b != '\t' && b != '\b' && b != '\f' && b != 0x1b) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidUtf8(byte[] data) {
        java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            dec.decode(java.nio.ByteBuffer.wrap(data));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int indexOfZero(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    /** 黑名单命中返回条目名；允许的命令返回 ""（对照 checkShellExecBlacklist）。 */
    static String checkShellExecBlacklist(String command) {
        for (BlacklistEntry entry : SHELL_EXEC_BLACKLIST) {
            if (entry.re().matcher(command).find()) {
                return entry.name();
            }
        }
        return "";
    }

    static String rejectExecutableStdin(String command, String stdin) {
        if (stdin == null || stdin.isEmpty() || !shellStdinIsProgram(command)) {
            return "";
        }
        if (stdin.getBytes(StandardCharsets.UTF_8).length > SHELL_EXEC_MAX_COMMAND_BYTES) {
            return String.format(
                    "stdin program too long (%d bytes; max %d). Put the program in write_sandbox_file, then run it with shell_exec",
                    stdin.getBytes(StandardCharsets.UTF_8).length, SHELL_EXEC_MAX_COMMAND_BYTES);
        }
        String reason = checkShellExecBlacklist(stdin);
        if (!reason.isEmpty()) {
            return "command rejected by shell_exec safety guard: " + reason;
        }
        return "";
    }

    static boolean shellStdinIsProgram(String command) {
        String[] fields = command.split("\\s+");
        int i = 0;
        while (i < fields.length && fields[i].contains("=") && !fields[i].startsWith("-")) {
            i++;
        }
        if (i >= fields.length) {
            return false;
        }
        String bin = SandboxPaths.base(fields[i]);
        String[] args = java.util.Arrays.copyOfRange(fields, i + 1, fields.length);
        if (bin.equals("bash") || bin.equals("sh") || bin.equals("dash") || bin.equals("zsh")
                || bin.equals("ksh") || bin.equals("ash")) {
            return interpreterReadsStdin(args, java.util.Set.of("-c"));
        }
        if (bin.equals("python") || bin.equals("python2") || bin.equals("python3") || bin.equals("pypy")
                || bin.equals("pypy3") || bin.startsWith("python3.") || bin.startsWith("python2.")) {
            return interpreterReadsStdin(args, java.util.Set.of("-c", "-m"));
        }
        if (bin.equals("node") || bin.equals("nodejs")) {
            return interpreterReadsStdin(args, java.util.Set.of("-e", "--eval", "-p", "--print"));
        }
        if (bin.equals("perl") || bin.equals("ruby")) {
            return interpreterReadsStdin(args, java.util.Set.of("-e", "-c"));
        }
        return false;
    }

    static boolean interpreterReadsStdin(String[] args, java.util.Set<String> programFlags) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--")) {
                return i + 1 >= args.length;
            }
            if (programFlags.contains(arg)) {
                return false;
            }
            if (arg.startsWith("-") && arg.length() > 2 && programFlags.contains(arg.substring(0, 2))) {
                return false;
            }
            if (arg.equals("-") || arg.equals("-s")) {
                return true;
            }
            if (!arg.startsWith("-")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把 s 缩到 limit 字节内，保头保尾（对照 truncateShellStream）。尾段优先——
     * shell 运行的最后几行几乎总带着可行动的诊断。
     * 返回 {内容, 是否截断}。
     */
    static String[] truncateShellStream(String s, int limit) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (limit <= 0 || bytes.length <= limit) {
            return new String[] {s, "false"};
        }
        // 标记本身算进字节预算；省略字节数取决于保留大小，先算一次再重算最终切分。
        // Go 的 len() 是字节；切分点可能落在 rune 中间——输出按 Go 的逐字节 U+FFFD 语义解码。
        String marker = String.format("\n...[truncated %d bytes]...\n", bytes.length - limit);
        if (marker.length() >= limit) {
            return new String[] {goStringFromBytes(bytes, bytes.length - limit, bytes.length), "true"};
        }
        int kept = limit - marker.length();
        int head = kept / 4;
        int tail = kept - head;
        marker = String.format("\n...[truncated %d bytes]...\n", bytes.length - head - tail);
        if (marker.length() != limit - kept) {
            kept = limit - marker.length();
            head = kept / 4;
            tail = kept - head;
        }
        byte[] out = new byte[head + marker.length() + tail];
        System.arraycopy(bytes, 0, out, 0, head);
        byte[] markerBytes = marker.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(markerBytes, 0, out, head, markerBytes.length);
        System.arraycopy(bytes, bytes.length - tail, out, head + markerBytes.length, tail);
        return new String[] {goStringFromBytes(out, 0, out.length), "true"};
    }

    /**
     * Go string 的 Java 形态：逐字节解码，非法 UTF-8 字节各产出一个 U+FFFD
     * （对照 Go json.Marshal 对无效字节的替换粒度——切到 rune 中间的截断输出因此一致）。
     */
    static String goStringFromBytes(byte[] bytes, int from, int to) {
        StringBuilder sb = new StringBuilder(to - from);
        int i = from;
        while (i < to) {
            int b = bytes[i] & 0xFF;
            if (b < 0x80) {
                sb.append((char) b);
                i++;
                continue;
            }
            int len = byteSequenceLength(b);
            if (len > 1 && i + len <= to && validUtf8Sequence(bytes, i, len)) {
                int cp = decodeUtf8(bytes, i, len);
                // 对照 Go：surrogate/超长编码在 Go 的解码里就是 RuneError w=1 → 逐字节替换
                if (cp >= 0 && !(cp >= 0xD800 && cp <= 0xDFFF)) {
                    sb.appendCodePoint(cp);
                    i += len;
                    continue;
                }
            }
            sb.append('\uFFFD');
            i++;
        }
        return sb.toString();
    }

    private static int byteSequenceLength(int lead) {
        if ((lead & 0xE0) == 0xC0) {
            return 2;
        }
        if ((lead & 0xF0) == 0xE0) {
            return 3;
        }
        if ((lead & 0xF8) == 0xF0) {
            return 4;
        }
        return 1; // 孤立续字节/非法首字节
    }

    private static boolean validUtf8Sequence(byte[] bytes, int i, int len) {
        for (int j = 1; j < len; j++) {
            if ((bytes[i + j] & 0xC0) != 0x80) {
                return false;
            }
        }
        return true;
    }

    private static int decodeUtf8(byte[] bytes, int i, int len) {
        switch (len) {
            case 2:
                return ((bytes[i] & 0x1F) << 6) | (bytes[i + 1] & 0x3F);
            case 3:
                return ((bytes[i] & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F);
            case 4:
                return ((bytes[i] & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12)
                        | ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F);
            default:
                return -1;
        }
    }

    private static ToolResult failure(String error) {
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(error);
        return r;
    }
}
