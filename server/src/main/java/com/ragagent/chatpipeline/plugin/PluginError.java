package com.ragagent.chatpipeline.plugin;

/**
 * 插件执行错误（对照 Go {@code chatpipeline.PluginError} 与包级预定义错误，chat_pipeline.go:80-140）。
 *
 * <h2>身份语义（实录钉住）</h2>
 * <p>Go 侧预定义错误是<b>包级单例指针</b>，管线多处用 {@code stageErr == ErrSearchNothing}
 * 做<b>指针比较</b>（progress.go 的 EndRetrievalProgress、search_parallel 的任务结果分派）。
 * Java 侧同样用<b>同一实例</b>（public static final 字段）+ 引用比较（{@code ==}）；
 * {@link #withError} 按 Go 的 {@code clone+WithError} 返回<b>新实例</b>，不污染单例。</p>
 *
 * <h2>Go 双通道折叠备案</h2>
 * <p>Go 的 {@code Err string}（原始 error）在 Java 侧是 {@code cause}（Throwable）；
 * 折叠语义同波 4.5a 备案——单返回通道。</p>
 */
public final class PluginError {

    /** 原始错误（Go 的 Err error；可为 null）。 */
    public final Throwable err;
    /** 人类可读描述（Go 的 Description）。 */
    public final String description;
    /** 错误类型标识（Go 的 ErrorType）。 */
    public final String errorType;

    public PluginError(Throwable err, String description, String errorType) {
        this.err = err;
        this.description = description == null ? "" : description;
        this.errorType = errorType == null ? "" : errorType;
    }

    // ----- 预定义错误（对照 chat_pipeline.go:88-125；恒同一实例） -----

    public static final PluginError SEARCH_NOTHING =
            new PluginError(null, "No relevant content found", "search_nothing");
    public static final PluginError SEARCH =
            new PluginError(null, "Failed to search knowledge base", "search_failed");
    public static final PluginError RERANK =
            new PluginError(null, "Reranking failed", "rerank_failed");
    public static final PluginError GET_RERANK_MODEL =
            new PluginError(null, "Failed to get rerank model", "get_rerank_model_failed");
    public static final PluginError GET_CHAT_MODEL =
            new PluginError(null, "Failed to get chat model", "get_chat_model_failed");
    public static final PluginError TEMPLATE_PARSE =
            new PluginError(null, "Failed to parse context template", "template_parse_failed");
    public static final PluginError TEMPLATE_EXECUTE =
            new PluginError(null, "Failed to generate search content", "template_execution_failed");
    public static final PluginError MODEL_CALL =
            new PluginError(null, "Failed to call model", "model_call_failed");
    public static final PluginError GET_HISTORY =
            new PluginError(null, "Failed to get conversation history", "get_history_failed");

    /** 对照 clone + WithError：附错误并返回<b>新实例</b>（单例不被改写）。 */
    public PluginError withError(Throwable cause) {
        return new PluginError(cause, this.description, this.errorType);
    }
}
