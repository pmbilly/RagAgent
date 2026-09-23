package com.ragagent.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ragagent.agent.AgentConfig;
import com.ragagent.sandbox.domain.CubeSandboxConfig;
import com.ragagent.sandbox.domain.SkillImageConfig;
import com.ragagent.sandbox.domain.TenantSandboxConfig;
import com.ragagent.sandbox.domain.TenantSandboxConfigEntity;
import com.ragagent.sandbox.domain.TenantSkillSnapshotEntity;
import com.ragagent.sandbox.service.SkillBundleParser.SkillBundle;
import com.ragagent.sandbox.service.SkillInstallPipelineImpl.QaLikeConfig;
import com.ragagent.sandbox.service.SkillInstallPipelineImpl.Manifest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * install 管线的确定性面（对照 Go tenant_skill_install_test.go 的纯逻辑可测子集：
 * 命令构造 / prompt 构造 / tar 播种 / 快照命名与代次 / agent 配置 / manifest）。
 * 无 provider、无 DB、无 LLM。
 */
class SkillInstallPipelineStaticTest {

    // ── 命令构造（发给沙箱的 shell 串是字节契约） ─────────────────────────

    @Test
    void seedExtractCommandBuildsExtractLine() {
        String cmd = SkillInstallPipelineImpl.seedExtractCommand("/opt/weknora/tenant/skills/demo");
        // ShellQuote 对 shell 安全字符不加引号（与 Go 同形）
        assertThat(cmd).isEqualTo("tar -xf /opt/weknora/tenant/skills/.weknora-seed.tar "
                + "-C /opt/weknora/tenant/skills/demo "
                + "&& rm -f /opt/weknora/tenant/skills/.weknora-seed.tar");
    }

    @Test
    void cleanImageScratchCommandKeepsWorkspaceExitCodeSemantics() {
        String cmd = SkillInstallPipelineImpl.cleanImageScratchCommand();
        // 工作区半段定退出码；三个包管理器修剪 best-effort；预算守卫与 exit $status 收尾
        assertThat(cmd).startsWith("rm -rf /workspace/* /tmp/* /workspace/.[!.]* || true");
        assertThat(cmd).contains("mkdir -p /workspace/input /workspace/output "
                + "&& chmod 775 /workspace/input /workspace/output; status=$?");
        assertThat(cmd).contains("command -v uv >/dev/null 2>&1 && uv cache prune >/dev/null 2>&1 || true");
        assertThat(cmd).contains("command -v npm >/dev/null 2>&1 && npm cache verify >/dev/null 2>&1 || true");
        assertThat(cmd).contains("command -v pnpm >/dev/null 2>&1 && pnpm store prune >/dev/null 2>&1 || true");
        assertThat(cmd).contains("total=$(du -skc /root/.cache/pip /root/.cache/uv /root/.npm "
                + "/root/.local/share/pnpm/store 2>/dev/null | tail -n1 | cut -f1)");
        assertThat(cmd).endsWith("; exit $status");
    }

    @Test
    void cacheBudgetGuardCommandWipesWholeWhenOverBudget() {
        String cmd = SkillInstallPipelineImpl.cacheBudgetGuardCommand(List.of("/c1", "/c2"), 256);
        assertThat(cmd).contains("total=$(du -skc /c1 /c2 2>/dev/null | tail -n1 | cut -f1)");
        // ShellQuote 对安全字符不加引号（与 Go 同形）
        assertThat(cmd).contains("echo \"cache total: ${total:-0}KB\"");
        assertThat(cmd).contains("[ \"${total:-0}\" -gt 256 ] && { rm -rf /c1 /c2; echo 'cache wiped: over budget'; }; true");
    }

    @Test
    void installToolsProbeCommandCoversAllProbeTools() {
        String cmd = SkillInstallPipelineImpl.installToolsProbeCommand();
        assertThat(cmd).startsWith("for t in uv npm pnpm pip3 pip python3 node;");
        assertThat(cmd).endsWith("printf '%s=%s\\n' \"$t\" \"$p\"; fi; done");
    }

    @Test
    void parseToolProbeOutputKeepsOnlyNameEqualsPathLines() {
        Map<String, String> out = SkillInstallPipelineImpl.parseToolProbeOutput(
                "uv=/root/.local/bin/uv\nnoise\nnpm=/usr/bin/npm\n\npython3=");
        assertThat(out).containsOnlyKeys("uv", "npm");
        assertThat(out.get("uv")).isEqualTo("/root/.local/bin/uv");
    }

