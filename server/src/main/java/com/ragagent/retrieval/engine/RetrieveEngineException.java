package com.ragagent.retrieval.engine;

/**
 * 检索引擎解析的哨兵异常族——对照 Go {@code internal/application/service/retriever/factory.go}
 * L17-42 的四个 sentinel error，外加 registry 里两处 {@code fmt.Errorf} 的分类位。
 *
 * <h2>照抄点（Go 注释原文的语义）</h2>
 * <ul>
 *   <li><b>面向用户的文案一律不含 store UUID</b>——Go 的哨兵"故意省略 store UUID 以防枚举泄漏"，
 *       租户/store 只进结构化日志。Java 侧同样如此（异常 message 与 Go 逐字一致）。</li>
 *   <li><b>NOT_FOUND / FORBIDDEN 是永久失败</b>（异步 worker 据此丢弃任务，对应 Go 的
 *       {@code asynq.SkipRetry}）；<b>UNAVAILABLE 是可重试失败</b>（元数据库不可达、后端
 *       暂时下线——把可重试的故障报成 not-found 就是"把一次性抖动变成永久丢单"）。</li>
 *   <li>TENANT_INFO_MISSING：同步无绑定路径需要 ctx 里的 TenantInfo 而不得。</li>
 * </ul>
 *
 * <h2>与 Go 的差异（备案）</h2>
 * <ul>
 *   <li>Go 用 {@code errors.Is} 做分类；Java 用 {@link Kind} 枚举沿 cause 链匹配
 *       （{@link #isKind}）——等价且对"带 store ID 文案的每调用新建异常"同样成立。</li>
 *   <li>Go 的 {@code context.Canceled / DeadlineExceeded} → Java 的
 *       {@code CancellationException / TimeoutException / InterruptedException}（沿 cause 链），
 *       与 {@code com.ragagent.im.runtime.ImFormat.isCanceledOrDeadline} 的既有约定一致。</li>
 * </ul>
 */
public class RetrieveEngineException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 哨兵分类（对照 Go 的四个 sentinel + registry 内部两处 fmt.Errorf）。 */
    public enum Kind {
        /** 对照 {@code ErrTenantInfoMissing}。 */
        TENANT_INFO_MISSING,
        /** 对照 {@code ErrVectorStoreNotFound}。 */
        VECTOR_STORE_NOT_FOUND,
        /** 对照 {@code ErrVectorStoreUnavailable}。 */
        VECTOR_STORE_UNAVAILABLE,
        /** 对照 {@code ErrVectorStoreForbidden}。 */
        VECTOR_STORE_FORBIDDEN,
        /** 对照 registry 的 {@code store %s not found in registry}（非哨兵，分类时并入 UNAVAILABLE）。 */
        STORE_NOT_REGISTERED,
        /** 对照 registry 的 {@code repository of type %s not found}。 */
        ENGINE_TYPE_NOT_REGISTERED,
        /** 对照 registry 的 {@code repository type %s already registered}。 */
        ENGINE_TYPE_ALREADY_REGISTERED,
    }

    private final Kind kind;

    public RetrieveEngineException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    // ── 哨兵单例（文案与 Go 逐字一致） ──────────────────────────────────────

    /** 对照 {@code ErrTenantInfoMissing}。 */
    public static final RetrieveEngineException TENANT_INFO_MISSING =
            new RetrieveEngineException(Kind.TENANT_INFO_MISSING, "tenant info not found in context");

    /** 对照 {@code ErrVectorStoreNotFound}。 */
    public static final RetrieveEngineException VECTOR_STORE_NOT_FOUND =
            new RetrieveEngineException(Kind.VECTOR_STORE_NOT_FOUND, "vector store not available");

    /** 对照 {@code ErrVectorStoreUnavailable}。 */
    public static final RetrieveEngineException VECTOR_STORE_UNAVAILABLE =
            new RetrieveEngineException(Kind.VECTOR_STORE_UNAVAILABLE,
                    "vector store engine unavailable");

    /** 对照 {@code ErrVectorStoreForbidden}。 */
    public static final RetrieveEngineException VECTOR_STORE_FORBIDDEN =
            new RetrieveEngineException(Kind.VECTOR_STORE_FORBIDDEN, "vector store access denied");

    // ── 分类助手 ────────────────────────────────────────────────────────────

    /** 对照 {@code errors.Is(err, sentinel)}（沿 cause 链按 kind 匹配）。 */
    public static boolean isKind(Throwable err, Kind kind) {
        for (Throwable c = err; c != null; c = c.getCause()) {
            if (c instanceof RetrieveEngineException e && e.kind == kind) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    /**
     * 对照 {@code isContextError}：调用方"放弃"（取消/超时）而非对 store 的判定。
     *
     * <p>与 Go 的分野同样重要：异步 worker 把 store 哨兵当永久失败而停止重试，
     * 因此取消/超时绝不能被报成 store 哨兵。</p>
     */
    public static boolean isCancellation(Throwable err) {
        for (Throwable c = err; c != null; c = c.getCause()) {
            if (c instanceof java.util.concurrent.CancellationException
                    || c instanceof java.util.concurrent.TimeoutException
                    || c instanceof InterruptedException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    /**
     * 分类后重抛（对照 Go 的 {@code return err} / {@code return classifyLookupError(err)}）。
     * 非受检异常在 Java 里就是错误通道，故直接抛。
     */
    public static RetrieveEngineException rethrow(Throwable err) {
        if (err instanceof RetrieveEngineException e) {
            return e;
        }
        if (err instanceof RuntimeException e) {
            throw e;
        }
        if (err instanceof Error e) {
            throw e;
        }
        return new RetrieveEngineException(Kind.VECTOR_STORE_UNAVAILABLE, String.valueOf(err));
    }
}
