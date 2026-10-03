package com.ragagent.retrieval.engine.doris;

import java.util.Locale;

/**
 * Doris 兼容模式——{@code auto}/{@code legacy}/{@code inner_product_duplicate}
 * 三态的解析与判定。
 *
 * <p>两种表模型的取舍：</p>
 * <ul>
 *   <li>{@code legacy}：{@code UNIQUE KEY(id)} + {@code cosine_distance} ANN +
 *       Stream Load partial update；</li>
 *   <li>{@code inner_product_duplicate}：{@code DUPLICATE KEY(id)} + 单位化内积 +
 *       delete/insert 重写。</li>
 * </ul>
 * <p>该设置在 embedding 表创建后不可直接互换；切换模式前需要重建这些表。</p>
 */
public enum DorisCompatMode {

    AUTO("auto"),
    LEGACY("legacy"),
    INNER_PRODUCT_DUPLICATE("inner_product_duplicate");

    /** 配置键。 */
    public static final String ENV_KEY = "DORIS_COMPAT_MODE";

    private final String wire;

    DorisCompatMode(String wire) {
        this.wire = wire;
    }

    /** 日志/错误文案里的字面形态。 */
    public String wire() {
        return wire;
    }

    /** 非 legacy 模式写入/查询前做单位化。 */
    public boolean normalizeEmbeddings() {
        return this != LEGACY;
    }

    /** DUPLICATE KEY 表上按 id 做 delete + insert。 */
    public boolean usesReplaceWrite() {
        return this != LEGACY;
    }

    /** 批量更新走整行重写（非 partial update）。 */
    public boolean usesRewriteChunkUpdates() {
        return this != LEGACY;
    }

    /** 解析结果（invalidRaw 非空 = 非法输入原样带回）。 */
    public record Configured(DorisCompatMode mode, String invalidRaw) {
    }

    /**
     * 解析配置值：trim 后空 → auto；大小写不敏感；
     * {@code inner-product-duplicate}/{@code inner_product}/{@code inner-product} 归一到
     * inner_product_duplicate；其余非法值回落 auto 并把原值带回给调用方告警。
     */
    public static Configured configured(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return new Configured(AUTO, "");
        }
        return switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "auto" -> new Configured(AUTO, "");
            case "legacy" -> new Configured(LEGACY, "");
            case "inner_product_duplicate", "inner-product-duplicate", "inner_product", "inner-product" ->
                    new Configured(INNER_PRODUCT_DUPLICATE, "");
            default -> new Configured(AUTO, trimmed);
        };
    }

    /**
     * 从建表 DDL 判读模式：DDL 小写后含 {@code "duplicate key("} → 内积副本模式，
     * 含 {@code "unique key("} → legacy；否则抛不支持错误。
     */
    public static DorisCompatMode fromDdl(String ddl) {
        String normalized = ddl == null ? "" : ddl.toLowerCase(Locale.ROOT);
        if (normalized.contains("duplicate key(")) {
            return INNER_PRODUCT_DUPLICATE;
        }
        if (normalized.contains("unique key(")) {
            return LEGACY;
        }
        throw new IllegalStateException(
                "unsupported table definition; expected UNIQUE KEY or DUPLICATE KEY");
    }
}
