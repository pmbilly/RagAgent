package com.ragagent.memory.service;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.memory.domain.MemoryMessageCursor;
import com.ragagent.session.domain.Message;
import com.ragagent.session.mapper.MessageMapper;
import com.ragagent.session.mapper.MessageRepository;
import org.springframework.stereotype.Component;

/**
 * 蒸馏读对话历史的两个入口（对照 Go {@code interfaces.MessageRepository} 里
 * 这个模块用到的两个方法）。
 *
 * <h2>⚠️ 为什么需要这个薄适配层（需要主会话决策的一点）</h2>
 * <p>Go 的 {@code GetMessagesBySessionBeforeTime} 与
 * {@code ListMessagesBySessionAfterCursor} 是同一个仓储接口上的两个方法。Java 侧：</p>
 * <ul>
 *   <li>{@code getMessagesBySessionBeforeTime} <b>已经存在</b>
 *       （{@code session.mapper.MessageRepository}，阶段 5.1 落地），直接复用；</li>
 *   <li>{@code ListMessagesBySessionAfterCursor} <b>不在</b>那里面——它的类注释明确写着
 *       "依赖 memory 模块的 {@code MemoryMessageCursor}，在下一步落地"。
 *       而本轮任务的范围限制是<b>只能改 memory 包</b>，所以这里用
 *       {@code MessageMapper}（MyBatis-Plus 的 {@code BaseMapper}）自己拼那条查询，
 *       而不是去改 {@code session} 包。</li>
 * </ul>
 * <p>后果：这条查询的 SQL 形状多了一份"副本"（另一份将来会落在 session 包里）。
 * <b>建议主会话在 handler 层落地时把它挪回 {@code MessageRepository}</b>——
 * 那才是它与其余消息查询该待的地方；本类届时退化成一行转发即可。</p>
 *
 * <h2>照抄的两个口径</h2>
 * <ol>
 *   <li><b>软删</b>：Go 的模型带 {@code gorm.DeletedAt}，GORM 自动补
 *       {@code deleted_at IS NULL}。Java 侧显式写（约定 §9「soft delete 不用 @TableLogic」）。</li>
 *   <li><b>游标条件</b>：{@code created_at > ? OR (created_at = ? AND id > ?)}，
 *       排序 {@code created_at ASC, id ASC}——用 {@code (created_at, id)} 做无损翻页，
 *       只在 {@code created_at} 上翻页会在同一毫秒有多条消息时丢行。</li>
 * </ol>
 */
@Component
public class MemoryMessageReader {

    private final MessageRepository messages;
    private final MessageMapper mapper;

    public MemoryMessageReader(MessageRepository messages, MessageMapper mapper) {
        this.messages = messages;
        this.mapper = mapper;
    }

    /**
     * 对照 Go {@code ListMessagesBySessionAfterCursor}。
     *
     * <p>游标为零值（时间零 + id 空）时**不加游标条件**，即从头开始。</p>
     */
    public List<Message> listAfterCursor(String sessionId, MemoryMessageCursor cursor, int limit) {
        LambdaQueryWrapper<Message> w = new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, sessionId)
                .isNull(Message::getDeletedAt);
        boolean hasCursor = cursor != null
                && (!GoTimeSerializer.isGoZero(cursor.getAt()) || !cursor.getId().isEmpty());
        if (hasCursor) {
            OffsetDateTime at = cursor.getAt();
            String id = cursor.getId();
            w.and(outer -> outer
                    .gt(Message::getCreatedAt, at)
                    .or(inner -> inner.eq(Message::getCreatedAt, at).gt(Message::getId, id)));
        }
        w.orderByAsc(Message::getCreatedAt).orderByAsc(Message::getId).last("LIMIT " + limit);
        return mapper.selectList(w);
    }

    /**
     * 对照 Go {@code GetMessagesBySessionBeforeTime}：取之前最旧的 limit 条
     * （SQL 上倒序取、再正序重排，user 在前）。
     */
    public List<Message> listBeforeTime(String sessionId, OffsetDateTime beforeTime, int limit) {
        return messages.getMessagesBySessionBeforeTime(sessionId, beforeTime, limit);
    }
}
