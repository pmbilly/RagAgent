package com.ragagent.storage.support;

/**
 * 把一个存储引用映射到拥有它的 {@link FileService}。
 *
 * <p>引用自带 provider scheme 与可选的存储后端 id，所以<b>一条回答可以跨多个后端</b>。</p>
 */
public interface Resolver {

    /** 没有后端能服务该引用时返回 {@code null}。 */
    FileService resolveFileService(String ref);
}
