package com.ragagent.common.knowledge;

/**
 * 知识库的**命令端口**（跨域"由知识域建行"的最小接口，与只读端口 {@link KnowledgeBaseGateway} 分列）。
 *
 * <p>为什么需要它：{@code auth} 的聊天历史配置端点（{@code PUT /tenant/config/chat-history}）在
 * "enabled + 有 embedding 模型 + 还没有 KB"时要**自动建一个隐藏知识库**并把 id 记进租户配置。
 * 此前 auth 自己 {@code new KnowledgeBase()} 填名字/类型/临时标记/描述——那是知识域的实体语义，
 * 注册域替知识域保管规则（{@code auth ⇄ knowledge} 环的另一半）。</p>
 *
 * <p>端口只传调用方真正提供的输入（embedding 模型 id），只回调用方真正消费的输出（新建行的 id）。</p>
 */
public interface KnowledgeBaseProvisioner {

    /**
     * 建"聊天历史"隐藏知识库（{@code __chat_history__}，document 型、临时、自动托管），返回其 id。
     *
     * <p>enabled + 有模型 + 无 KB → 自动建。名字/类型/描述等**语义归知识域**，
     * 调用方不感知实体形态。</p>
     */
    String provisionChatHistoryKnowledgeBase(String embeddingModelId);
}
