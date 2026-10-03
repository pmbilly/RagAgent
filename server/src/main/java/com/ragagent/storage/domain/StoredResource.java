package com.ragagent.storage.domain;

import java.time.OffsetDateTime;

/**
 * 资源注册表行，表名 {@code "resources"}（勿按类名推断成别的表名）。
 *
 * <p>本载体服务文件代理面的<b>读路径</b>（ResolvePath / ResolveAccessGrant /
 * 引用绑定查询）；Register/Bind 写路径见 {@code ResourceCatalogService}。</p>
 */
public class StoredResource {

    private String id;
    private String handle;
    private long tenantId;
    private String storageBackendId;
    private String provider;
    private String physicalPath;
    private String locationHash;
    private String kind;
    private String mimeType;
    private String originalName;
    private long size;
    private String contentHash;
    private String lifecycle;
    private OffsetDateTime expiresAt;
    private String state;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public static final String STATE_ACTIVE = "active";
    public static final String STATE_DELETED = "deleted";
    public static final String LIFECYCLE_PERSISTENT = "persistent";
    public static final String LIFECYCLE_TEMPORARY = "temporary";

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public String getHandle() { return handle; }
    public void setHandle(String v) { handle = v; }
    public long getTenantId() { return tenantId; }
    public void setTenantId(long v) { tenantId = v; }
    public String getStorageBackendId() { return storageBackendId; }
    public void setStorageBackendId(String v) { storageBackendId = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v; }
    public String getPhysicalPath() { return physicalPath; }
    public void setPhysicalPath(String v) { physicalPath = v; }
    public String getLocationHash() { return locationHash; }
    public void setLocationHash(String v) { locationHash = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { kind = v; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String v) { mimeType = v; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String v) { originalName = v; }
    public long getSize() { return size; }
    public void setSize(long v) { size = v; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String v) { contentHash = v; }
    public String getLifecycle() { return lifecycle; }
    public void setLifecycle(String v) { lifecycle = v; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime v) { expiresAt = v; }
    public String getState() { return state; }
    public void setState(String v) { state = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }
}
