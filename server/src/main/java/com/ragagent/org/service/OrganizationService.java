package com.ragagent.org.service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.org.domain.Organization;
import com.ragagent.org.domain.OrganizationJoinRequest;
import com.ragagent.org.domain.OrganizationTenantMember;
import com.ragagent.org.mapper.OrgSqlMapper;
import com.ragagent.org.mapper.OrganizationJoinRequestMapper;
import com.ragagent.org.mapper.OrganizationMapper;
import com.ragagent.org.mapper.OrganizationTenantMemberMapper;

/**
 * 组织 service（对照 Go internal/application/service/organization.go，Plan 3 语义：
 * 成员 = (org, tenant) 元组，user_id 仅作代表标注）。
 *
 * <p>翻译清单（§3）：无钩子；软删 organizations.deleted_at 显式 isNull；
 * 排序逐条显式（ListByTenantID/ListSearchable created_at DESC、ListTenantMembers
 * created_at ASC、ListJoinRequests created_at DESC）；Update 用显式列集合
 * （对照 GORM Select 八列，零值也要写）；join request 复审用条件 UPDATE 复刻
 * RowsAffected 语义。</p>
 */
@Service
public class OrganizationService {

    public static final int DEFAULT_INVITE_CODE_VALIDITY_DAYS = 7;
    /** Go 常量 DefaultMemberLimit = 200（组织创建时的默认成员上限）。 */
    public static final int DEFAULT_MEMBER_LIMIT = 200;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final java.util.Set<Integer> VALID_DAYS = java.util.Set.of(0, 1, 7, 30);

    private final OrganizationMapper orgMapper;
    private final OrganizationTenantMemberMapper memberMapper;
    private final OrganizationJoinRequestMapper requestMapper;
    private final OrgSqlMapper sqlMapper;

    public OrganizationService(OrganizationMapper orgMapper,
                               OrganizationTenantMemberMapper memberMapper,
                               OrganizationJoinRequestMapper requestMapper,
                               OrgSqlMapper sqlMapper) {
        this.orgMapper = orgMapper;
        this.memberMapper = memberMapper;
        this.requestMapper = requestMapper;
        this.sqlMapper = sqlMapper;
    }

    public record CreateRequest(String name, String description, String avatar,
                                Integer inviteCodeValidityDays, Integer memberLimit) {}

    public record UpdateRequest(String name, String description, String avatar, Boolean requireApproval,
                                Boolean searchable, Integer inviteCodeValidityDays, Integer memberLimit) {}

    public Organization createOrganization(String userId, long tenantId, CreateRequest req) {
        int validityDays = DEFAULT_INVITE_CODE_VALIDITY_DAYS;
        if (req.inviteCodeValidityDays() != null) {
            if (!VALID_DAYS.contains(req.inviteCodeValidityDays())) {
                throw new OrgServiceException(OrgServiceException.Kind.INVALID_VALIDITY_DAYS);
            }
            validityDays = req.inviteCodeValidityDays();
        }
        int memberLimit = DEFAULT_MEMBER_LIMIT;
        if (req.memberLimit() != null) {
            if (req.memberLimit() < 0) {
                throw new OrgServiceException(OrgServiceException.Kind.PLAIN, "member_limit must be >= 0");
            }
            memberLimit = req.memberLimit();
        }

        OffsetDateTime now = OffsetDateTime.now();
        Organization org = new Organization();
        org.setId(UUID.randomUUID().toString());
        org.setName(req.name());
        org.setDescription(orEmpty(req.description()));
        org.setAvatar(req.avatar() == null ? "" : req.avatar().trim());
        org.setOwnerId(userId);
        org.setOwnerTenantId(tenantId);
        org.setInviteCode(generateInviteCode());
        org.setInviteCodeExpiresAt(resolveInviteExpiry(validityDays, now));
        org.setInviteCodeValidityDays(validityDays);
        org.setMemberLimit(memberLimit);
        org.setCreatedAt(now);
        org.setUpdatedAt(now);
        try {
            orgMapper.insert(org);
        } catch (RuntimeException e) {
            throw new OrgServiceException(OrgServiceException.Kind.PLAIN, String.valueOf(e.getMessage()));
        }

        OrganizationTenantMember member = new OrganizationTenantMember();
        member.setId(UUID.randomUUID().toString());
        member.setOrganizationId(org.getId());
        member.setTenantId(tenantId);
        member.setRole("admin");
        member.setRepresentativeUserId(userId);
        member.setJoinedAt(now);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        try {
            memberMapper.insert(member);
        } catch (RuntimeException e) {
            orgMapper.deleteById(org.getId()); // 对照 Go 的回滚删除
            throw new OrgServiceException(OrgServiceException.Kind.PLAIN, "member already exists in organization");
        }
        return org;
    }

