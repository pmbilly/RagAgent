package com.ragagent.auth.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantInvitation;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.common.tenant.TenantRole;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.dto.TenantInvitationResponse;
import com.ragagent.auth.service.TenantInvitationService;
import com.ragagent.auth.service.TenantRbacException;
import com.ragagent.auth.service.TenantService;
import com.ragagent.auth.service.UserService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照 Go internal/handler/tenant_invitation.go（703 行）+ tenant_invite_link.go（111 行）
 * 的 9 条路由：租户侧 GET/POST /tenants/{id}/invitations、
 * DELETE /tenants/{id}/invitations/{inv_id}、POST /tenants/{id}/invite-links；
 * 收件箱 GET /me/invitations、GET /me/invitations/pending-count、
 * POST /me/invitations/{inv_id}/accept、POST /me/invitations/{inv_id}/decline、
 * POST /me/invitations/accept-by-token。
 *
 * <p><b>角色语义</b>：租户侧读 Viewer+、写 Owner+（RbacInterceptor）；/me 收件箱
 * 五条在 Go 里<b>无角色门</b>（RegisterMyInvitationRoutes 只挂 Auth）——Java 侧同样
 * 不登记 RBAC 规则，"只有被邀请人能操作"由 service 层把守。注意 accept-by-token
 * 也是**已登录**路由（Go 源码如此；与 /auth/register-by-invite 的公开+限流不同）。</p>
 *
 * <p><b>两层 404 刻意不同</b>：revoke 先 GetByID 再校验 inv.TenantID == URL :id——
 * 跨租户渲染成同款 "invitation not found"（不泄漏存在性）。</p>
 */
@RestController
public class TenantInvitationController {

    /** 对照 Go config.FrontendBaseURL / FRONTEND_BASE_URL env 的两级查找 */
    private static final String FRONTEND_BASE_URL_PROPERTY = "FRONTEND_BASE_URL";

    private final TenantInvitationService invitationService;
    private final UserService userService;
    private final TenantService tenantService;
    private final TenantMemberController memberController;
    private final Environment environment;

    public TenantInvitationController(TenantInvitationService invitationService,
                                      UserService userService,
                                      TenantService tenantService,
                                      TenantMemberController memberController,
                                      Environment environment) {
        this.invitationService = invitationService;
        this.userService = userService;
        this.tenantService = tenantService;
        this.memberController = memberController;
        this.environment = environment;
    }

    // ── 租户侧 ─────────────────────────────────────────────────────────────

    /** GET /tenants/{id}/invitations（Viewer+；Owner 及以上附 invite_url） */
    @GetMapping("/api/v1/tenants/{id}/invitations")
    public Map<String, Object> listTenantInvitations(@PathVariable String id, HttpServletRequest request) {
        long tenantId = TenantMemberController.parseTenantId(id);
        boolean includeTerminal = "true".equalsIgnoreCase(
                TenantMemberController.trimToEmpty(request.getParameter("include_terminal")));
        int[] pp = TenantMemberController.parseListPagination(request);

        TenantInvitationService.InvitationPage page =
                invitationService.listTenantInvitationsPage(tenantId, includeTerminal, pp[0], pp[1]);
        Map<String, User> usersById = hydrateUsers(page.invitations());
        // Share-link URL 内嵌注册 token——只有 Owner 及以上可以重取；其他角色看元数据
        boolean showShareLinks = currentRoleHasOwner();
        List<TenantInvitationResponse> resp = new ArrayList<>(page.invitations().size());
        for (TenantInvitation inv : page.invitations()) {
            // 租户视图不 hydrate 租户名（调用方已知道租户）——传 null
            resp.add(showShareLinks
                    ? projectInvitationWithLink(inv, usersById, null)
                    : projectInvitation(inv, usersById, null));
        }
        // gin.H 双层 map → 字母序 invitations < page < page_size < total
        Map<String, Object> data = new LinkedHashMap<>();
        // gin.H 字母序：invitations < page < page_size < total
        data.put("invitations", resp);
        data.put("page", pp[0]);
        data.put("page_size", pp[1]);
        data.put("total", page.total());
        return TenantMemberController.envelope(data);
    }

