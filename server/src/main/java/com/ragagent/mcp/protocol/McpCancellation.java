package com.ragagent.mcp.protocol;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消信号（对照 Go {@code context.WithCancel} 的 Done()/Err() 语义子集）。
 *
 * <p>为什么需要它（而不是简单地用 JDK 的 Future.cancel）：Go 侧 MCP 客户端把 ctx 的取消
 * 当作<b>连接生命周期</b>用——manager 的 {@code CloseClient}/{@code Shutdown} 取消 pending
 * 连接的 lifeCtx，让"正在建连的 goroutine"带着错误退出（manager.go:161-183）。Java 里没有
 * 等价物，故用本类显式建模：</p>
 *
 * <ul>
 *   <li>{@link #cancel()} = {@code cancelFunc()}；</li>
 *   <li>{@link #isCancelled()} = {@code ctx.Err() != nil}；</li>
 *   <li>{@link #future()} 供 select 式等待（{@code select { case &lt;-ctx.Done(): ... }}）；</li>
 *   <li>{@link #onCancel(Runnable)} 供"取消时中断阻塞中的 HTTP 调用"用
 *       （Go 侧 ctx 取消会直接让 {@code http.Client.Do} 返回，Java 的阻塞式
 *       {@code HttpClient.send} 只能靠线程中断 —— 见 {@code McpClientManager#connectClient}）。</li>
 * </ul>
 *
 * <p>父子关系对照 {@code context.WithCancel(parent)}：父取消 ⇒ 子取消，子取消不影响父。
 * 子从父的回调表里显式摘除，避免长时间运行的服务反复建连导致回调表无限增长。</p>
 */
public final class McpCancellation {

    /** 永不被取消的实例（对照 Go 的 {@code context.Background()}）。 */
    public static final McpCancellation NEVER = new McpCancellation(null);

    private final Set<McpCancellation> children = ConcurrentHashMap.newKeySet();
    private final Set<Runnable> listeners = ConcurrentHashMap.newKeySet();
    private final CompletableFuture<Void> signal = new CompletableFuture<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final McpCancellation parent;

    /** 对照 {@code context.Background()}：根 token，不可取消（除非显式 cancel）。 */
    public McpCancellation() {
        this(null);
    }

    private McpCancellation(McpCancellation parent) {
        this.parent = parent;
        if (parent != null && !parent.equals(NEVER)) {
            parent.children.add(this);
            parent.onCancel(() -> {
                parent.children.remove(this);
                this.cancel();
            });
        }
    }

    /** 对照 {@code context.WithCancel(parent)}：父取消 ⇒ 子取消。 */
    public McpCancellation child() {
        return new McpCancellation(this);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 对照 Go 的 cancelFunc：幂等。 */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        if (parent != null) {
            parent.children.remove(this);
        }
        signal.complete(null);
        for (Runnable listener : listeners) {
            runQuietly(listener);
        }
        listeners.clear();
        for (McpCancellation child : new ArrayList<>(children)) {
            child.cancel();
        }
        children.clear();
    }

    /** select 式等待用的信号（对照 {@code ctx.Done()}）；取消时正常完成，用 {@link #isCancelled()} 判定。 */
    public CompletableFuture<Void> future() {
        return signal;
    }

    /** 注册取消回调；若已取消则立即执行（对照 {@code select { case &lt;-ctx.Done(): }} 的"已关闭也能立即返回"）。 */
    public void onCancel(Runnable listener) {
        if (cancelled.get()) {
            runQuietly(listener);
            return;
        }
        listeners.add(listener);
        if (cancelled.get() && listeners.remove(listener)) {
            runQuietly(listener);
        }
    }

    /** 取消时中断 {@code thread}（让阻塞中的 {@code HttpClient.send} 抛 InterruptedException）。 */
    public void onCancelInterrupt(Thread thread) {
        onCancel(thread::interrupt);
    }

    /** 对照 Go {@code ctx.Err()}：已取消则抛异常。 */
    public void throwIfCancelled() {
        if (isCancelled()) {
            // Go 侧文案是 context.Canceled.Error() == "context canceled"（美式拼写，一个 l）。
            throw new McpException(McpErrorCode.CONNECTION_CLOSED, "context canceled");
        }
    }

    private static void runQuietly(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException ignored) {
            // 取消回调不允许把取消流程带崩
        }
    }
}
