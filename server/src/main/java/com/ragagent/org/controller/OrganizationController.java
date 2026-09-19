package com.ragagent.org.controller;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ragagent.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.GuardForbiddenException;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.service.ChunkAccessGuard;
import com.ragagent.org.domain.AgentRow;
import com.ragagent.org.domain.AgentShare;
import com.ragagent.org.domain.KbShare;
import com.ragagent.org.domain.Organization;
import com.ragagent.org.domain.OrganizationJoinRequest;
import com.ragagent.org.domain.OrganizationTenantMember;
import com.ragagent.org.dto.OrgResponses;
import com.ragagent.org.mapper.AgentRowMapper;
import com.ragagent.org.mapper.OrganizationMapper;
import com.ragagent.org.service.AgentShareService;
import com.ragagent.org.service.AgentShareService.SharedAgentInfo;
import com.ragagent.org.service.KbShareService;
import com.ragagent.org.service.OrganizationService;
import com.ragagent.org.service.OrgServiceException;

/**
 * 组织与跨空间共享（对照 Go internal/handler/organization.go 的 35 条路由 +
 * routes_agent.go RegisterOrganizationRoutes 的守卫矩阵）。
 *
 * <p>错误形态全部逐字对照 handler 的 c.Error(AppError) 分支——包括 Go 侧两处
 * 文案不匹配导致 service 错误落 500 的真行为（RequestRoleUpgrade 的
 * "tenant is not a member..." / "tenant is already an admin"）。</p>
 */
