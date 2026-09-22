package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ragagent.sandbox.mapper.TenantSandboxConfigMapper;
import com.ragagent.sandbox.service.TenantSandboxConfigService;
import com.ragagent.session.mapper.SessionMapper;

/**
 * 会话删除三件套的 sandbox 拆除步（对照 Go session.go destroyBoundSandbox L670-718）：
 * 错误一律吞掉（不阻断删除）、Disabled 后端 no-op、teardown 不查工作区 kill switch
 * （policy=nil 语义）、空 pin 走 disabled 短路。
 */
class SessionSandboxTeardownTest {

    private SessionMapper sessionMapper;
    private TenantSandboxConfigService sandboxConfigService;
    private TenantSandboxConfigMapper sandboxConfigMapper;
    private SessionTerminalService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(SessionMapper.class);
        sandboxConfigService = mock(TenantSandboxConfigService.class);
        sandboxConfigMapper = mock(TenantSandboxConfigMapper.class);
        service = new SessionTerminalService(sessionMapper, sandboxConfigService,
                sandboxConfigMapper, null);
    }

    @Test
    void blankSessionIdIsNoop() {
        assertThatCode(() -> service.destroyBoundSandbox(10002L, "")).doesNotThrowAnyException();
        assertThatCode(() -> service.destroyBoundSandbox(10002L, null)).doesNotThrowAnyException();
        verify(sessionMapper, never()).selectSandboxConfigPin(any());
    }

    @Test
    void pinReadFailureIsSwallowed() {
        when(sessionMapper.selectSandboxConfigPin("sess-1"))
                .thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> service.destroyBoundSandbox(10002L, "sess-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void emptyPinShortCircuitsToDisabled() {
        // 空 pin → ② 无具名配置 = disabled → no-op，全程不碰配置面
        when(sessionMapper.selectSandboxConfigPin("sess-1")).thenReturn("  ");
        assertThatCode(() -> service.destroyBoundSandbox(10002L, "sess-1"))
                .doesNotThrowAnyException();
        verify(sandboxConfigService, never()).workspaceScriptsDisabled(org.mockito.ArgumentMatchers.anyLong());
        verify(sandboxConfigMapper, never()).getByID(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void killSwitchIsNotConsultedOnTeardown() {
        // policy=nil：kill switch 抛错也不得阻断/短路拆除流程
        when(sessionMapper.selectSandboxConfigPin("sess-1")).thenReturn("-");
        when(sandboxConfigService.workspaceScriptsDisabled(10002L))
                .thenThrow(new RuntimeException("policy store down"));
        assertThatCode(() -> service.destroyBoundSandbox(10002L, "sess-1"))
                .doesNotThrowAnyException();
        verify(sandboxConfigService, never()).workspaceScriptsDisabled(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void namedConfigResolveFailureIsSwallowed() {
        when(sessionMapper.selectSandboxConfigPin("sess-1")).thenReturn("cfg-1");
        when(sandboxConfigMapper.getByID(10002L, "cfg-1"))
                .thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> service.destroyBoundSandbox(10002L, "sess-1"))
                .doesNotThrowAnyException();
    }
}