    @Test
    void formatToolchainSectionListsMissingGroup() {
        String ok = SkillInstallPipelineImpl.formatToolchainSection(
                Map.of("uv", "/root/.local/bin/uv"));
        assertThat(ok).startsWith("Toolchain (absolute paths as resolved in this image; prefer them over PATH):");
        assertThat(ok).contains("\n- uv: /root/.local/bin/uv");
        assertThat(ok).contains("\nnot found: npm, pnpm, pip3, pip, python3, node");

        String failed = SkillInstallPipelineImpl.formatToolchainSection(Map.of());
        assertThat(failed).isEqualTo("Toolchain: could not be probed in advance; "
                + "locate tools with `command -v <tool>` before relying on PATH.");
    }

    // ── prompt 构造 ──────────────────────────────────────────────────────

    @Test
    void buildRepairPromptCarriesGateFindingsOnly() {
        SkillVerificationException gate = new SkillVerificationException("python", true,
                List.of("module defusedxml is missing", "module lxml is missing"), "");
        String p = SkillInstallPipelineImpl.buildRepairPrompt("/skills/demo", gate);
        assertThat(p).startsWith("Verification of the skill you just installed failed. Fix only this and stop.");
        assertThat(p).contains("The python check reported:\n- module defusedxml is missing\n- module lxml is missing\n");
        assertThat(p).contains("Python packages go into /skills/demo/.venv (`uv pip install`, or");
        assertThat(p).contains("Node packages go under /skills/demo/node_modules.");
        assertThat(p).contains("Do NOT edit SKILL.md, requirements.txt, pyproject.toml or package.json");
        assertThat(p).contains("Runtime prerequisites and completion report (required for every skill):");
        // Go 对 runtime 指令原样拼接——<skill-dir> 占位保留字面（实录为准）
        assertThat(p).contains("<skill-dir>/.weknora/bin");
    }

    @Test
    void buildInstallPromptAnchors() {
        SkillBundle bundle = new SkillBundle();
        bundle.name = "demo";
        bundle.files.put("SKILL.md", "# Demo".getBytes(StandardCharsets.UTF_8));
        bundle.files.put("scripts/install_deps.py", new byte[0]);
        String p = SkillInstallPipelineImpl.buildInstallPrompt("/opt/weknora/tenant/skills/demo",
                bundle, Map.of("uv", "/root/.local/bin/uv"));

        assertThat(p).startsWith("Install this WeKnora skill into the sandbox image.\n\n"
                + "Skill directory: /opt/weknora/tenant/skills/demo\n");
        assertThat(p).contains("- This archive ships on-demand installer(s): `scripts/install_deps.py`.");
        assertThat(p).contains("write_skill_file to /opt/weknora/tenant/skills/demo/.weknora/requirements.json");
        assertThat(p).contains("- uv: /root/.local/bin/uv");
        assertThat(p).contains("SKILL.md:\n# Demo\n");
        // runtime 指令原样拼接——<skill-dir> 占位保留字面（Go 同形）
        assertThat(p).contains("<skill-dir>/.weknora/bin (on PATH when a session selects this");
    }

    @Test
    void formatFrontmatterRepairNoteOnlyWhenRepaired() {
        SkillBundle clean = new SkillBundle();
        assertThat(SkillInstallPipelineImpl.formatFrontmatterRepairNote(clean)).isEmpty();
        SkillBundle repaired = new SkillBundle();
        repaired.frontmatterRepaired = true;
        assertThat(SkillInstallPipelineImpl.formatFrontmatterRepairNote(repaired))
                .contains("The SKILL.md YAML frontmatter was automatically repaired");
    }

    // ── tar 播种（packSkillTar） ─────────────────────────────────────────

