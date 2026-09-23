package com.ragagent.sandbox.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

/**
 * DockerSandboxClient 的纯确定性面（无 daemon）：label 构造、快照引用名、
 * exec wrapper 命令、错误映射、超时包装、路径守卫、find 输出解析。
 * 真实 daemon 的行为由 {@link DockerSandboxIntegrationTest}（环境门控）覆盖。
 */
class DockerSandboxClientTest {

    // ── label 构造（对照 dockerContainerLabels/dockerSandboxMetadata） ────

    @Test
    void containerLabelsCarryManagedMark() {
        Map<String, String> labels = DockerEngineClients.dockerContainerLabels(Map.of(
                ConfigSandboxClient.ConfigSandboxes.METADATA_TENANT_ID, "10008",
                ConfigSandboxClient.ConfigSandboxes.METADATA_SESSION_ID, "sess-1"));
        assertThat(labels)
                .containsEntry(ConfigSandboxClient.ConfigSandboxes.METADATA_TENANT_ID, "10008")
                .containsEntry(ConfigSandboxClient.ConfigSandboxes.METADATA_SESSION_ID, "sess-1")
                .containsEntry(DockerEngineClients.DOCKER_MANAGED_LABEL, "true");
    }

    @Test
    void containerLabelsStripManagedMarkOnProjectionBack() {
        Map<String, String> labels = DockerEngineClients.dockerContainerLabels(
                Map.of(ConfigSandboxClient.ConfigSandboxes.METADATA_CONFIG_ID, "cfg-1"));
        labels.put(DockerSandboxClient.DOCKER_IDLE_TTL_LABEL, "1800");
        Map<String, String> metadata = DockerEngineClients.dockerSandboxMetadata(labels);
        assertThat(metadata)
                .containsEntry(ConfigSandboxClient.ConfigSandboxes.METADATA_CONFIG_ID, "cfg-1")
                .containsEntry(DockerSandboxClient.DOCKER_IDLE_TTL_LABEL, "1800")
                .doesNotContainKey(DockerEngineClients.DOCKER_MANAGED_LABEL);
    }

    @Test
    void containerLabelsHandleNullMetadata() {
        Map<String, String> labels = DockerEngineClients.dockerContainerLabels(null);
        assertThat(labels).containsOnly(
                Map.entry(DockerEngineClients.DOCKER_MANAGED_LABEL, "true"));
    }

    // ── 快照引用名（对照 dockerSkillSnapshotReference / dockerIsSkillSnapshotRef /
    //      dockerCanonicalSnapshotID / dockerSanitizeImageName） ──────────────

    @Test
    void skillSnapshotReferenceSanitizesName() {
        assertThat(DockerSandboxClient.skillSnapshotReference("My Skill! v2", "abc"))
                .isEqualTo("weknora-skill/myskillv2");
    }

    @Test
    void skillSnapshotReferenceCollapsesSeparators() {
        assertThat(DockerSandboxClient.skillSnapshotReference("a__b--c.d_e", "x"))
                .isEqualTo("weknora-skill/a-b-c-d-e");
    }

    @Test
    void skillSnapshotReferenceFallsBackToSandboxID() {
        assertThat(DockerSandboxClient.skillSnapshotReference("   ", "cfg-9-session"))
                .isEqualTo("weknora-skill/cfg-9-session");
    }

    @Test
    void skillSnapshotReferenceRejectsBothBlank() {
        assertThatThrownBy(() -> DockerSandboxClient.skillSnapshotReference("  ", "  "))
                .isInstanceOf(RemoteError.class)
                .satisfies(e -> assertThat(((RemoteError) e).kind)
                        .isEqualTo(RemoteErrorKind.INVALID_REQUEST))
                .hasMessageContaining("snapshot name is required");
    }

