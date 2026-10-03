package com.ragagent.chatpipeline.plugin;

/**
 * 插件执行错误。
 *
 * <h2>身份语义</h2>
 * <p>预定义错误是<b>单例</b>（public static final 字段），管线多处用
 * {@code stageErr == SEARCH_NOTHING} 做<b>引用比较</b>
 * （检索进度窗口关闭判定、并行检索任务的结果分派）。
 * {@link #withError} 返回<b>新实例</b>，不污染单例。</p>
 *
 * <h2>单通道错误</h2>
 * <p>底层错误统一放 {@code err}（Throwable）；失败不经双返回通道。</p>
 */
public final class PluginError {

    /** 底层错误（可为 null）。 */
    public final Throwable err;
    /** 人类可读描述。 */
    public final String description;
    /** 错误类型标识。 */
    public final String errorType;

    public PluginError(Throwable err, String description, String errorType) {
        this.err = err;
        this.description = description == null ? "" : description;
        this.errorType = errorType == null ? "" : errorType;
    }

    // ----- 预定义错误（恒同一实例） -----

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

    /** 附错误并返回<b>新实例</b>（单例不被改写）。 */
    public PluginError withError(Throwable cause) {
        return new PluginError(cause, this.description, this.errorType);
    }
}
