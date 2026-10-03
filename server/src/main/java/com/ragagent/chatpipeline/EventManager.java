package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.ragagent.chatpipeline.plugin.Plugin;
import com.ragagent.chatpipeline.plugin.PluginError;

/**
 * 插件注册表与事件分发器。
 *
 * <p>Register 按 ActivationEvents 把插件挂到对应事件；每个事件的处理链按<b>注册序</b>执行
 * （buildHandler 从后往前包闭包，先注册的在外层先执行，后注册的插件只影响 next 链头）。
 * Trigger 无 handler 时返回 null。</p>
 */
public final class EventManager {

    /** 带参处理链（不含管线上下文参数）。 */
    @FunctionalInterface
    interface HandlerNode {
        PluginError invoke(String eventType, ChatManage chatManage);
    }

    /** Map&lt;eventType, List&lt;Plugin&gtgt;：注册序。 */
    private Map<String, List<Plugin>> listeners;
    /** Map&lt;eventType, handler&gt;：已构建的处理链。 */
    private Map<String, HandlerNode> handlers;

    public EventManager() {
        this.listeners = new HashMap<>();
        this.handlers = new HashMap<>();
    }

    /** 注册插件并重建其每个事件的处理链。 */
    public synchronized void register(Plugin plugin) {
        if (listeners == null) {
            listeners = new HashMap<>();
        }
        if (handlers == null) {
            handlers = new HashMap<>();
        }
        for (String eventType : plugin.activationEvents()) {
            listeners.computeIfAbsent(eventType, k -> new ArrayList<>()).add(plugin);
            handlers.put(eventType, buildHandler(listeners.get(eventType)));
        }
    }

    /** 构建给定插件列表的处理链（从最后一个插件往前包）。 */
    private HandlerNode buildHandler(List<Plugin> plugins) {
        HandlerNode next = (eventType, chatManage) -> null;
        for (int i = plugins.size() - 1; i >= 0; i--) {
            final Plugin current = plugins.get(i);
            final HandlerNode prevNext = next;
            next = (eventType, chatManage) -> current.onEvent(eventType, chatManage,
                    () -> prevNext.invoke(eventType, chatManage));
        }
        return next;
    }

    /** 触发事件。无 handler 返回 null。 */
    public PluginError trigger(String eventType, ChatManage chatManage) {
        HandlerNode handler = handlers == null ? null : handlers.get(eventType);
        if (handler != null) {
            return handler.invoke(eventType, chatManage);
        }
        return null;
    }
}
