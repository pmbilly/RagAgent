package com.ragagent.sandbox.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 真实 Docker daemon 的集成验证（对照 Go docker_integration_test.go 的主链路）。
 *
 * <p><b>门控</b>：默认跳过。整链路跑法：
 * <pre>
 *   WEKNORA_SANDBOX_DOCKER_IT=true WEKNORA_SANDBOX_DOCKER_ENABLED=true \
 *   ./gradlew :server:test --tests "com.ragagent.sandbox.runtime.DockerSandboxIntegrationTest"
 * </pre>
 * 镜像默认 {@code wechatopenai/weknora-sandbox:main}（可用
 * {@code WEKNORA_SANDBOX_DOCKER_IMAGE} 覆盖；本机未预拉时 Create 会先冷拉几分钟）。
 * daemon endpoint 取 DOCKER_HOST / docker context / 默认 unix socket，与生产一致。</p>
 */
@EnabledIfEnvironmentVariable(named = "WEKNORA_SANDBOX_DOCKER_IT", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DockerSandboxIntegrationTest {

    private static final String IMAGE =
            System.getenv().getOrDefault("WEKNORA_SANDBOX_DOCKER_IMAGE",
                    EffectiveConfig.DEFAULT_DOCKER_IMAGE);

    private DockerSandboxClient client;

    @BeforeAll
    void buildClient() {
        EffectiveConfig cfg = EffectiveConfig.defaultConfig();
        cfg.type = SandboxTypes.TYPE_DOCKER;
        cfg.dockerImage = IMAGE;
        // 本机 daemon 通常在私网/本地 socket 上；集成客户端显式放行
        cfg.allowPrivateEndpoints = true;
        client = DockerSandboxClient.forConfig(cfg);
    }

    @AfterAll
    void tearDown() {
        // 各测试自清理；client 无需关闭（引擎连接池是进程级共享）
    }

    @Test
    @Order(1)
    void healthPingsDaemon() {
        client.health();
    }

    @Test
    @Order(2)
    void createExecReadFilesAndDelete() {
        Map<String, String> metadata = Map.of(
                ConfigSandboxClient.ConfigSandboxes.METADATA_TENANT_ID, "10008",
                ConfigSandboxClient.ConfigSandboxes.METADATA_SESSION_ID,
                "it-" + UUID.randomUUID(),
                ConfigSandboxClient.ConfigSandboxes.METADATA_CONFIG_ID, "it-config");
        SandboxSessionClient.Handle handle = client.create(new SandboxSessionClient.CreateRequest(
                IMAGE, SandboxSessionClient.TimeoutPolicy.serverDefault(
                        SandboxSessionClient.TimeoutAction.KILL),
                metadata, Map.of("IT_MARKER", "1"), null, null));
        try {
            // exec（含 env/workDir）
            SandboxSessionClient.ExecResult result = client.exec(handle,
                    new SandboxSessionClient.ExecRequest(null, "sh",
                            List.of("-c", "echo $IT_MARKER && pwd"), false, null,
                            Map.of("IT_MARKER", "hello"), "/workspace", null,
                            java.time.Duration.ofSeconds(30)));
            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout().trim()).isEqualTo("hello\n/workspace");
            assertThat(result.killed()).isFalse();

            // 文件面：write → read → stat → list → remove
            byte[] payload = {0x62, 0x69, 0x6e, 0x61, 0x72, 0x79, 0x01, 0x02, 0x63, 0x6f,
                    0x6e, 0x74, 0x65, 0x6e, 0x74}; // "binary\x01\x02content" 原始字节
            client.writeFile(handle, "/workspace/it/nested/data.bin", payload);
            assertThat(client.readFile(handle, "/workspace/it/nested/data.bin"))
                    .isEqualTo(payload);

            SandboxSessionClient.StatEntry stat = client.stat(handle,
                    "/workspace/it/nested/data.bin");
            assertThat(stat.type()).isEqualTo(SandboxSessionClient.DirEntryType.FILE);
            assertThat(stat.size()).isEqualTo(payload.length);

            List<SandboxSessionClient.DirEntry> entries =
                    client.listDir(handle, "/workspace/it/nested");
            assertThat(entries).extracting(SandboxSessionClient.DirEntry::name)
                    .containsExactly("data.bin");

            // 缺路径 → NOT_FOUND
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            client.readFile(handle, "/workspace/it/nope"))
                    .isInstanceOf(RemoteError.class)
                    .satisfies(e -> assertThat(((RemoteError) e).kind)
                            .isEqualTo(RemoteErrorKind.NOT_FOUND));

            client.remove(handle, "/workspace/it");
            // 目录已不存在 → NOT_FOUND（对照 Go 的 "does not exist" 分支）
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            client.listDir(handle, "/workspace/it"))
                    .isInstanceOf(RemoteError.class)
                    .satisfies(e -> assertThat(((RemoteError) e).kind)
                            .isEqualTo(RemoteErrorKind.NOT_FOUND));
        } finally {
            client.delete(((DockerSandboxClient.DockerHandle) handle).id());
        }
    }

    @Test
    @Order(3)
    void snapshotRoundTripUsesLocalImageTags() {
        SandboxSessionClient.Handle handle = client.create(
                new SandboxSessionClient.CreateRequest(IMAGE,
                        SandboxSessionClient.TimeoutPolicy.serverDefault(
                                SandboxSessionClient.TimeoutAction.KILL),
                        null, null, null, null));
        String sandboxId = ((DockerSandboxClient.DockerHandle) handle).id();
        try {
            SandboxSessionClient.SnapshotRef ref =
                    client.createSnapshot(sandboxId, "Integration Skill v1");
            // Go dockerSanitizeImageName 只收字母数字与 ./_/- 分隔符；空格被丢弃
            //（既非字母数字也非分隔符，落空后 lastSep 保持 false）→ 无连字符
            assertThat(ref.id()).isEqualTo("weknora-skill/integrationskillv1");
            assertThat(DockerSandboxClient.isSkillSnapshotRef(ref.id())).isTrue();
            try {
                List<SandboxSessionClient.SnapshotRef> listed =
                        client.listSnapshots(sandboxId);
                assertThat(listed).extracting(SandboxSessionClient.SnapshotRef::id)
                        .contains(ref.id());
            } finally {
                client.deleteSnapshot(ref.id());
            }
            // 幂等：缺失的快照删除不是错误
            client.deleteSnapshot(ref.id());
        } finally {
            client.delete(sandboxId);
        }
    }

    @Test
    @Order(4)
    void templateCatalogListsConfiguredImage() {
        List<RemoteTemplate> templates = client.listTemplates();
        assertThat(templates).extracting(t -> t.id).contains(IMAGE);
        RemoteTemplate ensured = client.ensureStandardTemplate();
        assertThat(ensured.id).isEqualTo(IMAGE);
        assertThat(ensured.status).isIn("ready", "building", "missing");
    }
}
