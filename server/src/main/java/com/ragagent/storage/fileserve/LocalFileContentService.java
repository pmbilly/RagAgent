package com.ragagent.storage.fileserve;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地盘文件服务（对照 Go {@code service/file/local.go localFileService} 的
 * GetFile / GetFileURL / normalizePathForBase 子集——W5c 文件代理面只消费这两个方法；
 * Save/Delete/Copy 随写入链回补）。
 *
 * <p>{@code externalURL} 在 Go 构造时取 env {@code APP_EXTERNAL_URL}（去尾斜杠）；
 * 设置时 {@code GetFileURL} 返回预签名 URL，否则返回 {@code local://…} 原样——
 * dev 部署恒为后者。</p>
 */
public class LocalFileContentService implements WritableFileContentService {

    private final String baseDir;
    private final String externalURL;

    public LocalFileContentService(String baseDir, String externalURL) {
        this.baseDir = baseDir;
        this.externalURL = externalURL == null ? "" : externalURL.trim().replaceAll("/+$", "");
    }

    public String baseDir() {
        return baseDir;
    }

    // ── GetFile ─────────────────────────────────────────────────────────────

    @Override
    public FileTransport.OpenedFile getFile(String filePath) throws IOException {
        String candidate = normalizePathForBase(filePath == null ? "" : filePath);
        String resolved = safePathUnderBase(baseDir, candidate);
        Path p = Path.of(resolved);
        if (!Files.isReadable(p)) {
            // 打开失败：Go 会 fmt.Errorf("failed to open file: %w") → 路由折成 404。
            // 文案只进日志，HTTP 形态是 404 无体。
            throw new IOException("failed to open file: open " + resolved + ": no such file or directory");
        }
        return FileTransport.OpenedFile.ofSeekable(p, Files.size(p));
    }

    // ── GetFileURL ──────────────────────────────────────────────────────────

    @Override
    public String getFileURL(String filePath) throws IOException {
        String normalized = filePath == null ? "" : filePath;
        if (!normalized.startsWith(FileContentService.LOCAL_SCHEME)) {
            String rel = goRel(baseDir, normalized);
            if (rel == null) {
                normalized = filePath;
            } else {
                normalized = FileContentService.LOCAL_SCHEME + rel;
            }
        }
        if (!externalURL.isEmpty()) {
            // 租户 ID 从存储路径解析（资源属主租户，不是调用者租户——Go 注释原文）
            long tenantId = StoragePaths.parseTenantIdFromStoragePath(normalized);
            byte[] key = StoragePaths.systemHmacKey();
            if (key == null) {
                return normalized; // Go: 签名失败 → WARN → 返回 local:// 路径
            }
            long expires = java.time.Instant.now().getEpochSecond() + 7200; // presignDefaultTTL
            String sig = StoragePaths.signPayload(key, normalized, tenantId, expires);
            return trimRightSlashes(externalURL) + StoragePaths.PRESIGN_PATH
                    + "?file_path=" + urlQueryEscape(normalized)
                    + "&tenant_id=" + tenantId
                    + "&expires=" + expires
                    + "&sig=" + sig;
        }
        return normalized;
    }

    private static String trimRightSlashes(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    /** 对照 Go url.Values.Encode 的查询串转义（Space→+，其余 url.QueryEscape 语义）。 */
    private static String urlQueryEscape(String s) {
        StringBuilder sb = new StringBuilder();
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if (Character.isLetterOrDigit(c) || "-_.~".indexOf(c) >= 0) {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('+');
            } else if (c == '*' || c == '-' || c == '.' || c == '_') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    // ── SaveBytes / DeleteFile（2026-09-24 存储写字节面批）──────────────────

    /**
     * 对照 Go {@code localFileService.SaveBytes}（file/local.go L218-249）：
     * SafeFileName 校验 → baseDir/{tenantID}/exports/ → {@code <base>_<纳秒><ext>}
     * 唯一名 → 写 0644 → 返回 {@code local://<rel>}。temp 对本地存储无效
     * （无自动过期支持——Go 注释原文）。
     */
    @Override
    public String saveBytes(byte[] data, long tenantId, String fileName, boolean temp)
            throws IOException {
        String safeName = safeFileName(fileName);
        Path dir = Path.of(joinPath(baseDir, Long.toString(tenantId), "exports"));
        Files.createDirectories(dir);
        String ext = extOf(safeName);
        String baseName = safeName.substring(0, safeName.length() - ext.length());
        Path filePath = dir.resolve(baseName + "_" + System.nanoTime() + ext);
        Files.write(filePath, data);
        String relPath = goRel(baseDir, filePath.toString());
        return FileContentService.LOCAL_SCHEME + relPath;
    }

    /** 对照 Go {@code localFileService.DeleteFile}：normalize + 守卫 + 删除。 */
    @Override
    public void deleteFile(String filePath) throws IOException {
        String candidate = normalizePathForBase(filePath == null ? "" : filePath);
        String resolved = safePathUnderBase(baseDir, candidate);
        Files.deleteIfExists(Path.of(resolved));
    }

    /** 对照 Go secutils.SafeFileName（security.go L150-165）。 */
    public static String safeFileName(String fileName) throws IOException {
        if (fileName == null || fileName.isEmpty()) {
            throw new IOException("fileName cannot be empty");
        }
        String base = Path.of(cleanPath(fileName)).getFileName() == null
                ? "" : Path.of(cleanPath(fileName)).getFileName().toString();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            throw new IOException("invalid fileName: path traversal or empty name");
        }
        if (base.contains("..")) {
            throw new IOException("invalid fileName: contains path traversal");
        }
        if (base.length() > 255) {
            throw new IOException("fileName too long");
        }
        return base;
    }

    /** 含点扩展名（filepath.Ext 语义）：最后一个 '.' 起（不含目录分隔）。 */
    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        String ext = name.substring(dot);
        if (ext.contains("/")) {
            return "";
        }
        return ext;
    }

    // ── 路径规范化 + 守卫 ───────────────────────────────────────────────────

    /**
     * 对照 Go {@code normalizePathForBase}：local://{rel} → join；绝对路径原样；
     * legacy 相对路径剥掉重复 base 前缀（"data/files/..."）。
     */
    String normalizePathForBase(String filePath) {
        if (filePath.startsWith(FileContentService.LOCAL_SCHEME)) {
            String relPath = filePath.substring(FileContentService.LOCAL_SCHEME.length());
            return joinPath(baseDir, fromSlash(relPath));
        }
        String clean = cleanPath(filePath.trim());
        if (clean.equals(".") || clean.isEmpty()) {
            return clean;
        }
        if (clean.startsWith("/")) {
            return clean;
        }
        String baseClean = cleanPath(baseDir);
        String baseNoSlash = trimChars(baseClean, "/");
        String cleanNoDot = cleanNoDotPrefix(clean);
        if (cleanNoDot.startsWith(baseNoSlash + "/")) {
            cleanNoDot = cleanNoDot.substring(baseNoSlash.length() + 1);
        }
        return joinPath(baseClean, cleanNoDot);
    }

    private static String cleanNoDotPrefix(String clean) {
        if (clean.startsWith("./")) {
            return clean.substring(2);
        }
        return clean;
    }

    private static String trimChars(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    /** 对照 Go {@code filepath.Join}（Clean 语义）。 */
    static String joinPath(String... elems) {
        List<String> parts = new ArrayList<>();
        for (String e : elems) {
            if (!e.isEmpty()) {
                parts.add(e);
            }
        }
        return cleanPath(String.join("/", parts));
    }

    /**
     * 对照 Go {@code filepath.Clean}（unix 规则）：
     * 折叠多斜杠、消 {@code .}、解 {@code ..}、根/空的特殊形态。
     */
    static String cleanPath(String path) {
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

    private static String fromSlash(String s) {
        // unix 上 Separator 就是 '/'，no-op（Go 的 FromSlash 在 Windows 才有差别）
        return s;
    }

    /**
     * 对照 Go {@code filepath.Rel}（unix）：同源相对化；根性不同 / base 含 ".."
     * 时返回 null（调用方按 Go 的 error 分支处理）。
     */
    static String goRel(String basePath, String targPath) {
        String base = cleanPath(basePath);
        String targ = cleanPath(targPath);
        if (base.equals(targ)) {
            return ".";
        }
        if (base.equals(".")) {
            base = "";
        }
        boolean baseSlashed = !base.isEmpty() && base.charAt(0) == '/';
        boolean targSlashed = !targ.isEmpty() && targ.charAt(0) == '/';
        if (baseSlashed != targSlashed) {
            return null; // Rel: can't make <targ> relative to <base>
        }
        int bl = base.length();
        int tl = targ.length();
        int b0 = 0;
        int bi = 0;
        int t0 = 0;
        int ti = 0;
        while (true) {
            while (bi < bl && base.charAt(bi) != '/') {
                bi++;
            }
            while (ti < tl && targ.charAt(ti) != '/') {
                ti++;
            }
            String baseSeg = base.substring(Math.min(b0, bl), Math.min(bi, bl));
            String targSeg = targ.substring(Math.min(t0, tl), Math.min(ti, tl));
            if (!targSeg.equals(baseSeg)) {
                break;
            }
            if (bi < bl) {
                bi++;
            }
            if (ti < tl) {
                ti++;
            }
            b0 = bi;
            t0 = ti;
            if (b0 > bl && t0 > tl) {
                // Go 的循环对"段耗尽且相等"不会停——那只在两路相等时发生，已在入口挡住
                break;
            }
        }
        String baseSeg = base.substring(Math.min(b0, bl), Math.min(bi, bl));
        if (baseSeg.equals("..")) {
            return null;
        }
        if (b0 < bl) {
            int seps = 0;
            for (int i = b0; i < bl; i++) {
                if (base.charAt(i) == '/') {
                    seps++;
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("..");
            for (int i = 0; i < seps; i++) {
                sb.append("/..");
            }
            if (tl != t0) {
                sb.append('/').append(targ, t0, tl);
            }
            return sb.toString();
        }
        return targ.substring(Math.min(t0, tl));
    }

    // ── utils/security.go SafePathUnderBase ─────────────────────────────────

    /** 对照 Go {@code SafePathUnderBase}：返回规范化绝对路径或抛 IOException（逃逸）。 */
    static String safePathUnderBase(String baseDir, String filePath) throws IOException {
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
