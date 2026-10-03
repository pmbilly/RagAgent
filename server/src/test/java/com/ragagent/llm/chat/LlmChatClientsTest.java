package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ragagent.common.error.BizException;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 聊天实例工厂（{@code LlmChatClients#create} / {@code newRemoteChat}）的分发契约。
 *
 * 构造期会做 SSRF 校验，而本机 DNS 可能把公网域名解析到受限段
 * （实测 api.openai.com → Teredo 地址），故先把测试用域名加入白名单，
 * 让断言聚焦在**类型分发**而非网络环境。
 */
class LlmChatClientsTest {

    private static SsrfGuard previous;

    @BeforeAll
    static void allowTestHosts() {
        previous = new SsrfGuard();
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("api.openai.com,api.deepseek.com,api.anthropic.com");
        LlmTransport.setSsrfGuard(guard);
    }

    @AfterAll
    static void restoreGuard() {
        LlmTransport.setSsrfGuard(previous);
    }

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

    /** 未知 source 报错（消息含 "unsupported chat model source"）。 */
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