    @Test
    void isSkillSnapshotRefMatchesRepoAndDockerIoAlias() {
        assertThat(DockerSandboxClient.isSkillSnapshotRef("weknora-skill/abc")).isTrue();
        assertThat(DockerSandboxClient.isSkillSnapshotRef("docker.io/weknora-skill/abc")).isTrue();
        assertThat(DockerSandboxClient.isSkillSnapshotRef("  weknora-skill/abc  ")).isTrue();
        assertThat(DockerSandboxClient.isSkillSnapshotRef("nginx")).isFalse();
        assertThat(DockerSandboxClient.isSkillSnapshotRef("weknora-skillx/abc")).isFalse();
        assertThat(DockerSandboxClient.isSkillSnapshotRef("")).isFalse();
        assertThat(DockerSandboxClient.isSkillSnapshotRef(null)).isFalse();
    }

    @Test
    void canonicalSnapshotIDStripsRegistryAndLatest() {
        assertThat(DockerSandboxClient.canonicalSnapshotID("docker.io/weknora-skill/foo:latest"))
                .isEqualTo("weknora-skill/foo");
        assertThat(DockerSandboxClient.canonicalSnapshotID("weknora-skill/foo:v2"))
                .isEqualTo("weknora-skill/foo:v2");
    }

    @Test
    void sanitizeImageNameCapsAtEightyAndTrimsDashes() {
        assertThat(DockerSandboxClient.sanitizeImageName("--leading-and-trailing--"))
                .isEqualTo("leading-and-trailing");
        String longName = "a".repeat(100) + "-b";
        String sanitized = DockerSandboxClient.sanitizeImageName(longName);
        assertThat(sanitized).hasSize(80);
        assertThat(sanitized.endsWith("-")).isFalse();
    }

    @Test
    void imageHasTagIgnoresDangling() {
        assertThat(DockerSandboxClient.imageHasTag(
                new DockerEngineClients.ImageListItem("id", new String[]{"<none>:<none>", ""},
                        Map.of(), 0))).isFalse();
        assertThat(DockerSandboxClient.imageHasTag(
                new DockerEngineClients.ImageListItem("id", new String[]{"weknora-skill/x"},
                        Map.of(), 0))).isTrue();
    }

    @Test
    void snapshotRefPrefersSkillTagAsId() {
        DockerEngineClients.ImageListItem item = new DockerEngineClients.ImageListItem(
                "sha256:deadbeef",
                new String[]{"<none>:<none>", "weknora-skill/my-skill:latest"},
                Map.of(DockerSandboxClient.DOCKER_SKILL_SNAPSHOT_LABEL, "true"), 42);
        SandboxSessionClient.SnapshotRef ref = DockerSandboxClient.snapshotRefOf(item);
        assertThat(ref.id()).isEqualTo("weknora-skill/my-skill");
        assertThat(ref.names()).containsExactly("weknora-skill/my-skill");
    }

    // ── exec wrapper（对照 dockerExecCommand/dockerExecUser/dockerExecWasKilled） ──

    @Test
    void execCommandShellVariantCarriesCommandAsPositional() {
        SandboxSessionClient.ExecRequest req = new SandboxSessionClient.ExecRequest(
                null, "echo hi && rm -rf /", List.of(), true, null, null, null, null, null);
        String[] argv = DockerSandboxClient.execCommand(req, Duration.ofSeconds(60));
        assertThat(argv).hasSize(5);
        assertThat(argv[0]).isEqualTo("/bin/sh");
        assertThat(argv[1]).isEqualTo("-c");
        assertThat(argv[2])
                .startsWith("touch " + DockerSandboxClient.DOCKER_ACTIVITY_MARKER)
                .contains("exec timeout -s KILL 60 /bin/bash --noprofile --norc -c \"$1\"");
        assertThat(argv[3]).isEqualTo("weknora-exec");
        assertThat(argv[4]).isEqualTo("echo hi && rm -rf /");
    }

    @Test
    void execCommandArgsVariantAppendsPositionals() {
        SandboxSessionClient.ExecRequest req = new SandboxSessionClient.ExecRequest(
                null, "python", List.of("-c", "print(1)"), false, null, null, null, null, null);
        String[] argv = DockerSandboxClient.execCommand(req, Duration.ofMillis(1500));
        assertThat(argv).hasSize(7);
        assertThat(argv[2]).contains("exec timeout -s KILL 2 \"$@\"");
        assertThat(argv[3]).isEqualTo("weknora-exec");
        assertThat(argv[4]).isEqualTo("python");
        assertThat(argv[5]).isEqualTo("-c");
        assertThat(argv[6]).isEqualTo("print(1)");
    }

