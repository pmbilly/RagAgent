package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@code SessionQaResolution.buildSearchTargets} 的契约测试（§11.37 第 1 步：动刀前先铺网）。
 *
 * <p>只钉**不变量**与空入参行为——方法 673 行、内部深嵌套，先锁住"外部可观察的最低保证"，
 * 再在后续步骤里按 4 个分支面逐步加密断言。上游链路已由
 * {@code SessionKnowledgeQaKbScopeTest} / {@code SessionKnowledgeQaServiceAgentModeTest} 覆盖。</p>
 */
class SessionQaResolutionSearchTargetsTest {

    private SessionQaResolution newResolution() {
        return new SessionQaResolution(mock(SessionKnowledgeQaService.class));
    }

    @Test
    void emptyInputsReturnEmptyList() {
        SessionQaResolution r = newResolution();
        List<SessionKnowledgeQaService.SearchTargetView> targets =
                r.buildSearchTargets(1L, List.of(), List.of(), List.of());
        assertThat(targets).isNotNull().isEmpty();
    }

    @Test
    void knowledgeIdsWithoutKbsStillReturnsList() {
        SessionQaResolution r = newResolution();
        List<SessionKnowledgeQaService.SearchTargetView> targets =
                r.buildSearchTargets(1L, List.of(), List.of("k1", "k2"), List.of());
        assertThat(targets).isNotNull();
    }

    @Test
    void unknownKbIdDoesNotThrow() {
        SessionQaResolution r = newResolution();
        assertThatCode(() -> r.buildSearchTargets(1L, List.of("no-such-kb"), List.of(), List.of()))
                .doesNotThrowAnyException();
    }
}
