/**
 * chat 管线的**纯逻辑辅助**：检索结果去重/去包含（{@link com.ragagent.chatpipeline.support.SearchSupport}）、
 * 查询分词与关键词抽取（{@link com.ragagent.chatpipeline.support.QueryTokenizer}）、
 * 图片信息聚合（{@link com.ragagent.chatpipeline.support.ImageInfoCollector}）、
 * 引用装配（{@link com.ragagent.chatpipeline.support.ReferencesSupport}）、
 * 记忆投影（{@link com.ragagent.chatpipeline.support.MemoryUsedMemories}）与匹配常量
 * （{@link com.ragagent.chatpipeline.support.MatchTypes}）。
 *
 * <p>无状态、无仓储依赖；插件（{@code chatpipeline.plugin}）与管线骨架（根包）都调用这里。</p>
 */
package com.ragagent.chatpipeline.support;
