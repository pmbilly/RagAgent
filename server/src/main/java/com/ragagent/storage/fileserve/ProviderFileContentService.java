package com.ragagent.storage.fileserve;

import java.io.IOException;
import java.io.InputStream;

import com.ragagent.storage.provider.FileService;

/**
 * 把 A3 的 {@link com.ragagent.storage.provider.FileService}（八个 provider 的真实实现）
 * 适配成本 HTTP 流式面的 {@link WritableFileContentService}——A3-3 接线件。
 *
 * <p>映射关系（方法一一对应，无中间语义）：</p>
 * <ul>
 *   <li>{@code getFile} → {@code getFile}（读满后交 {@link FileTransport.OpenedFile#ofBytes}）</li>
 *   <li>{@code getFileURL} → {@code getFileURL}（oss/cos/tos/s3 族的预签名 URL）</li>
 *   <li>{@code saveBytes} → {@code saveBytes}</li>
 *   <li>{@code deleteFile} → {@code deleteFile}</li>
 * </ul>
 *
 * <p><b>与 Go 的一处差异（备案）</b>：Go 的 {@code GetFile} 返回 {@code io.ReadCloser}，
 * 由 HTTP 层流式转发（大对象不占内存）；Java 侧的 {@code OpenedFile} 只有
 * "磁盘路径（可 seek）"与"内存字节"两种形态，云对象落不到前者，故走
 * {@code ofBytes}——<b>整对象读入堆内存</b>。locally-served 的小文件（图片/附件）无碍；
 * 超大对象的流式化需要给 {@code FileTransport} 补第三种形态，属独立课题。</p>
 */
public class ProviderFileContentService implements WritableFileContentService {

    private final FileService inner;

    public ProviderFileContentService(FileService inner) {
        this.inner = inner;
    }

    /** 底层 provider 服务（日志/诊断用）。 */
    public FileService provider() {
        return inner;
    }

    @Override
    public FileTransport.OpenedFile getFile(String filePath) throws IOException {
        try (InputStream in = inner.getFile(filePath)) {
            byte[] data = in == null ? new byte[0] : in.readAllBytes();
            return FileTransport.OpenedFile.ofBytes(data);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            // 对照 Go：打开失败/不存在 → IOException（路由折成 404）
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    @Override
    public String getFileURL(String filePath) throws IOException {
        try {
            return inner.getFileURL(filePath);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    @Override
    public String saveBytes(byte[] data, long tenantId, String fileName, boolean temp)
            throws IOException {
        try {
            return inner.saveBytes(data, tenantId, fileName, temp);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    @Override
    public void deleteFile(String filePath) throws IOException {
        try {
            inner.deleteFile(filePath);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }
}
