package com.ragagent.im.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;


/**
 * 纯出站内容助手。带 IO 依赖的部分（rewriteStorageURLs/cleanIMContent 的 resolver 段）
 * 留在 Service。
 */
public final class ImFormat {

    private ImFormat() {
    }

    // ── 兜底文案与限额 ───────────────────────────────────────────────────
    public static final String IM_NO_ANSWER_FALLBACK = "抱歉，我暂时无法回答这个问题。";
    public static final String IM_ERROR_FALLBACK = "抱歉，处理您的问题时出现了异常，请稍后再试。";
    public static final String IM_CANCELLED_FALLBACK = "抱歉，回答已被取消。";

    public static final int MAX_CONTENT_LENGTH = 4096;
    /** 引用消息最多收进的字符数（code point）。 */
    public static final int MAX_QUOTE_CONTENT_LENGTH = 500;
    public static final int MAX_IM_ATTACHMENT_BYTES = 32 << 20; // 32 MiB
    public static final int MAX_IM_VISION_ATTACHMENT_BYTES = 8 << 20; // 8 MiB
    public static final int MAX_IM_ATTACHMENT_LINES = 500;
    public static final int MAX_IM_ATTACHMENT_CONTENT_BYTES = 32 << 10; // 32 KiB

    // ── 引用/XML 清理 ────────────────────────────────────────────────────

    private static final Pattern CITATION_TAG_RE = Pattern.compile("<(?:kb|web)\\b[^>]*/?>");
    private static final Pattern IMAGE_XML_BLOCK_RE = Pattern.compile("(?s)<image\\b[^>]*>.*?</image>");
    private static final Pattern IMAGE_ORIGINAL_RE = Pattern.compile("<image_original>(.*?)</image_original>");

    /** 移除 s 里的 &lt;kb .../&gt; 与 &lt;web .../&gt; 行内引用标签。 */
    public static String stripImCitationTags(String s) {
        return CITATION_TAG_RE.matcher(s).replaceAll("");
    }

    /**
     * 把 &lt;image&gt; 块收回普通 markdown：有 &lt;image_original&gt; 时提取原始
     * ![alt](url)，否则整块删除。
     */
    public static String stripImageXMLTags(String s) {
        java.util.regex.Matcher blockMatcher = IMAGE_XML_BLOCK_RE.matcher(s);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (blockMatcher.find()) {
            out.append(s, last, blockMatcher.start());
            String block = blockMatcher.group();
            java.util.regex.Matcher orig = IMAGE_ORIGINAL_RE.matcher(block);
            if (orig.find() && orig.groupCount() > 0) {
                out.append(orig.group(1));
            }
            last = blockMatcher.end();
        }
        out.append(s.substring(last));
        return out.toString();
    }

    // ── 流式 holdback ────────────────────────────────────────────────────

    /**
     * 匹配在串尾未闭合的 &lt;image…/&lt;kb…/&lt;web… 开标签。
     */
    private static final Pattern INCOMPLETE_XML_TAG_RE =
            Pattern.compile("<(?:image|image_original|image_caption|image_ocr|kb|web)[^>]*$");

    /** 串尾可能被截断的 XML 标签的偏移（按 code point 计），没有则 -1。
     *  本仓库 storageurl 的 holdback 同款换算。 */
    public static int findIncompleteXMLTag(String s) {
        java.util.regex.Matcher m = INCOMPLETE_XML_TAG_RE.matcher(s);
        if (!m.find()) {
            return -1;
        }
        return s.substring(0, m.start()).codePointCount(0, m.start());
    }

    /** chunk 尾部最早的不完整模式偏移；等于 len 表示整段可冲。 */
    public static int holdbackCutoff(String chunk) {
        int cutoff = com.ragagent.storage.support.StreamRewriter.holdbackCutoff(chunk);
        int idx = findIncompleteXMLTag(chunk);
        if (idx >= 0 && idx < cutoff) {
            cutoff = idx;
        }
        return cutoff;
    }

