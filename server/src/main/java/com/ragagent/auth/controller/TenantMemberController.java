package com.ragagent.auth.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.dto.TenantMemberResponse;
import com.ragagent.auth.service.TenantMemberService;
import com.ragagent.auth.service.TenantRbacException;
import com.ragagent.auth.service.UserService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.web.GoJsonBindError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照 Go internal/handler/tenant_member.go（442 行）的 5 条路由：
 * GET/POST /tenants/{id}/members、PUT/DELETE /tenants/{id}/members/{user_id}、
 * POST /tenants/{id}/leave。
 *
 * <p>角色门禁与 PathTenantMatch 在 RbacInterceptor（对照路由层 g.Viewer()/g.Owner()
 * 与组级 g.PathTenantMatch()），controller 不复查角色。本类还承载成员/邀请两组
 * 共用的绑定与分页辅助（对照 Go 的包级函数 parseTenantIDFromPath /
 * parseListPagination / addMemberAndRespond）。</p>
 *
 * <p><b>错误映射不共享</b>：Go 的 writeAddMemberError / UpdateMemberRole /
 * RemoveMember / LeaveTenant 各有一份 errors.Is 链——"找不到成员"的文案在
 * leave 里是 "you are not a member of this workspace"、别处是 "membership not
 * found"；直加/邀请的哨兵映射也不一致。controller 的 catch 逐端点复刻，
 * 不做统一映射（§9 波 2 chunk 第 1 条的同族教训）。</p>
 */
