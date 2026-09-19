package com.ragagent.sandbox.service;

/**
 * skill 归档的字节存储接缝（对照 Go 的 {@code interfaces.FileService} 面里
 * install 管线实际用到的三个方法：SaveBytes / GetFile / DeleteFile）。
 *
 * <p>Go 经 StorageBackendResolver 按租户解析文件服务；Java 侧本批接本地落盘实现
 * {@code LocalSkillBundleStore}（测试配置 {@code weknora.storage.local-base-dir=./build/test-files}，
 * 生产默认 /data/files，与 Go 的 LOCAL_STORAGE_BASE_DIR 兜底一致）。对象存储后端
 * （minio/oss/s3）的解析面属波 4 接缝——届时补实现即可，调用方形状不变。</p>
 *
 * <p>读不到的引用必须抛（对照 Go：GetFile 错误让 trySkillBundle 报"不可用"，
 * 而不是把空字节当内容）。</p>
 */
public interface SkillBundleStore {

    /** 对照 SaveBytes：把归档落在租户命名空间下的 key，返回可回读的引用。 */
    String save(long tenantId, String key, byte[] archive);

    /** 对照 GetFile：按引用读回字节。读不到 / 引用越界时抛 RuntimeException。 */
    byte[] load(long tenantId, String ref);

    /** 对照 DeleteFile：尽力而为。 */
    void delete(long tenantId, String ref);
}
