package com.ragagent.wiki.service;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 合并进程内<b>字节完全相同</b>的并发请求（对照 Go 的
 * {@code golang.org/x/sync/singleflight.Group}，见 wiki_ingest.go 的
 * {@code llmRequests singleflight.Group}，L391）。
 *
 * <p><b>解决什么问题</b>：wiki ingest 的一个 reduce 批次会并发地对同一份
 * {@code WikiPageModifyUserPrompt} 发起多个 LLM 请求（每个页面一个），而它们的
 * 消息体可能逐字节相同（同一页面、同一批新信息被两条路径同时更新）。并发相同的
 * 请求打到 provider 是纯粹的浪费与限流风险，合并成一次即可。</p>
 *
 * <h2>与 Go 的语义对应</h2>
 * <ul>
 *   <li>Go 的 {@code Group.DoChan(key, fn)} 返回一个 channel，调用方
 *       {@code select} 在 {@code ctx.Done()} 与结果之间二选一。
 *       Java 的 {@link CompletableFuture} 天然同时承载「值」与「异常」，
 *       正好对应 Go {@code singleflight.Result{Val, Err}} 的两个字段——
 *       失败以 {@code completeExceptionally} 表达，而不是一个"装着错误的值"。</li>
 *   <li><b>fn 在独立线程上执行</b>：Go 的 DoChan 立刻返回、fn 跑在新建的 goroutine 里，
 *       所以率先发起调用的那个调用方<b>不会</b>因为 fn 阻塞而失去取消能力。
 *       Java 侧同样把 fn 提交到虚拟线程执行器上，而不是在 leader 线程里内联跑完
 *       ——否则 leader 被阻塞时，跟随者拿到的其实是一个"被自己的调用栈卡住"的 future。</li>
 *   <li><b>只在飞行期间合并</b>：完成后立刻从表里摘除，因此"上一次刚结束、新的
 *       相同请求马上到来"会真实地再执行一次（与 Go 一致——singleflight 不是缓存）。</li>
 * </ul>
 *
 * <p><b>⚠️ 多实例差异</b>：合并范围只有<b>单个 JVM</b>。Go 侧同样是进程内合并
 * （singleflight 从来不是分布式的），因此这一条与 Go <b>完全一致</b>，
 * 不构成部署约束。</p>
 */
public final class SingleFlight {

    /**
     * 飞行中的调用表。key → 该次调用的结果 future。
     *
     * <p>用 {@code remove(key, future)} 的两参版删除：只有当表里仍是<b>我们那一个</b>
     * future 时才移除，避免"A 的收尾删掉了 B 刚放进去的条目"。
     * （本实现里同一个 key 不会同时有两次执行——leader 会在完成时才移除，
     * 但两参版把这个不变式写死在代码里，不依赖调用顺序。）</p>
     */
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    /**
     * fn 的执行器。虚拟线程：一个 reduce 批次可能同时飞出几十个 LLM 调用，
     * 每个都在等网络，平台线程池会被轻易打满。
     *
     * <p>执行器生命周期与应用一致（本类以单例 bean 形式装配），不需要显式关闭。</p>
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 对照 Go {@code Group.DoChan}：若 key 已有在飞行的调用，直接返回它的 future；
     * 否则以本调用为 leader，把 fn 提交到虚拟线程执行并在完成后发布结果。
     *
     * <p>返回的 future 已完成时（或失败时）同样可用——调用方只需
     * {@code future.get()} / {@code join()}。</p>
     */
    public CompletableFuture<Object> doChan(String key, Callable<Object> fn) {
        CompletableFuture<Object> candidate = new CompletableFuture<>();
        CompletableFuture<Object> existing = inflight.putIfAbsent(key, candidate);
        if (existing != null) {
            // 跟随者：共享 leader 的结果（含异常）
            return existing;
        }

        // leader：fn 在独立虚拟线程上跑，leader 自身随后也只是"另一个等待者"
        CompletableFuture<Object> result = CompletableFuture.supplyAsync(() -> {
            try {
                return fn.call();
            } catch (Throwable t) {
                throw new java.util.concurrent.CompletionException(t);
            }
        }, executor);
        result.whenComplete((value, error) -> {
            if (error == null) {
                candidate.complete(value);
            } else {
                candidate.completeExceptionally(unwrap(error));
            }
            inflight.remove(key, candidate);
        });
        return candidate;
    }

    /**
     * 对照 Go 的 {@code (interface{}, error)} 返回：把 future 的值取出来。
     *
     * <p>失败时抛出<b>原始的</b>受检异常（而非包一层 {@code ExecutionException}），
     * 让调用方拿到与 Go {@code result.Err} 等价的错误对象。</p>
     */
    public static Object await(CompletableFuture<Object> future) throws Exception {
        try {
            return future.get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** 当前在飞行的调用数（测试/可观测用） */
    public int inflightCount() {
        return inflight.size();
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }
}
