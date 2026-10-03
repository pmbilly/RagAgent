package com.ragagent.storage.provider;

import java.io.IOException;
import java.io.InputStream;

/**
 * 可随机读的字节源。
 *
 * <p>流式响应按本能力分流：可 seek 的（本地文件、minio 对象）走
 * {@code Accept-Ranges: bytes} + Range/206；只能顺序读的（aws-sdk 族的 body）
 * 走流式 + {@code Accept-Ranges: none}。</p>
 *
 * <p>本接口是 provider 侧对"能 seek"能力的投影：{@link #size()} 取对象长度、
 * {@link #open(long)} 从偏移处打开流（实现可用 Range 请求，不必缓冲整个对象）。</p>
 */
public interface SeekableSource {

    /** 对象总长度。 */
    long size() throws IOException;

    /** 从 {@code offset} 起打开的流；调用方负责关闭。 */
    InputStream open(long offset) throws IOException;
}
