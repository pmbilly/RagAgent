package com.ragagent.storage.fileserve;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.ragagent.common.storage.StorageRuntimeEnv;

/**
 * 存储 provider:// 路径的解析与校验工具：
 *
 * <ul>
 *   <li>{@code storage://} backend 包装路径与 provider scheme 解析；</li>
 *   <li>{@code resource://} 手柄解析/构造；</li>
 *   <li>路径租户段校验族 + 预签名 URL 签名（{@code verifyFileUrlSig}）；</li>
 *   <li>存储引用识别（{@code containsStorageReference}）。</li>
 * </ul>
 *
 * <p>{@code parseStorageTarget} = 前两者的组合：
 * {@code "storage://3/local://7/x.png" → ("3","local")}、{@code "cos://7/x.png" → ("","cos")}。</p>
 */
public final class StoragePaths {

    private StoragePaths() {
    }

    /** {@code storage://} 前缀。 */
    public static final String STORAGE_BACKEND_SCHEME = "storage://";
    /** {@code resource://} 前缀与手柄长度。 */
    public static final String RESOURCE_SCHEME = "resource://";
    public static final int RESOURCE_HANDLE_LENGTH = 22;

    /** provider 列表——<b>顺序有语义</b>（逐个前缀匹配）。 */
    private static final String[] PROVIDERS = {"local", "minio", "cos", "tos", "s3", "oss", "ks3", "obs", "dummy"};

    /** KB 作用域 exports 段名。 */
    private static final String KB_SCOPED_EXPORTS_SEGMENT = "exports";

    /** 预签名路由路径。 */
    public static final String PRESIGN_PATH = "/api/v1/files/presigned";

    // ── provider / backend 解析 ─────────────────────────────────────────────

    public static boolean hasStorageBackendPrefix(String path) {
        return path != null && path.startsWith(STORAGE_BACKEND_SCHEME);
    }

    /**
     * {@code storage://<id>/<providerPath>}。
     * 返回 (backendID, providerPath, ok) 三元组。
     */
    public static record ParsedBackendPath(String backendId, String providerPath, boolean ok) {
    }

    public static ParsedBackendPath parseStorageBackendPath(String path) {
        if (!hasStorageBackendPrefix(path)) {
            return new ParsedBackendPath("", "", false);
        }
        String rest = path.substring(STORAGE_BACKEND_SCHEME.length());
        int slash = rest.indexOf('/');
        if (slash < 0 || slash == rest.length() - 1) {
            return new ParsedBackendPath("", "", false);
        }
        String backendId = rest.substring(0, slash);
        String providerPath = rest.substring(slash + 1);
        if (backendId.isEmpty()) {
            return new ParsedBackendPath("", "", false);
        }
        return new ParsedBackendPath(backendId, providerPath, true);
    }

    /** 先剥 backend 包装，再按固定顺序前缀匹配。 */
    public static String parseProviderScheme(String filePath) {
        String candidate = filePath == null ? "" : filePath;
        ParsedBackendPath parsed = parseStorageBackendPath(candidate);
        if (parsed.ok()) {
            candidate = parsed.providerPath();
        }
        for (String provider : PROVIDERS) {
            if (candidate.startsWith(provider + "://")) {
                return provider;
            }
        }
        return "";
    }

    /** (backendID, provider) 二元组。 */
    public static record StorageTarget(String backendId, String provider) {
    }

    public static StorageTarget parseStorageTarget(String filePath) {
        ParsedBackendPath parsed = parseStorageBackendPath(filePath);
        String providerPath = filePath == null ? "" : filePath;
        if (parsed.ok()) {
            providerPath = parsed.providerPath();
        }
        return new StorageTarget(parsed.ok() ? parsed.backendId() : "", parseProviderScheme(providerPath));
    }

    // ── resource:// 手柄 ────────────────────────────────────────────────────

