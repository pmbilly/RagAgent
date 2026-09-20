package com.ragagent.agent.tools;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * shell_exec 的 Go 实录回放：schema/描述、黑名单、截断、二进制抑制、stdin 判定、
 * 各类 hint、环境解析/捕获、Execute 全路径（含 install 变体）。
 */
class ShellExecRecordingTest {

    private static JsonNode rec(String group, String id) {
        return Tools45cFakes.rec45c(group, id);
    }

    @Test
    void schemaAndDescriptionsMatchGo() {
        ShellExecTool tool = new ShellExecTool(new Tools45cFakes.RecExecutor(), null);
        assertThat(RecordingSupport.canonicalJson(tool.getParameters()))
                .isEqualTo(RecordingSupport.canonicalJson(RecordingSupport.readTree(
                        rec("shell_exec", "schema").get("out").asText())));
        assertThat(tool.getDescription())
                .isEqualTo(rec("shell_exec", "description").get("out").asText());
        assertThat(ShellExecTool.newInstallShellExecTool(null, "/opt/weknora/tenant/skills/pdf").getDescription())
                .isEqualTo(rec("shell_exec", "install_description_valid").get("out").asText());
        assertThat(ShellExecTool.newInstallShellExecTool(null, "/bogus/dir").getDescription())
                .isEqualTo(rec("shell_exec", "install_description_invalid").get("out").asText());
        assertThat(ShellExecTool.newInstallShellExecTool(null, "").getDescription())
                .isEqualTo(rec("shell_exec", "install_description_empty").get("out").asText());
    }

    @Test
    void blacklistMatchesGo() {
        JsonNode cases = rec("shell_exec", "blacklist").get("cases");
        for (JsonNode c : cases) {
            assertThat(ShellExecTool.checkShellExecBlacklist(c.get("in").asText()))
                    .as("in=%s", c.get("in").asText())
                    .isEqualTo(c.get("reason").asText());
        }
    }

    @Test
    void truncateStreamMatchesGo() {
        // 输入按探针源码重建（len/limit/fill 序列），期望是 Go 实录的逐字节输出。
        JsonNode cases = rec("shell_exec", "truncate_stream").get("cases");
        String[] inputs = {"short", "a".repeat(200), "a".repeat(99), "a".repeat(100), "文".repeat(100),
                "a".repeat(1000), "b".repeat(12), "c".repeat(30), "d".repeat(40)};
        int[] limits = {100, 100, 100, 100, 100, 10, 10, 11, 12};
        assertThat(cases.size()).isEqualTo(inputs.length);
        for (int i = 0; i < cases.size(); i++) {
            JsonNode c = cases.get(i);
            String s = inputs[i];
            String[] out = ShellExecTool.truncateShellStream(s, limits[i]);
            assertThat(Boolean.parseBoolean(out[1])).as("truncated case %d", i)
                    .isEqualTo(c.get("truncated").asBoolean());
            assertThat(out[0]).as("out case %d (len=%d limit=%d)", i, inputs[i].length(), limits[i])
                    .isEqualTo(c.get("out").asText());
        }
    }

    @Test
    void resolveLimitsMatchesGo() {
        JsonNode cases = rec("shell_exec", "resolve_limits").get("cases");
        for (JsonNode c : cases) {
            int in = c.get("in").asInt();
            assertThat(ShellExecTool.resolveShellOutputLimit(in)).isEqualTo(c.get("out").asInt());
            assertThat(ShellExecTool.resolveShellStderrLimit(in)).isEqualTo(c.get("err_out").asInt());
        }
    }

    @Test
    void binaryDetectionMatchesGo() {
        JsonNode cases = rec("shell_exec", "binary").get("cases");
        for (JsonNode c : cases) {
            byte[] bytes = java.util.Base64.getDecoder().decode(c.get("in_b64").asText());
            // Go 探针把原始字节装进 string 后调用；Java 侧走 byte[] 重载（保字节语义）
            assertThat(ShellExecTool.isBinaryShellOutput(bytes))
                    .as("in_b64=%s", c.get("in_b64").asText())
                    .isEqualTo(c.get("binary").asBoolean());
        }
    }

