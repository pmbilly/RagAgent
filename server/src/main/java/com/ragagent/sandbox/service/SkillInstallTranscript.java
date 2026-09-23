package com.ragagent.sandbox.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ragagent.agent.domain.AgentStep;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.ToolResultPersist;
import com.ragagent.event.AgentCompleteData;
import com.ragagent.event.AgentFinalAnswerData;
import com.ragagent.event.AgentThoughtData;
import com.ragagent.event.AgentToolCallData;
import com.ragagent.event.AgentToolResultData;
import com.ragagent.event.CommandOutputData;
import com.ragagent.event.ErrorData;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.EventType;
import com.ragagent.llm.domain.ResponseType;
import com.ragagent.session.domain.Message;
import com.ragagent.session.mapper.MessageRepository;
import com.ragagent.stream.StreamEvent;
import com.ragagent.stream.StreamManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次 installer 会话的转写（对照 Go {@code installTranscript}，
 * internal/application/service/tenant_skill_transcript.go 全文逐行翻译）。
 *
 * <p>它是 installer 对 handler/session.AgentStreamHandler 的<b>对应物</b>而非复制品：
 * 后者活在 service 层之上（需要 artifact collector），在这里引入它会构成 import 环。
 * 重叠面是有意收窄的——install mode 只注册 shell_exec，所以下面这六个事件就是一次
 * 安装能产生的全部；references、memories、reflection、tool approvals 与 MCP OAuth
 * 均不可达（Go 注释原文）。事件形状刻意对齐 AgentStreamHandler，控制台才能用渲染
 * 聊天轮次的同一批组件渲染一次安装。</p>
 *
 * <h2>持久化面（任务书偏差备案）</h2>
 * <p>任务书断言存在 {@code skill_install_transcript} 表——Go 全仓无此表（仅 CHANGELOG
 * 提及字符串）。按 known-issues/04 的纪律（「任务书会写错，Go 源才是准绳」），持久化
 * 走两条既有通道，不建新表、不建新实体：</p>
 * <ul>
 *   <li><b>事件日志</b>：{@link StreamManager#appendEvent} → Redis 流
 *       （transcript SSE 端点的回放源，TTL 过期后 404）；</li>
 *   <li><b>durable 消息</b>：{@code messages} 表的 user/assistant 两行——
 *       Go 经 {@code interfaces.MessageRepository}（裸仓储，非带租户校验的 service）；
 *       Java 对应 {@link MessageRepository}。</li>
 * </ul>
 *
 * <h2>Go 既有行为的逐字照抄（勿「修好」）</h2>
 * <ul>
 *   <li>Go 的 {@code Message.BeforeCreate}（message.go L463-464）<b>无条件</b>用新
 *       UUID 覆盖传入的 ID——assistant 行落库后的真实 ID ≠ {@code assistantMessageId}。
 *       Java 的 {@code MessageRepository.create} 同样无条件覆写 ID（同一行为）。
 *       {@code save()} 的 UPDATE 以内存对象（被覆写后的 ID）为目标，因此仍能命中；
 *       与 Go 的指针语义一致。</li>
 *   <li>{@code onComplete} 刻意不发终帧：一次安装可能在验证后再跑一轮 installer，
 *       看到 "complete" 的控制台会在那一轮开始前停止跟随。只有 {@link #finish}
 *       关流（Go 注释原文）。</li>
 * </ul>
 */
public class SkillInstallTranscript {

    private static final Logger log = LoggerFactory.getLogger(SkillInstallTranscript.class);

    /**
     * 渐近进度回调（对照 Go 的 {@code onActivity func(steps int, lastCmd string)}）。
     * 只有 install 路径接线；remove 路径与多数测试留 null，什么都不发布。
     */
    @FunctionalInterface
    public interface InstallActivityListener {
        void onActivity(int steps, String lastCmd);
    }

    /** 进度卡单行日志的 rune 上限（对照 {@code progressLogMaxRunes}）。卡片一行高；heredoc 会压扁它。 */
    private static final int PROGRESS_LOG_MAX_RUNES = 80;

    private final EventBus bus;
    private final StreamManager streams;
    private final MessageRepository messages;

    private final String sessionId;
    private final String assistantMessageId;
    private final InstallActivityListener onActivity;

    /** 对照 Go 的 {@code mu sync.Mutex}；护住 message/answers/starts/计数/closed。 */
    private final Object mu = new Object();
    private Message message;
    private final List<AnswerSegment> answers = new ArrayList<>();
    /** tool_call_id → 开始时刻（nanoTime；Go 是 time.Time，只用来算差值）。 */
    private final Map<String, Long> starts = new HashMap<>();
    /** 终端事件与最终写都只许说一次 "本次安装结束了"。 */
    private boolean closed;
    /** 跨引擎轮次累计的总量，由 finish 发出的唯一终端事件上报。 */
    private int totalSteps;
    private long totalDurationMs;
    private int toolCalls;
    private boolean progressMuted;

    /** 有一条 final-answer 流水的一个轮次的散文（对照 {@code installAnswerSegment}）。 */
    private static final class AnswerSegment {
        final String id;
        String content = "";
        boolean superseded;

        AnswerSegment(String id) {
            this.id = id;
        }
    }

    public SkillInstallTranscript(EventBus bus, StreamManager streams, MessageRepository messages,
            String sessionId, String assistantMessageId, InstallActivityListener onActivity) {
        this.bus = bus;
        this.streams = streams;
        this.messages = messages;
        this.sessionId = sessionId;
        this.assistantMessageId = assistantMessageId;
        this.onActivity = onActivity;
    }

    /**
     * 对照 {@code Create}：引擎启动前写出会话需要的两行。assistant 行不能等到 run
     * 结束——/sessions/continue-stream 在开流前校验消息，否则在安装进行中附着的
     * 控制台会被拒绝（Go 注释原文）。
     *
     * <p>对照 Go 返回 error 的形态：失败抛 {@link IllegalStateException}（error 通道
     * 折叠，§1），消息保留 Go 的 wrap 前缀。</p>
     */
    public void create(String prompt) {
        if (messages == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        try {
            messages.create(userRow(UUID.randomUUID().toString(), prompt, now));
        } catch (RuntimeException e) {
            throw new IllegalStateException("create installer prompt message: " + e.getMessage(), e);
        }
        Message assistant = new Message();
        assistant.setId(assistantMessageId);
        assistant.setSessionId(sessionId);
        assistant.setRole(Message.ROLE_ASSISTANT);
        assistant.setCreatedAt(now.plusNanos(1_000_000));
        assistant.setUpdatedAt(now.plusNanos(1_000_000));
        try {
            messages.create(assistant);
        } catch (RuntimeException e) {
            throw new IllegalStateException("create installer answer message: " + e.getMessage(), e);
        }
        synchronized (mu) {
            // create() 无条件覆写 ID（Go BeforeCreate 同）——内存对象带着真实行 ID，
            // save() 的 UPDATE 因此能命中；与 Go 的指针语义逐字一致。
            message = assistant;
        }

        // prompt 也进事件日志，且先于 agent 的一切动作——回放一次日志就是整段对话。
        // 没有它，跟随运行中安装的控制台会看到一段自己看不见开场的对话（Go 注释原文）。
        appendEvent(promptEvent(prompt, now));
    }

    /** 接线一次安装能产生的全部六类事件（对照 {@code Subscribe}）。 */
    public void subscribe() {
        if (bus == null) {
            return;
        }
        bus.on(EventType.EVENT_AGENT_THOUGHT, this::onThought);
        bus.on(EventType.EVENT_AGENT_TOOL_CALL, this::onToolCall);
        bus.on(EventType.EVENT_AGENT_COMMAND_OUTPUT, this::onInstallOutput);
        bus.on(EventType.EVENT_AGENT_TOOL_RESULT, this::onToolResult);
        bus.on(EventType.EVENT_AGENT_FINAL_ANSWER, this::onAnswer);
        bus.on(EventType.EVENT_ERROR, this::onError);
        bus.on(EventType.EVENT_AGENT_COMPLETE, this::onComplete);
    }

    /**
     * 对照 {@code Finish}：关闭记录。runErr 是本次安装的裁决而不只是引擎的——验证在
     * agent 停止之后运行，那里的失败才是人们真正来读的东西，所以写在这里（Go 注释原文）。
     */
    public void finish(String runErr) {
        synchronized (mu) {
            if (closed) {
                return;
            }
            closed = true;
        }
        if (runErr != null && !runErr.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("stage", "install");
            data.put("error", runErr);
            StreamEvent evt = new StreamEvent(UUID.randomUUID().toString(), ResponseType.ERROR,
                    runErr, true);
            evt.setTimestamp(OffsetDateTime.now());
            evt.setData(data);
            appendEvent(evt);

            synchronized (mu) {
                // 裁决永远不是 preamble，所以它拿一段后继调用无法撤回的独立段（Go 注释原文）。
                AnswerSegment failure = segmentLocked("install-failure");
                if (!composeAnswerLocked().isEmpty()) {
                    failure.content = "\n\n";
                }
                failure.content += runErr;
            }
        }

        int steps;
        long duration;
        synchronized (mu) {
            steps = totalSteps;
            duration = totalDurationMs;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total_steps", steps);
        data.put("total_duration_ms", duration);
        StreamEvent evt = new StreamEvent(UUID.randomUUID().toString(), ResponseType.COMPLETE,
                "", true);
        evt.setTimestamp(OffsetDateTime.now());
        evt.setData(data);
        appendEvent(evt);
        save();
    }

    /**
     * 对照 {@code RecordPrompt}：记录 installer 在 run 中途收到的一条补充指令。
     * 转写存在的意义就是回放一次日志等于整段对话——一条缺失指令的修复轮读起来就像
     * agent 自发决定多装几个包，而这恰恰是有人来读它的时刻（Go 注释原文）。
     */
    public void recordPrompt(String prompt) {
        appendEvent(promptEvent(prompt, OffsetDateTime.now()));
    }

    private static StreamEvent promptEvent(String prompt, OffsetDateTime now) {
        StreamEvent evt = new StreamEvent(UUID.randomUUID().toString(),
                ResponseType.INSTALL_PROMPT, prompt, true);
        evt.setTimestamp(now);
        evt.setData(new LinkedHashMap<>());
        return evt;
    }

    // ── 事件回调（Go 包私有方法 → Java 包可见，供单测直调） ───────────────

    void onThought(Event evt) {
        if (!(evt.getData() instanceof AgentThoughtData data)) {
            return;
        }
        StreamEvent out = new StreamEvent(evt.getId(), ResponseType.THINKING, data.getContent(),
                data.isDone());
        out.setTimestamp(OffsetDateTime.now());
        out.setData(spanMeta(evt.getId(), data.isDone()));
        appendEvent(out);
    }

    void onToolCall(Event evt) {
        if (!(evt.getData() instanceof AgentToolCallData data)) {
            return;
        }
        int steps;
        String lastCmd;
        synchronized (mu) {
            if (starts.containsKey(data.getToolCallId())) {
                return;
            }
            starts.put(data.getToolCallId(), System.nanoTime());
            // 这一轮调了工具，所以它不是结束本次 run 的那一轮：它流出的散文都是
            // preamble，不得进入 Message.Content（Go 注释原文）。
            for (AnswerSegment seg : answers) {
                if (!seg.superseded && !seg.content.isEmpty()) {
                    seg.superseded = true;
                }
            }
            // 一条命令就是渐近进度的一步。静音的 run（第一轮结束后）停止计数，
            // 修复轮不能把进度条拖回它那时受控的 stage 锚点之下（Go 注释原文）。
            steps = 0;
            lastCmd = "";
            if (!progressMuted) {
                toolCalls++;
                steps = toolCalls;
                lastCmd = installToolCallSummary(data);
            }
        }

        StreamEvent out = new StreamEvent(evt.getId(), ResponseType.TOOL_CALL,
                "Calling tool: " + data.getToolName(), false);
        out.setTimestamp(OffsetDateTime.now());
        Map<String, Object> dataMap = new LinkedHashMap<>();
        dataMap.put("tool_name", data.getToolName());
        dataMap.put("arguments", data.getArguments());
        dataMap.put("tool_call_id", data.getToolCallId());
        out.setData(dataMap);
        appendEvent(out);
        if (steps > 0 && onActivity != null) {
            onActivity.onActivity(steps, lastCmd);
        }
    }

    void onToolResult(Event evt) {
        if (!(evt.getData() instanceof AgentToolResultData data)) {
            return;
        }
        long durationMs;
        synchronized (mu) {
            durationMs = data.getDurationMs();
            Long start = starts.get(data.getToolCallId());
            if (start != null) {
                durationMs = (System.nanoTime() - start) / 1_000_000;
                starts.remove(data.getToolCallId());
            }
        }

        // 失败的命令以 error 形态露出，与 chat 路径一致，控制台才会高亮它而不是
        // 把它归档成又一步安静的进展（Go 注释原文）。
        ResponseType responseType = ResponseType.TOOL_RESULT;
        String content = ToolResultPersist.streamContentForToolResult(data.getToolName(),
                data.isSuccess(), data.getError(), data.getData());
        if (!data.isSuccess()) {
            responseType = ResponseType.ERROR;
            if (content.isEmpty() && !data.getError().isEmpty()) {
                content = data.getError();
            }
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tool_name", data.getToolName());
        meta.put("success", data.isSuccess());
        meta.put("error", data.getError());
        meta.put("duration_ms", durationMs);
        meta.put("tool_call_id", data.getToolCallId());
        ToolResult result = new ToolResult();
        result.setSuccess(data.isSuccess());
        result.setOutput(data.getOutput());
        result.setError(data.getError());
        result.setData(data.getData());
        meta.putAll(ToolResultPersist.sanitizeToolResultForClient(data.getToolName(), result));

        StreamEvent out = new StreamEvent(evt.getId(), responseType, content, false);
        out.setTimestamp(OffsetDateTime.now());
        out.setData(meta);
        appendEvent(out);
    }

    void onAnswer(Event evt) {
        if (!(evt.getData() instanceof AgentFinalAnswerData data)) {
            return;
        }
        synchronized (mu) {
            if (!data.getContent().isEmpty()) {
                segmentLocked(evt.getId()).content += data.getContent();
            }
        }

        StreamEvent out = new StreamEvent(evt.getId(), ResponseType.ANSWER, data.getContent(),
                data.isDone());
        out.setTimestamp(OffsetDateTime.now());
        out.setData(spanMeta(evt.getId(), data.isDone()));
        appendEvent(out);
    }

    void onError(Event evt) {
        if (!(evt.getData() instanceof ErrorData data)) {
            return;
        }
        StreamEvent out = new StreamEvent(evt.getId(), ResponseType.ERROR, data.getError(), true);
        out.setTimestamp(OffsetDateTime.now());
        Map<String, Object> dataMap = new LinkedHashMap<>();
        dataMap.put("stage", data.getStage());
        dataMap.put("error", data.getError());
        out.setData(dataMap);
        appendEvent(out);
    }

    /**
     * 对照 {@code onComplete}：记录这一轮以什么收尾。刻意不发终端事件（见类注释）。
     */
    void onComplete(Event evt) {
        if (!(evt.getData() instanceof AgentCompleteData data)) {
            return;
        }
        synchronized (mu) {
            if (assistantMessageId.equals(data.getMessageId())) {
                Message msg = ensureMessageLocked();
                msg.setCompleted(true);
                msg.setAgentDurationMs(data.getTotalDurationMs());
                if (data.getAgentSteps() instanceof List<?> raw) {
                    // Go 是 []types.AgentStep 的类型断言；Java 引擎侧装的就是
                    // List<AgentStep>，逐元素过滤是引用语义下最接近的等价物。
                    List<AgentStep> steps = new ArrayList<>(raw.size());
                    for (Object o : raw) {
                        if (o instanceof AgentStep s) {
                            steps.add(s);
                        }
                    }
                    msg.setAgentSteps(ToolResultPersist.sanitizeAgentStepsForStorage(steps));
                }
            }
            // 引擎可能从未流出过答案分片就以纯文本自然停止。从完成载荷取摘要，
            // 转写才不会留下一条空的最终消息（Go 注释原文）。
            if (composeAnswerLocked().isEmpty() && !data.getFinalAnswer().isEmpty()) {
                segmentLocked(evt.getId()).content = data.getFinalAnswer();
            }
            // 累加而非覆写：需要修复轮的安装跑过两个引擎轮次，两次的开销都是它的。
            totalSteps += data.getTotalSteps();
            totalDurationMs += data.getTotalDurationMs();
        }
    }

    void onInstallOutput(Event evt) {
        if (!(evt.getData() instanceof CommandOutputData data)) {
            return;
        }
        synchronized (mu) {
            if (closed) {
                return;
            }
        }
        StreamEvent out = new StreamEvent(evt.getId(), ResponseType.INSTALL_OUTPUT, "", false);
        out.setTimestamp(OffsetDateTime.now());
        Map<String, Object> dataMap = new LinkedHashMap<>();
        dataMap.put("tool_call_id", data.getToolCallId());
        dataMap.put("command", data.getCommand());
        dataMap.put("started_at", data.getStartedAt());
        dataMap.put("output", data.getOutput());
        dataMap.put("done", data.isDone());
        out.setData(dataMap);
        appendEvent(out);
    }

    // ── 内部面 ───────────────────────────────────────────────────────────

    /**
     * 对照 {@code spanMeta}：镜像 chat 路径的每分片元数据，控制台才能按事件 ID
     * 分组、并在 span 关闭时展示耗时。
     */
    private Map<String, Object> spanMeta(String eventId, boolean done) {
        synchronized (mu) {
            starts.putIfAbsent(eventId, System.nanoTime());
            if (!done) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("event_id", eventId);
                return meta;
            }
            Long start = starts.remove(eventId);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("event_id", eventId);
            meta.put("duration_ms", start == null ? 0L : (System.nanoTime() - start) / 1_000_000);
            meta.put("completed_at", OffsetDateTime.now().toEpochSecond());
            return meta;
        }
    }

    /** 对照 {@code segmentLocked}：取（或建） accumulating 一个答案事件 ID 的段。调用方必须持有 mu。 */
    private AnswerSegment segmentLocked(String id) {
        for (AnswerSegment seg : answers) {
            if (seg.id.equals(id)) {
                return seg;
            }
        }
        AnswerSegment seg = new AnswerSegment(id);
        answers.add(seg);
        return seg;
    }

    /** 对照 {@code composeAnswerLocked}：按到达序重建未被工具调用撤回的答案段。调用方必须持有 mu。 */
    private String composeAnswerLocked() {
        StringBuilder b = new StringBuilder();
        for (AnswerSegment seg : answers) {
            if (!seg.superseded) {
                b.append(seg.content);
            }
        }
        return b.toString();
    }

    /** 对照 {@code append}：尽力而为——事件日志写失败只降级为日志行。 */
    private void appendEvent(StreamEvent evt) {
        if (streams == null) {
            return;
        }
        try {
            streams.appendEvent(sessionId, assistantMessageId, evt);
        } catch (RuntimeException e) {
            log.warn("[skill] append {} to install transcript {} failed: {}",
                    evt.getType(), sessionId, e.getMessage());
        }
    }

    /** 对照 {@code ensureMessageLocked}：Create 没跑（播种失败）时也尽量记录。调用方必须持有 mu。 */
    private Message ensureMessageLocked() {
        if (message == null) {
            message = new Message();
            message.setId(assistantMessageId);
            message.setSessionId(sessionId);
            message.setRole(Message.ROLE_ASSISTANT);
            message.setCreatedAt(OffsetDateTime.now());
        }
        return message;
    }

    /** 对照 {@code save}：把重建出的答案写回 assistant 行。 */
    private void save() {
        if (messages == null) {
            return;
        }
        Message msg;
        synchronized (mu) {
            msg = ensureMessageLocked();
            msg.setContent(composeAnswerLocked());
            msg.setCompleted(true);
            msg.setUpdatedAt(OffsetDateTime.now());
        }
        try {
            messages.update(msg);
        } catch (RuntimeException e) {
            log.warn("[skill] persist install transcript {} failed: {}", sessionId, e.getMessage());
        }
    }

    private static Message userRow(String id, String prompt, OffsetDateTime now) {
        Message m = new Message();
        m.setId(id);
        m.setRole(Message.ROLE_USER);
        m.setContent(prompt);
        m.setCompleted(true);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        return m;
    }

    /** InstallSteerSink（closeIfDrained）读的定位符；包可见，同 Go 的 tr.sessionID 直读。 */
    String sessionIdForSink() {
        return sessionId;
    }

    String assistantMessageIdForSink() {
        return assistantMessageId;
    }

    // ── 渐近进度（asymptoticInstallPercent） ─────────────────────────────

    /**
     * 对照 {@code muteActivityProgress}：渐近进度停在其到达处。第一轮 installer 结束后
     * 调用一次——其后的一切（验证与修复轮）由显式 stage 锚点（agent_done 80、
     * repairing 82）覆盖，修复轮再次发布会把进度条拖回锚点之下（Go 注释原文）。
     */
    public void muteActivityProgress() {
        synchronized (mu) {
            progressMuted = true;
        }
    }

    /**
     * 对照 {@code asymptoticInstallPercent}：在不知道总量的前提下填充 35→79 的区间。
     * 每条命令以剩余量的递减份额推进进度条，所以曲线对任意轮数都单调、收敛于
     * agent_done 锚点 80 之下——从不超过，也绝不中途停滞。
     *
     * <p>形状是 35 + 44·(1 − e^(−k/12))：第一条命令推进约 4 个点，第十条约 60%，
     * 第二十条约 71%；即便跑满 max_iterations=30 也只到约 75，仍低于 79 的天花板。</p>
     */
    public static int asymptoticInstallPercent(int k) {
        if (k <= 0) {
            return 35;
        }
        int p = 35 + (int) Math.round(44 * (1 - Math.exp(-k / 12.0)));
        if (p > 79) {
            return 79;
        }
        return p;
    }

    /**
     * 对照 {@code installToolCallSummary}：把一次工具调用压成进度卡展示的单行日志。
     * 引擎自带的提示（如 {@code shell_exec: uv venv .venv}）优先；否则用 shell 命令。
     */
    static String installToolCallSummary(AgentToolCallData data) {
        String summary = data.getHint() == null ? "" : data.getHint().trim();
        if (summary.isEmpty()) {
            Object command = data.getArguments() == null ? null : data.getArguments().get("command");
            if (command instanceof String cmd && !cmd.trim().isEmpty()) {
                summary = data.getToolName() + ": " + cmd.trim();
            } else {
                summary = data.getToolName();
            }
        }
        summary = summary.replace("\n", " ");
        long runes = summary.codePoints().count();
        if (runes > PROGRESS_LOG_MAX_RUNES) {
            int cut = summary.offsetByCodePoints(0, PROGRESS_LOG_MAX_RUNES);
            summary = summary.substring(0, cut) + "…";
        }
        return summary;
    }
}
