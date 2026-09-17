package com.ragagent.wiki.domain;

/**
 * 一个 knowledge id 在 source_refs 上的三种匹配形式（由 {@link WikiSourceRefs#of} 构造）。
 *
 * <p>刻意写成带 getter 的 POJO 而不是 record：MyBatis 的 {@code #{n.exactLike}}
 * 走 Reflection/MetaObject 取属性，POJO getter 是各版本都稳的形态。</p>
 *
 * @param needle     PG jsonb 包含分支的操作数，形如 {@code ["doc-1"]}
 * @param exactLike  非 PG 方言下"数组里恰好含该 id"的 LIKE 模式，形如 {@code %"doc-1"%}
 * @param prefixLike 历史 {@code "id|title"} 形态的 LIKE 模式，形如 {@code %"doc-1|%}（两种方言共用）
 */
public class SourceRefNeedle {

    private String needle = "";
    private String exactLike = "";
    private String prefixLike = "";

    public SourceRefNeedle() {}

    public SourceRefNeedle(String needle, String exactLike, String prefixLike) {
        this.needle = needle == null ? "" : needle;
        this.exactLike = exactLike == null ? "" : exactLike;
        this.prefixLike = prefixLike == null ? "" : prefixLike;
    }

    public String getNeedle() { return needle; }
    public void setNeedle(String v) { this.needle = v == null ? "" : v; }

    public String getExactLike() { return exactLike; }
    public void setExactLike(String v) { this.exactLike = v == null ? "" : v; }

    public String getPrefixLike() { return prefixLike; }
    public void setPrefixLike(String v) { this.prefixLike = v == null ? "" : v; }
}
