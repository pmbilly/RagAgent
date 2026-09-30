package com.ragagent.agent.approval;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.ragagent.common.llm.ResponseType;

/**
 * 测试用事件总线（对照 Go {@code event.NewEventBus()} + {@code bus.On(...)}）。
 *
 * <p>与 Go 默认（同步）模式一致：handler 在 emit 的调用线程里顺序执行；
 * handler 抛异常会上抛，被 {@link Gate} 包装成 emit 失败。</p>
 */
class RecordingEventBus implements EventBus {

    private final Map<ResponseType, List<Consumer<Event>>> handlers = new ConcurrentHashMap<>();
    private final List<Event> emitted = new CopyOnWriteArrayList<>();

    /** 对照 Go {@code bus.On(type, handler)} */
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
