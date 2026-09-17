package com.ragagent.wiki.service;

import java.util.List;

import com.ragagent.llm.LlmChatClient;

/**
 * wiki 管线按模型 ID 取运行时模型实例的端口（对照 Go
 * {@code modelService.GetChatModel(ctx, id)} / {@code modelService.GetEmbeddingModel(ctx, id)}）。
 *
 * <h2>为什么是端口</h2>
 * <p>Go 的 {@code wikiIngestService.modelService} 是完整的 {@code interfaces.ModelService}，
 * 含 {@code GetChatModel} / {@code GetEmbeddingModel} 两个运行时工厂方法。
 * Java 侧阶段 2 的 {@code ModelService} 只实现了配置 CRUD（其类注释明确写着
 * "GetChatModel 等运行时工厂随阶段 7 agent 引擎"），因此这里定义一个<b>窄端口</b>，
 * 由本模块提供一个基于既有 {@code ChatConfig}/{@code LlmChatClients}/
 * {@code EmbedderClient} 的默认实现，后续阶段若把工厂收进 {@code ModelService}，
 * 只需替换实现 bean。</p>
 *
 * <p><b>失败语义（与 Go 一致）</b>：两个方法在模型缺失、状态异常或类型不符时
 * <b>抛异常</b>（Go 返回 error）。调用方按各自语义降级：</p>
 * <ul>
 *   <li>{@code ProcessWikiIngest} 在 chat 模型取不到时让整个任务失败并重试；</li>
 *   <li>{@code selectRelevantFolders} 在 embedding 模型取不到时只记 warn 并
 *       回落成"把全部目录喂给 planner"——目录相似度是<b>优化</b>，不是硬依赖。</li>
 * </ul>
 */
public interface WikiModelResolver {

    /**
     * 对照 Go {@code modelService.GetChatModel(ctx, id)}：按模型 ID 构造可用的
     * 聊天客户端。
     *
     * @throws RuntimeException 模型不存在 / 状态异常 / 配置不支持
     */
    LlmChatClient getChatModel(String modelId);

    /**
     * 对照 Go {@code modelService.GetEmbeddingModel(ctx, id)}：按模型 ID 构造
     * embedding 客户端。
     *
     * @throws RuntimeException 模型不存在 / 状态异常 / 不是 embedding 类型
     */
    WikiEmbeddingModel getEmbeddingModel(String modelId);

    /**
     * 对照 Go {@code interfaces.Embedder} 的 {@code BatchEmbed(ctx, texts)}：
     * 批量嵌入。失败时抛异常（Go 返回 error）。
     */
    interface WikiEmbeddingModel {
        List<float[]> batchEmbed(List<String> texts) throws Exception;
    }
}
