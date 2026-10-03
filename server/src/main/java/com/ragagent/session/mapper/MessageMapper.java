package com.ragagent.session.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.common.web.PgJsonTypeHandler;
import com.ragagent.session.domain.AgentStepListTypeHandler;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageArtifact;
import com.ragagent.session.domain.MessageArtifactListTypeHandler;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.MessageAttachmentListTypeHandler;
import com.ragagent.session.domain.MessageImageListTypeHandler;
import com.ragagent.session.domain.MentionedItemListTypeHandler;
import com.ragagent.session.domain.SearchResultListTypeHandler;
import com.ragagent.session.domain.UsedMemoryListTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * messages 的 MyBatis-Plus 基础仓储。
 *
 * <p>{@code ListMessagesBySessionAfterCursor}（依赖 memory 模块的
 * {@code MemoryMessageCursor}）已在 {@link MessageRepository} 落地——memory 的 service
 * 层需要它做游标分页，本轮收口回这里，不再由 memory 侧自拼一份 SQL。</p>
 *
 * <p>三条 JOIN sessions 的检索查询里，{@code SearchMessagesByKeyword} 与
 * {@code GetMessagesByRequestIDs} 由 {@link MessageRepository} 两步化
 * （先查消息再补标题）；{@code GetMessagesByKnowledgeIDs}（向量搜索回映射）
 * 保留**一条 JOIN SQL**——INNER JOIN 要把「会话已软删」的消息直接排除，
 * 见 {@link #selectMessagesByKnowledgeIds}。</p>
 *
 * <p>软删除的处置同 sessions：查询显式 {@code deleted_at IS NULL}，删除是 UPDATE。</p>
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 取某会话里指定角色的第一条消息。
     *
     * <p>SQL 显式 {@code deleted_at IS NULL} 并 {@code LIMIT 1}。</p>
     */
    @Select("SELECT * FROM messages WHERE session_id = #{sessionId} AND role = #{role} "
            + "AND deleted_at IS NULL ORDER BY created_at ASC LIMIT 1")
    Message selectFirstBySessionAndRole(@Param("sessionId") String sessionId,
                                        @Param("role") String role);

    /**
     * 取某会话里每条消息的 artifacts。
     *
     * <p>只投影 {@code artifacts} 一列（{@code created_at} 仅用于 SQL 排序）。
     * <b>不在 SQL 里过滤空 artifacts</b>——空列表由调用方在内存里跳过，保持同一处置。</p>
     */
    @Select("SELECT artifacts FROM messages "
            + "WHERE session_id = #{sessionId} AND deleted_at IS NULL ORDER BY created_at ASC")
    @Results({
            @Result(column = "artifacts", property = "artifacts",
                    typeHandler = MessageArtifactListTypeHandler.class)
    })
    List<ArtifactRow> selectArtifactRows(@Param("sessionId") String sessionId);

    /**
     * 取某会话里每条消息的 attachments。
     *
     * <p>与上面那条的差别：这条**不跳过空值**——空列表也原样返回。</p>
     */
    @Select("SELECT attachments FROM messages "
            + "WHERE session_id = #{sessionId} AND deleted_at IS NULL ORDER BY created_at ASC")
    @Results({
            @Result(column = "attachments", property = "attachments",
                    typeHandler = MessageAttachmentListTypeHandler.class)
    })
    List<AttachmentRow> selectAttachmentRows(@Param("sessionId") String sessionId);

    /** 只写 {@code images} 列。 */
    @Update("UPDATE messages SET images = "
            + "#{images, typeHandler=com.ragagent.session.domain.MessageImageListTypeHandler, "
            + "jdbcType=OTHER} "
            + "WHERE id = #{messageId} AND session_id = #{sessionId}")
    int updateImages(@Param("sessionId") String sessionId,
                     @Param("messageId") String messageId,
                     @Param("images") Object images);

    /** 只写 {@code rendered_content} 列。 */
    @Update("UPDATE messages SET rendered_content = #{renderedContent} "
            + "WHERE id = #{messageId} AND session_id = #{sessionId}")
    int updateRenderedContent(@Param("sessionId") String sessionId,
                              @Param("messageId") String messageId,
                              @Param("renderedContent") String renderedContent);

    /**
     * 只写 {@code knowledge_id} 列。
     *
     * <p>注意这条**没有 session_id 条件**——只按主键。别顺手补上。</p>
     */
    @Update("UPDATE messages SET knowledge_id = #{knowledgeId} WHERE id = #{messageId}")
    int updateKnowledgeId(@Param("messageId") String messageId,
                          @Param("knowledgeId") String knowledgeId);

    /**
     * 按 {@code knowledge_id} 取回消息：向量搜索把 KB 命中映射回消息的查询。
     *
     * <p>一条 JOIN SQL：{@code INNER JOIN sessions ON sessions.id = messages.session_id
     * AND sessions.deleted_at IS NULL}（会话已软删的消息**直接排除**——这是必须保真的语义，
     * 不能像另外两条那样两步化）＋ {@code messages.deleted_at IS NULL}；无 ORDER BY
     * （调用方按 KB 分数重排）。</p>
     *
     * <p>⚠️ 自定义 {@code @Select} 不套实体的 {@code @TableField(typeHandler=...)}：
     * {@code messages.*} 里的 9 个 jsonb 列
     * 必须逐列写进方法级 {@code @Results}，否则读回来恒为 null 或解析炸。
     * {@code is_completed}/{@code is_fallback} 的实体属性名**不带 is 前缀**
     * （{@code completed}/{@code fallback}）——漏显式映射就恒 false。</p>
     */
    @Select("<script>SELECT messages.*, sessions.title AS session_title FROM messages "
            + "INNER JOIN sessions ON sessions.id = messages.session_id "
            + "AND sessions.deleted_at IS NULL "
            + "WHERE messages.deleted_at IS NULL AND messages.knowledge_id IN "
            + "<foreach collection='knowledgeIds' item='kid' open='(' separator=',' close=')'>"
            + "#{kid}</foreach></script>")
    @Results({
            @Result(column = "id", property = "id"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "content", property = "content"),
            @Result(column = "role", property = "role"),
            @Result(column = "knowledge_references", property = "knowledgeReferences",
                    typeHandler = SearchResultListTypeHandler.class),
            @Result(column = "agent_steps", property = "agentSteps",
                    typeHandler = AgentStepListTypeHandler.class),
            @Result(column = "mentioned_items", property = "mentionedItems",
                    typeHandler = MentionedItemListTypeHandler.class),
            @Result(column = "images", property = "images",
                    typeHandler = MessageImageListTypeHandler.class),
            @Result(column = "attachments", property = "attachments",
                    typeHandler = MessageAttachmentListTypeHandler.class),
            @Result(column = "artifacts", property = "artifacts",
                    typeHandler = MessageArtifactListTypeHandler.class),
            @Result(column = "is_completed", property = "completed"),
            @Result(column = "is_fallback", property = "fallback"),
            @Result(column = "agent_duration_ms", property = "agentDurationMs"),
            @Result(column = "usage", property = "usage", typeHandler = PgJsonTypeHandler.class),
            @Result(column = "rendered_content", property = "renderedContent"),
            @Result(column = "channel", property = "channel"),
            @Result(column = "agent_id", property = "agentId"),
            @Result(column = "agent_tenant_id", property = "agentTenantId"),
            @Result(column = "model_id", property = "modelId"),
            @Result(column = "execution_context", property = "executionContext",
                    typeHandler = PgJsonTypeHandler.class),
            @Result(column = "knowledge_id", property = "knowledgeId"),
            @Result(column = "used_memories", property = "usedMemories",
                    typeHandler = UsedMemoryListTypeHandler.class),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "deleted_at", property = "deletedAt"),
            @Result(column = "session_title", property = "sessionTitle")
    })
    List<MessageWithSessionRow> selectMessagesByKnowledgeIds(
            @Param("knowledgeIds") List<String> knowledgeIds);

    /** artifacts 投影行。jsonb 列挂类型处理器只能靠方法级 {@code @Results}。 */
    class ArtifactRow {
        private List<MessageArtifact> artifacts;

        public List<MessageArtifact> getArtifacts() {
            return artifacts;
        }

        public void setArtifacts(List<MessageArtifact> v) {
            this.artifacts = v;
        }
    }

    /** attachments 投影行。 */
    class AttachmentRow {
        private List<MessageAttachment> attachments;

        public List<MessageAttachment> getAttachments() {
            return attachments;
        }

        public void setAttachments(List<MessageAttachment> v) {
            this.attachments = v;
        }
    }

    /**
     * {@code selectMessagesByKnowledgeIds} 的投影行：Message ＋ JOIN 出来的
     * {@code session_title}。
     * 继承 Message 只为让 {@code @Results} 直接映射到消息属性——不参与 MyBatis-Plus
     * 的任何 CRUD，转 {@code MessageWithSession} 时也直接以此为消息本体（只读）。
     */
    class MessageWithSessionRow extends Message {
        private String sessionTitle = "";

        public String getSessionTitle() {
            return sessionTitle;
        }

        public void setSessionTitle(String v) {
            this.sessionTitle = v == null ? "" : v;
        }
    }
}
