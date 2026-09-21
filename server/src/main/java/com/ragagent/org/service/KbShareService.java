package com.ragagent.org.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.org.domain.KbShare;
import com.ragagent.org.domain.Organization;
import com.ragagent.org.domain.OrganizationTenantMember;
import com.ragagent.org.mapper.KbShareMapper;
import com.ragagent.org.mapper.OrganizationMapper;
import com.ragagent.org.mapper.OrgSqlMapper;
import com.ragagent.org.mapper.OrganizationTenantMemberMapper;

/**
 * KB 共享 service（对照 Go internal/application/service/kbshare.go）。
 *
 * <p>三维权限帽：effective = min(share.Permission, tenant_org_role, tenant_role_cap)
 * —— 本空间 Viewer 对任何共享资源最高只到 org viewer（applyTenantRoleCap）。</p>
 *
 * <p>已知差异：Go 的 Share/Update/Remove 会写 KB 活动审计（recordKBActivity →
 * AuditActionKBShare*），本批未接 audit 埋点（audit_logs 无新增行，HTTP 契约不变）。</p>
 */
@Service
public class KbShareService {

    private final KbShareMapper shareMapper;
    private final OrganizationMapper orgMapper;
    private final OrganizationTenantMemberMapper memberMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final ChunkMapper chunkMapper;
    private final OrganizationService organizationService;
    private final com.ragagent.org.mapper.OrgSqlMapper sqlMapper;

    public KbShareService(KbShareMapper shareMapper, OrganizationMapper orgMapper,
                          OrganizationTenantMemberMapper memberMapper, KnowledgeBaseMapper kbMapper,
                          KnowledgeMapper knowledgeMapper, ChunkMapper chunkMapper,
                          OrganizationService organizationService,
                          com.ragagent.org.mapper.OrgSqlMapper sqlMapper) {
        this.shareMapper = shareMapper;
        this.orgMapper = orgMapper;
        this.memberMapper = memberMapper;
        this.kbMapper = kbMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.chunkMapper = chunkMapper;
        this.organizationService = organizationService;
        this.sqlMapper = sqlMapper;
    }

