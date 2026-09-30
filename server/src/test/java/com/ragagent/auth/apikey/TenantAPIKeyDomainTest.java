package com.ragagent.auth.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.ragagent.auth.apikey.domain.APIKeyCapability;
import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.apikey.domain.APIKeyScopeType;
import com.ragagent.auth.apikey.domain.APIKeyStringListTypeHandler;
import com.ragagent.auth.apikey.domain.TenantAPIKey;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import com.ragagent.common.error.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 能力模型 / 作用域判定 / 请求上下文语义的单测——逐条对照 Go
 * internal/types/tenant_api_key_test.go、tenant_api_key_scope_test.go
 * 与 types/tenant_api_key.go 里的判定函数。
 *
 * <p>这一组是本模块**权限语义**的守门人：能力枚举、KB 白名单三态、
 * full-access 与能力的关系，任何一条改动都应当先在这里变红。</p>
 */
class TenantAPIKeyDomainTest {

    @AfterEach
    void clearScope() {
        APIKeyScopeContext.clear();
    }

    // ── TenantAPIKeyScope.Normalize（对照 tenant_api_key_test.go L5-32） ──

    @Test
    void normalizePreservesFullAccess() {
        // 归一化不能把 full access 弄丢（Go: TestTenantAPIKeyScopeNormalizePreservesFullAccess）
        TenantAPIKeyScope scope = new TenantAPIKeyScope(0L, null, true, null, null).normalize();
        assertThat(scope.fullAccess()).isTrue();
    }

    @Test
    void normalizeDefaultsToScopedAccess() {
        // 空 scope 也绝不能**凭空变成** full access
        TenantAPIKeyScope scope = TenantAPIKeyScope.empty().normalize();
        assertThat(scope.fullAccess()).isFalse();
        assertThat(scope.scopeType()).isEqualTo(APIKeyScopeType.TENANT);
    }

    @Test
    void normalizeDropsInvalidCapabilities() {
        // 去重 + 丢弃未知能力，顺序保留首次出现
        TenantAPIKeyScope scope = new TenantAPIKeyScope(0L, "tenant", false, null,
                List.of("chat", "bogus", "retrieve", "chat")).normalize();
        assertThat(scope.capabilities()).containsExactly("chat", "retrieve");
    }

    // ── HasCapability（对照 tenant_api_key_scope_test.go L61-73） ──

    @Test
    void hasCapability() {
        TenantAPIKeyScope s = new TenantAPIKeyScope(0L, "tenant", false, null, List.of("chat"));
        assertThat(s.hasCapability(APIKeyCapability.CHAT)).isTrue();
        assertThat(TenantAPIKeyScope.empty().hasCapability(APIKeyCapability.CHAT)).isFalse();
        // 未知能力永远不被满足（先 normalize，失败立即 false）
        assertThat(s.hasCapability("bogus")).isFalse();
        assertThat(s.hasCapability(null)).isFalse();
    }

    // ── NormalizeAPIKeyCapabilities（对照 tenant_api_key_test.go L75-97） ──

    @Test
    void normalizeAllCapabilities() {
        List<String> got = APIKeyCapability.normalizeAll(List.of(
                " Retrieve ", "chat", "read_agents", "manage_kbs", "message_history",
                "manage_mcp_services", "manage_members", "manage_spaces", "bogus", ""));
        assertThat(got).containsExactly(
                "retrieve", "chat", "read_agents", "manage_kbs", "message_history",
                "manage_mcp_services", "manage_members", "manage_spaces");
    }

    @Test
    void normalizeAllCapabilitiesNeverReturnsNull() {
        // Go 的 make(StringArray, 0, ...) 保证空输入产出 []，绝不产出 nil。
        // 这个差别有外部契约后果：响应里的 capabilities 永远不是 null。
        assertThat(APIKeyCapability.normalizeAll(null)).isNotNull().isEmpty();
        assertThat(APIKeyCapability.normalizeAll(List.of("nope"))).isNotNull().isEmpty();
    }

    @Test
    void normalizeCapabilityMapsUnknownToNull() {
        assertThat(APIKeyCapability.normalize(" MANAGE_MODELS ")).isEqualTo("manage_models");
        assertThat(APIKeyCapability.normalize("bogus")).isNull();
        assertThat(APIKeyCapability.normalize(null)).isNull();
    }

    // ── APIKeyScopeType（对照 types/tenant_api_key.go L47-54） ──

    @Test
    void normalizeScopeType() {
        assertThat(APIKeyScopeType.normalize(" PLATFORM ")).isEqualTo("platform");
        assertThat(APIKeyScopeType.normalize("platform")).isEqualTo("platform");
        // 未知 / 空 / null 一律回落 tenant
        assertThat(APIKeyScopeType.normalize("weird")).isEqualTo("tenant");
        assertThat(APIKeyScopeType.normalize("")).isEqualTo("tenant");
        assertThat(APIKeyScopeType.normalize(null)).isEqualTo("tenant");
    }

