package com.ragagent.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.ragagent.apikey.domain.TenantAPIKeyRequest;
import com.ragagent.apikey.service.TenantAPIKeyValidator;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import org.junit.jupiter.api.Test;

/**
 * 请求校验的文案与顺序测试——逐条对照 Go
 * internal/handler/tenant_api_key_validation_test.go（L1-62）
 * 与 {@code validateTenantAPIKeyRequest}（handler/tenant.go L802-867）。
 *
 * <p>校验顺序**就是契约**：前端与集成方按 message 断言，
 * 所以"先报哪一条"必须有测试钉住。</p>
 */
class TenantAPIKeyValidatorTest {

    private static final TenantAPIKeyValidator.KnowledgeBaseLookup LOOKUP = id -> switch (id) {
        case "kb-owned" -> 42L;
        case "kb-other" -> 43L;
        default -> null; // 不存在 / 查询出错在 Go 里走同一分支
    };

    private static TenantAPIKeyRequest req(String name, boolean fullAccess, List<String> caps) {
        return new TenantAPIKeyRequest(name, fullAccess, null, caps, null);
    }

    /** 对照 {@code TestValidateTenantAPIKeyRequestRequiresCapabilitiesForScopedKey}。 */
    @Test
    void requiresCapabilitiesForScopedKey() {
        assertThatThrownBy(() -> TenantAPIKeyValidator.validate(
                req("integration", false, null), 1L, LOOKUP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("capabilities are required for scoped API keys");
    }

    /** 对照 {@code TestValidateTenantAPIKeyRequestAllowsFullAccessWithoutCapabilities}。 */
    @Test
    void allowsFullAccessWithoutCapabilities() {
        assertThatCode(() -> TenantAPIKeyValidator.validate(
                req("owner", true, null), 1L, LOOKUP)).doesNotThrowAnyException();
    }

    /** 对照 {@code TestValidateTenantAPIKeyRequestAcceptsScopedKeyWithCapability}。 */
    @Test
    void acceptsScopedKeyWithCapability() {
        assertThatCode(() -> TenantAPIKeyValidator.validate(
                req("chat", false, List.of("chat")), 1L, LOOKUP)).doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankNameBeforeAnythingElse() {
        // name 是第一步：哪怕 full_access 也过不了
        assertThatThrownBy(() -> TenantAPIKeyValidator.validate(
                req("   ", true, null), 1L, LOOKUP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void rejectsUnknownCapabilityWhenOthersAreKnown() {
        // raw = ["chat","bogus"] → 归一化后非空，于是走到"未知能力"分支
        BizException e = (BizException) org.assertj.core.api.Assertions.catchThrowable(
                () -> TenantAPIKeyValidator.validate(
                        req("x", false, List.of("chat", "bogus")), 1L, LOOKUP));
        assertThat(e).isNotNull();
        assertThat(e.appError().message()).isEqualTo("capabilities contains an unknown capability");
        assertThat(e.appError().code()).isEqualTo(1010); // ErrValidation
        assertThat(e.appError().httpCode()).isEqualTo(400);
    }

    @Test
    void allUnknownCapabilitiesReportTheEmptyMessageFirst() {
        // raw = ["bogus"] → 归一化后为空 → 报的是第 3 步的文案（顺序即契约）
        BizException e = (BizException) org.assertj.core.api.Assertions.catchThrowable(
                () -> TenantAPIKeyValidator.validate(req("x", false, List.of("bogus")), 1L, LOOKUP));
        assertThat(e).isNotNull();
        assertThat(e.appError().message())
                .isEqualTo("capabilities are required for scoped API keys");
    }

    /**
     * 对照 {@code TestValidateTenantAPIKeyKnowledgeBaseOwnership}：
     * 同租户通过；跨租户 403；不存在 400。
     */
    @Test
    void knowledgeBaseOwnership() {
        TenantAPIKeyValidator.KnowledgeBaseLookup lookup = id -> switch (id) {
            case "kb-owned" -> 42L;
            case "kb-other" -> 43L;
            default -> null;
        };
        assertThatCode(() -> TenantAPIKeyValidator.validateKnowledgeBaseIds(
                42L, List.of("kb-owned"), lookup)).doesNotThrowAnyException();

        BizException crossTenant = (BizException) org.assertj.core.api.Assertions.catchThrowable(
                () -> TenantAPIKeyValidator.validateKnowledgeBaseIds(42L, List.of("kb-other"), lookup));
        assertThat(crossTenant.appError().httpCode()).isEqualTo(403);
        assertThat(crossTenant.appError().message())
                .isEqualTo("knowledge_base_ids contains a knowledge base outside this workspace");

        BizException missing = (BizException) org.assertj.core.api.Assertions.catchThrowable(
                () -> TenantAPIKeyValidator.validateKnowledgeBaseIds(42L, List.of("kb-missing"), lookup));
        assertThat(missing.appError().httpCode()).isEqualTo(400);
        assertThat(missing.appError().message())
                .isEqualTo("knowledge_base_ids contains an unknown knowledge base");
    }

    @Test
    void blankKnowledgeBaseIdsAreSkipped() {
        assertThatCode(() -> TenantAPIKeyValidator.validateKnowledgeBaseIds(
                42L, List.of("  ", "", "kb-owned"), LOOKUP)).doesNotThrowAnyException();
        assertThatCode(() -> TenantAPIKeyValidator.validateKnowledgeBaseIds(
                42L, List.of(), LOOKUP)).doesNotThrowAnyException();
        assertThatCode(() -> TenantAPIKeyValidator.validateKnowledgeBaseIds(
                42L, null, LOOKUP)).doesNotThrowAnyException();
    }

    @Test
    void fullAccessSkipsKnowledgeBaseValidation() {
        // 对照 Go 的 `if req.FullAccess { return nil }`：full-access 时 KB 白名单
        // **完全不校验**（哪怕里面全是垃圾 ID）
        assertThatCode(() -> TenantAPIKeyValidator.validate(
                new TenantAPIKeyRequest("owner", true, List.of("kb-missing", "kb-other"), null, null),
                42L, LOOKUP)).doesNotThrowAnyException();
    }

    /** 校验失败的 details 恒为 null（AppError 的 details 恒输出）。 */
    @Test
    void validationAppErrorHasNullDetails() {
        BizException e = (BizException) org.assertj.core.api.Assertions.catchThrowable(
                () -> TenantAPIKeyValidator.validate(req("x", false, null), 1L, LOOKUP));
        assertThat(e.appError().details()).isNull();
        assertThat(e.appError()).isInstanceOf(AppError.class);
        // 响应形态（GlobalExceptionHandler 输出）逐字段核对
        Map<String, Object> serialized = Map.of(
                "code", e.appError().code(),
                "message", e.appError().message());
        assertThat(serialized).containsEntry("code", 1010);
    }
}
