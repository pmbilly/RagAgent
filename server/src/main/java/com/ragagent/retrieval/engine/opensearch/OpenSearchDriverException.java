package com.ragagent.retrieval.engine.opensearch;

/**
 * OpenSearch 驱动的哨兵异常族——对照 Go
 * {@code repository/retriever/opensearch/errors.go} 的九个 sentinel error
 * + {@code repository.go} 的 {@code wrapTransport} 分类。
 *
 * <p>与 Go 的对应：</p>
 * <ul>
 *   <li>{@code ErrIndexNotFound} → {@link Kind#INDEX_NOT_FOUND}（alias/索引缺失——
 *       该 dim 尚未 Save 过；检索与按字段删除路径返回）</li>
 *   <li>{@code ErrDimensionMismatch} → {@link Kind#DIMENSION_MISMATCH}</li>
 *   <li>{@code ErrAuth} → {@link Kind#AUTH}（401/403——与 ErrTransport 分开，
 *       服务层可映射干净的 4xx 而非 503）</li>
 *   <li>{@code ErrTransport} → {@link Kind#TRANSPORT}（网络/5xx/不透明错误；
 *       瞬时——ensureReady 不持久化，下次调用重试）</li>
 *   <li>{@code ErrVersionUnsupported} → {@link Kind#VERSION_UNSUPPORTED}</li>
 *   <li>{@code ErrConfigInvalid} → {@link Kind#CONFIG_INVALID}</li>
 *   <li>{@code ErrBatchTooLarge} → {@link Kind#BATCH_TOO_LARGE}（与 FEATURE_NOT_ENABLED
 *       分开——服务层可分块重试而非当"等未来实现"）</li>
 *   <li>{@code ErrCircuitBreaker} → {@link Kind#CIRCUIT_BREAKER}（429 +
 *       knn_circuit_breaker_exception；瞬时）</li>
 * </ul>
 *
 * <p>{@code httpStatus}/{@code errorType} 携带原始 HTTP 状态与集群错误类型，
 * 供 {@code isNotFound}（404）与 {@code isAlreadyExists}（400 +
 * resource_already_exists_exception）等价判定。集群侧的 reason 文案<b>不进</b>
 * 异常 message（可能含内部索引名/分片号/文档片段——照 Go wrapTransport 的
 * 脱敏纪律），仅进 DEBUG 日志。</p>
 */
public final class OpenSearchDriverException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 哨兵分类（对照 errors.go 的九个 sentinel）。 */
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

    /** 对照 {@code isTransientErr}：TRANSPORT / CIRCUIT_BREAKER 可重试。 */
    public static boolean isTransient(OpenSearchDriverException e) {
        return e.kind == Kind.TRANSPORT || e.kind == Kind.CIRCUIT_BREAKER;
    }

    /** 对照 {@code isNotFound}：HTTP 404。 */
    public static boolean isNotFound(OpenSearchDriverException e) {
        return e.httpStatus == 404;
    }

    /** 对照 {@code isAlreadyExistsError}：400 + resource_already_exists_exception。 */
    public static boolean isAlreadyExists(OpenSearchDriverException e) {
        return e.httpStatus == 400
                && "resource_already_exists_exception".equals(e.errorType);
    }
}
