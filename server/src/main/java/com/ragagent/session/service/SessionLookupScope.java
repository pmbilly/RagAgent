package com.ragagent.session.service;

/**
 * 「本请求按会话**属主租户**而非当前主体查会话」的请求级标记。
 *
 * <p>由共享 agent 流水线在解析出会话属主租户之后打标，
 * {@code sessionUserIDForLookup} 见到它就返回空 owner——
 * 也就是**跳过 user 范围**做内部查询（否则共享 agent 场景下会查不到本该能读的会话）。</p>
 *
 * <p><b>接线位置</b>：标记在 {@code KnowledgeQA} 入口打上，并沿整条 QA 流传播。Java 侧对应：
 * {@code KnowledgeQaController} 的 QA 执行线程在 {@code requestTenant.replay()} 后
 * {@link #mark()}，两条派生线程（消息索引、follow-up 建议）与状态持久化线程各自
 * {@code mark()}，四处都在 {@code TenantContext.clear()} 旁 {@link #clear()}——
 * 与其它线程上下文同一条纪律（设置方负责在收尾清理）。</p>
 */
public final class SessionLookupScope {

    private static final ThreadLocal<Boolean> SHARED_AGENT = new ThreadLocal<>();

    private SessionLookupScope() {
    }

    /** 打标：本请求改按会话属主租户查会话。 */
    public static void mark() {
        SHARED_AGENT.set(Boolean.TRUE);
    }

    /** 是否已打标。 */
    public static boolean isMarked() {
        return Boolean.TRUE.equals(SHARED_AGENT.get());
    }

    /** 与其它 ThreadLocal 上下文一样，设置方必须在请求结束时清理。 */
    public static void clear() {
        SHARED_AGENT.remove();
    }
}
