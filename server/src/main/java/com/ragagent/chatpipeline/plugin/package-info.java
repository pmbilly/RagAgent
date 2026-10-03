/**
 * chat 管线的**插件实现**：{@link com.ragagent.chatpipeline.Plugin} 接口 + 18 个步骤实现
 * （query_understand → search → merge → rerank → into_chat_message → chat_completion 等）。
 *
 * <p>装配在 {@link com.ragagent.chatpipeline.PipelineBuilder}（根包），
 * 跨域 seam 在 {@link com.ragagent.chatpipeline.PipelinePorts}（根包）；
 * 纯逻辑辅助在 {@link com.ragagent.chatpipeline.support}（同域子包）。
 * 部分静态/实例辅助方法放宽为 {@code public}：测试在 {@code com.ragagent.chatpipeline}
 * 根包直接探针，跨子包需可见。</p>
 */
package com.ragagent.chatpipeline.plugin;
