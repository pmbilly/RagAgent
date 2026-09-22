package com.ragagent.datasource.service;

/**
 * "为这个数据源找一个/建一个标签"的能力（对照 Go {@code interfaces.KnowledgeTagService}
 * 的 {@code FindOrCreateTagByName}，在 {@code datasource_service.go} 的
 * {@code resolveAutoTagIDs} 里被调用）。
 *
 * <h2>端口历史与现状</h2>
 * <p>波 0 落地时 {@code knowledge_tag} 模块尚未翻译，端口先钉住同步主流程的
 * 逐行对应；2026-09-23 走查批随 {@link KnowledgeTagAutoTagProvider} 接上生产实现。
 * 测试仍可注入假实现验证"拿到的 tagID 确实被传下去"。</p>
 *
 * <h2>失败语义（对照 Go）</h2>
 * <p>实现抛 RuntimeException（= Go 的 tagErr != nil）时，调用方 warn 后<b>继续同步</b>
 * ——条目只是没有自动标签，同步不失败；返回 null 等价 Go 的
 * {@code autoTag == nil} 分支（同样不打警告）。</p>
 */
public interface AutoTagProvider {

    /**
     * 在给定知识库里找同名标签，没有就建一个。
     *
     * @return 标签 ID；{@code null} 表示"本次不打标"（对照 Go 的 {@code autoTag == nil}）
     * @throws RuntimeException 标签服务不可用/写失败——<b>非致命</b>：
     *                          调用方按 Go 的语义记 warn 后继续同步
     */
    String findOrCreateTagId(String kbId, String name);
}
