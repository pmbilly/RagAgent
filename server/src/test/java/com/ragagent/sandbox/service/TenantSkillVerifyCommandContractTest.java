package com.ragagent.sandbox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ragagent.agent.tools.SandboxExecuteResult;
import com.ragagent.agent.tools.SandboxPaths;
import com.ragagent.sandbox.service.SkillBundleParser.SkillBundle;

/**
 * verify 族命令构造的<b>字节契约</b>：期望值是 2026-09-21 用 `go test -overlay`
 * 探针从 Go 仓录出的原始字符串（§9.1 录实录法），fixture 在
 * {@code contracts/w5k-probe-commands.tsv}。python 命令里 base64 的全文也一并钉住——
 * 它同时证明 Java 资源 {@code sandbox/tenant_skill_verify.py} 与 Go go:embed 的文件
 * <b>逐字节相同</b>（两侧各自 base64 后相等是跨仓字节同一性的实测证明）。
 */
class TenantSkillVerifyCommandContractTest {

    private static final String DIR = "/opt/weknora/tenant/skills/pdf-tools";

    static Map<String, String> fixtures() {
        try (var in = TenantSkillVerifyCommandContractTest.class
                .getResourceAsStream("/contracts/w5k-probe-commands.tsv")) {
            if (in == null) {
                throw new IllegalStateException("w5k-probe-commands.tsv fixture missing");
            }
            Map<String, String> map = new HashMap<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    map.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
            return map;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertFixture(Map<String, String> fx, String key, String actual) {
        assertEquals(fx.get(key), actual, "byte contract mismatch: " + key);
    }

    @Test
    void treeCommand() {
        String actual = TenantSkillVerifier.skillTreeVerifyCommand(DIR, List.of(
                "scripts/helper.js", "scripts/run.py", "setup.sh", "tools/fix.sh", "app.js",
                "lib/util.mjs"));
        assertFixture(fixtures(), "tree", actual);
    }

    @Test
    void treeCommandNoScripts() {
        assertFixture(fixtures(), "tree_none",
                TenantSkillVerifier.skillTreeVerifyCommand(DIR, List.of()));
    }

    @Test
    void pythonCommand() {
        assertFixture(fixtures(), "python", TenantSkillVerifier.skillPythonVerifyCommand(DIR,
                List.of("scripts/run.py", "app.js"),
                List.of("tests/conftest.py", "examples/demo.py")));
    }

    @Test
    void pythonCommandNoAuxiliary() {
        assertFixture(fixtures(), "python_noaux", TenantSkillVerifier.skillPythonVerifyCommand(
                DIR, List.of("scripts/run.py"), List.of()));
    }

    @Test
    void nodeCommand() {
        assertFixture(fixtures(), "node", TenantSkillVerifier.skillNodeVerifyCommand(DIR,
                List.of("app.js", "lib/util.mjs"), List.of("lodash", "left-pad")));
    }

    @Test
    void nodeCommandNoDeps() {
        assertFixture(fixtures(), "node_nodeps", TenantSkillVerifier.skillNodeVerifyCommand(
                DIR, List.of("app.js"), List.of()));
    }

    @Test
    void shellCommand() {
        assertFixture(fixtures(), "shell", TenantSkillVerifier.skillShellVerifyCommand(DIR,
                List.of("setup.sh", "tools/fix.sh")));
    }

    @Test
    void forEachScriptQuoting() {
        assertFixture(fixtures(), "forEach", TenantSkillVerifier.forEachScript(DIR,
                List.of("a b.py", "c.py"), "node --check \"$f\""));
    }

    @Test
    void shellQuote() {
        assertFixture(fixtures(), "quote", SandboxPaths.shellQuote("/tmp/skill with 'quotes'"));
    }

    @Test
    void runtimeCommandsCommand() {
        // Go 侧经 fixture manager 捕获（verifyRuntimePrerequisites 内联构造）；
        // Java 侧用记录执行器 + 报告 reader 走同一个入口。
        Map<String, byte[]> files = Map.of(
                DIR + "/.weknora/install-report.json",
                "{\"commands\":[\"bsk\",\"second.tool-2\"],\"blockers\":[]}"
                        .getBytes(StandardCharsets.UTF_8));
        RecordingExecutor exec = new RecordingExecutor();
        assertNull(TenantSkillVerifier.verifyRuntimePrerequisites(exec,
                new MapReader(files), "session-1", DIR));
        assertEquals(1, exec.commands.size());
        assertFixture(fixtures(), "runtime_cmds", exec.commands.get(0));
    }

    @Test
    void gateErrorRenderings() {
        assertFixture(fixtures(), "gate_err_problems", new SkillVerificationException(
                "python", true, List.of("p1", "p2"), "").getMessage());
        assertFixture(fixtures(), "gate_err_summary", new SkillVerificationException(
                "shell", false, List.of(), "exit 1").getMessage());
    }

    @Test
    void describeExecFailureRenderings() {
        assertFixture(fixtures(), "execfail_zero", TenantSkillVerifier.describeExecFailure(
                new SandboxExecuteResult("", "", 0, null, false, "")));
        assertFixture(fixtures(), "execfail_stderr", TenantSkillVerifier.describeExecFailure(
                new SandboxExecuteResult("", "boom\n", 1, null, false, "")));
        assertFixture(fixtures(), "execfail_killed", TenantSkillVerifier.describeExecFailure(
                new SandboxExecuteResult("", "", -1, null, true, "context deadline exceeded")));
        assertFixture(fixtures(), "execfail_nil", TenantSkillVerifier.describeExecFailure(null));
        assertFixture(fixtures(), "execfail_all", TenantSkillVerifier.describeExecFailure(
                new SandboxExecuteResult("", " line ", 2, null, true, " err ")));
    }

    @Test
    void sortedScriptPathsBySuffix() {
        SkillBundle bundle = probeBundle();
        assertEquals(List.of("BENCH/m.py", "benchmark/n.py", "doc/d.py", "docs/guide.py",
                "fixtures/f.py", "lib/x_test.py", "plain.py", "scripts/run.py", "setup.py",
                "testing/tt.py", "tests/conftest.py"),
                TenantSkillVerifier.sortedScriptPaths(bundle, ".py"));
        assertEquals(List.of("__tests__/t.js", "app.js", "example/e.js", "examples/es.js",
                "sample/s.js", "test_e2e.js"),
                TenantSkillVerifier.sortedScriptPaths(bundle, ".js", ".mjs", ".cjs"));
        assertEquals(List.of("setup.sh"), TenantSkillVerifier.sortedScriptPaths(bundle, ".sh"));
        assertEquals(List.of("BENCH/m.py", "__tests__/t.js", "app.js", "benchmark/n.py",
                "doc/d.py", "docs/guide.py", "example/e.js", "examples/es.js", "fixtures/f.py",
                "lib/x_test.py", "plain.py", "sample/s.js", "scripts/run.py", "setup.py",
                "setup.sh", "test_e2e.js", "testing/tt.py", "tests/conftest.py"),
                TenantSkillVerifier.sortedScriptPaths(bundle, ".py", ".js", ".mjs", ".cjs", ".sh"));
        assertTrue(TenantSkillVerifier.sortedScriptPaths(null, ".py").isEmpty(),
                "nil bundle → 空名单");
    }

    @Test
    void auxiliaryScriptNamingConventions() {
        String[][] expected = {
                {"tests/conftest.py", "true"},
                {"TESTS/x.py", "true"},
                {"scripts/run.py", "false"},
                {"setup.py", "true"},
                {"test_x.py", "true"},
                {"x_test.js", "true"},
                {"docs/guide.py", "true"},
                {"bench/b.py", "false"},
                {"benchmark/n.py", "true"},
                {"plain.py", "false"},
                {"conftest.py", "true"},
                {"deep/nested/Tests/a.py", "true"}};
        for (String[] e : expected) {
            assertEquals(Boolean.parseBoolean(e[1]),
                    TenantSkillVerifier.skillAuxiliaryScript(e[0]), e[0]);
        }
    }

    @Test
    void splitAuxiliaryScriptsKeepsCallerOrder() {
        var split = TenantSkillVerifier.splitAuxiliaryScripts(
                List.of("b.py", "tests/t.py", "a.py", "test_x.py"));
        assertEquals(List.of("b.py", "a.py"), split.entry());
        assertEquals(List.of("tests/t.py", "test_x.py"), split.auxiliary());
    }

    @Test
    void nodeDependencyNamesSortedDevExcludedUnreadableEmpty() {
        SkillBundle bundle = probeBundle();
        assertEquals(List.of("a", "b"), TenantSkillVerifier.nodeDependencyNames(bundle));
        SkillBundle bad = new SkillBundle();
        bad.files.put("package.json", "{oops".getBytes(StandardCharsets.UTF_8));
        assertTrue(TenantSkillVerifier.nodeDependencyNames(bad).isEmpty(),
                "读不了的 package.json 不是校验失败的理由");
        SkillBundle devOnly = new SkillBundle();
        devOnly.files.put("package.json",
                "{\"devDependencies\":{\"x\":\"1\"}}".getBytes(StandardCharsets.UTF_8));
        assertTrue(TenantSkillVerifier.nodeDependencyNames(devOnly).isEmpty(),
                "devDependencies 排除");
        assertTrue(TenantSkillVerifier.nodeDependencyNames(null).isEmpty());
    }

    @Test
    void notesKeepDuplicatesProblemsDropBlanks() {
        assertEquals(List.of("first note", "second note", "first note"),
                TenantSkillVerifier.verificationNotes(
                        "ok line\nnote: first note\nnote: second note\n\nnote: first note\n"));
        assertEquals(List.of("err one", "err two"),
                TenantSkillVerifier.verificationProblems("err one\n\n  err two  \n\n"));
    }

    @Test
    void bundleHasDependencies() {
        SkillBundle bundle = probeBundle();
        assertTrue(TenantSkillVerifier.bundleHasPythonDeps(bundle));
        assertTrue(TenantSkillVerifier.bundleHasNodeDeps(bundle));
        assertFalse(TenantSkillVerifier.bundleHasPythonDeps(null));
        assertFalse(TenantSkillVerifier.bundleHasNodeDeps(null));
    }

    /** 探针用的 bundle 固定输入（22 个文件）。 */
    private static SkillBundle probeBundle() {
        SkillBundle bundle = new SkillBundle();
        List<String> pairs = List.of(
                "SKILL.md", "# x\n",
                "scripts/run.py", "x=1",
                "tests/conftest.py", "y=1",
                "app.js", "z=1",
                "setup.sh", ":",
                "package.json", "{\"dependencies\":{\"b\":\"1.0\",\"a\":\"2.0\"},\"devDependencies\":{\"zz\":\"3.0\"}}",
                "requirements.txt", "pandas\n",
                "pyproject.toml", "[project]\n",
                "docs/guide.py", "q=1",
                "test_e2e.js", "w=1",
                "lib/x_test.py", "v=1",
                "setup.py", "s=1",
                "BENCH/m.py", "m=1",
                "benchmark/n.py", "n=1",
                "fixtures/f.py", "f=1",
                "__tests__/t.js", "t=1",
                "sample/s.js", "s=1",
                "testing/tt.py", "tt=1",
                "example/e.js", "e=1",
                "examples/es.js", "es=1",
                "doc/d.py", "d=1",
                "plain.py", "p=1");
        for (int i = 0; i < pairs.size(); i += 2) {
            bundle.files.put(pairs.get(i), pairs.get(i + 1).getBytes(StandardCharsets.UTF_8));
        }
        return bundle;
    }

    /** 记录命令、恒成功的执行器（树检查/依赖检查全过的形态）。 */
    static final class RecordingExecutor implements com.ragagent.agent.tools.SandboxInstallCommandExecutor {
        final List<String> commands = new ArrayList<>();
        int exitCode;
        String stdout = "";
        String stderr = "";

        @Override
        public SandboxExecuteResult execShellCommandWithOptions(String sessionId,
                String command, com.ragagent.agent.tools.ShellExecOptions opts) {
            commands.add(command);
            return new SandboxExecuteResult(stdout, stderr, exitCode, java.time.Duration.ZERO,
                    false, "");
        }
    }

    /** map 支撑的会话文件读取器；缺席文件按 Go fixture 的文案抛出。 */
    static final class MapReader implements TenantSkillVerifier.SessionFileReader {
        private final Map<String, byte[]> files;

        MapReader(Map<String, byte[]> files) {
            this.files = files;
        }

        @Override
        public byte[] readSessionFile(String sessionId, String path) throws IOException {
            byte[] raw = files.get(path);
            if (raw == null) {
                throw new java.io.FileNotFoundException("file does not exist");
            }
            return raw;
        }
    }
}
