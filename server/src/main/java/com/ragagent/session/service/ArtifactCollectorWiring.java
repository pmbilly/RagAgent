package com.ragagent.session.service;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ragagent.agent.tools.RemoteDirEntry;
import com.ragagent.session.mapper.MessageRepository;
import com.ragagent.storage.fileserve.StorageFileResolver;
import com.ragagent.storage.fileserve.WritableFileContentService;
import com.ragagent.storage.service.ResourceCatalogService;

/**
 * ArtifactCollector / AgentWebPages 的生产装配（2026-09-24 存储写字节面批）。
 *
 * <p>对照 Go {@code container.go L316-321 Provide(NewArtifactCollectorFromSandboxManager)}
 * + {@code initFileService}（全局 {@code resourceCatalogFileService}）：字节落盘面
 * = 进程级装饰服务（resource:// 手柄），会话产物存储 = message 仓储投影
 * （Go NewMessageRepoArtifactStore），资源绑定 = ResourceCatalogService::bind。</p>
 *
 * <p>与 Go 的结构差异（备案）：Go 的 collector 是**进程单例**——process-wide
 * sandboxMgr 断言成 source，会话绑定在 SessionBoundManager 内部按 sessionID 完成。
 * Java 的 bound manager 按回合解析，故 collector 按**回合**构造
 * （{@link #forTurn}，重解析走 {@code resolveForExecution}——Go sessionSource
 * 「按 pin 重新解析」语义）。fileService 恒在 → collector 恒非空；source 为 null
 * （沙箱后端无会话文件面/解析失败）时 Collect 走「无可附加产物」降级链。</p>
 */
@Component
public class ArtifactCollectorWiring {

    private static final Logger log = LoggerFactory.getLogger(ArtifactCollectorWiring.class);

    private final StorageFileResolver resolver;
    private final ResourceCatalogService catalog;
    private final MessageRepository messageRepo;
    private final SessionSandboxExecutionService sandboxExecution;
    private final String localBaseDir;

    public ArtifactCollectorWiring(StorageFileResolver resolver,
            ResourceCatalogService catalog,
            MessageRepository messageRepo,
            SessionSandboxExecutionService sandboxExecution,
            @Value("${weknora.storage.local-base-dir:${LOCAL_STORAGE_BASE_DIR:/data/files}}")
            String localBaseDir) {
        this.resolver = resolver;
        this.catalog = catalog;
        this.messageRepo = messageRepo;
        this.sandboxExecution = sandboxExecution;
        this.localBaseDir = localBaseDir;
    }

    private WritableFileContentService globalStorage;

    /** 进程级装饰服务（对照 Go initFileService；lazy：避免装配期碰文件系统）。 */
    public synchronized WritableFileContentService globalStorage() {
        if (globalStorage == null) {
            globalStorage = resolver.globalFileService(localBaseDir);
        }
        return globalStorage;
    }

    /**
     * 回合产物收集器。collector 恒非空（Go：fileService 在 → collector 在）；
     * source 的沙箱解析**惰性到首次列文件**——Go 的 source 是 process-wide manager，
     * 会话绑定在 ListSessionFiles 内部按 pin 完成，回合开始时绝不提前 provisioning。
     * 解析失败 → 空列表 → Collect 走「无可附加产物」降级，不阻断完成路径。
     */
    public ArtifactCollector forTurn(long tenantId, String sessionId, String sandboxConfigId) {
        return new ArtifactCollector(lazySource(tenantId, sessionId, sandboxConfigId),
                fileStore(), artifactStore(), binder());
    }

    /** 首次使用时解析会话沙箱的文件源（对照 Go sessionSource 的 collect 时重解析）。 */
    private ArtifactCollector.SandboxArtifactSource lazySource(long tenantId, String sessionId,
            String sandboxConfigId) {
        return new ArtifactCollector.SandboxArtifactSource() {
            private volatile SessionBoundArtifactSource delegate;
            private volatile boolean resolved;

            private SessionBoundArtifactSource resolve() {
                if (!resolved) {
                    synchronized (this) {
                        if (!resolved) {
                            try {
                                var r = sandboxExecution.resolveForExecution(
                                        tenantId, sessionId, sandboxConfigId);
                                delegate = SessionBoundArtifactSource.fromSandboxManager(
                                        r.manager(), tenantId);
                            } catch (RuntimeException e) {
                                log.warn("[ArtifactCollector] session sandbox resolve failed "
                                        + "(drain degrades to no-op): session={} err={}",
                                        sessionId, e.getMessage());
                            }
                            resolved = true;
                        }
                    }
                }
                return delegate;
            }

            @Override
            public java.util.List<RemoteDirEntry> listSessionFiles(String sessionId, String path) {
                SessionBoundArtifactSource d = resolve();
                return d == null ? java.util.List.of() : d.listSessionFiles(sessionId, path);
            }

            @Override
            public byte[] readSessionFile(String sessionId, String path) {
                SessionBoundArtifactSource d = resolve();
                return d == null ? new byte[0] : d.readSessionFile(sessionId, path);
            }
        };
    }

    /** 字节上传面：全局装饰服务（resource:// 手柄）+ SaveBytes 的 temp 恒 false。 */
    public ArtifactCollector.ArtifactFileStore fileStore() {
        return (data, tenantId, storageName) -> globalStorage().saveBytes(data, tenantId, storageName, false);
    }

    /** 去重面：message 仓储的会话产物投影（对照 NewMessageRepoArtifactStore）。 */
    public ArtifactCollector.SessionArtifactStore artifactStore() {
        return messageRepo::getSessionArtifacts;
    }

    /** 绑定面：资源目录 Bind。 */
    public ArtifactCollector.ResourceCatalogBinder binder() {
        return catalog::bind;
    }

    /**
     * agent 抓取页（web:// 快照）的存储面：SaveBytes 走同一装饰服务；
     * 读按 resource:// 解析到物理路径后整读（8MB 读限由 AgentWebPages 施加）。
     */
    public AgentWebPages.Store webPageStore() {
        return new AgentWebPages.Store() {
            @Override
            public String saveBytes(byte[] data, long tenantId, String name) throws Exception {
                return globalStorage().saveBytes(data, tenantId, name, false);
            }

            @Override
            public byte[] readFile(String reference) throws Exception {
                // 装饰服务内置 resource:// 解析 + local:// 归一（Go 的 inner.GetFile 链）；
                // OpenedFile 是不可变 record，读 seekable 不需要关闭
                var opened = globalStorage().getFile(reference);
                if (opened.bytes() != null) {
                    return opened.bytes();
                }
                return java.nio.file.Files.readAllBytes(opened.seekable());
            }

            @Override
            public void deleteFile(String reference) {
                try {
                    globalStorage().deleteFile(reference);
                } catch (IOException e) {
                    log.warn("[AgentWebPages] delete saved page failed: {} err={}", reference, e.getMessage());
                }
            }
        };
    }

    /** agent 抓取页的绑定面：web_page 关系绑到 assistant 消息。 */
    public AgentWebPages.Binding webPageBinding() {
        return catalog::bind;
    }

}
