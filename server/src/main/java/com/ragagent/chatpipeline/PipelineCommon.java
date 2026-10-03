package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.BiFunction;

import com.ragagent.agent.PromptInstructions;
import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.chatpipeline.plugin.PluginError;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.common.prompt.MessageAttachmentsPrompt;
import com.ragagent.common.session.PipelineMessageAttachmentView;
import com.ragagent.common.session.PipelineMessageImageView;
import com.ragagent.common.session.PipelineMessageView;

/**
 * 管线共享工具。
 *
 * <h2>已知取舍</h2>
 * <ul>
 *   <li>提示词缓存指纹元数据未接线：{@link LlmChatClient} 无 ctx 形参、无消费点，非行为面。</li>
 *   <li>虚拟线程 + TenantContext：runParallel/parallelMap 的任务里若要读租户，
 *       由调用方先 {@code TenantContextSnapshot.capture()} 再在任务里 replay。
 *       管线内部对 service 的调用一律显式传 tenantId/chatManage 值，不依赖 ThreadLocal。</li>
 * </ul>
 */
public final class PipelineCommon {

    /** 剥离 assistant 回答里 &lt;think&gt;…&lt;/think&gt; 的正则（(?s) 跨行匹配）。 */
    private static final String THINK_TAGS = "(?s)<think>.*?</think>";

    private PipelineCommon() {}

    // ----- 日志快捷方式 -----

    static void info(String stage, String action, Map<String, Object> fields) {
        PipelineLog.info(stage, action, fields);
    }

    static void warn(String stage, String action, Map<String, Object> fields) {
        PipelineLog.warn(stage, action, fields);
    }

    static void error(String stage, String action, Map<String, Object> fields) {
        PipelineLog.error(stage, action, fields);
    }

    // ----- 模型准备 -----

    /** (chatModel, opt) 组合返回。 */
    public record PreparedChatModel(LlmChatClient chatModel, ChatOptions options) {}