    public Organization getOrganization(String id) {
        Organization org = orgMapper.selectOne(new LambdaQueryWrapper<Organization>()
                .eq(Organization::getId, id).isNull(Organization::getDeletedAt).last("LIMIT 1"));
        if (org == null) {
            throw new OrgServiceException(OrgServiceException.Kind.ORG_NOT_FOUND);
        }
        return org;
    }

    public Organization getOrganizationByInviteCode(String inviteCode) {
        Organization org = orgMapper.selectOne(new LambdaQueryWrapper<Organization>()
                .eq(Organization::getInviteCode, inviteCode)
                .isNull(Organization::getDeletedAt).last("LIMIT 1"));
        if (org == null) {
            throw new OrgServiceException(OrgServiceException.Kind.ORG_NOT_FOUND);
        }
        if (org.getInviteCodeExpiresAt() != null && org.getInviteCodeExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new OrgServiceException(OrgServiceException.Kind.INVITE_CODE_EXPIRED);
        }
        return org;
    }

    public List<Organization> listTenantOrganizations(long tenantId) {
        return orgMapper.selectList(new LambdaQueryWrapper<Organization>()
                .isNull(Organization::getDeletedAt)
                .inSql(Organization::getId, "SELECT organization_id FROM organization_tenant_members "
                        + "WHERE tenant_id = " + tenantId)
                .orderByDesc(Organization::getCreatedAt));
    }

    public Organization updateOrganization(String id, String userId, long tenantId, UpdateRequest req) {
        if (!isTenantOrgAdmin(id, tenantId)) {
            throw new OrgServiceException(OrgServiceException.Kind.ORG_PERMISSION_DENIED);
        }
        Organization org = getOrganization(id);
        if (req.name() != null) {
            org.setName(req.name());
        }
        if (req.description() != null) {
            org.setDescription(req.description());
        }
        if (req.avatar() != null) {
            org.setAvatar(req.avatar().trim());
        }
        if (req.requireApproval() != null) {
            org.setRequireApproval(req.requireApproval());
        }
        if (req.searchable() != null) {
            org.setSearchable(req.searchable());
        }
        if (req.inviteCodeValidityDays() != null) {
            if (!VALID_DAYS.contains(req.inviteCodeValidityDays())) {
                throw new OrgServiceException(OrgServiceException.Kind.INVALID_VALIDITY_DAYS);
            }
            org.setInviteCodeValidityDays(req.inviteCodeValidityDays());
        }
        if (req.memberLimit() != null) {
            if (req.memberLimit() < 0) {
                throw new OrgServiceException(OrgServiceException.Kind.PLAIN, "member_limit must be >= 0");
            }
            if (req.memberLimit() > 0) {
                long count = sqlMapper.countTenantMembers(id);
                if (req.memberLimit() < count) {
                    throw new OrgServiceException(OrgServiceException.Kind.MEMBER_LIMIT_TOO_LOW);
                }
            }
            org.setMemberLimit(req.memberLimit());
        }
        org.setUpdatedAt(OffsetDateTime.now());
        // 对照 GORM Select(name, description, avatar, require_approval, searchable,
        // invite_code_validity_days, member_limit, updated_at)——零值也写
        Organization patch = new Organization();
        patch.setId(org.getId());
        patch.setName(org.getName());
        patch.setDescription(org.getDescription());
        patch.setAvatar(org.getAvatar());
        patch.setRequireApproval(org.isRequireApproval());
        patch.setSearchable(org.isSearchable());
        patch.setInviteCodeValidityDays(org.getInviteCodeValidityDays());
        patch.setMemberLimit(org.getMemberLimit());
        patch.setUpdatedAt(org.getUpdatedAt());
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Organization> uw =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
        uw.eq("id", org.getId())
                .set("name", patch.getName())
                .set("description", patch.getDescription())
                .set("avatar", patch.getAvatar())
                .set("require_approval", patch.isRequireApproval())
                .set("searchable", patch.isSearchable())
                .set("invite_code_validity_days", patch.getInviteCodeValidityDays())
                .set("member_limit", patch.getMemberLimit())
                .set("updated_at", patch.getUpdatedAt());
        orgMapper.update(null, uw);
        return org;
    }

