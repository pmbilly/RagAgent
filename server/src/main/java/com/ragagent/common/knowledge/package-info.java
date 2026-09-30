/**
 * 知识库跨域**端口与载荷**（最底层，零域依赖）：消费方注入接口、知识域实现，
 * 依赖方向是"消费域 → 端口 ← 知识域"，避免上层直连知识域的表实体与仓储。
 *
 * <ul>
 *   <li>{@code KnowledgeBaseGateway} / {@code KnowledgeBaseFacts} — 知识库归属事实
 *       （audit 的归属守卫、auth 的跨租户校验）；</li>
 *   <li>{@code KnowledgeBaseSearchGateway} / {@code KnowledgeBaseSearchFacts} — 检索侧
 *       KB 配置（检索编排的分组、索引开关、嵌入模型一致性闸门）；</li>
 *   <li>{@code KnowledgeDocumentGateway} / {@code KnowledgeDocumentFacts} — 文档元数据
 *       （检索结果装配）；</li>
 *   <li>{@code ChunkSearchGateway} / {@code ChunkFacts} — chunk 检索事实
 *       （命中过滤与扩召回）；</li>
 *   <li>{@code KnowledgeBaseProvisioner} — 命令端口（auth 的聊天历史隐藏库开通）。</li>
 * </ul>
 *
 * <p>载荷只带消费方真正读取的字段；需要更多字段时先改载荷，别把实体漏出去。</p>
 */
package com.ragagent.common.knowledge;
