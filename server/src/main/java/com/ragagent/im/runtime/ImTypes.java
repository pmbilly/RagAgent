package com.ragagent.im.runtime;

/**
 * IM 平台/模式/消息类型常量（对照 Go internal/im/{adapter.go,mode 语义} 的 string
 * 枚举族）。Go 侧全是 `type X string`——这里用 String 常量保逐字语义（这些值进
 * 渠道行、日志与 LLM 上下文，不进 HTTP 响应体）。
 */
public final class ImTypes {

    private ImTypes() {
    }

    // ── Platform（adapter.go L13-26） ────────────────────────────────────
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

    // ── SessionMode（adapter.go L31-36） ─────────────────────────────────
    /** user：按 (platform, user_id, chat_id, tenant_id) 解析会话。 */
    public static final String SESSION_MODE_USER = "user";
    /** thread：按 (platform, thread_id, chat_id, tenant_id) 解析会话。 */
    public static final String SESSION_MODE_THREAD = "thread";

    // ── MessageType（adapter.go L41-45） ─────────────────────────────────
    public static final String MESSAGE_TYPE_TEXT = "text";
    public static final String MESSAGE_TYPE_FILE = "file";
    public static final String MESSAGE_TYPE_IMAGE = "image";

    // ── ChatType（adapter.go L108-111） ──────────────────────────────────
    public static final String CHAT_TYPE_DIRECT = "direct";
    public static final String CHAT_TYPE_GROUP = "group";

    // ── 知识库入库渠道常量（对照 types/knowledge.go L20-40 的 imPlatformToChannel 消费面） ──
    public static final String CHANNEL_WECHAT = "wechat";
    public static final String CHANNEL_WECOM = "wecom";
    public static final String CHANNEL_FEISHU = "feishu";
    public static final String CHANNEL_DINGTALK = "dingtalk";
    public static final String CHANNEL_SLACK = "slack";
    public static final String CHANNEL_IM = "im";
}
