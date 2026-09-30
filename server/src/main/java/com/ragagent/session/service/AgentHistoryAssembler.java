package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.agent.domain.AgentStep;

import com.ragagent.common.prompt.MessageAttachmentsPrompt;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ToolCall;
import com.ragagent.session.domain.Message;
import com.ragagent.session.support.PipelineViews;

/**
 * {@code SessionAgentQaService} 的**历史/消息装配簇**（§14.9c 刀 1）：把落库的 {@code Message}
 * 行重建为引擎要的 {@code ChatMessage} 历史（轮次分组、steer 注入、agent step 回放、终答兜底、
 * 非终态工具调用过滤）。
 *
 * <p>为什么单独一类：这一簇是纯装配（输入是消息行、输出是聊天历史），与引擎创建、工具注册、
 * 配置构造三簇无交集；门面 {@code agentQA} 改调本类。共享项：{@code stringListOf} 归门面的
 * 配置簇使用，本簇不引用。</p>
 */
final class AgentHistoryAssembler {

    private final MessageService messageService;

    AgentHistoryAssembler(MessageService messageService) {
        this.messageService = messageService;
    }

    List<ChatMessage> loadAgentHistory(String sessionId, int maxRounds) {
        if (maxRounds <= 0) {
            return new ArrayList<>();
        }
        int fetchLimit = Math.max(maxRounds * SessionAgentQaService.AGENT_HISTORY_FETCH_MULTIPLIER, SessionAgentQaService.AGENT_HISTORY_FETCH_MIN);
        List<Message> rows = messageService.getRecentMessages(sessionId, fetchLimit);
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        // 按 requestID 分轮
        Map<String, Turn> turns = new LinkedHashMap<>();
        for (Message msg : rows) {
            Turn t = turns.computeIfAbsent(msg.getRequestId(), k -> new Turn());
            if ("user".equals(msg.getRole())) {
                t.users.add(msg);
                if (t.createdAt == null || msg.getCreatedAt().isBefore(t.createdAt)) {
                    t.createdAt = msg.getCreatedAt();
                }
            } else if ("assistant".equals(msg.getRole())) {
                t.assistant = msg;
            }
        }
        List<Turn> completeTurns = new ArrayList<>();
        for (Turn t : turns.values()) {
            if (!t.users.isEmpty() && t.assistant != null && t.assistant.isCompleted()) {
                t.users.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
                completeTurns.add(t);
            }
        }
        completeTurns.sort((a, b) -> a.createdAt.compareTo(b.createdAt));
        if (completeTurns.size() > maxRounds) {
            completeTurns = new ArrayList<>(completeTurns.subList(completeTurns.size() - maxRounds,
                    completeTurns.size()));
        }
        List<ChatMessage> out = new ArrayList<>();
        for (Turn t : completeTurns) {
            out.add(buildUserHistoryMessage(t.users.get(0)));
            out.addAll(buildTurnBodyMessages(t.assistant, t.users.subList(1, t.users.size())));
        }
        return out;
    }
    private static final class Turn {
        final List<Message> users = new ArrayList<>();
        Message assistant;
        java.time.OffsetDateTime createdAt;
    }
    /** buildTurnBodyMessages（agent_history.go L157-186）。 */
    private static List<ChatMessage> buildTurnBodyMessages(Message assistant, List<Message> midRunUsers) {
        if (midRunUsers.isEmpty()) {
            return buildAssistantHistoryMessages(assistant);
        }
        List<ChatMessage> out = new ArrayList<>();
        Map<String, Message> usersById = new LinkedHashMap<>();
        for (Message user : midRunUsers) {
            usersById.put(user.getId(), user);
        }
        boolean hasBoundaries = false;
        if (assistant.getAgentSteps() != null) {
            for (AgentStep step : assistant.getAgentSteps()) {
                hasBoundaries = hasBoundaries || (step.getUserMessagesBefore() != null
                        && !step.getUserMessagesBefore().isEmpty());
            }
        }
        List<Message> pending = new ArrayList<>(midRunUsers);
        final int[] next = {0};
        if (assistant.getAgentSteps() != null) {
            for (AgentStep step : assistant.getAgentSteps()) {
                if (step.getUserMessagesBefore() != null) {
                    for (String id : step.getUserMessagesBefore()) {
                        Message user = usersById.get(id);
                        if (user != null) {
                            out.add(steeredUserMessage(user));
                            usersById.remove(user.getId());
                        }
                    }
                }
                while (!hasBoundaries && next[0] < pending.size()
                        && step.getTimestamp() != null
                        && pending.get(next[0]).getCreatedAt().isBefore(step.getTimestamp())) {
                    Message user = pending.get(next[0]);
                    out.add(steeredUserMessage(user));
                    usersById.remove(user.getId());
                    next[0]++;
                }
                out.addAll(buildAgentStepMessages(step));
            }
        }
        for (Message user : midRunUsers) {
            if (usersById.containsKey(user.getId())) {
                out.add(steeredUserMessage(user));
                usersById.remove(user.getId());
            }
        }
        ChatMessage finalMsg = finalAnswerHistoryMessage(assistant);
        if (finalMsg != null) {
            out.add(finalMsg);
        }
        return out;
    }
    private static ChatMessage steeredUserMessage(Message user) {
        ChatMessage msg = buildUserHistoryMessage(user);
        msg.setContent(steerMessageContent(msg.getContent()));
        return msg;
    }
    /** buildUserHistoryMessage（agent_history.go L187-196）。 */
    private static ChatMessage buildUserHistoryMessage(Message m) {
        String content = m.getContent();
        if (m.getImages() != null) {
            StringBuilder captions = new StringBuilder();
            for (var img : m.getImages()) {
                if (img != null && img.getCaption() != null && !img.getCaption().isEmpty()) {
                    if (captions.length() > 0) {
                        captions.append('\n');
                    }
                    captions.append(img.getCaption());
                }
            }
            if (captions.length() > 0) {
                content += "\n\n[用户上传图片内容]\n" + captions;
            }
        }
        if (m.getAttachments() != null && !m.getAttachments().isEmpty()) {
            content += MessageAttachmentsPrompt.build(
                    PipelineViews.ofAttachments(m.getAttachments()));
        }
        ChatMessage msg = new ChatMessage();
        msg.setRole("user");
        msg.setContent(content);
        return msg;
    }
    /** buildAssistantHistoryMessages（agent_history.go L204-218）。 */
    private static List<ChatMessage> buildAssistantHistoryMessages(Message m) {
        List<ChatMessage> msgs = new ArrayList<>();
        if (m.getAgentSteps() != null) {
            for (AgentStep step : m.getAgentSteps()) {
                msgs.addAll(buildAgentStepMessages(step));
            }
        }
        ChatMessage finalMsg = finalAnswerHistoryMessage(m);
        if (finalMsg != null) {
            msgs.add(finalMsg);
        }
        return msgs;
    }
    /** buildAgentStepMessages（agent_history.go L225-266）。 */
    private static List<ChatMessage> buildAgentStepMessages(AgentStep step) {
        List<com.ragagent.agent.domain.ToolCall> nonTerminalCalls = filterNonTerminalToolCalls(step.getToolCalls());
        if (nonTerminalCalls.isEmpty()) {
            if (step.isIntermediateAnswer() && step.getThought() != null && !step.getThought().isBlank()) {
                ChatMessage msg = new ChatMessage();
                msg.setRole("assistant");
                msg.setContent(step.getThought());
                msg.setReasoningContent(step.getReasoningContent());
                return new ArrayList<>(List.of(msg));
            }
            return new ArrayList<>();
        }
        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(step.getThought());
        assistantMsg.setReasoningContent(step.getReasoningContent());
        List<ToolCall> chatCalls = new ArrayList<>();
        for (var tc : nonTerminalCalls) {
            ToolCall c = new ToolCall();
            c.setId(tc.getId());
            c.setType("function");
            c.setFunction(new com.ragagent.llm.domain.FunctionCall(
                    tc.getName(), toJsonString(tc.getArgs())));
            c.setProviderMetadata(tc.getProviderMetadata());
            chatCalls.add(c);
        }
        assistantMsg.setToolCalls(chatCalls);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(assistantMsg);
        for (var tc : nonTerminalCalls) {
            ChatMessage toolMsg = new ChatMessage();
            toolMsg.setRole("tool");
            toolMsg.setContent(com.ragagent.agent.tools.ToolResultPersist.compactToolOutputForHistory(
                    tc.getName(), tc.getResult()));
            toolMsg.setToolCallId(tc.getId());
            toolMsg.setName(tc.getName());
            msgs.add(toolMsg);
        }
        return msgs;
    }
    private static String toJsonString(Object args) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    args == null ? Map.of() : args);
        } catch (Exception e) {
            return "{}";
        }
    }
    /** finalAnswerHistoryMessage（agent_history.go L272-289）。 */
    private static ChatMessage finalAnswerHistoryMessage(Message m) {
        String content = m.getContent() == null ? "" : m.getContent()
                .replaceAll("(?s)<think>.*?</think>", "");
        content = content.replace("\n\n本轮生成的文件: ![", "\n\n该历史消息生成的文件: ![")
                .replace("\n\nFile generated this turn: ![", "\n\nFile generated in that historical turn: ![");
        content = content.trim();
        if (content.isEmpty()) {
            return null;
        }
        ChatMessage msg = new ChatMessage();
        msg.setRole("assistant");
        msg.setContent(content);
        return msg;
    }
    /** filterNonTerminalToolCalls（agent_history.go L301-313）。 */
    private static List<com.ragagent.agent.domain.ToolCall> filterNonTerminalToolCalls(
            List<com.ragagent.agent.domain.ToolCall> calls) {
        List<com.ragagent.agent.domain.ToolCall> out = new ArrayList<>();
        if (calls == null) {
            return out;
        }
        for (var tc : calls) {
            if ("final_answer".equals(tc.getName()) || isPipelineToolCallId(tc.getId())) {
                continue;
            }
            out.add(tc);
        }
        return out;
    }
    /** types.IsPipelineToolCallID（pipeline 阶段工具调用前缀）。 */
    private static boolean isPipelineToolCallId(String id) {
        return id != null && (id.startsWith("attachment_parsing") || id.startsWith("web_search")
                || id.startsWith("knowledge_search") || id.startsWith("image_analysis")
                || id.startsWith("references"));
    }
    /** 只给模型输入加投递上下文（types.SteerMessageContent；引擎包内静态的同款实现）。 */
    private static String steerMessageContent(String content) {
        return "<steer_message>\n" + content + "\n</steer_message>\n<continue_task>\n"
                + "This is guidance for the task in progress. Apply it and continue unfinished work "
                + "unless the user explicitly changes or cancels the task.\n</continue_task>";
    }
}
