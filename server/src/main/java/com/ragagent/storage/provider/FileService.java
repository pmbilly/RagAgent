package com.ragagent.storage.provider;

import java.io.InputStream;

/**
 * 文件服务接口（对照 Go {@code interfaces.FileService}，internal/types/interfaces/file.go）：
 * 保存/读取/删除/复制 + 连通性自检 + 下载 URL。
 *
 * <p>路径一律是 <b>provider:// 形态</b>（如 {@code local://7/kb-1/1712.png}、{@code s3://…}）——
 * 由各后端自己解析；跨后端复制由各后端拒绝（{@link CrossBackendCopyException}），
 * 与 Go 的 {@code ErrCrossBackendCopy} 同义。</p>
 *
 * <p><b>错误通道</b>：Go 的 {@code error} 折叠为运行时异常（与 Java 侧既有端口约定一致）；
 * 跨后端复制用专门的 {@link CrossBackendCopyException} 以便调用方按类型判定。</p>
 */
public interface FileService {

    /**
     * 上传件的最小面（对照 Go {@code *multipart.FileHeader} 的 Filename/Size/Open/Header）。
     *
     * <p>{@code contentType} 对应 Go 从 multipart 头部取的 {@code Content-Type}：
     * 为空时各后端按扩展名推断（照 Go 的 {@code GetContentTypeByExt} 兜底）。</p>
     */
    record UploadFile(String fileName, long size,
                      java.util.function.Supplier<InputStream> opener,
                      String contentType) {

        public UploadFile {
            contentType = contentType == null ? "" : contentType.trim();
        }

        /** 兼容构造：不带内容类型（由后端按扩展名推断）。 */
        public UploadFile(String fileName, long size,
                          java.util.function.Supplier<InputStream> opener) {
            this(fileName, size, opener, "");
        }
    }

    /** 对照 {@code CheckConnectivity}：后端可达且配置正确（目录存在 / bucket 可访问）。 */
    void checkConnectivity();

    /** 对照 {@code SaveFile}：存上传件，返回 {@code provider://} 路径。 */
    String saveFile(UploadFile file, long tenantId, String knowledgeId);

    /**
     * 对照 {@code SaveBytes}：存字节数据，返回 {@code provider://} 路径。
     * {@code temp=true} 表示临时区（可能过期；本地后端忽略该参数——照 Go）。
     */
    String saveBytes(byte[] data, long tenantId, String fileName, boolean temp);

    /** 对照 {@code GetFile}：按路径取文件（调用方负责关闭）。 */
    InputStream getFile(String filePath);

    /** 对照 {@code GetFileURL}：可直接加载的 http(s) URL；不支持时返回 {@code provider://} 路径。 */
    String getFileURL(String filePath);

    /** 对照 {@code DeleteFile}。 */
    void deleteFile(String filePath);

    /** 对照 {@code CopyFile}：复制到 {@code (tenantId, knowledgeId)} 名下的<b>新对象</b>。 */
    String copyFile(String srcPath, long tenantId, String knowledgeId);

    /** 对照 Go 的 {@code ErrCrossBackendCopy}：源路径属于别的 provider。 */
    class CrossBackendCopyException extends RuntimeException {
        public CrossBackendCopyException(String message) {
            super(message);
        }
    }
}
