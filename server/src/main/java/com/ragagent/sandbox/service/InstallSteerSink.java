package com.ragagent.sandbox.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.agent.SteerSink;
import com.ragagent.session.domain.Message;
import com.ragagent.session.mapper.MessageRepository;
import com.ragagent.stream.StreamEvent;
import com.ragagent.stream.StreamManager;

/**
 * installer 引擎侧的 steer 注入通道（对照 Go {@code installSteerSink}，
 * internal/application/service/tenant_skill_steer.go L154-249 全文）。
 *
 * <p>实现既有引擎半边接口 {@link SteerSink}（波 4.6b 预留、4.6d 已定型）。
 * 由安装管线（后续批次）在维护会话的引擎循环内构造——它是<b>每 run 有状态</b>的
 * （guidance/err 只被引擎协程访问），不是 Spring bean。</p>
 *
 * <h2>Redis/存储键契约（跨语言，逐字）</h2>
 * <ul>
 *   <li>live-run 键：{@code TenantSkillService.installSteerSession(sessionId)} =
 *       {@code skill-install:<sessionId>}——独立命名空间，普通聊天的 steering 永远
 *       到不了 installer 的维护 shell（Go 注释原文）。</li>
 *   <li>steer 事件列表：普通 chat 的 steer 子列表（sessionID/messageID 两段键）；
 *       {@code consumed}/{@code user_message_id} 写回事件 data。</li>
 *   <li>user 消息行 ID：对照 Go {@code uuid.NewSHA1(uuid.NameSpaceOID, ...)} 的
 *       UUIDv5（SHA-1、OID 命名空间 {@code 6ba7b812-9dad-11d1-80b4-00c04fd430c8}）
 *       ——确定性 ID 让 consumed 写失败后的重试是安全的。</li>
 * </ul>
 *
 * <h2>Go 既有行为的逐字照抄（勿「修好」）</h2>
 * <p>Go 的 {@code Message.BeforeCreate} 在 Create 时<b>无条件</b>覆写传入 ID（Java
 * {@code MessageRepository.create} 同），因此落库行的真实 ID 不是本方法返回的
 * 确定性 ID——返回值与 {@code user_message_id} 引用的是「逻辑 ID」。Go 如此，
 * 逐字保留（known-issues/04：golden 钉的是行为不是意图）。</p>
 *
 * <h2>错误通道</h2>
 * <p>Go 的 {@code sink.err} 字段 + error 返回值折叠为：写失败时记入 {@link #error()}
 * 并抛出（引擎 catch 后记日志跳过本轮注入，见 {@link SteerSink} 类注释）；
 * {@link #closeIfDrained()} 保留 Go 的 {@code (bool, error)} 双值语义——失败时
 * 记入 {@link #error()} 并返回 {@code false}，不抛（调用方按 closed=false 重试）。</p>
 */
public class InstallSteerSink implements SteerSink {

    private final StreamManager streams;
    private final MessageRepository messages;
    /**
     * 与 TenantSkillService 的 steerInstall/installGuidance 同一把监视器锁
     * （对照 Go 的 withInstallSteerLock 分布式锁；进程内等价物）。closeIfDrained 与
     * 入站发送共用：一次发送要么属于另一轮引擎，要么在接受前就被拒（Go 注释原文）。
     */
    private final Object steerLock;
    private final SkillInstallTranscript transcript;

    // 只有引擎协程访问以下字段（Go 注释原文）。
    private final List<String> guidance = new ArrayList<>();
    private RuntimeException err;

    public InstallSteerSink(StreamManager streams, MessageRepository messages,
            Object steerLock, SkillInstallTranscript transcript) {
        this.streams = streams;
        this.messages = messages;
        this.steerLock = steerLock;
        this.transcript = transcript;
    }

    /** 引擎可见的累计失败（对照 Go 的 {@code sink.err}，读方是安装管线）。 */
    public RuntimeException error() {
        return err;
    }

    /** 本轮已持久化的指引文本，按注入序（对照 Go 的 {@code sink.guidance}）。 */
    public List<String> guidance() {
        return guidance;
    }

