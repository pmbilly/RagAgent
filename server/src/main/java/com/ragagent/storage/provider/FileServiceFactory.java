package com.ragagent.storage.provider;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;

import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig;
import com.ragagent.common.security.SsrfGuard;

/**
 * 按租户存储配置造 provider 专属 {@link FileService}
 * （对照 Go {@code NewFileServiceFromStorageConfig}，file/factory.go 全文）。
 *
 * <p>provider 为空时回落到配置里的 {@code default_provider}；根目录缺省链照 Go：
 * 入参 → {@code LOCAL_STORAGE_BASE_DIR} → {@code /data/files}；local 再叠加
 * {@code sec.local.path_prefix}（{@code SafeJoinUnderBase} 语义：越界就忽略前缀）。</p>
 *
 * <p><b>批次划分（照 Go 的真实形态）</b>：批次一已落地 <b>local + s3 协议族</b>
 * （s3/minio/obs/ks3——Go 对 obs 用 AWS SDK + 端点解析、对 ks3 用其 AWS SDK 分支，
 * 本身就是 S3 兼容路线）；批次二补 <b>oss/cos/tos</b>（各家官方 SDK 逐条对齐）。
 * 未实现的 provider 抛明确异常，不静默退化到本地盘。</p>
 */
public final class FileServiceFactory {

    /** 对照 Go：{@code LOCAL_STORAGE_BASE_DIR} 的缺省。 */
    public static final String DEFAULT_LOCAL_BASE_DIR = "/data/files";
    public static final String ENV_LOCAL_BASE_DIR = "LOCAL_STORAGE_BASE_DIR";
    /** 对照 Go：本地后端的预签名基址来源。 */
    public static final String ENV_EXTERNAL_URL = "APP_EXTERNAL_URL";
    /** provider 缺省前缀（照 Go：s3/obs/ks3 均以 {@code weknora/} 起）。 */
    public static final String DEFAULT_PATH_PREFIX = "weknora/";

    /** 已实现的 provider（批次一：local + S3 协议族；批次二：oss/cos/tos）。 */
    static final Set<String> IMPLEMENTED =
            Set.of("local", "s3", "minio", "obs", "ks3", "oss", "cos", "tos");
    /** 批次二已补齐，不再有"未实现"的 provider（保留集合以便扩展时复用判定）。 */
    static final Set<String> PENDING = Set.of();

    private FileServiceFactory() {
    }

    /** 解析结果：服务 + 归一化后的 provider 名（对照 Go 的 {@code (svc, p, err)}）。 */
    public record Created(FileService service, String provider) {
    }

    /** 便捷入口：用默认 SSRF 闸（生产装配应注入 Spring 管理的单例）。 */
    public static Created fromStorageConfig(String provider, StorageEngineConfig sec,
                                            String localBaseDir) {
        return fromStorageConfig(provider, sec, localBaseDir, new SsrfGuard());
    }

    public static Created fromStorageConfig(String provider, StorageEngineConfig sec,
                                            String localBaseDir, SsrfGuard ssrfGuard) {
        String p = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty() && sec != null && sec.getDefaultProvider() != null) {
            p = sec.getDefaultProvider().trim().toLowerCase(Locale.ROOT);
        }
        if (p.isEmpty()) {
            throw new IllegalArgumentException("empty provider");
        }