    // ── KB 白名单判定（对照 types/tenant_api_key.go L320-355） ──

    @Test
    void allowsKnowledgeBaseSemantics() {
        TenantAPIKeyScope unrestricted = TenantAPIKeyScope.empty();
        // 空白 ID 恒 false —— 即便 Key 不限 KB
        assertThat(unrestricted.allowsKnowledgeBase("  ")).isFalse();
        assertThat(unrestricted.allowsKnowledgeBase(null)).isFalse();
        // 白名单为空 = 不限制
        assertThat(unrestricted.allowsKnowledgeBase("kb-1")).isTrue();

        TenantAPIKeyScope restricted = new TenantAPIKeyScope(
                0L, "tenant", false, List.of("kb-1", "kb-2"), null);
        assertThat(restricted.isKnowledgeBaseRestricted()).isTrue();
        assertThat(restricted.allowsKnowledgeBase("kb-1")).isTrue();
        assertThat(restricted.allowsKnowledgeBase("kb-3")).isFalse();

        // 受限 Key 传空清单 → false（"未指定"不等于"全放"）
        assertThat(restricted.allowsKnowledgeBases(List.of())).isFalse();
        assertThat(restricted.allowsKnowledgeBases(null)).isFalse();
        assertThat(restricted.allowsKnowledgeBases(List.of("kb-1", "kb-2"))).isTrue();
        assertThat(restricted.allowsKnowledgeBases(List.of("kb-1", "kb-9"))).isFalse();
        // 不受限 Key 传空清单 → true
        assertThat(unrestricted.allowsKnowledgeBases(List.of())).isTrue();
    }

    // ── 下游守卫（对照 tenant_api_key_scope_test.go L8-59） ──

