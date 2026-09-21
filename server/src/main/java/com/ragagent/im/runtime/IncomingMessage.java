package com.ragagent.im.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从 IM 回调解析出的统一消息（对照 Go {@code im.IncomingMessage}，internal/im/
 * adapter.go L48-103）。适配器负责把各平台载荷装进这个形状；γ2 的管线只认它。
 */
public final class IncomingMessage {

    /** 平台标识。 */
    public String platform;
    /** "text"（默认）或 "file"。 */
    public String messageType = ImTypes.MESSAGE_TYPE_TEXT;
    /** IM 平台的用户标识。 */
    public String userId = "";
    /** 用户显示名（可选）。 */
    public String userName = "";
    /** 群/频道 ID（私聊为空）。 */
    public String chatId = "";
    /** 私聊 vs 群聊。 */
    public String chatType = "";
    /** 文本内容（文件消息为空）。 */
    public String content = "";
    /** IM 平台的消息标识（去重用）。 */
    public String messageId = "";
    /** 平台文件标识（文件消息）。 */
    public String fileKey = "";
    /** 原始文件名（文件消息）。 */
    public String fileName = "";
    /** 文件大小（字节；文件消息，可选）。 */
    public long fileSize;
    /**
     * 平台线程标识（adapter.go L71-79）：
     * Slack=thread_ts（首条消息用自身 ts）、Mattermost=root_id（首条用 post_id）、
     * Feishu/Lark=root_id（首条用 message_id）、Telegram=message_thread_id（仅
     * Forum Topics）。WeCom/DingTalk 无线程支持为空。thread 模式下首条消息以自身
     * ID 作 ThreadID——每条首层消息各得一个新会话。
     */
    public String threadId = "";
    /** 引用/回复的消息（支持 quote-reply 的平台由适配器填）。 */
    public QuotedMessage quote;
    /** 平台私有字段（如 WeCom stream ID）。 */
    public Map<String, String> extra = new LinkedHashMap<>();

    public static IncomingMessage of(String platform, String userId, String content) {
        IncomingMessage m = new IncomingMessage();
        m.platform = platform;
        m.userId = userId;
        m.content = content;
        return m;
    }

    /**
     * 引用/回复消息（对照 Go {@code im.QuotedMessage}，adapter.go L89-103）。
     * NonTextType：引用消息没有可提取文本时记录原始类型（image/file/video）——
     * 用于生成 LLM 指令而非内容占位符（占位符会诱发幻觉）。
     */
    public static final class QuotedMessage {
        public String messageId = "";
        public String content = "";
        public String senderId = "";
        public boolean isBotMessage;
        public String nonTextType = "";
    }
}
