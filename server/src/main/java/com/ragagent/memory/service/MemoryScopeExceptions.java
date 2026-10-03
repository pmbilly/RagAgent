package com.ragagent.memory.service;

/**
 * service 层各判定结果对应的异常。
 *
 * <h2>为什么单独一个文件而不是一个类型一个文件</h2>
 * <p>这是一组"同一族的判定结果"：收在一个外壳类中让它们一眼看全。
 * 异常本身仍然是
 * 独立的 {@code public static} 类型，{@code catch} 与 {@code instanceof} 都不受影响。</p>
 *
 * <h2>⚠️ handler 层的映射（{@code MemoryController#fail}）</h2>
 * <pre>
 *   NoScope            → 401 "no principal in request"
 *   ItemNotFound       → 404 "memory not found"
 *   MemoryConflict     (domain 包) → 409 err.Error()
 *   SensitiveContent   → 400 err.Error()
 *   Disabled           → 400 "memory is disabled"
 *   其余（含 PreviouslyForgotten / EmptyContent）→ 500 + details
 * </pre>
 * <p>{@link PreviouslyForgotten} 与 {@link EmptyContent} <b>刻意不在</b> {@code fail()} 的
 * {@code switch} 里——它们与一切未列出的异常一样落 500。正常写路径上它们会被
 * 内部吞掉（见 {@code applyDecisions}），只有显式写入路径能让它们冒到 handler。</p>
 */
public final class MemoryScopeExceptions {

    private MemoryScopeExceptions() {}

    /** 请求里没有可归因的主体。 */
    public static class NoScope extends RuntimeException {
        public NoScope() {
            super("memory: no principal in context");
        }
    }

    /** 工作区或用户层把记忆关了。 */
    public static class Disabled extends RuntimeException {
        public Disabled() {
            super("memory: disabled for this scope");
        }
    }

    /**
     * id 不在调用者自己的记忆空间里。
     *
     * <p>作用域不匹配与真的不存在**刻意产生同一个错误**，这样一个 id 无法被用来跨用户探测存在性。</p>
     */
    public static class ItemNotFound extends RuntimeException {
        public ItemNotFound() {
            super("memory: item not found");
        }
    }

    /**
     * 这条陈述几乎全是凭据或身份号，
     * 脱敏之后就没什么值得记的了。
     */
    public static class SensitiveContent extends RuntimeException {
        public SensitiveContent() {
            super("memory: statement was sensitive material");
        }
    }

    /**
     * 这条陈述撞上了用户删过的某一条。
     * 写路径上的调用方把它当"无事可做"，而不是失败。
     */
    public static class PreviouslyForgotten extends RuntimeException {
        public PreviouslyForgotten() {
            super("memory: previously forgotten by the user");
        }
    }

    /** 清洗后为空的陈述。 */
    public static class EmptyContent extends RuntimeException {
        public EmptyContent() {
            super("memory: empty content");
        }
    }
}
