package com.ragagent.storage.fileserve;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 存储 provider:// 路径的解析与校验工具（收尾批 W5c，对照 Go 三个源文件）：
 *
 * <ul>
 *   <li>{@code types/knowledgebase.go} 的 {@code ParseStorageBackendPath} /
 *       {@code ParseProviderScheme}；</li>
 *   <li>{@code types/resource.go} 的 {@code ParseResourcePath} / {@code BuildResourcePath}；</li>
 *   <li>{@code utils/presign.go} 的路径租户段校验族 + {@code VerifyFileURLSig}；</li>
 *   <li>{@code types/file_reference.go} 的 {@code ContainsStorageReference}。</li>
 * </ul>
 *
 * <p>Go 侧 {@code router/files.go} 的 {@code parseStorageTarget} = 前两者：
 * {@code "backend://3/local://7/x.png" → ("3","local")}、{@code "cos://7/x.png" → ("","cos")}。</p>
 */
public final class StoragePaths {

    private StoragePaths() {
    }

    /** 对照 Go {@code storageBackendScheme}（knowledgebase.go L11）。 */
    public static final String STORAGE_BACKEND_SCHEME = "storage://";
    /** 对照 Go {@code ResourceScheme} / {@code ResourceHandleLength}（resource.go L12-13）。 */
    public static final String RESOURCE_SCHEME = "resource://";
    public static final int RESOURCE_HANDLE_LENGTH = 22;

    /** 对照 Go ParseProviderScheme 的 provider 列表——<b>顺序有语义</b>（逐个前缀匹配）。 */
    private static final String[] PROVIDERS = {"local", "minio", "cos", "tos", "s3", "oss", "ks3", "obs", "dummy"};

    /** 对照 Go {@code kbScopedExportsSegment}（presign.go L109）。 */
    private static final String KB_SCOPED_EXPORTS_SEGMENT = "exports";

    /** 对照 Go {@code presignPath}。 */
    public static final String PRESIGN_PATH = "/api/v1/files/presigned";

    // ── provider / backend 解析（types/knowledgebase.go）────────────────────

    /** 对照 Go {@code ParseStorageBackendPath}。 */
    public static boolean hasStorageBackendPrefix(String path) {
        return path != null && path.startsWith(STORAGE_BACKEND_SCHEME);
    }

    /**
     * 对照 Go {@code ParseStorageBackendPath}：{@code storage://<id>/<providerPath>}。
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

    /** 对照 Go {@code ParseProviderScheme}：先剥 backend 包装，再按固定顺序前缀匹配。 */
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

    /** 对照 Go {@code router/files.go parseStorageTarget}：(backendID, provider) 二元组。 */
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

    // ── resource:// 手柄（types/resource.go）────────────────────────────────

    public static boolean isResourceHandleChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    /** 对照 Go {@code ParseResourcePath}：合法时返回 handle，否则 null。 */
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

    // ── 路径租户段校验（utils/presign.go）───────────────────────────────────

    /** 对照 Go {@code unwrapStorageBackendPath}（presign.go L151-161）。 */
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

    /** 对照 Go {@code ParseTenantIDFromStoragePath}：第一个可解析为无符号整数的段。 */
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
                // Go 的 ParseUint 继续尝试下一段
            }
        }
        return 0;
    }

    /** 对照 Go {@code ValidateStoragePathTenant}：路径租户段缺失或不匹配 → 错误文案。 */
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

    /** 对照 Go {@code storagePathHasExportsScope}（presign.go L167-186）。 */
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

    /** 对照 Go {@code ValidateKBScopedStoragePath}：错误文案或 null。 */
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

    // ── presign 签名（utils/presign.go）─────────────────────────────────────

    /**
     * 对照 Go {@code SystemHMACKey}：env {@code SYSTEM_AES_KEY} 少于 16 字节视为
     * "本部署不能签名"（返回 null，不是空 key）。
     */
    public static byte[] systemHmacKey() {
        String key = com.ragagent.common.crypto.CryptoService.rawAesKey();
        if (key == null || key.length() < 16) {
            return null;
        }
        return key.getBytes(StandardCharsets.UTF_8);
    }

    /** 对照 Go {@code signPayload}：HMAC-SHA256(canonical payload) 的 hex。 */
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
     * 对照 Go {@code VerifyFileURLSig}：签名有效且未过期 → true。key 未配置 /
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

    // ── 存储引用识别（types/file_reference.go）──────────────────────────────

    /**
     * 对照 Go {@code StorageReferencePattern}。Go 的 RE2 写法原样搬：
     * {@code \b(?:resource://[0-9A-Za-z_-]+|(?:storage://[0-9A-Za-z_-]+/)?(?:local|minio|s3|cos|tos|oss|obs|ks3)://[^\s)\]>"]+)}
     *
     * <p>已知保留差异（同 storageurl 包的 §9 备案）：Java 的 {@code \s} 含
     * {@code \x0B}、RE2 不含——URL 里出现垂直制表符不可能，不为此偏离 Go 写法。</p>
     */
    private static final Pattern STORAGE_REFERENCE_PATTERN = Pattern.compile(
            "\\b(?:resource://[0-9A-Za-z_-]+|(?:storage://[0-9A-Za-z_-]+/)?"
                    + "(?:local|minio|s3|cos|tos|oss|obs|ks3)://[^\\s)\\]>\"]+)");

    /**
     * 对照 Go {@code ContainsStorageReference}：整 token 相等才算命中；text 以
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

    /** 对照 Go {@code files.go localStorageBaseDir}：启动期快照缺省 /data/files。 */
    public static String localStorageBaseDir() {
        String baseDir = com.ragagent.storage.config.StorageRuntimeEnv.localStorageBaseDir();
        if (baseDir == null || baseDir.trim().isEmpty()) {
            return "/data/files";
        }
        return baseDir.trim();
    }

    /** 对照 Go {@code localStorageAbsDir}。 */
    public static String localStorageAbsDir() {
        return Path.of(localStorageBaseDir()).toAbsolutePath().normalize().toString();
    }

    /** 对照 Go {@code resolve_tenant.go} 的全局 STORAGE_TYPE 读取（缺省 local，小写化）。 */
    public static String globalStorageType() {
        String t = com.ragagent.storage.config.StorageRuntimeEnv.storageType();
        if (t == null || t.trim().isEmpty()) {
            return "local";
        }
        return t.trim().toLowerCase(Locale.ROOT);
    }
}
