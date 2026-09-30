package com.ragagent.common.session;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.common.retrieval.SearchResult;

/**
 * 会话消息的「聊天管线视图」——chat 管线在端口往返与历史装载时真正读取的字段。
 *
 * <p>为什么需要它：管线要读消息的历史问答对（request_id/role/content）、图片描述、
 * 附件与知识引用，此前直接用 {@code session.domain.Message} 实体当载荷，
 * 使 chatpipeline 反向依赖会话域（与 {@code session → chatpipeline} 的装配/调用边成环）。
 * 载荷只带读取面：<b>需要更多字段时先改这里</b>，别把实体漏出去。</p>
 */
public record PipelineMessageView(
        String requestId,
        String role,
        String content,
        OffsetDateTime createdAt,
        List<PipelineMessageImageView> images,
        List<PipelineMessageAttachmentView> attachments,
        List<SearchResult> knowledgeReferences) {
}