    // ── QA 失败兜底 ──────────────────────────────────────────────────────

    /**
     * QA 错误 → 用户可见 IM 兜底文案：null→无答案；取消/超时
     * （CancellationException/TimeoutException/InterruptedException，含 cause 链）
     * →已取消；其余→异常。
     */
    public static String imQAFailureReply(Throwable err) {
        if (err == null) {
            return IM_NO_ANSWER_FALLBACK;
        }
        if (isCanceledOrDeadline(err)) {
            return IM_CANCELLED_FALLBACK;
        }
        return IM_ERROR_FALLBACK;
    }

    private static boolean isCanceledOrDeadline(Throwable err) {
        for (Throwable c = err; c != null; c = c.getCause()) {
            if (c instanceof java.util.concurrent.CancellationException
                    || c instanceof java.util.concurrent.TimeoutException
                    || c instanceof InterruptedException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    // ── userKey / 引用上下文 ──────────────────────────────────────────────

    /** "channelID:userID:chatID[:threadID]"，用于每用户限额与 /stop。 */
    public static String makeUserKey(String channelId, String userId, String chatId,
            String threadId) {
        if (threadId != null && !threadId.isEmpty()) {
            return channelId + ":" + userId + ":" + chatId + ":" + threadId;
        }
        return channelId + ":" + userId + ":" + chatId;
    }

    /** 消息类型 → 中文标签（LLM 指令用）。 */
    private static final Map<String, String> NON_TEXT_TYPE_LABEL = buildNonTextLabel();

    private static Map<String, String> buildNonTextLabel() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("image", "图片");
        m.put("file", "文件");
        m.put("video", "视频");
        m.put("voice", "语音");
        return m;
    }

    /**
     * QuotedMessage → LLM 上下文的带标签字符串；null 返回空。非文本引用生成
     * "告知用户无法处理"的指令，而非会诱发幻觉的内容占位符。
     */
    public static String formatQuotedContext(IncomingMessage.QuotedMessage quote) {
        if (quote == null) {
            return "";
        }
        // 非文本引用：给指令，不给内容占位。
        if (quote.nonTextType != null && !quote.nonTextType.isEmpty()) {
            String label = NON_TEXT_TYPE_LABEL.get(quote.nonTextType);
            if (label == null || label.isEmpty()) {
                label = "该类型的";
            }
            return "用户引用了一条" + label + "消息，但你无法查看该内容。请直接告知用户你目前无法处理"
                    + label + "消息，建议用户用文字描述问题。不要猜测该消息的内容。";
        }
        if (quote.content == null || quote.content.isEmpty()) {
            return "";
        }
        String content = quote.content;
        if (content.codePointCount(0, content.length()) > MAX_QUOTE_CONTENT_LENGTH) {
            content = content.substring(0,
                    content.offsetByCodePoints(0, MAX_QUOTE_CONTENT_LENGTH)) + "...";
        }
        // 防止引用内容逃出 XML 标签边界。
        content = content.replace("</quoted_message>", "");
        String label = "以下是用户引用的一条历史消息，仅作为上下文参考：";
        if (quote.isBotMessage) {
            label = "以下是用户引用的你（机器人）之前的回复，仅作为上下文参考：";
        }
        return label + "\n<quoted_message>\n" + content + "\n</quoted_message>";
    }

    // ── 工具可见性 ───────────────────────────────────────────────────────

    private static final java.util.Set<String> INTERNAL_TOOL_NAMES =
            java.util.Set.of("thinking", "todo_write");

    /** 内部推理工具（thinking/规划）不向 IM 用户展示进度。 */
    public static boolean isToolVisibleToUser(String toolName) {
        return !INTERNAL_TOOL_NAMES.contains(toolName);
    }

    // ── 文件消息/扩展名/平台映射 ─────────────────────────────────────────

    /**
     * 文件型平台事件 → 合法 QA 查询：有 caption 用 caption；否则确认收到并
     * 询问如何协助（不谎称已读取内容）。
     */
    public static String fileMessageQAContent(IncomingMessage msg) {
        if (msg.content != null && !msg.content.strip().isEmpty()) {
            return msg.content;
        }
        String fileName = msg.fileName == null ? "" : msg.fileName.strip();
        if (fileName.isEmpty()) {
            fileName = "未命名文件";
        }
        return "我上传了文件「" + fileName + "」。请确认已收到，并告知我接下来可以如何协助。";
    }

    /** 文件名的小写扩展名（无点 → ""）。 */
    public static String fileExtension(String filename) {
        String[] parts = filename.split("\\.");
        if (parts.length < 2) {
            return "";
        }
        return parts[parts.length - 1].toLowerCase();
    }

    /** 文件消息入库支持的扩展名。 */
    public static final java.util.Set<String> SUPPORTED_KB_FILE_EXTS = java.util.Set.of(
            "pdf", "txt", "docx", "doc",
            "md", "markdown",
            "png", "jpg", "jpeg", "gif",
            "csv", "xlsx", "xls",
            "pptx", "ppt");

    /** IM 平台标识 → Knowledge.Channel 常量。 */
    public static String imPlatformToChannel(String platform) {
        switch (platform == null ? "" : platform.toLowerCase()) {
            case "wechat":
                return ImTypes.CHANNEL_WECHAT;
            case "wecom", "wxwork":
                return ImTypes.CHANNEL_WECOM;
            case "feishu", "lark":
                return ImTypes.CHANNEL_FEISHU;
            case "dingtalk":
                return ImTypes.CHANNEL_DINGTALK;
            case "slack":
                return ImTypes.CHANNEL_SLACK;
            default:
                return ImTypes.CHANNEL_IM;
        }
    }

    // ── 会话标题 ─────────────────────────────────────────────────────────

    /** user 模式标题："名字"/"user xxxxxxxx"/"user" + 群/私聊后缀。 */
    public static String buildUserSessionTitle(IncomingMessage msg) {
        StringBuilder b = new StringBuilder();
        if (msg.userName != null && !msg.userName.isEmpty()) {
            b.append(msg.userName);
        } else if (msg.userId != null && !msg.userId.isEmpty()) {
            b.append("user ").append(shortID(msg.userId));
        } else {
            b.append("user");
        }
        if (ImTypes.CHAT_TYPE_GROUP.equals(msg.chatType) && !msg.chatId.isEmpty()) {
            b.append(" · group ").append(shortID(msg.chatId));
        } else if (ImTypes.CHAT_TYPE_DIRECT.equals(msg.chatType)) {
            b.append(" · dm");
        }
        return b.toString();
    }

    /**
     * thread 模式标题：不同用户共享一个会话，所以不带用户名；聊天/线程 ID 承担
     * 区分职责。同理省略平台前缀。
     */
    public static String buildThreadSessionTitle(IncomingMessage msg) {
        StringBuilder b = new StringBuilder();
        if (!msg.chatId.isEmpty()) {
            b.append("chat ").append(shortID(msg.chatId)).append(" · ");
        }
        b.append("thread ").append(shortID(msg.threadId));
        return b.toString();
    }

    /** id 的最后 8 个**字节**字符（更短则原样）——长平台 ID 在标题里保持可读。 */
    public static String shortID(String id) {
        if (id.length() > 8) {
            return id.substring(id.length() - 8);
        }
        return id;
    }

    /**
     * 新 IM 会话的起始标题：消息有文本 → ""（后续像 web 聊天一样按内容起标题）；
     * 没有文本 → IM 身份标题，保证行不为空。
     */
    public static String imInitialSessionTitle(IncomingMessage msg,
            java.util.function.Function<IncomingMessage, String> identityTitle) {
        if (msg.content != null && !msg.content.strip().isEmpty()) {
            return "";
        }
        return identityTitle.apply(msg);
    }
}
