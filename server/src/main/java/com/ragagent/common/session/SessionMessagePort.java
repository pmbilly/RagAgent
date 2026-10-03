package com.ragagent.common.session;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 会话消息的**只读端口**：给 memory（记忆蒸馏）读取会话历史用。
 *
 * <p>为什么需要它：memory 蒸馏需要按游标/时间读会话消息，此前直接注入 {@code session.mapper.MessageRepository}
 * 与 {@code session.domain.Message}（memory → session 反边，构成 memory ⇄ session 环）。蒸馏只用到
 * id/role/content/createdAt 四个字段，故抽成最窄只读契约放 common；会话侧实现（复用既有查询）。</p>
 */
public interface SessionMessagePort {

    /** 蒸馏所需的最小消息视图（不含附件/元数据等会话域细节）。 */
    record SessionMessageView(String id, String role, String content, OffsetDateTime createdAt) {
    }

    /**
     * 按游标取会话消息。
     * 游标为零值时表示从头开始。
     */
    List<SessionMessageView> listAfterCursor(String sessionId, OffsetDateTime afterCreatedAt, String afterId, int limit);

    /** 取 {@code beforeTime} 之前最旧的 limit 条。 */
    List<SessionMessageView> listBeforeTime(String sessionId, OffsetDateTime beforeTime, int limit);
}
