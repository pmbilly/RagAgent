package com.ragagent.session.service;

/**
 * 「本请求按会话**属主租户**而非当前主体查会话」的请求级标记（对照 Go 的
 * {@code types.SessionTenantIDContextKey}，internal/types/const.go:35）。
 *
 * <p>Go 侧由共享 agent 流水线在解析出会话属主租户之后写入 ctx，
 * {@code sessionUserIDForLookup} 见到它就返回空 owner——
 * 也就是**跳过 user 范围**做内部查询（否则共享 agent 场景下会查不到本该能读的会话）。</p>
 *
 * <p><b>接线状态：未接线</b>。共享 agent 是阶段 7 的机制，本类只把 Go 的那条分支
 * 落到一个明确的落点上（与 {@code StorageUrlContext} 的处置一致），
 * 现在没有任何生产代码调用 {@link #mark()}——于是分支恒为
 * {@code false}，查询**始终带 user 范围**。</p>
 *
 * <p>这造成的行为差异是<b>偏保守</b>的：Go 在标记存在时放行的查询，Java 侧现在会返回
 * 404。属于安全的默认，不是漏洞；阶段 7 接上共享 agent 时调 {@link #mark()} 即可对齐。</p>
 */
public final class SessionLookupScope {

    private static final ThreadLocal<Boolean> SHARED_AGENT = new ThreadLocal<>();

    private SessionLookupScope() {
    }

    /** 对照 Go 把 {@code SessionTenantIDContextKey} 写进 ctx。 */
    public static void mark() {
        SHARED_AGENT.set(Boolean.TRUE);
    }

    /** 对照 Go 的 {@code ctx.Value(SessionTenantIDContextKey) != nil}。 */
    public static boolean isMarked() {
        return Boolean.TRUE.equals(SHARED_AGENT.get());
    }

    /** 与其它 ThreadLocal 上下文一样，设置方必须在请求结束时清理。 */
    public static void clear() {
        SHARED_AGENT.remove();
    }
}
