package com.ragagent.common.web;

import java.util.Locale;

/**
 * 扩展名 → Content-Type / 内联判定（对照 Go utils/fileutil.go 的
 * GetContentTypeByExt / SafeContentTypeByFilename / IsActiveBrowserContentExt）。
 */
public final class ContentTypeByFilename {

    private ContentTypeByFilename() {
    }

    /** 对照 Go IsActiveBrowserContentExt（fileutil.go L9-16）。 */
    public static boolean isActiveBrowserContentExt(String ext) {
        String lower = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case ".svg", ".svgz", ".html", ".htm", ".xhtml", ".xml", ".js", ".mjs", ".css" -> true;
            default -> false;
        };
    }

    /** 对照 Go GetContentTypeByExt（fileutil.go L19-67）。 */
    public static String getByExt(String ext) {
        if (isActiveBrowserContentExt(ext)) {
            return "application/octet-stream";
        }
        String lower = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case ".csv" -> "text/csv; charset=utf-8";
            case ".json" -> "application/json";
            case ".pdf" -> "application/pdf";
            case ".doc" -> "application/msword";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".ppt" -> "application/vnd.ms-powerpoint";
            case ".pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ".xls" -> "application/vnd.ms-excel";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".txt" -> "text/plain; charset=utf-8";
            case ".mp4" -> "video/mp4";
            case ".mp3" -> "audio/mpeg";
            case ".wav" -> "audio/wav";
            case ".zip" -> "application/zip";
            case ".tar" -> "application/x-tar";
            case ".gz" -> "application/gzip";
            case ".md" -> "text/markdown; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    /**
     * 对照 Go SafeContentTypeByFilename（fileutil.go L73-84）：返回
     * (contentType, inline)；"活动浏览器内容"扩展强制 octet-stream 且不内联。
     */
    public static Record safe(String filename) {
        String ext = filename == null ? "" : extensionOf(filename);
        if (isActiveBrowserContentExt(ext)) {
            return new Record("application/octet-stream", false);
        }
        return new Record(getByExt(ext), true);
    }

    /** 简化 record（对照 Go 的多返回值）。 */
    public record Record(String contentType, boolean inline) {
    }

    private static String extensionOf(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx < 0 ? "" : filename.substring(idx);
    }
}
