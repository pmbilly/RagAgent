package com.ragagent.auth.service;

import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;

/**
 * 成员/邀请域的 service 哨兵异常。
 *
 * <p><b>为什么不用统一异常映射</b>：同一个哨兵在不同端点的 HTTP 形态不同——
 * 如 MEMBERSHIP_NOT_FOUND 在
 * updateRole/removeMember 是 404 "membership not found"，在 leave
 * 是 404 "you are not a member of this workspace"。因此 service 只抛带 Kind 的
 * 领域异常，HTTP 状态与文案由 controller 的 switch 逐端点决定。</p>
 *
 * <p>{@link #getMessage()} 一律是锁定原文（"tenant membership not found" 等），
 * controller 直接用它当响应 message。</p>
 */
public class TenantRbacException extends RuntimeException {

    public enum Kind {
        /** "tenant membership not found" */
        MEMBERSHIP_NOT_FOUND,
        /** "tenant membership already exists" */
        MEMBERSHIP_ALREADY_EXISTS,
        /** "invalid tenant role" */
        INVALID_TENANT_ROLE,
        /** "API keys cannot assign the owner role" */
        API_KEY_CANNOT_ASSIGN_OWNER,
        /** "cannot demote or remove the last active owner of the tenant" */
        LAST_OWNER,
        /** "a pending invitation for this user already exists" */
        PENDING_INVITATION_EXISTS,
        /** "user is already an active member of the tenant" */
        ALREADY_MEMBER,
        /** "invitation not found" */
        INVITATION_NOT_FOUND,
        /** "invitation is no longer pending" */
        INVITATION_NOT_PENDING,
        /** "invitation has expired" */
        INVITATION_EXPIRED,
        /** "only the invitee can accept or decline this invitation" */
        INVITATION_FORBIDDEN,
        /** "invitation token is invalid or has been revoked" */
        INVITATION_TOKEN_INVALID
    }

    private final Kind kind;

    public TenantRbacException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    // ── 哨兵原文（controller 直接当 message 用） ───────────────────────────

    public static TenantRbacException membershipNotFound() {
        return new TenantRbacException(Kind.MEMBERSHIP_NOT_FOUND, "tenant membership not found");
    }

    public static TenantRbacException membershipAlreadyExists() {
        return new TenantRbacException(Kind.MEMBERSHIP_ALREADY_EXISTS, "tenant membership already exists");
    }

    public static TenantRbacException invalidTenantRole() {
        return new TenantRbacException(Kind.INVALID_TENANT_ROLE, "invalid tenant role");
    }

    public static TenantRbacException apiKeyCannotAssignOwner() {
        return new TenantRbacException(Kind.API_KEY_CANNOT_ASSIGN_OWNER,
                "API keys cannot assign the owner role");
    }

    public static TenantRbacException lastOwner() {
        return new TenantRbacException(Kind.LAST_OWNER,
                "cannot demote or remove the last active owner of the tenant");
    }

    public static TenantRbacException pendingInvitationExists() {
        return new TenantRbacException(Kind.PENDING_INVITATION_EXISTS,
                "a pending invitation for this user already exists");
    }

    public static TenantRbacException alreadyMember() {
        return new TenantRbacException(Kind.ALREADY_MEMBER,
                "user is already an active member of the tenant");
    }

    public static TenantRbacException invitationNotFound() {
        return new TenantRbacException(Kind.INVITATION_NOT_FOUND, "invitation not found");
    }

    public static TenantRbacException invitationNotPending() {
        return new TenantRbacException(Kind.INVITATION_NOT_PENDING, "invitation is no longer pending");
    }

    public static TenantRbacException invitationExpired() {
        return new TenantRbacException(Kind.INVITATION_EXPIRED, "invitation has expired");
    }

    public static TenantRbacException invitationForbidden() {
        return new TenantRbacException(Kind.INVITATION_FORBIDDEN,
                "only the invitee can accept or decline this invitation");
    }

    public static TenantRbacException invitationTokenInvalid() {
        return new TenantRbacException(Kind.INVITATION_TOKEN_INVALID,
                "invitation token is invalid or has been revoked");
    }

    /** 哨兵 → 默认 AppError 映射（供需要"码 + 原文"的端点复用） */
    public BizException asConflict() {
        return new BizException(AppError.conflict(getMessage()));
    }

    public BizException asValidationError() {
        return new BizException(AppError.validation(getMessage()));
    }

    public BizException asForbidden() {
        return new BizException(AppError.forbidden(getMessage()));
    }

    public BizException asNotFound() {
        return new BizException(AppError.notFound(getMessage()));
    }
}
