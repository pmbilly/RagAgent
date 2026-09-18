package com.ragagent.session.mapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageImage;
import com.ragagent.session.domain.MessageArtifact;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.MessageNotFoundException;
import com.ragagent.session.domain.Session;
import org.springframework.stereotype.Component;

/**
 * 消息仓储（对照 Go internal/application/repository/message.go）。
 *
 * <h2>GORM 隐式行为 → Java 的等效清单（约定 §3 要求显式列出）</h2>
 * <ol>
 *   <li><b>钩子 BeforeCreate</b>（Go L463-484）：无条件新 UUID + 六个 nil 切片置空。
 *       → {@link #create} 里调 {@code normalizeListsForInsert()} 并覆盖 ID。</li>
 *   <li><b>⚠️ Updates(结构体) 跳过零值</b>（Go L139-143）：GORM 对**结构体**的 Updates
 *       只写非零字段——string "" / 数值 0 / bool false / 指针 nil / 切片 nil 一律跳过。
 *       也就是说"把 content 改成空串"在这条路径上**不生效**。
 *       → {@link #update} 逐字段按同一规则判断后才 SET（见该方法注释）。</li>
 *   <li><b>软删除</b>：{@code gorm.DeletedAt}。查询显式 {@code deleted_at IS NULL}，
 *       删除是 UPDATE。</li>
 *   <li><b>默认排序</b>：{@code created_at ASC/DESC} 各查询自带（Go L54/L68/L94/L121/L134）。</li>
 * </ol>
 *
 * <p><b>本文件尚未包含</b>三条 JOIN sessions 的检索查询与 memory 游标分页——下一步。</p>
 */
@Component
public class MessageRepository {

    /** jsonb 列的类型处理器全限定名（3 参 set 的 mapping 串）。 */
    private static final String PG_JSON = "com.ragagent.common.web.PgJsonTypeHandler";
    private static final String MENTIONED_ITEMS =
            "com.ragagent.session.domain.MentionedItemListTypeHandler";
    private static final String IMAGES = "com.ragagent.session.domain.MessageImageListTypeHandler";
    private static final String ATTACHMENTS =
            "com.ragagent.session.domain.MessageAttachmentListTypeHandler";
    private static final String ARTIFACTS =
            "com.ragagent.session.domain.MessageArtifactListTypeHandler";
    private static final String USED_MEMORIES =
            "com.ragagent.session.domain.UsedMemoryListTypeHandler";

    private final MessageMapper mapper;
    private final SessionMapper sessionMapper;

    public MessageRepository(MessageMapper mapper, SessionMapper sessionMapper) {
        this.mapper = mapper;
        this.sessionMapper = sessionMapper;
    }

    // ── 写 ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code CreateMessage}（L27-34）：钩子无条件生成新 ID。 */
    public Message create(Message message) {
        message.setId(UUID.randomUUID().toString());
        message.normalizeListsForInsert();
        mapper.insert(message);
        return message;
    }

