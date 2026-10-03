package com.ragagent.retrieval.engine;

/**
 * 检索引擎解析的哨兵异常族——四个哨兵分类（租户信息缺失 / store 不可得 / 不可用 / 禁访），
 * 外加注册表内部错误的分类位。
 *
 * <h2>行为契约</h2>
 * <ul>
 *   <li><b>面向用户的文案一律不含 store UUID</b>（防枚举泄漏），租户/store 只进结构化日志。</li>
 *   <li><b>NOT_FOUND / FORBIDDEN 是永久失败</b>（异步 worker 据此丢弃任务、不再重试）；
 *       <b>UNAVAILABLE 是可重试失败</b>（元数据库不可达、后端暂时下线——把可重试的故障报成
 *       not-found 就是"把一次性抖动变成永久丢单"）。</li>
 *   <li>TENANT_INFO_MISSING：同步无绑定路径需要租户上下文而不得。</li>
 * </ul>
 *
 * <h2>实现说明</h2>
 * <ul>
 *   <li>分类用 {@link Kind} 枚举沿 cause 链匹配（{@link #isKind}）——对"带 store ID 文案的
 *       每调用新建异常"同样成立。</li>
 *   <li>取消/超时对应
 *       {@code CancellationException / TimeoutException / InterruptedException}（沿 cause 链），
 *       与 {@code com.ragagent.im.runtime.ImFormat.isCanceledOrDeadline} 的既有约定一致。</li>
 * </ul>
 */
public class RetrieveEngineException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 哨兵分类（四个 store 哨兵 + 注册表内部错误）。 */
    public enum Kind {
        /** 上下文缺租户信息。 */
        TENANT_INFO_MISSING,
        /** store 不可得（永久失败）。 */
        VECTOR_STORE_NOT_FOUND,
        /** store 暂不可用（可重试）。 */
        VECTOR_STORE_UNAVAILABLE,
        /** store 禁访（永久失败）。 */
        VECTOR_STORE_FORBIDDEN,
        /** store 未在注册表（非哨兵，分类时并入 UNAVAILABLE）。 */
        STORE_NOT_REGISTERED,
        /** 引擎类型未注册仓库。 */
        ENGINE_TYPE_NOT_REGISTERED,
        /** 引擎类型重复注册。 */
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

    // ── 哨兵单例 ────────────────────────────────────────────────────────────

    /** 租户上下文缺失。 */
    public static final RetrieveEngineException TENANT_INFO_MISSING =
            new RetrieveEngineException(Kind.TENANT_INFO_MISSING, "tenant info not found in context");

    /** store 不可得。 */
    public static final RetrieveEngineException VECTOR_STORE_NOT_FOUND =
            new RetrieveEngineException(Kind.VECTOR_STORE_NOT_FOUND, "vector store not available");

    /** store 暂不可用。 */
    public static final RetrieveEngineException VECTOR_STORE_UNAVAILABLE =
            new RetrieveEngineException(Kind.VECTOR_STORE_UNAVAILABLE,
                    "vector store engine unavailable");

    /** store 禁访。 */
    public static final RetrieveEngineException VECTOR_STORE_FORBIDDEN =
            new RetrieveEngineException(Kind.VECTOR_STORE_FORBIDDEN, "vector store access denied");

    // ── 分类助手 ────────────────────────────────────────────────────────────

    /** 沿 cause 链按 kind 匹配。 */
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
     * 调用方"放弃"（取消/超时）而非对 store 的判定。
     *
     * <p>异步 worker 把 store 哨兵当永久失败而停止重试，
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
     * 已是本类型/运行时异常/Error → 原样抛；其余折成 UNAVAILABLE 哨兵。
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