    /** POST /tenants/{id}/invitations（Owner+；auto-accept 开启时直加成员） */
    @PostMapping("/api/v1/tenants/{id}/invitations")
    public ResponseEntity<Map<String, Object>> createInvitation(@PathVariable String id,
                                                                HttpServletRequest request) {
        long tenantId = TenantMemberController.parseTenantId(id);
        JsonNode body = TenantMemberController.bindJson(TenantMemberController.rawBody(request));
        TenantMemberController.requireFields(body, "createInvitationRequest", "email", "role");
        String email = body.path("email").asText();
        if (!TenantMemberController.isValidEmailFormat(email)) {
            throw new BizException(AppError.validation("invalid request body")
                    .withDetails("Key: 'createInvitationRequest.Email' Error:Field validation for 'Email'"
                            + " failed on the 'email' tag"));
        }
        TenantRole role = TenantRole.fromString(body.path("role").asText());
        if (!role.isValid()) {
            throw new BizException(AppError.validation("role must be one of owner/admin/contributor/viewer"));
        }
        String message = body.path("message").isTextual() ? body.path("message").asText() : "";

        User user = userService.getUserByEmail(TenantMemberController.trimToEmpty(email));
        if (user == null) {
            throw new BizException(AppError.notFound(
                    "user with this email is not registered; ask them to sign up first"));
        }
        String invitedBy = TenantMemberController.invitedByForCaller(TenantContext.currentUserId());

        // auto-accept 开关：跳过 pending 邀请、直接把已注册用户加成成员（含对账+采纳默认空间）
        if (TenantInvitationService.autoAcceptInvitationEnabled()) {
            TenantMemberController.PreparedResponse r =
                    autoAcceptInvitationAndRespond(user, tenantId, role, invitedBy);
            return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).body(r.body());
        }

