package com.ragagent.org.dto;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.org.domain.AgentRow;
import com.ragagent.org.domain.AgentShare;
import com.ragagent.org.domain.KbShare;
import com.ragagent.org.domain.Organization;
import com.ragagent.org.domain.OrganizationJoinRequest;
import com.ragagent.org.domain.OrganizationTenantMember;
import com.ragagent.knowledge.domain.KbIndexingStrategy;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.dto.KnowledgeBaseResponseBuilder;

/**
 * 组织/共享域的响应构造层。JSON 是契约：每个 map 的键序逐字对照 Go 的
 * struct 声明序（LinkedHashMap）或 gin.H 字母序（手工按序 put，嵌套 map 同）。
 *
 * <p>两个特殊形状：</p>
 * <ul>
 *   <li><b>agentPayload / agentConfigMap</b>：Go 把 custom_agents 行扫描进 struct 再
 *       marshal（无 EnsureDefaults——Preload 路径不过默认值注入），输出 = struct 声明序
 *       + omitempty 语义 + nil 切片 null。Java 侧从 jsonb 树逐字段重排，缺失/null 的
 *       标量折叠成 Go 零值（string ""、数 0、bool false），整数值的浮点按 Go 规则输出
 *       （0.7 → 0.7，0 → 0）。</li>
 *   <li><b>sharedKbRow</b>：Go 的 map（gin.H）→ 键字母序；内嵌 KB 走
 *       SharedStoreDisplay()（source=shared/status=available，删 vector_store_id，
 *       无 name/engine_type 键）。</li>
 * </ul>
 */
