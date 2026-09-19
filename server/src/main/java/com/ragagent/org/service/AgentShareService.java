package com.ragagent.org.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.org.domain.AgentRow;
import com.ragagent.org.domain.AgentShare;
import com.ragagent.org.domain.OrganizationTenantMember;
import com.ragagent.org.mapper.AgentRowMapper;
import com.ragagent.org.mapper.AgentShareMapper;
import com.ragagent.org.mapper.OrgSqlMapper;
import com.ragagent.org.mapper.OrganizationTenantMemberMapper;
import com.ragagent.org.mapper.TenantDisabledSharedAgentMapper;
import com.ragagent.org.mapper.TenantDisabledSharedAgentMapper.DisabledRow;

/**
 * 智能体共享 service（对照 Go internal/application/service/agent_share.go）。
 *
 * <p>共享权限恒强制 viewer（Go 注释原文：智能体共享仅支持只读）。
 * web_search_ready 只解析源空间的可用性位（不回显 provider 配置）。</p>
 */
@Service
public class AgentShareService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentShareMapper shareMapper;
    private final TenantDisabledSharedAgentMapper disabledMapper;
    private final OrganizationTenantMemberMapper memberMapper;
    private final AgentRowMapper agentMapper;
    private final OrgSqlMapper sqlMapper;

    public AgentShareService(AgentShareMapper shareMapper, TenantDisabledSharedAgentMapper disabledMapper,
                             OrganizationTenantMemberMapper memberMapper, AgentRowMapper agentMapper,
                             OrgSqlMapper sqlMapper) {
        this.shareMapper = shareMapper;
        this.disabledMapper = disabledMapper;
        this.memberMapper = memberMapper;
        this.agentMapper = agentMapper;
        this.sqlMapper = sqlMapper;
    }

    public AgentShare shareAgent(String agentId, String orgId, String userId, long tenantId, String permission) {
        AgentRow agent = agentMapper.getById(agentId);
        if (agent == null || agent.getTenantId() == null || agent.getTenantId() != tenantId) {
            throw OrgServiceException.agentNotFoundForShare();
        }
        JsonNode cfg = parse(agent.getConfig());
        String modelId = cfg == null ? "" : text(cfg, "model_id");
        if (modelId.isEmpty()) {
            throw OrgServiceException.agentNotConfigured();
        }
        if (agentRequiresRerankModel(cfg) && text(cfg, "rerank_model_id").isEmpty()) {
            throw OrgServiceException.agentNotConfigured();
        }
        // 目标组织必须存在（对照 GetByID → ErrOrgNotFound）
        if (sqlMapper.countOrgById(orgId) == 0) {
            throw new OrgServiceException(OrgServiceException.Kind.ORG_NOT_FOUND);
        }
        OrganizationTenantMember tm = memberRow(orgId, tenantId);
        if (tm == null) {
            throw new OrgServiceException(OrgServiceException.Kind.TENANT_NOT_IN_ORG);
        }
        if (!OrganizationService.hasPermission(tm.getRole(), "editor")) {
            throw OrgServiceException.orgRoleCannotShareAgent();
        }
        permission = "viewer"; // 智能体共享仅支持只读
        AgentShare existing = getByAgentAndOrg(agentId, orgId);
        if (existing != null) {
            existing.setPermission("viewer");
            existing.setUpdatedAt(OffsetDateTime.now());
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AgentShare> uw =
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
            uw.eq("id", existing.getId()).set("permission", existing.getPermission())
                    .set("updated_at", existing.getUpdatedAt());
            shareMapper.update(null, uw);
            return existing;
        }
        OffsetDateTime now = OffsetDateTime.now();
        AgentShare share = new AgentShare();
        share.setId(UUID.randomUUID().toString());
        share.setAgentId(agentId);
        share.setOrganizationId(orgId);
        share.setSharedByUserId(userId);
        share.setSourceTenantId(tenantId);
        share.setPermission(permission);
        share.setCreatedAt(now);
        share.setUpdatedAt(now);
        shareMapper.insert(share);
        return share;
    }

    public void removeShare(String shareId, String userId, long tenantId) {
        AgentShare share = getById(shareId);
        // (1) 原共享人
        if (share.getSharedByUserId() != null && share.getSharedByUserId().equals(userId)) {
            shareMapper.deleteById(shareId);
            return;
        }
        // (2) 源空间 Admin+
        if (tenantId != 0 && share.getSourceTenantId() != null && share.getSourceTenantId() == tenantId) {
            if (OrganizationService.callerTenantRole().hasPermission(TenantRole.ADMIN)) {
                shareMapper.deleteById(shareId);
                return;
            }
        }
        // (3) 目标组织 org admin
        OrganizationTenantMember tm = memberRow(share.getOrganizationId(), tenantId);
        if (tm != null && "admin".equals(tm.getRole())) {
            shareMapper.deleteById(shareId);
            return;
        }
        throw OrgServiceException.agentSharePermission();
    }

    /** 对照 ListSharesByAgent：owner 校验在 service 层（API-Key 短路路由守卫时的收口）。 */
    public List<AgentShare> listSharesByAgent(String agentId, long tenantId) {
        AgentRow agent = agentMapper.getById(agentId);
        if (agent == null || agent.getTenantId() == null || agent.getTenantId() != tenantId) {
            throw OrgServiceException.agentNotFoundForShare();
        }
        return listByAgent(agentId);
    }

    public List<AgentShare> listByAgent(String agentId) {
        return shareMapper.selectList(new LambdaQueryWrapper<AgentShare>()
                .eq(AgentShare::getAgentId, agentId)
                .orderByDesc(AgentShare::getCreatedAt));
    }

    /** 对照 ListByOrganization：join custom_agents（tenant=source、未删）后 preload。 */
    public List<AgentShare> listByOrganization(String orgId) {
        return shareMapper.selectList(new LambdaQueryWrapper<AgentShare>()
                .eq(AgentShare::getOrganizationId, orgId)
                .isNull(AgentShare::getDeletedAt)
                .apply("EXISTS (SELECT 1 FROM custom_agents ca WHERE ca.id = agent_id "
                        + "AND ca.tenant_id = source_tenant_id AND ca.deleted_at IS NULL)")
                .orderByDesc(AgentShare::getCreatedAt));
    }

    public List<AgentShare> listByOrganizations(List<String> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return List.of();
        }
        return shareMapper.selectList(new LambdaQueryWrapper<AgentShare>()
                .in(AgentShare::getOrganizationId, orgIds)
                .isNull(AgentShare::getDeletedAt)
                .apply("EXISTS (SELECT 1 FROM custom_agents ca WHERE ca.id = agent_id "
                        + "AND ca.tenant_id = source_tenant_id AND ca.deleted_at IS NULL)")
                .orderByDesc(AgentShare::getCreatedAt));
    }

    /** 对照 ListSharedAgentsForTenant。 */
    public List<AgentShare> listSharedForTenant(long tenantId) {
        return shareMapper.selectList(new LambdaQueryWrapper<AgentShare>()
                .isNull(AgentShare::getDeletedAt)
                .apply("EXISTS (SELECT 1 FROM custom_agents ca WHERE ca.id = agent_id "
                        + "AND ca.tenant_id = source_tenant_id AND ca.deleted_at IS NULL)")
                .inSql(AgentShare::getOrganizationId,
                        "SELECT otm.organization_id FROM organization_tenant_members otm "
                                + "WHERE otm.tenant_id = " + tenantId)
                .inSql(AgentShare::getOrganizationId,
                        "SELECT id FROM organizations WHERE deleted_at IS NULL")
                .orderByDesc(AgentShare::getCreatedAt));
    }

    /** 对照 ListSharedAgents（跨空间共享给我；disabled_by_me 按键 agent_source）。 */
    public List<SharedAgentInfo> listSharedAgents(long tenantId, TenantRole callerTenantRole) {
        List<AgentShare> shares = listSharedForTenant(tenantId);
        Map<String, SharedAgentInfo> byKey = new LinkedHashMap<>();
        for (AgentShare share : shares) {
            if (share.getSourceTenantId() != null && share.getSourceTenantId() == tenantId) {
                continue;
            }
            OrganizationTenantMember tm = memberRow(share.getOrganizationId(), tenantId);
            if (tm == null) {
                continue;
            }
            String effective = OrganizationService.minOrgRole(share.getPermission(), tm.getRole());
            effective = OrganizationService.applyTenantRoleCap(effective, callerTenantRole);
            AgentRow agent = agentMapper.getById(share.getAgentId());
            if (agent == null) {
                continue;
            }
            SharedAgentInfo info = sharedAgentInfo(agent, share, effective);
            String key = share.getAgentId() + "_" + share.getSourceTenantId();
            SharedAgentInfo existing = byKey.get(key);
            if (existing == null
                    || (OrganizationService.hasPermission(effective, existing.permission())
                            && !effective.equals(existing.permission()))) {
                byKey.put(key, info);
            }
        }
        List<SharedAgentInfo> result = new ArrayList<>(byKey.values());
        Set<String> disabled = disabledKeys(tenantId);
        for (SharedAgentInfo info : result) {
            info.setDisabledByMe(disabled.contains(info.agentId() + "_" + info.sourceTenantId()));
        }
        return result;
    }

    /** 对照 ListSharedAgentsInOrganization（含我共享的；is_mine 行不标 disabled_by_me）。 */
    public List<OrgSharedAgentItem> listSharedAgentsInOrganization(String orgId, long tenantId,
                                                                   TenantRole callerTenantRole) {
        OrganizationTenantMember tm = memberRow(orgId, tenantId);
        if (tm == null) {
            throw new OrgServiceException(OrgServiceException.Kind.TENANT_NOT_IN_ORG);
        }
        List<AgentShare> shares = listByOrganization(orgId);
        List<OrgSharedAgentItem> result = new ArrayList<>();
        for (AgentShare share : shares) {
            String effective = OrganizationService.minOrgRole(share.getPermission(), tm.getRole());
            effective = OrganizationService.applyTenantRoleCap(effective, callerTenantRole);
            AgentRow agent = agentMapper.getById(share.getAgentId());
            if (agent == null) {
                continue;
            }
            SharedAgentInfo info = sharedAgentInfo(agent, share, effective);
            boolean isMine = share.getSourceTenantId() != null && share.getSourceTenantId() == tenantId;
            result.add(new OrgSharedAgentItem(info, isMine));
        }
        Set<String> disabled = disabledKeys(tenantId);
        for (OrgSharedAgentItem item : result) {
            if (!item.isMine()) {
                item.info().setDisabledByMe(
                        disabled.contains(item.info().agentId() + "_" + item.info().sourceTenantId()));
            }
        }
        return result;
    }

    /** 对照 ListSharedAgentsInOrganizations（批量：org → items）。 */
    public Map<String, List<OrgSharedAgentItem>> listSharedAgentsInOrganizations(
            List<String> orgIds, long tenantId, TenantRole callerTenantRole,
            Map<String, OrganizationTenantMember> members) {
        Map<String, List<OrgSharedAgentItem>> out = new LinkedHashMap<>();
        if (orgIds == null || orgIds.isEmpty()) {
            return out;
        }
        List<AgentShare> shares = listByOrganizations(orgIds);
        Map<String, List<AgentShare>> byOrg = new LinkedHashMap<>();
        for (AgentShare share : shares) {
            if (members.containsKey(share.getOrganizationId())) {
                byOrg.computeIfAbsent(share.getOrganizationId(), k -> new ArrayList<>()).add(share);
            }
        }
        Set<String> disabled = disabledKeys(tenantId);
        for (Map.Entry<String, List<AgentShare>> e : byOrg.entrySet()) {
            OrganizationTenantMember tm = members.get(e.getKey());
            List<OrgSharedAgentItem> result = new ArrayList<>();
            for (AgentShare share : e.getValue()) {
                String effective = OrganizationService.minOrgRole(share.getPermission(), tm.getRole());
                effective = OrganizationService.applyTenantRoleCap(effective, callerTenantRole);
                AgentRow agent = agentMapper.getById(share.getAgentId());
                if (agent == null) {
                    continue;
                }
                SharedAgentInfo info = sharedAgentInfo(agent, share, effective);
                boolean isMine = share.getSourceTenantId() != null && share.getSourceTenantId() == tenantId;
                if (!isMine) {
                    info.setDisabledByMe(disabled.contains(share.getAgentId() + "_" + share.getSourceTenantId()));
                }
                result.add(new OrgSharedAgentItem(info, isMine));
            }
            out.put(e.getKey(), result);
        }
        return out;
    }

    /** 对照 CountByOrganizations（排除已删 agents）。 */
    public Map<String, Long> countByOrganizations(List<String> orgIds) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (orgIds == null || orgIds.isEmpty()) {
            return out;
        }
        for (String id : orgIds) {
            out.put(id, 0L);
        }
        List<AgentShare> shares = shareMapper.selectList(new LambdaQueryWrapper<AgentShare>()
                .in(AgentShare::getOrganizationId, orgIds)
                .isNull(AgentShare::getDeletedAt)
                .apply("EXISTS (SELECT 1 FROM custom_agents ca WHERE ca.id = agent_id "
                        + "AND ca.tenant_id = source_tenant_id AND ca.deleted_at IS NULL)"));
        for (AgentShare s : shares) {
            out.merge(s.getOrganizationId(), 1L, Long::sum);
        }
        return out;
    }

    public void setSharedAgentDisabledByMe(long tenantId, String agentId, long sourceTenantId, boolean disabled) {
        if (disabled) {
            disabledMapper.addIfAbsent(tenantId, agentId, sourceTenantId);
        } else {
            disabledMapper.remove(tenantId, agentId, sourceTenantId);
        }
    }

    /** 对照 GetShareByAgentIDForTenant（exclude 源空间；单行）。 */
    public AgentShare getShareByAgentIdForTenant(long tenantId, String agentId, long excludeTenantId) {
        List<AgentShare> rows = shareMapper.selectList(new LambdaQueryWrapper<AgentShare>()
                .eq(AgentShare::getAgentId, agentId)
                .ne(AgentShare::getSourceTenantId, excludeTenantId)
                .isNull(AgentShare::getDeletedAt)
                .inSql(AgentShare::getOrganizationId,
                        "SELECT otm.organization_id FROM organization_tenant_members otm "
                                + "WHERE otm.tenant_id = " + tenantId)
                .orderByAsc(AgentShare::getId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AgentShare getById(String id) {
        AgentShare share = shareMapper.selectOne(new LambdaQueryWrapper<AgentShare>()
                .eq(AgentShare::getId, id).last("LIMIT 1"));
        if (share == null) {
            throw new OrgServiceException(OrgServiceException.Kind.PLAIN, "agent share not found");
        }
        return share;
    }

    public AgentShare getByAgentAndOrg(String agentId, String orgId) {
        return shareMapper.selectOne(new LambdaQueryWrapper<AgentShare>()
                .eq(AgentShare::getAgentId, agentId)
                .eq(AgentShare::getOrganizationId, orgId)
                .isNull(AgentShare::getDeletedAt)
                .last("LIMIT 1"));
    }

    // ── 内部 ──

    private SharedAgentInfo sharedAgentInfo(AgentRow agent, AgentShare share, String effective) {
        String orgName = null;
        return new SharedAgentInfo(agent, share.getId(), share.getOrganizationId(), orgName,
                effective, share.getSourceTenantId(), share.getCreatedAt(),
                share.getSharedByUserId(), null,
                isAgentWebSearchReady(agent, share.getSourceTenantId()), false);
    }

    /** 对照 isAgentWebSearchReady：只解析可用性位（本批不查 provider 行详情）。 */
    private boolean isAgentWebSearchReady(AgentRow agent, Long sourceTenantId) {
        if (agent == null || sourceTenantId == null) {
            return false;
        }
        JsonNode cfg = parse(agent.getConfig());
        if (cfg == null || !cfg.path("web_search_enabled").asBoolean(false)) {
            return false;
        }
        String providerId = text(cfg, "web_search_provider_id");
        return sqlMapper.countWebSearchProvider(sourceTenantId, providerId) > 0;
    }

    /**
     * 对照 agentRequiresRerankModel：KBSelectionMode=none 不需要 rerank；
     * AllowedTools 空 → DefaultAllowedTools()（含 knowledge_search）→ 需要。
     */
    public static boolean agentRequiresRerankModel(JsonNode cfg) {
        if (cfg == null) {
            return false;
        }
        if ("none".equals(text(cfg, "kb_selection_mode"))) {
            return false;
        }
        JsonNode allowed = cfg.get("allowed_tools");
        if (allowed == null || !allowed.isArray() || allowed.isEmpty()) {
            return true; // DefaultAllowedTools 含 knowledge_search
        }
        for (JsonNode t : allowed) {
            if ("knowledge_search".equals(t.asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean orgExists(String orgId) {
        return sqlMapper.countOrgById(orgId) > 0;
    }

    private OrganizationTenantMember memberRow(String orgId, long tenantId) {
        return memberMapper.selectOne(new LambdaQueryWrapper<OrganizationTenantMember>()
                .eq(OrganizationTenantMember::getOrganizationId, orgId)
                .eq(OrganizationTenantMember::getTenantId, tenantId)
                .last("LIMIT 1"));
    }

    private Set<String> disabledKeys(long tenantId) {
        Set<String> keys = new HashSet<>();
        for (DisabledRow d : disabledMapper.listByTenant(tenantId)) {
            keys.add(d.getAgentId() + "_" + d.getSourceTenantId());
        }
        return keys;
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

    private static String text(JsonNode c, String field) {
        JsonNode n = c.get(field);
        return n == null || n.isNull() ? "" : n.asText();
    }

    /** 对照 types.SharedAgentInfo（可变 carrier，响应层再补 orgName/sharedByUsername）。 */
    public static final class SharedAgentInfo {
        private final AgentRow agent;
        private final String shareId;
        private final String organizationId;
        private String orgName = "";
        private final String permission;
        private final Long sourceTenantId;
        private final OffsetDateTime sharedAt;
        private final String sharedByUserId;
        private String sharedByUsername = "";
        private final boolean webSearchReady;
        private boolean disabledByMe;

        public SharedAgentInfo(AgentRow agent, String shareId, String organizationId, String orgName,
                               String permission, Long sourceTenantId, OffsetDateTime sharedAt,
                               String sharedByUserId, String sharedByUsername, boolean webSearchReady,
                               boolean disabledByMe) {
            this.agent = agent;
            this.shareId = shareId;
            this.organizationId = organizationId;
            this.orgName = orgName == null ? "" : orgName;
            this.permission = permission;
            this.sourceTenantId = sourceTenantId;
            this.sharedAt = sharedAt;
            this.sharedByUserId = sharedByUserId;
            this.sharedByUsername = sharedByUsername == null ? "" : sharedByUsername;
            this.webSearchReady = webSearchReady;
            this.disabledByMe = disabledByMe;
        }

        public AgentRow agent() { return agent; }
        public String shareId() { return shareId; }
        public String organizationId() { return organizationId; }
        public String orgName() { return orgName; }
        public void setOrgName(String v) { this.orgName = v; }
        public String permission() { return permission; }
        public Long sourceTenantId() { return sourceTenantId; }
        public OffsetDateTime sharedAt() { return sharedAt; }
        public String sharedByUserId() { return sharedByUserId; }
        public String sharedByUsername() { return sharedByUsername; }
        public void setSharedByUsername(String v) { this.sharedByUsername = v; }
        public boolean webSearchReady() { return webSearchReady; }
        public boolean disabledByMe() { return disabledByMe; }
        public void setDisabledByMe(boolean v) { this.disabledByMe = v; }
        public String agentId() { return agent == null ? "" : agent.getId(); }
    }

    public record OrgSharedAgentItem(SharedAgentInfo info, boolean isMine) {}
}
