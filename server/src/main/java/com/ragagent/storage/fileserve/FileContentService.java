package com.ragagent.storage.fileserve;

import java.io.IOException;

/**
 * 文件服务的读取面（{@code GetFile / GetFileURL} 两个方法——文件代理面消费的最小端口）。
 *
 * <p>与 storageurl 包的 {@link com.ragagent.storage.support.FileService}（只有
 * GetFileURL 的重写器端口）刻意分开：本端口是 HTTP 流式面，归属
 * {@code com.ragagent.storage} 的实现。八种 provider 都有真实实现
 * （local 走 {@link LocalFileContentService}；云 provider 经
 * {@code ProviderFileContentService} 适配 A3 的 provider 层）。</p>
 */
public interface FileContentService {

    String LOCAL_SCHEME = "local://";

    /** 打开失败/不存在 → IOException（路由折成 404）。 */
    FileTransport.OpenedFile getFile(String filePath) throws IOException;

    /** 解析为可访问的 URL（未配置外部访问时可能是 {@code local://} 路径形态）。 */
    String getFileURL(String filePath) throws IOException;
}
