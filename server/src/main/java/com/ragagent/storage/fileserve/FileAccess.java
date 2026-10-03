package com.ragagent.storage.fileserve;

/**
 * 授权后的文件定位符：请求级的中间结果，存储在它打开之前；物理定位符不能替代
 * KB/消息授权。
 */
public record FileAccess(long ownerTenantId, String path, String filename, String storageBackendId) {
}
