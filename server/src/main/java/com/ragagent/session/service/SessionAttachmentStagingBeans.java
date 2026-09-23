package com.ragagent.session.service;

import com.ragagent.session.mapper.TemporaryDocumentRepository;
import com.ragagent.storage.fileserve.FileProxyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 附件 staging 的生产装配（对照 Go container 的 NewSessionAttachmentStager Provide）：
 * 文件服务取 FileProxyService 的进程级默认（local 基座 + resource:// 装饰视图）。
 * 类本体保持纯构造（测试直喂替身），Spring 装配集中在此。
 */
@Configuration
public class SessionAttachmentStagingBeans {

    @Bean
    public SessionAttachmentStagingService sessionAttachmentStagingService(
            SessionSandboxExecutionService sandboxExecution,
            TemporaryDocumentRepository temporaryDocuments,
            FileProxyService fileProxy) {
        return new SessionAttachmentStagingService(
                sandboxExecution, temporaryDocuments, fileProxy.globalFileService());
    }
}
