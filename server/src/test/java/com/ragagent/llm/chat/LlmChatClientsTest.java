package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ragagent.common.error.BizException;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatConfig;
import org.junit.jupiter.api.Test;

/**
 * 聊天实例工厂的分发契约（对照 Go chat.NewChat / NewRemoteChat
 * 与测试 TestNewRemoteChat_AnthropicProvider）。
 */
class LlmChatClientsTest {

    private static ChatConfig remote(String baseUrl, String provider) {
        ChatConfig c = new ChatConfig();
        c.setSource("remote");
        c.setBaseUrl(baseUrl);
        c.setModelName("test-model");
        c.setModelId("m1");
        c.setApiKey("sk-test");
        c.setProvider(provider);
        return c;
    }

    /** Anthropic provider → 走独立的 Messages 协议实现（而不是 OpenAI 兼容路径）。 */
    @Test
    void remoteChatWithAnthropicProviderUsesAnthropicClient() {
        LlmChatClient c = LlmChatClients.newRemoteChat(remote("https://api.anthropic.com/v1", "anthropic"));
        assertInstanceOf(AnthropicChat.class, c);
    }

    /** provider 为空时从 baseURL 探测（anthropic.com 子串）。 */
    @Test
    void remoteChatDetectsAnthropicFromBaseUrl() {
        LlmChatClient c = LlmChatClients.newRemoteChat(remote("https://api.anthropic.com/v1", ""));
        assertInstanceOf(AnthropicChat.class, c);
    }

    /** 其余 OpenAI 兼容厂商统一走 RemoteApiChat。 */
    @Test
    void remoteChatWithOtherProvidersUsesRemoteApiClient() {
        assertInstanceOf(RemoteApiChat.class,
                LlmChatClients.newRemoteChat(remote("https://api.deepseek.com/v1", "deepseek")));
        assertInstanceOf(RemoteApiChat.class,
                LlmChatClients.newRemoteChat(remote("https://api.openai.com/v1", "openai")));
    }

    /** 未知 source 报错（对照 Go "unsupported chat model source: %s"）。 */
    @Test
    void unsupportedSourceThrows() {
        ChatConfig c = new ChatConfig();
        c.setSource("aliyun");
        BizException e = assertThrows(BizException.class,
                () -> LlmChatClients.create(c, null, null));
        assertTrue(e.getMessage().contains("unsupported chat model source"),
                "错误消息应含 Go 原文: " + e.getMessage());
    }
}