@RestController
public class TenantMemberController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Go 的 "system-<tenantID>" 合成用户（API-Key 主体）不入 invited_by */
    private static final String SYNTHETIC_PREFIX = "system-";

    private final TenantMemberService memberService;
    private final UserService userService;

    public TenantMemberController(TenantMemberService memberService, UserService userService) {
        this.memberService = memberService;
        this.userService = userService;
    }

    // ── 路由 ───────────────────────────────────────────────────────────────

    /** GET /tenants/{id}/members（Viewer+，分页 + q 筛选） */
    @GetMapping("/api/v1/tenants/{id}/members")
    public Map<String, Object> listMembers(@PathVariable String id, HttpServletRequest request) {
        long tenantId = parseTenantId(id);
        String q = trimToEmpty(request.getParameter("q"));
        int[] pp = parseListPagination(request);

        TenantMemberService.MemberPage page = memberService.listMembersPage(tenantId, q, pp[0], pp[1]);

        // 一批 hydrate（对照 Go：失败降级为空字段而非丢行，悬挂成员仍可被 Owner 清理）
        List<String> ids = new ArrayList<>();
        for (TenantMember m : page.members()) {
            ids.add(m.getUserId());
        }
        Map<String, User> usersById = userService.getUsersByIds(ids);

        List<TenantMemberResponse> resp = new ArrayList<>(page.members().size());
        for (TenantMember m : page.members()) {
            User u = usersById.get(m.getUserId());
            resp.add(new TenantMemberResponse(
                    m.getUserId(),
                    u == null ? "" : u.getEmail(),
                    u == null ? "" : u.getUsername(),
                    u == null ? "" : u.getAvatar(),
                    m.getRole(),
                    m.getStatus(),
                    m.getInvitedBy(),
                    m.getJoinedAt()));
        }
        // gin.H{data: gin.H{...}} 双层 map → 全字母序（members < page < page_size < total）
        Map<String, Object> data = new LinkedHashMap<>();
        // gin.H 字母序：members < page < page_size < total（golden 锁定）
        data.put("members", resp);
        data.put("page", pp[0]);
        data.put("page_size", pp[1]);
        data.put("total", page.total());
        return envelope(data);
    }

    /** POST /tenants/{id}/members（Owner+，直加路径；201 + TenantMemberResponse） */
    @PostMapping("/api/v1/tenants/{id}/members")
    public org.springframework.http.ResponseEntity<Map<String, Object>> addMember(
            @PathVariable String id, HttpServletRequest request) {
        long tenantId = parseTenantId(id);
        JsonNode body = bindJson(rawBody(request));
        requireFields(body, "addMemberRequest", "email", "role");
        String email = body.path("email").asText();
        if (!isValidEmailFormat(email)) {
            throw new BizException(AppError.validation("invalid request body")
                    .withDetails("Key: 'addMemberRequest.Email' Error:Field validation for 'Email'"
                            + " failed on the 'email' tag"));
        }
        TenantRole role = TenantRole.fromString(body.path("role").asText());
        if (!role.isValid()) {
            // 对照 handler 的 defence-in-depth：binding required 过了但角色值非法
            throw new BizException(AppError.validation("role must be one of owner/admin/contributor/viewer"));
        }

        User user = userService.getUserByEmail(trimToEmpty(email));
        if (user == null) {
            // ErrUserNotFound 是刻意的"还没注册"信号 → 404 让 UI 提示先注册
            throw new BizException(AppError.notFound(
                    "user with this email is not registered; ask them to sign up first"));
        }

        String invitedBy = invitedByForCaller(TenantContext.currentUserId());
        PreparedResponse r = addMemberAndRespond(user, tenantId, role, invitedBy);
        return org.springframework.http.ResponseEntity.status(r.status())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(r.body());
    }

    /** PUT /tenants/{id}/members/{user_id}（Owner+，改角色；ErrLastOwner → 409） */
    @PutMapping("/api/v1/tenants/{id}/members/{user_id}")
    public Map<String, Object> updateMemberRole(@PathVariable String id,
                                                @PathVariable("user_id") String userId,
                                                HttpServletRequest request) {
        long tenantId = parseTenantId(id);
        String targetUserId = trimToEmpty(userId);
        if (targetUserId.isEmpty()) {
            throw new BizException(AppError.validation("user_id is required"));
        }
        JsonNode body = bindJson(rawBody(request));
        requireFields(body, "updateMemberRoleRequest", "role");
        TenantRole role = TenantRole.fromString(body.path("role").asText());
        if (!role.isValid()) {
            throw new BizException(AppError.validation("role must be one of owner/admin/contributor/viewer"));
        }

        try {
            memberService.updateRole(targetUserId, tenantId, role);
        } catch (TenantRbacException e) {
            switch (e.kind()) {
                case MEMBERSHIP_NOT_FOUND -> throw new BizException(AppError.notFound("membership not found"));
                case LAST_OWNER -> throw e.asConflict();
                case INVALID_TENANT_ROLE -> throw e.asValidationError();
                case API_KEY_CANNOT_ASSIGN_OWNER -> throw e.asForbidden();
                default -> {
                    log(e);
                    throw new BizException(AppError.internal("failed to update member role")
                            .withDetails(e.getMessage()));
                }
            }
        }
        return successOnly();
    }

    /** DELETE /tenants/{id}/members/{user_id}（Owner+，软删；ErrLastOwner → 409） */
    @DeleteMapping("/api/v1/tenants/{id}/members/{user_id}")
    public Map<String, Object> removeMember(@PathVariable String id,
                                            @PathVariable("user_id") String userId) {
        long tenantId = parseTenantId(id);
        String targetUserId = trimToEmpty(userId);
        if (targetUserId.isEmpty()) {
            throw new BizException(AppError.validation("user_id is required"));
        }
        try {
            memberService.removeMember(targetUserId, tenantId);
        } catch (TenantRbacException e) {
            switch (e.kind()) {
                case MEMBERSHIP_NOT_FOUND -> throw new BizException(AppError.notFound("membership not found"));
                case LAST_OWNER -> throw e.asConflict();
                default -> {
                    log(e);
                    throw new BizException(AppError.internal("failed to remove member")
                            .withDetails(e.getMessage()));
                }
            }
        }
        return successOnly();
    }

    /** POST /tenants/{id}/leave（Viewer+，自助退出；ErrMembershipNotFound → 404 专属文案） */
    @PostMapping("/api/v1/tenants/{id}/leave")
    public Map<String, Object> leaveTenant(@PathVariable String id) {
        long tenantId = parseTenantId(id);
        String caller = requireCaller();
        try {
            memberService.removeMember(caller, tenantId);
        } catch (TenantRbacException e) {
            switch (e.kind()) {
                case MEMBERSHIP_NOT_FOUND ->
                        throw new BizException(AppError.notFound("you are not a member of this workspace"));
                case LAST_OWNER -> throw e.asConflict();
                default -> {
                    log(e);
                    throw new BizException(AppError.internal("failed to leave workspace")
                            .withDetails(e.getMessage()));
                }
            }
        }
        return successOnly();
    }

    // ── 共享辅助（成员/邀请两组共用，对照 Go 包级函数） ──────────────────────

    /** addMemberAndRespond 的结果：body + 显式状态码（201 直加成功用） */
    public record PreparedResponse(Map<String, Object> body, int status) {
    }

    /**
     * 对照 addMemberAndRespond：AddMember → 201 + TenantMemberResponse；
     * 哨兵 → writeAddMemberError 的映射（400/403/409/500）。
     * 公开给 TenantInvitationController（auto-accept 分支共用，对照 Go 的
     * 包级函数共享——两处映射永不漂移）。
     */
    public PreparedResponse addMemberAndRespond(User user, long tenantId, TenantRole role, String invitedBy) {
        try {
            TenantMember member = memberService.addMemberChecked(user.getId(), tenantId, role, invitedBy);
            // 对照 writeAddMemberSuccess：与列表端点同形，前端免二次往返
            return new PreparedResponse(envelope(memberResponseMap(member, user)), 201);
        } catch (TenantRbacException e) {
            switch (e.kind()) {
                case INVALID_TENANT_ROLE -> throw e.asValidationError();
                case API_KEY_CANNOT_ASSIGN_OWNER -> throw e.asForbidden();
                case MEMBERSHIP_ALREADY_EXISTS -> throw e.asConflict();
                default -> throw new BizException(AppError.internal("failed to add member")
                        .withDetails(e.getMessage()));
            }
        }
    }

    /** TenantMemberResponse → map（字段序 = Go struct 声明序，非字母序） */
    static Map<String, Object> memberResponseMap(TenantMember member, User user) {
        TenantMemberResponse r = memberResponse(member, user);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", r.userId());
        m.put("email", r.email());
        m.put("username", r.username());
        if (r.avatar() != null) {
            m.put("avatar", r.avatar());
        }
        m.put("role", r.role());
        m.put("status", r.status());
        if (r.invitedBy() != null) {
            m.put("invited_by", r.invitedBy());
        }
        m.put("joined_at", r.joinedAt());
        return m;
    }

    static Map<String, Object> envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }

    static Map<String, Object> successOnly() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return body;
    }

    /** 对照 parseTenantIDFromPath（通常已被 PathTenantMatch 拦在前面，保留同文案） */
    static long parseTenantId(String raw) {
        String s = trimToEmpty(raw);
        if (s.isEmpty()) {
            throw new BizException(AppError.validation("workspace id is required"));
        }
        try {
            long v = Long.parseLong(s);
            if (v <= 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new BizException(AppError.validation("workspace id must be a positive integer"));
        }
    }

    /** 对照 parseInvitationIDFromPath */
    static long parseInvitationId(String raw) {
        String s = trimToEmpty(raw);
        if (s.isEmpty()) {
            throw new BizException(AppError.validation("invitation id is required"));
        }
        try {
            long v = Long.parseLong(s);
            if (v <= 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new BizException(AppError.validation("invitation id must be a positive integer"));
        }
    }

    /**
     * 对照 parseListPagination：缺省 page=1 / size=20；**给了就必须合法**——
     * page 非正整数或非数字都是 400 "page must be a positive integer"，
     * size 出 [1,100] 是 400 "page_size must be between 1 and 100"（三段 if，不是 clamp）。
     */
    static int[] parseListPagination(HttpServletRequest request) {
        int page = 1;
        int pageSize = 20;
        String s = trimToEmpty(request.getParameter("page"));
        if (!s.isEmpty()) {
            try {
                int p = Integer.parseInt(s);
                if (p < 1) {
                    throw new NumberFormatException();
                }
                page = p;
            } catch (NumberFormatException e) {
                throw new BizException(AppError.validation("page must be a positive integer"));
            }
        }
        s = trimToEmpty(request.getParameter("page_size"));
        if (!s.isEmpty()) {
            try {
                int ps = Integer.parseInt(s);
                if (ps < 1 || ps > 100) {
                    throw new NumberFormatException();
                }
                pageSize = ps;
            } catch (NumberFormatException e) {
                throw new BizException(AppError.validation("page_size must be between 1 and 100"));
            }
        }
        return new int[] {page, pageSize};
    }

    static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /** 对照 types.IsSyntheticUserID：system-<id> 前缀（严格长于前缀） */
    static boolean isSyntheticUserId(String id) {
        if (id == null || id.length() <= SYNTHETIC_PREFIX.length()) {
            return false;
        }
        return id.startsWith(SYNTHETIC_PREFIX);
    }

    /** 对照 addMember/Create 的 invitedBy 归一：合成主体（API-Key）→ NULL */
    static String invitedByForCaller(String caller) {
        if (caller == null || caller.isEmpty() || isSyntheticUserId(caller)) {
            return null;
        }
        return caller;
    }

    /**
     * 绑定 JSON body：解析失败 → 400 validation("invalid request body") +
     * details=Go 解析器原文（GoJsonBindError 仿真；EOF 与字面量扫描逐字节一致）。
     */
    static JsonNode bindJson(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw new BizException(AppError.validation("invalid request body")
                    .withDetails(GoJsonBindError.message(null, null)));
        }
        try {
            return MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.validation("invalid request body")
                    .withDetails(GoJsonBindError.message(rawBody, e.getMessage())));
        }
    }

    /**
     * 对照 go-playground/validator 的 required tag：string 是"非零值"（空串失败、
     * 纯空白通过）。多失败字段按 struct 序 \n 连接进 details。
     */
    static void requireFields(JsonNode body, String structName, String... jsonFields) {
        List<String> lines = new ArrayList<>();
        for (String field : jsonFields) {
            JsonNode n = body.get(field);
            boolean missing = n == null || n.isNull() || (n.isTextual() && n.asText().isEmpty());
            if (missing) {
                String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
                lines.add("Key: '" + structName + "." + cap + "' Error:Field validation for '"
                        + cap + "' failed on the 'required' tag");
            }
        }
        if (!lines.isEmpty()) {
            throw new BizException(AppError.validation("invalid request body")
                    .withDetails(String.join("\n", lines)));
        }
    }

    /**
     * 对照 validator 的 email tag（简化子集：非空本地部分 + 恰一个 @ + 非空域名——
     * Go 的完整 RFC 正则接受的边角更宽；golden 只钉 "notanemail" 拒绝这一形态）。
     */
    static boolean isValidEmailFormat(String email) {
        int at = email.indexOf('@');
        return at > 0 && at == email.lastIndexOf('@') && at < email.length() - 1;
    }

    /** TenantMemberResponse 组装（直加路径的 email/username/avatar 来自刚查到的 user） */
    static TenantMemberResponse memberResponse(TenantMember member, User user) {
        return new TenantMemberResponse(
                member.getUserId(),
                user == null ? "" : user.getEmail(),
                user == null ? "" : user.getUsername(),
                user == null ? "" : user.getAvatar(),
                member.getRole(),
                member.getStatus(),
                member.getInvitedBy(),
                member.getJoinedAt());
    }

    /** caller user id（对照 types.UserIDFromContext；缺失 → 401） */
    static String requireCaller() {
        String caller = TenantContext.currentUserId();
        if (caller == null || caller.isEmpty()) {
            throw new BizException(AppError.unauthorized("caller user id missing from context"));
        }
        return caller;
    }

    static String rawBody(HttpServletRequest request) {
        if (request instanceof org.springframework.web.util.ContentCachingRequestWrapper cached) {
            byte[] buf = cached.getContentAsByteArray();
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        }
        try {
            return new String(request.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static void log(TenantRbacException e) {
        org.slf4j.LoggerFactory.getLogger(TenantMemberController.class)
                .error("tenant member op failed: kind={} msg={}", e.kind(), e.getMessage());
    }
}
