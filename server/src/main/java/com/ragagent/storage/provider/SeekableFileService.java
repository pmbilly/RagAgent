package com.ragagent.storage.provider;

import java.io.IOException;

/**
 * provider 服务的**可选**能力：其 Go SDK 返回的对象是 {@code io.ReadSeeker}，
 * 因此 Go 的 {@code filetransport.Serve} 会走 {@code http.ServeContent}
 * （{@code Accept-Ranges: bytes} + Range/206）——目前只有 **minio-go** 一族
 * （{@code *minio.Object} 用 Range 请求实现 Seek）；s3/cos/tos/obs/ks3 的 aws-sdk
 * body 是 {@code io.ReadCloser}（Go 也走流式）。
 *
 * <p>这是 **SDK 类型差异**，不是产品语义：别把它"顺手统一"到所有云 provider
 * （那会让 Java 对 s3/cos/… 也吐 {@code bytes}，与 Go 的 {@code none} 相反）。</p>
 */
public interface SeekableFileService extends FileService {

    /** 本 provider 在 Go 侧是否走 ServeContent（照 SDK 类型差异判定）。 */
    default boolean seekableReads() {
        return true;
    }

    /** 打开可随机读的字节源（实现见 minio 形态：HeadObject 取长度 + 带 Range 的 GetObject）。 */
    SeekableSource openSeekable(String filePath) throws IOException;
}