@RestController
public class OrganizationController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OrganizationService orgService;
    private final KbShareService kbShareService;
    private final AgentShareService agentShareService;
    private final ChunkAccessGuard chunkAccessGuard;
    private final UserMapper userMapper;
    private final TenantService tenantService;
    private final OrganizationMapper orgMapper;
    private final AgentRowMapper agentRowMapper;

    public OrganizationController(OrganizationService orgService, KbShareService kbShareService,
                                  AgentShareService agentShareService, ChunkAccessGuard chunkAccessGuard,
                                  UserMapper userMapper, TenantService tenantService,
                                  OrganizationMapper orgMapper, AgentRowMapper agentRowMapper) {
        this.orgService = orgService;
        this.kbShareService = kbShareService;
        this.agentShareService = agentShareService;
        this.chunkAccessGuard = chunkAccessGuard;
        this.userMapper = userMapper;
        this.tenantService = tenantService;
        this.orgMapper = orgMapper;
        this.agentRowMapper = agentRowMapper;
    }

    // ══════════════ organizations 组 ══════════════

    @PostMapping("/api/v1/organizations")
    public ResponseEntity<Map<String, Object>> createOrganization(@RequestBody(required = false) String raw) {
        JsonNode req = bindOr400(raw);
        String name = text(req, "name");
        if (name.isEmpty()) {
            throw validator("CreateOrganizationRequest.Name");
        }
        Organization org;
        try {
            org = orgService.createOrganization(TenantContext.currentUserId(), TenantContext.currentTenantId(),
                    new OrganizationService.CreateRequest(name, text(req, "description"),
                            text(req, "avatar"), intOrNull(req, "invite_code_validity_days"),
                            intOrNull(req, "member_limit")));
        } catch (OrgServiceException e) {
            if (e.kind() == OrgServiceException.Kind.INVALID_VALIDITY_DAYS) {
                throw new BizException(AppError.validation(e.getMessage()));
            }
            throw new BizException(AppError.internal("Failed to create organization").withDetails(e.getMessage()));
        }
        return ResponseEntity.status(201).body(success("data",
                toOrgResponse(org)));
    }

    @GetMapping("/api/v1/organizations")
    public ResponseEntity<Map<String, Object>> listMyOrganizations() {
        long tenantId = TenantContext.currentTenantId();
        String userId = TenantContext.currentUserId();
        List<Organization> orgs = orgService.listTenantOrganizations(tenantId);
        List<Map<String, Object>> response = new ArrayList<>();
        for (Organization org : orgs) {
            response.add(toOrgResponse(org));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("organizations", response);
        resp.put("total", (long) response.size());
        Map<String, Object> resourceCounts = buildResourceCountsByOrg(orgs, tenantId);
        if (resourceCounts != null) {
            resp.put("resource_counts", resourceCounts);
        }
        return ResponseEntity.ok(success("data", resp));
    }

    @GetMapping("/api/v1/organizations/preview/{code}")
    public ResponseEntity<Map<String, Object>> previewByInviteCode(@PathVariable("code") String inviteCode) {
        long tenantId = TenantContext.currentTenantId();
        Organization org;
        try {
            org = orgService.getOrganizationByInviteCode(inviteCode);
        } catch (OrgServiceException e) {
            throw BizException.notFound("Invalid invite code");
        }
        int memberCount = orgService.listTenantMembers(org.getId()).size();
        int shareCount = kbShareService.listByOrganization(org.getId()).size();
        int agentShareCount = agentShareService.listByOrganization(org.getId()).size();
        boolean isAlreadyMember = orgService.getTenantMemberRow(org.getId(), tenantId) != null;
        Map<String, Object> data = new TreeMap<>();
        data.put("agent_share_count", agentShareCount);
        data.put("avatar", orEmpty(org.getAvatar()));
        data.put("created_at", org.getCreatedAt());
        data.put("description", orEmpty(org.getDescription()));
        data.put("id", org.getId());
        data.put("is_already_member", isAlreadyMember);
        data.put("member_count", memberCount);
        data.put("name", org.getName());
        data.put("require_approval", org.isRequireApproval());
        data.put("share_count", shareCount);
        return ResponseEntity.ok(success("data", data));
    }

    @PostMapping("/api/v1/organizations/join")
    public ResponseEntity<Map<String, Object>> joinByInviteCode(@RequestBody(required = false) String raw) {
        JsonNode req = bindOr400(raw);
        String inviteCode = text(req, "invite_code");
        if (inviteCode.isEmpty()) {
            throw validator("JoinOrganizationRequest.InviteCode");
        }
        String userId = TenantContext.currentUserId();
        long tenantId = TenantContext.currentTenantId();
        try {
            Organization org = orgService.joinByInviteCode(inviteCode, userId, tenantId);
            return ResponseEntity.ok(success("data", toOrgResponse(org)));
        } catch (OrgServiceException e) {
            if (e.kind() == OrgServiceException.Kind.MEMBER_LIMIT_REACHED) {
                throw new BizException(AppError.validation("该空间成员已满，无法加入"));
            }
            throw BizException.notFound("Invalid invite code");
        }
    }

    @PostMapping("/api/v1/organizations/join-request")
    public ResponseEntity<Map<String, Object>> submitJoinRequest(@RequestBody(required = false) String raw) {
        JsonNode req = bindOr400(raw);
        String inviteCode = text(req, "invite_code");
        if (inviteCode.isEmpty()) {
            throw validator("SubmitJoinRequestRequest.InviteCode");
        }
        String userId = TenantContext.currentUserId();
        long tenantId = TenantContext.currentTenantId();
        Organization org;
        try {
            org = orgService.getOrganizationByInviteCode(inviteCode);
        } catch (OrgServiceException e) {
            throw BizException.notFound("Invalid invite code");
        }
        if (!org.isRequireApproval()) {
            throw new BizException(AppError.validation("This organization does not require approval. Use the join endpoint instead."));
        }
        if (orgService.getTenantMemberRow(org.getId(), tenantId) != null) {
            throw new BizException(AppError.validation("Your workspace is already a member of this organization"));
        }
        String requestedRole = text(req, "role");
        if (!requestedRole.isEmpty() && !OrganizationService.isValidRole(requestedRole)) {
            throw new BizException(AppError.validation("Invalid role; must be viewer, editor, or admin"));
        }
        try {
            OrganizationJoinRequest request = orgService.submitJoinRequest(org.getId(), userId, tenantId,
                    text(req, "message"), requestedRole);
            return ResponseEntity.ok(success("data", OrgResponses.joinRequestEntity(request)));
        } catch (OrgServiceException e) {
            if (e.kind() == OrgServiceException.Kind.MEMBER_LIMIT_REACHED) {
                throw new BizException(AppError.validation("该空间成员已满，无法提交加入申请"));
            }
            if ("pending request already exists".equals(e.getMessage())) {
                throw new BizException(AppError.validation("You have already submitted a request to join this organization"));
            }
            throw BizException.internal("Failed to submit join request");
        }
    }

    @GetMapping("/api/v1/organizations/search")
    public ResponseEntity<Map<String, Object>> searchOrganizations(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", required = false) String limitRaw) {
        long tenantId = TenantContext.currentTenantId();
        int limit = 20;
        if (limitRaw != null && !limitRaw.isEmpty()) {
            try {
                int n = Integer.parseInt(limitRaw);
                if (n > 0 && n <= 100) {
                    limit = n;
                }
            } catch (NumberFormatException ignored) {
                // Go: 解析失败保留默认 20
            }
        }
        List<Organization> orgs = orgService.listSearchable(q == null ? "" : q, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Organization org : orgs) {
            int memberCount = (int) safeCountMembers(org.getId());
            int shareCount = kbShareService.listByOrganization(org.getId()).size();
            int agentShareCount = agentShareService.listByOrganization(org.getId()).size();
            items.add(OrgResponses.searchableOrgItem(org, memberCount, shareCount, agentShareCount,
                    orgService.getTenantMemberRow(org.getId(), tenantId) != null));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", items);
        body.put("success", true);
        body.put("total", (long) items.size());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/v1/organizations/join-by-id")
    public ResponseEntity<Map<String, Object>> joinByOrganizationID(@RequestBody(required = false) String raw) {
        JsonNode req = bindOr400(raw);
        String organizationId = text(req, "organization_id");
        if (organizationId.isEmpty()) {
            throw validator("JoinByOrganizationIDRequest.OrganizationID");
        }
        String userId = TenantContext.currentUserId();
        long tenantId = TenantContext.currentTenantId();
        String requestedRole = text(req, "role");
        if (!requestedRole.isEmpty() && !OrganizationService.isValidRole(requestedRole)) {
            throw new BizException(AppError.validation("Invalid role; must be viewer, editor, or admin"));
        }
        try {
            Organization org = orgService.joinByOrganizationID(organizationId, userId, tenantId,
                    text(req, "message"), requestedRole);
            return ResponseEntity.ok(success("data", toOrgResponse(org)));
        } catch (OrgServiceException e) {
            switch (e.kind()) {
                case ORG_NOT_FOUND -> throw BizException.notFound("Organization not found or not open for search");
                case ORG_PERMISSION_DENIED -> throw BizException.forbidden("Organization not open for search");
                case MEMBER_LIMIT_REACHED -> throw new BizException(AppError.validation("该空间成员已满，无法加入"));
                case INVALID_ROLE -> throw new BizException(AppError.validation("Invalid role"));
                default -> throw BizException.internal("Failed to join organization");
            }
        }
    }

    @GetMapping("/api/v1/organizations/{id}")
    public ResponseEntity<Map<String, Object>> getOrganization(@PathVariable("id") String orgId) {
        long tenantId = TenantContext.currentTenantId();
        String userId = TenantContext.currentUserId();
        Organization org;
        try {
            org = orgService.getOrganization(orgId);
        } catch (OrgServiceException e) {
            throw BizException.notFound("Organization not found");
        }
        if (!org.isSearchable() && orgService.getTenantMemberRow(org.getId(), tenantId) == null) {
            throw BizException.notFound("Organization not found");
        }
        return ResponseEntity.ok(success("data", toOrgResponse(org)));
    }

    @PutMapping("/api/v1/organizations/{id}")
    public ResponseEntity<Map<String, Object>> updateOrganization(@PathVariable("id") String orgId,
                                                                  @RequestBody(required = false) String raw) {
        JsonNode req = bindOr400(raw);
        String userId = TenantContext.currentUserId();
        long tenantId = TenantContext.currentTenantId();
        try {
            Organization org = orgService.updateOrganization(orgId, userId, tenantId,
                    new OrganizationService.UpdateRequest(textOrNull(req, "name"), textOrNull(req, "description"),
                            textOrNull(req, "avatar"), boolOrNull(req, "require_approval"),
                            boolOrNull(req, "searchable"), intOrNull(req, "invite_code_validity_days"),
                            intOrNull(req, "member_limit")));
            return ResponseEntity.ok(success("data", toOrgResponse(org)));
        } catch (OrgServiceException e) {
            if (e.kind() == OrgServiceException.Kind.INVALID_VALIDITY_DAYS) {
                throw new BizException(AppError.validation(e.getMessage()));
            }
            if (e.kind() == OrgServiceException.Kind.MEMBER_LIMIT_TOO_LOW) {
                throw new BizException(AppError.validation("当前成员数已超过新的上限，请先移除成员或设置更大的上限"));
            }
            throw BizException.forbidden("Permission denied or organization not found");
        }
    }

    @DeleteMapping("/api/v1/organizations/{id}")
    public ResponseEntity<Map<String, Object>> deleteOrganization(@PathVariable("id") String orgId) {
        String userId = TenantContext.currentUserId();
        long tenantId = TenantContext.currentTenantId();
        try {
            orgService.deleteOrganization(orgId, userId, tenantId);
        } catch (OrgServiceException e) {
            throw BizException.forbidden("Permission denied or organization not found");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Organization deleted successfully");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/v1/organizations/{id}/leave")
    public ResponseEntity<Map<String, Object>> leaveOrganization(@PathVariable("id") String orgId) {
        String userId = TenantContext.currentUserId();
        long tenantId = TenantContext.currentTenantId();
        Organization org;
        try {
            org = orgService.getOrganization(orgId);
        } catch (OrgServiceException e) {
            throw BizException.notFound("Organization not found");
        }
        boolean isOwnerTenant = org.ownerTenantIdOrZero() != 0 && org.ownerTenantIdOrZero() == tenantId;
        boolean isLegacyOwnerUser = org.ownerTenantIdOrZero() == 0 && userId != null
                && userId.equals(org.getOwnerId());
        if (isOwnerTenant || isLegacyOwnerUser) {
            throw BizException.forbidden(
                    "Organization owner cannot leave. Please transfer ownership or delete the organization.");
        }
        try {
            orgService.removeTenantMember(orgId, tenantId, userId, tenantId);
        } catch (OrgServiceException e) {
            throw BizException.internal("Failed to leave organization");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Left organization successfully");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/v1/organizations/{id}/request-upgrade")
    public ResponseEntity<Map<String, Object>> requestRoleUpgrade(@PathVariable("id") String orgId,
                                                                  @RequestBody(required = false) String raw) {
        JsonNode req = bindOr400(raw);
        String requestedRole = text(req, "requested_role");
        if (requestedRole.isEmpty()) {
            throw validator("RequestRoleUpgradeRequest.RequestedRole");
        }
        if (!OrganizationService.isValidRole(requestedRole)) {
            throw new BizException(AppError.validation("Invalid role; must be viewer, editor, or admin"));
        }
        try {
            OrganizationJoinRequest request = orgService.requestRoleUpgrade(orgId, TenantContext.currentUserId(),
                    TenantContext.currentTenantId(), requestedRole, text(req, "message"));
            return ResponseEntity.ok(success("data", OrgResponses.joinRequestEntity(request)));
        } catch (OrgServiceException e) {
            // 逐字对照 Go handler 的 err.Error() 匹配——其中两条与 service 文案不匹配 → 500
            if ("pending request already exists".equals(e.getMessage())) {
                throw new BizException(AppError.validation("You already have a pending upgrade request"));
            }
            if ("user is not a member of this organization".equals(e.getMessage())) {
                throw new BizException(AppError.validation("You are not a member of this organization"));
            }
            if ("user is already an admin".equals(e.getMessage())) {
                throw new BizException(AppError.validation("You are already an admin"));
            }
            if ("cannot request upgrade to same or lower role".equals(e.getMessage())) {
                throw new BizException(AppError.validation("Cannot request upgrade to same or lower role"));
            }
            throw BizException.internal("Failed to submit upgrade request");
        }
    }

    @PostMapping("/api/v1/organizations/{id}/invite-code")
    public ResponseEntity<Map<String, Object>> generateInviteCode(@PathVariable("id") String orgId) {
        String code;
        try {
            code = orgService.generateInviteCode(orgId, TenantContext.currentUserId(),
                    TenantContext.currentTenantId());
        } catch (OrgServiceException e) {
            throw BizException.forbidden("Permission denied");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("invite_code", code);
        return ResponseEntity.ok(success("data", data));
    }

    @GetMapping("/api/v1/organizations/{id}/search-tenants")
    public ResponseEntity<Map<String, Object>> searchTenantsForInvite(
            @PathVariable("id") String orgId,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", required = false) String limitRaw) {
        return doSearchTenantsForInvite(orgId, q, limitRaw);
    }

    /** 对照 SearchUsersForInvite：同一 handler 的 deprecated 别名。 */
    @GetMapping("/api/v1/organizations/{id}/search-users")
    public ResponseEntity<Map<String, Object>> searchUsersForInvite(
            @PathVariable("id") String orgId,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", required = false) String limitRaw) {
        return doSearchTenantsForInvite(orgId, q, limitRaw);
    }

    private ResponseEntity<Map<String, Object>> doSearchTenantsForInvite(String orgId, String q, String limitRaw) {
        long tenantId = TenantContext.currentTenantId();
        if (!orgService.isTenantOrgAdmin(orgId, tenantId)) {
            throw BizException.forbidden("Only organization admins can invite members");
        }
        if (q == null || q.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", List.of());
            body.put("success", true);
            return ResponseEntity.ok(body);
        }
        int limit = 10;
        if (limitRaw != null && !limitRaw.isEmpty()) {
            try {
                int n = Integer.parseInt(limitRaw);
                if (n > 0 && n <= 50) {
                    limit = n;
                }
            } catch (NumberFormatException ignored) {
                // Go: 解析失败保留默认 10
            }
        }
        java.util.Set<Long> existingTenantIds = new java.util.HashSet<>();
        for (OrganizationTenantMember m : orgService.listTenantMembers(orgId)) {
            existingTenantIds.add(m.getTenantId());
        }
        List<Tenant> tenants;
        try {
            tenants = tenantService.searchTenants(q, 0, 1, limit * 2).tenants();
        } catch (RuntimeException e) {
            throw BizException.internal("Failed to search candidates");
        }
        // 插入序去重：首个命中保留（对照 seen map + idx 恢复）
        List<Long> ordered = new ArrayList<>();
        for (Tenant t : tenants) {
            if (t == null || t.getId() == null || t.getId() == 0 || existingTenantIds.contains(t.getId())
                    || ordered.contains(t.getId())) {
                continue;
            }
            ordered.add(t.getId());
        }
        List<Map<String, Object>> sorted = new ArrayList<>();
        for (Long tid : ordered) {
            String name = tenantName(tid);
            if (name == null || name.isEmpty()) {
                continue;
            }
            sorted.add(OrgResponses.tenantInviteCandidate(tid, name));
            if (sorted.size() >= limit) {
                break;
            }
        }
        return ResponseEntity.ok(success("data", sorted));
    }

    @PostMapping("/api/v1/organizations/{id}/invite")
    public ResponseEntity<Map<String, Object>> inviteMember(@PathVariable("id") String orgId,
                                                            @RequestBody(required = false) String raw) {
        long tenantId = TenantContext.currentTenantId();
        if (!orgService.isTenantOrgAdmin(orgId, tenantId)) {
            throw BizException.forbidden("Only organization admins can invite members");
        }
        JsonNode req = bindOr400(raw);
        String role = text(req, "role");
        if (role.isEmpty()) {
            throw validator("InviteMemberRequest.Role");
        }
        if (!OrganizationService.isValidRole(role)) {
            throw new BizException(AppError.validation("Invalid role; must be viewer, editor, or admin"));
        }
        String userId = TenantContext.currentUserId();
        long targetTenantId = req.path("tenant_id").asLong(0);
        String representativeUserId = text(req, "representative_user_id");
        String legacyUserId = text(req, "user_id");
        if (targetTenantId != 0) {
            // 对照 GetTenantByID：缺失（返回 null）也走 404 "Workspace not found"
            if (tenantService.getTenantById(targetTenantId) == null) {
                throw BizException.notFound("Workspace not found");
            }
            if (representativeUserId.isEmpty()) {
                representativeUserId = legacyUserId;
            }
            if (!representativeUserId.isEmpty()) {
                User u = userMapper.selectById(representativeUserId);
                if (u == null || u.getTenantId() == null || u.getTenantId() != targetTenantId) {
                    representativeUserId = "";
                }
            }
        } else if (!legacyUserId.isEmpty()) {
            User invitedUser = userMapper.selectById(legacyUserId);
            if (invitedUser == null) {
                throw BizException.notFound("User not found");
            }
            targetTenantId = invitedUser.getTenantId() == null ? 0 : invitedUser.getTenantId();
            if (representativeUserId.isEmpty()) {
                representativeUserId = legacyUserId;
            }
        } else {
            throw new BizException(AppError.validation("Either tenant_id or user_id is required"));
        }
        if (orgService.getTenantMemberRow(orgId, targetTenantId) != null) {
            throw new BizException(AppError.validation("Workspace is already a member of this organization"));
        }
        try {
            orgService.addTenantMember(orgId, targetTenantId, representativeUserId, role);
        } catch (OrgServiceException e) {
            if (e.kind() == OrgServiceException.Kind.MEMBER_LIMIT_REACHED) {
                throw new BizException(AppError.validation("该空间成员已满，无法添加新成员"));
            }
            throw BizException.internal("Failed to add member");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Member added successfully");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/v1/organizations/{id}/members")
    public ResponseEntity<Map<String, Object>> listMembers(@PathVariable("id") String orgId) {
        long tenantId = TenantContext.currentTenantId();
        if (orgService.getTenantMemberRow(orgId, tenantId) == null) {
            throw BizException.forbidden("Your workspace is not a member of this organization");
        }
        List<OrganizationTenantMember> members = orgService.listTenantMembers(orgId);
        Map<Long, String> tenantNames = new HashMap<>();
        for (OrganizationTenantMember m : members) {
            if (m.getTenantId() != null && !tenantNames.containsKey(m.getTenantId())) {
                tenantNames.put(m.getTenantId(), tenantName(m.getTenantId()));
            }
        }
        List<Map<String, Object>> response = new ArrayList<>();
        for (OrganizationTenantMember m : members) {
            User rep = m.getRepresentativeUserId() == null || m.getRepresentativeUserId().isEmpty()
                    ? null : userMapper.selectById(m.getRepresentativeUserId());
            response.add(OrgResponses.memberResponse(m, tenantNames.get(m.getTenantId()), rep));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("members", response);
        data.put("total", (long) response.size());
        return ResponseEntity.ok(success("data", data));
    }

    @PutMapping("/api/v1/organizations/{id}/members/{tenantId}")
    public ResponseEntity<Map<String, Object>> updateMemberRole(@PathVariable("id") String orgId,
                                                                @PathVariable("tenantId") String memberTenantIdRaw,
                                                                @RequestBody(required = false) String raw) {
        long memberTenantId = parseTenantIdOr400(memberTenantIdRaw);
        JsonNode req = bindOr400(raw);
        String role = text(req, "role");
        if (role.isEmpty()) {
            throw validator("UpdateMemberRoleRequest.Role");
        }
        try {
            orgService.updateTenantMemberRole(orgId, memberTenantId, role,
                    TenantContext.currentUserId(), TenantContext.currentTenantId());
        } catch (OrgServiceException e) {
            throw BizException.forbidden("Permission denied or invalid operation");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Member role updated successfully");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/api/v1/organizations/{id}/members/{tenantId}")
    public ResponseEntity<Map<String, Object>> removeMember(@PathVariable("id") String orgId,
                                                            @PathVariable("tenantId") String memberTenantIdRaw) {
        long memberTenantId = parseTenantIdOr400(memberTenantIdRaw);
        try {
            orgService.removeTenantMember(orgId, memberTenantId, TenantContext.currentUserId(),
                    TenantContext.currentTenantId());
        } catch (OrgServiceException e) {
            throw BizException.forbidden("Permission denied or invalid operation");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Member removed successfully");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/v1/organizations/{id}/join-requests")
    public ResponseEntity<Map<String, Object>> listJoinRequests(@PathVariable("id") String orgId) {
        long tenantId = TenantContext.currentTenantId();
        if (!orgService.isTenantOrgAdmin(orgId, tenantId)) {
            throw BizException.forbidden("Only organization admins can view join requests");
        }
        List<OrganizationJoinRequest> requests = orgService.listJoinRequests(orgId);
        List<Map<String, Object>> resp = new ArrayList<>();
        for (OrganizationJoinRequest r : requests) {
            if (!"pending".equals(r.getStatus())) {
                continue;
            }
            resp.add(OrgResponses.joinRequestResponse(r, userById(r.getUserId())));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requests", resp);
        data.put("total", (long) resp.size());
        return ResponseEntity.ok(success("data", data));
    }

    @PutMapping("/api/v1/organizations/{id}/join-requests/{requestId}/review")
    public ResponseEntity<Map<String, Object>> reviewJoinRequest(@PathVariable("id") String orgId,
                                                                 @PathVariable("requestId") String requestId,
                                                                 @RequestBody(required = false) String raw) {
        long tenantId = TenantContext.currentTenantId();
        if (!orgService.isTenantOrgAdmin(orgId, tenantId)) {
            throw BizException.forbidden("Only organization admins can review join requests");
        }
        JsonNode req = bindOr400(raw);
        String assignRole = null;
        String role = text(req, "role");
        if (!role.isEmpty()) {
            if (!OrganizationService.isValidRole(role)) {
                throw new BizException(AppError.validation("Invalid role; must be viewer, editor, or admin"));
            }
            assignRole = role;
        }
        try {
            orgService.reviewJoinRequest(orgId, requestId, req.path("approved").asBoolean(false),
                    TenantContext.currentUserId(), tenantId, text(req, "message"), assignRole);
        } catch (OrgServiceException e) {
            if (e.kind() == OrgServiceException.Kind.MEMBER_LIMIT_REACHED) {
                throw new BizException(AppError.validation("空间成员已满，无法通过该加入申请"));
            }
            if ("request has already been reviewed".equals(e.getMessage())) {
                throw new BizException(AppError.validation("Request has already been reviewed"));
            }
            throw BizException.internal("Failed to review join request");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Review completed");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ══════════════ KB shares ══════════════

    @PostMapping("/api/v1/knowledge-bases/{id}/shares")
    public ResponseEntity<Map<String, Object>> shareKnowledgeBase(@PathVariable("id") String kbId,
                                                                  @RequestBody(required = false) String raw) {
        if (!APIKeyScopeContext.present()) {
            chunkAccessGuard.requireOwnedKbInCallerSpace(kbId);
        }
        JsonNode req = bindOr400(raw);
        String organizationId = text(req, "organization_id");
        if (organizationId.isEmpty()) {
            throw validator("ShareKnowledgeBaseRequest.OrganizationID");
        }
        String permission = text(req, "permission");
        if (permission.isEmpty()) {
            throw validator("ShareKnowledgeBaseRequest.Permission");
        }
        try {
            KbShare share = kbShareService.shareKnowledgeBase(kbId, organizationId,
                    TenantContext.currentUserId(), TenantContext.currentTenantId(), permission);
            return ResponseEntity.status(201).body(success("data", OrgResponses.kbShareEntity(share)));
        } catch (OrgServiceException e) {
            if ("only editors and admins can share knowledge bases to this organization".equals(e.getMessage())) {
                throw BizException.forbidden(
                        "Only editors and admins can share knowledge bases to this organization");
            }
            throw BizException.forbidden("Permission denied or invalid operation");
        }
    }

    @GetMapping("/api/v1/knowledge-bases/{id}/shares")
    public ResponseEntity<Map<String, Object>> listKBShares(@PathVariable("id") String kbId) {
        long tenantId = TenantContext.currentTenantId();
        if (tenantId == 0) {
            throw BizException.unauthorized("Unauthorized");
        }
        List<KbShare> shares;
        try {
            shares = kbShareService.listSharesByKnowledgeBase(kbId, tenantId);
        } catch (OrgServiceException e) {
            if ("knowledge base not found".equals(e.getMessage())) {
                throw BizException.notFound("Knowledge base not found");
            }
            if ("only knowledge base owner can share".equals(e.getMessage())) {
                throw BizException.forbidden("Only the knowledge base owner can list its shares");
            }
            throw BizException.internal("Failed to list shares");
        }
        List<Map<String, Object>> response = new ArrayList<>();
        for (KbShare s : shares) {
            // 对照 Go ListKBShares：只回填 organization_name，不回填 shared_by_username
            response.add(OrgResponses.kbShareResponse(s, kbShareService.orgName(s.getOrganizationId()), null));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shares", response);
        data.put("total", (long) response.size());
        return ResponseEntity.ok(success("data", data));
    }

    @PutMapping("/api/v1/knowledge-bases/{id}/shares/{shareId}")
    public ResponseEntity<Map<String, Object>> updateSharePermission(@PathVariable("id") String kbId,
                                                                     @PathVariable("shareId") String shareId,
                                                                     @RequestBody(required = false) String raw) {
        if (!APIKeyScopeContext.present()) {
            chunkAccessGuard.requireOwnedKbInCallerSpace(kbId);
        }
        JsonNode req = bindOr400(raw);
        String permission = text(req, "permission");
        if (permission.isEmpty()) {
            throw validator("UpdateSharePermissionRequest.Permission");
        }
        try {
            kbShareService.updateSharePermission(shareId, permission,
                    TenantContext.currentUserId(), TenantContext.currentTenantId());
        } catch (OrgServiceException e) {
            throw BizException.forbidden("Permission denied");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Share permission updated successfully");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/api/v1/knowledge-bases/{id}/shares/{shareId}")
    public ResponseEntity<Map<String, Object>> removeShare(@PathVariable("id") String kbId,
                                                           @PathVariable("shareId") String shareId) {
        if (!APIKeyScopeContext.present()) {
            chunkAccessGuard.requireOwnedKbInCallerSpace(kbId);
        }
        try {
            kbShareService.removeShare(shareId, TenantContext.currentUserId(), TenantContext.currentTenantId());
        } catch (OrgServiceException e) {
            throw BizException.forbidden("Permission denied");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Share removed successfully");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ══════════════ agent shares ══════════════

    @PostMapping("/api/v1/agents/{id}/shares")
    public ResponseEntity<Map<String, Object>> shareAgent(@PathVariable("id") String agentId,
                                                          @RequestBody(required = false) String raw) {
        requireOwnedAgentOrAdmin(agentId);
        JsonNode req = bindOr400(raw);
        String organizationId = text(req, "organization_id");
        if (organizationId.isEmpty()) {
            throw validator("ShareKnowledgeBaseRequest.OrganizationID");
        }
        String permission = text(req, "permission");
        if (permission.isEmpty()) {
            throw validator("ShareKnowledgeBaseRequest.Permission");
        }
        try {
            AgentShare share = agentShareService.shareAgent(agentId, organizationId,
                    TenantContext.currentUserId(), TenantContext.currentTenantId(), permission);
            return ResponseEntity.status(201).body(success("data", OrgResponses.agentShareEntity(share)));
        } catch (OrgServiceException e) {
            if (e.getMessage() != null
                    && e.getMessage().startsWith("only editors and admins can share agents")) {
                throw BizException.forbidden("Only editors and admins can share agents to this organization");
            }
            if ("agent is not fully configured (missing required chat model, or rerank model when the knowledge_search tool is enabled)"
                    .equals(e.getMessage())) {
                throw new BizException(AppError.validation(
                        "Agent is not fully configured. Please set the chat model, and set the rerank model if the knowledge_search tool is enabled in agent settings."));
            }
            throw BizException.forbidden("Permission denied or invalid operation");
        }
    }

    @GetMapping("/api/v1/agents/{id}/shares")
    public ResponseEntity<Map<String, Object>> listAgentShares(@PathVariable("id") String agentId) {
        requireOwnedAgentOrAdmin(agentId);
        long tenantId = TenantContext.currentTenantId();
        if (tenantId == 0) {
            throw BizException.unauthorized("Unauthorized");
        }
        List<AgentShare> shares;
        try {
            shares = agentShareService.listSharesByAgent(agentId, tenantId);
        } catch (OrgServiceException e) {
            if ("agent not found".equals(e.getMessage())) {
                throw BizException.notFound("Agent not found");
            }
            if ("only agent owner can share".equals(e.getMessage())) {
                throw BizException.forbidden("Only the agent owner can list its shares");
            }
            throw BizException.internal("Failed to list shares");
        }
        List<Map<String, Object>> response = new ArrayList<>();
        for (AgentShare s : shares) {
            response.add(OrgResponses.agentShareResponse(s, kbShareService.orgName(s.getOrganizationId())));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shares", response);
        data.put("total", response.size());
        return ResponseEntity.ok(success("data", data));
    }

    @DeleteMapping("/api/v1/agents/{id}/shares/{shareId}")
    public ResponseEntity<Map<String, Object>> removeAgentShare(@PathVariable("id") String agentId,
                                                                @PathVariable("shareId") String shareId) {
        requireOwnedAgentOrAdmin(agentId);
        try {
            agentShareService.removeShare(shareId, TenantContext.currentUserId(), TenantContext.currentTenantId());
        } catch (OrgServiceException e) {
            throw BizException.forbidden("Permission denied");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Share removed successfully");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ══════════════ org shares 读面 ══════════════

    @GetMapping("/api/v1/organizations/{id}/shares")
    public ResponseEntity<Map<String, Object>> listOrgShares(@PathVariable("id") String orgId) {
        long tenantId = TenantContext.currentTenantId();
        OrganizationTenantMember member = orgService.getTenantMemberRow(orgId, tenantId);
        if (member == null) {
            throw BizException.forbidden("Your workspace is not a member of this organization");
        }
        String myRoleInOrg = member.getRole();
        List<KbShare> shares = kbShareService.listByOrganization(orgId);
        List<Map<String, Object>> response = new ArrayList<>();
        for (KbShare s : shares) {
            String effectivePerm = s.getPermission();
            if (!OrganizationService.hasPermission(myRoleInOrg, s.getPermission())) {
                effectivePerm = myRoleInOrg;
            }
            KnowledgeBase kb = kbShareService.kbById(s.getKnowledgeBaseId());
            Long knowledgeCount = null;
            Long chunkCount = null;
            if (kb != null) {
                kbShareService.applyKbCounts(kb, s.getSourceTenantId());
                knowledgeCount = kb.getKnowledgeCount();
                chunkCount = kb.getChunkCount();
            }
            response.add(OrgResponses.kbShareResponseFull(s, myRoleInOrg, effectivePerm, kb,
                    knowledgeCount, chunkCount, username(s.getSharedByUserId())));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shares", response);
        data.put("total", (long) response.size());
        return ResponseEntity.ok(success("data", data));
    }

    @GetMapping("/api/v1/organizations/{id}/agent-shares")
    public ResponseEntity<Map<String, Object>> listOrgAgentShares(@PathVariable("id") String orgId) {
        long tenantId = TenantContext.currentTenantId();
        OrganizationTenantMember member = orgService.getTenantMemberRow(orgId, tenantId);
        if (member == null) {
            throw BizException.forbidden("Your workspace is not a member of this organization");
        }
        String myRoleInOrg = member.getRole();
        List<AgentShare> shares = agentShareService.listByOrganization(orgId);
        List<Map<String, Object>> response = new ArrayList<>();
        for (AgentShare s : shares) {
            String effectivePerm = s.getPermission();
            if (!OrganizationService.hasPermission(myRoleInOrg, s.getPermission())) {
                effectivePerm = myRoleInOrg;
            }
            AgentRow agent = agentRowMapper.getById(s.getAgentId());
            response.add(OrgResponses.agentShareResponseFull(s, myRoleInOrg, effectivePerm, agent,
                    kbShareService.orgName(s.getOrganizationId()), username(s.getSharedByUserId())));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shares", response);
        data.put("total", response.size());
        return ResponseEntity.ok(success("data", data));
    }

    @GetMapping("/api/v1/organizations/{id}/shared-knowledge-bases")
    public ResponseEntity<Map<String, Object>> listOrganizationSharedKnowledgeBases(
            @PathVariable("id") String orgId) {
        long tenantId = TenantContext.currentTenantId();
        TenantRole callerTenantRole = OrganizationService.callerTenantRole();
        List<Map<String, Object>> rows;
        try {
            rows = listSpaceKnowledgeBasesInOrganization(orgId, tenantId, callerTenantRole);
        } catch (OrgServiceException e) {
            if (e.kind() == OrgServiceException.Kind.TENANT_NOT_IN_ORG) {
                throw BizException.forbidden("Your workspace is not a member of this organization");
            }
            throw BizException.internal("Failed to list shared knowledge bases");
        } catch (RuntimeException e) {
            throw BizException.internal("Failed to list shared knowledge bases");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", rows);
        body.put("success", true);
        body.put("total", rows.size());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/v1/organizations/{id}/shared-agents")
    public ResponseEntity<Map<String, Object>> listOrganizationSharedAgents(@PathVariable("id") String orgId) {
        long tenantId = TenantContext.currentTenantId();
        TenantRole callerTenantRole = OrganizationService.callerTenantRole();
        List<AgentShareService.OrgSharedAgentItem> list;
        try {
            list = agentShareService.listSharedAgentsInOrganization(orgId, tenantId, callerTenantRole);
        } catch (OrgServiceException e) {
            if (e.kind() == OrgServiceException.Kind.TENANT_NOT_IN_ORG) {
                throw BizException.forbidden("Your workspace is not a member of this organization");
            }
            throw BizException.internal("Failed to list shared agents");
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (AgentShareService.OrgSharedAgentItem item : list) {
            enrichAgentInfo(item.info(), orgId);
            data.add(agentInfoMap(item.info(), item.isMine()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        body.put("total", data.size());
        return ResponseEntity.ok(body);
    }

    // ══════════════ shared-* ══════════════

    @GetMapping("/api/v1/shared-knowledge-bases")
    public ResponseEntity<Map<String, Object>> listSharedKnowledgeBases() {
        long tenantId = TenantContext.currentTenantId();
        TenantRole callerTenantRole = OrganizationService.callerTenantRole();
        List<KbShareService.SharedKbInfo> sharedKBs;
        try {
            sharedKBs = kbShareService.listSharedKnowledgeBases(tenantId, callerTenantRole);
        } catch (RuntimeException e) {
            throw BizException.internal("Failed to list shared knowledge bases");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KbShareService.SharedKbInfo info : sharedKBs) {
            KbShareService.KbRaw raw = kbShareService.getKbRaw(info.knowledgeBase().getId());
            rows.add(OrgResponses.sharedKbRow(info.knowledgeBase(), info.shareId(), info.organizationId(),
                    info.orgName(), info.permission(),
                    info.sourceTenantId() == null ? 0L : info.sourceTenantId(), info.sharedAt(), null,
                    raw.indexingStrategyJson(), raw.storageBackendId()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", rows);
        body.put("success", true);
        body.put("total", rows.size());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/v1/shared-agents")
    public ResponseEntity<Map<String, Object>> listSharedAgents() {
        long tenantId = TenantContext.currentTenantId();
        TenantRole callerTenantRole = OrganizationService.callerTenantRole();
        List<SharedAgentInfo> list;
        try {
            list = agentShareService.listSharedAgents(tenantId, callerTenantRole);
        } catch (RuntimeException e) {
            throw BizException.internal("Failed to list shared agents");
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (SharedAgentInfo info : list) {
            enrichAgentInfo(info, info.organizationId());
            data.add(agentInfoMap(info, null));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        body.put("total", data.size());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/v1/shared-agents/disabled")
    public ResponseEntity<Map<String, Object>> setSharedAgentDisabledByMe(
            @RequestBody(required = false) String raw) {
        long tenantId = TenantContext.currentTenantId();
        JsonNode req = bindBadRequest(raw);
        String agentId = text(req, "agent_id");
        if (agentId.isEmpty()) {
            throw validator("SetSharedAgentDisabledByMeRequest.AgentID");
        }
        boolean disabled = req.path("disabled").asBoolean(false);
        long sourceTenantId;
        AgentRow agent = agentRowMapper.getById(agentId);
        if (agent != null && agent.getTenantId() != null && agent.getTenantId() == tenantId) {
            sourceTenantId = tenantId;
        } else {
            AgentShare share = agentShareService.getShareByAgentIdForTenant(tenantId, agentId, tenantId);
            if (share == null) {
                throw BizException.forbidden("No access to this agent");
            }
            sourceTenantId = share.getSourceTenantId() == null ? 0L : share.getSourceTenantId();
        }
        try {
            agentShareService.setSharedAgentDisabledByMe(tenantId, agentId, sourceTenantId, disabled);
        } catch (RuntimeException e) {
            throw BizException.internal("Failed to update preference");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ══════════════ 内部辅助 ══════════════

    /** 对照 toOrgResponse（含邀请码/pending 可见性与成员/共享计数）。 */
    private Map<String, Object> toOrgResponse(Organization org) {
        long currentTenantId = TenantContext.currentTenantId();
        String currentUserID = TenantContext.currentUserId();
        boolean isOwner = org.ownerTenantIdOrZero() != 0
                ? org.ownerTenantIdOrZero() == currentTenantId
                : currentUserID != null && currentUserID.equals(org.getOwnerId());
        int memberCount = orgService.listTenantMembers(org.getId()).size();
        int shareCount = kbShareService.listByOrganization(org.getId()).size();
        int agentShareCount = agentShareService.listByOrganization(org.getId()).size();
        String myRole = "";
        try {
            myRole = orgService.getTenantRoleInOrg(org.getId(), currentTenantId);
        } catch (OrgServiceException ignored) {
            myRole = "";
        }
        boolean isAdmin = "admin".equals(myRole);
        String inviteCode = null;
        OffsetDateTime inviteCodeExpiresAt = null;
        Integer pendingJoinRequestCount = null;
        if (isAdmin || isOwner) {
            inviteCode = org.getInviteCode();
            inviteCodeExpiresAt = org.getInviteCodeExpiresAt();
            pendingJoinRequestCount = (int) orgService.countPendingJoinRequests(org.getId());
        }
        boolean hasPendingUpgrade = orgService.getPendingUpgradeRequest(org.getId(), currentTenantId) != null;
        return OrgResponses.orgResponse(org, isOwner, currentTenantId, currentUserID, memberCount,
                shareCount, agentShareCount, myRole, hasPendingUpgrade, inviteCode,
                inviteCodeExpiresAt, pendingJoinRequestCount);
    }

    /** 对照 buildResourceCountsByOrg（批量接口 + 内存合并；失败降级为无 resource_counts）。 */
    private Map<String, Object> buildResourceCountsByOrg(List<Organization> orgs, long tenantId) {
        List<String> orgIds = new ArrayList<>();
        for (Organization o : orgs) {
            orgIds.add(o.getId());
        }
        Map<String, Long> agentCounts;
        Map<String, List<String>> directKbIdsByOrg;
        Map<String, List<AgentShareService.OrgSharedAgentItem>> agentListByOrg;
        try {
            agentCounts = agentShareService.countByOrganizations(orgIds);
            directKbIdsByOrg = kbShareService.listSharedKbIdsByOrganizations(orgIds, tenantId);
            Map<String, OrganizationTenantMember> members =
                    orgService.listMembersByTenantForOrgs(tenantId, orgIds);
            agentListByOrg = agentShareService.listSharedAgentsInOrganizations(orgIds, tenantId,
                    OrganizationService.callerTenantRole(), members);
        } catch (RuntimeException e) {
            return null;
        }
        Map<String, Integer> byOrgKb = new LinkedHashMap<>();
        Map<Long, List<String>> tenantKbCache = new HashMap<>();
        for (Organization o : orgs) {
            java.util.Set<String> directSet = new java.util.HashSet<>();
            List<String> directIds = directKbIdsByOrg.getOrDefault(o.getId(), List.of());
            directSet.addAll(directIds);
            int count = directIds.size();
            for (AgentShareService.OrgSharedAgentItem item : agentListByOrg.getOrDefault(o.getId(), List.of())) {
                AgentRow agent = item.info().agent();
                if (agent == null) {
                    continue;
                }
                JsonNode cfg = parse(agent.getConfig());
                String mode = cfg == null ? "" : text(cfg, "kb_selection_mode");
                if ("none".equals(mode)) {
                    continue;
                }
                List<String> kbIds = null;
                switch (mode) {
                    case "selected" -> {
                        List<String> selected = strSlice(cfg, "knowledge_bases");
                        if (selected == null || selected.isEmpty()) {
                            continue;
                        }
                        kbIds = selected;
                    }
                    case "all" -> {
                        Long tid = agent.getTenantId();
                        if (!tenantKbCache.containsKey(tid)) {
                            tenantKbCache.put(tid, kbIdsByTenant(tid));
                        }
                        kbIds = tenantKbCache.get(tid);
                    }
                    default -> {
                        List<String> selected = strSlice(cfg, "knowledge_bases");
                        if (selected != null && !selected.isEmpty()) {
                            kbIds = selected;
                        }
                    }
                }
                if (kbIds == null) {
                    continue;
                }
                for (String kbId : kbIds) {
                    if (kbId != null && !kbId.isEmpty() && !directSet.contains(kbId)) {
                        directSet.add(kbId);
                        count++;
                    }
                }
            }
            byOrgKb.put(o.getId(), count);
        }
        Map<String, Integer> byOrgAgent = new TreeMap<>();
        for (Organization o : orgs) {
            byOrgAgent.put(o.getId(), 0);
        }
        for (Map.Entry<String, Long> e : agentCounts.entrySet()) {
            byOrgAgent.put(e.getKey(), e.getValue().intValue());
        }
        Map<String, Object> kbs = new LinkedHashMap<>();
        Map<String, Object> kbByOrg = new TreeMap<>();
        kbByOrg.putAll(byOrgKb);
        kbs.put("by_organization", kbByOrg);
        Map<String, Object> agents = new LinkedHashMap<>();
        agents.put("by_organization", byOrgAgent);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("knowledge_bases", kbs);
        out.put("agents", agents);
        return out;
    }

    /** 对照 listSpaceKnowledgeBasesInOrganization（直接共享 + 共享智能体携带的 KB 合并）。 */
    private List<Map<String, Object>> listSpaceKnowledgeBasesInOrganization(String orgId, long tenantId,
                                                                            TenantRole callerTenantRole) {
        List<KbShareService.OrgSharedKbItem> directList =
                kbShareService.listSharedInOrganization(orgId, tenantId, callerTenantRole);
        java.util.Set<String> directKbIds = new java.util.HashSet<>();
        for (KbShareService.OrgSharedKbItem item : directList) {
            if (item.info().knowledgeBase() != null && !item.info().knowledgeBase().getId().isEmpty()) {
                directKbIds.add(item.info().knowledgeBase().getId());
            }
        }
        List<AgentShareService.OrgSharedAgentItem> agentList =
                agentShareService.listSharedAgentsInOrganization(orgId, tenantId, callerTenantRole);
        String orgName = "";
        if (!agentList.isEmpty() && orgId.equals(agentList.get(0).info().organizationId())) {
            orgName = agentList.get(0).info().orgName();
        }
        if (orgName.isEmpty()) {
            try {
                orgName = orgService.getOrganization(orgId).getName();
            } catch (OrgServiceException ignored) {
                orgName = "";
            }
        }
        // row 副产物：extras（is_mine + 可选 source_from_agent）与 info 绑定携带
        List<MergedKbRow> merged = new ArrayList<>();
        for (KbShareService.OrgSharedKbItem item : directList) {
            merged.add(new MergedKbRow(item.info(), item.isMine(), null));
        }
        for (AgentShareService.OrgSharedAgentItem agentItem : agentList) {
            AgentRow agent = agentItem.info().agent();
            if (agent == null) {
                continue;
            }
            JsonNode cfg = parse(agent.getConfig());
            String mode = cfg == null ? "" : text(cfg, "kb_selection_mode");
            if ("none".equals(mode)) {
                continue;
            }
            List<String> kbIds;
            switch (mode) {
                case "selected" -> {
                    kbIds = strSlice(cfg, "knowledge_bases");
                    if (kbIds == null || kbIds.isEmpty()) {
                        continue;
                    }
                }
                case "all" -> kbIds = kbIdsByTenant(agent.getTenantId());
                default -> {
                    kbIds = strSlice(cfg, "knowledge_bases");
                    if (kbIds == null || kbIds.isEmpty()) {
                        continue;
                    }
                }
            }
            String agentName = agent.getName() == null || agent.getName().isEmpty()
                    ? agent.getId() : agent.getName();
            long sourceTenantId = agent.getTenantId() == null ? 0L : agent.getTenantId();
            for (String kbId : kbIds) {
                if (kbId == null || kbId.isEmpty() || directKbIds.contains(kbId)) {
                    continue;
                }
                KnowledgeBase kb = kbShareService.kbById(kbId);
                if (kb == null) {
                    continue;
                }
                if (kb.getTenantId() == null || kb.getTenantId() != sourceTenantId) {
                    continue;
                }
                directKbIds.add(kbId);
                kbShareService.applyKbCounts(kb, sourceTenantId);
                KbShareService.SharedKbInfo info = new KbShareService.SharedKbInfo(
                        kb, "", orgId, orgName, "viewer", sourceTenantId, agentItem.info().sharedAt());
                merged.add(new MergedKbRow(info, sourceTenantId == tenantId,
                        OrgResponses.sourceFromAgent(agent.getId(), agentName, mode)));
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MergedKbRow row : merged) {
            KbShareService.SharedKbInfo info = row.info;
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("is_mine", row.isMine);
            if (row.sourceFromAgent != null) {
                extras.put("source_from_agent", row.sourceFromAgent);
            }
            KbShareService.KbRaw raw = kbShareService.getKbRaw(info.knowledgeBase().getId());
            rows.add(OrgResponses.sharedKbRow(info.knowledgeBase(), info.shareId(), info.organizationId(),
                    info.orgName(), info.permission(),
                    info.sourceTenantId() == null ? 0L : info.sourceTenantId(), info.sharedAt(), extras,
                    raw.indexingStrategyJson(), raw.storageBackendId()));
        }
        return rows;
    }

    private record MergedKbRow(KbShareService.SharedKbInfo info, boolean isMine,
                               Map<String, Object> sourceFromAgent) {}

    /** 对照 OwnedAgentOrAdmin（路由守卫的控制器内等价物）：creator 或 Admin+；缺失/跨空间放行。 */
    private void requireOwnedAgentOrAdmin(String agentId) {
        if (APIKeyScopeContext.present()) {
            return; // API-Key 主体短路（对照 Go 中间件）
        }
        if (agentId == null || agentId.isEmpty()) {
            return;
        }
        AgentRow agent = agentRowMapper.getById(agentId);
        if (agent == null || agent.getTenantId() == null
                || !agent.getTenantId().equals(TenantContext.currentTenantId())) {
            return; // ErrResourceNotFound → 中间件放行
        }
        String role = TenantContext.currentRole();
        String uid = TenantContext.currentUserId();
        boolean admin = TenantRole.fromString(role).hasPermission(TenantRole.ADMIN);
        if (admin) {
            return;
        }
        if (agent.isBuiltin()) {
            throw GuardForbiddenException.mustOwnResourceOrHaveRole(); // tenant-owned，空 creator
        }
        String createdBy = agent.getCreatedBy() == null ? "" : agent.getCreatedBy();
        if (createdBy.isEmpty() || !createdBy.equals(uid)) {
            throw GuardForbiddenException.mustOwnResourceOrHaveRole();
        }
    }

    private void enrichAgentInfo(SharedAgentInfo info, String orgId) {
        info.setOrgName(kbShareService.orgName(orgId));
        if (info.sharedByUserId() != null && !info.sharedByUserId().isEmpty()) {
            info.setSharedByUsername(username(info.sharedByUserId()));
        }
    }

    private Map<String, Object> agentInfoMap(SharedAgentInfo info, Boolean isMine) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent", info.agent() == null ? null : OrgResponses.agentPayload(info.agent()));
        m.put("share_id", orEmpty(info.shareId()));
        m.put("organization_id", orEmpty(info.organizationId()));
        m.put("org_name", orEmpty(info.orgName()));
        m.put("permission", info.permission());
        m.put("source_tenant_id", info.sourceTenantId() == null ? 0L : info.sourceTenantId());
        m.put("shared_at", info.sharedAt());
        if (info.sharedByUserId() != null && !info.sharedByUserId().isEmpty()) {
            m.put("shared_by_user_id", info.sharedByUserId());
        }
        if (info.sharedByUsername() != null && !info.sharedByUsername().isEmpty()) {
            m.put("shared_by_username", info.sharedByUsername());
        }
        m.put("web_search_ready", info.webSearchReady());
        m.put("disabled_by_me", info.disabledByMe());
        if (isMine != null) {
            m.put("is_mine", isMine);
        }
        return m;
    }

    private List<String> kbIdsByTenant(Long tenantId) {
        if (tenantId == null) {
            return List.of();
        }
        try {
            return kbShareService.kbIdsByTenant(tenantId);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private String username(String userId) {
        if (userId == null || userId.isEmpty()) {
            return "";
        }
        User u = userMapper.selectById(userId);
        return u == null ? "" : orEmpty(u.getUsername());
    }

    private User userById(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        return userMapper.selectById(userId);
    }

    private String tenantName(Long tenantId) {
        if (tenantId == null) {
            return "";
        }
        try {
            Tenant t = tenantService.getTenantById(tenantId);
            return t == null ? "" : orEmpty(t.getName());
        } catch (RuntimeException e) {
            return "";
        }
    }

    private long safeCountMembers(String orgId) {
        try {
            return orgService.listTenantMembers(orgId).size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private long parseTenantIdOr400(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new BizException(AppError.validation("Invalid workspace ID"));
        }
    }

    /** 对照 ShouldBindJSON 的失败分支：400 validation "Invalid request parameters" + details。 */
    private JsonNode bindOr400(String raw) {
        if (raw == null || raw.isBlank()) {
            throw validationWithDetails("EOF");
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw validationWithDetails(GoJsonBindError.message(raw, e.getMessage()));
        }
    }

    /** SetSharedAgentDisabledByMe 用 NewBadRequestError（code 1000），与其余端点不同。 */
    private JsonNode bindBadRequest(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(AppError.badRequest("Invalid request").withDetails("EOF"));
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("Invalid request")
                    .withDetails(GoJsonBindError.message(raw, e.getMessage())));
        }
    }

    private BizException validationWithDetails(String details) {
        return new BizException(AppError.validation("Invalid request parameters").withDetails(details));
    }

    /** Go binding required 校验的原文（validator v10 格式）。 */
    private BizException validator(String fieldSpec) {
        int dot = fieldSpec.lastIndexOf('.');
        String structName = fieldSpec.substring(0, dot);
        String field = fieldSpec.substring(dot + 1);
        return validationWithDetails("Key: '" + structName + "." + field
                + "' Error:Field validation for '" + field + "' failed on the 'required' tag");
    }

    private static Map<String, Object> success(String key, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(key, data);
        body.put("success", true);
        return body;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Integer intOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || !v.isNumber() ? null : v.intValue();
    }

    private static Boolean boolOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || !v.isBoolean() ? null : v.asBoolean();
    }

    private static List<String> strSlice(JsonNode cfg, String field) {
        JsonNode v = cfg.get(field);
        if (v == null || v.isNull() || !v.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode e : v) {
            out.add(e.isNull() ? null : e.asText());
        }
        return out;
    }

    private static JsonNode parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
