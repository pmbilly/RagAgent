package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * agent 模式分派谓词（对照 Go {@code types.CustomAgent.IsAgentMode}，
 * internal/types/custom_agent.go L553-556：{@code Config.AgentMode == AgentModeSmartReasoning}）。
 *
 * <p>2026-09-23 走查抓回：该谓词曾误写成 {@code == "agent"}（Go 侧无此取值），
 * 使所有真实 agent（前端/内置/IM 一律写 {@code smart-reasoning}）在 agent-chat
 * 被误判进 RAG 快答分支。本测试钉住取值域——只有 {@code smart-reasoning} 才算
 * agent 模式；{@code quick-answer}、空值、旧误值都不算。</p>
 */
class SessionKnowledgeQaServiceAgentModeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode cfg(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void smartReasoningIsAgentMode() {
        assertThat(SessionKnowledgeQaService.isAgentMode(cfg("{\"agent_mode\":\"smart-reasoning\"}")))
                .isTrue();
    }

    @Test
    void quickAnswerIsNotAgentMode() {
        assertThat(SessionKnowledgeQaService.isAgentMode(cfg("{\"agent_mode\":\"quick-answer\"}")))
                .isFalse();
    }

    @Test
    void legacyMistakenValueIsNotAgentMode() {
        assertThat(SessionKnowledgeQaService.isAgentMode(cfg("{\"agent_mode\":\"agent\"}")))
                .isFalse();
    }

    @Test
    void blankOrMissingModeIsNotAgentMode() {
        assertThat(SessionKnowledgeQaService.isAgentMode(cfg("{\"agent_mode\":\"\"}"))).isFalse();
        assertThat(SessionKnowledgeQaService.isAgentMode(cfg("{}"))).isFalse();
    }
}
