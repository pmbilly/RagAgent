package com.ragagent.storage.provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地文件系统后端。
 *
 * <h2>布局与路径</h2>
 * <ul>
 *   <li>{@code SaveFile} → {@code {baseDir}/{tenantId}/{knowledgeId}/{nanoTime}{ext}}，
 *       返回 {@code local://{斜杠相对路径}}；</li>
 *   <li>{@code SaveBytes} → {@code {baseDir}/{tenantId}/exports/{基名}_{nanoTime}{ext}}
 *       （文件名先过安全校验，禁路径遍历）；</li>
 *   <li>{@code CopyFile} → 目标同上布局（真字节复制，**无硬链接**）；源带非 {@code local://}
 *       的 scheme → {@link FileService.CrossBackendCopyException}；</li>
 *   <li>取/删路径必须先归一到 baseDir 之下（{@code ../../} 一律拒绝）。</li>
 * </ul>
 *
 * <h2>GetFileURL</h2>
 * <p>配了 {@code externalURL}（{@code APP_EXTERNAL_URL}）时走预签名 URL；
 * 未注入 {@link UrlSigner}（预签名未接线）时记警告并返回 {@code local://} 路径。</p>
 */
public class LocalFileService implements FileService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileService.class);

    /** {@code local://} 前缀。 */
    public static final String LOCAL_SCHEME = "local://";

    /** 预签名接缝（对照 {@code utils.SignFileURL}）：未注入 = 未接线，返回 provider 路径。 */
    public interface UrlSigner {
        String sign(String baseUrl, String filePath, long tenantId, long ttlSeconds);
    }

    private final Path baseDir;
    private final String externalURL;
    private final UrlSigner signer;

    public LocalFileService(String baseDir, String externalURL) {
        this(baseDir, externalURL, null);
    }

    public LocalFileService(String baseDir, String externalURL, UrlSigner signer) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
        this.externalURL = externalURL == null ? "" : trimRightSlash(externalURL.trim());
        this.signer = signer;
    }

    /** 供装配与测试观察解析后的根目录。 */
    public Path baseDir() {
        return baseDir;
    }

    @Override
    public void checkConnectivity() {
        if (!Files.exists(baseDir)) {
            throw new IllegalStateException("storage directory not accessible: " + baseDir);
        }
        if (!Files.isDirectory(baseDir)) {
            throw new IllegalStateException("storage path is not a directory: " + baseDir);
        }
    }

    @Override
    public String saveFile(UploadFile file, long tenantId, String knowledgeId) {
        Path dir = safeUnderBase(baseDir.resolve(String.valueOf(tenantId)).resolve(knowledgeId));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create directory: " + e.getMessage(), e);
        }
        String ext = extensionOf(file.fileName());
        Path target = dir.resolve(System.nanoTime() + ext);
        try (InputStream in = file.opener().get()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("failed to save file: " + e.getMessage(), e);
        }
        return localScheme(baseDir.relativize(target));
    }

    @Override
    public String saveBytes(byte[] data, long tenantId, String fileName, boolean temp) {
        String safeName = safeFileName(fileName);
        Path dir = baseDir.resolve(String.valueOf(tenantId)).resolve("exports");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create directory: " + e.getMessage(), e);
        }
        String ext = extensionOf(safeName);
        String base = safeName.substring(0, safeName.length() - ext.length());
        Path target = dir.resolve(base + "_" + System.nanoTime() + ext);
        try {
            Files.write(target, data);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write file: " + e.getMessage(), e);
        }
        return localScheme(baseDir.relativize(target));
    }

    @Override
    public InputStream getFile(String filePath) {
        Path resolved = safeUnderBase(normalizePathForBase(filePath));
        try {
            return Files.newInputStream(resolved);
        } catch (IOException e) {
            // baseDir/resolved 都记上：写入方与读取方 LOCAL_STORAGE_BASE_DIR 不一致时一眼可见
            log.error("Failed to open file: baseDir={} resolvedPath={} err={}",
                    baseDir, resolved, e.toString());
            throw new IllegalStateException("failed to open file: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteFile(String filePath) {
        Path resolved = safeUnderBase(normalizePathForBase(filePath));
        try {
            Files.delete(resolved);
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete file: " + e.getMessage(), e);
        }
    }

    @Override
    public String copyFile(String srcPath, long tenantId, String knowledgeId) {
        int schemeAt = srcPath == null ? -1 : srcPath.indexOf("://");
        if (schemeAt >= 0 && !srcPath.startsWith(LOCAL_SCHEME)) {
            throw new FileService.CrossBackendCopyException(
                    "local file service cannot copy \"" + srcPath + "\"");
        }
        Path src = safeUnderBase(normalizePathForBase(srcPath == null ? "" : srcPath));
        Path dir = safeUnderBase(baseDir.resolve(String.valueOf(tenantId)).resolve(knowledgeId));
        try {
            Files.createDirectories(dir);
            String ext = extensionOf(srcPath);
            Path target = dir.resolve(System.nanoTime() + ext);
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
            return localScheme(baseDir.relativize(target));
        } catch (IOException e) {
            throw new IllegalStateException("failed to copy file: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFileURL(String filePath) {
        String normalized = filePath == null ? "" : filePath;
        if (!normalized.startsWith(LOCAL_SCHEME)) {
            try {
                normalized = localScheme(baseDir.relativize(
                        Paths.get(normalized).toAbsolutePath().normalize()));
            } catch (RuntimeException e) {
                normalized = filePath == null ? "" : filePath;
            }
        }
        if (!externalURL.isEmpty()) {
            long tenantId = parseTenantIdFromStoragePath(normalized);
            if (signer == null) {
                // 预签名未接线（未配 SYSTEM_AES_KEY）→ 记警告 + 返回 local:// 路径
                log.warn("Failed to generate presigned URL for {}: presign not wired, "
                        + "returning local:// path", normalized);
                return normalized;
            }
            return signer.sign(externalURL, normalized, tenantId, 0);
        }
        return normalized;
    }

    // ── 内部：路径处理（对照 normalizePathForBase / SafePathUnderBase / SafeFileName） ──

    /** 对照 {@code normalizePathForBase}：provider scheme / 绝对路径 / base 下相对 / 遗留带前缀。 */
    Path normalizePathForBase(String filePath) {
        String p = filePath == null ? "" : filePath.trim();
        if (p.startsWith(LOCAL_SCHEME)) {
            return baseDir.resolve(p.substring(LOCAL_SCHEME.length()));
        }
        Path clean = Paths.get(p).normalize();
        if (clean.toString().isEmpty() || clean.toString().equals(".")) {
            return clean;
        }
        if (clean.isAbsolute()) {
            return clean;
        }
        // 遗留相对路径里重复的 base 前缀（如 "data/files/..."）要剥掉
        String baseStr = trimSlashes(baseDir.toString());
        String rel = trimSlashes(clean.toString());
        rel = rel.startsWith("./") ? rel.substring(2) : rel;
        if (rel.startsWith(baseStr + "/")) {
            rel = rel.substring(baseStr.length() + 1);
        }
        return baseDir.resolve(rel);
    }

    /** 对照 {@code SafePathUnderBase}：解析结果必须落在 baseDir 之内。 */
    private Path safeUnderBase(Path candidate) {
        Path resolved = candidate.toAbsolutePath().normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("invalid path: escapes storage base dir");
        }
        return resolved;
    }

    /** 对照 {@code SafeFileName}（取 basename；见 {@link StorageObjects#safeFileName}）。 */
    static String safeFileName(String fileName) {
        return StorageObjects.safeFileName(fileName);
    }

    /** 对照 {@code ParseTenantIDFromStoragePath}：从 {@code local://{tenant}/{...}} 取租户 id。 */
    static long parseTenantIdFromStoragePath(String path) {
        String p = path == null ? "" : path;
        if (p.startsWith(LOCAL_SCHEME)) {
            p = p.substring(LOCAL_SCHEME.length());
        }
        int slash = p.indexOf('/');
        String first = slash < 0 ? p : p.substring(0, slash);
        try {
            return Long.parseLong(first);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String localScheme(Path relative) {
        return LOCAL_SCHEME + relative.toString().replace('\\', '/');
    }

    private static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return dot > slash && dot >= 0 ? name.substring(dot) : "";
    }

    private static String trimRightSlash(String s) {
        String out = s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String trimSlashes(String s) {
        String out = s;
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** 供测试：把输入流写到目标（保留给后端扩展用）。 */
    static void copy(InputStream in, OutputStream out) throws IOException {
        in.transferTo(out);
    }
}
