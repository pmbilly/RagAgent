package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ragagent.sandbox.runtime.DisabledSandboxManager;
import com.ragagent.sandbox.runtime.SandboxManager;
import com.ragagent.sandbox.runtime.SessionBoundManager;
import com.ragagent.sandbox.service.TenantSandboxConfigService;
import com.ragagent.sandbox.service.TenantSandboxResolverService;
import com.ragagent.sandbox.service.TenantSkillService;
import com.ragagent.sandbox.service.UserEnvService;
import com.ragagent.session.service.SessionSandboxExecutionService.TurnLease;

/**
 * 回合租约的行为验收（对照 Go session.go holdSandboxTurn L939-993 的分支链）：
 * 空 sessionID / 解析失败 / 非 SessionBoundManager / begin 失败 → 空租约；
 * 成功 → begin 立即、end 延迟到 close，end 失败仅吞掉。
 */
class SessionSandboxTurnLeaseTest {

    private TenantSandboxResolverService resolver;
    private TenantSandboxConfigService configService;
    private SessionSandboxPinnerService pinner;
    private SessionSandboxExecutionService service;

    @BeforeEach
    void setUp() {
        resolver = mock(TenantSandboxResolverService.class);
        configService = mock(TenantSandboxConfigService.class);
        pinner = mock(SessionSandboxPinnerService.class);
        service = new SessionSandboxExecutionService(resolver, configService,
                mock(TenantSkillService.class), pinner, mock(UserEnvService.class));
    }

    private void stubWorkspacePolicyOk(long tenantId) {
        when(configService.workspaceScriptsDisabled(tenantId)).thenReturn(false);
    }

    @Test
    void blankSessionIdIsEmptyLease() {
        TurnLease lease = service.holdSandboxTurn(10002L, "  ", "cfg-1");
        assertThatCode(lease::close).doesNotThrowAnyException();
        verifyNoInteractions(resolver, configService);
    }

    @Test
    void emptyConfigIdResolvesDisabledManager() {
        // 空/全局默认配置 → DisabledSandboxManager（非 SessionBoundManager）→ 空租约
        stubWorkspacePolicyOk(10002L);
        TurnLease lease = service.holdSandboxTurn(10002L, "s1", "");
        assertThatCode(lease::close).doesNotThrowAnyException();
        verifyNoInteractions(resolver);
    }

    @Test
    void resolveFailureIsWarnedAndReturnsEmptyLease() {
        when(configService.workspaceScriptsDisabled(10002L)).thenReturn(false);
        when(resolver.resolve(10002L, "cfg-1")).thenThrow(new RuntimeException("endpoint down"));
        TurnLease lease = service.holdSandboxTurn(10002L, "s1", "cfg-1");
        assertThatCode(lease::close).doesNotThrowAnyException();
    }

    @Test
    void nonSessionBoundManagerReturnsEmptyLease() {
        stubWorkspacePolicyOk(10002L);
        when(resolver.resolve(10002L, "cfg-1")).thenReturn(new DisabledSandboxManager());
        TurnLease lease = service.holdSandboxTurn(10002L, "s1", "cfg-1");
        assertThatCode(lease::close).doesNotThrowAnyException();
    }

    @Test
    void successBeginsImmediatelyAndEndsOnClose() {
        long tenantId = 10002L;
        stubWorkspacePolicyOk(tenantId);
        SessionBoundManager bound = mock(SessionBoundManager.class);
        when(resolver.resolve(tenantId, "cfg-1")).thenReturn(bound);

        TurnLease lease = service.holdSandboxTurn(tenantId, "s1", "cfg-1");
        verify(bound).beginSessionTurn(tenantId, "s1");
        verify(bound, never()).endSessionTurn(tenantId, "s1");

        lease.close();
        verify(bound).endSessionTurn(tenantId, "s1");
    }

    @Test
    void beginFailureReturnsEmptyLeaseWithoutEnd() {
        long tenantId = 10002L;
        stubWorkspacePolicyOk(tenantId);
        SessionBoundManager bound = mock(SessionBoundManager.class);
        org.mockito.Mockito.doThrow(new RuntimeException("lease store down"))
                .when(bound).beginSessionTurn(tenantId, "s1");
        when(resolver.resolve(tenantId, "cfg-1")).thenReturn(bound);

        TurnLease lease = service.holdSandboxTurn(tenantId, "s1", "cfg-1");
        verify(bound).beginSessionTurn(tenantId, "s1");
        lease.close();
        verify(bound, never()).endSessionTurn(tenantId, "s1");
    }

    @Test
    void endFailureOnCloseIsSwallowed() {
        long tenantId = 10002L;
        stubWorkspacePolicyOk(tenantId);
        SessionBoundManager bound = mock(SessionBoundManager.class);
        org.mockito.Mockito.doThrow(new RuntimeException("lease store down"))
                .when(bound).endSessionTurn(tenantId, "s1");
        when(resolver.resolve(tenantId, "cfg-1")).thenReturn(bound);

        TurnLease lease = service.holdSandboxTurn(tenantId, "s1", "cfg-1");
        assertThatCode(lease::close).doesNotThrowAnyException();
        verify(bound).endSessionTurn(tenantId, "s1");
    }

    @Test
    void emptyLeaseCloseIsPureNoop() {
        TurnLease lease = TurnLease.empty();
        assertThatCode(lease::close).doesNotThrowAnyException();
    }

    @Test
    void missingWorkspaceContextYieldsEmptyLease() {
        // tenantId=0 + 具名配置 → resolveTenant 抛 "missing workspace context" → 空租约
        TurnLease lease = service.holdSandboxTurn(0L, "s1", "cfg-1");
        assertThatCode(lease::close).doesNotThrowAnyException();
        verifyNoInteractions(resolver);
    }

    @Test
    void sandboxManagerTypeOfDisabledIsNotSessionBound() {
        // 防御性契约：disabled 管理器不宣告会话能力（空租约分支的前提）
        SandboxManager mgr = new DisabledSandboxManager();
        assertThat(SessionBoundArtifactSource.fromSandboxManager(mgr, 10002L)).isNull();
    }
}
