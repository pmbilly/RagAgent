package com.ragagent.agent.tools;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 同一 sandbox 文件的写串行化 + 全局变更纪元（对照 Go {@code file_mutation_queue.go}，逐字移植）。
 *
 * <p><b>为什么按路径串行</b>：append 模式的 write_sandbox_file 与 edit_sandbox_file 都是对
 * 远端文件系统的读-改-写，远端没有原子原语。模型在一个响应里发多个工具调用时引擎可能并发执行，
 * 两个调用碰同一路径就会读到同样字节、第二次写悄悄丢掉第一次——症状是"模型自己的输出消失"，
 * 从日志无法证伪。按路径串行使无关文件仍然并发；全局锁也正确，但会把并行工具调用
 * 最有用的场景抹掉。</p>
 *
 * <p><b>纪元计数器</b>：read_file 会把下载的文件缓存来翻页，这个计数器让缓存感知写入。
 * 只看 Stat 不够——等长替换（改个数字）size 不变，部分后端 mtime 精度 1 秒，同一秒内
 * "编辑后再读"会误判未变；计数器不会漏。它按进程全局而非按路径，过度失效
 * （写文件 B 丢掉缓存里的文件 A）只多一次重下载，换来一个免维护、不会增长的计数器。</p>
 */
public final class FileMutationQueue {

    /** 对照 sandboxFileMutationEpoch：进程内已完成的 sandbox 文件写总数。 */
    private static final AtomicLong SANDBOX_FILE_MUTATION_EPOCH = new AtomicLong();

    /** 对照包级 sandboxFileMutations 单例。 */
    private static final FileMutationQueue SANDBOX_FILE_MUTATIONS = new FileMutationQueue();

    private final Object mu = new Object();
    private final Map<String, Entry> locks = new HashMap<>();

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        /** 让条目在还有调用者排队时保持存活，map 不会积累"进程里写过的每个文件"一把锁。 */
        int refs;
    }

    /** 当前纪元值（缓存记录后用于比对）。 */
    public static long sandboxMutationEpoch() {
        return SANDBOX_FILE_MUTATION_EPOCH.get();
    }

    /** 使 read_file 的 workspace 下载缓存失效；shell 命令没有路径清单，任何启动的命令都算写。 */
    public static void noteSandboxMutation() {
        SANDBOX_FILE_MUTATION_EPOCH.incrementAndGet();
    }

    /** 阻塞直到该 key 空闲；返回 release 函数（Go 的 func() 返回值）。 */
    public Runnable lockFile(String key) {
        Entry entry;
        synchronized (mu) {
            entry = locks.computeIfAbsent(key, k -> new Entry());
            entry.refs++;
        }

        entry.lock.lock();

        return () -> {
            entry.lock.unlock();
            synchronized (mu) {
                entry.refs--;
                if (entry.refs == 0) {
                    locks.remove(key);
                }
            }
        };
    }

    /**
     * 串行化一个会话内对一个文件的写（对照 lockSandboxFile）。路径到达此处时已 clean 且绝对。
     * release 时记账"一次写完成"——使读缓存失效的正是这一步。
     */
    public static Runnable lockSandboxFile(String sessionId, String filePath) {
        Runnable release = SANDBOX_FILE_MUTATIONS.lockFile(sessionId + "\0" + filePath);
        return () -> {
            noteSandboxMutation();
            release.run();
        };
    }

    /** 测试探针：锁表是否已空（对照 Go 测试直接查 queue.locks）。 */
    boolean isEmptyForTest() {
        synchronized (mu) {
            return locks.isEmpty();
        }
    }
}
