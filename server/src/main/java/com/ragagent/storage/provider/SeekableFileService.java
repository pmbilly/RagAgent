package com.ragagent.storage.provider;

import java.io.IOException;

/**
 * provider 服务的**可选**能力：SDK 返回的对象可随机读（用 Range 请求实现 Seek），
 * 从而走 {@code Accept-Ranges: bytes} + Range/206 的随机读支路——目前只有
 * **minio** 一族支持；s3/cos/tos/obs/ks3 的 SDK body 只能顺序读（走流式）。
 *
 * <p>这是 **SDK 类型差异**，不是产品语义：别把它"顺手统一"到所有云 provider
 * （那会让其余 provider 被迫整对象缓冲）。</p>
 */
public interface SeekableFileService extends FileService {

    /** 本 provider 是否支持随机读（按 SDK 能力判定）。 */
    default boolean seekableReads() {
        return true;
    }

    /** 打开可随机读的字节源（实现见 minio 形态：HeadObject 取长度 + 带 Range 的 GetObject）。 */
    SeekableSource openSeekable(String filePath) throws IOException;
}
