package com.ragagent.im.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agentm.domain.CustomAgentEntity;
import com.ragagent.agentm.service.AgentConfigJson;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ImFormat;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.Session;
import com.ragagent.session.service.QaSupport;

/**
 * QA 管线的共享底座：agent 模式判定、IM 请求构造（含 agent config 缺省补全）、
 * 用户/助手消息落库。runQA（同步）与流式管线共用。
 */
final class ImQaRequests {

    private final ImService service;

    ImQaRequests(ImService service) {
        this.service = service;
    }


    static boolean isAgentMode(CustomAgentEntity agent) {
        // 对照 CustomAgent.IsAgentMode：Config.AgentMode == "smart-reasoning"
        if (agent == null || agent.getConfig() == null || agent.getConfig().isEmpty()) {
            return false;
        }
        try {
            JsonNode cfg = ImService.JSON.readTree(agent.getConfig());
            return "smart-reasoning".equals(cfg.path("agent_mode").asText(""));
        } catch (Exception e) {
            return false;
        }
    }

    /** 对照 buildIMQARequest（service.go L528-558）。 */
    QaSupport.QaRequest buildIMQARequest(Session session, String query,
            String assistantMessageId, String userMessageId, CustomAgentEntity agent,
            IncomingMessage.QuotedMessage quote) {
        QaSupport.QaRequest req = new QaSupport.QaRequest();
        req.session = session;
        req.query = query;
        req.assistantMessageId = assistantMessageId;
        req.userMessageId = userMessageId;
        req.agentRow = agent;
        if (agent != null && agent.getConfig() != null && !agent.getConfig().isEmpty()) {
            try {
                req.agentConfig = (com.fasterxml.jackson.databind.node.ObjectNode)
                        ImService.JSON.readTree(agent.getConfig());
                AgentConfigJson.ensureDefaults(req.agentConfig);
            } catch (Exception ignored) {
                req.agentConfig = null;
            }
        }
        req.webSearchEnabled = agent != null && req.agentConfig != null
                && req.agentConfig.path("web_search_enabled").asBoolean(false);
        req.quotedContext = ImFormat.formatQuotedContext(quote);

        return req;
    }

    /** 对照 createIMUserMessagePayload（service.go L580-595）。 */
    Message createUserMessage(String sessionId, String content, String requestId) {
        Message m = new Message();
        m.setSessionId(sessionId);
        m.setRole("user");
        m.setContent(content);
        m.setRequestId(requestId);
        m.setCompleted(true);
        m.setChannel("im");
        return service.messageService.createMessage(m);
    }

    /** 对照 createIMAssistantMessagePayload（service.go L700-709）。 */
    Message createAssistantMessage(String sessionId, String requestId) {
        Message m = new Message();
        m.setSessionId(sessionId);
        m.setRole("assistant");
        m.setRequestId(requestId);
        m.setChannel("im");
        return service.messageService.createMessage(m);
    }
}
