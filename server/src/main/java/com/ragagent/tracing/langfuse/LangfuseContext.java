package com.ragagent.tracing.langfuse;

import java.util.ArrayDeque;

/**
 * 线程内的 langfuse 观测上下文（架构差异的落点）：
 * Go 把观测上下文放在 {@code ctx} 里逐层传递；Java 侧调用点没有 ctx 参数，
 * 故在本包立一个<b>线程内的帧栈</b>——{@code start*} 入栈、{@code finish} 出栈，
 * 子观测取栈顶帧作为父（对照 OTel 的 {@code trace.SpanFromContext}）。
 *
 * <p><b>纪律（约定 §5）</b>：跨线程边界必须显式 {@link Snapshot#capture() capture} /
 * {@link Snapshot#replay() replay}（再 {@link #clear()}），禁止隐式继承——
 * 与 TenantContext 同款。未 replay 的工作线程 = Go 侧 ctx 未携带 trace，
 * 子树会成为自动根（autoTrace 语义），不影响业务正确性。</p>
 */
final class LangfuseContext {

    /** 一帧 = (traceId, spanId)；栈顶为当前 span。 */
    record Frame(String traceIdHex, String spanIdHex) {
    }

    private static final ThreadLocal<ArrayDeque<Frame>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    /** 对照 Go 的 traceFromCtx 命中（是否已存在活跃 trace）。 */
    private static final ThreadLocal<Boolean> TRACE_PRESENT = ThreadLocal.withInitial(() -> false);

    private LangfuseContext() {
    }

    static void push(Frame frame) {
        STACK.get().push(frame);
    }

    /** 出栈（乱序 finish 安全：按值移除）。 */
    static void pop(Frame frame) {
        STACK.get().remove(frame);
    }

    /** 栈顶帧（当前 span）；空 → null。 */
    static Frame current() {
        return STACK.get().peek();
    }

    static boolean hasTrace() {
        return Boolean.TRUE.equals(TRACE_PRESENT.get());
    }

    static void markTrace() {
        TRACE_PRESENT.set(true);
    }

    /** 清空本线程上下文（线程池/虚拟线程收尾必调，防止污染下一个任务）。 */
    static void clear() {
        STACK.remove();
        TRACE_PRESENT.remove();
    }

    /** 跨线程快照（§5）：发射线程 capture，工作线程 replay，finally clear。 */
    record Snapshot(String traceIdHex, String spanIdHex, boolean tracePresent) {

        static Snapshot capture() {
            Frame f = current();
            return new Snapshot(f == null ? null : f.traceIdHex(),
                    f == null ? null : f.spanIdHex(), hasTrace());
        }

        void replay() {
            if (tracePresent) {
                markTrace();
            }
            if (traceIdHex != null && spanIdHex != null) {
                push(new Frame(traceIdHex, spanIdHex));
            }
        }
    }
}