    @Test
    void authorizeKnowledgeTargetsRejectsKnowledgeIds() {
        APIKeyScopeContext.set(new TenantAPIKeyScope(
                0L, "tenant", false, List.of("kb-1"), null));
        assertThatThrownBy(() -> TenantAPIKeyScope.authorizeKnowledgeTargets(
                List.of("kb-1"), List.of("doc-1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("knowledge_ids without a verified knowledge base");
    }

    @Test
    void authorizeKnowledgeTargetsAllowsUnspecifiedTargets() {
        APIKeyScopeContext.set(new TenantAPIKeyScope(
                0L, "tenant", false, List.of("kb-1"), null));
        // 都不指定 → 放行（null 与空列表等价）
        TenantAPIKeyScope.authorizeKnowledgeTargets(null, null);
        TenantAPIKeyScope.authorizeKnowledgeTargets(List.of(), List.of());
    }

    @Test
    void authorizeKnowledgeTargetsRejectsOutOfScopeKbs() {
        APIKeyScopeContext.set(new TenantAPIKeyScope(
                0L, "tenant", false, List.of("kb-1"), null));
        assertThatThrownBy(() -> TenantAPIKeyScope.authorizeKnowledgeTargets(List.of("kb-2"), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("one or more knowledge bases");
    }

    @Test
    void authorizeOptionalTagIdsRejectsTags() {
        APIKeyScopeContext.set(new TenantAPIKeyScope(
                0L, "tenant", false, List.of("kb-1"), null));
        assertThatThrownBy(() -> TenantAPIKeyScope.authorizeOptionalTagIds(List.of("tag-1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("tag_ids without a verified knowledge base");
    }

    @Test
    void guardsAreNoOpForNonApiKeyCallers() {
        // 没有 API Key 主体（JWT 会话）→ 三个守卫都是 no-op
        TenantAPIKeyScope.authorizeKnowledgeBases(List.of("kb-1"));
        TenantAPIKeyScope.authorizeKnowledgeTargets(List.of("kb-1"), List.of("doc-1"));
        TenantAPIKeyScope.authorizeOptionalTagIds(List.of("tag-1"));
    }

    @Test
    void guardsAreNoOpForUnrestrictedKeys() {
        APIKeyScopeContext.set(new TenantAPIKeyScope(0L, "tenant", true, null, null));
        TenantAPIKeyScope.authorizeKnowledgeBases(List.of("kb-1"));
        TenantAPIKeyScope.authorizeKnowledgeTargets(List.of("kb-1"), List.of("doc-1"));
        TenantAPIKeyScope.authorizeOptionalTagIds(List.of("tag-1"));
    }

    @Test
    void filterKnowledgeBasesIntersectsAgentDefaults() {
        APIKeyScopeContext.set(new TenantAPIKeyScope(
                0L, "tenant", false, List.of("kb-1", "kb-2"), null));
        // 未显式指定 kb_ids → 与白名单求交（静默过滤）
        List<String> got = TenantAPIKeyScope.filterKnowledgeBases(null, List.of("kb-2", "kb-3"));
        assertThat(got).containsExactly("kb-2");
    }

    @Test
    void filterKnowledgeBasesRejectsExplicitOutOfScope() {
        APIKeyScopeContext.set(new TenantAPIKeyScope(
                0L, "tenant", false, List.of("kb-1"), null));
        // 显式指定 → 越界即 403（不静默过滤）
        assertThatThrownBy(() -> TenantAPIKeyScope.filterKnowledgeBases(
                List.of("kb-1", "kb-2"), List.of("kb-1", "kb-2")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("one or more knowledge bases");
    }

    @Test
    void filterKnowledgeBasesPassthroughForUnrestrictedKey() {
        List<String> resolved = List.of("kb-2", "kb-3");
        assertThat(TenantAPIKeyScope.filterKnowledgeBases(null, resolved)).isSameAs(resolved);
    }

    // ── 派生方法不得泄漏成 JSON 属性（约定 §9 复发率最高的坑） ──

    @Test
    void derivedAccessorsAreNotSerialized() throws Exception {
        TenantAPIKey key = new TenantAPIKey();
        key.setId(7L);
        key.setTenantId(42L);
        key.setScopeType("tenant");
        key.setName("k");
        key.setKeyHash("deadbeef");
        key.setApiKey("sk-x");
        key.setFullAccess(false);
        key.setKnowledgeBaseIds(List.of("kb-1"));
        key.setCapabilities(List.of("retrieve"));

        // 契约实体：isPlatform()/tenantIdValue() 在 Go 里是**方法**，key_hash 的 tag 是 "-"
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(key);
        assertThat(json).doesNotContain("\"platform\"");
        assertThat(json).doesNotContain("\"tenantIdValue\"");
        assertThat(json).doesNotContain("deadbeef");
        assertThat(json).doesNotContain("key_hash");
        // Go 的 tag 是蛇形，必须逐字对上
        assertThat(json).contains("\"tenant_id\"");
        assertThat(json).contains("\"scope_type\"");
        assertThat(json).contains("\"full_access\"");
        assertThat(json).contains("\"knowledge_base_ids\"");
        assertThat(json).contains("\"api_key\"");

        // 严格映射器回读不炸（漏 @JsonIgnore 会让这里失败）
        TenantAPIKey back = new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .readValue(json, TenantAPIKey.class);
        assertThat(back.getTenantId()).isEqualTo(42L);
        assertThat(back.getKnowledgeBaseIds()).containsExactly("kb-1");
    }

    @Test
    void platformDetectionAndTenantIdValue() {
        TenantAPIKey platform = new TenantAPIKey();
        platform.setId(1L);
        platform.setScopeType("platform");
        assertThat(platform.isPlatform()).isTrue();
        assertThat(platform.tenantIdValue()).isZero();

        TenantAPIKey tenant = new TenantAPIKey();
        tenant.setId(2L);
        tenant.setScopeType(" PLATFORM ");
        tenant.setTenantId(42L);
        assertThat(tenant.isPlatform()).isTrue(); // 归一化后仍认 platform
        assertThat(tenant.tenantIdValue()).isEqualTo(42L);
    }

    // ── jsonb 字符串数组列的三态往返（APIKeyStringListTypeHandler） ──

    @Test
    void stringArrayHandlerPreservesNullEmptyAndValues() {
        // null → 字面量 "null"（Go 的 json.Marshal(nil StringArray)）；
        // 不是 SQL NULL —— 列是 NOT NULL，且响应里这一列会输出 null
        assertThat(APIKeyStringListTypeHandler.encode(null)).isEqualTo("null");
        assertThat(APIKeyStringListTypeHandler.encode(List.of())).isEqualTo("[]");
        assertThat(APIKeyStringListTypeHandler.encode(List.of("kb-1"))).isEqualTo("[\"kb-1\"]");

        // 读路径：null 字面量 → null；[] → 可变空列表；SQL NULL/空串 → null
        assertThat(APIKeyStringListTypeHandler.decode("null")).isNull();
        assertThat(APIKeyStringListTypeHandler.decode(null)).isNull();
        assertThat(APIKeyStringListTypeHandler.decode("")).isNull();
        assertThat(APIKeyStringListTypeHandler.decode("[]")).isNotNull().isEmpty();
        assertThat(APIKeyStringListTypeHandler.decode("[\"kb-1\",\"kb-2\"]"))
                .containsExactly("kb-1", "kb-2");

        // 解码结果必须可变（业务代码可能就地改）
        List<String> decoded = APIKeyStringListTypeHandler.decode("[]");
        decoded.add("kb-x");
        assertThat(decoded).containsExactly("kb-x");
    }
}
