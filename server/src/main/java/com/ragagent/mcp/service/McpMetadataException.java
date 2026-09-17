package com.ragagent.mcp.service;

import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;

/**
 * 目录快照语义错误（对照 Go types/mcp_metadata.go:11-24 的 5 个哨兵 error）。
 *
 * <p>Go 用 {@code errors.Is} 做身份判定，Java 用 {@link Kind} 枚举。
 * handler 层据此把错误映射成 HTTP 响应——<b>注意 refresh 与否会改变默认分支的文案，
 * 这个上下文只有 handler 知道</b>，所以这里把 Kind 暴露出去而不是把文案写死。</p>
 *
 * <p>默认（refresh=false 分支）的文案已经按 Go handler 的映射填好，
 * 直接抛出即可得到一致的响应；需要 refresh 专属文案时按 Kind 重映射。</p>
 */
public class McpMetadataException extends BizException {

    /** 对照 Go 的哨兵 error 身份 */
    public enum Kind {
        /** ErrMCPServiceNotFound："MCP service not found" */
        SERVICE_NOT_FOUND,
        /** ErrMCPOAuthPrincipalRequired：OAuth 目录缺少已认证 principal */
        PRINCIPAL_REQUIRED,
        /** ErrMCPMetadataStorage：元数据仓储不可用 */
        STORAGE_UNAVAILABLE,
        /** ErrMCPMetadataConnectionChanged：刷新期间连接配置被改 */
        CONNECTION_CHANGED,
        /** ErrMCPMetadataTooLarge：序列化目录超过 8 MiB */
        TOO_LARGE,
        /** ErrMCPMetadataInvalidTools：工具名为空或重复 */
        INVALID_TOOLS,
        /** 其它（默认分支） */
        OTHER
    }

    private final Kind kind;

    private McpMetadataException(Kind kind, AppError appError) {
        super(appError);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    // ── Go 原始哨兵文案（诊断/日志用，与 HTTP 文案不同） ──────────────

    /** 对照 ErrMCPServiceNotFound */
    public static final String MSG_SERVICE_NOT_FOUND = "MCP service not found";
    /** 对照 ErrMCPOAuthPrincipalRequired */
    public static final String MSG_PRINCIPAL_REQUIRED =
            "OAuth metadata requires an authenticated principal";
    /** 对照 ErrMCPMetadataStorage */
    public static final String MSG_STORAGE_UNAVAILABLE = "MCP metadata storage is unavailable";
    /** 对照 ErrMCPMetadataConnectionChanged */
    public static final String MSG_CONNECTION_CHANGED = "MCP connection changed during refresh";
    /** 对照 ErrMCPMetadataTooLarge */
    public static final String MSG_TOO_LARGE = "MCP metadata exceeds the 8 MiB storage limit";
    /** 对照 ErrMCPMetadataInvalidTools */
    public static final String MSG_INVALID_TOOLS =
            "MCP directory contains empty or duplicate tool names";

    // ── 工厂：文案逐字对照 Go handler mcpMetadataAppError（internal/handler/mcp_metadata.go:105） ──

    /** 对照 errors.NewNotFoundError("MCP service not found") */
    public static McpMetadataException serviceNotFound() {
        return new McpMetadataException(Kind.SERVICE_NOT_FOUND,
                AppError.notFound("MCP service not found"));
    }

    /** 对照 errors.NewUnauthorizedError("OAuth metadata requires an authenticated user") */
    public static McpMetadataException principalRequired() {
        return new McpMetadataException(Kind.PRINCIPAL_REQUIRED,
                AppError.unauthorized("OAuth metadata requires an authenticated user"));
    }

    /** 对照 errors.NewServiceUnavailableError("MCP metadata storage is unavailable") */
    public static McpMetadataException storageUnavailable() {
        return new McpMetadataException(Kind.STORAGE_UNAVAILABLE,
                AppError.serviceUnavailable("MCP metadata storage is unavailable"));
    }

    /** 对照 errors.NewConflictError("MCP connection changed during refresh; ...") */
    public static McpMetadataException connectionChanged() {
        return new McpMetadataException(Kind.CONNECTION_CHANGED,
                AppError.conflict("MCP connection changed during refresh; "
                        + "save the configuration and sync again"));
    }

    /** 对照 errors.NewBadRequestError("MCP directory is invalid or too large") */
    public static McpMetadataException invalidOrTooLarge(Kind kind) {
        return new McpMetadataException(kind,
                AppError.badRequest("MCP directory is invalid or too large"));
    }

    /** 对照 default 分支（refresh=false 时） */
    public static McpMetadataException readFailed() {
        return new McpMetadataException(Kind.OTHER, AppError.internal("Failed to read MCP metadata"));
    }
}
