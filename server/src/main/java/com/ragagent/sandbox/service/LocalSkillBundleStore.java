package com.ragagent.sandbox.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本地落盘实现：布局 {@code {baseDir}/{tenantId}/{key}}，引用形态
 * {@code local://{tenantId}/{key}}（Go 本地 provider 的 local:// 前缀语义）。
 *
 * <p>种子行指向不存在对象的引用（如 /bundles/probe-skill.zip）在 baseDir 守卫或
 * 读文件处被拒绝——这正是 golden sbk-files / sbk-reinstall 的 404 链路
 * （对照 Go service/file/local.go 的 GetFile + SafePathUnderBase）。</p>
 */
@Component
public class LocalSkillBundleStore implements SkillBundleStore {

    private final Path baseDir;

    public LocalSkillBundleStore(
            @Value("${weknora.storage.local-base-dir:${LOCAL_STORAGE_BASE_DIR:/data/files}}")
            String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    @Override
    public String save(long tenantId, String key, byte[] archive) {
        try {
            Path target = baseDir.resolve(Long.toUnsignedString(tenantId)).resolve(key);
            Files.createDirectories(target.getParent());
            Files.write(target, archive);
            return "local://" + Long.toUnsignedString(tenantId) + "/" + key;
        } catch (IOException e) {
            throw new IllegalStateException("store skill bundle failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] load(long tenantId, String ref) {
        Path target = resolveUnderBase(ref);
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new IllegalStateException("read skill bundle failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(long tenantId, String ref) {
        try {
            Files.deleteIfExists(resolveUnderBase(ref));
        } catch (RuntimeException | IOException e) {
            // 尽力而为（Go 的 deleteBundleBestEffort 只记日志）
        }
    }

    private Path resolveUnderBase(String ref) {
        String base = baseDir.toAbsolutePath().normalize().toString();
        String candidate = ref == null ? "" : ref;
        if (candidate.startsWith("local://")) {
            candidate = candidate.substring("local://".length());
        }
        Path resolved = Path.of(candidate).isAbsolute()
                ? Path.of(candidate)
                : baseDir.resolve(candidate);
        Path abs = resolved.toAbsolutePath().normalize();
        if (!abs.equals(baseDir.toAbsolutePath().normalize()) && !abs.startsWith(base + "/")) {
            // 对照 Go local provider 的 SafePathUnderBase 守卫
            throw new IllegalArgumentException(
                    "invalid file path: path traversal denied: path is outside base directory");
        }
        return abs;
    }
}
