package com.ragagent.session.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ragagent.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 会话查询范围标记测试（{@code SessionService.sessionUserIDForLookup} 与
 * {@code SessionLookupScope} 打标的联动）。
 *
 * <p>{@code KnowledgeQaController} 的 QA 线程与三条派生线程打标/清理；
 * 本测试锁住语义：未打标 → 按属主查；打标 → 租户范围（返回空 owner）。</p>
 */
class SessionLookupScopeTest {

    @AfterEach
    void tearDown() {
        SessionLookupScope.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("未打标：按会话属主（当前主体派生 id）查")
    void unmarkedUsesSessionOwner() {
        TenantContext.set(7L, TenantContext.webUserPrincipal("u-9"), "owner", false, "u-9", false);
        assertFalse(SessionLookupScope.isMarked());
        assertEquals("u-9", SessionService.sessionUserIDForLookup());
    }

    @Test
    @DisplayName("打标：返回空 owner（租户范围查询）——共享 agent 场景的关键分支")
    void markedReturnsEmptyOwner() {
        TenantContext.set(7L, TenantContext.webUserPrincipal("u-9"), "owner", false, "u-9", false);
        SessionLookupScope.mark();
        assertTrue(SessionLookupScope.isMarked());
        assertEquals("", SessionService.sessionUserIDForLookup());
    }

    @Test
    @DisplayName("清理后恢复属主范围（线程池复用不得残留标记）")
    void clearRestoresOwnerScope() {
        TenantContext.set(7L, TenantContext.webUserPrincipal("u-9"), "owner", false, "u-9", false);
        SessionLookupScope.mark();
        SessionLookupScope.clear();
        assertFalse(SessionLookupScope.isMarked());
        assertEquals("u-9", SessionService.sessionUserIDForLookup());
    }
}