    public KbShare shareKnowledgeBase(String kbId, String orgId, String userId, long tenantId, String permission) {
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId).isNull(KnowledgeBase::getDeletedAt).last("LIMIT 1"));
        if (kb == null) {
            throw OrgServiceException.kbNotFound();
        }
        if (kb.getTenantId() == null || kb.getTenantId() != tenantId) {
            throw OrgServiceException.notKbOwner();
        }
        getOrg(orgId);
        OrganizationTenantMember tm = getMemberRow(orgId, tenantId);
        if (tm == null) {
            throw new OrgServiceException(OrgServiceException.Kind.TENANT_NOT_IN_ORG);
        }
        if (!OrganizationService.hasPermission(tm.getRole(), "editor")) {
            throw OrgServiceException.orgRoleCannotShare();
        }
        if (!OrganizationService.isValidRole(permission)) {
            throw new OrgServiceException(OrgServiceException.Kind.INVALID_ROLE);
        }
        KbShare existing = getByKbAndOrg(kbId, orgId);
        if (existing != null) {
            existing.setPermission(permission);
            existing.setUpdatedAt(OffsetDateTime.now());
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<KbShare> uw =
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
            uw.eq("id", existing.getId()).set("permission", existing.getPermission())
                    .set("updated_at", existing.getUpdatedAt());
            shareMapper.update(null, uw);
            return existing;
        }
        OffsetDateTime now = OffsetDateTime.now();
        KbShare share = new KbShare();
        share.setId(UUID.randomUUID().toString());
        share.setKnowledgeBaseId(kbId);
        share.setOrganizationId(orgId);
        share.setSharedByUserId(userId);
        share.setSourceTenantId(tenantId);
        share.setPermission(permission);
        share.setCreatedAt(now);
        share.setUpdatedAt(now);
        shareMapper.insert(share);
        return share;
    }

    public void updateSharePermission(String shareId, String permission, String userId, long tenantId) {
        KbShare share = getById(shareId);
        if (!callerCanManageShare(share.getSharedByUserId(), share.getSourceTenantId(),
                share.getOrganizationId(), userId, tenantId)) {
            throw new OrgServiceException(OrgServiceException.Kind.PLAIN, "permission denied for this share operation");
        }
        if (!OrganizationService.isValidRole(permission)) {
            throw new OrgServiceException(OrgServiceException.Kind.INVALID_ROLE);
        }
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<KbShare> uw =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
        uw.eq("id", share.getId()).set("permission", permission).set("updated_at", OffsetDateTime.now());
        shareMapper.update(null, uw);
    }

    public void removeShare(String shareId, String userId, long tenantId) {
        KbShare share = getById(shareId);
        if (!callerCanManageShare(share.getSharedByUserId(), share.getSourceTenantId(),
                share.getOrganizationId(), userId, tenantId)) {
            throw new OrgServiceException(OrgServiceException.Kind.PLAIN, "permission denied for this share operation");
        }
        shareMapper.deleteById(share.getId());
    }

    public List<KbShare> listSharesByKnowledgeBase(String kbId, long tenantId) {
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId).isNull(KnowledgeBase::getDeletedAt).last("LIMIT 1"));
        if (kb == null) {
            throw OrgServiceException.kbNotFound();
        }
        if (kb.getTenantId() == null || kb.getTenantId() != tenantId) {
            throw OrgServiceException.notKbOwner();
        }
        return listByKnowledgeBase(kbId);
    }

    public List<KbShare> listByKnowledgeBase(String kbId) {
        return shareMapper.selectList(new LambdaQueryWrapper<KbShare>()
                .eq(KbShare::getKnowledgeBaseId, kbId)
                .orderByDesc(KbShare::getCreatedAt));
    }

    /**
     * 对照 CheckTenantKBPermission（kbshare.go L462-485，W5α 收口）：org 共享给
     * 调用方租户的有效角色取最高（minOrgRole(share, member) → applyTenantRoleCap）。
     * 成员查询失败跳过该 share（Go `continue`）。
     */
    public CheckTenantKBPermissionResult checkTenantKBPermission(String kbId, long callerTenantId,
                                                                 TenantRole callerTenantRole) {
        List<KbShare> shares = listByKnowledgeBase(kbId);
        String highest = "";
        boolean shared = false;
        for (KbShare share : shares) {
            OrganizationTenantMember tm = getMemberRow(share.getOrganizationId(), callerTenantId);
            if (tm == null) {
                continue;
            }
            shared = true;
            String effective = OrganizationService.minOrgRole(share.getPermission(), tm.getRole());
            effective = OrganizationService.applyTenantRoleCap(effective, callerTenantRole);
            if (highest.isEmpty() || OrganizationService.hasPermission(effective, highest)) {
                highest = effective;
            }
        }
        return new CheckTenantKBPermissionResult(highest, shared);
    }

    /** (effectiveRole, isShared)：role 空串 = 无有效授予（对照 Go 的 "" 零值）。 */
    public record CheckTenantKBPermissionResult(String role, boolean shared) {

        /** 对照 KBSharePermissions.Check：双方角色有效且 effective ≥ required。 */
        public boolean permits(String required) {
            return shared && OrganizationService.isValidRole(role)
                    && OrganizationService.isValidRole(required)
                    && OrganizationService.hasPermission(role, required);
        }
    }

    /** 对照 ListByOrganization：排除 KB 已软删的 share，order created_at DESC。 */
    public List<KbShare> listByOrganization(String orgId) {
        return shareMapper.selectList(new LambdaQueryWrapper<KbShare>()
                .eq(KbShare::getOrganizationId, orgId)
                .isNull(KbShare::getDeletedAt)
                .inSql(KbShare::getKnowledgeBaseId,
                        "SELECT id FROM knowledge_bases WHERE deleted_at IS NULL")
                .orderByDesc(KbShare::getCreatedAt));
    }

    public List<KbShare> listByOrganizations(List<String> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return List.of();
        }
        return shareMapper.selectList(new LambdaQueryWrapper<KbShare>()
                .in(KbShare::getOrganizationId, orgIds)
                .isNull(KbShare::getDeletedAt)
                .inSql(KbShare::getKnowledgeBaseId,
                        "SELECT id FROM knowledge_bases WHERE deleted_at IS NULL")
                .orderByDesc(KbShare::getCreatedAt));
    }

    /** 对照 ListSharedKBsForTenant（跨空间共享给我所在组织的 KB，排除已删 org/kb）。 */
    public List<KbShare> listSharedForTenant(long tenantId) {
        return shareMapper.selectList(new LambdaQueryWrapper<KbShare>()
                .isNull(KbShare::getDeletedAt)
                .inSql(KbShare::getKnowledgeBaseId,
                        "SELECT id FROM knowledge_bases WHERE deleted_at IS NULL")
                .inSql(KbShare::getOrganizationId,
                        "SELECT otm.organization_id FROM organization_tenant_members otm "
                                + "WHERE otm.tenant_id = " + tenantId)
                .inSql(KbShare::getOrganizationId,
                        "SELECT id FROM organizations WHERE deleted_at IS NULL")
                .orderByDesc(KbShare::getCreatedAt));
    }

    /**
     * 对照 ListSharedKnowledgeBases：逐 share 解析成员关系与三维帽，按 KB 去重
     * （保留更高 effective 权限的条目）。
     */
    public List<SharedKbInfo> listSharedKnowledgeBases(long tenantId, TenantRole callerTenantRole) {
        List<KbShare> shares = listSharedForTenant(tenantId);
        Map<String, SharedKbInfo> byKb = new LinkedHashMap<>();
        for (KbShare share : shares) {
            if (share.getSourceTenantId() != null && share.getSourceTenantId() == tenantId) {
                continue;
            }
            OrganizationTenantMember tm = getMemberRow(share.getOrganizationId(), tenantId);
            if (tm == null) {
                continue;
            }
            String effective = OrganizationService.minOrgRole(share.getPermission(), tm.getRole());
            effective = OrganizationService.applyTenantRoleCap(effective, callerTenantRole);
            KnowledgeBase kb = kbById(share.getKnowledgeBaseId());
            if (kb == null) {
                continue;
            }
            applyKbCounts(kb, share.getSourceTenantId());
            String orgName = orgName(share.getOrganizationId());
            SharedKbInfo info = new SharedKbInfo(kb, share.getId(), share.getOrganizationId(), orgName,
                    effective, share.getSourceTenantId(), share.getCreatedAt());
            SharedKbInfo existing = byKb.get(kb.getId());
            if (existing == null
                    || (OrganizationService.hasPermission(effective, existing.permission)
                            && !effective.equals(existing.permission))) {
                byKb.put(kb.getId(), info);
            }
        }
        return new ArrayList<>(byKb.values());
    }

    /** 对照 ListSharedKnowledgeBasesInOrganization。 */
    public List<OrgSharedKbItem> listSharedInOrganization(String orgId, long tenantId, TenantRole callerTenantRole) {
        OrganizationTenantMember tm = getMemberRow(orgId, tenantId);
        if (tm == null) {
            throw new OrgServiceException(OrgServiceException.Kind.TENANT_NOT_IN_ORG);
        }
        List<KbShare> shares = listByOrganization(orgId);
        List<OrgSharedKbItem> result = new ArrayList<>();
        for (KbShare share : shares) {
            String effective = OrganizationService.minOrgRole(share.getPermission(), tm.getRole());
            effective = OrganizationService.applyTenantRoleCap(effective, callerTenantRole);
            KnowledgeBase kb = kbById(share.getKnowledgeBaseId());
            if (kb == null) {
                continue;
            }
            applyKbCounts(kb, share.getSourceTenantId());
            String orgName = orgName(share.getOrganizationId());
            result.add(new OrgSharedKbItem(new SharedKbInfo(kb, share.getId(), share.getOrganizationId(),
                    orgName, effective, share.getSourceTenantId(), share.getCreatedAt()),
                    share.getSourceTenantId() != null && share.getSourceTenantId() == tenantId));
        }
        return result;
    }

    /** 对照 ListSharedKnowledgeBaseIDsByOrganizations（org → 直接共享 KB id 列表）。 */
    public Map<String, List<String>> listSharedKbIdsByOrganizations(List<String> orgIds, long tenantId) {
        Map<String, List<String>> byOrg = new LinkedHashMap<>();
        if (orgIds == null || orgIds.isEmpty()) {
            return byOrg;
        }
        Map<String, OrganizationTenantMember> members =
                organizationService.listMembersByTenantForOrgs(tenantId, orgIds);
        List<KbShare> shares = listByOrganizations(orgIds);
        for (KbShare share : shares) {
            if (share == null || !members.containsKey(share.getOrganizationId())) {
                continue;
            }
            String kbId = share.getKnowledgeBaseId();
            if (kbId != null && !kbId.isEmpty()) {
                byOrg.computeIfAbsent(share.getOrganizationId(), k -> new ArrayList<>()).add(kbId);
            }
        }
        return byOrg;
    }

    /** 对照 CountByOrganizations：排除已删 KB。 */
    public Map<String, Long> countByOrganizations(List<String> orgIds) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (orgIds == null || orgIds.isEmpty()) {
            return out;
        }
        for (String id : orgIds) {
            out.put(id, 0L);
        }
        List<KbShare> shares = shareMapper.selectList(new LambdaQueryWrapper<KbShare>()
                .in(KbShare::getOrganizationId, orgIds)
                .isNull(KbShare::getDeletedAt)
                .inSql(KbShare::getKnowledgeBaseId, "SELECT id FROM knowledge_bases WHERE deleted_at IS NULL"));
        for (KbShare s : shares) {
            out.merge(s.getOrganizationId(), 1L, Long::sum);
        }
        return out;
    }

    public KbShare getById(String id) {
        KbShare share = shareMapper.selectOne(new LambdaQueryWrapper<KbShare>()
                .eq(KbShare::getId, id).last("LIMIT 1"));
        if (share == null) {
            throw OrgServiceException.shareNotFound();
        }
        return share;
    }

    public KbShare getByKbAndOrg(String kbId, String orgId) {
        return shareMapper.selectOne(new LambdaQueryWrapper<KbShare>()
                .eq(KbShare::getKnowledgeBaseId, kbId)
                .eq(KbShare::getOrganizationId, orgId)
                .isNull(KbShare::getDeletedAt)
                .last("LIMIT 1"));
    }

    /**
     * callerCanManageShare 三分支：(1) 原共享人；(2) 源空间 Admin+；
     * (3) 目标组织的 org admin。
     */
    public boolean callerCanManageShare(String shareSharedByUserId, Long shareSourceTenantId, String shareOrgId,
                                        String callerUserId, long callerTenantId) {
        if (shareSharedByUserId != null && shareSharedByUserId.equals(callerUserId)) {
            return true;
        }
        if (callerTenantId != 0 && shareSourceTenantId != null && shareSourceTenantId == callerTenantId) {
            TenantRole role = OrganizationService.callerTenantRole();
            if (role.hasPermission(TenantRole.ADMIN)) {
                return true;
            }
        }
        OrganizationTenantMember tm = getMemberRow(shareOrgId, callerTenantId);
        return tm != null && "admin".equals(tm.getRole());
    }

    // ── 内部 ──

    public KnowledgeBase kbById(String kbId) {
        return kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId).isNull(KnowledgeBase::getDeletedAt).last("LIMIT 1"));
    }

    /** KB 行的原始列（绕过实体 getter 的 EnsureDefaults——共享读面 Go 走 Preload raw 行）。 */
    public record KbRaw(String indexingStrategyJson, String storageBackendId) {}

    public KbRaw getKbRaw(String kbId) {
        OrgSqlMapper.KbRawRow row = sqlMapper.selectKbRaw(kbId);
        if (row == null) {
            return new KbRaw(null, null);
        }
        return new KbRaw(row.getIndexingStrategy(), row.getStorageBackendId());
    }

    /** 对照 kbService.ListKnowledgeBasesByTenantID 的 id 投影（resource-counts / agent 携带合并用）。 */
    public List<String> kbIdsByTenant(Long tenantId) {
        if (tenantId == null) {
            return List.of();
        }
        List<KnowledgeBase> kbs = kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getTenantId, tenantId)
                .isNull(KnowledgeBase::getDeletedAt));
        List<String> ids = new ArrayList<>();
        for (KnowledgeBase kb : kbs) {
            if (kb != null && kb.getId() != null && !kb.getId().isEmpty()) {
                ids.add(kb.getId());
            }
        }
        return ids;
    }

    public OrganizationTenantMember getMemberRow(String orgId, long tenantId) {
        return memberMapper.selectOne(new LambdaQueryWrapper<OrganizationTenantMember>()
                .eq(OrganizationTenantMember::getOrganizationId, orgId)
                .eq(OrganizationTenantMember::getTenantId, tenantId)
                .last("LIMIT 1"));
    }

    private void getOrg(String orgId) {
        Organization org = orgMapper.selectOne(new LambdaQueryWrapper<Organization>()
                .eq(Organization::getId, orgId).isNull(Organization::getDeletedAt).last("LIMIT 1"));
        if (org == null) {
            throw new OrgServiceException(OrgServiceException.Kind.ORG_NOT_FOUND);
        }
    }

    public String orgName(String orgId) {
        Organization org = orgMapper.selectOne(new LambdaQueryWrapper<Organization>()
                .eq(Organization::getId, orgId).last("LIMIT 1"));
        return org == null ? "" : org.getName();
    }

    /** document → knowledge_count；faq → chunk_count（跨空间按 source_tenant 计数）。 */
    public void applyKbCounts(KnowledgeBase kb, Long sourceTenantId) {
        String type = kb.getType();
        if ("document".equals(type)) {
            Long count = knowledgeMapper.selectCount(new LambdaQueryWrapper<com.ragagent.knowledge.domain.Knowledge>()
                    .eq(com.ragagent.knowledge.domain.Knowledge::getKnowledgeBaseId, kb.getId())
                    .eq(com.ragagent.knowledge.domain.Knowledge::getTenantId, sourceTenantId)
                    .isNull(com.ragagent.knowledge.domain.Knowledge::getDeletedAt));
            kb.setKnowledgeCount(count == null ? 0L : count);
        } else if ("faq".equals(type)) {
            Long count = chunkMapper.selectCount(new LambdaQueryWrapper<com.ragagent.knowledge.domain.Chunk>()
                    .eq(com.ragagent.knowledge.domain.Chunk::getKnowledgeBaseId, kb.getId())
                    .eq(com.ragagent.knowledge.domain.Chunk::getTenantId, sourceTenantId)
                    .isNull(com.ragagent.knowledge.domain.Chunk::getDeletedAt));
            kb.setChunkCount(count == null ? 0L : count);
        }
    }

    /** 对照 types.SharedKnowledgeBaseInfo。 */
    public record SharedKbInfo(KnowledgeBase knowledgeBase, String shareId, String organizationId,
                               String orgName, String permission, Long sourceTenantId, OffsetDateTime sharedAt) {}

    /** 对照 types.OrganizationSharedKnowledgeBaseItem（is_mine + 可选 source_from_agent 由控制器补）。 */
    public record OrgSharedKbItem(SharedKbInfo info, boolean isMine) {}
}
