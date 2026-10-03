package com.ragagent.embed;

/**
 * embed 管理面的服务层错误。
 *
 * <p>错误分派：</p>
 * <ul>
 *   <li>{@code CHANNEL_NOT_FOUND} → 404 {"error":"embed channel not found"}；</li>
 *   <li>{@code BAD_REQUEST_TEXT}（webhook URL / launcher icon 校验）→ 400 + 错误原文；</li>
 *   <li>AppError(code==NotFound) → 404 + 原文（实际不可达：agent 归属校验对未知 agent
 *       抛的是普通错误——golden 钉死 ghost-agent / 跨租户 agent 都是 500）；</li>
 *   <li>其余（含 {@code CHANNEL_DISABLED} 走专用分支、{@code TOKEN_INVALID}、agent 归属
 *       失败等）→ 500 {"error":"operation failed"}。</li>
 * </ul>
 */
public final class EmbedError extends RuntimeException {

    public enum Kind {
        /** 404 {"error":"embed channel not found"}。 */
        CHANNEL_NOT_FOUND,
        /** 400 + 原文（webhook URL / launcher icon 校验）。 */
        BAD_REQUEST_TEXT,
        /** 500 {"error":"operation failed"}（含 agent 归属失败）。 */
        OPERATION_FAILED,
        /** 403 {"error":"embed channel is disabled"}（preview-session 专用分支）。 */
        CHANNEL_DISABLED,
        /** 401 {"error":"invalid embed channel or token"}（EmbedAuth 门）。 */
        TOKEN_INVALID,
        /** 503 {"error":"session tokens unavailable"}（Redis 不可用）。 */
        SESSION_UNAVAILABLE
    }

    public final Kind kind;

    private EmbedError(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public static EmbedError channelNotFound() {
        return new EmbedError(Kind.CHANNEL_NOT_FOUND, "embed channel not found");
    }

    public static EmbedError badRequest(String message) {
        return new EmbedError(Kind.BAD_REQUEST_TEXT, message);
    }

    public static EmbedError operationFailed() {
        return new EmbedError(Kind.OPERATION_FAILED, "operation failed");
    }

    public static EmbedError channelDisabled() {
        return new EmbedError(Kind.CHANNEL_DISABLED, "embed channel is disabled");
    }

    public static EmbedError tokenInvalid() {
        return new EmbedError(Kind.TOKEN_INVALID, "invalid embed channel or token");
    }

    public static EmbedError sessionUnavailable() {
        return new EmbedError(Kind.SESSION_UNAVAILABLE, "session tokens unavailable");
    }

    /** webhook URL 校验失败的错误文案族。 */
    public static EmbedError webhookInvalid(String detail) {
        return badRequest("invalid embed webhook URL: " + detail);
    }

    /** launcher icon 校验失败的错误文案族。 */
    public static EmbedError iconInvalid(String detail) {
        return badRequest("invalid embed launcher icon: " + detail);
    }
}