    /**
     * 对照 Go {@code UpdateMessage}（L139-143）——**只写非零字段**。
     *
     * <p>GORM 对结构体的 Updates 会跳过零值，所以这里逐字段判断：
     * string {@code != ""}、数值 {@code != 0}、bool {@code != false}、对象/切片 {@code != null}。
     * 这条规则直接决定了"清空某列"在这种调用下是做不到的——照抄，别"修好"。</p>
     *
     * <p>注意其中 {@code session_id} 也在非零字段之列（GORM 不排除它），不过它已被 WHERE
     * 钉住，写了也是同值。{@code id} 是主键、{@code created_at}/{@code deleted_at} 由 GORM 另行处理，
     * 都不进 SET；{@code updated_at} 由 GORM 的 autoUpdateTime 自动刷新，这里显式补上。</p>
     */
    public void update(Message m) {
        // 用字符串列名的 UpdateWrapper 而不是 Lambda：jsonb 列必须靠 3 参 set 显式挂
        // typeHandler，而 lambda 形式的 set 拿不到列映射、会退化成 Java 序列化
        // （H2 报 "Data conversion error converting CAST(X'aced0005...)"）。
        UpdateWrapper<Message> w = new UpdateWrapper<Message>()
                .eq("id", m.getId())
                .eq("session_id", m.getSessionId())
                .isNull("deleted_at");

        boolean any = false;

        if (nonEmpty(m.getRequestId())) {
            w.set("request_id", m.getRequestId());
            any = true;
        }
        if (nonEmpty(m.getContent())) {
            w.set("content", m.getContent());
            any = true;
        }
        if (nonEmpty(m.getRole())) {
            w.set("role", m.getRole());
            any = true;
        }
        any |= setJson(w, "knowledge_references", m.getKnowledgeReferences(), PG_JSON);
        any |= setJson(w, "agent_steps", m.getAgentSteps(), PG_JSON);
        any |= setJson(w, "mentioned_items", m.getMentionedItems(), MENTIONED_ITEMS);
        any |= setJson(w, "images", m.getImages(), IMAGES);
        any |= setJson(w, "attachments", m.getAttachments(), ATTACHMENTS);
        any |= setJson(w, "artifacts", m.getArtifacts(), ARTIFACTS);
        if (m.isCompleted()) {
            w.set("is_completed", true);
            any = true;
        }
        if (m.isFallback()) {
            w.set("is_fallback", true);
            any = true;
        }
        if (m.getAgentDurationMs() != 0) {
            w.set("agent_duration_ms", m.getAgentDurationMs());
            any = true;
        }
        any |= setJson(w, "usage", m.getUsage(), PG_JSON);
        if (nonEmpty(m.getRenderedContent())) {
            w.set("rendered_content", m.getRenderedContent());
            any = true;
        }
        if (nonEmpty(m.getChannel())) {
            w.set("channel", m.getChannel());
            any = true;
        }
        if (nonEmpty(m.getAgentId())) {
            w.set("agent_id", m.getAgentId());
            any = true;
        }
        if (m.getAgentTenantId() != 0) {
            w.set("agent_tenant_id", m.getAgentTenantId());
            any = true;
        }
        if (nonEmpty(m.getModelId())) {
            w.set("model_id", m.getModelId());
            any = true;
        }
        any |= setJson(w, "execution_context", m.getExecutionContext(), PG_JSON);
        if (nonEmpty(m.getKnowledgeId())) {
            w.set("knowledge_id", m.getKnowledgeId());
            any = true;
        }
        any |= setJson(w, "used_memories", m.getUsedMemories(), USED_MEMORIES);

        if (!any) {
            // GORM 在全部字段为零时会生成 `UPDATE messages SET` 这种非法语句而报错；
            // 这里直接跳过，语义等价于"没有可更新的列"。
            return;
        }
        w.set("updated_at", OffsetDateTime.now());
        mapper.update(null, w);
    }

    /** Go 的零值判定：string 的非零就是非空。 */
    private static boolean nonEmpty(String v) {
        return v != null && !v.isEmpty();
    }

    /** 只写非 null 的 jsonb 列；mapping 串让 MyBatis 用指定的类型处理器。 */
    private static boolean setJson(UpdateWrapper<Message> w, String column, Object value, String handler) {
        if (value == null) {
            return false;
        }
        w.set(column, value, "typeHandler=" + handler);
        return true;
    }

    /** 对照 Go {@code DeleteMessage}（L146-150）——软删。 */
    public void delete(String sessionId, String messageId) {
        LambdaUpdateWrapper<Message> w = new LambdaUpdateWrapper<Message>()
                .eq(Message::getId, messageId)
                .eq(Message::getSessionId, sessionId)
                .isNull(Message::getDeletedAt)
                .set(Message::getDeletedAt, OffsetDateTime.now());
        mapper.update(null, w);
    }

    /** 对照 Go {@code DeleteMessagesBySessionID}（L322-324）——整会话软删。 */
    public void deleteBySessionId(String sessionId) {
        LambdaUpdateWrapper<Message> w = new LambdaUpdateWrapper<Message>()
                .eq(Message::getSessionId, sessionId)
                .isNull(Message::getDeletedAt)
                .set(Message::getDeletedAt, OffsetDateTime.now());
        mapper.update(null, w);
    }