    @Test
    void execCommandClampsSubSecondTimeoutToOneSecond() {
        SandboxSessionClient.ExecRequest req = new SandboxSessionClient.ExecRequest(
                null, "true", List.of(), false, null, null, null, null, null);
        String[] argv = DockerSandboxClient.execCommand(req, Duration.ofMillis(100));
        assertThat(argv[2]).contains("exec timeout -s KILL 1 \"$@\"");
    }

    @Test
    void execUserDefaultsToRoot() {
        assertThat(DockerSandboxClient.execUser(null))
                .isEqualTo(SandboxSessionClient.ExecRequest.DEFAULT_SANDBOX_EXEC_USER)
                .isEqualTo("root");
        assertThat(DockerSandboxClient.execUser("  alice  ")).isEqualTo("alice");
    }

    @Test
    void execWasKilledRecognizesWrapperCodes() {
        assertThat(DockerSandboxClient.execWasKilled(137)).isTrue();
        assertThat(DockerSandboxClient.execWasKilled(124)).isTrue();
        assertThat(DockerSandboxClient.execWasKilled(0)).isFalse();
        assertThat(DockerSandboxClient.execWasKilled(1)).isFalse();
    }

    // ── 错误映射（对照 dockerErrorKind/dockerError/dockerInvalidRequest） ──

    @Test
    void rpcTimeoutMapsToTimeoutKind() {
        RemoteError err = DockerEngineClients.dockerError("Exec",
                new DockerEngineClients.DockerRpcTimeoutException());
        assertThat(err.kind).isEqualTo(RemoteErrorKind.TIMEOUT);
        assertThat(err.provider).isEqualTo("docker");
        assertThat(err.op).isEqualTo("Exec");
    }

    @Test
    void rpcDeadlineExceedsAndCancelsVirtualThread() {
        assertThatThrownBy(() -> DockerEngineClients.rpc(Duration.ofMillis(20), () -> {
            Thread.sleep(5_000);
            return null;
        })).isInstanceOf(DockerEngineClients.DockerRpcTimeoutException.class)
                .hasMessage("context deadline exceeded");
    }

    @Test
    void rpcWithoutTimeoutRunsUnbounded() {
        Integer value = DockerEngineClients.rpc(null, () -> 41 + 1);
        assertThat(value).isEqualTo(42);
    }

    @Test
    void dockerErrorKindStatusMappingMatchesGo() {
        // dockerErrorKind L336 的关键分支：Create 的 404 是坏模板而非沙箱消失
        assertThat(DockerEngineClients.dockerErrorKindOfStatus("Create", 404))
                .isEqualTo(RemoteErrorKind.INVALID_REQUEST);
        assertThat(DockerEngineClients.dockerErrorKindOfStatus("Get", 404))
                .isEqualTo(RemoteErrorKind.NOT_FOUND);
        assertThat(DockerEngineClients.dockerErrorKindOfStatus("Exec", 409))
                .isEqualTo(RemoteErrorKind.CONFLICT);
        assertThat(DockerEngineClients.dockerErrorKindOfStatus("List", 500))
                .isEqualTo(RemoteErrorKind.UNAVAILABLE);
        assertThat(DockerEngineClients.dockerErrorKindOfStatus("Health", 400))
                .isEqualTo(RemoteErrorKind.INVALID_REQUEST);
    }

    @Test
    void dockerInvalidRequestCarriesOpAndKind() {
        RemoteError err = DockerEngineClients.dockerInvalidRequest("WriteFile", "path is required");
        assertThat(err.kind).isEqualTo(RemoteErrorKind.INVALID_REQUEST);
        assertThat(err.provider).isEqualTo("docker");
        assertThat(err.message).isEqualTo("path is required");
        assertThat(err).hasMessage("docker WriteFile: invalid_request: path is required");
    }

