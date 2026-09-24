package com.ragagent.storage.fileserve;

import java.io.IOException;

/**
 * 文件服务的读取面（对照 Go {@code interfaces.FileService} 的 GetFile / GetFileURL
 * 两个方法——W5c 文件代理面消费的最小端口）。
 *
 * <p>与 storageurl 包的 {@link com.ragagent.storageurl.FileService}（只有
 * GetFileURL 的重写器端口）刻意分开：本端口是 HTTP 流式面，归属
 * {@code com.ragagent.storage} 的实现。八种 provider 都有真实实现
 * （local 走 {@link LocalFileContentService}；云 provider 经
 * {@code ProviderFileContentService} 适配 A3 的 provider 层）。</p>
 */
public interface FileContentService {

    String LOCAL_SCHEME = "local://";

    /** 对照 Go {@code GetFile(ctx, filePath)}：打开失败/不存在 → IOException（路由折成 404）。 */
    FileTransport.OpenedFile getFile(String filePath) throws IOException;

    /** 对照 Go {@code GetFileURL(ctx, filePath)}。 */
    String getFileURL(String filePath) throws IOException;
}
