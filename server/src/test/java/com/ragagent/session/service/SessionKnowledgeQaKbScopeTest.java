package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * KB 检索范围的可读性判定（对照 Go {@code access.KBPermissions.Check}，context.go:79-94，
 * required=OrgRoleViewer；调用点 {@code SessionKnowledgeQaService.buildSearchTargets} 的 resolveKBTenant）。
 *
 * <p>2026-09-25 E2E 抓回：旧实现按"KB 行存在即归其租户"处理、**不做可读性过滤**，
 * 而 Go 在 {@code permissions.Check} 不过时把该 KB **丢弃**（不进检索范围）。分歧**用户可见**：
 * 用**外租户 KB** 检索时，Go `search_targets=0` → `search_nothing` → 降级作答；
 * 本仓却真去搜它 ⇒ 一旦该 KB 绑定的 store 已失效，就是 2200 硬错中止整个回合
 * （双端 A/B 已复现，修后一致；见 known-issues/09 §7.6）。</p>
 */
class SessionKnowledgeQaKbScopeTest {

    @Test
    void ownTenantIsReadable() {
        assertThat(SessionKnowledgeQaService.kbReadableByCaller(10002L, 10002L, () -> false))
                .as("自有租户（Go：caller.TenantID == ownerTenantID && required == Viewer ⇒ true）")
                .isTrue();
    }

    @Test
    void foreignTenantWithoutShareIsNotReadable() {
        assertThat(SessionKnowledgeQaService.kbReadableByCaller(10002L, 10004L, () -> false))
                .as("外租户且无共享 ⇒ 不可读（该 KB 须被丢弃，不进检索范围）")
                .isFalse();
    }

    @Test
    void foreignTenantWithSharedViewerIsReadable() {
        assertThat(SessionKnowledgeQaService.kbReadableByCaller(10002L, 10004L, () -> true))
                .as("外租户但组织共享 ≥ viewer ⇒ 可读（Go：p.shares.Check(kbID, required)）")
                .isTrue();
    }

    @Test
    void missingCallerOrOwnerIsNotReadable() {
        assertThat(SessionKnowledgeQaService.kbReadableByCaller(null, 10002L, () -> true)).isFalse();
        assertThat(SessionKnowledgeQaService.kbReadableByCaller(0L, 10002L, () -> true)).isFalse();
        assertThat(SessionKnowledgeQaService.kbReadableByCaller(10002L, 0L, () -> true)).isFalse();
    }

    @Test
    void nullShareProbeIsNotReadable() {
        assertThat(SessionKnowledgeQaService.kbReadableByCaller(10002L, 10004L, null))
                .as("共享服务不可用（未装配）时按不可读处理——更严不泄漏")
                .isFalse();
    }
}