    // ── 路径守卫（对照 dockerCleanPath/dockerReservedPath） ──────────────

    @Test
    void cleanPathNormalizesLikeGoPathClean() {
        assertThat(DockerSandboxClient.dockerCleanPath("Stat", "/tmp//a/./b"))
                .isEqualTo("/tmp/a/b");
        assertThat(DockerSandboxClient.dockerCleanPath("Stat", "/workspace/../etc/passwd"))
                .isEqualTo("/etc/passwd");
        assertThat(DockerSandboxClient.dockerCleanPath("Stat", " /workspace/ "))
                .isEqualTo("/workspace");
    }

    @Test
    void cleanPathRejectsRelativeAndReserved() {
        assertThatThrownBy(() -> DockerSandboxClient.dockerCleanPath("ReadFile", "relative/path"))
                .isInstanceOf(RemoteError.class)
                .hasMessageContaining("path must be absolute: relative/path");
        assertThatThrownBy(() -> DockerSandboxClient.dockerCleanPath("ReadFile", "  "))
                .hasMessageContaining("path is required");
        assertThatThrownBy(() -> DockerSandboxClient.dockerCleanPath("ReadFile", "/proc/self/mounts"))
                .hasMessageContaining("path is not addressable: /proc");
        assertThatThrownBy(() -> DockerSandboxClient.dockerCleanPath(
                "ReadFile", DockerSandboxClient.DOCKER_ACTIVITY_MARKER))
                .hasMessageContaining("path is not addressable");
        // 保留字前缀的兄弟目录不受牵连
        assertThat(DockerSandboxClient.dockerCleanPath("ReadFile", "/process"))
                .isEqualTo("/process");
    }

    @Test
    void reservedPathMatchesExactPrefix() {
        assertThat(DockerSandboxClient.dockerReservedPath("/dev")).isEqualTo("/dev");
        assertThat(DockerSandboxClient.dockerReservedPath("/dev/null")).isEqualTo("/dev");
        assertThat(DockerSandboxClient.dockerReservedPath("/device")).isNull();
        assertThat(DockerSandboxClient.dockerReservedPath("/workspace")).isNull();
    }

    // ── find 输出解析（对照 parseDockerFindOutput） ──────────────────────