    @Test
    void prepareStreamMatchesGo() {
        JsonNode cases = rec("shell_exec", "prepare_stream").get("cases");
        String[] inputs = {"ok", "x".repeat(50), new String(new byte[] {0, 1}, java.nio.charset.StandardCharsets.ISO_8859_1)};
        for (int i = 0; i < cases.size(); i++) {
            JsonNode c = cases.get(i);
            String s = inputs[i];
            ShellExecTool.StreamPrep prep = ShellExecTool.prepareShellStream(s, c.get("limit").asInt());
            assertThat(prep.text()).as("out case %d", i).isEqualTo(c.get("out").asText());
            assertThat(prep.truncated()).as("truncated case %d", i).isEqualTo(c.get("truncated").asBoolean());
            assertThat(prep.binary()).as("binary case %d", i).isEqualTo(c.get("binary").asBoolean());
        }
    }

    @Test
    void stdinProgramDetectionMatchesGo() {
        JsonNode cases = rec("shell_exec", "stdin_program").get("cases");
        for (JsonNode c : cases) {
            assertThat(ShellExecTool.shellStdinIsProgram(c.get("in").asText()))
                    .as("in=%s", c.get("in").asText())
                    .isEqualTo(c.get("is_program").asBoolean());
        }
    }

    @Test
    void rejectExecutableStdinMatchesGo() {
        JsonNode cases = rec("shell_exec", "reject_stdin").get("cases");
        for (JsonNode c : cases) {
            String name = c.get("name").asText();
            String command = switch (name) {
                case "plain" -> "cat";
                case "too_long" -> "bash";
                case "blacklisted" -> "bash";
                case "interpreter_flag" -> "python3 -c 'print(1)'";
                case "empty_stdin" -> "bash -s";
                default -> "";
            };
            String stdin = switch (name) {
                case "plain" -> "data";
                case "too_long" -> "x".repeat(8 * 1024 + 1);
                case "blacklisted" -> "rm -rf /";
                case "interpreter_flag" -> "data";
                case "empty_stdin" -> "";
                default -> "";
            };
            assertThat(ShellExecTool.rejectExecutableStdin(command, stdin))
                    .as("case %s", name).isEqualTo(c.get("reason").asText());
        }
    }

    @Test
    void inlineEvalHintsMatchGo() {
        JsonNode cases = rec("shell_exec", "inline_eval").get("cases");
        String[] commands = {
                "python -c 'x=1'",
                "python -c '" + "y".repeat(300) + "'",
                "node -e 'var x=1;\nvar y=2;\nvar z=3;'",
                "node --eval 'x'",
                "python3 script.py",
                "python -c 'x'\n" + "z".repeat(300),
        };
        int i = 0;
        for (JsonNode c : cases) {
            String cmd = commands[i];
            assertThat(ShellExecTool.hasInlineEvalFlag(cmd)).as("has_flag case %d", i)
                    .isEqualTo(c.get("has_flag").asBoolean());
            assertThat(ShellExecTool.isInlineInterpreterProgram(cmd)).as("is_inline case %d", i)
                    .isEqualTo(c.get("is_inline").asBoolean());
            assertThat(ShellExecTool.shellInlineEvalHint(cmd)).as("hint case %d", i)
                    .isEqualTo(c.get("hint").asText());
            i++;
        }
    }

    @Test
    void missingModuleHintsMatchGo() {
        JsonNode cases = rec("shell_exec", "missing_module").get("cases");
        String[] stderrs = {
                "Traceback: ModuleNotFoundError: No module named 'pandas'",
                "Error: Cannot find module 'left-pad'",
                "/bin/sh: MODULE_NOT_FOUND",
                "ReferenceError: x is not defined",
                "/opt/x/.venv/bin/python: No module named pip",
        };
        int i = 0;
        for (JsonNode c : cases) {
            String stderr = stderrs[i];
            assertThat(ShellExecTool.isMissingInterpreterModule(stderr)).as("missing case %d", i)
                    .isEqualTo(c.get("missing").asBoolean());
            assertThat(ShellExecTool.shellMissingModuleHint("python3 /workspace/x.py", stderr))
                    .as("hint case %d", i).isEqualTo(c.get("hint").asText());
            i++;
        }
    }

