package com.ragagent.memory.service;

import java.util.List;

import com.ragagent.llm.LlmChatClient;
import com.ragagent.model.domain.Model;

/**
 * memory 管线按模型 ID 取运行时模型实例的端口。
 *
 * <h2>为什么是端口</h2>
 * <p>与 {@code wiki.service.WikiModelResolver} 同一个理由：{@code ModelService}
 * 只实现了配置 CRUD，运行时工厂方法收在这里，
 * 将来若把工厂并回 {@code ModelService}，换掉实现 bean 即可。</p>
 *
 * <h2>失败语义</h2>
 * <p>取模型失败**抛异常**；调用方按各自语义降级：</p>
 * <ul>
 *   <li>{@code embedText} / {@code vectorSearch} 把异常当"没有向量"，
 *       回落到字面匹配——语义匹配是**增强**，不是硬依赖；</li>
 *   <li>{@code callExtractionModel} 把异常当失败并上抛，
 *       因为抽取没有模型就寸步难行；</li>
 *   <li>{@code callConsolidationModel} / {@code adjudicateTopics} 把异常当
 *       "模型不可用"，只记日志并降级。</li>
 * </ul>
 */
public interface MemoryModelResolver {

    /** 模型缺失/状态异常时抛异常。 */
    LlmChatClient getChatModel(String modelId);

    /** 取嵌入模型并对文本嵌入。 */
    float[] embed(String modelId, String text) throws Exception;

    /** 抽取模型回落时用来挑工作区的问答模型。 */
    List<Model> listModels();
}
