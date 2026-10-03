package com.ragagent.im.runtime;

/**
 * IM 平台/模式/消息类型常量（字符串常量形态；这些值进
 * 渠道行、日志与 LLM 上下文，不进 HTTP 响应体）。
 */
public final class ImTypes {

    private ImTypes() {
    }

    // ── Platform ─────────────────────────────────────────────────────────
    /** lark 是飞书国际版（open.larksuite.com）：共用 Feishu 适配器，仅 API host 与租户不同。 */
    public static final String PLATFORM_WECOM = "wecom";
    public static final String PLATFORM_FEISHU = "feishu";
    public static final String PLATFORM_LARK = "lark";
    public static final String PLATFORM_SLACK = "slack";
    public static final String PLATFORM_TELEGRAM = "telegram";
    public static final String PLATFORM_DINGTALK = "dingtalk";
    public static final String PLATFORM_MATTERMOST = "mattermost";
    public static final String PLATFORM_WECHAT = "wechat";
    public static final String PLATFORM_QQBOT = "qqbot";
    public static final String PLATFORM_YUNZHIJIA = "yunzhijia";

    // ── SessionMode ──────────────────────────────────────────────────────
    /** user：按 (platform, user_id, chat_id, tenant_id) 解析会话。 */
    public static final String SESSION_MODE_USER = "user";
    /** thread：按 (platform, thread_id, chat_id, tenant_id) 解析会话。 */
    public static final String SESSION_MODE_THREAD = "thread";

    // ── MessageType ──────────────────────────────────────────────────────
    public static final String MESSAGE_TYPE_TEXT = "text";
    public static final String MESSAGE_TYPE_FILE = "file";
    public static final String MESSAGE_TYPE_IMAGE = "image";

    // ── ChatType ─────────────────────────────────────────────────────────
    public static final String CHAT_TYPE_DIRECT = "direct";
    public static final String CHAT_TYPE_GROUP = "group";

    // ── 知识库入库渠道常量 ────────────────────────────────────────────────
    public static final String CHANNEL_WECHAT = "wechat";
    public static final String CHANNEL_WECOM = "wecom";
    public static final String CHANNEL_FEISHU = "feishu";
    public static final String CHANNEL_DINGTALK = "dingtalk";
    public static final String CHANNEL_SLACK = "slack";
    public static final String CHANNEL_IM = "im";
}