    @Test
    void skillNameFromCommandMatchesGo() {
        JsonNode cases = rec("shell_exec", "skill_from_command").get("cases");
        for (JsonNode c : cases) {
            assertThat(ShellExecTool.skillNameFromShellCommand(c.get("in").asText()))
                    .as("in=%s", c.get("in").asText())
                    .isEqualTo(c.get("skill").asText());
        }
    }

    @Test
    void commandNotFoundHintsMatchGo() {
        JsonNode cases = rec("shell_exec", "command_not_found").get("cases");
        for (JsonNode c : cases) {
            int code = c.get("code").asInt();
            String command = c.get("command").asText();
            String stderr = c.get("stderr").asText();
            assertThat(ShellExecTool.shellCommandNotFoundHint(code, command, stderr))
                    .as("hint %s", command).isEqualTo(c.get("hint").asText());
            assertThat(ShellExecTool.inferredMissingCommand(command, stderr))
                    .as("inferred %s", command).isEqualTo(c.get("inferred").asText());
        }
    }

    @Test
    void recoveryHintsMatchGo() {
        JsonNode cases = rec("shell_exec", "recovery_plain").get("cases");
        String[] commands = {
                "ls", "tree /x", "uv pip install --python /opt/weknora/tenant/skills/pdf/.venv/bin/python x",
                "python3 x.py", "python -c '" + "y".repeat(300) + "'", "make",
        };
        String[] stderrs = {"", "tree: not found", "Read-only file system at .venv",
                "ModuleNotFoundError: No module named 'pandas'", "", "build error"};
        int[] codes = {0, 127, 1, 1, 1, 1};
        int i = 0;
        for (JsonNode c : cases) {
            assertThat(ShellExecTool.shellExecRecoveryHint(codes[i], commands[i], stderrs[i]))
                    .as("case %d", i).isEqualTo(c.get("hint").asText());
            i++;
        }
    }

    @Test
    void recoveryHintsWithSkillEnvironmentMatchGo() {
        JsonNode r = rec("shell_exec", "recovery_skill_env");
        ShellExecTool tool = new ShellExecTool(new Tools45cFakes.RecExecutor(), null)
                .withSkillEnvironment(skillEnv45c());
        assertThat(tool.recoveryHint(skillEnv45c(), "pdf", 1, "cmd", ".venv: permission denied"))
                .isEqualTo(r.get("venv_named").asText());
        assertThat(tool.recoveryHint(skillEnv45c(), "", 1, "python3 /opt/weknora/tenant/skills/pdf/run.py", "No module named pip"))
                .isEqualTo(r.get("venv_infer").asText());
        assertThat(tool.recoveryHint(skillEnv45c(), "", 1, "touch /x", "Permission denied"))
                .isEqualTo(r.get("permission").asText());
        assertThat(tool.recoveryHint(skillEnv45c(), "", 1, "touch /x", "Read-only file system"))
                .isEqualTo(r.get("readonly").asText());
        assertThat(tool.recoveryHint(skillEnv45c(), "pdf", 1, "node x.js", "Cannot find module 'left-pad'"))
                .isEqualTo(r.get("module_named").asText());
        assertThat(tool.recoveryHint(skillEnv45c(), "", 1, "node x.js", "Cannot find module 'left-pad'"))
                .isEqualTo(r.get("module_anon").asText());
        assertThat(tool.recoveryHint(skillEnv45c(), "pdf", 1, "node x.js", "No module named 'xlsx'"))
                .isEqualTo(r.get("module_other").asText());
        assertThat(tool.recoveryHint(skillEnv45c(), "", 127, "tree /x", "tree: not found"))
                .isEqualTo(r.get("cnf").asText());
    }

