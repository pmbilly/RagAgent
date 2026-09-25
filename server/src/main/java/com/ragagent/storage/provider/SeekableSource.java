package com.ragagent.storage.provider;

import java.io.IOException;
import java.io.InputStream;

/**
 * 可随机读的字节源（对照 Go 的 {@code io.ReadSeeker} 存储对象）。
 *
 * <p>Go 的 {@code filetransport.Serve} 按 {@code reader.(io.ReadSeeker)} 分流：能 seek 的
 * （本地 {@code *os.File}、minio-go 的 {@code *minio.Object}）走 {@code http.ServeContent}
 * ——{@code Accept-Ranges: bytes} + Range/206；不能 seek 的（aws-sdk 族的 body 是
 * {@code io.ReadCloser}）走流式 + {@code Accept-Ranges: none}。</p>
 *
 * <p>本接口是 provider 侧对"能 seek"能力的投影：{@link #size()} 取对象长度、
 * {@link #open(long)} 从偏移处打开流（实现可用 Range 请求，不必缓冲整个对象）。</p>
 */
public interface SeekableSource {

    /** 对象总长度（Go 的 {@code Seek(0, io.SeekEnd)}）。 */
    long size() throws IOException;

    /** 从 {@code offset} 起打开的流；调用方负责关闭。 */
    InputStream open(long offset) throws IOException;
}