    /**
     * 对照 Go {@code UpdateMessageImages}（L306-311）：只写 images 列。
     *
     * <p>Go 的注释说它"用 Select 强制 GORM 带上这一列（否则结构体式 Updates 会跳过
     * 自定义 Valuer 类型）"，但代码实际写的是 {@code Update("images", images)}——
     * 单列 Update 本就不会被零值规则拦。Java 用显式 UPDATE 语句，同一效果。</p>
     */
    public void updateImages(String sessionId, String messageId, List<MessageImage> images) {
        mapper.updateImages(sessionId, messageId, images);
    }

    /** 对照 Go {@code UpdateMessageRenderedContent}（L314-319）。 */
    public void updateRenderedContent(String sessionId, String messageId, String renderedContent) {
        mapper.updateRenderedContent(sessionId, messageId, renderedContent);
    }

    /** 对照 Go {@code UpdateMessageKnowledgeID}（L327-334）：**没有 session_id 条件**。 */
    public void updateKnowledgeId(String messageId, String knowledgeId) {
        mapper.updateKnowledgeId(messageId, knowledgeId);
    }

    // ── 读 ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code GetMessage}（L37-47）：id + session_id；零行抛 404。 */
    public Message getMessage(String sessionId, String messageId) {
        LambdaQueryWrapper<Message> w = new LambdaQueryWrapper<Message>()
                .eq(Message::getId, messageId)
                .eq(Message::getSessionId, sessionId)
                .isNull(Message::getDeletedAt);
        Message m = mapper.selectOne(w);
        if (m == null) {
            throw new MessageNotFoundException();
        }
        return m;
    }

