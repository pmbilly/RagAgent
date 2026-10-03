package com.ragagent.datasource.service;

/**
 * "为这个数据源找一个/建一个标签"的能力（同步流程在 {@code resolveAutoTagIds}
 * 里调用）。
 *
 * <h2>端口历史与现状</h2>
 * <p>生产实现见 {@link KnowledgeTagAutoTagProvider}。
 * 测试仍可注入假实现验证"拿到的 tagID 确实被传下去"。</p>
 *
 * <h2>失败语义</h2>
 * <p>实现抛 RuntimeException 时，调用方 warn 后<b>继续同步</b>
 * ——条目只是没有自动标签，同步不失败；返回 {@code null} 同样不打警告、视为本次不打标。</p>
 */
public interface AutoTagProvider {

    /**
     * 在给定知识库里找同名标签，没有就建一个。
     *
     * @return 标签 ID；{@code null} 表示"本次不打标"
     * @throws RuntimeException 标签服务不可用/写失败——<b>非致命</b>：
     *                          调用方记 warn 后继续同步
     */
    String findOrCreateTagId(String kbId, String name);
}
