package com.ragagent.auth.domain.tenantconfig;

import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig.CosEngineConfig;
import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig.Ks3EngineConfig;
import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig.MinioEngineConfig;
import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig.ObsEngineConfig;
import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig.OssEngineConfig;
import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig.S3EngineConfig;
import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig.TosEngineConfig;

/**
 * 租户配置密钥的打码与合并（响应面打码 / 更新面保留旧值）三族：
 * WebSearch、ParserEngine、StorageEngine。
 *
 * <p>⚠️ preserveIfRedacted 的语义是「空串<b>或</b>占位符都保留旧值」
 * ——唯一例外是 S3：空串意味着
 * 「切到 AWS 默认凭据链」，只有占位符才回退。</p>
 */
public final class TenantConfigRedaction {

    /** 密钥打码占位符。 */
    public static final String REDACTED = "***";

    private TenantConfigRedaction() {
    }

    /** 空串或 "***" → 保留旧值。 */
    public static String preserveIfRedacted(String incoming, String existing) {
        if (incoming == null || incoming.isEmpty() || REDACTED.equals(incoming)) {
            return existing == null ? "" : existing;
        }
        return incoming;
    }

    // ── WebSearch ──────────────────────────────────────────────────────────

    /**
     * 响应面（打码）：
     * 先 Effective 归一化（max_results≤0→10、compression_method 空→"none"、
     * blacklist null→[]），再 api_key 清空、proxy_url 非空（trim 后）打码。
     * 入参 null → null（调用方在信封层输出 "data":null）。
     */
    public static WebSearchConfig webSearchForResponse(WebSearchConfig cfg) {
        if (cfg == null) {
            return null;
        }
        cfg.applyEffective();
        cfg.setApiKey("");
        if (!cfg.getProxyUrl().trim().isEmpty()) {
            cfg.setProxyUrl(REDACTED);
        }
        return cfg;
    }

    /**
     * 更新面合并：incoming 先 Effective，再对
     * api_key/proxy_url 做 preserve。prev 取 existing 的 Effective 值。
     */
    public static WebSearchConfig mergeWebSearch(WebSearchConfig incoming, WebSearchConfig existing) {
        incoming.applyEffective();
        WebSearchConfig prev = new WebSearchConfig();
        if (existing != null) {
            prev = existing;
            prev.applyEffective();
        }
        incoming.setApiKey(preserveIfRedacted(incoming.getApiKey(), prev.getApiKey()));
        incoming.setProxyUrl(preserveIfRedacted(incoming.getProxyUrl(), prev.getProxyUrl()));
        return incoming;
    }

    // ── ParserEngine ───────────────────────────────────────────────────────

    /** 响应面（打码）：两个密钥非空打码。 */
    public static ParserEngineConfig parserEngineForResponse(ParserEngineConfig cfg) {
        if (cfg == null) {
            return null;
        }
        if (!cfg.getMineruApiKey().isEmpty()) {
            cfg.setMineruApiKey(REDACTED);
        }
        if (!cfg.getPaddleOcrVlCloudToken().isEmpty()) {
            cfg.setPaddleOcrVlCloudToken(REDACTED);
        }
        return cfg;
    }

    /**
     * 更新面合并：两个密钥 preserve；
     * incoming 未携带 chat_parser_engine_rules 时保留存量的 legacy 规则。
     */
    public static ParserEngineConfig mergeParserEngine(ParserEngineConfig incoming,
                                                       ParserEngineConfig existing) {
        incoming.setMineruApiKey(preserveIfRedacted(
                incoming.getMineruApiKey(), existing == null ? "" : existing.getMineruApiKey()));
        incoming.setPaddleOcrVlCloudToken(preserveIfRedacted(
                incoming.getPaddleOcrVlCloudToken(),
                existing == null ? "" : existing.getPaddleOcrVlCloudToken()));
        if (incoming.getChatParserEngineRules() == null && existing != null) {
            incoming.setChatParserEngineRules(existing.getChatParserEngineRules());
        }
        return incoming;
    }

    // ── StorageEngine ──────────────────────────────────────────────────────