    @Override
    public List<Map<String, Object>> pollSteer(String sessionId, String messageId,
            int lastOffset) {
        // 引擎活跃期间持续刷新专用标记——即使没有控制台在轮询。每个维护会话
        // 只有一个引擎（Go 注释原文）。
        try {
            streams.setLiveRun(TenantSkillService.installSteerSession(sessionId), messageId, "");
        } catch (RuntimeException e) {
            err = e;
            throw e;
        }
        try {
            var batch = streams.getSteerEvents(sessionId, messageId, 0);
            List<Map<String, Object>> result = new ArrayList<>();
            for (StreamEvent evt : batch.events()) {
                boolean consumed = evt.getData() != null
                        && Boolean.TRUE.equals(evt.getData().get("consumed"));
                if (!consumed) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", evt.getId());
                    item.put("content", evt.getContent());
                    result.add(item);
                }
            }
            return result;
        } catch (RuntimeException e) {
            err = e;
            throw e;
        }
    }

    @Override
    public String persistSteerMessage(String sessionId, String messageId, String steerId,
            String content, Object mentionedItems, String channel) {
        // mentions 只入历史，运行中轮次的范围不因此变宽（SteerSink 契约）——install
        // 路径的 Go 实现同样忽略它们。
        String id = uuidV5NameSpaceOid(sessionId + ":" + messageId + ":" + steerId);
        try {
            Message m = new Message();
            m.setId(id);
            m.setSessionId(sessionId);
            m.setRole(Message.ROLE_USER);
            m.setContent(content);
            m.setCompleted(true);
            m.setCreatedAt(OffsetDateTime.now());
            m.setUpdatedAt(OffsetDateTime.now());
            messages.create(m);
        } catch (RuntimeException e) {
            Message existing = null;
            try {
                existing = messages.getMessage(sessionId, id);
            } catch (RuntimeException getErr) {
                // 落到下面的失败分支
            }
            if (existing == null || !sessionId.equals(existing.getSessionId())
                    || !content.equals(existing.getContent())) {
                err = e;
                return "";
            }
        }
        boolean updated;
        try {
            updated = streams.updateSteerEventData(sessionId, messageId, steerId,
                    Map.of("consumed", true, "user_message_id", id));
        } catch (RuntimeException e) {
            err = new IllegalStateException(
                    "record install guidance consumption: updated=false: " + e.getMessage(), e);
            return "";
        }
        if (!updated) {
            err = new IllegalStateException(
                    "record install guidance consumption: updated=false");
            return "";
        }
        guidance.add(content);
        return id;
    }

    /**
     * 对照 {@code closeIfDrained}：所有指引都已消费时清掉 live-run 标记。
     * 返回 {@code false} = 还有未消费的、或清标记失败（后者同时记入 {@link #error()}）。
     */
    public boolean closeIfDrained() {
        synchronized (steerLock) {
            var batch = streams.getSteerEvents(transcriptSessionId(),
                    transcriptAssistantMessageId(), 0);
            for (StreamEvent evt : batch.events()) {
                boolean consumed = evt.getData() != null
                        && Boolean.TRUE.equals(evt.getData().get("consumed"));
                if (!consumed) {
                    return false;
                }
            }
            try {
                streams.clearLiveRun(TenantSkillService.installSteerSession(transcriptSessionId()),
                        transcriptAssistantMessageId());
                return true;
            } catch (RuntimeException e) {
                err = e;
                return false;
            }
        }
    }

    private String transcriptSessionId() {
        // transcript 的会话 ID 就是维护会话（Go：tr.sessionID）
        return transcript.sessionIdForSink();
    }

    private String transcriptAssistantMessageId() {
        return transcript.assistantMessageIdForSink();
    }

    /**
     * 对照 Go {@code uuid.NewSHA1(uuid.NameSpaceOID, []byte(name))}：
     * RFC 4122 §4.3 的 SHA-1 变体（version 5），命名空间 OID 的 16 个原始字节前缀。
     */
    static String uuidV5NameSpaceOid(String name) {
        byte[] ns = {(byte) 0x6b, (byte) 0xa7, (byte) 0xb8, (byte) 0x12, (byte) 0x9d, (byte) 0xad,
                (byte) 0x11, (byte) 0xd1, (byte) 0x80, (byte) 0xb4, (byte) 0x00, (byte) 0xc0,
                (byte) 0x4f, (byte) 0xd4, (byte) 0x30, (byte) 0xc8};
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
        md.update(ns);
        md.update(name.getBytes(StandardCharsets.UTF_8));
        byte[] hash = md.digest();
        byte[] out = new byte[16];
        System.arraycopy(hash, 0, out, 0, 16);
        out[6] = (byte) ((out[6] & 0x0f) | 0x50);
        out[8] = (byte) ((out[8] & 0x3f) | 0x80);
        StringBuilder sb = new StringBuilder(36);
        for (int i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) {
                sb.append('-');
            }
            sb.append(String.format("%02x", out[i]));
        }
        return sb.toString();
    }
}
