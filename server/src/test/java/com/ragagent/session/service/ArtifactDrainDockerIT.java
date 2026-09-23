package com.ragagent.session.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
import org.junit.jupiter.api.io.TempDir;

import com.ragagent.agent.tools.OutputLinks;
import com.ragagent.sandbox.runtime.ConfigSandboxClient;
import com.ragagent.sandbox.runtime.DockerSandboxClient;
import com.ragagent.sandbox.runtime.EffectiveConfig;
import com.ragagent.sandbox.runtime.SandboxSessionClient;
import com.ragagent.sandbox.runtime.SessionBoundManager;
import com.ragagent.sandbox.runtime.SessionSandboxBinding;
import com.ragagent.sandbox.runtime.SessionSandboxBindingStore;
import com.ragagent.sandbox.runtime.SandboxTypes;
import com.ragagent.session.domain.MessageArtifact;
import com.ragagent.storage.fileserve.StorageFileResolver;
import com.ragagent.storage.fileserve.WritableFileContentService;
import com.ragagent.storage.service.ResourceCatalogService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Docker 的**产物排水全链**（2026-09-24，收 §0.-9 的验证缺口）：
 * 容器内 /workspace/output 的文件 → {@link ArtifactCollector}（生产 source 适配
 * {@link SessionBoundArtifactSource} + {@link SessionBoundManager}，绑定存储按生产
 * 形态预置 docker 绑定）→ 字节落本地盘（local:// 引用）→ 去重与引用历史。
 *
 * <p><b>门控</b>：默认跳过。整链路跑法（同 DockerSandboxIntegrationTest）：
 * <pre>
 *   WEKNORA_SANDBOX_DOCKER_IT=true WEKNORA_SANDBOX_DOCKER_ENABLED=true \
 *   ./gradlew :server:test --tests "com.ragagent.session.service.ArtifactDrainDockerIT"
 * </pre>
 * 本机 OrbStack 注意 DOCKER_HOST（{@code unix:///$HOME/.orbstack/run/docker.sock}）。
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "WEKNORA_SANDBOX_DOCKER_IT", matches = "true")
@org.springframework.boot.test.context.SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArtifactDrainDockerIT {

    private static final String IMAGE = System.getenv().getOrDefault(
            "WEKNORA_SANDBOX_DOCKER_IMAGE", EffectiveConfig.DEFAULT_DOCKER_IMAGE);

    private static final long TENANT_ID = 10008L;
    private static final String SESSION_ID = "drain-it-" + UUID.randomUUID();

    private static DockerSandboxClient client;
    private static String containerId;
    private static SessionBoundArtifactSource source;

    @org.springframework.beans.factory.annotation.Autowired
    private StorageFileResolver resolver;
    @org.springframework.beans.factory.annotation.Autowired
    private ResourceCatalogService catalog;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeAll
    void buildChain(@org.junit.jupiter.api.io.TempDir Path tmp) {
        com.ragagent.TestSchema.createTables(jdbc);
        com.ragagent.TestSchema.resetData(jdbc);
        EffectiveConfig cfg = EffectiveConfig.defaultConfig();
        cfg.type = SandboxTypes.TYPE_DOCKER;
        cfg.dockerImage = IMAGE;
        cfg.allowPrivateEndpoints = true;
        client = DockerSandboxClient.forConfig(cfg);

        Map<String, String> metadata = Map.of(
                ConfigSandboxClient.ConfigSandboxes.METADATA_TENANT_ID, Long.toString(TENANT_ID),
                ConfigSandboxClient.ConfigSandboxes.METADATA_SESSION_ID, SESSION_ID,
                ConfigSandboxClient.ConfigSandboxes.METADATA_CONFIG_ID, "drain-it-config");
        SandboxSessionClient.Handle handle = client.create(new SandboxSessionClient.CreateRequest(
                IMAGE, SandboxSessionClient.TimeoutPolicy.serverDefault(
                        SandboxSessionClient.TimeoutAction.KILL),
                metadata, Map.of(), null, null));
        containerId = ((DockerSandboxClient.DockerHandle) handle).id();

        // 生产绑定形态：provider=docker + 容器 id 写进内存绑定存储；排水路径
        // （lookupSessionHandle）按绑定 connect 回容器，绝不 provisioning。
        SessionSandboxBinding binding = new SessionSandboxBinding();
        binding.version = RemoteSessionLifecycleVersionHolder.VERSION;
        binding.provider = SandboxTypes.TYPE_DOCKER;
        binding.tenantId = TENANT_ID;
        binding.sessionId = SESSION_ID;
        binding.sandboxId = containerId;
        binding.templateId = IMAGE;
        binding.configId = "drain-it-config";
        binding.createdAt = Instant.now();
        SessionSandboxBindingStore.MemorySessionSandboxBindingStore store =
                new SessionSandboxBindingStore.MemorySessionSandboxBindingStore();
        store.create(new SessionSandboxBindingStore.SessionSandboxKey(TENANT_ID, SESSION_ID),
                binding);

        SessionBoundManager manager = new SessionBoundManager(cfg, client, store,
                key -> true, "drain-it-config", true);
        source = SessionBoundArtifactSource.fromSandboxManager(manager, TENANT_ID);
        assertThat(source).as("Docker 管理器必须暴露会话文件面").isNotNull();
        baseDir = tmp;
        globalStore = resolver.globalFileService(tmp.toString());
    }

    private static Path baseDir;
    private static WritableFileContentService globalStore;

    @AfterAll
    void tearDown() {
        if (client != null && containerId != null) {
            try {
                client.delete(containerId);
            } catch (RuntimeException ignored) {
                // 尽力清理
            }
        }
    }

    @Test
    @Order(1)
    void drainCollectsContainerFilesToLocalStore(@TempDir Path tmp) throws Exception {
        SandboxSessionClient.Handle handle = client.connect(
                new SandboxSessionClient.ConnectRequest(containerId, null));
        SandboxSessionClient.ExecResult setup = client.exec(handle,
                new SandboxSessionClient.ExecRequest(null, "sh",
                        List.of("-c", "mkdir -p /workspace/output && "
                                + "printf 'report-body' > /workspace/output/report.txt && "
                                + "printf '{\"v\":1}' > /workspace/output/data.json"),
                        false, null, Map.of(), "/workspace", null,
                        java.time.Duration.ofSeconds(30)));
        assertThat(setup.exitCode()).isZero();

        // 生产写面：装饰服务 → resource:// 手柄 + web 之外的资源目录注册
        ArtifactCollector collector = new ArtifactCollector(source,
                (data, tenantId, name) -> globalStore.saveBytes(data, tenantId, name, false),
                sessionId -> List.of(),
                catalog::bind);

        List<MessageArtifact> artifacts = collector.collect(SESSION_ID, "drain-msg-1",
                TENANT_ID, OutputLinks.artifactOutputDir());

        assertThat(artifacts).as("两个产物文件都应被收集").hasSize(2);
        for (MessageArtifact art : artifacts) {
            assertThat(art.getUrl()).startsWith("resource://");
            assertThat(art.getSourcePath()).startsWith("/workspace/output/");
        }
        MessageArtifact report = artifacts.stream()
                .filter(a -> a.getFileName().equals("report.txt"))
                .findFirst().orElseThrow();
        // 物理文件经资源目录解析后可读（provider 作用域路径 local://… 相对 baseDir）
        var resolved = catalog.resolvePath(report.getUrl());
        assertThat(resolved.error()).isFalse();
        Path physical = baseDir.resolve(
                resolved.physicalPath().substring("local://".length()));
        assertThat(Files.readAllBytes(physical))
                .isEqualTo("report-body".getBytes(StandardCharsets.UTF_8));
        // 绑定行：message owner + artifact 关系
        Integer bindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_bindings WHERE owner_type='message' "
                        + "AND owner_id='drain-msg-1' AND relation='artifact'",
                Integer.class);
        assertThat(bindings).isEqualTo(2);

        // 去重：已知产物（同 source_path+mtime）不再重复收集
        ArtifactCollector withStore = new ArtifactCollector(source,
                (data, tenantId, name) -> globalStore.saveBytes(data, tenantId, name, false),
                sessionId -> artifacts,
                catalog::bind);
        // Go：全部已知 → make(MessageArtifacts, 0, 0) = 空切片（非 nil）；
        // nil 只用于「无条目/无 source」的降级分支
        assertThat(withStore.collect(SESSION_ID, "drain-msg-2", TENANT_ID,
                OutputLinks.artifactOutputDir()))
                .as("全部产物已被记录，第二轮应返回空切片").isEmpty();

        // 引用历史：答案文本显式引用 resource:// 手柄时回带该产物
        List<MessageArtifact> history = withStore.referencedHistory(SESSION_ID, "drain-msg-3",
                "请下载 " + report.getUrl());
        assertThat(history).as("被引用的产物应回带").isNotNull();
        assertThat(history).extracting(MessageArtifact::getFileName).containsExactly("report.txt");
    }

    @Test
    @Order(2)
    void collectWithMissingOutputDirDegrades() {
        // 输出目录不存在（容器已回收 / skill 写去别处）→ collect 返回 null
        ArtifactCollector collector = new ArtifactCollector(source,
                (data, tenantId, name) -> globalStore.saveBytes(data, tenantId, name, false),
                sessionId -> List.of(), catalog::bind);
        assertThat(collector.collect(SESSION_ID, "drain-msg-4", TENANT_ID,
                "/workspace/output/does-not-exist")).isNull();
    }

    /** 版本常量中转（避免测试类里散落魔法数）。 */
    private static final class RemoteSessionLifecycleVersionHolder {
        static final int VERSION =
                com.ragagent.sandbox.runtime.RemoteSessionLifecycle.SESSION_SANDBOX_BINDING_VERSION;
    }
}