public final class OrgResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OrgResponses() {}

    // ── OrganizationResponse（Go struct 声明序）──
    public static Map<String, Object> orgResponse(Organization org, boolean isOwner, long callerTenantId,
            String currentUserId, int memberCount, int shareCount, int agentShareCount,
            String myRole, boolean hasPendingUpgrade, String inviteCode,
            OffsetDateTime inviteCodeExpiresAt, Integer pendingJoinRequestCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", org.getId());
        m.put("name", org.getName());
        m.put("description", org.getDescription());
        if (org.getAvatar() != null && !org.getAvatar().isEmpty()) {
            m.put("avatar", org.getAvatar());
        }
        m.put("owner_id", org.getOwnerId());
        m.put("owner_tenant_id", org.ownerTenantIdOrZero());
        if (inviteCode != null && !inviteCode.isEmpty()) {
            m.put("invite_code", inviteCode);
        }
        if (inviteCodeExpiresAt != null) {
            m.put("invite_code_expires_at", inviteCodeExpiresAt);
        }
        m.put("invite_code_validity_days", org.getInviteCodeValidityDays());
        m.put("require_approval", org.isRequireApproval());
        m.put("searchable", org.isSearchable());
        m.put("member_limit", org.getMemberLimit());
        m.put("member_count", memberCount);
        m.put("share_count", shareCount);
        m.put("agent_share_count", agentShareCount);
        m.put("pending_join_request_count", pendingJoinRequestCount == null ? 0 : pendingJoinRequestCount);
        m.put("is_owner", isOwner);
        if (myRole != null && !myRole.isEmpty()) {
            m.put("my_role", myRole);
        }
        m.put("has_pending_upgrade", hasPendingUpgrade);
        m.put("created_at", org.getCreatedAt());
        m.put("updated_at", org.getUpdatedAt());
        return m;
    }

    /** SearchableOrganizationItem（struct 声明序）。 */
    public static Map<String, Object> searchableOrgItem(Organization org, int memberCount, int shareCount,
            int agentShareCount, boolean isAlreadyMember) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", org.getId());
        m.put("name", org.getName());
        m.put("description", org.getDescription());
        if (org.getAvatar() != null && !org.getAvatar().isEmpty()) {
            m.put("avatar", org.getAvatar());
        }
        m.put("member_count", memberCount);
        m.put("member_limit", org.getMemberLimit());
        m.put("share_count", shareCount);
        m.put("agent_share_count", agentShareCount);
        m.put("is_already_member", isAlreadyMember);
        m.put("require_approval", org.isRequireApproval());
        return m;
    }

    /** OrganizationMemberResponse（struct 声明序）。 */
    public static Map<String, Object> memberResponse(OrganizationTenantMember m, String tenantName,
            com.ragagent.auth.domain.User rep) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", m.getId());
        r.put("user_id", m.getRepresentativeUserId() == null ? "" : m.getRepresentativeUserId());
        r.put("representative_user_id", m.getRepresentativeUserId() == null ? "" : m.getRepresentativeUserId());
        r.put("username", rep == null ? "" : nz(rep.getUsername()));
        r.put("email", rep == null ? "" : nz(rep.getEmail()));
        r.put("avatar", rep == null ? "" : nz(rep.getAvatar()));
        r.put("role", m.getRole());
        r.put("tenant_id", m.getTenantId() == null ? 0L : m.getTenantId());
        if (tenantName != null && !tenantName.isEmpty()) {
            r.put("tenant_name", tenantName);
        }
        r.put("joined_at", m.getCreatedAt());
        return r;
    }

    /** 原始 OrganizationJoinRequest 实体序列化（struct 声明序，无 omitempty）。 */
    public static Map<String, Object> joinRequestEntity(OrganizationJoinRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("organization_id", r.getOrganizationId());
        m.put("user_id", r.getUserId());
        m.put("tenant_id", r.getTenantId() == null ? 0L : r.getTenantId());
        m.put("request_type", r.getRequestType());
        m.put("prev_role", r.getPrevRole() == null ? "" : r.getPrevRole());
        m.put("requested_role", r.getRequestedRole());
        m.put("status", r.getStatus());
        m.put("message", r.getMessage() == null ? "" : r.getMessage());
        m.put("reviewed_by", r.getReviewedBy() == null ? "" : r.getReviewedBy());
        m.put("reviewed_at", r.getReviewedAt());
        m.put("review_message", r.getReviewMessage() == null ? "" : r.getReviewMessage());
        m.put("created_at", r.getCreatedAt());
        m.put("updated_at", r.getUpdatedAt());
        return m;
    }

    /** JoinRequestResponse（struct 声明序；reviewed_at omitempty）。 */
    public static Map<String, Object> joinRequestResponse(OrganizationJoinRequest r,
            com.ragagent.auth.domain.User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("user_id", r.getUserId());
        m.put("username", user == null ? "" : nz(user.getUsername()));
        m.put("email", user == null ? "" : nz(user.getEmail()));
        m.put("message", r.getMessage() == null ? "" : r.getMessage());
        String requestType = r.getRequestType() == null || r.getRequestType().isEmpty()
                ? "join" : r.getRequestType();
        m.put("request_type", requestType);
        m.put("prev_role", r.getPrevRole() == null ? "" : r.getPrevRole());
        m.put("requested_role", r.getRequestedRole());
        m.put("status", r.getStatus());
        m.put("created_at", r.getCreatedAt());
        if (r.getReviewedAt() != null) {
            m.put("reviewed_at", r.getReviewedAt());
        }
        return m;
    }

    /** TenantInviteCandidate（struct 声明序；representative_avatar omitempty）。 */
    public static Map<String, Object> tenantInviteCandidate(long tenantId, String tenantName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant_id", tenantId);
        m.put("tenant_name", tenantName);
        m.put("representative_user_id", "");
        m.put("representative_username", "");
        m.put("representative_email", "");
        return m;
    }

    /** 原始 KnowledgeBaseShare 实体（POST/PUT share 的 data）。 */
    public static Map<String, Object> kbShareEntity(KbShare s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("knowledge_base_id", s.getKnowledgeBaseId());
        m.put("organization_id", s.getOrganizationId());
        m.put("shared_by_user_id", s.getSharedByUserId());
        m.put("source_tenant_id", s.getSourceTenantId() == null ? 0L : s.getSourceTenantId());
        m.put("permission", s.getPermission());
        m.put("created_at", s.getCreatedAt());
        m.put("updated_at", s.getUpdatedAt());
        m.put("deleted_at", s.getDeletedAt());
        return m;
    }

    /** 原始 AgentShare 实体（POST agent share 的 data）。 */
    public static Map<String, Object> agentShareEntity(AgentShare s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("agent_id", s.getAgentId());
        m.put("organization_id", s.getOrganizationId());
        m.put("shared_by_user_id", s.getSharedByUserId());
        m.put("source_tenant_id", s.getSourceTenantId() == null ? 0L : s.getSourceTenantId());
        m.put("permission", s.getPermission());
        m.put("created_at", s.getCreatedAt());
        m.put("updated_at", s.getUpdatedAt());
        m.put("deleted_at", s.getDeletedAt());
        return m;
    }

    /** ListKBShares 的行（KnowledgeBaseShareResponse 声明序，含 org 名、不含 my_*）。 */
    public static Map<String, Object> kbShareResponse(KbShare s, String organizationName,
            String sharedByUsername) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("knowledge_base_id", s.getKnowledgeBaseId());
        m.put("knowledge_base_name", "");
        m.put("knowledge_base_type", "");
        m.put("knowledge_count", 0L);
        m.put("chunk_count", 0L);
        m.put("organization_id", s.getOrganizationId());
        m.put("organization_name", organizationName == null ? "" : organizationName);
        m.put("shared_by_user_id", s.getSharedByUserId());
        m.put("shared_by_username", sharedByUsername == null ? "" : sharedByUsername);
        m.put("source_tenant_id", s.getSourceTenantId() == null ? 0L : s.getSourceTenantId());
        m.put("permission", s.getPermission());
        m.put("my_role_in_org", "");
        m.put("my_permission", "");
        m.put("created_at", s.getCreatedAt());
        m.put("require_approval", false);
        return m;
    }

    /** ListOrgShares 的行（同 struct，附 my_role_in_org/my_permission 与 KB 元信息）。 */
    public static Map<String, Object> kbShareResponseFull(KbShare s, String myRole, String effectivePerm,
            KnowledgeBase kb, Long knowledgeCount, Long chunkCount, String sharedByUsername) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("knowledge_base_id", s.getKnowledgeBaseId());
        m.put("knowledge_base_name", kb == null ? "" : kb.getName());
        m.put("knowledge_base_type", kb == null ? "" : kb.getType());
        m.put("knowledge_count", knowledgeCount == null ? 0L : knowledgeCount);
        m.put("chunk_count", chunkCount == null ? 0L : chunkCount);
        m.put("organization_id", s.getOrganizationId());
        m.put("organization_name", "");
        m.put("shared_by_user_id", s.getSharedByUserId());
        m.put("shared_by_username", sharedByUsername == null ? "" : sharedByUsername);
        m.put("source_tenant_id", s.getSourceTenantId() == null ? 0L : s.getSourceTenantId());
        m.put("permission", s.getPermission());
        m.put("my_role_in_org", myRole);
        m.put("my_permission", effectivePerm);
        m.put("created_at", s.getCreatedAt());
        m.put("require_approval", false);
        return m;
    }

    /** ListAgentShares 的行（AgentShareResponse 声明序；agent_name 恒 ""）。 */
    public static Map<String, Object> agentShareResponse(AgentShare s, String organizationName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("agent_id", s.getAgentId());
        m.put("agent_name", "");
        m.put("organization_id", s.getOrganizationId());
        m.put("organization_name", organizationName == null ? "" : organizationName);
        m.put("shared_by_user_id", s.getSharedByUserId());
        m.put("shared_by_username", "");
        m.put("source_tenant_id", s.getSourceTenantId() == null ? 0L : s.getSourceTenantId());
        m.put("permission", s.getPermission());
        m.put("created_at", s.getCreatedAt());
        return m;
    }

    /** ListOrgAgentShares 的行（附 my_* 与 agent scope 摘要；omitempty 逐字段）。 */
    public static Map<String, Object> agentShareResponseFull(AgentShare s, String myRole, String effectivePerm,
            AgentRow agent, String organizationName, String sharedByUsername) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("agent_id", s.getAgentId());
        m.put("agent_name", agent == null ? "" : nz(agent.getName()));
        m.put("organization_id", s.getOrganizationId());
        m.put("organization_name", organizationName == null ? "" : organizationName);
        m.put("shared_by_user_id", s.getSharedByUserId());
        m.put("shared_by_username", sharedByUsername == null ? "" : sharedByUsername);
        m.put("source_tenant_id", s.getSourceTenantId() == null ? 0L : s.getSourceTenantId());
        m.put("permission", s.getPermission());
        if (myRole != null && !myRole.isEmpty()) {
            m.put("my_role_in_org", myRole);
        }
        if (effectivePerm != null && !effectivePerm.isEmpty()) {
            m.put("my_permission", effectivePerm);
        }
        m.put("created_at", s.getCreatedAt());
        JsonNode cfg = agent == null ? null : parse(agent.getConfig());
        String kbMode = cfg == null ? "" : text(cfg, "kb_selection_mode");
        if (!kbMode.isEmpty()) {
            m.put("scope_kb", kbMode);
            if ("selected".equals(kbMode) && cfg.get("knowledge_bases") != null && cfg.get("knowledge_bases").isArray()
                    && cfg.get("knowledge_bases").size() > 0) {
                m.put("scope_kb_count", cfg.get("knowledge_bases").size());
            }
        }
        boolean webSearch = cfg != null && cfg.path("web_search_enabled").asBoolean(false);
        if (webSearch) {
            m.put("scope_web_search", true);
        }
        String mcpMode = cfg == null ? "" : text(cfg, "mcp_selection_mode");
        if (!mcpMode.isEmpty()) {
            m.put("scope_mcp", mcpMode);
            if ("selected".equals(mcpMode) && cfg.get("mcp_services") != null && cfg.get("mcp_services").isArray()
                    && cfg.get("mcp_services").size() > 0) {
                m.put("scope_mcp_count", cfg.get("mcp_services").size());
            }
        }
        if (agent != null && agent.getAvatar() != null && !agent.getAvatar().isEmpty()) {
            m.put("agent_avatar", agent.getAvatar());
        }
        return m;
    }

    // ── sharedKBRow（gin.H 字母序）──
    public static Map<String, Object> sharedKbRow(KnowledgeBase kb, String shareId, String organizationId,
            String orgName, String permission, long sourceTenantId, OffsetDateTime sharedAt,
            Map<String, Object> extras, String rawIndexingJson, String rawStorageBackendId) {
        Map<String, Object> kbView = KnowledgeBaseResponseBuilder.build(kb, null);
        kbView.remove("vector_store_id");
        kbView.remove("vector_store_name");
        kbView.remove("vector_store_engine_type");
        kbView.put("vector_store_source", "shared");
        kbView.put("vector_store_status", "available");
        // 共享读面 Go 走 Preload raw 行（无 EnsureDefaults）：
        //  - indexing_strategy NULL → 零值 struct（全 false 对象）；
        //  - capabilities 从 raw 索引布尔派生（graph 还要看 extract_config.enabled）；
        //  - storage_backend_id omitempty：空时整键缺席（builder 恒放 null，这里摘掉）。
        KbIndexingStrategy rawIdx = zeroStrategy();
        if (rawIndexingJson != null && !rawIndexingJson.isEmpty()) {
            try {
                JsonNode n = MAPPER.readTree(rawIndexingJson);
                if (n != null && n.isObject()) {
                    rawIdx = new KbIndexingStrategy();
                    rawIdx.setVectorEnabled(n.path("vector_enabled").asBoolean(false));
                    rawIdx.setKeywordEnabled(n.path("keyword_enabled").asBoolean(false));
                    rawIdx.setWikiEnabled(n.path("wiki_enabled").asBoolean(false));
                    rawIdx.setGraphEnabled(n.path("graph_enabled").asBoolean(false));
                }
            } catch (Exception ignored) {
                rawIdx = zeroStrategy();
            }
        }
        Map<String, Object> idx = new LinkedHashMap<>();
        idx.put("graph_enabled", rawIdx.isGraphEnabled());
        idx.put("keyword_enabled", rawIdx.isKeywordEnabled());
        idx.put("vector_enabled", rawIdx.isVectorEnabled());
        idx.put("wiki_enabled", rawIdx.isWikiEnabled());
        kbView.put("indexing_strategy", idx);
        boolean graph = rawIdx.isGraphEnabled()
                && kb.getExtractConfig() != null
                && kb.getExtractConfig().path("enabled").asBoolean(false);
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("faq", "faq".equals(kb.getType()));
        caps.put("graph", graph);
        caps.put("keyword", rawIdx.isKeywordEnabled());
        caps.put("vector", rawIdx.isVectorEnabled());
        caps.put("wiki", rawIdx.isWikiEnabled());
        kbView.put("capabilities", caps);
        if (rawStorageBackendId == null || rawStorageBackendId.isEmpty()) {
            kbView.remove("storage_backend_id");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        // extras 为 null（/shared-knowledge-bases 主路径）时不输出 is_mine（Go extras=nil）
        if (extras != null && extras.containsKey("is_mine")) {
            row.put("is_mine", extras.get("is_mine"));
        }
        row.put("knowledge_base", kbView);
        row.put("org_name", orgName == null ? "" : orgName);
        row.put("organization_id", organizationId == null ? "" : organizationId);
        row.put("permission", permission);
        row.put("share_id", shareId == null ? "" : shareId);
        row.put("shared_at", sharedAt);
        if (extras != null && extras.containsKey("source_from_agent")) {
            // gin.H map 字母序：source_from_agent < source_tenant_id（f < t）
            row.put("source_from_agent", extras.get("source_from_agent"));
        }
        row.put("source_tenant_id", sourceTenantId);
        return row;
    }

    /** SharedAgentInfo（struct 声明序）。 */
    public static Map<String, Object> sharedAgentInfo(AgentRow agent, String shareId, String organizationId,
            String orgName, String permission, long sourceTenantId, OffsetDateTime sharedAt,
            String sharedByUserId, String sharedByUsername, boolean webSearchReady, boolean disabledByMe) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent", agent == null ? null : agentPayload(agent));
        m.put("share_id", shareId == null ? "" : shareId);
        m.put("organization_id", organizationId == null ? "" : organizationId);
        m.put("org_name", orgName == null ? "" : orgName);
        m.put("permission", permission);
        m.put("source_tenant_id", sourceTenantId);
        m.put("shared_at", sharedAt);
        if (sharedByUserId != null && !sharedByUserId.isEmpty()) {
            m.put("shared_by_user_id", sharedByUserId);
        }
        if (sharedByUsername != null && !sharedByUsername.isEmpty()) {
            m.put("shared_by_username", sharedByUsername);
        }
        m.put("web_search_ready", webSearchReady);
        m.put("disabled_by_me", disabledByMe);
        return m;
    }

    /** SourceFromAgentInfo（struct 声明序）。 */
    public static Map<String, Object> sourceFromAgent(String agentId, String agentName, String kbSelectionMode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent_id", agentId);
        m.put("agent_name", agentName);
        m.put("kb_selection_mode", kbSelectionMode);
        return m;
    }

    // ── CustomAgent struct 序列化（声明序 + omitempty）──
    public static Map<String, Object> agentPayload(AgentRow a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("name", nz(a.getName()));
        m.put("description", nz(a.getDescription()));
        m.put("avatar", nz(a.getAvatar()));
        m.put("is_builtin", a.isBuiltin());
        m.put("tenant_id", a.getTenantId() == null ? 0L : a.getTenantId());
        m.put("created_by", nz(a.getCreatedBy()));
        m.put("config", agentConfigMap(parse(a.getConfig())));
        m.put("created_at", a.getCreatedAt());
        m.put("updated_at", a.getUpdatedAt());
        m.put("deleted_at", null);
        return m;
    }

    /**
     * CustomAgentConfig marshal（声明序）。Go json.Unmarshal 缺键 → 零值，
     * marshal 非 omitempty 键恒输出；nil 切片/指针输出 null；omitempty 零值省略。
     */
    public static Map<String, Object> agentConfigMap(JsonNode c) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (c == null || c.isNull()) {
            c = MAPPER.createObjectNode();
        }
        m.put("agent_mode", text(c, "agent_mode"));
        ifStr(c, "agent_type", m);
        m.put("system_prompt", text(c, "system_prompt"));
        ifStr(c, "system_prompt_id", m);
        m.put("context_template", text(c, "context_template"));
        ifStr(c, "context_template_id", m);
        m.put("model_id", text(c, "model_id"));
        m.put("rerank_model_id", text(c, "rerank_model_id"));
        m.put("temperature", goNumber(c, "temperature"));
        m.put("max_completion_tokens", intOf(c, "max_completion_tokens"));
        m.put("thinking", boolPtr(c, "thinking"));
        m.put("citation_enabled", boolPtr(c, "citation_enabled"));
        m.put("max_iterations", intOf(c, "max_iterations"));
        ifIntNonZero(c, "llm_call_timeout", m);
        m.put("allowed_tools", strSlice(c, "allowed_tools"));
        m.put("mcp_selection_mode", text(c, "mcp_selection_mode"));
        m.put("mcp_services", strSlice(c, "mcp_services"));
        ifIntNonZero(c, "mcp_auth_wait_timeout", m);
        m.put("skills_selection_mode", text(c, "skills_selection_mode"));
        m.put("selected_skills", strSlice(c, "selected_skills"));
        ifStr(c, "sandbox_config_id", m);
        m.put("kb_selection_mode", text(c, "kb_selection_mode"));
        m.put("knowledge_bases", strSlice(c, "knowledge_bases"));
        m.put("retrieve_kb_only_when_mentioned", boolOf(c, "retrieve_kb_only_when_mentioned"));
        m.put("retain_retrieval_history", boolOf(c, "retain_retrieval_history"));
        m.put("image_upload_enabled", boolOf(c, "image_upload_enabled"));
        m.put("vlm_model_id", text(c, "vlm_model_id"));
        m.put("audio_upload_enabled", boolOf(c, "audio_upload_enabled"));
        m.put("asr_model_id", text(c, "asr_model_id"));
        m.put("image_storage_provider", text(c, "image_storage_provider"));
        m.put("supported_file_types", strSlice(c, "supported_file_types"));
        ifArrayNonEmpty(c, "chat_parser_engine_rules", m);
        m.put("attachment_image_understanding", boolOf(c, "attachment_image_understanding"));
        ifIntNonZero(c, "attachment_ocr_max_pages", m);
        ifIntNonZero(c, "attachment_parse_wait_timeout_sec", m);
        m.put("data_analysis_enabled", boolOf(c, "data_analysis_enabled"));
        m.put("faq_priority_enabled", boolOf(c, "faq_priority_enabled"));
        m.put("faq_direct_answer_threshold", goNumber(c, "faq_direct_answer_threshold"));
        m.put("faq_score_boost", goNumber(c, "faq_score_boost"));
        m.put("web_search_enabled", boolOf(c, "web_search_enabled"));
        m.put("web_search_max_results", intOf(c, "web_search_max_results"));
        ifStr(c, "web_search_provider_id", m);
        m.put("web_fetch_enabled", boolOf(c, "web_fetch_enabled"));
        ifIntNonZero(c, "web_fetch_top_n", m);
        m.put("multi_turn_enabled", boolOf(c, "multi_turn_enabled"));
        m.put("history_turns", intOf(c, "history_turns"));
        ifBoolPtrNonNil(c, "memory_enabled", m);
        m.put("embedding_top_k", intOf(c, "embedding_top_k"));
        m.put("keyword_threshold", goNumber(c, "keyword_threshold"));
        m.put("vector_threshold", goNumber(c, "vector_threshold"));
        m.put("rerank_top_k", intOf(c, "rerank_top_k"));
        m.put("rerank_threshold", goNumber(c, "rerank_threshold"));
        m.put("enable_query_expansion", boolOf(c, "enable_query_expansion"));
        m.put("enable_rewrite", boolOf(c, "enable_rewrite"));
        m.put("rewrite_prompt_system", text(c, "rewrite_prompt_system"));
        m.put("rewrite_prompt_user", text(c, "rewrite_prompt_user"));
        ifStr(c, "query_understand_model_id", m);
        m.put("fallback_strategy", text(c, "fallback_strategy"));
        m.put("fallback_response", text(c, "fallback_response"));
        m.put("fallback_prompt", text(c, "fallback_prompt"));
        ifStrMapNonEmpty(c, "intent_prompts", m);
        ifQuestionSuggestions(c.get("question_suggestions"), m);
        return m;
    }

    /** QuestionSuggestionConfig（omitempty 指针；嵌套按声明序）。 */
    private static void ifQuestionSuggestions(JsonNode q, Map<String, Object> m) {
        if (q == null || q.isNull()) {
            return;
        }
        Map<String, Object> outer = new LinkedHashMap<>();
        Map<String, Object> starters = new LinkedHashMap<>();
        JsonNode s = q.get("starters");
        starters.put("enabled", s != null && s.path("enabled").asBoolean(false));
        starters.put("mode", s == null ? "" : text(s, "mode"));
        starters.put("items", s == null ? null : strList(s.get("items")));
        starters.put("count", s == null ? 0 : s.path("count").asInt(0));
        Map<String, Object> fu = new LinkedHashMap<>();
        JsonNode f = q.get("follow_ups");
        fu.put("enabled", f != null && f.path("enabled").asBoolean(false));
        fu.put("mode", f == null ? "" : text(f, "mode"));
        fu.put("count", f == null ? 0 : f.path("count").asInt(0));
        if (f != null && !text(f, "model_id").isEmpty()) {
            fu.put("model_id", text(f, "model_id"));
        }
        if (f != null && !text(f, "additional_instruction").isEmpty()) {
            fu.put("additional_instruction", text(f, "additional_instruction"));
        }
        if (f != null && f.get("categories") != null && f.get("categories").isArray() && f.get("categories").size() > 0) {
            fu.put("categories", strList(f.get("categories")));
        }
        fu.put("max_context_turns", f == null ? 0 : f.path("max_context_turns").asInt(0));
        fu.put("suppress_on_fallback", f != null && f.path("suppress_on_fallback").asBoolean(false));
        fu.put("suppress_when_answer_asks_question", f != null && f.path("suppress_when_answer_asks_question").asBoolean(false));
        fu.put("knowledge_fallback", f != null && f.path("knowledge_fallback").asBoolean(false));
        fu.put("allow_regenerate", f != null && f.path("allow_regenerate").asBoolean(false));
        outer.put("starters", starters);
        outer.put("follow_ups", fu);
        m.put("question_suggestions", outer);
    }

    // ── 小工具 ──

    /** 全 false 的零值索引策略（Go NULL 列 → 零值 struct）。 */
    private static com.ragagent.knowledge.domain.KbIndexingStrategy zeroStrategy() {
        return new com.ragagent.knowledge.domain.KbIndexingStrategy();
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

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String text(JsonNode c, String field) {
        JsonNode n = c.get(field);
        return n == null || n.isNull() ? "" : n.asText();
    }

    private static boolean boolOf(JsonNode c, String field) {
        JsonNode n = c.get(field);
        return n != null && n.asBoolean(false);
    }

    private static int intOf(JsonNode c, String field) {
        JsonNode n = c.get(field);
        if (n == null || n.isNull()) {
            return 0;
        }
        if (n.isNumber()) {
            return n.intValue();
        }
        return 0;
    }

    /** Go 的 float64 marshal：整数值输出整数形态（0.0 → 0），否则 double。 */
    private static Object goNumber(JsonNode c, String field) {
        JsonNode n = c.get(field);
        if (n == null || n.isNull() || !n.isNumber()) {
            return 0;
        }
        double d = n.asDouble();
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.007199254740992E15) {
            return (long) d;
        }
        return d;
    }

    private static Boolean boolPtr(JsonNode c, String field) {
        JsonNode n = c.get(field);
        if (n == null || n.isNull() || !n.isBoolean()) {
            return null;
        }
        return n.asBoolean();
    }

    private static List<String> strSlice(JsonNode c, String field) {
        JsonNode n = c.get(field);
        return strList(n);
    }

    private static List<String> strList(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            for (JsonNode e : n) {
                out.add(e.isNull() ? null : e.asText());
            }
            return out;
        }
        return out;
    }

    private static void ifStr(JsonNode c, String field, Map<String, Object> m) {
        String v = text(c, field);
        if (!v.isEmpty()) {
            m.put(field, v);
        }
    }

    private static void ifIntNonZero(JsonNode c, String field, Map<String, Object> m) {
        int v = intOf(c, field);
        if (v != 0) {
            m.put(field, v);
        }
    }

    private static void ifBoolPtrNonNil(JsonNode c, String field, Map<String, Object> m) {
        Boolean v = boolPtr(c, field);
        if (v != null) {
            m.put(field, v);
        }
    }

    private static void ifArrayNonEmpty(JsonNode c, String field, Map<String, Object> m) {
        JsonNode n = c.get(field);
        if (n != null && n.isArray() && n.size() > 0) {
            m.put(field, MAPPER.valueToTree(n));
        }
    }

    private static void ifStrMapNonEmpty(JsonNode c, String field, Map<String, Object> m) {
        JsonNode n = c.get(field);
        if (n == null || !n.isObject() || n.size() == 0) {
            return;
        }
        Map<String, Object> sm = new TreeMap<>();
        n.fields().forEachRemaining(e -> sm.put(e.getKey(), e.getValue().isNull() ? "" : e.getValue().asText()));
        m.put(field, sm);
    }
}
