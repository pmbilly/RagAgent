package com.ragagent.storage.fileserve;

import java.io.IOException;

/**
 * 文件服务的写字节面（对照 Go {@code interfaces.FileService} 的
 * {@code SaveBytes / DeleteFile}，internal/types/interfaces/file.go L15-27）。
 *
 * <p>W5c 的 {@link FileContentService} 只翻读端口；chat 产物、agent 抓取页
 * （AgentWebPages）与 ArtifactCollector 的上传走本端口。装饰器
 * {@code StorageFileResolver.DecoratedFileService} 实现它：写字节先落物理
 * provider，再经资源注册表换成稳定 {@code resource://} 手柄
 * （对照 Go {@code resourceCatalogFileService.SaveBytes}）。</p>
 */
public interface WritableFileContentService extends FileContentService {

    /**
     * 对照 Go {@code SaveBytes(ctx, data, tenantID, fileName, temp)}：落盘并返回
     * {@code local://…} 物理引用（装饰器返回 {@code resource://…}）。temp 对
     * local 存储无效果（无自动过期支持——Go 注释原文）。
     */
    String saveBytes(byte[] data, long tenantId, String fileName, boolean temp) throws IOException;

    /** 对照 Go {@code DeleteFile}：删除物理文件/对象。 */
    void deleteFile(String filePath) throws IOException;
}
