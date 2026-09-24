package com.ragagent.knowledge.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.storage.fileserve.StorageFileResolver;
import com.ragagent.storage.fileserve.StoragePaths;
import com.ragagent.storage.provider.FileService;

/**
 * 租户感知的文件存储门面（A3-3 尾批）——知识上传 / 读取 / 删除的**唯一入口**。
 *
 * <h2>为什么需要</h2>
 * <p>接线前这三件事直连 {@link LocalStorageService}（本地盘），配了云后端的租户也照落本地：
 * 库里的 {@code file_path} 恒为 {@code resource://{tenant}/{knowledge}/{name}}。本门面按
 * <b>引用形态</b>分流：</p>
 *
 * <ul>
 *   <li><b>本地</b>（无 scheme / {@code local://} / {@code resource://} 或租户 default
 *       provider 为空或 {@code local}）：<b>原样</b>走 {@link LocalStorageService}——
 *       {@code resource://} 是 golden 锁定的落盘契约，不能换成 provider 的 {@code local://…}
 *       （换了会让既有行读不回来、golden 全红）；</li>
 *   <li><b>云</b>（引用带 {@code cos://}/{@code s3://}/{@code oss://}… 或租户 default
 *       provider 是云）：走 A3 的 provider 服务——{@code SaveFile} 落
 *       {@code {prefix}{tenant}/{knowledge}/{uuid}{ext}}、{@code GetFile} 读回、
 *       {@code DeleteFile} 删对象，与 Go 的 {@code fileService.SaveFile/GetFile} 同形。</li>
 * </ul>
 *
 * <h2>失败姿态（照 Go）</h2>
 * <ul>
 *   <li>上传时租户配了云却解析失败（配置不全/凭据错）→ <b>抛出</b>，不静默回落本地
 *       （否则"配置错了却在本地悄悄成功"是最难查的一类事故）；</li>
 *   <li>删除是 best-effort（对照 Go 的日志即弃）：本地目录树恒清，云对象失败只记日志。</li>
 * </ul>
 */
@Service
public class TenantFileStorage {

    private static final Logger log = LoggerFactory.getLogger(TenantFileStorage.class);

    private final LocalStorageService local;
    private final StorageFileResolver resolver;
    private final TenantService tenantService;
    private final String localBaseDir;

    public TenantFileStorage(LocalStorageService local,
                             StorageFileResolver resolver,
                             TenantService tenantService,
                             @Value("${weknora.storage.local-base-dir:${LOCAL_STORAGE_BASE_DIR:/data/files}}")
                                     String localBaseDir) {
        this.local = local;
        this.resolver = resolver;
        this.tenantService = tenantService;
        this.localBaseDir = localBaseDir;
    }

    // ── 写 ──────────────────────────────────────────────────────────────────

    /**
     * 保存知识文件（对照 Go {@code CreateKnowledgeFromFile} 的
     * {@code fileService.SaveFile(file, tenantID, knowledgeID)}）→ 落库的 file_path。
     */
    public String save(long tenantId, String knowledgeId, String fileName, byte[] content) {
        StorageFileResolver.ProviderResolution resolved = resolveProvider(tenantId, null);
        if (resolved.ok()) {
            String path = resolved.service().saveFile(
                    new FileService.UploadFile(fileName, content.length,
                            () -> new ByteArrayInputStream(content), ""),
                    tenantId, knowledgeId);
            log.info("stored knowledge file on provider {}: knowledge_id={} path={}",
                    resolved.provider(), knowledgeId, path);
            return path;
        }
        if (resolved.error() != null) {
            throw new IllegalStateException(
                    "resolve storage provider failed: " + resolved.error());
        }
        return local.save(tenantId, knowledgeId, fileName, content);
    }

    // ── 读 ──────────────────────────────────────────────────────────────────

    /**
     * 读回字节（对照 Go {@code fileService.GetFile}）。本地引用保持
     * {@link LocalStorageService#read(String)} 的既有语义（只认 {@code resource://}）。
     */
    public byte[] read(long tenantId, String filePath) {
        String provider = StoragePaths.parseProviderScheme(filePath);
        if (provider.isEmpty()) {
            return local.read(filePath);
        }
        if (isLocalScheme(provider)) {
            // {@code local://…}（Go local provider 的原生形态）也归本地：
            // LocalStorageService.readChecked 认得它，read 只认 resource://。
            return local.readChecked(filePath);
        }
        return readFromProvider(tenantId, provider, filePath);
    }

    /**
     * 读回字节（对照 Go {@code GetKnowledgeFile} 的错误信封）。非 provider 引用走
     * {@link LocalStorageService#readChecked(String)}（多 scheme 容忍 + 路径穿越守卫 +
     * {@code Failed to retrieve file} 信封）；provider 引用失败折成同一个信封。
     */
    public byte[] readChecked(long tenantId, String filePath) {
        String provider = StoragePaths.parseProviderScheme(filePath);
        if (provider.isEmpty() || isLocalScheme(provider)) {
            return local.readChecked(filePath);
        }
        try {
            return readFromProvider(tenantId, provider, filePath);
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null ? e.toString() : e.getMessage();
            throw new BizException(AppError.internal("Failed to retrieve file")
                    .withDetails(detail));
        }
    }

    // ── 删 ──────────────────────────────────────────────────────────────────

    /**
     * 删除知识的所有文件（对照 Go {@code DeleteKnowledge} 的 best-effort）：
     * 本地目录树恒清（既有语义），{@code filePath} 是 provider 引用时额外删对象。
     */
    public void delete(long tenantId, String knowledgeId, String filePath) {
        local.deleteTree(tenantId, knowledgeId);
        String provider = StoragePaths.parseProviderScheme(filePath);
        if (provider.isEmpty() || isLocalScheme(provider)) {
            return;
        }
        try {
            StorageFileResolver.ProviderResolution resolved = resolveProvider(tenantId, provider);
            if (!resolved.ok()) {
                log.warn("delete provider object skipped: provider={} err={}",
                        provider, resolved.error());
                return;
            }
            resolved.service().deleteFile(filePath);
            log.info("deleted provider object: provider={} path={}", provider, filePath);
        } catch (RuntimeException e) {
            log.warn("delete provider object failed: path={} err={}", filePath, e.toString());
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** {@code local} 也是 provider scheme，但它的读法就是本地盘（照 Go 的 local provider）。 */
    private static boolean isLocalScheme(String provider) {
        return "local".equalsIgnoreCase(provider);
    }

    private byte[] readFromProvider(long tenantId, String provider, String filePath) {
        StorageFileResolver.ProviderResolution resolved = resolveProvider(tenantId, provider);
        if (!resolved.ok()) {
            throw new IllegalStateException("read from provider \"" + provider + "\" failed: "
                    + resolved.error());
        }
        try (InputStream in = resolved.service().getFile(filePath)) {
            return in == null ? new byte[0] : in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read file: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    /**
     * 按租户解析 provider 服务；{@code provider} 为空表示"用租户默认"。
     *
     * <p>取不到租户（无 tenantId / 库里无行）→ (null, "", null)：<b>当作没有云配置</b>，
     * 走本地契约——这与"配了云但解析失败"（错误非空 → 抛出）是两种姿态，
     * 后者不能静默退回本地。</p>
     */
    private StorageFileResolver.ProviderResolution resolveProvider(long tenantId, String provider) {
        Tenant tenant = tenantId <= 0 ? null : tenantService.getTenantById(tenantId);
        if (tenant == null) {
            return new StorageFileResolver.ProviderResolution(null, "", null);
        }
        return resolver.resolveProviderService(tenant, "", provider, localBaseDir);
    }
}
