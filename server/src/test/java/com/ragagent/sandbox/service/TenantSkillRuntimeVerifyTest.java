package com.ragagent.sandbox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ragagent.agent.tools.SandboxExecuteResult;
import com.ragagent.agent.tools.SandboxFileSource;
import com.ragagent.agent.tools.SandboxInstallCommandExecutor;
import com.ragagent.agent.tools.ShellExecOptions;
import com.ragagent.sandbox.service.SkillBundleParser.SkillBundle;
import com.ragagent.sandbox.service.TenantSkillVerifier.VerifyResult;

/**
 * 运行时前提门的<b>行为</b>验收（对照 Go {@code tenant_skill_runtime_verify_test.go}）：
 * 校验器实际发出的 shell 串在这里由本机 {@code /bin/bash --noprofile --norc -c}
 * 真执行（Go 的 runtimeProbeManager 同法），引号、PATH 前缀（SkillCommandPath）与
 * command -v 语义都是被测对象。报告校验阶梯的六个形态与 Go 的用例一一对应。
 */
class TenantSkillRuntimeVerifyTest {

    private static final String INSTALL_SKILL_DIR = "/opt/weknora/tenant/skills/pdf-tools";

    /** 对照 Go runtimeProbeManager：真跑 bash，合并输出进 stderr。 */
    static final class BashProbeExecutor implements SandboxInstallCommandExecutor {
        @Override
        public SandboxExecuteResult execShellCommandWithOptions(String sessionId,
                String command, ShellExecOptions opts) throws IOException {
            try {
                Process p = new ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c",
                        command).redirectErrorStream(true).start();
                byte[] output = p.getInputStream().readAllBytes();
                assertTrue(p.waitFor(30, TimeUnit.SECONDS), "probe command hung");
                int code = p.exitValue();
                return new SandboxExecuteResult("", new String(output, StandardCharsets.UTF_8),
                        code, Duration.ZERO, false, "");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static Map<String, byte[]> filesOf(String path, String content) {
        Map<String, byte[]> files = new HashMap<>();
        files.put(path, content.getBytes(StandardCharsets.UTF_8));
        return files;
    }

    /** 对照 TestRuntimePrerequisitesMarkdownOnlySkillCannotSkipReport：纯文档 skill 逃不过报告门。 */
    @Test
    void markdownOnlySkillCannotSkipReport() {
        SkillBundle bundle = new SkillBundle();
        bundle.files.put("SKILL.md",
                "Requires the bsk CLI and browser extension.".getBytes(StandardCharsets.UTF_8));
        VerifyResult result = TenantSkillVerifier.verifySkill(new TenantSkillVerifyCommandContractTest.RecordingExecutor(),
                new TenantSkillVerifyCommandContractTest.MapReader(Map.of()), "session",
                INSTALL_SKILL_DIR, bundle);
        SkillVerificationException gate = assertInstanceOf(SkillVerificationException.class,
                result.error());
        assertTrue(gate.repairable);
        assertEquals(TenantSkillVerifyCommandContractTest.fixtures().get("verify_md_only"),
                gate.getMessage(), "与 Go 实录的完整错误串逐字节一致（含 reader 的 file does not exist）");
    }

    /** 对照 TestRuntimePrerequisiteReportValidation：六形态全是 repairable 的门错误。 */
    @Test
    void reportValidationLadder() {
        List<String> payloads = List.of(
                "{}",
                "{\"commands\":null,\"blockers\":[]}",
                "{\"commands\":[],\"blockers\":null}",
                "{\"commands\":[\"bsk; touch bad\"],\"blockers\":[]}",
                "{\"commands\":[\"/root/.local/bin/bsk\"],\"blockers\":[]}",
                "{\"commands\":[],\"blockers\":[\"\"]}");
        List<String> wantLiterals = List.of(
                "Write a valid .weknora/install-report.json",
                "Write a valid .weknora/install-report.json",
                "Write a valid .weknora/install-report.json",
                "commands must contain bare executable names, without paths or arguments",
                "commands must contain bare executable names, without paths or arguments",
                "blockers must contain non-empty explanations");
        for (int i = 0; i < payloads.size(); i++) {
            String reportPath = INSTALL_SKILL_DIR + "/.weknora/install-report.json";
            Exception err = TenantSkillVerifier.verifyRuntimePrerequisites(new TenantSkillVerifyCommandContractTest.RecordingExecutor(),
                    new TenantSkillVerifyCommandContractTest.MapReader(filesOf(reportPath, payloads.get(i))), "session",
                    INSTALL_SKILL_DIR);
            SkillVerificationException gate = assertInstanceOf(SkillVerificationException.class,
                    err, "payload " + payloads.get(i));
            assertTrue(gate.repairable, "payload " + payloads.get(i));
            assertEquals("runtime prerequisites", gate.language, "payload " + payloads.get(i));
            assertTrue(gate.getMessage().contains(wantLiterals.get(i)),
                    "payload " + payloads.get(i) + " → " + gate.getMessage());
        }
    }

    /** 对照 TestRuntimePrerequisitesResolveSkillLocalCLI：skill 本地 bin 的 PATH 解析是真 shell 语义。 */
    @Test
    void runtimePrerequisitesResolveSkillLocalCli(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("skill with ' quotes");
        Path binDir = dir.resolve(".weknora/bin");
        Files.createDirectories(binDir);
        Map<String, byte[]> files = new HashMap<>(filesOf(
                dir.resolve(".weknora/install-report.json").toString(),
                "{\"commands\":[\"weknora-test-cli\"],\"blockers\":[]}"));

        Exception err = TenantSkillVerifier.verifyRuntimePrerequisites(new BashProbeExecutor(),
                new TenantSkillVerifyCommandContractTest.MapReader(files), "session", dir.toString());
        SkillVerificationException gate = assertInstanceOf(SkillVerificationException.class, err);
        assertTrue(gate.repairable);
        assertTrue(gate.getMessage().contains("weknora-test-cli"));

        Path cli = binDir.resolve("weknora-test-cli");
        Files.write(cli, "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertNull(TenantSkillVerifier.verifyRuntimePrerequisites(new BashProbeExecutor(),
                new TenantSkillVerifyCommandContractTest.MapReader(files), "session", dir.toString()),
                "命令落进 .weknora/bin 后必须被 PATH 前缀解析到");
    }

    /** 对照 TestRunInstallDoesNotSnapshotUnresolvedExternalPrerequisites 的门判定段：外部 blocker 恒不可修。 */
    @Test
    void externalBlockerIsNotRepairable() {
        String reportPath = INSTALL_SKILL_DIR + "/.weknora/install-report.json";
        Exception err = TenantSkillVerifier.verifyRuntimePrerequisites(new TenantSkillVerifyCommandContractTest.RecordingExecutor(),
                new TenantSkillVerifyCommandContractTest.MapReader(filesOf(reportPath,
                        "{\"commands\":[\"bsk\"],\"blockers\":[\"The browser extension cannot reach the remote sandbox daemon.\"]}")),
                "session", INSTALL_SKILL_DIR);
        SkillVerificationException gate = assertInstanceOf(SkillVerificationException.class, err);
        assertFalse(gate.repairable, "外部 blocker 再多轮安装也修不了");
        assertTrue(gate.getMessage().contains("browser extension"));
        assertTrue(gate.getMessage().startsWith("runtime prerequisites verification failed: "
                + "Unresolved runtime prerequisites: "));
    }
}
