package com.ragagent.common.session;

/**
 * 用户消息附件的「提示词视图」——附件渲染成 LLM 提示词段
 * （{@code common.prompt.MessageAttachmentsPrompt}）时读取的字段。
 *
 * <p>字段集严格等于渲染面：文件名/类型/大小、内容与截断信息、选块统计；
 * 附件的 id/url 等会话域细节不出域。</p>
 */
public record PipelineMessageAttachmentView(
        String fileName,
        String fileType,
        long fileSize,
        String contentMode,
        String content,
        boolean truncated,
        int lineCount,
        int selectedChunks,
        int totalChunks) {
}