    @Test
    void parseFindOutputParsesEntriesAndSkipsMalformed() {
        String output = "f\t12\t1758600000.5\t/workspace/a.txt\n"
                + "d\t4096\t1758600001\t/workspace/sub\n"
                + "garbage line\n"
                + "L\t7\t1758600002.25\t/workspace/link\n"
                + "\n";
        List<SandboxSessionClient.DirEntry> entries =
                DockerSandboxClient.parseFindOutput(output);
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).name()).isEqualTo("a.txt");
        assertThat(entries.get(0).type()).isEqualTo(SandboxSessionClient.DirEntryType.FILE);
        assertThat(entries.get(0).size()).isEqualTo(12);
        assertThat(entries.get(0).modTime().toEpochSecond()).isEqualTo(1758600000L);
        assertThat(entries.get(1).type()).isEqualTo(SandboxSessionClient.DirEntryType.DIR);
        assertThat(entries.get(2).type()).isEqualTo(SandboxSessionClient.DirEntryType.OTHER);
    }

    @Test
    void firstNonEmptyLineTruncatesLongStderr() {
        assertThat(DockerSandboxClient.firstNonEmptyLine("\n \n boom: detail\nmore\n"))
                .isEqualTo("boom: detail");
        String longLine = "x".repeat(300);
        String first = DockerSandboxClient.firstNonEmptyLine(longLine);
        assertThat(first).hasSize(201).endsWith("…");
        assertThat(DockerSandboxClient.firstNonEmptyLine("   ")).isEmpty();
    }

    // ── 网络模式与空闲 TTL ───────────────────────────────────────────────

    @Test
    void networkModeMapsEgressSwitchToNone() {
        RemoteNetworkPolicy noEgress = new RemoteNetworkPolicy();
        noEgress.allowInternetAccess = false;
        assertThat(DockerSandboxClient.networkMode("bridge", noEgress)).isEqualTo("none");

        RemoteNetworkPolicy withEgress = new RemoteNetworkPolicy();
        withEgress.allowInternetAccess = true;
        assertThat(DockerSandboxClient.networkMode("", withEgress)).isEqualTo("bridge");
        assertThat(DockerSandboxClient.networkMode("none", withEgress)).isEqualTo("none");
        assertThat(DockerSandboxClient.networkMode("bridge", null)).isEqualTo("bridge");
    }

    @Test
    void effectiveIdleTTLPrefersExplicitPolicy() {
        assertThat(DockerSandboxClient.effectiveIdleTTL(
                SandboxSessionClient.TimeoutPolicy.serverDefault(
                        SandboxSessionClient.TimeoutAction.KILL),
                Duration.ofMinutes(30)))
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(DockerSandboxClient.effectiveIdleTTL(
                new SandboxSessionClient.TimeoutPolicy(
                        SandboxSessionClient.TimeoutMode.EXPLICIT,
                        Duration.ofMinutes(5),
                        SandboxSessionClient.TimeoutAction.PAUSE,
                        false),
                Duration.ofMinutes(30)))
                .isEqualTo(Duration.ofMinutes(5));
        // 显式 0 值不生效（对照 policy.Value > 0 判定）
        assertThat(DockerSandboxClient.effectiveIdleTTL(
                new SandboxSessionClient.TimeoutPolicy(
                        SandboxSessionClient.TimeoutMode.EXPLICIT,
                        Duration.ZERO, SandboxSessionClient.TimeoutAction.KILL, false),
                Duration.ofMinutes(30)))
                .isEqualTo(Duration.ofMinutes(30));
    }

    // ── 设置投影（对照 dockerSettingsFromConfig） ────────────────────────

    @Test
    void settingsFromConfigAppliesBuiltinDefaults() {
        EffectiveConfig cfg = EffectiveConfig.defaultConfig();
        cfg.type = SandboxTypes.TYPE_DOCKER;
        cfg.dockerImage = "  wechatopenai/weknora-sandbox:main  ";
        DockerSandboxClient.Settings settings = DockerSandboxClient.settingsFromConfig(cfg);
        assertThat(settings.image()).isEqualTo("wechatopenai/weknora-sandbox:main");
        assertThat(settings.cpuLimit()).isEqualTo(EffectiveConfig.DEFAULT_DOCKER_CPU_LIMIT);
        assertThat(settings.memoryBytes()).isEqualTo(EffectiveConfig.DEFAULT_DOCKER_MEMORY_LIMIT);
        assertThat(settings.pidsLimit()).isEqualTo(EffectiveConfig.DEFAULT_DOCKER_PIDS_LIMIT);
        assertThat(settings.idleTTL())
                .isEqualTo(Duration.ofSeconds(EffectiveConfig.DEFAULT_DOCKER_IDLE_TTL_SEC));
        assertThat(settings.httpTimeout())
                .isEqualTo(Duration.ofSeconds(EffectiveConfig.DEFAULT_DOCKER_HTTP_TIMEOUT_SEC));
        assertThat(settings.networkMode()).isEmpty();
    }

    @Test
    void settingsFromConfigRequiresImage() {
        EffectiveConfig cfg = EffectiveConfig.defaultConfig();
        cfg.type = SandboxTypes.TYPE_DOCKER;
        cfg.dockerImage = "   ";
        assertThatThrownBy(() -> DockerSandboxClient.settingsFromConfig(cfg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sandbox: docker backend requires an image");
        assertThatThrownBy(() -> DockerSandboxClient.settingsFromConfig(null))
                .hasMessage("sandbox: docker client requires a config");
    }

    @Test
    void settingsFromConfigRejectsForbiddenNetworkMode() {
        EffectiveConfig cfg = EffectiveConfig.defaultConfig();
        cfg.type = SandboxTypes.TYPE_DOCKER;
        cfg.dockerNetworkMode = "host";
        assertThatThrownBy(() -> DockerSandboxClient.settingsFromConfig(cfg))
                .hasMessageContaining("use \"bridge\" or \"none\"");
    }

    @Test
    void forCheckDisablesIdleSweeping() {
        EffectiveConfig cfg = EffectiveConfig.defaultConfig();
        cfg.type = SandboxTypes.TYPE_DOCKER;
        cfg.dockerImage = "nginx";
        DockerEngineClients.DockerEngine fake = unusedEngine();
        DockerSandboxClient resolved = new DockerSandboxClient(fake,
                DockerSandboxClient.settingsFromConfig(cfg));
        assertThat(resolved.sweeper()).isNotNull();
        // forCheck 是构造入口之一，受 docker 后端总闸管（测试内开闸，finally 复位）
        SandboxBackendPolicy.setDockerBackendEnabled(true);
        try {
            DockerSandboxClient check = DockerSandboxClient.forCheck(cfg);
            assertThat(check.sweeper()).isNull();
        } finally {
            SandboxBackendPolicy.clearDockerBackendEnabledOverride();
        }
    }

    // ── 能力面 ───────────────────────────────────────────────────────────

    @Test
    void capabilitiesMatchGoDockerRemoteClient() {
        DockerSandboxClient client = new DockerSandboxClient(unusedEngine(),
                new DockerSandboxClient.Settings("img", 2.0, 1, 1, "bridge", "",
                        Duration.ofMinutes(30), Duration.ofSeconds(30),
                        new DockerEngineClients.DockerEndpoint("", "", true)));
        SandboxSessionClient.Capabilities caps = client.capabilities();
        assertThat(caps.supportsReconnect()).isTrue();
        assertThat(caps.supportsMetadata()).isTrue();
        assertThat(caps.supportsListSandboxes()).isTrue();
        assertThat(caps.supportsPauseResume()).isTrue();
        assertThat(caps.supportsTimeoutRefresh()).isFalse();
        assertThat(caps.supportsFilesystemEnumeration()).isTrue();
        assertThat(caps.supportsSnapshots()).isTrue();
        assertThat(caps.supportsVolumes()).isFalse();
        assertThat(client.provider()).isEqualTo(SandboxTypes.TYPE_DOCKER);
    }

    @Test
    void dockerStateOfNormalizesContainerStatuses() {
        assertThat(DockerEngineClients.dockerStateOf("running"))
                .isEqualTo(SandboxSessionClient.Summary.STATE_RUNNING);
        assertThat(DockerEngineClients.dockerStateOf("exited"))
                .isEqualTo(SandboxSessionClient.Summary.STATE_PAUSED);
        assertThat(DockerEngineClients.dockerStateOf("dead"))
                .isEqualTo(SandboxSessionClient.Summary.STATE_TERMINAL);
        assertThat(DockerEngineClients.dockerStateOf("weird"))
                .isEqualTo(SandboxSessionClient.Summary.STATE_UNKNOWN);
    }

    @Test
    void handleIDValidatesProviderAndId() {
        assertThatThrownBy(() -> DockerSandboxClient.dockerHandleID("Exec", null))
                .hasMessageContaining("sandbox handle is required");
        assertThatThrownBy(() -> DockerSandboxClient.dockerHandleID("Exec",
                new DockerSandboxClient.DockerHandle("  ", null)))
                .hasMessageContaining("sandbox handle has no ID");
        SandboxSessionClient.Handle foreign = new SandboxSessionClient.Handle() {
            @Override
            public String id() {
                return "s-1";
            }

            @Override
            public String provider() {
                return SandboxTypes.TYPE_E2B;
            }

            @Override
            public Map<String, String> metadata() {
                return null;
            }
        };
        assertThatThrownBy(() -> DockerSandboxClient.dockerHandleID("Exec", foreign))
                .hasMessageContaining("handle belongs to provider e2b");
    }

    private static DockerEngineClients.DockerEngine unusedEngine() {
        // 纯确定性测试不触达引擎；数组绕开 lambda 可空性
        return new DockerEngineClients.DockerEngine[] { null }[0];
    }
}
