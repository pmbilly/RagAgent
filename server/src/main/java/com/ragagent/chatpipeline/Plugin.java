package com.ragagent.chatpipeline;

/**
 * chat 管线插件接口（对照 Go {@code chatpipeline.Plugin}，chat_pipeline.go:11-21）。
 *
 * <p>插件处理特定事件；{@code next} 是插件链的下一环——Go 用闭包表达，Java 用
 * {@link Chain} 函数式接口表达。返回 {@code null} 表示无错误（Go 的 nil {@code *PluginError}）。</p>
 */
public interface Plugin {

    /** 处理事件（对照 OnEvent）。返回 null = 无错误。 */
    PluginError onEvent(String eventType, ChatManage chatManage, Chain next);

    /** 本插件响应的事件类型列表（对照 ActivationEvents）。 */
    String[] activationEvents();

    /** 插件链的下一环（对照 Go 的 {@code next func() *PluginError}）。 */
    @FunctionalInterface
    interface Chain {
        PluginError next();
    }
}
