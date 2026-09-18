package com.ragagent.memory.service;

/**
 * service 层与 Go 哨兵错误逐条对应的异常
 * （对照 Go {@code internal/application/service/memory/} 的包级 {@code var} 与
 * {@code errors.New}）。
 *
 * <h2>为什么单独一个文件而不是一个类型一个文件</h2>
 * <p>Go 把它们定义在 {@code scope.go} / {@code service.go} 的包级 {@code var} 块里——
 * 一个地方一眼看全。Java 每个 {@code public} 类型都要自己的文件，会把这组"同一族的
 * 判定结果"摊到五个文件里，反而看不出它们是一组。故收在一个外壳类中；异常本身仍然是
 * 独立的 {@code public static} 类型，{@code catch} 与 {@code instanceof} 都不受影响。</p>
 *
 * <h2>⚠️ handler 层的映射（Go {@code handler/memory.go} 的 {@code fail()}）</h2>
 * <pre>
 *   NoScope            → 401 "no principal in request"
 *   ItemNotFound       → 404 "memory not found"
 *   MemoryConflict     (domain 包) → 409 err.Error()
 *   SensitiveContent   → 400 err.Error()
 *   Disabled           → 400 "memory is disabled"
 *   其余（含 PreviouslyForgotten / EmptyContent）→ 500 + details
 * </pre>
 * <p>{@link PreviouslyForgotten} 与 {@link EmptyContent} <b>刻意不在</b> {@code fail()} 的
 * {@code switch} 里——Go 里它们落到 {@code default} 分支返回 500。正常写路径上它们会被
 * 内部吞掉（见 {@code applyDecisions}），只有显式写入路径能让它们冒到 handler。</p>
 */
public final class MemoryScopeExceptions {

    private MemoryScopeExceptions() {}

    /** 对照 Go {@code ErrNoMemoryScope}：请求里没有可归因的主体。 */
    public static class NoScope extends RuntimeException {
        public NoScope() {
            super("memory: no principal in context");
        }
    }

    /** 对照 Go {@code ErrMemoryDisabled}：工作区或用户层把记忆关了。 */
    public static class Disabled extends RuntimeException {
        public Disabled() {
            super("memory: disabled for this scope");
        }
    }

    /**
     * 对照 Go {@code ErrItemNotFound}：id 不在调用者自己的记忆空间里。
     *
     * <p>作用域不匹配与真的不存在**刻意产生同一个错误**，这样一个 id 无法被用来跨用户探测存在性。</p>
     */
    public static class ItemNotFound extends RuntimeException {
        public ItemNotFound() {
            super("memory: item not found");
        }
    }

    /**
     * 对照 Go {@code ErrSensitiveContent}：这条陈述几乎全是凭据或身份号，
     * 脱敏之后就没什么值得记的了。
     */
    public static class SensitiveContent extends RuntimeException {
        public SensitiveContent() {
            super("memory: statement was sensitive material");
        }
    }

    /**
     * 对照 Go {@code ErrPreviouslyForgotten}：这条陈述撞上了用户删过的某一条。
     * 写路径上的调用方把它当"无事可做"，而不是失败。
     */
    public static class PreviouslyForgotten extends RuntimeException {
        public PreviouslyForgotten() {
            super("memory: previously forgotten by the user");
        }
    }

    /** 对照 Go 的 {@code errors.New("memory: empty content")}（清洗后为空）。 */
    public static class EmptyContent extends RuntimeException {
        public EmptyContent() {
            super("memory: empty content");
        }
    }
}
