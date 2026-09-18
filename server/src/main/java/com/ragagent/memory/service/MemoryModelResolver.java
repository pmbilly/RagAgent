package com.ragagent.memory.service;

import java.util.List;

import com.ragagent.llm.LlmChatClient;
import com.ragagent.model.domain.Model;

/**
 * memory 管线按模型 ID 取运行时模型实例的端口（对照 Go
 * {@code modelService.GetChatModel(ctx, id)} / {@code GetEmbeddingModel(ctx, id)} /
 * {@code ListModels(ctx)}）。
 *
 * <h2>为什么是端口</h2>
 * <p>与 {@code wiki.service.WikiModelResolver} 同一个理由：Java 阶段 2 的
 * {@code ModelService} 只实现了配置 CRUD，运行时工厂方法收在这里，
 * 将来若把工厂并回 {@code ModelService}，换掉实现 bean 即可。</p>
 *
 * <h2>失败语义（与 Go 一致）</h2>
 * <p>取模型失败**抛异常**（Go 返回 error）；调用方按各自语义降级：</p>
 * <ul>
 *   <li>{@code embedText} / {@code vectorSearch} 把异常当"没有向量"，
 *       回落到字面匹配——语义匹配是**增强**，不是硬依赖；</li>
 *   <li>{@code callExtractionModel} 把异常当失败并上抛（Go 的
 *       {@code get extraction model: %w}），因为抽取没有模型就寸步难行；</li>
 *   <li>{@code callConsolidationModel} / {@code adjudicateTopics} 把异常当
 *       "模型不可用"，只记日志并降级（Go 的 {@code unavailable} 分支）。</li>
 * </ul>
 */
public interface MemoryModelResolver {

    /** 对照 Go {@code modelService.GetChatModel(ctx, id)}。模型缺失/状态异常时抛异常。 */
    LlmChatClient getChatModel(String modelId);

    /** 对照 Go {@code modelService.GetEmbeddingModel(ctx, id)} + {@code Embedder.Embed}。 */
    float[] embed(String modelId, String text) throws Exception;

    /** 对照 Go {@code modelService.ListModels(ctx)}：抽取模型回落时用来挑工作区的问答模型。 */
    List<Model> listModels();
}
