package com.ragagent.session.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 附件的本地文件存储。
 *
 * <p>布局沿用既有盘上数据：{LOCAL_STORAGE_BASE_DIR}/{tenantId}/exports/
 * {safeName}_{unixNano}{ext}，引用形态 {@code local://{tenantId}/exports/...}。
 * ref 不进响应；**存量文件互读**依赖布局稳定——旧实现写入的文件要能继续 preview。</p>
 */
@Component
public class AttachmentFileStore {

    private static final Logger log = LoggerFactory.getLogger(AttachmentFileStore.class);
    private static final String LOCAL_SCHEME = "local://";

    private final Path baseDir;

    public AttachmentFileStore(
            @Value("${weknora.storage.local-base-dir:${LOCAL_STORAGE_BASE_DIR:/data/files}}")
            String baseDir) {
        // toAbsolutePath().normalize()：测试配置是相对路径 ./build/test-files，
        // 而 resolve().normalize() 后是绝对形态——不规范化会被 startsWith 误判穿越
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
    }

    /** 保存：统一写 exports/ 目录。 */
    public String saveBytes(byte[] data, long tenantId, String fileName) {
        String safeName = safeFileName(fileName);
        Path dir = baseDir.resolve(String.valueOf(tenantId)).resolve("exports");
        try {
            Files.createDirectories(dir);
            String ext = extOf(safeName);
            String baseName = safeName.substring(0, safeName.length() - ext.length());
            String unique = baseName + "_" + System.nanoTime() + ext;
            Path target = dir.resolve(unique);
            Files.write(target, data);
            return LOCAL_SCHEME + tenantId + "/exports/" + unique;
        } catch (IOException e) {
            throw new IllegalStateException("failed to save attachment: " + e.getMessage(), e);
        }
    }

    /** 路径遍历防护后按字节读。 */
    public byte[] getFile(String ref) {
        Path resolved = resolve(ref);
        if (resolved == null || !Files.exists(resolved)) {
            throw new IllegalStateException("failed to open file: no such file: " + ref);
        }
        try {
            return Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new IllegalStateException("failed to open file: " + e.getMessage(), e);
        }
    }

    /** 删除；缺失时静默忽略。 */
    public void deleteFile(String ref) {
        try {
            Path resolved = resolve(ref);
            if (resolved != null && Files.exists(resolved)) {
                Files.delete(resolved);
            }
        } catch (IOException e) {
            log.warn("Failed to delete attachment file {}: {}", ref, e.toString());
        }
    }

    /** 逃出 baseDir 的引用一律拒绝。 */
    private Path resolve(String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }
        String rel = ref.startsWith(LOCAL_SCHEME) ? ref.substring(LOCAL_SCHEME.length()) : ref;
        Path resolved = baseDir.resolve(rel).normalize();
        if (!resolved.startsWith(baseDir)) {
            log.warn("Path traversal denied for attachment ref: {}", ref);
            return null;
        }
        return resolved;
    }

    /** 只取 base 名 + 防穿越。 */
    static String safeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("fileName cannot be empty");
        }
        String base = Path.of(fileName).getFileName().toString();
        if (base.isEmpty() || base.equals(".") || base.equals("..") || base.contains("..")) {
            throw new IllegalArgumentException("invalid fileName: path traversal or empty name");
        }
        if (base.length() > 255) {
            throw new IllegalArgumentException("fileName too long");
        }
        return base;
    }

    /** 上传存储名：chat_attachment_{uuid12}{ext}——ext 已带点（Go L175 语义）。 */
    static String storageName(String ext) {
        return "chat_attachment_" + UUID.randomUUID().toString().substring(0, 12)
                + (ext == null ? "" : ext);
    }

    private static String extOf(String name) {
        int idx = name.lastIndexOf('.');
        return idx < 0 ? "" : name.substring(idx);
    }
}
