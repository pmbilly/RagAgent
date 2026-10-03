package com.ragagent.storage.fileserve;

import java.io.IOException;
import java.net.URI;

/**
 * 后端作用域包装（{@code GetFile / GetFileURL} 子集）：
 *
 * <ul>
 *   <li>{@code GetFile}：先 unwrap {@code storage://<id>/…} 包装——id 与本实例不符 →
 *       "storage backend mismatch"（路由折成 404）；无包装原样透传。</li>
 *   <li>{@code GetFileURL}：unwrap → inner 求值 → <b>结果等于输入时重新包上
 *       storage:// 前缀</b>（= inner 没有改写路径，即外部 URL 未配置的 local）——
 *       presigned-preview 看到的 {@code storage://<backendID>/local://…} 就是它；
 *       inner 产出了预签名 URL 时用 scoped 路径重签（APP_EXTERNAL_URL 在位才可达）。</li>
 * </ul>
 *
 * <p>Save/Delete/Copy 随写入链回补。</p>
 */
public class BackendScopedFileService implements FileContentService {

    private final String backendId;
    private final FileContentService inner;

    public BackendScopedFileService(String backendId, FileContentService inner) {
        this.backendId = backendId;
        this.inner = inner;
    }

    private String unwrap(String path) throws IOException {
        StoragePaths.ParsedBackendPath parsed = StoragePaths.parseStorageBackendPath(path);
        if (!parsed.ok()) {
            return path;
        }
        if (!parsed.backendId().equals(backendId)) {
            throw new IOException("storage backend mismatch");
        }
        return parsed.providerPath();
    }

    private String wrap(String path) {
        return StoragePaths.STORAGE_BACKEND_SCHEME + backendId + "/" + path;
    }

    @Override
    public FileTransport.OpenedFile getFile(String filePath) throws IOException {
        return inner.getFile(unwrap(filePath));
    }

    @Override
    public String getFileURL(String filePath) throws IOException {
        String p = unwrap(filePath);
        String result = inner.getFileURL(p);
        String scoped = wrap(p);
        if (result.equals(p)) {
            return scoped;
        }
        // inner 产出了预签名 URL → 用 scoped 路径重签（Go L83-93；本地存储的
        // 预签名 URL 场景，确保代理落到确切实例）
        try {
            URI u = URI.create(result);
            if (u.getPath() != null && u.getPath().endsWith("/api/v1/files/presigned")
                    && p.equals(queryParam(u, "file_path"))) {
                String basePath = u.getPath().substring(0,
                        u.getPath().length() - "/api/v1/files/presigned".length());
                String baseURL = u.getScheme() + "://" + u.getHost()
                        + (u.getPort() >= 0 ? ":" + u.getPort() : "") + basePath;
                long expires = Long.parseLong(queryParam(u, "expires"));
                long ttl = expires - java.time.Instant.now().getEpochSecond();
                byte[] key = StoragePaths.systemHmacKey();
                if (key != null) {
                    long effective = ttl <= 0 ? 7200 : ttl;
                    long exp = java.time.Instant.now().getEpochSecond() + effective;
                    String sig = StoragePaths.signPayload(key, scoped,
                            StoragePaths.parseTenantIdFromStoragePath(scoped), exp);
                    return baseURL + StoragePaths.PRESIGN_PATH
                            + "?file_path=" + percentEncodeQuery(scoped)
                            + "&tenant_id=" + StoragePaths.parseTenantIdFromStoragePath(scoped)
                            + "&expires=" + exp + "&sig=" + sig;
                }
            }
        } catch (Exception ignored) {
            // Go: 解析失败/签名失败 → 原样返回 result
        }
        return result;
    }

    private static String queryParam(URI u, String name) {
        String query = u.getRawQuery();
        if (query == null) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String percentEncodeQuery(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }
}
