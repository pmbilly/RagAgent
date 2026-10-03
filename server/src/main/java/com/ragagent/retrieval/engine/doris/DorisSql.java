package com.ragagent.retrieval.engine.doris;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;

/**
 * Doris 驱动的常量与纯函数族——字段常量 / 列序 / whereBuilder / embedding 字面量与解析 /
 * 校验与单位化 / 建表 DDL / 存储估算 / SourceID 改写。
 *
 * <p><b>实现说明</b>：① 浮点字面量按最短往返表示输出（去掉尾随 {@code .0}、指数形态
 * {@code 1e+07}），与 {@code Float.toString} 一致；② 存储估算按 UTF-8 字节数计
 * （{@code utf8Length}，非 UTF-16 长度）；③ 占位符 {@code ?} 语义由 JDBC 驱动
 * 承担（MySQL 协议）。</p>
 */
public final class DorisSql {

    private DorisSql() {
    }

    // ── 字段名常量 ──────────────────────────────────────────────────────────

    public static final String FIELD_ID = "id";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_SOURCE_ID = "source_id";
    public static final String FIELD_SOURCE_TYPE = "source_type";
    public static final String FIELD_CHUNK_ID = "chunk_id";
    public static final String FIELD_KNOWLEDGE_ID = "knowledge_id";
    public static final String FIELD_KNOWLEDGE_BASE_ID = "knowledge_base_id";
    public static final String FIELD_TAG_ID = "tag_id";
    public static final String FIELD_IS_ENABLED = "is_enabled";
    public static final String FIELD_EMBEDDING = "embedding";

    /** INSERT / SELECT 的标准列序。 */
    public static final List<String> COLUMNS = List.of(
            FIELD_ID, FIELD_CONTENT, FIELD_SOURCE_ID, FIELD_SOURCE_TYPE,
            FIELD_CHUNK_ID, FIELD_KNOWLEDGE_ID, FIELD_KNOWLEDGE_BASE_ID, FIELD_TAG_ID,
            FIELD_IS_ENABLED, FIELD_EMBEDDING);

    /** 检索列序：不含 embedding（省带宽）。 */
    public static final List<String> COLUMNS_FOR_RETRIEVE = List.of(
            FIELD_ID, FIELD_CONTENT, FIELD_SOURCE_ID, FIELD_SOURCE_TYPE,
            FIELD_CHUNK_ID, FIELD_KNOWLEDGE_ID, FIELD_KNOWLEDGE_BASE_ID, FIELD_TAG_ID,
            FIELD_IS_ENABLED);

    /** 复制列序：比检索多 embedding（复制要搬向量本身）。 */
    public static final List<String> COLUMNS_FOR_COPY = COLUMNS;

    // ── whereBuilder 族 ─────────────────────────────────────────────────────

    /** 单个条件：clause 是参数化 SQL 片段（带 ? 占位），args 对应顺序。 */
    private record WhereCond(String clause, List<Object> args) {
    }

    /** {@code build()} 的结果：子句（不含 "WHERE " 前缀）+ 参数数组。 */
    public record Clause(String sql, List<Object> args) {
    }

    /**
     * 把 RetrieveParams 的过滤条件转成 SQL WHERE 子句。
     * 所有用户输入（IDs）一律走 {@code ?} 参数，严禁拼进 clause 字符串。
     */
    public static final class WhereBuilder {

        private final List<WhereCond> conds = new ArrayList<>();

        public void addEqual(String field, Object value) {
            conds.add(new WhereCond(field + " = ?", List.of(value)));
        }

        public void addIn(String field, List<String> values) {
            if (values == null || values.isEmpty()) {
                return;
            }
            conds.add(new WhereCond(placeholderClause(field, " IN (", values),
                    new ArrayList<>(values)));
        }

        public void addNotIn(String field, List<String> values) {
            if (values == null || values.isEmpty()) {
                return;
            }
            conds.add(new WhereCond(placeholderClause(field, " NOT IN (", values),
                    new ArrayList<>(values)));
        }

