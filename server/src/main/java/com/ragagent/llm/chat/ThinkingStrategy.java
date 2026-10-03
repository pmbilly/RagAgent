package com.ragagent.llm.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.llm.domain.ChatOptions;

/**
 * 把 {@link ChatOptions#getThinking()} 转换成各厂商的 HTTP 字段。
 *
 * **实现说明**：本接口统一用 Jackson {@link ObjectNode} 构造请求体，
 * 传输层不再分流；但「是否注入了自定义 thinking 字段」仍有一个可观察
 * 后果——流终态事件是否携带 finish_reason 与该标记挂钩
 * （见 RemoteApiChat 终态事件注释），所以 apply 保留 boolean 返回：true = 已注入。
 * **必须保留的是"何时注入"的语义**（null 语义、alwaysSend、disableOnNonStream），
 * 那才是线上行为。
 */
public interface ThinkingStrategy {

    /**
     * 就地把 thinking 相关字段写进请求体。
     *
     * @param body     出站请求体（已含标准 OpenAI 字段）
     * @param opts     调用选项；opts 为 null 或 thinking 为 null 时多数策略不注入
     * @param isStream 是否流式（Qwen3 非流式拒绝 thinking）
     * @return 是否注入了字段
     */
    boolean apply(ObjectNode body, ChatOptions opts, boolean isStream);

    /** 策略名，用于"测试连接"页诊断展示。 */
    String name();
}
