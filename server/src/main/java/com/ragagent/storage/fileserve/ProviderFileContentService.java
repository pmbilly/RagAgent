package com.ragagent.storage.fileserve;

import java.io.IOException;
import java.io.InputStream;

import com.ragagent.storage.provider.FileService;
import com.ragagent.storage.provider.SeekableFileService;
import com.ragagent.storage.provider.SeekableSource;

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
 * <p><b>W5γ5.1：读面已流式化</b>——{@code getFile} 把 provider 的 {@code InputStream}
 * 直接交给 {@link FileTransport.OpenedFile#ofStream}（Go 的 SDK body 直转响应），
 * <b>不再整对象入堆</b>；{@code OpenedFile} 的内存字节形态保留给"手工写响应/需要 bytes"
 * 的调用方（知识 byte[] 出口、图片 base64 等）。打开动作仍是即时的——provider 的真调用
 * 与错误在此刻暴露（照 Go 的 {@code GetFile}），保证 404 语义不变。</p>
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
        try {
            // 照 Go 的分流：SDK 对象是 io.ReadSeeker 的 provider（minio-go）走 ServeContent
            // （Accept-Ranges: bytes + Range/206）；其余（aws-sdk 族的 body）走流式。
            // 两种形态都在此刻"打开"（错误即刻暴露 → 404），只把读体交给 HTTP 层。
            if (inner instanceof SeekableFileService seekable && seekable.seekableReads()) {
                SeekableSource source = seekable.openSeekable(filePath);
                return FileTransport.OpenedFile.ofSeekableSource(source, source.size());
            }
            InputStream in = inner.getFile(filePath);
            return FileTransport.OpenedFile.ofStream(
                    in == null ? InputStream.nullInputStream() : in, 0);
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