    @Test
    void packSkillTarWritesSortedUSTarEntries(@TempDir Path tmp) throws Exception {
        SkillBundle bundle = new SkillBundle();
        bundle.name = "demo";
        bundle.files.put("b.txt", "BBBB".getBytes(StandardCharsets.UTF_8));
        bundle.files.put("a/c.txt", "CCCCCCC".getBytes(StandardCharsets.UTF_8));
        bundle.files.put("a.txt", "AAA".getBytes(StandardCharsets.UTF_8));

        byte[] tar = SkillInstallPipelineImpl.packSkillTar(bundle);
        Path out = tmp.resolve("seed.tar");
        Files.write(out, tar);

        // 用系统 tar 验证结构可解（Go 的 archive/tar 产物在沙箱内同样被 tar -xf 消费）
        var proc = new ProcessBuilder("tar", "-tf", out.toString())
                .redirectErrorStream(true).start();
        String listing = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(proc.waitFor()).isEqualTo(0);
        assertThat(listing.trim().split("\n")).containsExactly("a.txt", "a/c.txt", "b.txt");

        // 头字段：mode 0755、size 八进制、ustar magic、名字
        byte[] headA = tar;
        assertThat(new String(headA, 0, 5, StandardCharsets.UTF_8)).isEqualTo("a.txt");
        assertThat(new String(headA, 100, 7, StandardCharsets.UTF_8)).isEqualTo("0000755");
        assertThat(new String(headA, 257, 5, StandardCharsets.UTF_8)).isEqualTo("ustar");
        assertThat(new String(headA, 124, 11, StandardCharsets.UTF_8)).isEqualTo("00000000003"); // size 3

        // 内容量 = 3×(512 头 + 512 对齐体) + 1024 结束符
        assertThat((long) tar.length).isEqualTo(8L * 512);
    }