    /** 响应面（打码）：7 个云 provider 的密钥对非空打码。 */
    public static StorageEngineConfig storageEngineForResponse(StorageEngineConfig cfg) {
        if (cfg == null) {
            return null;
        }
        if (cfg.getMinio() != null) {
            MinioEngineConfig c = cfg.getMinio();
            if (!c.getAccessKeyId().isEmpty()) {
                c.setAccessKeyId(REDACTED);
            }
            if (!c.getSecretAccessKey().isEmpty()) {
                c.setSecretAccessKey(REDACTED);
            }
        }
        if (cfg.getCos() != null) {
            CosEngineConfig c = cfg.getCos();
            if (!c.getSecretId().isEmpty()) {
                c.setSecretId(REDACTED);
            }
            if (!c.getSecretKey().isEmpty()) {
                c.setSecretKey(REDACTED);
            }
        }
        if (cfg.getTos() != null) {
            TosEngineConfig c = cfg.getTos();
            if (!c.getAccessKey().isEmpty()) {
                c.setAccessKey(REDACTED);
            }
            if (!c.getSecretKey().isEmpty()) {
                c.setSecretKey(REDACTED);
            }
        }
        if (cfg.getS3() != null) {
            S3EngineConfig c = cfg.getS3();
            if (!c.getAccessKey().isEmpty()) {
                c.setAccessKey(REDACTED);
            }
            if (!c.getSecretKey().isEmpty()) {
                c.setSecretKey(REDACTED);
            }
        }
        if (cfg.getOss() != null) {
            OssEngineConfig c = cfg.getOss();
            if (!c.getAccessKey().isEmpty()) {
                c.setAccessKey(REDACTED);
            }
            if (!c.getSecretKey().isEmpty()) {
                c.setSecretKey(REDACTED);
            }
        }
        if (cfg.getKs3() != null) {
            Ks3EngineConfig c = cfg.getKs3();
            if (!c.getAccessKey().isEmpty()) {
                c.setAccessKey(REDACTED);
            }
            if (!c.getSecretKey().isEmpty()) {
                c.setSecretKey(REDACTED);
            }
        }
        if (cfg.getObs() != null) {
            ObsEngineConfig c = cfg.getObs();
            if (!c.getAccessKey().isEmpty()) {
                c.setAccessKey(REDACTED);
            }
            if (!c.getSecretKey().isEmpty()) {
                c.setSecretKey(REDACTED);
            }
        }
        return cfg;
    }

    /**
     * 更新面合并：各 provider 密钥对 preserve。
     * S3 例外：只有 "***" 才回退，空串保留（切 IAM 链语义）。
     */
    public static StorageEngineConfig mergeStorageEngine(StorageEngineConfig incoming,
                                                         StorageEngineConfig existing) {
        if (incoming.getMinio() != null) {
            MinioEngineConfig c = incoming.getMinio();
            MinioEngineConfig prev = existing == null ? null : existing.getMinio();
            c.setAccessKeyId(preserveIfRedacted(c.getAccessKeyId(), prev == null ? "" : prev.getAccessKeyId()));
            c.setSecretAccessKey(preserveIfRedacted(
                    c.getSecretAccessKey(), prev == null ? "" : prev.getSecretAccessKey()));
        }
        if (incoming.getCos() != null) {
            CosEngineConfig c = incoming.getCos();
            CosEngineConfig prev = existing == null ? null : existing.getCos();
            c.setSecretId(preserveIfRedacted(c.getSecretId(), prev == null ? "" : prev.getSecretId()));
            c.setSecretKey(preserveIfRedacted(c.getSecretKey(), prev == null ? "" : prev.getSecretKey()));
        }
        if (incoming.getTos() != null) {
            TosEngineConfig c = incoming.getTos();
            TosEngineConfig prev = existing == null ? null : existing.getTos();
            c.setAccessKey(preserveIfRedacted(c.getAccessKey(), prev == null ? "" : prev.getAccessKey()));
            c.setSecretKey(preserveIfRedacted(c.getSecretKey(), prev == null ? "" : prev.getSecretKey()));
        }
        if (incoming.getS3() != null) {
            S3EngineConfig c = incoming.getS3();
            S3EngineConfig prev = existing == null ? null : existing.getS3();
            // S3 例外：空串 = 切 AWS 默认凭据链，不清旧值只认 "***"
            if (REDACTED.equals(c.getAccessKey())) {
                c.setAccessKey(prev == null ? "" : prev.getAccessKey());
            }
            if (REDACTED.equals(c.getSecretKey())) {
                c.setSecretKey(prev == null ? "" : prev.getSecretKey());
            }
        }
        if (incoming.getOss() != null) {
            OssEngineConfig c = incoming.getOss();
            OssEngineConfig prev = existing == null ? null : existing.getOss();
            c.setAccessKey(preserveIfRedacted(c.getAccessKey(), prev == null ? "" : prev.getAccessKey()));
            c.setSecretKey(preserveIfRedacted(c.getSecretKey(), prev == null ? "" : prev.getSecretKey()));
        }
        if (incoming.getKs3() != null) {
            Ks3EngineConfig c = incoming.getKs3();
            Ks3EngineConfig prev = existing == null ? null : existing.getKs3();
            c.setAccessKey(preserveIfRedacted(c.getAccessKey(), prev == null ? "" : prev.getAccessKey()));
            c.setSecretKey(preserveIfRedacted(c.getSecretKey(), prev == null ? "" : prev.getSecretKey()));
        }
        if (incoming.getObs() != null) {
            ObsEngineConfig c = incoming.getObs();
            ObsEngineConfig prev = existing == null ? null : existing.getObs();
            c.setAccessKey(preserveIfRedacted(c.getAccessKey(), prev == null ? "" : prev.getAccessKey()));
            c.setSecretKey(preserveIfRedacted(c.getSecretKey(), prev == null ? "" : prev.getSecretKey()));
        }
        return incoming;
    }
}
