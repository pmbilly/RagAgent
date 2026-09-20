package com.ragagent.embedding;

/**
 * 对照 Go {@code types.EmbedQueryContextKey}（const.go L55-56）：ctx 里标记
 * 「本次 embedding 输入是查询侧而非文档侧」，NVIDIA embedder 用它切换
 * {@code input_type}=query/passage。
 *
 * <p>Java 无 ctx 对象，ThreadLocal 承载（同 {@code llm.limiter.BackgroundTaskContext}
 * 的先例；虚拟线程内独立、不跨线程传递）。</p>
 */
public final class EmbedQueryContext {

    private static final ThreadLocal<Boolean> QUERY = new ThreadLocal<>();

    private EmbedQueryContext() {
    }

    /** 对照 Go：ctx 无值时 isQuery=false。 */
    public static boolean isQuery() {
        return Boolean.TRUE.equals(QUERY.get());
    }

    public static Scope markQuery() {
        Boolean previous = QUERY.get();
        QUERY.set(Boolean.TRUE);
        return () -> {
            if (previous == null) {
                QUERY.remove();
            } else {
                QUERY.set(previous);
            }
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
