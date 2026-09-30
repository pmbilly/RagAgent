package com.ragagent.im.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agentm.domain.CustomAgentEntity;
import com.ragagent.agentm.service.CustomAgentService;
import com.ragagent.agentm.service.AgentConfigJson;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.EventType;
import com.ragagent.im.domain.ChannelSessionEntity;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.mapper.ChannelSessionMapper;
import com.ragagent.im.mapper.ImChannelMapper;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.AdapterInterfaces.Adapter;
import com.ragagent.im.runtime.AdapterInterfaces.FullOutputProgressSender;
import com.ragagent.im.runtime.AdapterInterfaces.StreamSender;
import com.ragagent.im.runtime.Commands;
import com.ragagent.im.runtime.Commands.CommandContext;
import com.ragagent.im.runtime.Commands.CommandRegistry;
import com.ragagent.im.runtime.Commands.CommandResult;
import com.ragagent.im.runtime.ImCommandSet;
import com.ragagent.im.runtime.ImFormat;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.QaQueue;
import com.ragagent.im.runtime.ReplyMessage;
import com.ragagent.im.runtime.StreamSection;
import com.ragagent.im.runtime.ThinkDisplay;
import com.ragagent.im.runtime.ToolDisplay;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.Session;
import com.ragagent.session.service.MessageService;
import com.ragagent.session.service.SessionAgentQaService;
import com.ragagent.session.service.SessionKnowledgeQaService;
import com.ragagent.session.service.QaSupport;
import com.ragagent.session.service.SessionService;

/**
 * IM 执行体核心（对照 Go internal/im/service.go，波 5 W5γ2 翻译；方法注释按
 * Go 行号锚定）。
 *
 * <h2>Redis 分支的落地声明（波 5 纪律：内存形态先行）</h2>
 * Go 的 redis 可 nil，nil 分支全部有本地回落。Java 侧先落这些本地分支：
 * <ul>
 *   <li>去重（L1650-1667 redis==nil：进程内 map + TTL 清理）；</li>
 *   <li>WS leader 选举（L1235+）：单实例恒 leader——不做 Redis SETNX 竞选；</li>
 *   <li>跨实例 /stop 标记与 inflight 映射（L1454-1553）：进程内 map；</li>
 *   <li>渠道配置 pub/sub（L1021-1101）：本进程内直接失效。</li>
 * </ul>
 * 多实例部署把这些换成 Redis 实现即可（键名常量保留在 {@link ImRedisKeys}）。
 */
@Service
public class ImService {

