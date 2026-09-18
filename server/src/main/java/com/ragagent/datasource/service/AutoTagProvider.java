package com.ragagent.datasource.service;

/**
 * "为这个数据源找一个/建一个标签"的能力（对照 Go {@code interfaces.KnowledgeTagService}
 * 的 {@code FindOrCreateTagByName}，在 {@code datasource_service.go} 的
 * {@code resolveAutoTagIDs} 里被调用）。
 *
 * <h2>为什么是端口</h2>
 * <p>Go 的 {@code knowledge_tag} 模块（{@code internal/application/service/knowledge_tag.go}
 * + {@code knowledge_tags} / {@code knowledge_tag_relations} 两张表）<b>尚未翻译</b>，
 * 本模块不能凭空造一个。做成端口后有两个明确的好处：</p>
 * <ol>
 *   <li>同步主流程与 Go 逐行对应（{@code resolveAutoTagIDs} 的三条分支都在），
 *       等标签模块落地时接上实现即可，不必回头改同步逻辑；</li>
 *   <li>测试可以注入一个真实返回标签的假实现，验证"拿到的 tagID 确实被传下去"。</li>
 * </ol>
 *
 * <h2>缺失实现时的行为</h2>
 * <p>{@link NoAutoTagProvider} 回 {@code null}，等价于 Go 的
 * {@code autoTag == nil} 分支——同步照常进行、条目只是没有自动标签。
 * Go 侧这个分支是因为"标签服务返回了 nil 标签"，不是因为出错，
 * 所以刻意<b>不</b>记警告（Go 只在 {@code tagErr != nil} 时 warn）。</p>
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