    public record SearchableResult(java.util.List<Organization> orgs, Map<String, Integer> memberCounts,
                                   Map<String, Integer> shareCounts, Map<String, Integer> agentShareCounts,
                                   java.util.Set<String> memberOrgIds) {}

    /** 对照 SearchSearchableOrganizations 的前置查询（计数组装在控制器/响应层）。 */
    public List<Organization> listSearchable(String query, int limit) {
        if (limit <= 0) {
            limit = 20;
        }
        LambdaQueryWrapper<Organization> q = new LambdaQueryWrapper<Organization>()
                .eq(Organization::isSearchable, true)
                .isNull(Organization::getDeletedAt);
        if (query != null && !query.isEmpty()) {
            // 对照 name ILIKE / description ILIKE / id::text ILIKE：
            // LOWER+LIKE 双方言等价（H2 无 ILIKE），PG 行为一致
            String pattern = "%" + query.toLowerCase() + "%";
            q.and(w -> w.apply("(LOWER(name) LIKE {0} OR LOWER(description) LIKE {1} "
                    + "OR LOWER(CAST(id AS VARCHAR)) LIKE {2})", pattern, pattern, pattern));
        }
        q.orderByDesc(Organization::getCreatedAt).last("LIMIT " + limit);
        return orgMapper.selectList(q);
    }

    public Organization joinByOrganizationID(String orgId, String userId, long tenantId,
                                             String message, String requestedRole) {
        Organization org;
        try {
            org = getOrganization(orgId);
        } catch (OrgServiceException e) {
            if (e.kind() == OrgServiceException.Kind.ORG_NOT_FOUND) {
                throw e;
            }
            throw e;
        }
        if (!org.isSearchable()) {
            throw new OrgServiceException(OrgServiceException.Kind.ORG_PERMISSION_DENIED);
        }
        if (getTenantMemberRow(orgId, tenantId) != null) {
            return org; // 已是成员 → 幂等
        }
        if (requestedRole != null && !requestedRole.isEmpty() && !isValidRole(requestedRole)) {
            throw new OrgServiceException(OrgServiceException.Kind.INVALID_ROLE);
        }
        if (requestedRole == null || requestedRole.isEmpty()) {
            requestedRole = "viewer";
        }
        if (org.isRequireApproval()) {
            submitJoinRequest(orgId, userId, tenantId, message, requestedRole);
            return org;
        }
        joinAsViewerWithChecks(org, userId, tenantId);
        return org;
    }

    public void deleteOrganization(String id, String userId, long tenantId) {
        Organization org = getOrganization(id);
        boolean isOwnerTenant = org.ownerTenantIdOrZero() != 0 && org.ownerTenantIdOrZero() == tenantId;
        boolean isLegacyOwnerUser = org.ownerTenantIdOrZero() == 0
                && userId != null && userId.equals(org.getOwnerId());
        if (!isOwnerTenant && !isLegacyOwnerUser) {
            throw new OrgServiceException(OrgServiceException.Kind.ORG_PERMISSION_DENIED);
        }
        // 对照 Go：share 删除失败仅告警后继续（dev 语义一致，这里两表软删）
        orgMapper.delete(new LambdaQueryWrapper<Organization>().eq(Organization::getId, id));
    }

