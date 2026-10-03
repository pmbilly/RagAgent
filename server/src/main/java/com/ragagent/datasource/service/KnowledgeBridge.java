package com.ragagent.datasource.service;

import java.util.List;
import java.util.Map;

import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;

/**
 * 数据源同步往知识库里写东西时要用的那一小片能力（知识库服务 + 仓储
 * 在本模块被调用到的部分）。
 *
 * <h2>为什么是一个端口，而不是直接注入 {@code KnowledgeService}</h2>
 * <p>三条理由，每一条都足以让它成立：</p>
 * <ol>
 *   <li><b>租户显式</b>：同步跑在后台虚拟线程上，<b>没有</b> {@code TenantContext}。
 *       {@code KnowledgeService} 一律从 {@code TenantContext} 取租户
 *       （{@code getKnowledge}/{@code deleteKnowledge}/{@code requireKb} 都是），
 *       在 worker 里会拿到 0 → 查不到 → 同步整条路走不通。
 *       所以本端口的每个方法都显式收租户。</li>
 *   <li><b>能力缺口</b>：{@code KnowledgeService} 的创建入口不收
 *       {@code metadata} 与 {@code tagIDs}（尚未支持）。同步回来时按
 *       {@code metadata->>'external_id'} 找旧行，metadata 缺失就<b>永远</b>找不到
 *       ——增量同步会退化成"每次新建一份"。</li>
 *   <li><b>可测</b>：service 的测试用假实现即可驱动整条同步状态机，
 *       不必拉起真实的知识解析管线。</li>
 * </ol>
 *
 * <h2>已知差异：知识库写入是"最小可用"闭环</h2>
 * <p>完整的知识写入还包含：文件名安全校验、按连接器/KB
 * 解析多模态与问题生成配置、标签关系、按 KB 的存储引擎选择、处理任务载荷
 * （含语言/问题数）、入队失败的补偿与审计。{@link MapperKnowledgeBridge}
 * 只保留"落一行可被后续同步找回的 knowledge + 交给进程内处理队列"这一条最小闭环
 * ——其余部分要么依赖尚未落地的能力（问题生成），要么对数据源同步这条
 * 路径没有可观察影响（审计埋点由本模块自己记）。<b>写进知识库的内容本身因而是最小集</b>：
 * 解析/分块/向量化仍走 {@code KnowledgeProcessWorker}。</p>
 *
 * <h2>不落响应体</h2>
 * <p>本端口的任何方法都不产出 HTTP 响应（同步是后台的），所以这里不存在
 * "字节对齐"要求；{@link Knowledge} 只是被读写的数据载体。</p>
 */
public interface KnowledgeBridge {

    /**
     * 按 id 查知识库：<b>不带租户过滤</b>
     * （调用方自己比 {@code tenantId}），查不到回 {@code null}。
     *
     * <p>⚠️ <b>不能</b>换成 {@code KnowledgeBaseService.getKnowledgeBase(String)}——
     * 那个方法是租户作用域 + 抛 {@code BizException}。而 datasource 的授权判定
     * 需要区分 403（库存在但属于别人）与 404（库不存在），把两者都压成 404
     * 会让跨租户探测从"拒绝"变成"不存在"。</p>
     */
    KnowledgeBase findKnowledgeBase(String kbId);

    /**
     * 按
     * {@code metadata->>'datasource_id'} + {@code metadata->>'external_id'} 找，
     * <b>限定本租户与知识库、排除软删行</b>；找不到回 {@code null}（不是错误）。
     */
    Knowledge findByDataSourceExternalId(long tenantId, String kbId, String dataSourceId,
                                         String externalId);

    /**
     * 按 metadata 前缀找行：{@code metadata->>key} 以
     * {@code prefix} 开头的行（用于"清扫"文档下已消失的子项）。
     * 无命中回空列表。
     */
    List<Knowledge> findByMetadataKeyPrefix(long tenantId, String kbId, String key, String prefix);

    /**
     * 把已抓取到的字节写进知识库。
     *
     * @param metadata 写进 {@code metadata} 列的键值对（含 {@code external_id} 等）
     * @param tagIds   自动打标（本模块的标签端口尚无实现，实际恒为空列表）
     * @param channel  界面上的来源标签（连接器给的 {@code metadata.channel} 优先）
     * @throws com.ragagent.knowledge.service.KnowledgeService.DuplicateKnowledgeException
     *         内容重复
     */
    Knowledge createFromFile(long tenantId, String kbId, byte[] content, String fileName,
                             Map<String, String> metadata, List<String> tagIds, String channel);

    /**
     * 让知识库自己去下载并解析 URL。
     *
     * <p>返回的行<b>还没有</b> datasource 的 metadata——调用方随后显式补上
     * （见 {@link #attachMetadata}），因为"重复内容"分支会复用一条已有行，
     * 那条行不该被重新打标。</p>
     */
    Knowledge createFromUrl(long tenantId, String kbId, String url, String fileName, String title,
                            List<String> tagIds, String channel);

    /** 把 metadata 合并写进该 knowledge 行。 */
    void attachMetadata(Knowledge knowledge, Map<String, String> metadata);

    /** 软删单条（含级联与存储清理）。 */
    void softDelete(long tenantId, String knowledgeId);

    /** 软删一批。 */
    void softDeleteList(long tenantId, List<String> knowledgeIds);

    /**
     * 物理删，必须在软删之后调，
     * 否则同步的删除会留下墓碑行、挡住同一个外部条目之后的重新同步。
     */
    void hardDelete(long tenantId, String knowledgeId);

    /** 物理删一批（空列表是 no-op）。 */
    void hardDeleteList(long tenantId, List<String> knowledgeIds);
}