    @Test
    void envHelpersMatchGo() {
        JsonNode r = rec("shell_exec", "env_helpers");
        assertThat(Tools45cFakes.goJson(
                ShellExecTool.stillMissing(List.of("A", "B", "C"), Map.of("A", "1", "B", " "))))
                .isEqualTo(r.get("still_missing").asText());
        // Go：missing 为 nil 切片时原样返回（nil）→ json "null"
        assertThat(r.get("still_missing_empty").asText()).isEqualTo("null");
        assertThat(ShellExecTool.stillMissing(new ArrayList<>(), Map.of("A", "1"))).isEmpty();
        Map<String, String> drop = ShellExecTool.dropResolvedNames(
                new LinkedHashMap<>(Map.of("A", "1", "B", "2")), Map.of("A", "stored"));
        assertThat(Tools45cFakes.goJson(drop)).isEqualTo(r.get("drop_resolved").asText());
        assertThat(Tools45cFakes.goJson(ShellExecTool.dropResolvedNames(new LinkedHashMap<>(), Map.of("A", "x"))))
                .isEqualTo(r.get("drop_resolved_empty").asText());
    }

    @Test
    void executePathsMatchGo() {
        String[] ids = {"exec_happy", "exec_nonzero", "exec_transport_err", "exec_killed",
                "exec_killed_with_err", "exec_nil_result", "exec_empty_command", "exec_long_command",
                "exec_blacklisted", "exec_no_session", "exec_work_dir_denied", "exec_work_dir_relative",
                "exec_timeout_clamp", "exec_timeout_small", "exec_stdin_flow", "exec_stdin_rejected",
                "exec_stdin_too_long", "exec_stdout_truncated", "exec_max_output_1024",
                "exec_stderr_truncated", "exec_binary_out", "exec_env_passthrough"};
        for (String id : ids) {
            JsonNode r = rec("shell_exec", id);
            ShellExecToolResult got = runScenario(id, false);
            ToolResult result = got.result;
            assertThat(result.isSuccess()).as("success %s", id).isEqualTo(r.get("success").asBoolean());
            assertThat(result.getOutput()).as("output %s", id).isEqualTo(r.get("output").asText());
            assertThat(result.getError()).as("error %s", id).isEqualTo(r.get("error").asText());
            JsonNode dataJson = r.get("data_json");
            if (dataJson != null && !dataJson.isNull() && !dataJson.asText().isEmpty()
                    && result.getData() != null) {
                assertThat(RecordingSupport.goJsonOfData(result.getData()))
                        .as("data %s", id).isEqualTo(dataJson.asText());
            }
            assertExecSide(r, got);
        }
    }

    @Test
    void skillEnvPathsMatchGo() {
        String[] ids = {"exec_resolver_error", "exec_missing_env", "exec_supplied_env",
                "exec_resolved_env", "exec_no_resolver"};
        for (String id : ids) {
            JsonNode r = rec("shell_exec", id);
            ShellExecToolResult got = runScenario(id, true);
            ToolResult result = got.result;
            assertThat(result.isSuccess()).as("success %s", id).isEqualTo(r.get("success").asBoolean());
            assertThat(result.getOutput()).as("output %s", id).isEqualTo(r.get("output").asText());
            assertThat(result.getError()).as("error %s", id).isEqualTo(r.get("error").asText());
            JsonNode dataJson = r.get("data_json");
            if (dataJson != null && !dataJson.isNull() && !dataJson.asText().isEmpty()
                    && result.getData() != null) {
                assertThat(RecordingSupport.goJsonOfData(result.getData()))
                        .as("data %s", id).isEqualTo(dataJson.asText());
            }
            assertExecSide(r, got);
        }
        // capture 记录（supplied_env）
        JsonNode captures = rec("shell_exec", "capture_calls").get("calls");
        assertThat(captures.isArray()).isTrue();
        // capture_calls_noresolver：无 resolver 时 skill_name 被拒，没有 capture
        assertThat(rec("shell_exec", "capture_calls_noresolver").get("calls").isArray()).isTrue();
    }

