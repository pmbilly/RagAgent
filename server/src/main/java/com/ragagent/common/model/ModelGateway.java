package com.ragagent.common.model;

/**
 * 模型的**只读端口**（跨域读取模型最小事实集）。
 *
 * <p>由 model 域实现（{@code ModelService}），消费方注入接口而非 {@code ModelService}/{@code Model}，
 * 依赖方向因此是"消费域 → 端口 ← model 域"。</p>
 */
public interface ModelGateway {

    /** 按 id 查模型事实；查不到返回 {@code null}（调用方保留自己的回落语义）。 */
    ModelFacts findFacts(String modelId);
}
