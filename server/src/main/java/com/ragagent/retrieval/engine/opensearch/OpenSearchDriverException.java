package com.ragagent.retrieval.engine.opensearch;

/**
 * OpenSearch 驱动的哨兵异常族。
 *
 * <ul>
 *   <li>{@link Kind#INDEX_NOT_FOUND}：alias/索引缺失——
 *       该 dim 尚未 Save 过；检索与按字段删除路径返回</li>
 *   <li>{@link Kind#DIMENSION_MISMATCH}：向量维度不一致</li>
 *   <li>{@link Kind#AUTH}：401/403——与 TRANSPORT 分开，
 *       服务层可映射干净的 4xx 而非 503</li>
 *   <li>{@link Kind#TRANSPORT}：网络/5xx/不透明错误；
 *       瞬时——ensureReady 不持久化，下次调用重试</li>
 *   <li>{@link Kind#VERSION_UNSUPPORTED}：集群版本不受支持</li>
 *   <li>{@link Kind#CONFIG_INVALID}：配置非法</li>
 *   <li>{@link Kind#BATCH_TOO_LARGE}：批量超限——与 FEATURE_NOT_ENABLED
 *       分开，服务层可分块重试而非当"等未来实现"</li>
 *   <li>{@link Kind#CIRCUIT_BREAKER}：429 +
 *       knn_circuit_breaker_exception；瞬时</li>
 *   <li>{@link Kind#FEATURE_NOT_ENABLED}：依赖的集群特性未启用</li>
 * </ul>
 *
 * <p>{@code httpStatus}/{@code errorType} 携带原始 HTTP 状态与集群错误类型，
 * 供 {@code isNotFound}（404）与 {@code isAlreadyExists}（400 +
 * resource_already_exists_exception）判定。集群侧的 reason 文案<b>不进</b>
 * 异常 message（可能含内部索引名/分片号/文档片段——脱敏纪律），仅进 DEBUG 日志。</p>
 */
public final class OpenSearchDriverException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 哨兵分类。 */
    public enum Kind {
        INDEX_NOT_FOUND, DIMENSION_MISMATCH, AUTH, TRANSPORT,
        VERSION_UNSUPPORTED, CONFIG_INVALID, BATCH_TOO_LARGE, CIRCUIT_BREAKER,
        FEATURE_NOT_ENABLED
    }

    private final Kind kind;
    private final int httpStatus;
    private final String errorType;

    public OpenSearchDriverException(Kind kind, String message) {
        this(kind, message, 0, null);
    }

    public OpenSearchDriverException(Kind kind, String message, int httpStatus, String errorType) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.errorType = errorType;
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String errorType() {
        return errorType;
    }

    /** 可重试判定：TRANSPORT / CIRCUIT_BREAKER。 */
    public static boolean isTransient(OpenSearchDriverException e) {
        return e.kind == Kind.TRANSPORT || e.kind == Kind.CIRCUIT_BREAKER;
    }

    /** 索引缺失判定：HTTP 404。 */
    public static boolean isNotFound(OpenSearchDriverException e) {
        return e.httpStatus == 404;
    }

    /** 索引已存在判定：400 + resource_already_exists_exception。 */
    public static boolean isAlreadyExists(OpenSearchDriverException e) {
        return e.httpStatus == 400
                && "resource_already_exists_exception".equals(e.errorType);
    }
}