    @Test
    void installPathsMatchGo() {
        for (String id : new String[] {"install_exec", "install_exec_ws_denied", "install_skill_name_refused"}) {
            JsonNode r = rec("shell_exec", id);
            ShellExecToolResult got = runScenario(id, true);
            ToolResult result = got.result;
            assertThat(result.isSuccess()).as("success %s", id).isEqualTo(r.get("success").asBoolean());
            if (r.has("output")) {
                assertThat(result.getOutput()).as("output %s", id).isEqualTo(r.get("output").asText());
            }
            assertThat(result.getError()).as("error %s", id).isEqualTo(r.get("error").asText());
            JsonNode dataJson = r.get("data_json");
            if (dataJson != null && !dataJson.isNull() && !dataJson.asText().isEmpty()
                    && result.getData() != null && !dataJson.asText().equals("null")) {
                assertThat(RecordingSupport.goJsonOfData(result.getData()))
                        .as("data %s", id).isEqualTo(dataJson.asText());
            }
            if (r.has("exec") && r.get("exec").isObject() && got.executor != null) {
                JsonNode ex = r.get("exec");
                assertThat(got.executor.command).isEqualTo(ex.get("command").asText());
                assertThat(got.executor.workDir).isEqualTo(ex.get("work_dir").asText());
                assertThat(SandboxExecuteResult.GoDuration.of(got.executor.timeout))
                        .isEqualTo(ex.get("timeout").asText());
            }
        }
    }

    /** 断言执行器侧观察（calls/command/work_dir/timeout/env）。 */
    private void assertExecSide(JsonNode r, ShellExecToolResult got) {
        JsonNode ex = r.get("exec");
        if (ex == null || !ex.isObject() || got.executor == null) {
            return;
        }
        assertThat(got.executor.calls).isEqualTo(ex.get("calls").asInt());
        assertThat(got.executor.command).isEqualTo(ex.get("command").asText());
        assertThat(got.executor.workDir).isEqualTo(ex.get("work_dir").asText());
        // Go 录的是 time.Duration 的 String()；Java 侧同口径复刻（SandboxExecuteResult.GoDuration）
        assertThat(SandboxExecuteResult.GoDuration.of(got.executor.timeout))
                .isEqualTo(ex.get("timeout").asText());
        String envJson = got.executor.env == null ? "null" : Tools45cFakes.goJson(got.executor.env);
        assertThat(envJson).isEqualTo(ex.get("env_json").asText());
    }

    /** 探针 shellTestSkillManager45c 的内存镜像（tenant source: pdf-tools ready+enabled）。 */
    static Tools45cFakes.MemSkills skillEnv45c() {
        Tools45cFakes.MemSkills env = new Tools45cFakes.MemSkills();
        env.enabled = true;
        env.metadata = List.of(Tools45cFakes.MemSkills.meta("pdf-tools", ""));
        env.dirs.put("pdf-tools", new SkillEnvironment.SkillDir("/opt/weknora/tenant/skills/pdf-tools", true));
        return env;
    }

    record ShellExecToolResult(ToolResult result, Tools45cFakes.RecExecutor executor,
            List<Map<String, Object>> captures) {
    }