    public void addTenantMember(String orgId, long tenantId, String representativeUserId, String role) {
        if (!isValidRole(role)) {
            throw new OrgServiceException(OrgServiceException.Kind.INVALID_ROLE);
        }
        Organization org = getOrganization(orgId);
        if (org.getMemberLimit() > 0) {
            long count = sqlMapper.countTenantMembers(orgId);
            if (count >= org.getMemberLimit()) {
                throw new OrgServiceException(OrgServiceException.Kind.MEMBER_LIMIT_REACHED);
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        OrganizationTenantMember member = new OrganizationTenantMember();
        member.setId(UUID.randomUUID().toString());
        member.setOrganizationId(orgId);
        member.setTenantId(tenantId);
        member.setRole(role);
        member.setRepresentativeUserId(orEmpty(representativeUserId));
        member.setJoinedAt(now);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        try {
            memberMapper.insert(member);
        } catch (RuntimeException e) {
            throw new OrgServiceException(OrgServiceException.Kind.PLAIN, "member already exists in organization");
        }
    }

    public void removeTenantMember(String orgId, long memberTenantId, String operatorUserId, long operatorTenantId) {
        Organization org = getOrganization(orgId);
        if (isOwnerTenant(org, memberTenantId)) {
            throw new OrgServiceException(OrgServiceException.Kind.CANNOT_REMOVE_OWNER);
        }
        if (operatorTenantId == memberTenantId) {
            removeMemberRow(orgId, memberTenantId);
            return;
        }
        if (!isTenantOrgAdmin(orgId, operatorTenantId)) {
            throw new OrgServiceException(OrgServiceException.Kind.ORG_PERMISSION_DENIED);
        }
        removeMemberRow(orgId, memberTenantId);
    }

    public void updateTenantMemberRole(String orgId, long memberTenantId, String role,
                                       String operatorUserId, long operatorTenantId) {
        if (!isValidRole(role)) {
            throw new OrgServiceException(OrgServiceException.Kind.INVALID_ROLE);
        }
        if (!isTenantOrgAdmin(orgId, operatorTenantId)) {
            throw new OrgServiceException(OrgServiceException.Kind.ORG_PERMISSION_DENIED);
        }
        Organization org = getOrganization(orgId);
        if (isOwnerTenant(org, memberTenantId)) {
            throw new OrgServiceException(OrgServiceException.Kind.CANNOT_CHANGE_OWNER_ROLE);
        }
        if (sqlMapper.updateMemberRole(orgId, memberTenantId, role) == 0) {
            throw new OrgServiceException(OrgServiceException.Kind.PLAIN, "organization member not found");
        }
    }

    public List<OrganizationTenantMember> listTenantMembers(String orgId) {
        return memberMapper.selectList(new LambdaQueryWrapper<OrganizationTenantMember>()
                .eq(OrganizationTenantMember::getOrganizationId, orgId)
                .orderByAsc(OrganizationTenantMember::getCreatedAt));
    }

    /** 对照 GetTenantMember：缺行返回 null（不抛）。 */
    public OrganizationTenantMember getTenantMemberRow(String orgId, long tenantId) {
        return memberMapper.selectOne(new LambdaQueryWrapper<OrganizationTenantMember>()
                .eq(OrganizationTenantMember::getOrganizationId, orgId)
                .eq(OrganizationTenantMember::getTenantId, tenantId)
                .last("LIMIT 1"));
    }

    public OrganizationTenantMember getTenantMember(String orgId, long tenantId) {
        OrganizationTenantMember member = getTenantMemberRow(orgId, tenantId);
        if (member == null) {
            throw new OrgServiceException(OrgServiceException.Kind.TENANT_NOT_IN_ORG);
        }
        return member;
    }

    public String generateInviteCode(String orgId, String userId, long tenantId) {
        if (!isTenantOrgAdmin(orgId, tenantId)) {
            throw new OrgServiceException(OrgServiceException.Kind.ORG_PERMISSION_DENIED);
        }
        Organization org = getOrganization(orgId);
        int validityDays = org.getInviteCodeValidityDays();
        if (validityDays != 0 && !VALID_DAYS.contains(validityDays)) {
            validityDays = DEFAULT_INVITE_CODE_VALIDITY_DAYS;
        }
        String code = generateInviteCode();
        sqlMapper.updateInviteCode(orgId, code, resolveInviteExpiry(validityDays, OffsetDateTime.now()));
        return code;
    }

    public Organization joinByInviteCode(String inviteCode, String userId, long tenantId) {
        Organization org = getOrganizationByInviteCode(inviteCode);
        if (org.isRequireApproval()) {
            throw new OrgServiceException(OrgServiceException.Kind.ORG_PERMISSION_DENIED);
        }
        joinAsViewerWithChecks(org, userId, tenantId);
        return org;
    }

    public boolean isTenantOrgAdmin(String orgId, long tenantId) {
        OrganizationTenantMember member = getTenantMemberRow(orgId, tenantId);
        return member != null && "admin".equals(member.getRole());
    }

    public String getTenantRoleInOrg(String orgId, long tenantId) {
        OrganizationTenantMember member = getTenantMemberRow(orgId, tenantId);
        if (member == null) {
            throw new OrgServiceException(OrgServiceException.Kind.TENANT_NOT_IN_ORG);
        }
        return member.getRole();
    }

    // ── join / upgrade requests ──

    public OrganizationJoinRequest submitJoinRequest(String orgId, String userId, long tenantId,
                                                     String message, String requestedRole) {
        if (getPendingRequest(orgId, tenantId, "join") != null) {
            throw new OrgServiceException(OrgServiceException.Kind.PENDING_REQUEST_EXISTS);
        }
        Organization org = getOrganization(orgId);
        if (org.getMemberLimit() > 0) {
            long count = sqlMapper.countTenantMembers(orgId);
            if (count >= org.getMemberLimit()) {
                throw new OrgServiceException(OrgServiceException.Kind.MEMBER_LIMIT_REACHED);
            }
        }
        if (requestedRole == null || requestedRole.isEmpty() || !isValidRole(requestedRole)) {
            requestedRole = "viewer";
        }
        OffsetDateTime now = OffsetDateTime.now();
        OrganizationJoinRequest request = new OrganizationJoinRequest();
        request.setId(UUID.randomUUID().toString());
        request.setOrganizationId(orgId);
        request.setUserId(userId);
        request.setTenantId(tenantId);
        request.setRequestType("join");
        request.setPrevRole("");
        request.setRequestedRole(requestedRole);
        request.setStatus("pending");
        request.setMessage(orEmpty(message));
        request.setReviewedBy("");
        request.setReviewMessage("");
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        requestMapper.insert(request);
        return request;
    }

    public List<OrganizationJoinRequest> listJoinRequests(String orgId) {
        return requestMapper.selectList(new LambdaQueryWrapper<OrganizationJoinRequest>()
                .eq(OrganizationJoinRequest::getOrganizationId, orgId)
                .orderByDesc(OrganizationJoinRequest::getCreatedAt));
    }

    public long countPendingJoinRequests(String orgId) {
        return sqlMapper.countJoinRequests(orgId, "pending");
    }

    public void reviewJoinRequest(String orgId, String requestId, boolean approved, String reviewerId,
                                  long reviewerTenantId, String message, String assignRole) {
        OrganizationJoinRequest request = requestMapper.selectById(requestId);
        if (request == null || !request.getOrganizationId().equals(orgId)) {
            throw new OrgServiceException(OrgServiceException.Kind.JOIN_REQUEST_NOT_FOUND);
        }
        if (!"pending".equals(request.getStatus())) {
            throw new OrgServiceException(OrgServiceException.Kind.PLAIN, "request has already been reviewed");
        }
        String status = approved ? "approved" : "rejected";
        if (approved) {
            String role = "viewer";
            if (assignRole != null && !assignRole.isEmpty() && isValidRole(assignRole)) {
                role = assignRole;
            } else if (request.getRequestedRole() != null && !request.getRequestedRole().isEmpty()
                    && isValidRole(request.getRequestedRole())) {
                role = request.getRequestedRole();
            }
            if ("upgrade".equals(request.getRequestType())) {
                if (sqlMapper.updateMemberRole(request.getOrganizationId(), request.getTenantId(), role) == 0) {
                    throw new OrgServiceException(OrgServiceException.Kind.PLAIN, "organization member not found");
                }
            } else {
                Organization org = getOrganization(request.getOrganizationId());
                if (org.getMemberLimit() > 0) {
                    long count = sqlMapper.countTenantMembers(org.getId());
                    if (count >= org.getMemberLimit()) {
                        throw new OrgServiceException(OrgServiceException.Kind.MEMBER_LIMIT_REACHED);
                    }
                }
                OffsetDateTime now = OffsetDateTime.now();
                OrganizationTenantMember member = new OrganizationTenantMember();
                member.setId(UUID.randomUUID().toString());
                member.setOrganizationId(request.getOrganizationId());
                member.setTenantId(request.getTenantId());
                member.setRole(role);
                member.setRepresentativeUserId(orEmpty(request.getUserId()));
                member.setJoinedAt(now);
                member.setCreatedAt(now);
                member.setUpdatedAt(now);
                try {
                    memberMapper.insert(member);
                } catch (RuntimeException e) {
                    throw new OrgServiceException(OrgServiceException.Kind.PLAIN,
                            "member already exists in organization");
                }
            }
        }
        if (sqlMapper.reviewJoinRequest(requestId, status, reviewerId, orEmpty(message)) == 0) {
            throw new OrgServiceException(OrgServiceException.Kind.PLAIN, "request has already been reviewed");
        }
    }

    public OrganizationJoinRequest requestRoleUpgrade(String orgId, String userId, long tenantId,
                                                      String requestedRole, String message) {
        OrganizationTenantMember member = getTenantMemberRow(orgId, tenantId);
        if (member == null) {
            throw new OrgServiceException(OrgServiceException.Kind.TENANT_NOT_IN_ORG);
        }
        if (!isValidRole(requestedRole)) {
            throw new OrgServiceException(OrgServiceException.Kind.INVALID_ROLE);
        }
        if ("admin".equals(member.getRole())) {
            throw new OrgServiceException(OrgServiceException.Kind.ALREADY_ADMIN);
        }
        if (!hasPermission(requestedRole, member.getRole()) || requestedRole.equals(member.getRole())) {
            throw new OrgServiceException(OrgServiceException.Kind.CANNOT_UPGRADE_TO_SAME_ROLE);
        }
        if (getPendingRequest(orgId, tenantId, "upgrade") != null) {
            throw new OrgServiceException(OrgServiceException.Kind.PENDING_REQUEST_EXISTS);
        }
        OffsetDateTime now = OffsetDateTime.now();
        OrganizationJoinRequest request = new OrganizationJoinRequest();
        request.setId(UUID.randomUUID().toString());
        request.setOrganizationId(orgId);
        request.setUserId(userId);
        request.setTenantId(tenantId);
        request.setRequestType("upgrade");
        request.setPrevRole(member.getRole());
        request.setRequestedRole(requestedRole);
        request.setStatus("pending");
        request.setMessage(orEmpty(message));
        request.setReviewedBy("");
        request.setReviewMessage("");
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        requestMapper.insert(request);
        return request;
    }

    /** 对照 GetPendingUpgradeRequest：缺行返回 null。 */
    public OrganizationJoinRequest getPendingUpgradeRequest(String orgId, long tenantId) {
        return getPendingRequest(orgId, tenantId, "upgrade");
    }

    // ── 内部 ──

    private OrganizationJoinRequest getPendingRequest(String orgId, long tenantId, String type) {
        return requestMapper.selectOne(new LambdaQueryWrapper<OrganizationJoinRequest>()
                .eq(OrganizationJoinRequest::getOrganizationId, orgId)
                .eq(OrganizationJoinRequest::getTenantId, tenantId)
                .eq(OrganizationJoinRequest::getStatus, "pending")
                .eq(OrganizationJoinRequest::getRequestType, type)
                .last("LIMIT 1"));
    }

    private void joinAsViewerWithChecks(Organization org, String representativeUserId, long tenantId) {
        if (getTenantMemberRow(org.getId(), tenantId) != null) {
            return;
        }
        if (org.getMemberLimit() > 0) {
            long count = sqlMapper.countTenantMembers(org.getId());
            if (count >= org.getMemberLimit()) {
                throw new OrgServiceException(OrgServiceException.Kind.MEMBER_LIMIT_REACHED);
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        OrganizationTenantMember member = new OrganizationTenantMember();
        member.setId(UUID.randomUUID().toString());
        member.setOrganizationId(org.getId());
        member.setTenantId(tenantId);
        member.setRole("viewer");
        member.setRepresentativeUserId(orEmpty(representativeUserId));
        member.setJoinedAt(now);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        try {
            memberMapper.insert(member);
        } catch (RuntimeException e) {
            throw new OrgServiceException(OrgServiceException.Kind.PLAIN, "member already exists in organization");
        }
    }

    private void removeMemberRow(String orgId, long tenantId) {
        int rows = memberMapper.delete(new LambdaQueryWrapper<OrganizationTenantMember>()
                .eq(OrganizationTenantMember::getOrganizationId, orgId)
                .eq(OrganizationTenantMember::getTenantId, tenantId));
        if (rows == 0) {
            throw new OrgServiceException(OrgServiceException.Kind.TENANT_NOT_IN_ORG);
        }
    }

    /**
     * 对照 isOwnerTenant：OwnerTenantID 为 0（legacy 行）时对**任意**租户返回 true
     * （Go 注释原文：fail-closed，冻结成员表直到回填）。
     */
    private boolean isOwnerTenant(Organization org, long tenantId) {
        if (org == null) {
            return false;
        }
        if (org.ownerTenantIdOrZero() == 0) {
            return true;
        }
        return org.ownerTenantIdOrZero() == tenantId;
    }

    public static boolean isValidRole(String role) {
        return "admin".equals(role) || "editor".equals(role) || "viewer".equals(role);
    }

    /** OrgMemberRole.HasPermission：admin(3) > editor(2) > viewer(1)。 */
    public static boolean hasPermission(String role, String required) {
        return level(role) >= level(required);
    }

    public static int level(String role) {
        if (role == null) {
            return 0;
        }
        return switch (role) {
            case "admin" -> 3;
            case "editor" -> 2;
            case "viewer" -> 1;
            default -> 0;
        };
    }

    /** MinOrgRole：取两者中较低者（admin > editor > viewer 阶梯）。 */
    public static String minOrgRole(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b;
        }
        if (b == null || b.isEmpty()) {
            return a;
        }
        return hasPermission(a, b) ? b : a;
    }

    /** applyTenantRoleCap：本空间 Viewer 对共享资源最高只到 org viewer。 */
    public static String applyTenantRoleCap(String perm, TenantRole callerTenantRole) {
        if (callerTenantRole == TenantRole.VIEWER && hasPermission(perm, "editor")) {
            return "viewer";
        }
        return perm;
    }

    /** 对照 TenantRoleFromContext 的 fail-closed 语义：未附加 → Viewer。 */
    public static TenantRole callerTenantRole() {
        String role = TenantContext.currentRole();
        try {
            return role == null ? TenantRole.VIEWER : TenantRole.fromString(role);
        } catch (Exception e) {
            return TenantRole.VIEWER;
        }
    }

    public static OffsetDateTime resolveInviteExpiry(int validityDays, OffsetDateTime now) {
        if (validityDays == 0) {
            return null;
        }
        return now.plusDays(validityDays);
    }

    public static String generateInviteCode() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** ListTenantMembersByTenantForOrgs 的 Java 版（批量成员行）。 */
    public Map<String, OrganizationTenantMember> listMembersByTenantForOrgs(long tenantId, List<String> orgIds) {
        Map<String, OrganizationTenantMember> out = new HashMap<>();
        if (orgIds == null || orgIds.isEmpty()) {
            return out;
        }
        List<OrganizationTenantMember> members = memberMapper.selectList(
                new LambdaQueryWrapper<OrganizationTenantMember>()
                        .eq(OrganizationTenantMember::getTenantId, tenantId)
                        .in(OrganizationTenantMember::getOrganizationId, orgIds));
        for (OrganizationTenantMember m : members) {
            out.put(m.getOrganizationId(), m);
        }
        return out;
    }
}