    @Test
    void packSkillTarRejectsEscapingPaths() {
        SkillBundle bundle = new SkillBundle();
        bundle.name = "demo";
        bundle.files.put("../escape.txt", new byte[0]);
        assertThatThrownBy(() -> SkillInstallPipelineImpl.packSkillTar(bundle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("escapes the archive root");
    }

    // ── 快照命名与代次 ───────────────────────────────────────────────────

    @Test
    void nextSnapshotGenerationIsMaxOfLiveAndLedgerPlusOne() {
        List<TenantSkillSnapshotEntity> ledger = List.of(
                snapshot(3), snapshot(7), snapshot(2));
        assertThat(SkillInstallPipelineImpl.nextSnapshotGeneration(5, ledger)).isEqualTo(8);
        assertThat(SkillInstallPipelineImpl.nextSnapshotGeneration(9, ledger)).isEqualTo(10);
        assertThat(SkillInstallPipelineImpl.nextSnapshotGeneration(0, List.of())).isEqualTo(1);
        assertThat(SkillInstallPipelineImpl.nextSnapshotGeneration(-1, List.of())).isEqualTo(1);
    }

    private static TenantSkillSnapshotEntity snapshot(int generation) {
        TenantSkillSnapshotEntity e = new TenantSkillSnapshotEntity();
        e.setId("row-" + generation);
        e.setGeneration(generation);
        e.setState("active");
        return e;
    }

    @Test
    void skillSnapshotBuildNameFormat() {
        assertThat(SkillInstallPipelineImpl.skillSnapshotBuildName(10008,
                "a1b2c3d4-1234-5678-9abc-def012345678", 4, "row-uuid-9999"))
                .isEqualTo("weknora-sk-t10008-a1b2c3d4123456789abcdef012345678-g4-rowuuid9");
        assertThat(SkillInstallPipelineImpl.compactSnapshotToken("")).isEqualTo("row");
        assertThat(SkillInstallPipelineImpl.compactSnapshotToken("short")).isEqualTo("short");
        assertThat(SkillInstallPipelineImpl.compactSnapshotToken("0123456789abcdef"))
                .isEqualTo("01234567");
    }

    @Test
    void manifestPathAndRequirementsPath() {
        assertThat(SkillInstallPipelineImpl.manifestPath())
                .isEqualTo("/opt/weknora/tenant/skills/.manifest.json");
        assertThat(SkillInstallPipelineImpl.requirementsPath("demo"))
                .isEqualTo("/opt/weknora/tenant/skills/demo/.weknora/requirements.json");
        assertThat(SkillInstallPipelineImpl.requirementsPath("../evil")).isEmpty();
    }

    // ── 目录守卫与镜像可用性 ─────────────────────────────────────────────

    @Test
    void guardSkillDirRefusesSkillsRoot() {
        assertThat(SkillInstallPipelineImpl.guardSkillDirError("/opt/weknora/tenant/skills"))
                .isEqualTo("refusing to use the skills root \"/opt/weknora/tenant/skills\" "
                        + "as a skill directory");
        assertThat(SkillInstallPipelineImpl.guardSkillDirError("/opt/weknora/tenant/skills/demo"))
                .isEmpty();
    }

    @Test
    void ensureUsableImageAcceptsAbsentOrMatchingFingerprint() {
        assertThatCode(() -> SkillInstallPipelineImpl.ensureUsableImage(null))
                .doesNotThrowAnyException();
        TenantSandboxConfigEntity cfg = new TenantSandboxConfigEntity();
        cfg.setId("cfg-1");
        assertThatCode(() -> SkillInstallPipelineImpl.ensureUsableImage(cfg))
                .doesNotThrowAnyException();

        TenantSandboxConfig config = new TenantSandboxConfig();
        config.setSandboxType("cube");
        CubeSandboxConfig cube = new CubeSandboxConfig();
        cube.setApiKey("k1");
        cube.setApiUrl("https://api");
        config.setCube(cube);
        cfg.setConfig(config);

        // 无镜像 → 放行
        assertThatCode(() -> SkillInstallPipelineImpl.ensureUsableImage(cfg))
                .doesNotThrowAnyException();
        // 指纹匹配 → 放行
        SkillImageConfig image = new SkillImageConfig();
        image.setSnapshotId("snap-1");
        image.setOwnerFingerprint(SkillInstallPipelineImpl.skillOwnerFingerprint(config));
        config.setSkillImage(image);
        assertThatCode(() -> SkillInstallPipelineImpl.ensureUsableImage(cfg))
                .doesNotThrowAnyException();
        // 指纹不匹配（凭据轮换）→ 拒绝
        image.setOwnerFingerprint("deadbeef");
        assertThatThrownBy(() -> SkillInstallPipelineImpl.ensureUsableImage(cfg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("belongs to another provider account");
    }

    @Test
    void effectiveBaseTemplatePrefersRecordedChainBase() {
        TenantSandboxConfig config = new TenantSandboxConfig();
        config.setSandboxType("cube");
        CubeSandboxConfig cube = new CubeSandboxConfig();
        cube.setTemplateId("base-tpl");
        config.setCube(cube);
        TenantSandboxConfigEntity cfg = new TenantSandboxConfigEntity();
        cfg.setConfig(config);
        assertThat(SkillInstallPipelineImpl.effectiveBaseTemplate(cfg)).isEqualTo("base-tpl");

        SkillImageConfig image = new SkillImageConfig();
        image.setBaseTemplateId("origin-tpl");
        config.setSkillImage(image);
        assertThat(SkillInstallPipelineImpl.effectiveBaseTemplate(cfg)).isEqualTo("origin-tpl");
    }

    // ── installer agent 配置 ─────────────────────────────────────────────

    @Test
    void unionToolsAppendsMissingInOrder() {
        assertThat(SkillInstallPipelineImpl.unionTools(
                List.of("shell_exec", "todo_write"), List.of("shell_exec", "write_skill_file")))
                .containsExactly("shell_exec", "todo_write", "write_skill_file");
        assertThat(SkillInstallPipelineImpl.unionTools(List.of(" "), List.of("a")))
                .containsExactly("a");
    }

    @Test
    void installerAgentConfigGrantsInstallModeAndUnionsTools() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var defaults = mapper.readTree("""
                {"id":"builtin-skill-installer","config":{
                  "max_iterations":0,"temperature":0.3,
                  "allowed_tools":["todo_write"],"system_prompt":"custom prompt",
                  "multi_turn_enabled":true,"llm_call_timeout":30}}
                """);

        AgentConfig cfg = SkillInstallPipelineImpl.installerAgentConfig(
                defaults, "cfg-1", "/opt/weknora/tenant/skills/demo");
        assertThat(cfg.getMaxIterations()).isEqualTo(30); // 0 → 默认 30
        assertThat(cfg.getAllowedTools()).containsExactly(
                "todo_write", "shell_exec", "write_skill_file", "edit_skill_file");
        assertThat(cfg.getTemperature()).isEqualTo(0.3);
        assertThat(((QaLikeConfig) cfg).getSystemPrompt()).isEqualTo("custom prompt");
        assertThat(cfg.isMultiTurnEnabled()).isTrue();
        assertThat(cfg.getLlmCallTimeout()).isEqualTo(30);
        // 安装模式授权键 = 内建 ID；shell 是特权变体、skill 目录圈定
        assertThat(cfg.isSkillInstallMode()).isTrue();
        assertThat(cfg.getSkillInstallDir()).isEqualTo("/opt/weknora/tenant/skills/demo");
        assertThat(((QaLikeConfig) cfg).getSandboxConfigId()).isEqualTo("cfg-1");
        assertThat(cfg.getThinking()).isFalse();
    }

    @Test
    void installerAgentConfigDefaultsWithoutRegistry() {
        AgentConfig cfg = SkillInstallPipelineImpl.installerAgentConfig(null, "cfg-1",
                "/opt/weknora/tenant/skills/demo");
        assertThat(cfg.getMaxIterations()).isEqualTo(30);
        assertThat(cfg.getAllowedTools()).containsExactly(
                "shell_exec", "write_skill_file", "edit_skill_file");
        assertThat(cfg.getTemperature()).isEqualTo(0.2);
        assertThat(cfg.isSkillInstallMode()).isTrue();
        assertThat(cfg.getThinking()).isFalse();
    }

    // ── manifest 逐字节形态 ──────────────────────────────────────────────

    @Test
    void manifestJsonFieldOrderMatchesGoStruct() throws Exception {
        SkillInstallPipelineImpl.ManifestEntry e = new SkillInstallPipelineImpl.ManifestEntry();
        e.id = "skill-1";
        e.name = "demo";
        e.version = "1.0";
        e.sha256 = "abc";
        e.installedAt = java.time.OffsetDateTime.parse("2026-09-23T00:00:00Z");
        Manifest m = new Manifest();
        m.skills.add(e);
        String json = new ObjectMapper().writeValueAsString(m);
        // Go struct 序：id, name, version(omitempty), sha256, installed_at（RFC3339 序列化器）
        assertThat(json).startsWith("{\"skills\":[{\"id\":\"skill-1\",\"name\":\"demo\","
                + "\"version\":\"1.0\",\"sha256\":\"abc\",\"installed_at\":\"2026-09-23T");
        assertThat(json).endsWith("\"}]}");
    }

    @Test
    void manifestEntryOmitsEmptyVersion() throws Exception {
        SkillInstallPipelineImpl.ManifestEntry e = new SkillInstallPipelineImpl.ManifestEntry();
        e.id = "skill-1";
        e.name = "demo";
        e.sha256 = "abc";
        Manifest m = new Manifest();
        m.skills.add(e);
        String json = new ObjectMapper().writeValueAsString(m);
        assertThat(json).doesNotContain("version");
    }

    // ── 渐近进度（管线消费的转写静态函数；键值锚点） ──────────────────────

    @Test
    void asymptoticInstallPercentMonotonicBelow80() {
        assertThat(SkillInstallTranscript.asymptoticInstallPercent(0)).isEqualTo(35);
        int prev = 35;
        for (int k = 1; k <= 60; k++) {
            int p = SkillInstallTranscript.asymptoticInstallPercent(k);
            assertThat(p).isBetween(prev, 79);
            prev = p;
        }
        assertThat(SkillInstallTranscript.asymptoticInstallPercent(1000)).isEqualTo(79);
    }

    @Test
    void packageCachePathsUnderHome() {
        assertThat(SkillInstallPipelineImpl.packageCachePaths("/root")).containsExactly(
                "/root/.cache/pip", "/root/.cache/uv", "/root/.npm",
                "/root/.local/share/pnpm/store");
    }

    @Test
    void heartbeatDefaultsMirrorGo() {
        assertThat(SkillInstallPipelineImpl.SKILL_INSTALL_HEARTBEAT_INTERVAL)
                .isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(SkillInstallPipelineImpl.INSTALL_COMMAND_TIMEOUT)
                .isEqualTo(java.time.Duration.ofMinutes(10));
        assertThat(SkillInstallPipelineImpl.READY_SKILL_WRITE_ATTEMPTS).isEqualTo(3);
        assertThat(SkillInstallPipelineImpl.SKILL_INSTALL_VERIFY_ROUNDS).isEqualTo(2);
        assertThat(SkillInstallPipelineImpl.SKILL_CACHE_BUDGET_MB).isEqualTo(256);
    }
}