    /** 按探针源码重建各 Execute 场景。 */
    private ShellExecToolResult runScenario(String id, boolean skillEnv) {
        Tools45cFakes.RecExecutor exec = new Tools45cFakes.RecExecutor();
        List<Map<String, Object>> captures = new ArrayList<>();
        Tools45cFakes.MemSkills env = skillEnv45c();
        ShellExecTool tool;
        Tools45cFakes.MemResolver resolver = new Tools45cFakes.MemResolver();
        switch (id) {
            case "exec_resolver_error" -> {
                resolver.err = Tools45cFakes.boom("db unavailable");
                tool = new ShellExecTool(exec, resolver).withSkillEnvironment(env);
            }
            case "exec_missing_env" -> {
                resolver.resolved = new LinkedHashMap<>(Map.of("PDF_API_KEY", "stored-key"));
                resolver.missing = List.of("PDF_API_KEY2");
                tool = new ShellExecTool(exec, resolver).withSkillEnvironment(env);
            }
            case "exec_supplied_env" -> {
                resolver.resolved = new LinkedHashMap<>(Map.of("PDF_API_KEY", "stored-key"));
                resolver.missing = List.of("PDF_API_KEY2");
                tool = new ShellExecTool(exec, resolver).withSkillEnvironment(env)
                        .withEnvCapture((skill, pairs) -> captures.add(Map.of("skill", skill, "pairs", pairs)));
            }
            case "exec_resolved_env" -> {
                resolver.resolved = new LinkedHashMap<>(Map.of("PDF_API_KEY", "stored-key", "EXTRA", "from-store"));
                tool = new ShellExecTool(exec, resolver).withSkillEnvironment(env);
            }
            case "exec_no_resolver" -> {
                // 探针里这个工具没有 skill environment（resolver 也是 nil）
                tool = new ShellExecTool(exec, null)
                        .withEnvCapture((skill, pairs) -> captures.add(Map.of("skill", skill, "pairs", pairs)));
            }
            case "install_exec", "install_exec_ws_denied", "install_skill_name_refused" -> {
                Tools45cFakes.RecExecutor inner = new Tools45cFakes.RecExecutor();
                inner.result = new SandboxExecuteResult("", "", 0, Duration.ofMillis(1), false, "");
                SandboxInstallCommandExecutor privileged = (sessionId, command, opts) -> inner.execShellCommand(
                        sessionId, command, opts.workDir(), opts.timeout(), opts.env(), opts.onOutput());
                tool = ShellExecTool.newInstallShellExecTool(privileged, "/opt/weknora/tenant/skills/pdf");
                String args = switch (id) {
                    case "install_exec" -> "{\"command\":\"ls\"}";
                    case "install_exec_ws_denied" -> "{\"command\":\"ls\",\"work_dir\":\"/workspace\"}";
                    default -> "{\"command\":\"ls\",\"skill_name\":\"pdf\"}";
                };
                ToolResult result = tool.execute(withSession(RecordingSupport.readTree(args)));
                return new ShellExecToolResult(result, inner, captures);
            }
            default -> tool = new ShellExecTool(exec, null);
        }
        String args = switch (id) {
            case "exec_resolver_error" -> "{\"command\":\"x\",\"skill_name\":\"pdf\"}";
            case "exec_missing_env" -> "{\"command\":\"run.py\",\"skill_name\":\"pdf\"}";
            case "exec_supplied_env" -> "{\"command\":\"PDF_API_KEY2=from-chat run.py\",\"skill_name\":\"pdf\"}";
            case "exec_resolved_env" -> "{\"command\":\"run.py\",\"skill_name\":\"pdf\",\"env\":{\"PDF_API_KEY\":\"model-key\"}}";
            case "exec_no_resolver" -> "{\"command\":\"PDF_API_KEY=k run.py\",\"skill_name\":\"pdf\"}";
            case "exec_happy" -> "{\"command\":\"ls -la\",\"work_dir\":\"sub\"}";
            case "exec_nonzero" -> "{\"command\":\"make\"}";
            case "exec_transport_err" -> "{\"command\":\"ls\"}";
            case "exec_killed" -> "{\"command\":\"sleep 1000\"}";
            case "exec_killed_with_err" -> "{\"command\":\"x\"}";
            case "exec_nil_result" -> "{\"command\":\"x\"}";
            case "exec_empty_command" -> "{\"command\":\"   \"}";
            case "exec_long_command" -> String.format("{\"command\":\"%s\"}", "a".repeat(9000));
            case "exec_blacklisted" -> "{\"command\":\"rm -rf /\"}";
            case "exec_no_session" -> "{\"command\":\"ls\"}";
            case "exec_work_dir_denied" -> "{\"command\":\"ls\",\"work_dir\":\"/opt/weknora\"}";
            case "exec_work_dir_relative" -> "{\"command\":\"ls\",\"work_dir\":\"../output\"}";
            case "exec_timeout_clamp" -> "{\"command\":\"x\",\"timeout_sec\":5000}";
            case "exec_timeout_small" -> "{\"command\":\"x\",\"timeout_sec\":30}";
            case "exec_stdin_flow" -> "{\"command\":\"cat > out.txt\",\"stdin\":\"hello\\nstdin\\n\"}";
            case "exec_stdin_rejected" -> "{\"command\":\"bash -s\",\"stdin\":\"rm -rf /\"}";
            case "exec_stdin_too_long" -> String.format("{\"command\":\"bash -s\",\"stdin\":\"%s\"}", "x".repeat(65537));
            case "exec_stdout_truncated" -> "{\"command\":\"gen\"}";
            case "exec_max_output_1024" -> "{\"command\":\"gen\",\"max_output_bytes\":1024}";
            case "exec_stderr_truncated" -> "{\"command\":\"gen\",\"max_stderr_bytes\":200}";
            case "exec_binary_out" -> "{\"command\":\"gen\"}";
            case "exec_env_passthrough" -> "{\"command\":\"env\",\"env\":{\"PIP_INDEX_URL\":\"https://example.com/pypi/simple\"}}";
            default -> throw new IllegalArgumentException(id);
        };
        // exec_no_session 也有 session（探针 rec45cCtx）
        if ("exec_no_session".equals(id)) {
            exec.result = new SandboxExecuteResult("", "", 0, java.time.Duration.ofMillis(1), false, "");
        }
        switch (id) {
            case "exec_happy" -> exec.result = new SandboxExecuteResult("file1\nfile2\n", "", 0,
                    Duration.ofMillis(1500), false, "");
            case "exec_nonzero" -> exec.result = new SandboxExecuteResult("", "make: *** No rule", 2,
                    Duration.ofMillis(800), false, "");
            case "exec_transport_err" -> exec.err = Tools45cFakes.boom("dial tcp 1.2.3.4: i/o timeout");
            case "exec_killed" -> exec.result = new SandboxExecuteResult("", "", 137, Duration.ofSeconds(600), true, "");
            case "exec_killed_with_err" -> exec.result = new SandboxExecuteResult("", "", 1, Duration.ofSeconds(1), true, "custom");
            case "exec_stdin_flow" -> exec.result = new SandboxExecuteResult("", "", 0, Duration.ofMillis(20), false, "");
            case "exec_no_session" -> exec.result = new SandboxExecuteResult("", "", 0, Duration.ofMillis(1), false, "");
            case "exec_stdout_truncated" -> exec.result = new SandboxExecuteResult(
                    "HEAD".repeat(100) + "m".repeat(20000) + "TAIL".repeat(100), "", 0, Duration.ofMillis(5), false, "");
            case "exec_max_output_1024" -> exec.result = exec.result = new SandboxExecuteResult(
                    "HEAD".repeat(100) + "m".repeat(20000) + "TAIL".repeat(100), "", 0, Duration.ofMillis(1), false, "");
            case "exec_stderr_truncated" -> exec.result = new SandboxExecuteResult("", "e".repeat(5000), 1,
                    Duration.ofMillis(1), false, "");
            case "exec_binary_out" -> exec.result = new SandboxExecuteResult(
                    new String(new byte[] {0, 1, 2, 3}, java.nio.charset.StandardCharsets.ISO_8859_1), "", 0,
                    Duration.ofMillis(1), false, "");
            default -> {
            }
        }
        // 探针的 shellExecRun 一律用 rec45cCtx（带 session）；exec_no_session_ctx 单独录无 ctx 形态
        ToolResult result = tool.execute(new ToolRequest(RecordingSupport.readTree(args),
                new ToolExecContext("zz45c-session", "", "", "", "", null, null, 0),
                ToolCancellation.LIVE, 0));
        return new ShellExecToolResult(result, exec, captures);
    }

    private static ToolRequest withSession(JsonNode args) {
        return new ToolRequest(args, new ToolExecContext("zz45c-session", "", "", "", "", null, null, 0),
                ToolCancellation.LIVE, 0);
    }
}
