package com.ragagent.storage.fileserve;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 存储路径守卫与三态引用解析的**单一份实现**（③ local 双实现去重，W5γ5.1）。
 *
 * <p>此前同一条 Go 语义在两处各写一遍：{@code knowledge.LocalStorageService.resolveUnderBase}
 * （{@code resource://} 契约、错误折 BizException 信封）与
 * {@code storage.fileserve.LocalFileContentService.safePathUnderBase}
 * （{@code local://} 契约、错误抛 IOException → 404）。本类收敛两段共享逻辑；
 * 两支**各自保留**引用形态、落盘布局与错误通道——<b>不合并两支</b>：Go 侧根本没有
 * 对应物（Go 是"单 {@code FileService} + {@code local.go} 单实现 + 装饰器 + filetransport
 * 出口"），合并会造成 ~125 个 golden 重录而行为零收益。理由与证据见
 * {@code docs/storage-a3-plan.md} §3。</p>
 *
 * <p>语义照 Go：{@code filepath.Clean}（unix 规则）与
 * {@code internal/utils/security.go} 的 {@code SafePathUnderBase}。</p>
 */
public final class StoragePathGuard {

    /** provider 原生本地引用前缀（Go local provider 的 {@code local://}）。 */
    public static final String LOCAL_SCHEME = "local://";

    /** 知识布局引用前缀（Java 侧约定，golden 锁定）。 */
    public static final String RESOURCE_SCHEME = "resource://";

    private StoragePathGuard() {
    }

    /** 剥掉已知 scheme（{@code resource://} / {@code local://}）；裸路径与绝对路径原样返回。 */
    public static String stripKnownScheme(String filePath) {
        if (filePath == null) {
            return "";
        }
        if (filePath.startsWith(RESOURCE_SCHEME)) {
            return filePath.substring(RESOURCE_SCHEME.length());
        }
        if (filePath.startsWith(LOCAL_SCHEME)) {
            return filePath.substring(LOCAL_SCHEME.length());
        }
        return filePath;
    }

    /**
     * 对照 Go {@code filepath.Clean}（unix 规则）：
     * 折叠多斜杠、消 {@code .}、解 {@code ..}、根/空的特殊形态。
     */
    public static String cleanPath(String path) {
        if (path.isEmpty()) {
            return ".";
        }
        boolean rooted = path.startsWith("/");
        List<String> out = new ArrayList<>();
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!out.isEmpty() && !out.get(out.size() - 1).equals("..")) {
                    out.remove(out.size() - 1);
                } else if (!rooted) {
                    out.add("..");
                }
                continue;
            }
            out.add(part);
        }
        StringBuilder sb = new StringBuilder();
        if (rooted) {
            sb.append('/');
        }
        sb.append(String.join("/", out));
        if (sb.length() == 0) {
            return ".";
        }
        return sb.toString();
    }

    /**
     * 对照 Go {@code SafePathUnderBase}：返回规范化绝对路径，逃逸 base 时抛
     * {@code IOException}（文案照 Go 原文）。调用方各自翻译成自己的错误通道。
     */
    public static String safePathUnderBase(String baseDir, String filePath) throws IOException {
        if (baseDir.isEmpty() || filePath.isEmpty()) {
            throw new IOException("baseDir and filePath cannot be empty");
        }
        String absBase = Path.of(cleanPath(baseDir)).toAbsolutePath().normalize().toString();
        String absPath = Path.of(cleanPath(filePath)).toAbsolutePath().normalize().toString();
        if (!absPath.equals(absBase) && !absPath.startsWith(absBase + "/")) {
            throw new IOException("invalid file path: path traversal denied: path is outside base directory");
        }
        return absPath;
    }
}
