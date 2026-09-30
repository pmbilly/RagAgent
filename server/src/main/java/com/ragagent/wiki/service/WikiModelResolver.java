package com.ragagent.wiki.service;

import java.util.List;

import com.ragagent.llm.LlmChatClient;

/**
 * wiki 管线按模型 ID 取运行时模型实例的端口。
 *
 * <h2>为什么是端口</h2>
 * <p>本模块的 {@code ModelService} 只实现了配置 CRUD，运行时工厂
 * （{@code GetChatModel} / {@code GetEmbeddingModel}）由这里定义<b>窄端口</b>，
 * 并提供一个基于既有 {@code ChatConfig}/{@code LlmChatClients}/
 * {@code EmbedderClient} 的默认实现；将来若把工厂收进 {@code ModelService}，
 * 只需替换实现 bean。</p>
 *
 * <p><b>失败语义</b>：两个方法在模型缺失、状态异常或类型不符时
 * <b>抛异常</b>。调用方按各自语义降级：</p>
 * <ul>
 *   <li>ingest 批次在 chat 模型取不到时让整个任务失败并重试；</li>
 *   <li>目录规划在 embedding 模型取不到时只记 warn 并
 *       回落成"把全部目录喂给 planner"——目录相似度是<b>优化</b>，不是硬依赖。</li>
 * </ul>
 */
public interface WikiModelResolver {

    /**
     * 按模型 ID 构造可用的聊天客户端。
     *
     * @throws RuntimeException 模型不存在 / 状态异常 / 配置不支持
     */
    LlmChatClient getChatModel(String modelId);

    /**
     * 按模型 ID 构造 embedding 客户端。
     *
     * @throws RuntimeException 模型不存在 / 状态异常 / 不是 embedding 类型
     */
    WikiEmbeddingModel getEmbeddingModel(String modelId);

    /**
     * 批量嵌入。失败时抛异常。
     */
    interface WikiEmbeddingModel {
        List<float[]> batchEmbed(List<String> texts) throws Exception;
    }
}