    /** 对照 Go {@code GetMessagesBySession}（L50-59）：created_at ASC，offset/limit 不做归一化。 */
    public List<Message> getMessagesBySession(String sessionId, int page, int pageSize) {
        return mapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, sessionId)
                .isNull(Message::getDeletedAt)
                .orderByAsc(Message::getCreatedAt)
                .last("LIMIT " + pageSize + " OFFSET " + ((page - 1) * pageSize)));
    }

    /**
     * 对照 Go {@code GetRecentMessagesBySession}（L62-85）：倒序取 limit 条，**再正序重排**。
     *
     * <p>重排的比较器照抄 Go：先比 created_at；相等时 user 在前、其余在后。</p>
     *
     * <p><b>已知差异</b>：Go 用的是 {@code slices.SortFunc}（pdqsort，**不保证稳定**），
     * 且该比较器对"同一时间戳、同一 role"的两条返回 {@code a > b}（自反不一致）。
     * Java 用稳定排序，这种输入下会保留 SQL 顺序。二者只在"同微秒同角色"时可能不同序。</p>
     */
    public List<Message> getRecentMessagesBySession(String sessionId, int limit) {
        List<Message> messages = new ArrayList<>(mapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, sessionId)
                .isNull(Message::getDeletedAt)
                .orderByDesc(Message::getCreatedAt)
                .last("LIMIT " + limit)));
        messages.sort(createdAtThenUserFirst());
        return messages;
    }

    /** 对照 Go {@code GetMessagesBySessionBeforeTime}（L88-108）：同样的倒序取 + 正序重排。 */
    public List<Message> getMessagesBySessionBeforeTime(String sessionId, OffsetDateTime beforeTime, int limit) {
        List<Message> messages = new ArrayList<>(mapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, sessionId)
                .lt(Message::getCreatedAt, beforeTime)
                .isNull(Message::getDeletedAt)
                .orderByDesc(Message::getCreatedAt)
                .last("LIMIT " + limit)));
        messages.sort(createdAtThenUserFirst());
        return messages;
    }

    /**
     * 对照 Go {@code ListMessagesBySessionAfterTime}（L113-125）：取 afterTime 之后**最旧的**
     * limit 条，让持有水位线的调用方能一页页往前推而不跳过。
     *
     * <p>afterTime 为零值时**不加时间条件**（Go 的 {@code !afterTime.IsZero()}）。</p>
     */
    public List<Message> listMessagesBySessionAfterTime(String sessionId, OffsetDateTime afterTime, int limit) {
        LambdaQueryWrapper<Message> w = new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, sessionId)
                .isNull(Message::getDeletedAt);
        if (afterTime != null) {
            w.gt(Message::getCreatedAt, afterTime);
        }
        w.orderByAsc(Message::getCreatedAt).last("LIMIT " + limit);
        return mapper.selectList(w);
    }

    /**
     * 对照 Go {@code GetMessageByRequestID}（L164-181）。
     *
     * <p><b>查不到返回 null 而不是抛错</b>——Go 在这里显式把 {@code ErrRecordNotFound}
     * 翻成 {@code nil, nil}，与同文件其它读方法不同。</p>
     */
    public Message getMessageByRequestId(String sessionId, String requestId) {
        return mapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, sessionId)
                .eq(Message::getRequestId, requestId)
                .isNull(Message::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** 对照 Go {@code GetFirstMessageOfUser}（L153-161）。 */
    public Message getFirstMessageOfUser(String sessionId) {
        return mapper.selectFirstBySessionAndRole(sessionId, Message.ROLE_USER);
    }

    /** 对照 Go {@code GetKnowledgeIDsBySessionID}（L290-301）：只取非空 knowledge_id。 */
    public List<String> getKnowledgeIdsBySessionId(String sessionId) {
        return mapper.selectObjs(new LambdaQueryWrapper<Message>()
                        .select(Message::getKnowledgeId)
                        .eq(Message::getSessionId, sessionId)
                        .ne(Message::getKnowledgeId, "")
                        .isNotNull(Message::getKnowledgeId)
                        .isNull(Message::getDeletedAt))
                .stream().map(String::valueOf).toList();
    }

    /**
     * 对照 Go {@code OwnedSessionIDs}（L223-247）：把一组会话 id 收窄到这个人拥有的那些。
     *
     * <p>向量检索路径是通过**共享知识库**找到消息的，那里没有"谁写的"的概念，
     * 所以返回之前必须在这里重新确立归属。</p>
     */
    public Map<String, Boolean> ownedSessionIds(long tenantId, String ownerId, List<String> sessionIds) {
        Map<String, Boolean> owned = new HashMap<>();
        if (sessionIds == null || sessionIds.isEmpty()) {
            return owned;
        }
        LambdaQueryWrapper<Session> w = new LambdaQueryWrapper<Session>()
                        .select(Session::getId)
                        .eq(Session::getTenantId, tenantId)
                        .isNull(Session::getDeletedAt)
                        .in(Session::getId, sessionIds);
        if (ownerId != null && !ownerId.isEmpty()) {
            w.and(q -> q.eq(Session::getUserId, ownerId)
                    .or().isNull(Session::getUserId)
                    .or().eq(Session::getUserId, ""));
        }
        // 走 sessions 表：借用 SessionMapper
        for (Object id : sessionMapper.selectObjs(w)) {
            owned.put(String.valueOf(id), true);
        }
        return owned;
    }

    // ── 投影 ────────────────────────────────────────────────────────────────

    /** 对照 Go {@code GetSessionArtifacts}（L342-371）：按创建序把各行的 artifacts 展平。 */
    public List<MessageArtifact> getSessionArtifacts(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        List<MessageMapper.ArtifactRow> rows = mapper.selectArtifactRows(sessionId);
        List<MessageArtifact> result = new ArrayList<>();
        for (MessageMapper.ArtifactRow row : rows) {
            List<MessageArtifact> a = row.getArtifacts();
            if (a == null || a.isEmpty()) {
                continue;
            }
            result.addAll(a);
        }
        return result;
    }

    /** 对照 Go {@code GetSessionAttachments}（L375-398）：**不跳过空列表**。 */
    public List<MessageAttachment> getSessionAttachments(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        List<MessageMapper.AttachmentRow> rows = mapper.selectAttachmentRows(sessionId);
        List<MessageAttachment> result = new ArrayList<>();
        for (MessageMapper.AttachmentRow row : rows) {
            if (row.getAttachments() != null) {
                result.addAll(row.getAttachments());
            }
        }
        return result;
    }

    /** Go 的重排比较器：created_at 升序；相等时 user 在前，其余在后。 */
    private static Comparator<Message> createdAtThenUserFirst() {
        return (a, b) -> {
            int cmp = a.getCreatedAt().compareTo(b.getCreatedAt());
            if (cmp != 0) {
                return cmp;
            }
            return Message.ROLE_USER.equals(a.getRole()) ? -1 : 1;
        };
    }
}