    private static final Logger log = LoggerFactory.getLogger(ImService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ImChannelMapper channels;
    private final ChannelSessionMapper channelSessions;
    private final SessionService sessionService;
    private final MessageService messageService;
    private final CustomAgentService agentService;
    private final TenantService tenantService;
    private final SessionKnowledgeQaService knowledgeQaService;
    private final SessionAgentQaService agentQaService;
    private final com.ragagent.storage.support.Resolver storageResolver;
    private final com.ragagent.storage.support.FileService defaultFileSvc;

    // ── 调谐参数（对照 resolveIMConfig，service.go L805-840 + L40-60 常量） ──
    private final int workers;
    private final int maxQueue;
    private final int maxPerUser;
    private final int rateLimitWindowSec;
    private final int rateLimitMax;

    private final CommandRegistry cmdRegistry = new CommandRegistry();
    private final QaQueue qaQueue;

    /** 运行中的渠道（channelID → 状态）。 */
    private final Map<String, ChannelState> channelStates = new ConcurrentHashMap<>();
    /** 去重（redis==nil 分支）：messageID → epoch 秒。 */
    private final Map<String, Long> processedMsgs = new ConcurrentHashMap<>();
    /** 在途请求（/stop 的本地取消面）。 */
    private final Map<String, InflightEntry> inflight = new ConcurrentHashMap<>();
    /** 跨实例 /stop 标记的本地等价物。 */
    private final Map<String, Long> stopMarkers = new ConcurrentHashMap<>();

    /** 运行中的渠道状态（对照 Go {@code im.channelState}，service.go L261-267）。 */
    record ChannelState(ImChannelEntity channel, Adapter adapter,
            AtomicReference<Runnable> adapterStop) {
    }

    static final class InflightEntry {
        final java.util.function.BooleanSupplier cancel;
        volatile String sessionId = "";
        volatile String assistantMessageId = "";

        InflightEntry(java.util.function.BooleanSupplier cancel) {
            this.cancel = cancel;
        }
    }

    public ImService(ImChannelMapper channels, ChannelSessionMapper channelSessions,
            SessionService sessionService, MessageService messageService,
            CustomAgentService agentService, TenantService tenantService,
            SessionKnowledgeQaService knowledgeQaService, SessionAgentQaService agentQaService,
            java.util.Optional<com.ragagent.storage.support.Resolver> storageResolver,
            java.util.Optional<com.ragagent.storage.support.FileService> defaultFileSvc,
            @Value("${im.workers:5}") int workers,
            @Value("${im.max-queue:50}") int maxQueue,
            @Value("${im.max-per-user:3}") int maxPerUser,
            @Value("${im.rate-limit-window-sec:60}") int rateLimitWindowSec,
            @Value("${im.rate-limit-max:10}") int rateLimitMax) {
        this.channels = channels;
        this.channelSessions = channelSessions;
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.agentService = agentService;
        this.tenantService = tenantService;
        this.knowledgeQaService = knowledgeQaService;
        this.agentQaService = agentQaService;
        this.storageResolver = storageResolver.orElse(null);
        this.defaultFileSvc = defaultFileSvc.orElse(null);
        this.workers = workers;
        this.maxQueue = maxQueue;
        this.maxPerUser = maxPerUser;
        this.rateLimitWindowSec = rateLimitWindowSec;
        this.rateLimitMax = rateLimitMax;
        ImCommandSet.registerDefaults(this.cmdRegistry, kbLister(), knowledgeSearcher());
        this.qaQueue = new QaQueue(workers, maxQueue, maxPerUser, task -> {
            QaTask t = (QaTask) task.attach();
            executeQARequest(t);
        });
        this.qaQueue.start();
    }

    // ── 命令的依赖面（cmd_info/cmd_search 的 KB/检索读取） ────────────────

    private ImCommandSet.KnowledgeBaseLister kbLister() {
        return new ImCommandSet.KnowledgeBaseLister() {
            @Override
            public List<KbView> listKnowledgeBases() {
                return List.of();
            }

            @Override
            public List<KbView> listKnowledgeBasesByTenantId(long tenantId) {
                return List.of();
            }
        };
    }

    private ImCommandSet.KnowledgeSearcher knowledgeSearcher() {
        return (kbIds, knowledgeIds, documentIds, query) -> List.of();
    }

    // ── 渠道生命周期（service.go L921-1250 精简面：adapter 注册表） ────────

    /** 渠道启动时必须注册的平台工厂（对照 AdapterFactory / RegisterAdapterFactory）。 */
    public interface AdapterFactory {
        /** 返回适配器与停止函数（长连接的拆除柄）。webhook 型适配器 stop 可为 null。 */
        AdapterRegistration create(ImChannelEntity channel,
                java.util.function.BiConsumer<IncomingMessage, String> msgHandler);
    }

    public record AdapterRegistration(Adapter adapter, Runnable stop) {
    }

    private final Map<String, AdapterFactory> adapterFactories = new ConcurrentHashMap<>();

    /** 对照 RegisterAdapterFactory（service.go L921-927）。 */
    public void registerAdapterFactory(String platform, AdapterFactory factory) {
        adapterFactories.put(platform, factory);
    }

    /** 渠道行 → 适配器是否就绪且配置未变（对照 GetChannelAdapter/EnsureChannelAdapter 精简）。 */
    public Adapter adapterFor(ImChannelEntity channel) {
        if (channel == null || !channel.isEnabled()) {
            return null;
        }
        AdapterFactory factory = adapterFactories.get(channel.getPlatform());
        if (factory == null) {
            return null;
        }
        ChannelState state = channelStates.get(channel.getId());
        if (state == null) {
            startChannel(channel);
            state = channelStates.get(channel.getId());
        }
        return state == null ? null : state.adapter();
    }

    /** 对照 StartChannel/startChannelInternal（L1102-1190）：注册适配器 + 起 worker。 */
    public synchronized void startChannel(ImChannelEntity channel) {
        AdapterFactory factory = adapterFactories.get(channel.getPlatform());
        if (factory == null) {
            log.warn("[IM] no adapter factory for platform {} (channel {})",
                    channel.getPlatform(), channel.getId());
            return;
        }
        AtomicReference<Runnable> stopRef = new AtomicReference<>();
        AdapterRegistration reg;
        try {
            reg = factory.create(channel, (msg, chId) -> handleMessage(msg, chId));
        } catch (RuntimeException e) {
            // 照 Go：工厂失败（凭据不全 / 出站校验不过 / 平台未实现该模式）时渠道起不来，
            // 适配器不入运行态——回调路径因此走 "adapter not active"（503 "channel not
            // available"），而不是把异常冒成 500。Go 的对应事实：golden
            // w5a-im-callback-enabled-get.json 即"mattermost 工厂建适配器失败 → 503"。
            log.warn("[IM] Channel start failed: id={} platform={} mode={} err={}",
                    channel.getId(), channel.getPlatform(), channel.getMode(), e.toString());
            return;
        }
        stopRef.set(reg.stop());
        channelStates.put(channel.getId(), new ChannelState(channel, reg.adapter(), stopRef));
        log.info("[IM] Channel started: id={} platform={} mode={}", channel.getId(),
                channel.getPlatform(), channel.getMode());
    }

    /** 对照 StopChannel（L1191-1234）。 */
    public synchronized void stopChannel(String channelId) {
        ChannelState cs = channelStates.remove(channelId);
        if (cs != null && cs.adapterStop() != null && cs.adapterStop().get() != null) {
            cs.adapterStop().get().run();
        }
    }

    /**
     * 对照 Go {@code DeleteChannelsByAgent}（service.go L3243-3261）：软删该 agent
     * 的全部 IM 渠道并停止运行中的适配器——概览列表与运行中的适配器不得比 agent
     * 活得更久（自定义 agent 删除时调用）。
     *
     * <p>Go 的 {@code publishChannelConfigChange}（跨实例配置广播）在 Java 单实例
     * 装配下无对应面，与 Go 单实例行为等价。</p>
     */
    public void deleteChannelsByAgent(String agentId, long tenantId) {
        java.util.List<ImChannelEntity> found = channels.listByAgent(agentId, tenantId);
        if (found.isEmpty()) {
            return;
        }
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        for (ImChannelEntity ch : found) {
            channels.softDelete(ch.getId(), tenantId, now);
            stopChannel(ch.getId());
        }
    }

    /** 对照 LoadAndStartChannels（L985-1020）：启动时拉起全部 enabled 渠道。 */
    public void loadAndStartChannels() {
        for (ImChannelEntity ch : channels.listEnabled()) {
            startChannel(ch);
        }
    }

    /**
     * 对照 container.go L1664-1667：应用就绪后从库拉起全部 enabled 渠道（2026-09-25
     * 评审批接线——此前全工程无调用点，重启后渠道全部沉默）。失败只 WARN，不阻塞启动。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startChannelsOnReady() {
        try {
            loadAndStartChannels();
        } catch (RuntimeException e) {
            log.warn("[IM] Failed to load channels from database: {}", e.getMessage());
        }
    }

    /**
     * 对照 Go {@code Service.Stop()}（service.go L928-945）+ container 的
     * {@code cleaner.RegisterWithName("IMService", imService.Stop)}：停机时停
     * QA 队列与全部运行中的渠道适配器。
     */
    @jakarta.annotation.PreDestroy
    public void stop() {
        try {
            qaQueue.stop();
        } catch (RuntimeException e) {
            log.warn("[IM] qa queue stop failed: {}", e.getMessage());
        }
        for (String id : new java.util.ArrayList<>(channelStates.keySet())) {
            try {
                stopChannel(id);
            } catch (RuntimeException e) {
                log.warn("[IM] channel {} stop failed: {}", id, e.getMessage());
            }
        }
    }

    private long channelTenantIdOrThrow(String channelId) {
        ChannelState st = channelStates.get(channelId);
        if (st != null) {
            return st.channel().getTenantId();
        }
        ImChannelEntity ch = channels.getById(channelId);
        if (ch == null) {
            throw new IllegalStateException("channel not found: " + channelId);
        }
        return ch.getTenantId();
    }

    // ── 消息入口（HandleMessage，service.go L1670-1890） ─────────────────

    /** 对照 isDuplicate（L1650-1667）的 redis==nil 分支。 */
    boolean isDuplicate(String messageId) {
        long now = System.currentTimeMillis();
        Long prev = processedMsgs.putIfAbsent(messageId, now);
        if (prev != null) {
            return true;
        }
        if (processedMsgs.size() > 10_000) {
            processedMsgs.entrySet().removeIf(e -> now - e.getValue() > 300_000);
        }
        return false;
    }

    /**
     * IM 消息总入口：去重 → 限长 → 适配器解析 → 限流 → 空消息提示 → 会话解析 →
     * 命令分派 → QA 排队。全程绑定渠道租户的合成身份（对照 Go withIMIdentity，
     * service.go L425-443："system-<tenantID>" 合成用户 + viewer 最小权限——
     * 组织共享 KB 的解析要求非空 UserID）。
     */
    public void handleMessage(IncomingMessage msg, String channelId) {
        com.ragagent.event.TenantContextSnapshot previous =
                com.ragagent.event.TenantContextSnapshot.capture();
        try {
            runHandleMessage(msg, channelId);
        } finally {
            TenantContext.clear();
            previous.replay();
        }
    }

    private void runHandleMessage(IncomingMessage msg, String channelId) {
        if (TenantContext.currentTenantId() == null) {
            // 回调线程无认证上下文——绑渠道租户（QA 管线的仓库查询要求租户在上下文）。
            long tid = channelTenantIdOrThrow(channelId);
            TenantContext.set(tid, new TenantContext.Principal(
                    TenantContext.PrincipalTypes.IM_USER, "system-" + tid),
                    "viewer", false, "system-" + tid, false);
        }
        if (msg.messageId != null && !msg.messageId.isEmpty() && isDuplicate(msg.messageId)) {
            log.info("[IM] Skipping duplicate message: {}", msg.messageId);
            return;
        }
        // 限长（runes；service.go L1674-1679）
        if (msg.content.codePointCount(0, msg.content.length()) > ImFormat.MAX_CONTENT_LENGTH) {
            log.warn("[IM] Message too long, truncating to {}", ImFormat.MAX_CONTENT_LENGTH);
            msg.content = msg.content.substring(0,
                    msg.content.offsetByCodePoints(0, ImFormat.MAX_CONTENT_LENGTH));
        }

        ChannelState state = channelStates.get(channelId);
        Adapter adapter = state == null ? null : state.adapter();
        if (adapter == null) {
            ImChannelEntity ch = channels.getById(channelId);
            if (ch == null) {
                throw new IllegalStateException("channel not found: " + channelId);
            }
            startChannel(ch);
            state = channelStates.get(channelId);
            adapter = state == null ? null : state.adapter();
            if (adapter == null) {
                throw new IllegalStateException(
                        "channel adapter not available after start: " + channelId);
            }
        }
        ImChannelEntity channel = state.channel();

        String threadId = ImTypes.SESSION_MODE_THREAD.equals(channel.getSessionMode())
                ? msg.threadId : "";

        boolean isCommand = cmdRegistry.isRegistered(msg.content);
        if (!isCommand && !rateLimitAllow(makeRateKey(channelId, msg.userId, msg.chatId, threadId))) {
            log.warn("[IM] Rate limited: channel={} user={} chat={}", channelId, msg.userId, msg.chatId);
            sendReplyQuiet(adapter, msg, new ReplyMessage("您的消息发送过于频繁，请稍后再试。", false, true));
            return;
        }

        if (ImTypes.MESSAGE_TYPE_FILE.equals(msg.messageType)
                || ImTypes.MESSAGE_TYPE_IMAGE.equals(msg.messageType)) {
            msg.content = ImFormat.fileMessageQAContent(msg);
        }
        var empty = emptyIncomingMessageReply(msg);
        if (empty.present()) {
            sendReplyQuiet(adapter, msg, new ReplyMessage(empty.hint(), false, true));
            return;
        }

        long tenantId = channel.getTenantId();
        String agentId = channel.getAgentId() == null ? "" : channel.getAgentId();

        // 会话解析（租户已在上下文；ChannelSession/Session 另带显式租户列）。
        ChannelSessionEntity channelSession =
                resolveSession(msg, tenantId, agentId, channelId, channel.getSessionMode());
        CustomAgentEntity customAgent = null;
        if (!agentId.isEmpty()) {
            try {
                var result = agentService.getAgentByID(agentId, "zh-CN");
                customAgent = result == null ? null : result.row();
            } catch (Exception e) {
                log.warn("[IM] Failed to get agent {}: {}, using default", agentId, e.getMessage());
            }
        }

        Commands.ParseResult cmd = cmdRegistry.parse(msg.content);
        if (cmd.matched()) {
            handleCommand(cmd.command(), cmd.args(), msg, adapter, channel, channelSession,
                    customAgent);
            return;
        }
        if (Commands.looksLikeCommand(msg.content)) {
            sendReplyQuiet(adapter, msg,
                    new ReplyMessage("未知指令，发送 `/help` 查看所有可用指令。", false, true));
            return;
        }

        Session session = sessionService.getSession(channelSession.getSessionId());
        if (session == null) {
            // 会话被删：回收陈旧映射并重解析（service.go L1826-1845）
            channelSessions.softDelete(channelSession.getId(), OffsetDateTime.now());
            channelSession = resolveSession(msg, tenantId, agentId, channelId,
                    channel.getSessionMode());
            session = sessionService.getSession(channelSession.getSessionId());
            if (session == null) {
                throw new IllegalStateException(
                        "get session (retry): " + channelSession.getSessionId());
            }
        }

        String userKey = ImFormat.makeUserKey(channelId, msg.userId, msg.chatId, threadId);
        QaQueue.QaRequest req = new QaQueue.QaRequest(userKey, msg);
        QaTask task = new QaTask(msg, session, customAgent, adapter, channel, channelId, userKey);
        task.bind(req);
        req.attach(task);
        try {
            qaQueue.enqueue(req);
        } catch (QaQueue.RejectedException e) {
            log.warn("[IM] Queue rejected: user={} reason={}", msg.userId, e.getMessage());
            sendReplyQuiet(adapter, msg,
                    new ReplyMessage("当前排队较多，请稍后再试。", false, true));
        }
    }

    /** 限流的本地滑动窗口（对照 ratelimit.Limiter.Allow 的本地分支）。 */
    private final Map<String, List<Long>> rateWindows = new ConcurrentHashMap<>();

    private boolean rateLimitAllow(String key) {
        long now = System.currentTimeMillis();
        long windowMs = rateLimitWindowSec * 1000L;
        List<Long> hits = rateWindows.computeIfAbsent(key, k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        synchronized (hits) {
            hits.removeIf(t -> now - t > windowMs);
            if (hits.size() >= rateLimitMax) {
                return false;
            }
            hits.add(now);
            return true;
        }
    }

    private static String makeRateKey(String channelId, String userId, String chatId, String threadId) {
        return "rl:" + ImFormat.makeUserKey(channelId, userId, chatId, threadId);
    }

    /** 对照 emptyIncomingMessageReply（service.go L1891-1917）。 */
    record EmptyHint(String hint, boolean present) {
    }

    static EmptyHint emptyIncomingMessageReply(IncomingMessage msg) {
        if (msg == null || !msg.content.strip().isEmpty()) {
            return new EmptyHint("", false);
        }
        boolean hasAttachment = ImTypes.MESSAGE_TYPE_FILE.equals(msg.messageType)
                || ImTypes.MESSAGE_TYPE_IMAGE.equals(msg.messageType)
                || !msg.fileKey.strip().isEmpty();
        if (hasAttachment) {
            return new EmptyHint("", false);
        }
        String rawType = msg.extra == null ? ""
                : java.util.Optional.ofNullable(msg.extra.get("raw_msgtype")).orElse("").strip().toLowerCase();
        return switch (rawType) {
            case "audio" -> new EmptyHint("未能识别这条语音中的文字内容。请改用纯文本发送，或再说一遍。", true);
            case "video" -> new EmptyHint("暂不支持视频消息。请改用纯文本发送；图片或文件请单独发送。", true);
            default -> new EmptyHint("未能识别这条消息中的文字内容。请改用纯文本发送；图片或文件请单独发送。", true);
        };
    }

    // ── 会话解析（service.go L2237-2444） ────────────────────────────────

    ChannelSessionEntity resolveSession(IncomingMessage msg, long tenantId, String agentId,
            String imChannelId, String sessionMode) {
        if (ImTypes.SESSION_MODE_THREAD.equals(sessionMode)) {
            return resolveThreadSession(msg, tenantId, agentId, imChannelId);
        }
        return resolveUserSession(msg, tenantId, agentId, imChannelId);
    }

    private ChannelSessionEntity resolveUserSession(IncomingMessage msg, long tenantId,
            String agentId, String imChannelId) {
        ChannelSessionEntity cs = channelSessions.findUserSession(msg.platform, msg.userId,
                msg.chatId, tenantId, agentId);
        if (cs != null) {
            return cs;
        }
        // 有文本就以 "" 起头（首条消息后按内容起标题）；否则用 IM 身份标题。
        Session created = createImSession(tenantId,
                ImFormat.imInitialSessionTitle(msg, ImFormat::buildUserSessionTitle),
                "Auto-created from " + msg.platform + " IM integration");
        ChannelSessionEntity fresh = newMapping(msg, tenantId, agentId, imChannelId, created.getId());
        fresh.setChatId(msg.chatId);
        return insertMapping(fresh, created);
    }

    private ChannelSessionEntity resolveThreadSession(IncomingMessage msg, long tenantId,
            String agentId, String imChannelId) {
        String threadId = msg.threadId;
        if (threadId == null || threadId.isEmpty()) {
            // 纵深防御：前端挡住不支持平台的 thread 模式；真空 thread 回落 user 模式，
            // 避免所有空 thread 消息共享一个会话。
            log.warn("[IM] Thread mode but ThreadID is empty (platform={} chat={}), falling back to user session",
                    msg.platform, msg.chatId);
            return resolveUserSession(msg, tenantId, agentId, imChannelId);
        }
        ChannelSessionEntity cs = channelSessions.findThreadSession(msg.platform, msg.chatId,
                threadId, tenantId, agentId);
        if (cs != null) {
            return cs;
        }
        Session created = createImSession(tenantId,
                ImFormat.imInitialSessionTitle(msg, ImFormat::buildThreadSessionTitle),
                "Thread-based session from " + msg.platform + " IM");
        ChannelSessionEntity fresh = newMapping(msg, tenantId, agentId, imChannelId, created.getId());
        fresh.setChatId(msg.chatId);
        fresh.setThreadId(threadId);
        return insertMapping(fresh, created);
    }

    private Session createImSession(long tenantId, String title, String description) {
        Session s = new Session();
        s.setTenantId(tenantId);
        s.setTitle(title);
        s.setDescription(description);
        return sessionService.createSession(s);
    }

    private static ChannelSessionEntity newMapping(IncomingMessage msg, long tenantId,
            String agentId, String imChannelId, String sessionId) {
        ChannelSessionEntity e = new ChannelSessionEntity();
        e.setId(UUID.randomUUID().toString());
        e.setPlatform(msg.platform);
        // Go 零值语义：空串不是 NULL（H2 显式 NULL 不落列 DEFAULT）
        e.setUserId(msg.userId == null ? "" : msg.userId);
        e.setChatId(msg.chatId == null ? "" : msg.chatId);
        e.setThreadId(msg.threadId == null ? "" : msg.threadId);
        e.setSessionId(sessionId);
        e.setTenantId(tenantId);
        e.setAgentId(agentId == null ? "" : agentId);
        e.setImChannelId(imChannelId == null ? "" : imChannelId);
        e.setStatus("active");
        e.setMetadata("{}");
        return e;
    }

    private ChannelSessionEntity insertMapping(ChannelSessionEntity fresh, Session created) {
        try {
            channelSessions.insert(fresh, OffsetDateTime.now());
            log.info("[IM] Created new session mapping: session={}", created.getId());
            return fresh;
        } catch (RuntimeException e) {
            log.error("[IM] channel session insert failed: {}", e.toString(), e);
            // 并发创建撞唯一约束：清掉孤儿会话，回落已存在映射（Go L2320-2340 同形）。
            try {
                sessionService.deleteSession(created.getId());
            } catch (Exception cleanup) {
                log.warn("[IM] Failed to clean up orphaned session {}: {}",
                        created.getId(), cleanup.getMessage());
            }
            ChannelSessionEntity existing = channelSessions.findUserSession(fresh.getPlatform(),
                    fresh.getUserId(), fresh.getChatId(), fresh.getTenantId(), fresh.getAgentId());
            if (existing == null) {
                throw new IllegalStateException("create channel session: " + e.getMessage(), e);
            }
            return existing;
        }
    }

    // ── 命令执行（handleCommand，service.go L2080-2193） ──────────────────

    void handleCommand(Commands.ImCommand cmd, List<String> args, IncomingMessage msg,
            Adapter adapter, ImChannelEntity channel, ChannelSessionEntity channelSession,
            CustomAgentEntity customAgent) {
        CommandContext cmdCtx = new CommandContext();
        cmdCtx.incoming = msg;
        cmdCtx.session = channelSession;
        cmdCtx.tenantId = channel.getTenantId();
        cmdCtx.agentName = customAgent == null ? "" : customAgent.getName();
        cmdCtx.customAgent = customAgent;
        cmdCtx.channelOutputMode = channel.getOutputMode() == null ? "" : channel.getOutputMode();

        CommandResult result;
        try {
            result = cmd.execute(cmdCtx, args);
        } catch (Exception e) {
            log.error("[IM] Command /{} error: {}", cmd.name(), e.getMessage(), e);
            sendReplyQuiet(adapter, msg,
                    new ReplyMessage("抱歉，执行指令时出现了异常，请稍后再试。", false, true));
            return;
        }

        if (result.action == Commands.ACTION_CLEAR && channelSession != null) {
            // 软删当前映射：下条 IM 消息解析出全新会话。
            channelSessions.softDelete(channelSession.getId(), OffsetDateTime.now());
        } else if (result.action == Commands.ACTION_STOP) {
            doLocalStop(channel, msg, channelSession);
        }

        boolean sent = false;
        if (!"full".equals(channel.getOutputMode()) && adapter instanceof StreamSender streamer) {
            try {
                sendStreamReply(msg, streamer, result.content);
                sent = true;
            } catch (Exception e) {
                log.warn("[IM] Stream reply for command /{} failed, falling back: {}",
                        cmd.name(), e.getMessage());
            }
        }
        if (!sent) {
            sendReplyQuiet(adapter, msg, new ReplyMessage(result.content, false, true));
        }
        log.info("[IM] Command /{} executed: channel={} user={} action={}",
                cmd.name(), channel.getId(), msg.userId, result.action);
    }

    private void doLocalStop(ImChannelEntity channel, IncomingMessage msg,
            ChannelSessionEntity channelSession) {
        String stopThreadId = ImTypes.SESSION_MODE_THREAD.equals(channel.getSessionMode())
                ? msg.threadId : "";
        String inflightKey = ImFormat.makeUserKey(channel.getId(), msg.userId, msg.chatId,
                stopThreadId);
        // 1. 本地取消：出队或在途取消。
        boolean localStopped = qaQueue.remove(inflightKey);
        InflightEntry entry = localStopped ? null : inflight.remove(inflightKey);
        if (entry != null) {
            entry.cancel.getAsBoolean();
            localStopped = true;
        }
        String sessionId = entry == null ? "" : entry.sessionId;
        String messageId = entry == null ? "" : entry.assistantMessageId;
        // 2. 命中在途映射：本地 cancel 已生效（qaCtx 取消）——跨实例的 StreamManager
        //    stop 事件属 Redis 分支（内存形态下唯一实例就是本地）。备案于类注释。
        if (!sessionId.isEmpty() && !messageId.isEmpty()) {
            log.info("[IM] Cancelled in-flight QA: session={} message={}", sessionId, messageId);
        }
        // 3. 标记（redis==nil 的本地等价物）。
        if (!localStopped && sessionId.isEmpty()) {
            stopMarkers.put(inflightKey, System.currentTimeMillis());
            log.info("[IM] Set local stop marker (no inflight found): key={}", inflightKey);
        }
    }

    private volatile com.ragagent.stream.StreamManager streamManagerRef;

    /** StreamManager 延迟接（避免与 stream 包的装配环；测试可注 stub）。 */
    public void setStreamManager(com.ragagent.stream.StreamManager sm) {
        this.streamManagerRef = sm;
    }

    private com.ragagent.stream.StreamManager streamManager() {
        return streamManagerRef;
    }

    /** 对照 sendStreamReply（service.go L2195-2216）。 */
    void sendStreamReply(IncomingMessage msg, StreamSender streamer, String content)
            throws Exception {
        String streamId = streamer.startStream(msg);
        streamer.updateStreamContent(msg, streamId, content);
        streamer.finalizeStream(msg, streamId, content);
        streamer.endStream(msg, streamId);
    }

    // ── QA 执行（executeQARequest，service.go L1927-2013） ────────────────

    void executeQARequest(QaTask task) {
        // 队列 worker 是独立虚拟线程：租户上下文必须显式携带（约定 §5，对照 Go
        // ctx 随 qaRequest.ctx 流转）。
        TenantContext.set(task.tenantId(), new TenantContext.Principal(
                TenantContext.PrincipalTypes.IM_USER, "system-" + task.tenantId()),
                "viewer", false, "system-" + task.tenantId(), false);
        QaQueue.QaRequest req = task.queueReq();
        InflightEntry entry = new InflightEntry(() -> {
            req.cancel();
            return true;
        });
        inflight.put(task.userKey, entry);
        try {
            // 排队期间被 /stop 的直接跳过。
            if (req.isCancelled()) {
                return;
            }
            IncomingMessage msg = task.msg;
            QaAttach attach = task.attach();
            List<com.ragagent.session.domain.MessageAttachment> attachments = List.of();
            List<String> imageUrls = List.of();
            boolean streamDisabled = "full".equals(attach.channel().getOutputMode());

            if (streamDisabled) {
                if (attach.adapter() instanceof FullOutputProgressSender progress
                        && progress.supportsFullOutputProgress()) {
                    try {
                        handleMessageFullOutput(msg, attach, progress);
                    } catch (Exception e) {
                        log.error("[IM] Full-output QA failed: {}", e.getMessage(), e);
                    }
                    return;
                }
            } else if (attach.adapter() instanceof StreamSender streamer) {
                try {
                    handleMessageStream(msg, attach, streamer);
                } catch (Exception e) {
                    log.error("[IM] Stream QA failed: {}", e.getMessage(), e);
                }
                return;
            }

            // 非流式兜底：收集完整答案再发。
            QaOutcome outcome = runQA(attach);
            String answer = outcome.answer();
            if (outcome.error() != null) {
                log.error("[IM] QA failed: {}, sending fallback reply", outcome.error());
                answer = ImFormat.imQAFailureReply(outcome.error());
            }
            String display = formatIMOutboundAnswerOrFallback(answer);
            sendReplyQuiet(attach.adapter(), msg, new ReplyMessage(display, false, true));
            log.info("[IM] Reply sent: channel={} platform={} user={} answer_len={}",
                    attach.channelId(), msg.platform, msg.userId, answer.length());
        } finally {
            inflight.remove(task.userKey);
            TenantContext.clear();
        }
    }

    /** 排队任务：QaRequest（队列面）+ 业务束（对照 Go 的 qaRequest 整体）。 */
    static final class QaTask {
        private QaQueue.QaRequest queueReq;

        QaQueue.QaRequest queueReq() {
            return queueReq;
        }
        final IncomingMessage msg;
        final Session session;
        final CustomAgentEntity agent;
        final Adapter adapter;
        final ImChannelEntity channel;
        final String channelId;
        final String userKey;

        void bind(QaQueue.QaRequest r) {
            this.queueReq = r;
        }

        QaTask(IncomingMessage msg, Session session, CustomAgentEntity agent,
                Adapter adapter, ImChannelEntity channel, String channelId, String userKey) {

            this.msg = msg;
            this.session = session;
            this.agent = agent;
            this.adapter = adapter;
            this.channel = channel;
            this.channelId = channelId;
            this.userKey = userKey;
        }

        long tenantId() {
            return channel.getTenantId();
        }

        QaAttach attach() {
            return new QaAttach(msg, session, agent, adapter, channel, channelId, userKey);
        }
    }

    /** QA 输入束（对照 Go qaRequest 的业务字段）。 */
    record QaAttach(IncomingMessage msg, Session session, CustomAgentEntity agent,
            Adapter adapter, ImChannelEntity channel, String channelId, String userKey) {
    }

    record QaOutcome(String answer, Exception error) {
    }

    private void handleMessageFullOutput(IncomingMessage msg, QaAttach attach,
            FullOutputProgressSender streamer) throws Exception {
        String streamId;
        try {
            streamId = streamer.startStream(msg);
        } catch (Exception e) {
            log.warn("[IM] StartStream failed for full output, falling back: {}", e.getMessage());
            runFallbackNonStream(attach);
            return;
        }
        QaOutcome outcome = runQA(attach);
        String answer = outcome.answer();
        if (outcome.error() != null) {
            log.error("[IM] Full-output QA failed: {}, sending fallback reply", outcome.error());
            answer = ImFormat.imQAFailureReply(outcome.error());
        }
        String finalContent = formatIMOutboundAnswerOrFallback(answer);
        Exception finalizeErr = null;
        try {
            streamer.finalizeStream(msg, streamId, finalContent);
        } catch (Exception e) {
            finalizeErr = e;
            log.warn("[IM] FinalizeStream failed for full output: {}", e.getMessage());
        }
        try {
            streamer.endStream(msg, streamId);
        } catch (Exception e) {
            log.warn("[IM] EndStream failed for full output: {}", e.getMessage());
        }
        if (finalizeErr != null) {
            sendReplyQuiet(attach.adapter(), msg, new ReplyMessage(finalContent, false, true));
        }
    }

    private void runFallbackNonStream(QaAttach attach) {
        QaOutcome outcome = runQA(attach);
        String answer = outcome.answer();
        if (outcome.error() != null) {
            answer = ImFormat.imQAFailureReply(outcome.error());
        }
        String display = formatIMOutboundAnswerOrFallback(answer);
        sendReplyQuiet(attach.adapter(), attach.msg(), new ReplyMessage(display, false, true));
    }

    // ── runQA（service.go L2924-3115）：事件收集 + 消息落库 ────────────────

    QaOutcome runQA(QaAttach attach) {
        EventBus eventBus = new EventBus();
        StringBuilder answerBuilder = new StringBuilder();
        AtomicReference<Exception> qaErr = new AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch complete = new java.util.concurrent.CountDownLatch(1);

        eventBus.on(EventType.EVENT_AGENT_FINAL_ANSWER, evt -> {
            if (!(evt.getData() instanceof com.ragagent.event.payload.AgentFinalAnswerData data)) {
                return;
            }
            String content = data.getContent();
            if (content != null && !content.isEmpty()) {
                synchronized (answerBuilder) {
                    answerBuilder.append(content);
                }
            }
            if (data.isDone()) {
                done.countDown();
            }
        });
        eventBus.on(EventType.EVENT_ERROR, evt -> {
            if (!(evt.getData() instanceof com.ragagent.event.payload.ErrorData data)) {
                return;
            }
            log.error("[IM] QA error: {}", data.getError());
            qaErr.set(new RuntimeException("QA pipeline error: " + data.getError()));
            done.countDown();
            complete.countDown();
        });
        eventBus.on(EventType.EVENT_MCP_OAUTH_REQUIRED, evt -> {
            // OAuth 待授权：IM 无法处理会话内提示 → 汇总成文末提示（γ2 精简为日志备案）。
            log.info("[IM] MCP OAuth required: {}", evt.getData());
        });

        CustomAgentEntity agent = attach.agent();
        Session session = attach.session();
        String requestId = UUID.randomUUID().toString();

        Message userMsg = createUserMessage(session.getId(), attach.msg().content, requestId);
        Message assistantMsg = createAssistantMessage(session.getId(), requestId);

        eventBus.on(EventType.EVENT_AGENT_COMPLETE, evt -> {
            if (!(evt.getData() instanceof com.ragagent.event.payload.AgentCompleteData data)) {
                return;
            }
            String finalAnswer = data.getFinalAnswer();
            if (finalAnswer != null && !finalAnswer.isEmpty()) {
                synchronized (answerBuilder) {
                    if (answerBuilder.isEmpty()) {
                        answerBuilder.append(finalAnswer);
                    }
                }
            }
            assistantMsg.setCompleted(true);
            complete.countDown();
        });

        // 租户上下文：IM 回调无 JWT——显式注入合成身份（Go withIMIdentity 的
        // "system-<tenantID>" 对应 TenantContext.IM_USER principal）。
        long imTenant = attach.channel().getTenantId();
        TenantContext.set(imTenant, new TenantContext.Principal(
                TenantContext.PrincipalTypes.IM_USER, "system-" + imTenant), "viewer",
                false, "system-" + imTenant, false);
        try {
            QaSupport.QaRequest qaReq = buildIMQARequest(session, attach.msg().content,
                    assistantMsg.getId(), userMsg.getId(), agent, attach.msg().quote);
            // 同步执行（Go 是 goroutine + select 等待；Java 侧 QA 服务内部为
            // 虚拟线程管线，事件经 eventBus 回流到上面的订阅）。
            Exception runErr;
            try {
                if (agent != null && isAgentMode(agent)) {
                    agentQaService.agentQA(qaReq, eventBus);
                } else {
                    knowledgeQaService.knowledgeQA(qaReq, eventBus);
                }
                runErr = null;
            } catch (Exception e) {
                runErr = e;
            }
            if (runErr != null) {
                qaErr.set(new RuntimeException("QA execution error: " + runErr.getMessage(), runErr));
                done.countDown();
                complete.countDown();
            } else {
                try {
                    // 等待最终帧（对照 select done / waitForIMAgentComplete）。
                    if (!done.await(10, java.util.concurrent.TimeUnit.MINUTES)) {
                        qaErr.compareAndSet(null, new java.util.concurrent.TimeoutException("IM QA wait"));
                    }
                    if (isAgentMode(agent)) {
                        complete.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            TenantContext.clear();
        }

        String answer;
        synchronized (answerBuilder) {
            answer = answerBuilder.toString();
        }
        Exception err = qaErr.get();
        if (answer.isEmpty() && err != null) {
            return new QaOutcome("", err);
        }
        if (answer.isEmpty()) {
            answer = ImFormat.IM_NO_ANSWER_FALLBACK;
        }
        assistantMsg.setContent(answer);
        assistantMsg.setCompleted(true);
        try {
            messageService.updateMessage(assistantMsg);
        } catch (Exception e) {
            log.warn("[IM] Failed to update assistant message: {}", e.getMessage());
        }
        return new QaOutcome(answer, null);
    }

    private static boolean isAgentMode(CustomAgentEntity agent) {
        // 对照 CustomAgent.IsAgentMode：Config.AgentMode == "smart-reasoning"
        if (agent == null || agent.getConfig() == null || agent.getConfig().isEmpty()) {
            return false;
        }
        try {
            JsonNode cfg = JSON.readTree(agent.getConfig());
            return "smart-reasoning".equals(cfg.path("agent_mode").asText(""));
        } catch (Exception e) {
            return false;
        }
    }

    /** 对照 buildIMQARequest（service.go L528-558）。 */
    private QaSupport.QaRequest buildIMQARequest(Session session, String query,
            String assistantMessageId, String userMessageId, CustomAgentEntity agent,
            IncomingMessage.QuotedMessage quote) {
        QaSupport.QaRequest req = new QaSupport.QaRequest();
        req.session = session;
        req.query = query;
        req.assistantMessageId = assistantMessageId;
        req.userMessageId = userMessageId;
        req.agentRow = agent;
        if (agent != null && agent.getConfig() != null && !agent.getConfig().isEmpty()) {
            try {
                req.agentConfig = (com.fasterxml.jackson.databind.node.ObjectNode)
                        JSON.readTree(agent.getConfig());
                AgentConfigJson.ensureDefaults(req.agentConfig);
            } catch (Exception ignored) {
                req.agentConfig = null;
            }
        }
        req.webSearchEnabled = agent != null && req.agentConfig != null
                && req.agentConfig.path("web_search_enabled").asBoolean(false);
        req.quotedContext = ImFormat.formatQuotedContext(quote);

        return req;
    }

    /** 对照 createIMUserMessagePayload（service.go L580-595）。 */
    private Message createUserMessage(String sessionId, String content, String requestId) {
        Message m = new Message();
        m.setSessionId(sessionId);
        m.setRole("user");
        m.setContent(content);
        m.setRequestId(requestId);
        m.setCompleted(true);
        m.setChannel("im");
        return messageService.createMessage(m);
    }

    /** 对照 createIMAssistantMessagePayload（service.go L700-709）。 */
    private Message createAssistantMessage(String sessionId, String requestId) {
        Message m = new Message();
        m.setSessionId(sessionId);
        m.setRole("assistant");
        m.setRequestId(requestId);
        m.setChannel("im");
        return messageService.createMessage(m);
    }

    // ── 出站内容整形（service.go L144-201） ──────────────────────────────

    private String cleanIMContent(String content) {
        content = ImFormat.stripImageXMLTags(content);
        content = ImFormat.stripImCitationTags(content);
        if (storageResolver != null) {
            content = new com.ragagent.storage.support.Rewriter(storageResolver, "IM")
                    .rewrite(content);
        }
        return content;
    }

    String formatIMOutboundAnswerOrFallback(String raw) {
        String content = cleanIMContent(ThinkDisplay.formatIMDisplayContent(raw,
                ThinkDisplay.STREAM_DISPLAY_FINAL));
        if (content.strip().isEmpty()) {
            return ImFormat.IM_NO_ANSWER_FALLBACK;
        }
        return content;
    }

    // ── handleMessageStream（service.go L2482-2907） ─────────────────────

    private void handleMessageStream(IncomingMessage msg, QaAttach attach,
            StreamSender streamer) throws Exception {
        String streamId;
        try {
            streamId = streamer.startStream(msg);
        } catch (Exception e) {
            log.warn("[IM] StartStream failed, falling back to non-streaming: {}", e.getMessage());
            runFallbackNonStream(attach);
            return;
        }

        EventBus eventBus = new EventBus();
        StreamBuffers buf = new StreamBuffers();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch complete = new java.util.concurrent.CountDownLatch(1);

        CustomAgentEntity agent = attach.agent();
        boolean useAgent = isAgentMode(agent);

        subscribeStreamEvents(eventBus, buf, done, complete, useAgent);

        Session session = attach.session();
        String requestId = UUID.randomUUID().toString();
        Message userMsg = createUserMessage(session.getId(), msg.content, requestId);
        Message assistantMsg = createAssistantMessage(session.getId(), requestId);
        buf.assistantMessage = assistantMsg;

        // 租户上下文（同 runQA）。
        long tenantId = attach.channel().getTenantId();
        TenantContext.set(tenantId, new TenantContext.Principal(
                TenantContext.PrincipalTypes.IM_USER, "system-" + tenantId), "viewer",
                false, "system-" + tenantId, false);
        try {
            QaSupport.QaRequest qaReq = buildIMQARequest(session, msg.content,
                    assistantMsg.getId(), userMsg.getId(), agent, msg.quote);
            Exception runErr;
            try {
                if (useAgent) {
                    agentQaService.agentQA(qaReq, eventBus);
                } else {
                    knowledgeQaService.knowledgeQA(qaReq, eventBus);
                }
                runErr = null;
            } catch (Exception e) {
                runErr = e;
            }
            if (runErr != null) {
                log.error("[IM] QA stream execution error: {}", runErr.getMessage(), runErr);
                buf.qaErr = new RuntimeException("QA execution error: " + runErr.getMessage(), runErr);
                done.countDown();
                complete.countDown();
            } else {
                // 冲刷循环：300ms 批量把缓冲推给平台（holdback 防半个引用/标签）。
                long flushInterval = 300;
                long deadlineWait = java.util.concurrent.TimeUnit.MINUTES.toMillis(10);
                long start = System.currentTimeMillis();
                while (true) {
                    if (done.await(flushInterval, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        break;
                    }
                    flushStream(msg, streamer, streamId, buf, useAgent);
                    if (System.currentTimeMillis() - start > deadlineWait) {
                        break;
                    }
                }
                if (useAgent) {
                    complete.await(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            TenantContext.clear();
        }

        String resolvedAnswer = buf.pickStoredAnswer();
        ThinkDisplay.IMStreamParts parts = buf.parts(useAgent);
        if (parts.answer.isEmpty()) {
            parts.answer = resolvedAnswer;
        }
        String answer = resolvedAnswer;
        Exception finalErr = buf.qaErr;
        boolean noVisibleContent = !buf.streamedAny && resolvedAnswer.strip().isEmpty();

        String finalDisplay = cleanIMContent(ThinkDisplay.formatIMFinalFromParts(parts));
        if (noVisibleContent || finalDisplay.isEmpty()) {
            String fallback = finalErr != null ? ImFormat.imQAFailureReply(finalErr)
                    : ImFormat.IM_NO_ANSWER_FALLBACK;
            finalDisplay = fallback;
            if (answer.isEmpty()) {
                answer = fallback;
            }
        }

        try {
            streamer.finalizeStream(msg, streamId, finalDisplay);
        } catch (Exception e) {
            log.warn("[IM] FinalizeStream failed: {}", e.getMessage());
        }
        try {
            streamer.endStream(msg, streamId);
        } catch (Exception e) {
            log.warn("[IM] EndStream failed: {}", e.getMessage());
        }
        if (answer.isEmpty()) {
            answer = ImFormat.IM_NO_ANSWER_FALLBACK;
        }
        assistantMsg.setContent(answer);
        assistantMsg.setCompleted(true);
        try {
            messageService.updateMessage(assistantMsg);
        } catch (Exception e) {
            log.warn("[IM] Failed to update assistant message: {}", e.getMessage());
        }
        log.info("[IM] Stream reply sent: platform={} user={} answer_len={}",
                msg.platform, msg.userId, answer.length());
    }

    /** 流缓冲袋（对照 handleMessageStream 的局部变量组，service.go L2501-2542）。 */
    static final class StreamBuffers {
        final StreamSection reasoningInner = new StreamSection();
        final StreamSection agentInner = new StreamSection();
        final StringBuilder agentLiveAnswer = new StringBuilder();
        final StringBuilder answerOuter = new StringBuilder();
        final StringBuilder answerBuilder = new StringBuilder();
        Exception qaErr;
        boolean agentDone;
        boolean streamedAny;
        Message assistantMessage;
        String agentCompleteFinalAnswer = "";
        final Map<String, Boolean> seenToolCalls = new ConcurrentHashMap<>();
        final Map<String, Integer> agentToolIdx = new ConcurrentHashMap<>();
        final Map<String, Integer> pipelineIdx = new ConcurrentHashMap<>();
        final List<ToolDisplay.IMToolStep> agentToolSteps = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<ToolDisplay.IMToolStep> pipelineToolSteps = java.util.Collections.synchronizedList(new ArrayList<>());

        synchronized ThinkDisplay.IMStreamParts parts(boolean useAgent) {
            ThinkDisplay.IMStreamParts p = new ThinkDisplay.IMStreamParts();
            p.mode = useAgent ? ThinkDisplay.IM_STREAM_MODE_AGENT
                    : ThinkDisplay.IM_STREAM_MODE_QUICK_QA;
            p.pipelineToolSteps = List.copyOf(pipelineToolSteps);
            p.reasoningInner = reasoningInner.text();
            p.agentInner = agentInner.text();
            p.agentToolSteps = List.copyOf(agentToolSteps);
            p.liveAnswer = agentLiveAnswer.toString();
            p.answer = answerOuter.toString();
            return p;
        }

        synchronized String pickStoredAnswer() {
            for (String s : List.of(answerBuilder.toString(), answerOuter.toString(),
                    agentLiveAnswer.toString(), agentCompleteFinalAnswer)) {
                if (!s.strip().isEmpty()) {
                    return s;
                }
            }
            return "";
        }

        synchronized void mergeBuffers(String completeFinal) {
            if (!answerBuilder.isEmpty()) {
                return;
            }
            if (!agentLiveAnswer.isEmpty()) {
                String live = agentLiveAnswer.toString();
                answerBuilder.append(live);
                if (answerOuter.isEmpty()) {
                    answerOuter.append(live);
                }
            } else if (!answerOuter.isEmpty()) {
                answerBuilder.append(answerOuter);
            } else if (!completeFinal.strip().isEmpty()) {
                answerBuilder.append(completeFinal);
                answerOuter.append(completeFinal);
            }
        }
    }

    /** 事件订阅组（对照 service.go L2557-2740 的一串 On）。 */
    private void subscribeStreamEvents(EventBus eventBus, StreamBuffers buf,
            java.util.concurrent.CountDownLatch done,
            java.util.concurrent.CountDownLatch complete, boolean useAgent) {
        eventBus.on(EventType.EVENT_AGENT_FINAL_ANSWER, evt -> {
            if (!(evt.getData() instanceof com.ragagent.event.payload.AgentFinalAnswerData data)) {
                return;
            }
            String content = data.getContent();
            synchronized (buf) {
                if (useAgent && !buf.agentDone) {
                    if (content != null && !content.isEmpty()) {
                        buf.agentLiveAnswer.append(content);
                        buf.streamedAny = true;
                    }
                } else if (content != null) {
                    buf.answerOuter.append(content);
                    buf.answerBuilder.append(content);
                    buf.streamedAny = true;
                }
            }
            if (data.isDone()) {
                done.countDown();
            }
        });
        eventBus.on(EventType.EVENT_ERROR, evt -> {
            String text = evt.getData() instanceof com.ragagent.event.payload.ErrorData d
                    ? d.getError() : String.valueOf(evt.getData());
            synchronized (buf) {
                buf.qaErr = new RuntimeException("QA pipeline error: " + text);
            }
            done.countDown();
            complete.countDown();
        });
        eventBus.on(EventType.EVENT_AGENT_COMPLETE, evt -> {
            if (!(evt.getData() instanceof com.ragagent.event.payload.AgentCompleteData data)) {
                return;
            }
            synchronized (buf) {
                buf.agentDone = true;
                buf.agentCompleteFinalAnswer = data.getFinalAnswer() == null ? "" : data.getFinalAnswer();
                if (buf.assistantMessage != null) {
                    buf.assistantMessage.setCompleted(true);
                }
                buf.mergeBuffers(buf.agentCompleteFinalAnswer);
            }
            complete.countDown();
        });
        eventBus.on(EventType.EVENT_AGENT_REFERENCES, evt -> {
            // 引用进 assistant 消息（web 端的交互 UI 在 IM 无意义，不入最终文本）。
        });
        eventBus.on(EventType.EVENT_AGENT_THOUGHT, evt -> {
            String content = evt.getData() instanceof com.ragagent.event.payload.AgentThoughtData d
                    && d.getContent() != null ? d.getContent() : "";
            synchronized (buf) {
                if (content.isEmpty()) {
                    return;
                }
                if (useAgent) {
                    buf.agentInner.write(content);
                    buf.streamedAny = true;
                } else {
                    buf.reasoningInner.write(content);
                    buf.streamedAny = true;
                }
            }
        });
        eventBus.on(EventType.EVENT_AGENT_TOOL_CALL, evt -> {
            ToolEvent t = toolOf(evt);
            if (t == null || !ImFormat.isToolVisibleToUser(t.toolName)) {
                return;
            }
            synchronized (buf) {
                if (buf.seenToolCalls.putIfAbsent(t.toolCallId, Boolean.TRUE) != null) {
                    if (useAgent) {
                        upsert(buf.agentToolSteps, buf.agentToolIdx, t.toolCallId, step -> {
                            if (t.arguments != null) {
                                step.arguments = t.arguments;
                            }
                        });
                        buf.streamedAny = true;
                    }
                    return;
                }
                if (!useAgent && ThinkDisplay.isRAGPipelineToolName(t.toolName)) {
                    upsert(buf.pipelineToolSteps, buf.pipelineIdx, t.toolCallId, step -> {
                        step.toolName = t.toolName;
                        step.pending = true;
                        step.arguments = t.arguments;
                    });
                    buf.streamedAny = true;
                } else if (useAgent) {
                    // 乐观答案收回 think 块（Web: superseded preamble）。
                    if (!buf.agentLiveAnswer.isEmpty()) {
                        if (!buf.agentInner.text().isEmpty()) {
                            buf.agentInner.ensureNewlineBefore();
                        }
                        buf.agentInner.write(buf.agentLiveAnswer.toString());
                        buf.agentLiveAnswer.setLength(0);
                    }
                    upsert(buf.agentToolSteps, buf.agentToolIdx, t.toolCallId, step -> {
                        step.toolName = t.toolName;
                        step.pending = true;
                        step.arguments = t.arguments;
                    });
                    buf.streamedAny = true;
                }
            }
        });
        eventBus.on(EventType.EVENT_AGENT_TOOL_RESULT, evt -> {
            ToolEvent t = toolOf(evt);
            if (t == null || !ImFormat.isToolVisibleToUser(t.toolName)) {
                return;
            }
            synchronized (buf) {
                if (!useAgent && ThinkDisplay.isRAGPipelineToolName(t.toolName)) {
                    upsert(buf.pipelineToolSteps, buf.pipelineIdx, t.toolCallId, step -> {
                        step.toolName = t.toolName;
                        step.pending = false;
                        step.success = t.success;
                        step.data = t.data;
                        step.output = t.output;
                    });
                    buf.streamedAny = true;
                } else if (useAgent) {
                    upsert(buf.agentToolSteps, buf.agentToolIdx, t.toolCallId, step -> {
                        step.toolName = t.toolName;
                        step.pending = false;
                        step.success = t.success;
                        step.data = t.data;
                        step.output = t.output;
                    });
                    buf.streamedAny = true;
                }
            }
        });
    }

    /** 工具事件的统一视图（event.Data 的字段面）。 */
    private record ToolEvent(String toolCallId, String toolName, boolean success,
            Map<String, Object> arguments, Map<String, Object> data, String output) {
    }

    private static ToolEvent toolOf(Event evt) {
        Object d = evt.getData();
        if (d instanceof com.ragagent.event.payload.AgentToolCallData c) {
            return new ToolEvent(c.getToolCallId(), c.getToolName(), false,
                    c.getArguments(), null, "");
        }
        if (d instanceof com.ragagent.event.payload.AgentToolResultData r) {
            return new ToolEvent(r.getToolCallId(), r.getToolName(), r.isSuccess(),
                    null, r.getData(), r.getOutput() == null ? "" : r.getOutput());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** 对照 upsertIMToolStep（tool_display.go L763-772）。 */
    private static void upsert(List<ToolDisplay.IMToolStep> steps, Map<String, Integer> index,
            String id, java.util.function.Consumer<ToolDisplay.IMToolStep> update) {
        Integer i = index.get(id);
        if (i != null) {
            update.accept(steps.get(i));
            return;
        }
        ToolDisplay.IMToolStep step = new ToolDisplay.IMToolStep(id, "");
        update.accept(step);
        index.put(id, steps.size());
        steps.add(step);
    }

    /** 冲刷一次中间帧（对照 flush 闭包，service.go L2786-2806）。 */
    private void flushStream(IncomingMessage msg, StreamSender streamer, String streamId,
            StreamBuffers buf, boolean useAgent) {
        ThinkDisplay.IMStreamParts parts;
        boolean agentRunning;
        synchronized (buf) {
            parts = buf.parts(useAgent);
            agentRunning = useAgent && !buf.agentDone;
        }
        String displaySource = ThinkDisplay.formatIMIntermediateFromParts(parts, agentRunning);
        if (displaySource.isEmpty()) {
            return;
        }
        int cut = ImFormat.holdbackCutoff(displaySource);
        if (cut < displaySource.length()) {
            displaySource = displaySource.substring(0, cut);
        }
        String display = cleanIMContent(displaySource);
        try {
            streamer.updateStreamContent(msg, streamId, display);
        } catch (Exception e) {
            log.warn("[IM] UpdateStreamContent failed: {}", e.getMessage());
        }
    }

    private void sendReplyQuiet(Adapter adapter, IncomingMessage msg, ReplyMessage reply) {
        try {
            adapter.sendReply(msg, reply);
        } catch (Exception e) {
            log.warn("[IM] Send reply failed: {}", e.getMessage());
        }
    }

}