        switch (p) {
            case "local" -> {
                String base = localBaseDir == null || localBaseDir.trim().isEmpty()
                        ? envOr(ENV_LOCAL_BASE_DIR, DEFAULT_LOCAL_BASE_DIR)
                        : localBaseDir.trim();
                String dir = base;
                if (sec != null && sec.getLocal() != null
                        && sec.getLocal().getPathPrefix() != null
                        && !sec.getLocal().getPathPrefix().trim().isEmpty()) {
                    String joined = safeJoinUnderBase(base, sec.getLocal().getPathPrefix().trim());
                    if (joined != null) {
                        dir = joined;
                    }
                }
                return new Created(new LocalFileService(dir, envOr(ENV_EXTERNAL_URL, "")), p);
            }
            case "s3" -> {
                StorageEngineConfig.S3EngineConfig c = sec == null ? null : sec.getS3();
                if (c == null || trim(c.getRegion()).isEmpty() || trim(c.getBucketName()).isEmpty()
                        || trim(c.getAccessKey()).isEmpty() != trim(c.getSecretKey()).isEmpty()) {
                    throw new IllegalArgumentException("incomplete s3 config");
                }
                String prefix = trim(c.getPathPrefix()).isEmpty()
                        ? DEFAULT_PATH_PREFIX : trim(c.getPathPrefix());
                return new Created(new S3CompatibleFileService(new S3CompatibleFileService.Config(
                        "s3", trim(c.getEndpoint()), trim(c.getAccessKey()), trim(c.getSecretKey()),
                        trim(c.getBucketName()), trim(c.getRegion()), prefix,
                        c.isForcePathStyle()), ssrfGuard), p);
            }
            case "minio" -> {
                StorageEngineConfig.MinioEngineConfig c = sec == null ? null : sec.getMinio();
                if (c == null) {
                    throw new IllegalArgumentException("incomplete minio config");
                }
                boolean remote = "remote".equalsIgnoreCase(trim(c.getMode()));
                String endpoint = remote ? trim(c.getEndpoint()) : envOr("MINIO_ENDPOINT", "");
                String accessKey = remote ? trim(c.getAccessKeyId()) : envOr("MINIO_ACCESS_KEY_ID", "");
                String secretKey = remote ? trim(c.getSecretAccessKey())
                        : envOr("MINIO_SECRET_ACCESS_KEY", "");
                String bucket = trim(c.getBucketName()).isEmpty()
                        ? envOr("MINIO_BUCKET_NAME", "") : trim(c.getBucketName());
                if (endpoint.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty()
                        || bucket.isEmpty()) {
                    throw new IllegalArgumentException("incomplete minio config");
                }
                // Go 的 minio-go 用 Secure 决定 http/https（endpoint 只给 host）
                String url = endpoint.contains("://")
                        ? endpoint : (c.isUseSsl() ? "https://" : "http://") + endpoint;
                return new Created(new S3CompatibleFileService(new S3CompatibleFileService.Config(
                        "minio", url, accessKey, secretKey, bucket, "us-east-1", "", true),
                        ssrfGuard), p);
            }
            case "obs" -> {
                StorageEngineConfig.ObsEngineConfig c = sec == null ? null : sec.getObs();
                String endpoint = firstNonEmpty(c == null ? "" : trim(c.getEndpoint()),
                        envOr("OBS_ENDPOINT", ""));
                String region = firstNonEmpty(c == null ? "" : trim(c.getRegion()),
                        envOr("OBS_REGION", ""));
                String accessKey = firstNonEmpty(c == null ? "" : trim(c.getAccessKey()),
                        envOr("OBS_ACCESS_KEY", ""));
                String secretKey = firstNonEmpty(c == null ? "" : trim(c.getSecretKey()),
                        envOr("OBS_SECRET_KEY", ""));
                String bucket = firstNonEmpty(c == null ? "" : trim(c.getBucketName()),
                        envOr("OBS_BUCKET_NAME", ""));
                String prefix = firstNonEmpty(c == null ? "" : trim(c.getPathPrefix()),
                        envOr("OBS_PATH_PREFIX", ""));
                if (prefix.isEmpty()) {
                    prefix = DEFAULT_PATH_PREFIX;
                }
                if (endpoint.isEmpty() || region.isEmpty() || accessKey.isEmpty()
                        || secretKey.isEmpty() || bucket.isEmpty()) {
                    throw new IllegalArgumentException("incomplete obs config");
                }
                return new Created(new S3CompatibleFileService(new S3CompatibleFileService.Config(
                        "obs", endpoint, accessKey, secretKey, bucket, region, prefix, true),
                        ssrfGuard), p);
            }
            case "ks3" -> {
                StorageEngineConfig.Ks3EngineConfig c = sec == null ? null : sec.getKs3();
                if (c == null || trim(c.getEndpoint()).isEmpty() || trim(c.getRegion()).isEmpty()
                        || trim(c.getAccessKey()).isEmpty() || trim(c.getSecretKey()).isEmpty()
                        || trim(c.getBucketName()).isEmpty()) {
                    throw new IllegalArgumentException("incomplete ks3 config");
                }
                String prefix = trim(c.getPathPrefix()).isEmpty()
                        ? DEFAULT_PATH_PREFIX : trim(c.getPathPrefix());
                return new Created(new S3CompatibleFileService(new S3CompatibleFileService.Config(
                        "ks3", trim(c.getEndpoint()), trim(c.getAccessKey()), trim(c.getSecretKey()),
                        trim(c.getBucketName()), trim(c.getRegion()), prefix, true),
                        ssrfGuard), p);
            }
            case "oss" -> {
                StorageEngineConfig.OssEngineConfig c = sec == null ? null : sec.getOss();
                if (c == null || trim(c.getEndpoint()).isEmpty() || trim(c.getRegion()).isEmpty()
                        || trim(c.getAccessKey()).isEmpty() || trim(c.getSecretKey()).isEmpty()
                        || trim(c.getBucketName()).isEmpty()) {
                    throw new IllegalArgumentException("incomplete oss config");
                }
                String prefix = trim(c.getPathPrefix()).isEmpty()
                        ? DEFAULT_PATH_PREFIX : trim(c.getPathPrefix());
                String tempBucket = c.isUseTempBucket() ? trim(c.getTempBucketName()) : "";
                return new Created(new OssFileService(trim(c.getEndpoint()), trim(c.getRegion()),
                        trim(c.getAccessKey()), trim(c.getSecretKey()), trim(c.getBucketName()),
                        prefix, tempBucket, trim(c.getTempRegion()), ssrfGuard), p);
            }
            case "cos" -> {
                StorageEngineConfig.CosEngineConfig c = sec == null ? null : sec.getCos();
                if (c == null || trim(c.getSecretId()).isEmpty() || trim(c.getSecretKey()).isEmpty()
                        || trim(c.getBucketName()).isEmpty() || trim(c.getRegion()).isEmpty()) {
                    throw new IllegalArgumentException("incomplete cos config");
                }
                // 照 Go：COS 的 prefix 默认 "weknora"（不带斜杠，服务内自行拼接）
                String prefix = trim(c.getPathPrefix()).isEmpty() ? "weknora"
                        : trim(c.getPathPrefix());
                return new Created(new CosFileService(trim(c.getBucketName()), trim(c.getRegion()),
                        trim(c.getSecretId()), trim(c.getSecretKey()), prefix,
                        trim(c.getTempBucketName()), trim(c.getTempRegion())), p);
            }
            case "tos" -> {
                StorageEngineConfig.TosEngineConfig c = sec == null ? null : sec.getTos();
                if (c == null || trim(c.getEndpoint()).isEmpty() || trim(c.getRegion()).isEmpty()
                        || trim(c.getAccessKey()).isEmpty() || trim(c.getSecretKey()).isEmpty()
                        || trim(c.getBucketName()).isEmpty()) {
                    throw new IllegalArgumentException("incomplete tos config");
                }
                // 照 Go：TOS 的 pathPrefix 原样传入（不设默认，服务内 trim 斜杠）
                return new Created(new TosFileService(trim(c.getEndpoint()), trim(c.getRegion()),
                        trim(c.getAccessKey()), trim(c.getSecretKey()), trim(c.getBucketName()),
                        c.getPathPrefix(), trim(c.getTempBucketName()), trim(c.getTempRegion()),
                        ssrfGuard), p);
            }
            default -> {
                if (PENDING.contains(p)) {
                    throw new UnsupportedOperationException(
                            "provider \"" + p + "\" not implemented yet (A3 phase 2)");
                }
                throw new IllegalArgumentException("unsupported provider \"" + p + "\"");
            }
        }
    }

    /** 对照 {@code SafeJoinUnderBase}：越界返回 null（调用方保留原 base）。 */
    static String safeJoinUnderBase(String base, String prefix) {
        try {
            Path b = Paths.get(base).toAbsolutePath().normalize();
            Path joined = b.resolve(prefix).normalize();
            return joined.startsWith(b) ? joined.toString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String firstNonEmpty(String a, String b) {
        return a == null || a.isEmpty() ? (b == null ? "" : b) : a;
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.trim().isEmpty() ? fallback : v.trim();
    }
}
