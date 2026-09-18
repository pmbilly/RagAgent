package com.ragagent.knowledge.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import com.ragagent.common.error.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 本地存储引擎（对照 Go internal/storage local provider，阶段 3 最小实现）。
 *
 * 文件路径契约：对外暴露 resource://{key} 不透明串（golden 已锁定此前缀），
 * key 的内部编码与 Go 不同（opaque，不外泄语义），读写自洽即可——
 * preview/download 等回读路径走同一 service。
 *
 * 落盘布局：{LOCAL_STORAGE_BASE_DIR}/{tenantId}/{knowledgeId}/{fileName}
 * （env 未设置时默认 /data/files，与 Go 的 LOCAL_STORAGE_BASE_DIR 一致）。
 */
@Service
public class LocalStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final Path baseDir;

    public LocalStorageService(
            @Value("${weknora.storage.local-base-dir:${LOCAL_STORAGE_BASE_DIR:/data/files}}") String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    /** 测试种子用：本地落盘根目录（绝对化 + 规范化，防止相对路径下 startsWith 误判）。 */
    public Path baseDir() {
        return baseDir.toAbsolutePath().normalize();
    }

    /** 保存文件内容，返回 resource:// 路径（对照 golden file_path 形态） */
    public String save(long tenantId, String knowledgeId, String fileName, byte[] content) {
        try {
            Path dir = baseDir.resolve(String.valueOf(tenantId)).resolve(knowledgeId);
            Files.createDirectories(dir);
            Path target = dir.resolve(sanitize(fileName));
            Files.write(target, content);
            String key = tenantId + "/" + knowledgeId + "/" + sanitize(fileName);
            return "resource://" + key;
        } catch (IOException e) {
            throw new IllegalStateException("failed to create directory or write file: " + e.getMessage(), e);
        }
    }

    /** 按 resource:// 路径读回字节 */
    public byte[] read(String resourcePath) {
        if (resourcePath == null || !resourcePath.startsWith("resource://")) {
            throw new IllegalArgumentException("invalid resource path");
        }
        try {
            return Files.readAllBytes(baseDir.resolve(resourcePath.substring("resource://".length())));
        } catch (IOException e) {
            throw new IllegalStateException("failed to read file: " + e.getMessage(), e);
        }
    }

    /**
     * 波 2：对照 Go local provider GetFile 的路径解析 + SafePathUnderBase 守卫
     * （service/file/local.go L111-132 + utils/security.go L110-127）。支持
     * resource://（阶段 3 布局）与 local://{rel}（Go provider 原生）与裸相对路径；
     * 解析后不在 baseDir 下 → Go 原文 "invalid file path: path traversal denied: ..."。
     * 空路径/读失败由调用方翻译成 "Failed to retrieve file" 信封（对照
     * GetKnowledgeFile 的错误链）。
     */
    public byte[] readChecked(String filePath) {
        Path resolved = resolveUnderBase(filePath);
        try {
            return Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new BizException(com.ragagent.common.error.AppError.internal("Failed to retrieve file")
                    .withDetails("failed to open file: " + e.getMessage()));
        }
    }

    private Path resolveUnderBase(String filePath) {
        String base = baseDir.toAbsolutePath().normalize().toString();
        String candidate;
        if (filePath != null && filePath.startsWith("local://")) {
            candidate = filePath.substring("local://".length());
        } else if (filePath != null && filePath.startsWith("resource://")) {
            candidate = filePath.substring("resource://".length());
        } else {
            candidate = filePath == null ? "" : filePath;
        }
        Path resolved = java.nio.file.Path.of(candidate).isAbsolute()
                ? java.nio.file.Path.of(candidate)
                : baseDir.resolve(candidate);
        Path abs = resolved.toAbsolutePath().normalize();
        if (!abs.equals(baseDir.toAbsolutePath().normalize()) && !abs.startsWith(base + "/")) {
            throw new BizException(com.ragagent.common.error.AppError.internal("Failed to retrieve file")
                    .withDetails("invalid file path: path traversal denied: path is outside base directory"));
        }
        return abs;
    }

    public boolean exists(String resourcePath) {
        if (resourcePath == null || !resourcePath.startsWith("resource://")) {
            return false;
        }
        return Files.exists(baseDir.resolve(resourcePath.substring("resource://".length())));
    }

    public void deleteTree(long tenantId, String knowledgeId) {
        Path dir = baseDir.resolve(String.valueOf(tenantId)).resolve(knowledgeId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("delete {} failed: {}", p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("delete tree {} failed: {}", dir, e.toString());
        }
    }

    /** 对照 Go file_hash：golden 为 32 位小写十六进制（md5） */
    public static String md5Hex(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long maxFileSizeBytes() {
        String env = System.getenv("MAX_FILE_SIZE_MB");
        int mb = 50;
        if (env != null && !env.isBlank()) {
            try {
                mb = Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return (long) mb * 1024 * 1024;
    }

    public static long maxFileSizeMb() {
        return maxFileSizeBytes() / (1024 * 1024);
    }

    private static String sanitize(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unnamed";
        }
        // 防路径穿越
        return fileName.replace("/", "_").replace("\\", "_").replace("..", "__");
    }

    public static byte[] readAll(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        in.transferTo(out);
    }
}