        try {
            TenantInvitation inv = invitationService.create(tenantId, user.getId(), role, invitedBy, message);
            Map<String, User> usersById = new HashMap<>();
            usersById.put(user.getId(), user);
            if (invitedBy != null) {
                // 尽力 hydrate inviter——失败降级为只有 invitee 字段
                User inviter = userService.getUserById(invitedBy);
                if (inviter != null) {
                    usersById.put(inviter.getId(), inviter);
                }
            }
            // Go：c.JSON(http.StatusCreated, ...)——创建邀请是 201
            return ResponseEntity.status(201)
                    .body(TenantMemberController.envelope(projectInvitation(inv, usersById, null)));
        } catch (TenantRbacException e) {
            switch (e.kind()) {
                case INVALID_TENANT_ROLE -> throw e.asValidationError();
                case API_KEY_CANNOT_ASSIGN_OWNER -> throw e.asForbidden();
                case PENDING_INVITATION_EXISTS -> throw e.asConflict();
                case ALREADY_MEMBER -> throw e.asConflict();
                default -> throw new BizException(AppError.internal("failed to create invitation")
                        .withDetails(e.getMessage()));
            }
        }
    }

    /** DELETE /tenants/{id}/invitations/{inv_id}（Owner+，撤销 pending） */
    @DeleteMapping("/api/v1/tenants/{id}/invitations/{inv_id}")
    public Map<String, Object> revokeInvitation(@PathVariable String id,
            @PathVariable("inv_id") String invId) {
        long tenantId = TenantMemberController.parseTenantId(id);
        long invIdNum = TenantMemberController.parseInvitationId(invId);

        // 跨租户校验邀请行的 tenant_id——不匹配渲染成同款 404（不泄漏存在性）
        TenantInvitation inv = invitationService.getById(invIdNum);
        if (inv == null) {
            throw new BizException(AppError.notFound("invitation not found"));
        }
        if (inv.getTenantId() != tenantId) {
            throw new BizException(AppError.notFound("invitation not found"));
        }
        try {
            invitationService.revoke(invIdNum);
        } catch (TenantRbacException e) {
            switch (e.kind()) {
                case INVITATION_NOT_FOUND -> throw e.asNotFound();
                case INVITATION_NOT_PENDING -> throw e.asConflict();
                default -> throw new BizException(AppError.internal("failed to revoke invitation")
                        .withDetails(e.getMessage()));
            }
        }
        return TenantMemberController.successOnly();
    }

    /** POST /tenants/{id}/invite-links（Owner+，多用途共享链接；201 + 带 invite_url） */
    @PostMapping("/api/v1/tenants/{id}/invite-links")
    public ResponseEntity<Map<String, Object>> createInviteLink(@PathVariable String id,
                                                                HttpServletRequest request) {
        long tenantId = TenantMemberController.parseTenantId(id);
        JsonNode body = TenantMemberController.bindJson(TenantMemberController.rawBody(request));
        TenantMemberController.requireFields(body, "createInviteLinkRequest", "role");
        TenantRole role = TenantRole.fromString(body.path("role").asText());
        if (!role.isValid()) {
            throw new BizException(AppError.validation("role must be one of owner/admin/contributor/viewer"));
        }
        String message = body.path("message").isTextual() ? body.path("message").asText() : "";
        String invitedBy = TenantMemberController.invitedByForCaller(TenantContext.currentUserId());

        TenantInvitation inv;
        try {
            inv = invitationService.createShareLink(tenantId, role, invitedBy, message);
        } catch (TenantRbacException e) {
            if (e.kind() == TenantRbacException.Kind.API_KEY_CANNOT_ASSIGN_OWNER) {
                throw e.asForbidden();
            }
            throw new BizException(AppError.internal("failed to create share link").withDetails(e.getMessage()));
        }
        Map<String, User> usersById = new HashMap<>();
        if (invitedBy != null) {
            User inviter = userService.getUserById(invitedBy);
            if (inviter != null) {
                usersById.put(inviter.getId(), inviter);
            }
        }
        return ResponseEntity.status(201)
                .body(TenantMemberController.envelope(projectInvitationWithLink(inv, usersById, null)));
    }

    // ── 收件箱（/me/invitations*，登录即可、无角色门） ────────────────────────

    /** GET /me/invitations（默认仅 pending；include_terminal=true 带终止态） */
    @GetMapping("/api/v1/me/invitations")
    public Map<String, Object> listMyInvitations(HttpServletRequest request) {
        String caller = TenantMemberController.requireCaller();
        boolean includeTerminal = "true".equalsIgnoreCase(
                TenantMemberController.trimToEmpty(request.getParameter("include_terminal")));
        List<TenantInvitation> rows = invitationService.listByInvitee(caller, includeTerminal);
        Map<String, User> usersById = hydrateUsers(rows);
        Map<Long, Tenant> tenantsById = hydrateTenants(rows);
        List<TenantInvitationResponse> resp = new ArrayList<>(rows.size());
        for (TenantInvitation inv : rows) {
            resp.add(projectInvitation(inv, usersById, tenantsById));
        }
        // gin.H{invitations, total} → 字母序（**没有** page/page_size 键）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("invitations", resp);
        data.put("total", resp.size());
        return TenantMemberController.envelope(data);
    }

    /** GET /me/invitations/pending-count（头像角标轮询的轻量端点） */
    @GetMapping("/api/v1/me/invitations/pending-count")
    public Map<String, Object> countMyPendingInvitations() {
        String caller = TenantMemberController.requireCaller();
        long count = invitationService.countPendingByInvitee(caller);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pending_count", count);
        return TenantMemberController.envelope(data);
    }

    /** POST /me/invitations/{inv_id}/accept（接受 + 写成员行 + 首空间采纳） */
    @PostMapping("/api/v1/me/invitations/{inv_id}/accept")
    public Map<String, Object> acceptMyInvitation(@PathVariable("inv_id") String invId) {
        String caller = TenantMemberController.requireCaller();
        long invIdNum = TenantMemberController.parseInvitationId(invId);
        TenantMember member;
        try {
            member = invitationService.accept(invIdNum, caller);
        } catch (TenantRbacException e) {
            throw acceptDeclineError(e, "failed to accept invitation");
        }
        adoptHomeTenantIfTenantless(caller, member);
        return membershipEnvelope(member, "");
    }

    /** POST /me/invitations/{inv_id}/decline（拒绝；不建成员行） */
    @PostMapping("/api/v1/me/invitations/{inv_id}/decline")
    public Map<String, Object> declineMyInvitation(@PathVariable("inv_id") String invId) {
        String caller = TenantMemberController.requireCaller();
        long invIdNum = TenantMemberController.parseInvitationId(invId);
        try {
            invitationService.decline(invIdNum, caller);
        } catch (TenantRbacException e) {
            throw acceptDeclineError(e, "failed to decline invitation");
        }
        return TenantMemberController.successOnly();
    }

    /** POST /me/invitations/accept-by-token（已登录用户用共享链接 token 加入；幂等） */
    @PostMapping("/api/v1/me/invitations/accept-by-token")
    public Object acceptMyInvitationByToken(HttpServletRequest request) {
        String caller = TenantMemberController.requireCaller();
        JsonNode body = TenantMemberController.bindJson(TenantMemberController.rawBody(request));
        requireToken(body);
        String token = TenantMemberController.trimToEmpty(body.path("token").asText());
        if (token.isEmpty()) {
            // 纯空白过了 binding（required 对 string 是非零值），由 trim 后的空检查拦下
            throw new BizException(AppError.validation("token is required"));
        }

        TenantMember member;
        try {
            member = invitationService.acceptByToken(token, caller);
        } catch (TenantRbacException e) {
            if (e.kind() == TenantRbacException.Kind.INVITATION_TOKEN_INVALID) {
                // 无效/过期/撤销统一 410 Gone（对照 LookupInvitationByToken）
                throw new BizException(new AppError(
                        com.ragagent.common.error.ErrorCode.NOT_FOUND.value(),
                        "invitation link is invalid or has been revoked", null, 410));
            }
            throw new BizException(AppError.internal("failed to accept invitation").withDetails(e.getMessage()));
        }
        adoptHomeTenantIfTenantless(caller, member);

        // 供前端切换空间展示
        String tenantName = "";
        Tenant tenant = tenantService.getTenantById(member.getTenantId());
        if (tenant != null) {
            tenantName = tenant.getName();
        }
        return membershipEnvelope(member, tenantName);
    }

    // ── 共享投影 / 辅助 ─────────────────────────────────────────────────────

    /** Accept/Decline 的哨兵→HTTP 映射（两处同形，Go 的 switch 逐字对应） */
    private static BizException acceptDeclineError(TenantRbacException e, String internalMessage) {
        switch (e.kind()) {
            case INVITATION_NOT_FOUND:
                return new BizException(AppError.notFound("invitation not found"));
            case INVITATION_FORBIDDEN:
                return e.asForbidden();
            case INVITATION_NOT_PENDING:
            case INVITATION_EXPIRED:
                return e.asConflict();
            default:
                return new BizException(AppError.internal(internalMessage).withDetails(e.getMessage()));
        }
    }

    /**
     * 对照 autoAcceptInvitationAndRespond：直加成员 → 对账 stale pending 行 →
     * 无空间用户采纳默认空间（失败 → 500 "member added but default workspace update failed"，
     * 成员行**已**写入）→ 201 成员结构。
     */
    private TenantMemberController.PreparedResponse autoAcceptInvitationAndRespond(
            User user, long tenantId, TenantRole role, String invitedBy) {
        TenantMemberController.PreparedResponse r =
                memberController.addMemberAndRespond(user, tenantId, role, invitedBy);
        invitationService.markPendingAcceptedIfExists(tenantId, user.getId());
        if (user.getTenantId() == null || user.getTenantId() == 0) {
            user.setTenantId(tenantId);
            try {
                userService.updateUser(user);
            } catch (RuntimeException e) {
                throw new BizException(AppError.internal("member added but default workspace update failed")
                        .withDetails(e.getMessage()));
            }
        }
        return r;
    }

    /**
     * 对照 Accept/AcceptByToken 的尾部：tenantless 账户把首个接受的空间设为默认
     * （成员关系才是授权来源；TenantID 只供登录/导航缺省）。更新失败 → 500。
     */
    private void adoptHomeTenantIfTenantless(String caller, TenantMember member) {
        User user = userService.getUserById(caller);
        if (user != null && (user.getTenantId() == null || user.getTenantId() == 0)) {
            user.setTenantId(member.getTenantId());
            try {
                userService.updateUser(user);
            } catch (RuntimeException e) {
                throw new BizException(
                        AppError.internal("invitation accepted but default workspace update failed")
                                .withDetails(e.getMessage()));
            }
        }
    }

    /** 对照 projectInvitation：inviter/invitee/tenant 字段尽力 hydrate，缺失降级为 id */
    static TenantInvitationResponse projectInvitation(TenantInvitation inv,
                                                      Map<String, User> usersById,
                                                      Map<Long, Tenant> tenantsById) {
        User invitee = usersById == null ? null : usersById.get(inv.getInviteeUserId());
        User inviter = inv.getInvitedBy() == null || usersById == null
                ? null
                : usersById.get(inv.getInvitedBy());
        Tenant tenant = tenantsById == null ? null : tenantsById.get(inv.getTenantId());
        return new TenantInvitationResponse(
                inv.getId(),
                inv.getTenantId(),
                tenant == null ? "" : tenant.getName(),
                inv.getInviteeUserId(),
                invitee == null ? "" : invitee.getEmail(),
                invitee == null ? "" : invitee.getUsername(),
                inv.getInvitedBy(),
                inviter == null ? "" : inviter.getEmail(),
                inviter == null ? "" : inviter.getUsername(),
                inv.getRole(),
                inv.getStatus(),
                inv.getMessage(),
                inv.getExpiresAt(),
                inv.getRespondedAt(),
                inv.getCreatedAt(),
                "",
                inv.getInviteeUserId() == null || inv.getInviteeUserId().isEmpty(),
                inv.getAcceptedCount());
    }

    /**
     * 对照 projectInvitationWithLink：仍处 pending 的 share-link 行附 invite_url
     * （FrontendBaseURL 缺省 → 宿主相对 "/register?token=…"，SPA 自行解析 origin）。
     * 收件箱路径**不**走这里（per-user 邀请没有可复制的 token）。
     */
    private TenantInvitationResponse projectInvitationWithLink(TenantInvitation inv,
                                                               Map<String, User> usersById,
                                                               Map<Long, Tenant> tenantsById) {
        TenantInvitationResponse base = projectInvitation(inv, usersById, tenantsById);
        String inviteUrl = "";
        if (TenantInvitationService.STATUS_PENDING.equals(inv.getStatus())
                && inv.getToken() != null && !inv.getToken().isEmpty()) {
            inviteUrl = frontendBaseUrl() + "/register?token=" + inv.getToken();
        }
        return new TenantInvitationResponse(base.id(), base.tenantId(), base.tenantName(),
                base.inviteeUserId(), base.inviteeEmail(), base.inviteeName(), base.invitedBy(),
                base.inviterEmail(), base.inviterName(), base.role(), base.status(), base.message(),
                base.expiresAt(), base.respondedAt(), base.createdAt(), inviteUrl,
                base.isShareLink(), base.acceptedCount());
    }

    /**
     * 对照 frontendBaseURLFor：配置属性 → 请求时 env（运维免重启滚动）→ 空串；
     * 去尾斜杠。Java 侧 Environment.getProperty 同时覆盖 yaml 属性与
     * FRONTEND_BASE_URL 环境变量（viper 绑定 + 运行时 env 的净效果一致）。
     */
    private String frontendBaseUrl() {
        String candidate = environment.getProperty(FRONTEND_BASE_URL_PROPERTY, "");
        String out = candidate == null ? "" : candidate.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** 对照 hydrateUsers：invitee + inviter 的并集批量查；失败降级空 map */
    private Map<String, User> hydrateUsers(List<TenantInvitation> invs) {
        Map<String, User> out = new HashMap<>();
        if (invs == null || invs.isEmpty()) {
            return out;
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (TenantInvitation inv : invs) {
            if (inv.getInviteeUserId() != null && !inv.getInviteeUserId().isEmpty()) {
                ids.add(inv.getInviteeUserId());
            }
            if (inv.getInvitedBy() != null) {
                ids.add(inv.getInvitedBy());
            }
        }
        try {
            return userService.getUsersByIds(ids);
        } catch (RuntimeException e) {
            return out;
        }
    }

    /** 对照 hydrateTenants：/me 视图跨空间，需要租户名 */
    private Map<Long, Tenant> hydrateTenants(List<TenantInvitation> invs) {
        Map<Long, Tenant> out = new HashMap<>();
        if (invs == null || invs.isEmpty()) {
            return out;
        }
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (TenantInvitation inv : invs) {
            ids.add(inv.getTenantId());
        }
        try {
            return tenantService.getTenantsByIds(ids);
        } catch (RuntimeException e) {
            return out;
        }
    }

    /** 对照 ListTenantInvitations 的 showShareLinks：调用方角色 ≥ Owner */
    private boolean currentRoleHasOwner() {
        return TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.OWNER);
    }

    /** 对照 accept-by-token 的绑定：token required（validator 原文进 details） */
    private static void requireToken(JsonNode body) {
        JsonNode n = body.get("token");
        boolean missing = n == null || n.isNull() || (n.isTextual() && n.asText().isEmpty());
        if (missing) {
            throw new BizException(AppError.validation("token is required")
                    .withDetails("Key: 'acceptInvitationByTokenRequest.Token' Error:Field validation for "
                            + "'Token' failed on the 'required' tag"));
        }
    }

    /**
     * membership 信封：gin.H 嵌套 map → 全字母序
     * （data &lt; success；membership 内 joined_at &lt; role &lt; status &lt; tenant_id）。
     * tenantName 非空时附 tenant_name（accept-by-token 专有，membership &lt; tenant_name）。
     */
    private static Map<String, Object> membershipEnvelope(TenantMember member, String tenantName) {
        Map<String, Object> membership = new LinkedHashMap<>();
        membership.put("joined_at", member.getJoinedAt());
        membership.put("role", member.getRole());
        membership.put("status", member.getStatus());
        membership.put("tenant_id", member.getTenantId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("membership", membership);
        if (tenantName != null && !tenantName.isEmpty()) {
            data.put("tenant_name", tenantName);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }
}