    /** 取 chat 模型并从 SummaryConfig 组 ChatOptions。失败抛异常。 */
    public static PreparedChatModel prepareChatModel(PipelinePorts.ModelService modelService,
                                                     ChatManage chatManage) {
        LlmChatClient chatModel = modelService.getChatModel(chatManage.getChatModelId());
        if (chatModel == null) {
            throw new PipelinePorts.PipelinePortException(
                    "chat model " + chatManage.getChatModelId() + " not found");
        }
        ChatOptions opt = new ChatOptions();
        SummaryConfig sc = chatManage.getSummaryConfig();
        opt.setTemperature(sc.getTemperature());
        opt.setTopP(sc.getTopP());
        opt.setSeed(sc.getSeed());
        opt.setMaxTokens(sc.getMaxTokens());
        opt.setMaxCompletionTokens(sc.getMaxCompletionTokens());
        opt.setFrequencyPenalty(sc.getFrequencyPenalty());
        opt.setPresencePenalty(sc.getPresencePenalty());
        opt.setThinking(sc.getThinking());
        opt.setPromptCacheKey(chatManage.getSessionId());
        if (opt.getThinking() != null) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("enabled", opt.getThinking());
            info("Stream", "thinking_option", fields);
        }
        return new PreparedChatModel(chatModel, opt);
    }

    // ----- 消息组装 -----

    /**
     * 渲染系统提示词 + 历史 + 当前用户消息。
     * SystemPromptOverride 优先于 SummaryConfig.Prompt；记忆段落接在系统提示词最末。
     */
    public static List<ChatMessage> prepareMessagesWithHistory(ChatManage chatManage) {
        String base = chatManage.getSummaryConfig().getPrompt();
        if (!chatManage.getSystemPromptOverride().isEmpty()) {
            base = chatManage.getSystemPromptOverride();
        }
        Map<String, String> vals = new LinkedHashMap<>();
        vals.put("query", chatManage.getQuery());
        vals.put("language", chatManage.getLanguage());
        vals.put("contexts", chatManage.getRenderedContexts());
        String systemPrompt = AgentPromptPlaceholdersHolder.render(base, vals);
        systemPrompt += "\n\n" + PromptInstructions.SOURCE_DATA_BOUNDARY_PROMPT
                + "\n\n" + PromptInstructions.SOURCED_ANSWER_OUTPUT_PROMPT;
        systemPrompt += chatManage.getMemoryPrompt();

        List<ChatMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatMessage("system", systemPrompt));

        appendHistoryMessages(chatMessages, chatManage.getHistory());

        // 当前用户消息：仅 chat 模型支持视觉时带图（非 vision 靠 UserContent 里的文本描述）。
        ChatMessage userMsg = new ChatMessage("user", chatManage.getUserContent());
        if (chatManage.isChatModelSupportsVision()
                && chatManage.getImages() != null && !chatManage.getImages().isEmpty()) {
            userMsg.setImages(chatManage.getImages());
        }
        chatMessages.add(userMsg);
        return chatMessages;
    }

    /** 按时间序追加历史问答对。 */
    public static void appendHistoryMessages(List<ChatMessage> messages, List<History> history) {
        if (history == null) {
            return;
        }
        for (History h : history) {
            messages.add(new ChatMessage("user", h.getQuery()));
            messages.add(new ChatMessage("assistant", h.getAnswer()));
        }
    }

    /**
     * 取近期消息 → 按 requestID 分组成问答对 → 剥思考标签 →
     * 按时间倒序 → 截 maxRounds → 反转回正序。
     */
    public static List<History> loadAndProcessHistory(PipelinePorts.MessageService messageService,
                                                      String sessionId, int maxRounds, int fetchCount) {
        List<PipelineMessageView> history =
                messageService.getRecentMessagesBySession(sessionId, fetchCount);
        Map<String, History> historyMap = new LinkedHashMap<>();
        for (PipelineMessageView message : history) {
            History h = historyMap.get(message.requestId());
            if (h == null) {
                h = new History();
            }
            if ("user".equals(message.role())) {
                // RenderedContent 是旧轮的提示词快照，重放会把旧协议混进本轮；
                // 历史引用单独走 KnowledgeReferences，由本轮重新渲染合并。
                h.setQuery(message.content());
                h.setCreateAt(message.createdAt() == null ? null : message.createdAt().toInstant());
                String desc = extractImageCaptions(message.images());
                if (!desc.isEmpty()) {
                    h.setQuery(h.getQuery() + "\n\n[用户上传图片内容]\n" + desc);
                }
                if (message.attachments() != null && !message.attachments().isEmpty()) {
                    h.setQuery(h.getQuery() + MessageAttachmentsPrompt.build(message.attachments()));
                }
            } else {
                h.setAnswer(message.content().replaceAll(THINK_TAGS, ""));
                h.setKnowledgeReferences(message.knowledgeReferences());
            }
            historyMap.put(message.requestId(), h);
        }

        List<History> historyList = new ArrayList<>(historyMap.size());
        for (History h : historyMap.values()) {
            if (!h.getAnswer().isEmpty() && !h.getQuery().isEmpty()) {
                historyList.add(h);
            }
        }

        historyList.sort((a, b) -> {
            java.time.Instant ta = a.getCreateAt();
            java.time.Instant tb = b.getCreateAt();
            if (ta == null && tb == null) {
                return 0;
            }
            if (ta == null) {
                return 1;
            }
            if (tb == null) {
                return -1;
            }
            return tb.compareTo(ta); // After：新在前
        });

        if (historyList.size() > maxRounds) {
            historyList = new ArrayList<>(historyList.subList(0, maxRounds));
        }

        java.util.Collections.reverse(historyList);
        return historyList;
    }

    /** 拼接消息图片的非空 Caption。 */
    static String extractImageCaptions(List<PipelineMessageImageView> images) {
        List<String> parts = new ArrayList<>();
        if (images != null) {
            for (PipelineMessageImageView img : images) {
                if (img != null && img.caption() != null && !img.caption().isEmpty()) {
                    parts.add(img.caption());
                }
            }
        }
        return String.join("\n", parts);
    }

    // ----- 并发工具 -----

    /** 具名并发任务。 */
    public record ParallelTask(String name, java.util.function.Supplier<PluginError> run) {}

    /**
     * 并发执行任务，返回 name → 非 null 错误的 map（无错任务不进 map）。
     * 任务跑在虚拟线程上。
     *
     * <p>ThreadLocal 不跨线程，这里在提交线程快照 TenantContext、任务线程回放——
     * 否则下游读 TenantContext 的代码（如 getModelByID → tenantId()=0）会静默拿到空上下文。</p>
     */
    public static Map<String, PluginError> runParallel(ParallelTask... tasks) {
        Map<String, PluginError> errs = new HashMap<>();
        if (tasks.length == 0) {
            return errs;
        }
        com.ragagent.event.TenantContextSnapshot tenantSnap =
                com.ragagent.event.TenantContextSnapshot.capture();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(tasks.length);
            for (ParallelTask task : tasks) {
                futures.add(executor.submit(() -> {
                    tenantSnap.replay();
                    try {
                        PluginError err = task.run().get();
                        if (err != null) {
                            synchronized (errs) {
                                errs.put(task.name(), err);
                            }
                        }
                        return null;
                    } finally {
                        com.ragagent.common.context.TenantContext.clear();
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("parallel task failed", e);
                }
            }
        }
        return errs;
    }

    /**
     * 并发映射，结果保持 items 顺序；maxWorkers ≤ 0 不限并发。
     * 任务跑在虚拟线程上，用信号量封顶并发。
     */
    public static <T, R> List<R> parallelMap(List<T> items, int maxWorkers,
                                             BiFunction<Integer, T, R> fn) {
        int n = items.size();
        if (n == 0) {
            return null;
        }
        List<R> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            results.add(null);
        }
        int workers = maxWorkers <= 0 || maxWorkers > n ? n : maxWorkers;
        Semaphore sem = new Semaphore(workers);
        com.ragagent.event.TenantContextSnapshot tenantSnap =
                com.ragagent.event.TenantContextSnapshot.capture();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                final T item = items.get(i);
                futures.add(executor.submit(() -> {
                    tenantSnap.replay();
                    try {
                        sem.acquireUninterruptibly();
                        try {
                            results.set(idx, fn.apply(idx, item));
                        } finally {
                            sem.release();
                        }
                        return null;
                    } finally {
                        com.ragagent.common.context.TenantContext.clear();
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    throw new IllegalStateException("parallel map failed", e);
                }
            }
        }
        return results;
    }

    /** AgentPromptPlaceholders 是 agent 包的 public 类，直接静态引用（避免每次写全名）。 */
    private static final class AgentPromptPlaceholdersHolder {
        static String render(String template, Map<String, String> vals) {
            return com.ragagent.common.prompt.AgentPromptPlaceholders.renderPromptPlaceholders(template, vals);
        }
    }

    /** 附件提示词构建（见 {@link MessageAttachmentsPrompt}）。 */
    static String attachmentsPrompt(List<PipelineMessageAttachmentView> attachments) {
        return MessageAttachmentsPrompt.build(attachments);
    }

    /** 供 MergeSupport 用。 */
    static List<SearchTarget> targets(ChatManage cm) {
        return cm.getSearchTargets();
    }
}
