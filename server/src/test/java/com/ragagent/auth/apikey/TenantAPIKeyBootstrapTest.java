package com.ragagent.auth.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.ragagent.auth.apikey.filter.APIKeyAuthChannel;
import com.ragagent.auth.apikey.service.TenantAPIKeyBootstrap;
import org.junit.jupiter.api.Test;

/**
 * 建租户时自动发 Key / 响应嵌 Key 的支持函数测试——对照 Go
 * internal/handler/tenant.go 的 {@code autoCreateTenantAPIKey}（L513-526）与
 * {@code tenantWithAPIKey}（L496-511），以及 {@code isPlatformTenantOptionalAPI}。
 */
class TenantAPIKeyBootstrapTest {

    // ── autoCreateTenantAPIKey 的 3 层解析（DB > env > false） ──

    @Test
    void autoCreateDefaultsToFalse() {
        // 测试环境没有 WEKNORA_TENANT_AUTO_CREATE_API_KEY → false（现代部署默认关闭）
        assertThat(TenantAPIKeyBootstrap.resolveAutoCreateApiKey(null)).isFalse();
    }

    @Test
    void autoCreatePrefersSystemSettingOverEnv() {
        // DB 行存在时优先于 env（env 在测试里为空，这里验的是"DB 值直接生效"）
        assertThat(TenantAPIKeyBootstrap.resolveAutoCreateApiKey(true)).isTrue();
        assertThat(TenantAPIKeyBootstrap.resolveAutoCreateApiKey(false)).isFalse();
    }

    @Test
    void defaultKeyNameMatchesGo() {
        assertThat(TenantAPIKeyBootstrap.DEFAULT_KEY_NAME).isEqualTo("default");
        assertThat(TenantAPIKeyBootstrap.AUTO_CREATE_ENV).isEqualTo("WEKNORA_TENANT_AUTO_CREATE_API_KEY");
    }

    // ── tenantWithAPIKey（唯一的"响应里带明文 Key"时机） ──

    @Test
    void tenantWithApiKeyEmbedsTokenAndSortsKeysAlphabetically() {
        Map<String, Object> tenant = Map.of(
                "id", 42,
                "name", "workspace",
                "status", "active",
                "nested", Map.of("z_key", 1, "a_key", 2));

        Map<String, Object> merged = TenantAPIKeyBootstrap.tenantWithApiKey(tenant, "sk-once");

        assertThat(merged).containsEntry("apiKey", "sk-once");
        // Go 的实现走 map（Marshal → map → 加键），encoding/json 对 map 按字母序输出，
        // 且**递归**对嵌套 map 生效
        assertThat(merged.keySet()).containsExactly("apiKey", "id", "name", "nested", "status");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) merged.get("nested");
        assertThat(nested.keySet()).containsExactly("a_key", "z_key");
    }

    @Test
    void tenantWithApiKeyHandlesNestedLists() {
        Map<String, Object> tenant = Map.of(
                "id", 1,
                "engines", List.of(Map.of("z", 1, "a", 2)));
        Map<String, Object> merged = TenantAPIKeyBootstrap.tenantWithApiKey(tenant, "sk-x");
        assertThat(merged.keySet()).containsExactly("apiKey", "engines", "id");
        assertThat(merged).containsEntry("apiKey", "sk-x");
    }

    // ── isPlatformTenantOptionalAPI（平台 Key 不带 X-Tenant-ID 时的放行清单） ──

    @Test
    void platformTenantOptionalApiExactAdminPrefixOnly() {
        assertThat(APIKeyAuthChannel.isPlatformTenantOptionalApi("/api/v1/system/admin", "GET")).isTrue();
        assertThat(APIKeyAuthChannel.isPlatformTenantOptionalApi("/api/v1/system/admin/settings", "GET")).isTrue();
        assertThat(APIKeyAuthChannel.isPlatformTenantOptionalApi("/api/v1/system/admin/", "GET")).isTrue();
        // 裸 HasPrefix 会误放行同前缀路径——Go 特意用精确边界，这里钉住
        assertThat(APIKeyAuthChannel.isPlatformTenantOptionalApi("/api/v1/system/admin-foo", "GET")).isFalse();
        assertThat(APIKeyAuthChannel.isPlatformTenantOptionalApi("/api/v1/system/settings", "GET")).isFalse();
    }

    @Test
    void platformTenantOptionalApiTenantCatalogRules() {
        assertThat(APIKeyAuthChannel.isPlatformTenantOptionalApi("/api/v1/tenants", "POST")).isTrue();
        // 方法不匹配 / 其它租户路由都不放行
        assertThat(APIKeyAuthChannel.isPlatformTenantOptionalApi("/api/v1/tenants/42", "GET")).isFalse();
        assertThat(APIKeyAuthChannel.isPlatformTenantOptionalApi("/api/v1/tenants/42/api-keys", "GET")).isFalse();
        assertThat(APIKeyAuthChannel.isPlatformTenantOptionalApi(null, "GET")).isFalse();
    }
}
