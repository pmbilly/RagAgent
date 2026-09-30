package com.ragagent.common.knowledge;

/**
 * 知识库的「归属事实」——跨域只读端口的载荷（存在性 + 归属租户 + 创建者）。
 *
 * <p>为什么需要它：{@code audit}（审计日志按知识库过滤时的归属守卫）与 {@code auth}（API Key 的
 * 跨租户校验）都要"按 id 查未软删的知识库"，并且都要区分「查不到」与「存在但属于别的空间」。
 * 若直接注入 {@code KnowledgeBaseMapper} + {@code KnowledgeBase} 实体，两个消费域就会反向依赖
 * 知识域（{@code audit ⇄ knowledge}、{@code auth ⇄ knowledge} 环）。</p>
 *
 * <p>只带调用方真正读取的字段；需要更多字段时**先改这里**，别把实体漏出去。</p>
 */
public record KnowledgeBaseFacts(Long tenantId, String creatorId) {
}
