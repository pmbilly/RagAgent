package com.ragagent.auth.service;

import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;

/**
 * 成员/邀请域的 service 哨兵异常（对照 Go tenant_member.go / tenant_invitation.go
 * 的 sentinel errors + handler 的 errors.Is 分派）。
 *
 * <p><b>为什么不用统一异常映射</b>：同一个哨兵在不同端点的 HTTP 形态不同
 * （§9 波 2 chunk 第 1 条的同族教训）——如 {@code ErrMembershipNotFound} 在
 * UpdateMemberRole/RemoveMember 是 404 "membership not found"，在 LeaveTenant
 * 是 404 "you are not a member of this workspace"。因此 service 只抛带 Kind 的
 * 领域异常，HTTP 状态与文案由 controller 的 switch 逐端点决定（对照 Go 的
 * {@code errors.Is(err, service.ErrXxx)} 链）。</p>
 *
 * <p>{@link #message} 一律是 Go 哨兵的原文（"tenant membership not found" 等），
 * controller 直接用它当响应 message。</p>
 */
public class TenantRbacException extends RuntimeException {

    public enum Kind {
        /** ErrMembershipNotFound："tenant membership not found" */
        MEMBERSHIP_NOT_FOUND,
        /** ErrMembershipAlreadyExists："tenant membership already exists" */
        MEMBERSHIP_ALREADY_EXISTS,
        /** ErrInvalidTenantRole："invalid tenant role" */
        INVALID_TENANT_ROLE,
        /** ErrAPIKeyCannotAssignOwner："API keys cannot assign the owner role" */
        API_KEY_CANNOT_ASSIGN_OWNER,
        /** ErrLastOwner："cannot demote or remove the last active owner of the tenant" */
        LAST_OWNER,
        /** ErrPendingInvitationExists："a pending invitation for this user already exists" */
        PENDING_INVITATION_EXISTS,
        /** ErrAlreadyMember："user is already an active member of the tenant" */
        ALREADY_MEMBER,
        /** ErrInvitationNotFound："invitation not found" */
        INVITATION_NOT_FOUND,
        /** ErrInvitationNotPending："invitation is no longer pending" */
        INVITATION_NOT_PENDING,
        /** ErrInvitationExpired："invitation has expired" */
        INVITATION_EXPIRED,
        /** ErrInvitationForbidden："only the invitee can accept or decline this invitation" */
        INVITATION_FORBIDDEN,
        /** ErrInvitationTokenInvalid："invitation token is invalid or has been revoked" */
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

    // ── Go 哨兵原文（controller 直接当 message 用） ─────────────────────────

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

    /** 哨兵 → Go handler 里的默认 AppError 映射（供需要"码 + 原文"的端点复用） */
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