    public static boolean isResourceHandleChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    /** {@code resource://} 路径合法时返回 handle，否则 null。 */
    public static String parseResourcePath(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith(RESOURCE_SCHEME)) {
            return null;
        }
        String handle = trimmed.substring(RESOURCE_SCHEME.length());
        if (handle.length() != RESOURCE_HANDLE_LENGTH) {
            return null;
        }
        for (int i = 0; i < handle.length(); i++) {
            if (!isResourceHandleChar(handle.charAt(i))) {
                return null;
            }
        }
        return handle;
    }

    public static boolean isResourcePath(String value) {
        return parseResourcePath(value) != null;
    }

    public static String buildResourcePath(String handle) {
        return RESOURCE_SCHEME + (handle == null ? "" : handle.trim());
    }

    // ── 路径租户段校验 ──────────────────────────────────────────────────────

    /** 剥掉 {@code storage://<id>/} 包装。 */
    static String unwrapStorageBackendPath(String filePath) {
        if (!hasStorageBackendPrefix(filePath)) {
            return filePath;
        }
        String rest = filePath.substring(STORAGE_BACKEND_SCHEME.length());
        String[] parts = splitFirst(rest, '/');
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return filePath;
        }
        return parts[1];
    }

    private static String[] splitFirst(String s, char sep) {
        int idx = s.indexOf(sep);
        if (idx < 0) {
            return new String[] {s};
        }
        return new String[] {s.substring(0, idx), s.substring(idx + 1)};
    }

    /** 取路径中第一个可解析为非负整数的段。 */
    public static long parseTenantIdFromStoragePath(String filePath) {
        String unwrapped = unwrapStorageBackendPath(filePath);
        int schemeEnd = unwrapped.indexOf("://");
        if (schemeEnd < 0) {
            return 0;
        }
        String rest = unwrapped.substring(schemeEnd + 3);
        for (String part : rest.split("/")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id >= 0) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // 解析失败继续尝试下一段
            }
        }
        return 0;
    }

    /** 路径租户段缺失或不匹配 → 错误文案。 */
    public static String validateStoragePathTenantError(String filePath, long tenantId) {
        long pathTenant = parseTenantIdFromStoragePath(filePath);
        if (pathTenant == 0) {
            return "storage path has no tenant segment";
        }
        if (pathTenant != tenantId) {
            return "storage path workspace mismatch";
        }
        return null;
    }

    /** 路径是否落在该租户的 exports 命名空间内。 */
    static boolean storagePathHasExportsScope(String filePath, long tenantId) {
        String unwrapped = unwrapStorageBackendPath(filePath);
        int schemeEnd = unwrapped.indexOf("://");
        if (schemeEnd < 0) {
            return false;
        }
        String rest = unwrapped.substring(schemeEnd + 3);
        String tenantSeg = Long.toString(tenantId);
        String[] parts = rest.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].equals(tenantSeg)) {
                continue;
            }
            if (i + 1 < parts.length && parts[i + 1].equals(KB_SCOPED_EXPORTS_SEGMENT)) {
                return true;
            }
            if (i > 0 && parts[i - 1].equals(KB_SCOPED_EXPORTS_SEGMENT)) {
                return true;
            }
        }
        return false;
    }

    /** KB 作用域校验：错误文案或 null。 */
    public static String validateKbScopedStoragePathError(String filePath, long tenantId) {
        String base = validateStoragePathTenantError(filePath, tenantId);
        if (base != null) {
            return base;
        }
        if (!storagePathHasExportsScope(filePath, tenantId)) {
            return "storage path is outside KB-scoped exports namespace";
        }
        return null;
    }

    // ── presign 签名 ────────────────────────────────────────────────────────

    /**
     * env {@code SYSTEM_AES_KEY} 少于 16 字节视为
     * "本部署不能签名"（返回 null，不是空 key）。
     */
    public static byte[] systemHmacKey() {
        String key = com.ragagent.common.crypto.CryptoService.rawAesKey();
        if (key == null || key.length() < 16) {
            return null;
        }
        return key.getBytes(StandardCharsets.UTF_8);
    }

    /** HMAC-SHA256(canonical payload) 的 hex。 */
    public static String signPayload(byte[] key, String filePath, long tenantId, long expires) {
        String payload = "file_path=" + filePath + "&tenant_id=" + tenantId + "&expires=" + expires;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] sum = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(sum.length * 2);
            for (byte b : sum) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hmac-sha256 unavailable", e);
        }
    }

    /**
     * 签名有效且未过期 → true。key 未配置 /
     * expires 非整数 / 已过期 / 签名不等（常数时间比较）→ false。
     */
    public static boolean verifyFileUrlSig(String filePath, long tenantId, String expiresStr, String sig) {
        byte[] key = systemHmacKey();
        if (key == null) {
            return false;
        }
        long expires;
        try {
            expires = Long.parseLong(expiresStr == null ? "" : expiresStr.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (java.time.Instant.now().getEpochSecond() > expires) {
            return false;
        }
        String expected = signPayload(key, filePath, tenantId, expires);
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                (sig == null ? "" : sig).getBytes(StandardCharsets.UTF_8));
    }

    // ── 存储引用识别 ────────────────────────────────────────────────────────

    /**
     * 存储引用形态：
     * {@code \b(?:resource://[0-9A-Za-z_-]+|(?:storage://[0-9A-Za-z_-]+/)?(?:local|minio|s3|cos|tos|oss|obs|ks3)://[^\s)\]>"]+)}
     *
     * <p>已知保留差异：Java 的 {@code \s} 多含 {@code \x0B}——URL 里出现垂直制表符
     * 不可能，不为此收窄写法。</p>
     */
    private static final Pattern STORAGE_REFERENCE_PATTERN = Pattern.compile(
            "\\b(?:resource://[0-9A-Za-z_-]+|(?:storage://[0-9A-Za-z_-]+/)?"
                    + "(?:local|minio|s3|cos|tos|oss|obs|ks3)://[^\\s)\\]>\"]+)");

    /**
     * 整 token 相等才算命中；text 以
     * {@code [ {"} 开头时先按 JSON 解码再递归（防嵌套 JSON 字符串的转义掩护）。
     */
    public static boolean containsStorageReference(String text, String reference) {
        if (reference == null || reference.isEmpty()) {
            return false;
        }
        String trimmed = text == null ? "" : text.trim();
        if (!trimmed.isEmpty()) {
            char first = trimmed.charAt(0);
            if (first == '[' || first == '{' || first == '"') {
                try {
                    Object value = new com.fasterxml.jackson.databind.ObjectMapper().readValue(trimmed, Object.class);
                    return containsStorageReferenceValue(value, reference);
                } catch (Exception ignored) {
                    // Go: json.Unmarshal 失败 → 落回正则扫描
                }
            }
        }
        Matcher m = STORAGE_REFERENCE_PATTERN.matcher(text == null ? "" : text);
        while (m.find()) {
            if (m.group().equals(reference)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsStorageReferenceValue(Object value, String reference) {
        if (value instanceof String s) {
            return containsStorageReference(s, reference);
        }
        if (value instanceof java.util.List<?> list) {
            for (Object item : list) {
                if (containsStorageReferenceValue(item, reference)) {
                    return true;
                }
            }
        }
        if (value instanceof java.util.Map<?, ?> map) {
            for (Object item : map.values()) {
                if (containsStorageReferenceValue(item, reference)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── 部署环境 ────────────────────────────────────────────────────────────

    /** 启动期快照，缺省 /data/files。 */
    public static String localStorageBaseDir() {
        String baseDir = StorageRuntimeEnv.localStorageBaseDir();
        if (baseDir == null || baseDir.trim().isEmpty()) {
            return "/data/files";
        }
        return baseDir.trim();
    }

    /** 本地存储根的绝对路径形态。 */
    public static String localStorageAbsDir() {
        return Path.of(localStorageBaseDir()).toAbsolutePath().normalize().toString();
    }

    /** 全局 STORAGE_TYPE 读取（缺省 local，小写化）。 */
    public static String globalStorageType() {
        String t = StorageRuntimeEnv.storageType();
        if (t == null || t.trim().isEmpty()) {
            return "local";
        }
        return t.trim().toLowerCase(Locale.ROOT);
    }
}
