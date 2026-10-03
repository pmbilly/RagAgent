package com.ragagent.session.service;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ragagent.storage.fileserve.StorageFileResolver;
import com.ragagent.storage.fileserve.WritableFileContentService;
import com.ragagent.storage.service.ResourceCatalogService;

/**
 * AgentWebPages（agent 抓取页 web:// 快照）的生产装配。
 *
 * <p>沙箱产物排水器随沙箱裁剪退役；本类只剩进程级字节落盘
 * 面与 web_page 绑定面。</p>
 */
@Component
public class ArtifactCollectorWiring {

    private static final Logger log = LoggerFactory.getLogger(ArtifactCollectorWiring.class);

    private final StorageFileResolver resolver;
    private final ResourceCatalogService catalog;
    private final String localBaseDir;

    public ArtifactCollectorWiring(StorageFileResolver resolver,
            ResourceCatalogService catalog,
            @Value("${weknora.storage.local-base-dir:${LOCAL_STORAGE_BASE_DIR:/data/files}}")
            String localBaseDir) {
        this.resolver = resolver;
        this.catalog = catalog;
        this.localBaseDir = localBaseDir;
    }

    private WritableFileContentService globalStorage;

    /** 进程级装饰服务（lazy：避免装配期碰文件系统）。 */
    public synchronized WritableFileContentService globalStorage() {
        if (globalStorage == null) {
            globalStorage = resolver.globalFileService(localBaseDir);
        }
        return globalStorage;
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
                // 装饰服务内置 resource:// 解析 + local:// 归一；
                // 三形态通吃（流形态读完即关），整读为字节
                return globalStorage().getFile(reference).readAllBytes();
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