        private static String placeholderClause(String field, String operator, List<String> values) {
            StringBuilder clause = new StringBuilder(field).append(operator);
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    clause.append(", ");
                }
                clause.append('?');
            }
            return clause.append(')').toString();
        }

        /** 没有任何条件时返回 ("1 = 1", 空参数)，方便调用方无脑拼接。 */
        public Clause build() {
            if (conds.isEmpty()) {
                return new Clause("1 = 1", List.of());
            }
            StringBuilder sql = new StringBuilder();
            List<Object> args = new ArrayList<>();
            for (int i = 0; i < conds.size(); i++) {
                if (i > 0) {
                    sql.append(" AND ");
                }
                WhereCond c = conds.get(i);
                sql.append(c.clause());
                args.addAll(c.args());
            }
            return new Clause(sql.toString(), args);
        }
    }

    /**
     * 基础过滤：默认追加 {@code is_enabled = TRUE}（关闭的 chunk
     * 不参与检索，与 Qdrant/Milvus/Weaviate 一致）。
     */
    public static WhereBuilder buildBaseFilter(RetrieveParams params) {
        WhereBuilder w = new WhereBuilder();
        w.addEqual(FIELD_IS_ENABLED, true);
        if (params == null) {
            return w;
        }
        w.addIn(FIELD_KNOWLEDGE_BASE_ID, params.knowledgeBaseIds);
        w.addIn(FIELD_KNOWLEDGE_ID, params.knowledgeIds);
        w.addIn(FIELD_TAG_ID, params.tagIds);
        w.addNotIn(FIELD_KNOWLEDGE_ID, params.excludeKnowledgeIds);
        w.addNotIn(FIELD_CHUNK_ID, params.excludeChunkIds);
        return w;
    }

    // ── embedding 字面量族 ──────────────────────────────────────────────────

    /**
     * 解析 Doris {@code ARRAY<FLOAT>} 经 MySQL 协议
     * 返回的字面量（形如 {@code "[1,2,3]"}）。容错优先：不带 [] 也接受、空数组返回 null。
     * 数字解析失败抛 {@code strconv.ParseFloat} 原文形态的异常（由调用方包
     * {@code parse embedding: ...}）。
     */
    public static float[] parseEmbeddingLiteral(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("[")) {
            s = s.substring(1);
        }
        if (s.endsWith("]")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return null;
        }
        String[] parts = s.split(",", -1);
        float[] out = new float[parts.length];
        int n = 0;
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                out[n++] = Float.parseFloat(p);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "strconv.ParseFloat: parsing \"" + p + "\": invalid syntax");
            }
        }
        return Arrays.copyOf(out, n);
    }

    /** 非有限值异常：带上标下标，便于日志定位。 */
    public static final class InvalidEmbeddingException extends RuntimeException {

        public InvalidEmbeddingException(int index, float value) {
            super("doris: embedding[" + index + "] is not finite: " + formatFloat32(value));
        }
    }

    /**
     * 校验：元素必须全为有限值——字面量序列化对
     * NaN/±Inf 会输出 "NaN"/"+Inf"/"-Inf"，拼进 SQL 会报语法错误（或产生未定义结果）。
     * fail-fast 比悄悄写脏数据安全。
     */
    public static void validateEmbedding(float[] vec) {
        if (vec == null) {
            return;
        }
        for (int i = 0; i < vec.length; i++) {
            float v = vec[i];
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                throw new InvalidEmbeddingException(i, v);
            }
        }
    }

    /**
     * 单位化：返回单位长度副本（零向量返回原样副本，
     * 空向量返回 null），供 inner_product ANN 保持 cosine 语义。
     */
    public static float[] normalizeEmbedding(float[] vec) {
        if (vec == null || vec.length == 0) {
            return null;
        }
        double sumSquares = 0;
        for (float value : vec) {
            double f = value;
            sumSquares += f * f;
        }
        if (sumSquares == 0) {
            return vec.clone();
        }
        float norm = (float) Math.sqrt(sumSquares);
        float[] normalized = new float[vec.length];
        for (int i = 0; i < vec.length; i++) {
            normalized[i] = vec[i] / norm;
        }
        return normalized;
    }

    /**
     * 向量字面量：{@code "[1.23,4.56,...]"}。
     *
     * <p>不用占位符的原因：MySQL 驱动不支持 ARRAY 参数绑定、Doris 也只接受
     * 字面量。注入风险可控——{@code float} 序列化后只可能含 {@code [0-9eE+-.\s]}。</p>
     */
    public static String embeddingLiteral(float[] vec) {
        if (vec == null || vec.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(vec.length * 12);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(formatFloat32(vec[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * 浮点字面量形态：最短往返数字 + 去尾随 {@code .0} + 科学计数法
     * （{@code 1e+07}/{@code 1e-05}）+ {@code NaN}/{@code +Inf}/{@code -Inf}/{@code -0} 拼写。
     */
    static String formatFloat32(float v) {
        if (Float.isNaN(v)) {
            return "NaN";
        }
        if (v == Float.POSITIVE_INFINITY) {
            return "+Inf";
        }
        if (v == Float.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        if (v == 0f) {
            return Float.floatToRawIntBits(v) < 0 ? "-0" : "0";
        }
        String sign = v < 0 ? "-" : "";
        BigDecimal decimal = new BigDecimal(Float.toString(Math.abs(v)));
        String digits = decimal.unscaledValue().toString();
        int dp = digits.length() - decimal.scale();
        while (digits.length() > 1 && digits.endsWith("0")) {
            digits = digits.substring(0, digits.length() - 1);
        }
        int exp = dp - 1;
        if (exp < -4 || exp >= 6) {
            String mantissa = digits.length() == 1
                    ? digits
                    : digits.charAt(0) + "." + digits.substring(1);
            String expSign = exp < 0 ? "-" : "+";
            return sign + mantissa + "e" + expSign + String.format("%02d", Math.abs(exp));
        }
        if (dp <= 0) {
            return sign + "0." + "0".repeat(-dp) + digits;
        }
        if (dp >= digits.length()) {
            return sign + digits + "0".repeat(dp - digits.length());
        }
        return sign + digits.substring(0, dp) + "." + digits.substring(dp);
    }

    // ── 建表 DDL ────────────────────────────────────────────────────────────

    /**
     * 建表 DDL。关键点：DUPLICATE KEY(id)（兼容 Doris/SelectDB
     * 对 ANN 索引的表模型要求；按 id 替换语义由 delete+insert 实现）、INVERTED 索引覆盖
     * 过滤字段 + 中文分词全文索引、ANN 索引 HNSW + inner_product（legacy 为 cosine_distance）。
     *
     * <p>DDL 中 dimension/buckets/replication 是受控 int，拼接无注入风险。</p>
     */
    public static String buildCreateTableDdl(String tableName, int dimension, int buckets,
                                             int replication, DorisCompatMode mode) {
        String metricType = "inner_product";
        String keyMode = "DUPLICATE KEY(id)";
        String properties = "\t\"replication_num\"=\"" + replication + "\"";
        if (mode == DorisCompatMode.LEGACY) {
            metricType = "cosine_distance";
            keyMode = "UNIQUE KEY(id)";
            properties = "\t\"replication_num\"=\"" + replication + "\",\n"
                    + "\t\"enable_unique_key_merge_on_write\"=\"true\"";
        }
        String tpl = "CREATE TABLE IF NOT EXISTS `%s` (\n"
                + "    id                VARCHAR(64)  NOT NULL,\n"
                + "    chunk_id          VARCHAR(64),\n"
                + "    knowledge_id      VARCHAR(64),\n"
                + "    knowledge_base_id VARCHAR(64),\n"
                + "    source_id         VARCHAR(255),\n"
                + "    source_type       INT,\n"
                + "    tag_id            VARCHAR(64),\n"
                + "    is_enabled        BOOLEAN,\n"
                + "    content           TEXT,\n"
                + "    embedding         ARRAY<FLOAT> NOT NULL,\n"
                + "    INDEX idx_chunk    (chunk_id)          USING INVERTED,\n"
                + "    INDEX idx_kb       (knowledge_base_id) USING INVERTED,\n"
                + "    INDEX idx_kid      (knowledge_id)      USING INVERTED,\n"
                + "    INDEX idx_src      (source_id)         USING INVERTED,\n"
                + "    INDEX idx_tag      (tag_id)            USING INVERTED,\n"
                + "    INDEX idx_enabled  (is_enabled)        USING INVERTED,\n"
                + "    INDEX idx_content  (content)           USING INVERTED"
                + " PROPERTIES(\"parser\"=\"chinese\",\"support_phrase\"=\"true\"),\n"
                + "    INDEX idx_emb      (embedding)         USING ANN PROPERTIES(\n"
                + "        \"index_type\"=\"hnsw\",\n"
                + "\t\t\"metric_type\"=\"%s\",\n"
                + "        \"dim\"=\"%d\",\n"
                + "        \"max_degree\"=\"32\",\n"
                + "        \"ef_construction\"=\"200\"\n"
                + "    )\n"
                + ") ENGINE=OLAP\n"
                + "%s\n"
                + "DISTRIBUTED BY HASH(id) BUCKETS %d\n"
                + "PROPERTIES(\n"
                + "\t%s\n"
                + ");";
        return String.format(tpl, tableName, metricType, dimension, keyMode, buckets, properties);
    }

    // ── 存储估算与 SourceID 改写 ────────────────────────────────────────────

    /**
     * 存储估算：payload 字符串字节 + 向量 ({@code dim*4}) +
     * HNSW ({@code M*2*8}，M=32) + 元数据 24。
     */
    public static long calculateStorageSize(DorisVectorEmbedding emb) {
        long payload = 0;
        payload += utf8Length(emb.content);
        payload += utf8Length(emb.sourceId);
        payload += utf8Length(emb.chunkId);
        payload += utf8Length(emb.knowledgeId);
        payload += utf8Length(emb.knowledgeBaseId);
        payload += utf8Length(emb.tagId);
        payload += 8; // source_type int
        long vec = 0;
        long hnsw = 0;
        if (emb.embedding != null && emb.embedding.length > 0) {
            vec = emb.embedding.length * 4L;
            final long hnswM = 32;
            hnsw = hnswM * 2 * 8;
        }
        final long metaBytes = 24;
        return payload + vec + hnsw + metaBytes;
    }

    /** 按 UTF-8 字节数计长（非 UTF-16 长度）。 */
    static long utf8Length(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int bytes = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x80) {
                if (c < 0x800) {
                    bytes++;
                } else if (!Character.isSurrogate(c)) {
                    bytes += 2;
                }
            }
        }
        return bytes;
    }

    /**
     * SourceID 三态改写（与 Qdrant 实现同构）：
     * 普通 chunk（{@code SourceID == ChunkID}）→ {@code targetChunkID}；
     * 生成型问题（{@code SourceID == "<chunkID>-<questionID>"}）→ 替换前缀；
     * 其他场景 → 新 UUID（保持唯一性）。
     */
    public static String translateSourceId(String originalSourceId, String sourceChunkId,
                                           String targetChunkId) {
        String original = originalSourceId == null ? "" : originalSourceId;
        String srcChunk = sourceChunkId == null ? "" : sourceChunkId;
        if (original.equals(srcChunk)) {
            return targetChunkId;
        }
        if (original.startsWith(srcChunk + "-")) {
            String questionId = original.substring(srcChunk.length() + 1);
            return targetChunkId + "-" + questionId;
        }
        return UUID.randomUUID().toString();
    }
}
