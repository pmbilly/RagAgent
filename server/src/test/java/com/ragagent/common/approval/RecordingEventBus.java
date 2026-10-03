package com.ragagent.common.approval;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.ragagent.common.llm.ResponseType;

/**
 * 测试用事件总线（额外记录已 emit 的事件供断言）。
 *
 * <p>同步语义：handler 在 emit 的调用线程里顺序执行；
 * handler 抛异常会上抛，被 {@link Gate} 包装成 emit 失败。</p>
 */
class RecordingEventBus implements EventBus {

    private final Map<ResponseType, List<Consumer<Event>>> handlers = new ConcurrentHashMap<>();
    private final List<Event> emitted = new CopyOnWriteArrayList<>();

    /** 注册 handler（同类型可多个，顺序执行） */
    RecordingEventBus on(ResponseType type, Consumer<Event> handler) {
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    @Override
    public void emit(Event event) {
        emitted.add(event);
        for (Consumer<Event> handler : handlers.getOrDefault(event.type(), List.of())) {
            handler.accept(event);
        }
    }

    List<Event> emitted() {
        return emitted;
    }
}
