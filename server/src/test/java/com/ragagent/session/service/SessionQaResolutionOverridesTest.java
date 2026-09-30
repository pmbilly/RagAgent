package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.chatpipeline.ChatManage;
import org.junit.jupiter.api.Test;

/**
 * {@code SessionQaResolution.applyAgentOverridesToChatManage} 的契约测试
 * （§11.37 第 1 步：动刀前先铺网）。
 *
 * <p>本类是 413 行的"平铺 20 个 if"，先用最小契约钉住两件事：① {@code agentConfig == null} 必须安全早返回；
 * ② 给了 agentConfig（含 system_prompt）也不能抛异常。更强的逐键断言（temperature / topK / prompts 落点）
 * 留到第 2 步抽 applier 时逐步加密。</p>
 */
class SessionQaResolutionOverridesTest {

    private SessionQaResolution newResolution() {
        return new SessionQaResolution(mock(SessionKnowledgeQaService.class));
    }

    @Test
    void nullAgentConfigIsSafeNoOp() {
        SessionQaResolution r = newResolution();
        QaSupport.QaRequest req = new QaSupport.QaRequest();
        req.agentConfig = null;
        ChatManage cm = new ChatManage();
        assertThatCode(() -> r.applyAgentOverridesToChatManage(req, cm)).doesNotThrowAnyException();
    }

    @Test
    void agentConfigWithSystemPromptDoesNotThrow() {
        SessionQaResolution r = newResolution();
        QaSupport.QaRequest req = new QaSupport.QaRequest();
        ObjectNode cfg = new ObjectMapper().createObjectNode();
        cfg.put("system_prompt", "S");
        req.agentConfig = cfg;
        ChatManage cm = new ChatManage();
        assertThatCode(() -> r.applyAgentOverridesToChatManage(req, cm)).doesNotThrowAnyException();
    }
}
