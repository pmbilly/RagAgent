package com.ragagent.wiki.service.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import com.ragagent.common.context.TenantContext;
import com.ragagent.knowledge.domain.KnowledgeProcessingSpan;
import com.ragagent.knowledge.service.SpanTracker;
import com.ragagent.common.wiki.WikiLanguageSupport;

/**
 * 批次执行用到的零散工具：错误分类、正文清洗、有界并发扇出、span 门面。
 */
public final class WikiBatchSupport {

    private WikiBatchSupport() {}

    // ═══════════════════════════════════════════════════════════════
    // 错误分类
    // ═══════════════════════════════════════════════════════════════

    /**
     * 失败的 LLM 调用看起来是否像上游 429 / 配额耗尽。
     *
     * <p>它把后续调度器掰到更长的 {@link WikiIngestConstants#RATE_LIMIT_BACKOFF}
     * 上，这样重试就不会继续捶打一个已经打满的 rpm 预算。实现是纯字符串包含判断
     * （大小写不敏感）——<b>不要</b>改成按异常类型判定，
     * 因为 LLM 客户端的错误是跨 20 多家供应商拼出来的人类可读消息。</p>
     */
    public static boolean isLikelyRateLimitError(Throwable err) {
        if (err == null) {
            return false;
        }
        String msg = collectMessage(err).toLowerCase(java.util.Locale.ROOT);
        for (String needle : RATE_LIMIT_NEEDLES) {
            if (msg.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 429 / 配额类错误消息的特征词（小写匹配） */
    private static final String[] RATE_LIMIT_NEEDLES = {
            "rate limit", "ratelimit", "429", "too many requests", "quota"
    };

    /**
     * 收集异常链上的全部消息。
     *
     * <p>异常常把根因放在 cause 里，只看最外层消息会漏掉 "429"
     * ——把整条链拼起来后再匹配，只放宽、不收紧。</p>
     */
    private static String collectMessage(Throwable err) {
        StringBuilder buf = new StringBuilder();
        Throwable cur = err;
        int depth = 0;
        while (cur != null && depth < 8) {
            String m = cur.getMessage();
            if (m != null && !m.isEmpty()) {
                if (buf.length() > 0) {
                    buf.append(": ");
                }
                buf.append(m);
            }
            cur = cur.getCause();
            depth++;
        }
        return buf.length() == 0 ? err.getClass().getSimpleName() : buf.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // 正文清洗
    // ═══════════════════════════════════════════════════════════════

    /**
     * 匹配旧版生成页里残留的短 chunk 别名引用：
     * {@code [ \t]*\[c\d{3,}(?:\s*[,;]\s*c\d{3,})*\]}。
     */
    private static final Pattern INLINE_CHUNK_CITATION_REGEX =
            Pattern.compile("[ \\t]*\\[c\\d{3,}(?:\\s*[,;]\\s*c\\d{3,})*\\]");

    /**
     * 剥掉行内 chunk 引用：它们只是内部的摄取元数据，必须从喂给编辑模型的既有正文里
     * 剥掉，免得后续更新把它们抄回重写的正文里。
     */
    public static String stripInlineChunkCitations(String content) {
        if (content == null || content.isEmpty()) {
            return content == null ? "" : content;
        }
        return INLINE_CHUNK_CITATION_REGEX.matcher(content).replaceAll("");
    }

    // ═══════════════════════════════════════════════════════════════
    // 有界并发扇出
    // ═══════════════════════════════════════════════════════════════

    /**
     * 有界并发的任务扇出。
     *
     * <h2>为什么不用 {@code Executors.newFixedThreadPool(n)}</h2>
     * <p>限制的是<b>同时运行</b>的任务数，任务本身是一次
     * LLM 调用（阻塞 IO）。这里用<b>虚拟线程 + 信号量</b>：信号量控制并发度、
     * 虚拟线程承载阻塞调用（项目已开启 {@code spring.threads.virtual.enabled}）。</p>
     *
     * <h2>返回值的取舍</h2>
     * <p>调用点惯例：任务函数在每条路径上自行记日志、不取消兄弟任务，因此这里
     * <b>不实现</b>首错取消，只等待全部任务结束——与既有调度语义一致，
     * 省掉一个不会生效的取消机制。</p>
     *
     * <h2>上下文传播</h2>
     * <p>ThreadLocal <b>不会</b>被新线程继承，因此这里在扇出时显式抓取父线程的
     * {@link TenantContext#currentTenantId()} 与
     * {@link WikiLanguageSupport#languageFromContext()}，在每个虚拟线程里重新装上并在
     * 结束时清除。没有这一步，扇出里的 LLM 调用会丢掉租户（
     * {@code generateWithTemplate} 的跨调用合并会退化）与语言（
     * 语言解析会回落到默认值）。</p>
     *
     * @param limit  并发上限（{@code <= 0} 表示不限，但会按 1 处理以避免无限线程）
     * @param bodies 任务体（每个任务自己吞掉异常并记日志）
     */
    public static void fanOut(int limit, List<Runnable> bodies) {
        if (bodies == null || bodies.isEmpty()) {
            return;
        }
        // 在父线程抓取"会随 ctx 流动"的值
        final Long tenantId = TenantContext.currentTenantId();
        final String locale = WikiLanguageSupport.languageFromContext();

        int effective = Math.max(1, limit);
        Semaphore slots = new Semaphore(effective);
        List<Thread> threads = new ArrayList<>(bodies.size());
        for (Runnable body : bodies) {
            Thread t = Thread.ofVirtual().unstarted(() -> {
                try {
                    slots.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (tenantId != null) {
                    TenantContext.set(tenantId, null, null, false, null, false);
                }
                WikiLanguageSupport.setCurrentLocale(locale);
                try {
                    body.run();
                } catch (Throwable ignored) {
                    // 任务内已自行记日志
                } finally {
                    // 必须显式清，否则污染后续复用该线程的任务
                    if (tenantId != null) {
                        TenantContext.clear();
                    }
                    WikiLanguageSupport.clearCurrentLocale();
                    slots.release();
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            boolean interrupted = false;
            while (true) {
                try {
                    t.join();
                    break;
                } catch (InterruptedException ie) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 租户作用域
    // ═══════════════════════════════════════════════════════════════

    /**
     * 为 wiki 批次建立租户上下文作用域。
     *
     * <p><b>为什么必须有</b>：wiki 批次跑在队列的虚拟线程上，那里没有 HTTP 请求的
     * Filter 链，因此 {@link TenantContext} 是空的。而
     * {@code WikiModelResolver.getChatModel} 要走
     * {@code ModelService.getModelByID}（按租户可见性过滤），空租户会让用户自建的模型
     * 查不到——批次直接以 {@code get_chat_model_failed} 失败。</p>
     *
     * <p>返回句柄必须在 {@code finally} 里 {@link TenantScope#close()}：它会<b>恢复</b>
     * 调用线程原本的会话（而不是粗暴清空），这样在请求线程上同步重放任务
     * （测试、运维工具）也不会把请求上下文弄丢。</p>
     */
    public static TenantScope enterTenantScope(long tenantId) {
        Long prevTenant = TenantContext.currentTenantId();
        TenantContext.Principal prevPrincipal = TenantContext.currentPrincipal();
        String prevRole = TenantContext.currentRole();
        boolean prevSysAdmin = TenantContext.isSystemAdmin();
        String prevUser = TenantContext.currentUserId();
        boolean prevAccessAll = TenantContext.canAccessAllTenants();
        if (tenantId > 0) {
            TenantContext.set(tenantId, null, null, false, null, false);
        }
        return new TenantScope(prevTenant, prevPrincipal, prevRole, prevSysAdmin,
                prevUser, prevAccessAll);
    }

    /** {@link #enterTenantScope(long)} 的作用域句柄：close 时恢复调用线程原会话。 */
    public static final class TenantScope implements AutoCloseable {
        private final Long prevTenant;
        private final TenantContext.Principal prevPrincipal;
        private final String prevRole;
        private final boolean prevSysAdmin;
        private final String prevUser;
        private final boolean prevAccessAll;

        TenantScope(Long prevTenant, TenantContext.Principal prevPrincipal, String prevRole,
                    boolean prevSysAdmin, String prevUser, boolean prevAccessAll) {
            this.prevTenant = prevTenant;
            this.prevPrincipal = prevPrincipal;
            this.prevRole = prevRole;
            this.prevSysAdmin = prevSysAdmin;
            this.prevUser = prevUser;
            this.prevAccessAll = prevAccessAll;
        }

        @Override
        public void close() {
            TenantContext.clear();
            if (prevTenant != null || prevPrincipal != null || prevRole != null
                    || prevUser != null || prevSysAdmin || prevAccessAll) {
                TenantContext.set(prevTenant, prevPrincipal, prevRole, prevSysAdmin,
                        prevUser, prevAccessAll);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // span 门面
    // ═══════════════════════════════════════════════════════════════

    /**
     * span 追踪门面。
     *
     * <p>接入 {@link SpanTracker} 后，wiki 批次在父 attempt 的 postprocess 阶段下挂出
     * {@code postprocess.wiki}（及其 {@code .extract}/{@code .summary}/{@code .classify}/
     * {@code .page[slug]} 子 span），trace 视图因此可见逐文档的 wiki 处理。
     * {@link #NOOP}（未注入追踪器）保留纯 no-op 语义——追踪绝不阻断业务。</p>
     */
    public static final class WikiSpans {

        /** 未接线追踪器时的 no-op 形态。 */
        public static final WikiSpans NOOP = new WikiSpans(null);

        private final SpanTracker tracker;

        public WikiSpans(SpanTracker tracker) {
            this.tracker = tracker;
        }

        /**
         * {@code LatestAttempt} → {@code LookupStage(postprocess)} 找父 span，
         * 在其下开 {@code postprocess.wiki}。任一步缺失 → null（best-effort：
         * 追踪绝不阻断业务）。
         *
         * <p>跨线程说明：wiki 批次跑在独立调度线程，没有引擎的 attempt 上下文；
         * 这里用 knowledgeId 从 {@link SpanTracker} 反查，不依赖调用线程携带 attempt。</p>
         */
        public SpanTracker.SpanHandle beginWikiSubspan(String knowledgeId,
                                                       Map<String, Object> input) {
            if (tracker == null || knowledgeId == null || knowledgeId.isEmpty()) {
                return null;
            }
            try {
                int attempt = tracker.latestAttempt(knowledgeId);
                if (attempt <= 0) {
                    return null;
                }
                SpanTracker.SpanHandle parent = tracker.lookupStage(knowledgeId, attempt,
                        KnowledgeProcessingSpan.STAGE_POST_PROCESS);
                if (parent == null) {
                    return null;
                }
                return tracker.beginSubSpan(parent, "postprocess.wiki",
                        KnowledgeProcessingSpan.KIND_SUB_SPAN, input);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** 在父 span 下开子 span；父缺席 → null。 */
        public SpanTracker.SpanHandle beginSubSpan(SpanTracker.SpanHandle parent, String name,
                                                   Map<String, Object> input) {
            if (tracker == null || parent == null || name == null || name.isEmpty()) {
                return null;
            }
            try {
                return tracker.beginSubSpan(parent, name,
                        KnowledgeProcessingSpan.KIND_SUB_SPAN, input);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** 结束 span（best-effort）。 */
        public void endSpan(SpanTracker.SpanHandle span, Map<String, Object> output) {
            if (tracker == null || span == null) {
                return;
            }
            try {
                tracker.endSpan(span, output);
            } catch (RuntimeException e) {
                // best-effort：追踪失败不阻断批次
            }
        }

        /** 标记 span 失败（best-effort）。 */
        public void failSpan(SpanTracker.SpanHandle span, String code, String message,
                             Throwable err) {
            if (tracker == null || span == null) {
                return;
            }
            try {
                tracker.failSpan(span, code, message, err);
            } catch (RuntimeException e) {
                // best-effort
            }
        }

        /** 标记 span 跳过（best-effort）。 */
        public void skipSpan(SpanTracker.SpanHandle span, String reason) {
            if (tracker == null || span == null) {
                return;
            }
            try {
                tracker.skipSpan(span, reason);
            } catch (RuntimeException e) {
                // best-effort
            }
        }
    }
}
