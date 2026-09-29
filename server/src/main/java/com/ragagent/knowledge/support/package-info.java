/**
 * 知识库的纯逻辑支撑：无状态、零依赖的算法与规则，不作为 Spring bean。
 * <ul>
 *   <li>{@code GraphChunkSelector} — 图抽取前的 chunk 选取；</li>
 *   <li>{@code QuestionBatchPlanner} — 生成问题的批次规划（与 {@code domain.QuestionBatchPayload}
 *       的字段一一对应）；</li>
 *   <li>{@code KnowledgeIndexContent} — 索引内容串拼装；</li>
 *   <li>{@code ParserEngineRules} — 按扩展名从租户配置解析解析器引擎（会话域
 *       {@code TemporaryDocument*} 亦复用）。</li>
 * </ul>
 * {@code service/} 则只放 Spring 服务（用例）与 {@code @Component}。
 */
package com.ragagent.knowledge.support;
