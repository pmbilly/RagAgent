package com.ragagent.session.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.common.web.PgJsonTypeHandler;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageArtifact;
import com.ragagent.session.domain.MessageArtifactListTypeHandler;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.MessageAttachmentListTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * messages 的 MyBatis-Plus 基础仓储（对照 Go
 * internal/application/repository/message.go 的 {@code messageRepository}）。
 *
 * <p>{@code ListMessagesBySessionAfterCursor}（依赖 memory 模块的
 * {@code MemoryMessageCursor}）已在 {@link MessageRepository} 落地——memory 的 service
 * 层需要它做游标分页，本轮收口回这里，不再由 memory 侧自拼一份 SQL。</p>
 *
 * <p><b>本文件尚未包含</b>三条需要 JOIN sessions 的检索查询
 * （{@code SearchMessagesByKeyword} / {@code GetMessagesByKnowledgeIDs} /
 * {@code GetMessagesByRequestIDs}）——它们在搜索端点落地时补。</p>
 *
 * <p>软删除的处置同 sessions：查询显式 {@code deleted_at IS NULL}，删除是 UPDATE。</p>
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 取某会话里指定角色的第一条消息（对照 Go {@code GetFirstMessageOfUser}，L153-161）。
     *
     * <p>Go 用 {@code First}，GORM 自动补 {@code deleted_at IS NULL} 并加 {@code LIMIT 1}。</p>
     */
    @Select("SELECT * FROM messages WHERE session_id = #{sessionId} AND role = #{role} "
            + "AND deleted_at IS NULL ORDER BY created_at ASC LIMIT 1")
    Message selectFirstBySessionAndRole(@Param("sessionId") String sessionId,
                                        @Param("role") String role);

    /**
     * 取某会话里每条消息的 artifacts（对照 Go {@code GetSessionArtifacts}，L342-371）。
     *
     * <p>只投影 {@code artifacts} 一列（{@code created_at} 仅用于 SQL 排序，Go 的行结构体里
     * 虽然带着它但从未读取）。<b>不在 SQL 里过滤空 artifacts</b>——Go 是在内存里
     * {@code len == 0 → continue} 跳过的，保持同一处置。</p>
     */
    @Select("SELECT artifacts FROM messages "
            + "WHERE session_id = #{sessionId} AND deleted_at IS NULL ORDER BY created_at ASC")
    @Results({
            @Result(column = "artifacts", property = "artifacts",
                    typeHandler = MessageArtifactListTypeHandler.class)
    })
    List<ArtifactRow> selectArtifactRows(@Param("sessionId") String sessionId);

    /**
     * 取某会话里每条消息的 attachments（对照 Go {@code GetSessionAttachments}，L375-398）。
     *
     * <p>与上面那条的差别：Go 这条**不跳过空值**（少了那段 {@code continue}）。行为差异照抄。</p>
     */
    @Select("SELECT attachments FROM messages "
            + "WHERE session_id = #{sessionId} AND deleted_at IS NULL ORDER BY created_at ASC")
    @Results({
            @Result(column = "attachments", property = "attachments",
                    typeHandler = MessageAttachmentListTypeHandler.class)
    })
    List<AttachmentRow> selectAttachmentRows(@Param("sessionId") String sessionId);

    /** 只写 {@code images} 列（对照 Go {@code UpdateMessageImages}，L306-311）。 */
    @Update("UPDATE messages SET images = "
            + "#{images, typeHandler=com.ragagent.session.domain.MessageImageListTypeHandler, "
            + "jdbcType=OTHER} "
            + "WHERE id = #{messageId} AND session_id = #{sessionId}")
    int updateImages(@Param("sessionId") String sessionId,
                     @Param("messageId") String messageId,
                     @Param("images") Object images);

    /** 只写 {@code rendered_content} 列（对照 Go {@code UpdateMessageRenderedContent}，L314-319）。 */
    @Update("UPDATE messages SET rendered_content = #{renderedContent} "
            + "WHERE id = #{messageId} AND session_id = #{sessionId}")
    int updateRenderedContent(@Param("sessionId") String sessionId,
                              @Param("messageId") String messageId,
                              @Param("renderedContent") String renderedContent);

    /**
     * 只写 {@code knowledge_id} 列（对照 Go {@code UpdateMessageKnowledgeID}，L327-334）。
     *
     * <p>注意 Go 这条**没有 session_id 条件**——只按主键。别顺手补上。</p>
     */
    @Update("UPDATE messages SET knowledge_id = #{knowledgeId} WHERE id = #{messageId}")
    int updateKnowledgeId(@Param("messageId") String messageId,
                          @Param("knowledgeId") String knowledgeId);

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
}
