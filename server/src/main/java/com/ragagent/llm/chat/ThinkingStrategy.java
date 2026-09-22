package com.ragagent.llm.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.llm.domain.ChatOptions;

/**
 * 把 {@link ChatOptions#getThinking()} 翻译成各厂商的 HTTP 字段
 * （对照 Go chat.ThinkingStrategy，internal/models/chat/thinking.go:50-118）。
 *
 * **Java 侧简化（有意为之，已在约定文档登记）**：Go 的 Apply 返回
 * `(customBody, useRawHTTP)`——非 nil 的自定义 body 必须走裸 HTTP，因为
 * go-openai SDK 的 struct 带不了 `enable_thinking` 这类非标准顶层字段。
 * Java 侧统一用 Jackson {@link ObjectNode} 构造请求体，SDK 限制不存在，
 * 传输层因此不再分流；但「是否注入了自定义 thinking 字段」仍有一个可观察
 * 后果——Go 裸 HTTP 路径的流终态事件**不带** finish_reason，SDK 路径带
 * （见 RemoteApiChat 终态事件注释），所以 apply 保留 boolean 返回：
 * true = 已注入（Go 此时 useRawHTTP=true）。
 * **必须保留的是"何时注入"的语义**（nil 语义、alwaysSend、disableOnNonStream），
 * 那才是线上行为。
 */
public interface ThinkingStrategy {

    /**
     * 就地把 thinking 相关字段写进请求体。
     *
     * @param body     出站请求体（已含标准 OpenAI 字段）
     * @param opts     调用选项；opts 为 null 或 thinking 为 null 时多数策略不注入
     * @param isStream 是否流式（Qwen3 非流式拒绝 thinking）
     * @return 是否注入了字段（对照 Go Apply 的 useRawHTTP 返回值）
     */
    boolean apply(ObjectNode body, ChatOptions opts, boolean isStream);

    /** 策略名（对照 Go thinkingStrategyName），用于"测试连接"页诊断展示。 */
    String name();
}
