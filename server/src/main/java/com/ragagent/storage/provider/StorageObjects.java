package com.ragagent.storage.provider;

import java.util.Locale;
import java.util.Set;

/**
 * 对象存储的三个小助手（逐个对照 Go）：
 *
 * <ul>
 *   <li>{@link #safeObjectKey} ← {@code utils.SafeObjectKey}（security.go L168-176）：非空且不含 {@code ..}；</li>
 *   <li>{@link #isActiveBrowserContentExt} ← {@code utils.IsActiveBrowserContentExt}（fileutil.go L9-16）：
 *       SVG/HTML/JS/CSS 这类**可执行内容**的扩展名；</li>
 *   <li>{@link #contentTypeByExt} ← {@code utils.GetContentTypeByExt}（fileutil.go L19-84）：
 *       主动内容一律降级为 {@code application/octet-stream}（防存储型 XSS），其余按表；</li>
 *   <li>{@link #extensionOf} ← {@code filepath.Ext}（本地后端也用它，故提到这里共用）。</li>
 * </ul>
 */
public final class StorageObjects {

    /** 对照 Go 的主动内容表。 */
    private static final Set<String> ACTIVE_EXTS =
            Set.of(".svg", ".svgz", ".html", ".htm", ".xhtml", ".xml", ".js", ".mjs", ".css");

    private StorageObjects() {
    }

    /**
     * 对照 {@code utils.SafeFileName}（internal/utils/security.go L150-165）:
     * {@code filepath.Base(filepath.Clean(name))}——<b>目录部分被丢弃，不是拒绝</b>
     * （所以 {@code "tenant-skills/catalog/x.zip"} 合法，落成 {@code x.zip}，
     * 这正是 Go 的 skill 归档与 FAQ 导出所依赖的行为），再拒
     * {@code .}/{@code ..}/含 {@code ..}/超 255。
     */
    public static String safeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("fileName cannot be empty");
        }
        String cleaned = cleanPath(fileName.replace('\\', '/'));
        int slash = cleaned.lastIndexOf('/');
        String base = slash < 0 ? cleaned : cleaned.substring(slash + 1);
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            throw new IllegalArgumentException("invalid fileName: path traversal or empty name");
        }
        if (base.contains("..")) {
            throw new IllegalArgumentException("invalid fileName: contains path traversal");
        }
        if (base.length() > 255) {
            throw new IllegalArgumentException("fileName too long");
        }
        return base;
    }

    /** 近似 Go {@code filepath.Clean} 的词法折叠：去空段与 {@code .}、解析 {@code ..}。 */
    private static String cleanPath(String path) {
        boolean absolute = path.startsWith("/");
        java.util.ArrayDeque<String> out = new java.util.ArrayDeque<>();
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!out.isEmpty() && !"..".equals(out.peekLast())) {
                    out.removeLast();
                } else if (!absolute) {
                    out.addLast(segment);
                }
                continue;
            }
            out.addLast(segment);
        }
        return (absolute ? "/" : "") + String.join("/", out);
    }

    /** 对照 {@code SafeObjectKey}：路径遍历一律拒绝（S3 的 key 允许 {@code /}）。 */
    public static void safeObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isEmpty()) {
            throw new IllegalArgumentException("object key cannot be empty");
        }
        if (objectKey.contains("..")) {
            throw new IllegalArgumentException("object key contains path traversal");
        }
    }

    /** 对照 {@code IsActiveBrowserContentExt}。 */
    public static boolean isActiveBrowserContentExt(String ext) {
        return ACTIVE_EXTS.contains(ext == null ? "" : ext.toLowerCase(Locale.ROOT));
    }

    /** 对照 {@code GetContentTypeByExt}：主动内容 → octet-stream，其余按扩展名表。 */
    public static String contentTypeByExt(String ext) {
        String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        if (isActiveBrowserContentExt(e)) {
            return "application/octet-stream";
        }
        return switch (e) {
            case ".csv" -> "text/csv; charset=utf-8";
            case ".json" -> "application/json";
            case ".pdf" -> "application/pdf";
            case ".doc" -> "application/msword";
            case ".docx" ->
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".xls" -> "application/vnd.ms-excel";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".ppt" -> "application/vnd.ms-powerpoint";
            case ".pptx" ->
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ".txt", ".md", ".log" -> "text/plain; charset=utf-8";
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".bmp" -> "image/bmp";
            case ".tiff", ".tif" -> "image/tiff";
            case ".ico" -> "image/x-icon";
            case ".mp3" -> "audio/mpeg";
            case ".wav" -> "audio/wav";
            case ".mp4" -> "video/mp4";
            case ".mov" -> "video/quicktime";
            case ".zip" -> "application/zip";
            case ".gz", ".tar.gz" -> "application/gzip";
            case ".tar" -> "application/x-tar";
            case ".7z" -> "application/x-7z-compressed";
            case ".rar" -> "application/vnd.rar";
            default -> "application/octet-stream";
        };
    }

    /** 对照 {@code filepath.Ext}：最后一个点之后（含点）；点在同级分隔符之前则视为无扩展名。 */
    public static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return dot > slash && dot >= 0 ? name.substring(dot) : "";
    }
}
