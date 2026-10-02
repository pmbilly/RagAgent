package com.ragagent.agent.management.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * /agents/placeholders 的静态定义。
 * 纯常量面，键序固定 name/label/description；组键按字母序。
 */
@Component
public class AgentPlaceholders {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record P(String name, String label, String description) {}

    private static final P QUERY = new P("query", "用户问题", "用户当前的问题或查询内容");
    private static final P CONTEXTS = new P("contexts", "检索内容", "从知识库检索到的相关内容列表");
    private static final P CURRENT_TIME = new P("current_time", "当前时间",
            "当前日期（ISO 格式：2006-01-02）。只用日期、不用时钟，避免秒级变化打断 provider 前缀缓存。");
    private static final P CURRENT_WEEK = new P("current_week", "当前星期",
            "当前星期几（如：星期一、Monday）");
    private static final P CONVERSATION = new P("conversation", "历史对话",
            "格式化的历史对话内容，用于多轮对话改写");
    private static final P YESTERDAY = new P("yesterday", "昨天日期", "昨天的日期（格式：2006-01-02）");
    private static final P ANSWER = new P("answer", "助手回答", "助手的回答内容（用于对话历史格式化）");
    private static final P KNOWLEDGE_BASES = new P("knowledge_bases", "知识库列表",
            "自动格式化的知识库列表，包含名称、描述、文档数量等信息");
    private static final P WEB_SEARCH_STATUS = new P("web_search_status", "网络搜索状态",
            "网络搜索工具是否启用的状态（Enabled 或 Disabled）");
    private static final P LANGUAGE = new P("language", "用户语言",
            "用户界面的语言偏好，如 Chinese (Simplified)、English、Korean 等，用于控制 LLM 回答语言");

    public ObjectNode data() {
        // 键按字母序输出(键 = 前端字段面, camel);P 名 = 模板令牌(数据值, 一律 snake——由 AgentPlaceholdersTest 守住)
        ObjectNode data = MAPPER.createObjectNode();
        data.set("agentSystemPrompt", list(
                List.of(KNOWLEDGE_BASES, WEB_SEARCH_STATUS, CURRENT_TIME, LANGUAGE)));
        data.set("all", list(List.of(QUERY, CONTEXTS, CURRENT_TIME, CURRENT_WEEK, CONVERSATION,
                YESTERDAY, ANSWER, KNOWLEDGE_BASES, WEB_SEARCH_STATUS, LANGUAGE)));
        data.set("contextTemplate", list(
                List.of(QUERY, CONTEXTS, CURRENT_TIME, CURRENT_WEEK, LANGUAGE)));
        data.set("fallbackPrompt", list(List.of(QUERY, LANGUAGE)));
        data.set("rewritePrompt", list(
                List.of(QUERY, CONVERSATION, CURRENT_TIME, YESTERDAY, LANGUAGE)));
        data.set("rewriteSystemPrompt", list(
                List.of(QUERY, CONVERSATION, CURRENT_TIME, YESTERDAY, LANGUAGE)));
        data.set("systemPrompt", list(
                List.of(QUERY, CONTEXTS, CURRENT_TIME, CURRENT_WEEK, LANGUAGE)));
        return data;
    }

    private static ArrayNode list(List<P> ps) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (P p : ps) {
            ObjectNode n = arr.addObject();
            n.put("name", p.name());
            n.put("label", p.label());
            n.put("description", p.description());
        }
        return arr;
    }
}
