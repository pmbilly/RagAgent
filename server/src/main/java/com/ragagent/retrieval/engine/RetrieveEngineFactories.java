package com.ragagent.retrieval.engine;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工厂函数——对照 Go {@code internal/application/service/retriever/factory.go}
 * （260 行）的 {@code CreateRetrieveEngineForKB} / {@code CreateRetrieveEngineFromPayload}
 * / {@code VerifyBinding} / {@code classifyLookupError} / {@code isContextError}。
 *
 * <p>本类只做解析与分类，不做构造：真实构造在
 * {@link EngineFactory#createFromStore}（经 {@link StoreEngineFactory} 注入注册表）。</p>
 *
 * <h2>解析规则（照抄 Go 注释）</h2>
 * <ol>
 *   <li><b>无绑定</b>（storeId 为 null 或空串）→ 回落租户有效引擎（{@code RETRIEVE_DRIVER}
 *       驱动的 env-store 流）。</li>
 *   <li><b>有绑定</b> → ①归属校验必须为真，跨租户得 {@code FORBIDDEN}；②注册表必须能给出引擎，
 *       未注册得 {@code NOT_FOUND}（DB 里有行但启动期建失败就是这个形态）；③单引擎仍包成
 *       复合引擎，保住它 {@code Support()} 驱动的检索类型匹配。</li>
 * </ol>
 *
 * <h2>哨兵是给调用方分类用的</h2>
 * <p>异步任务处理（如知识库/索引删除）把 {@code FORBIDDEN}/{@code NOT_FOUND} 映射成
 * {@code SkipRetry}（永久丢弃），把 {@code UNAVAILABLE} 当成可重试。所以"取消/超时"与
 * "数据库抖动"都<b>绝不能</b>被报成 not-found——那等于把一次性故障变成永久丢单。</p>
 *
 * <h2>与 Go 的差异（备案）</h2>
 * <ul>
 *   <li>{@code CreateRetrieveEngineForKB} 原本从 ctx 读 TenantInfo；本仓按约定 §1 显式传
 *       {@code tenantEngines}（{@code null} 表示"ctx 里没有 TenantInfo"）。接线方负责用
 *       {@link EffectiveEngines#of} 从 {@code TenantContext} 取出真实租户的引擎列表。</li>
 *   <li>结构化日志走 slf4j；字段（tenant_id / store_id / reason）照抄。</li>
 * </ul>
 */
public final class RetrieveEngineFactories {

    private static final Logger log = LoggerFactory.getLogger(RetrieveEngineFactories.class);

    private RetrieveEngineFactories() {
    }

    /**
     * 对照 {@code CreateRetrieveEngineForKB}：从知识库的 VectorStore 绑定解析复合引擎。
     * 应用服务的<b>同步</b>调用点走这个。
     *
     * @param vectorStoreId 知识库的 vector_store_id（null/空 = 无绑定）
     * @param tenantEngines 当前租户的有效引擎；{@code null} = ctx 里没有 TenantInfo
     */
    public static CompositeRetrieveEngine createForKb(RetrieveEngineRegistry registry,
                                                      TenantStoreOwnership ownership,
                                                      long tenantId,
                                                      String vectorStoreId,
                                                      List<RetrieverEngineParams> tenantEngines) {
        // nil 与空串都归一到"无绑定"，调用方没法把空 UUID 顺手塞进 getByStoreID。
        if (vectorStoreId == null || vectorStoreId.isEmpty()) {
            if (tenantEngines == null) {
                throw RetrieveEngineException.TENANT_INFO_MISSING;
            }
            return CompositeRetrieveEngine.create(registry, tenantEngines);
        }
        return resolveBoundEngine(registry, ownership, tenantId, vectorStoreId);
    }

    /**
     * 对照 {@code CreateRetrieveEngineFromPayload}：异步任务变体。它<b>不</b>从 ctx 读
     * TenantInfo（异步处理不填 ctx），tenantID 由反序列化的载荷显式带来，并在 storeId
     * 非空时与 store 的真实归属核对。
     *
     * <p>{@code vectorStoreID} 加入载荷之前入队的任务解出来是 null → 透明回落到
     * 预序列化的 effectiveEngines 路径，升级期间不丢任何在途任务。</p>
     */
    public static CompositeRetrieveEngine createFromPayload(RetrieveEngineRegistry registry,
                                                            TenantStoreOwnership ownership,
                                                            long tenantId,
                                                            List<RetrieverEngineParams> effectiveEngines,
                                                            String vectorStoreId) {
        if (vectorStoreId == null || vectorStoreId.isEmpty()) {
            return CompositeRetrieveEngine.create(registry, effectiveEngines);
        }
        return resolveBoundEngine(registry, ownership, tenantId, vectorStoreId);
    }

    /**
     * 对照 {@code VerifyBinding}：断言非空 storeId 属于该租户、且在注册表里能取到引擎。
     * 把"每个 store 绑定都要过的两道关"封成一个函数，好让检索包之外的调用方
     * （尤其是知识库创建校验路径）复用同一套哨兵层级而不是各抄一遍。
     *
     * <p>失败分类：归属查询的基础设施错误<b>原样返回</b>（调用方自行决定重试/放弃）；
     * 不属于该租户 → {@code FORBIDDEN}；属于但注册表取不到 → {@code classifyLookupError}。</p>
     *
     * <p>本函数自身从不回显 store UUID；调用方必须在边界处把哨兵包成用户可见文案
     * （并按需用结构化字段记下 tenant/store）。</p>
     */
    public static void verifyBinding(RetrieveEngineRegistry registry,
                                     TenantStoreOwnership ownership,
                                     long tenantId, String storeId) {
        boolean owned = ownership.storeOwnedBy(storeId, tenantId);
        if (!owned) {
            throw RetrieveEngineException.VECTOR_STORE_FORBIDDEN;
        }
        try {
            registry.getOrLoadByStoreId(tenantId, storeId);
        } catch (RuntimeException e) {
            throw classifyLookupError(e);
        }
    }

    /**
     * 对照 {@code resolveBoundEngine}：归属校验后的共享查表路径。
     *
     * <p>直接用解析出的服务拼复合引擎，<b>不能</b>走 {@code NewCompositeRetrieveEngine}——
     * 那个函数经 {@code GetRetrieveEngineService} 读 byEngineType 表（env-store），
     * 而 DB store 在 byStoreID 表里、只按引擎类型取不到（同一类型可以有多个 store）。
     * 语义上：绑了 DB store 的知识库用该 store 支持的<b>全部</b>检索类型，这有意压过
     * 租户级有效引擎过滤——把知识库绑到具体 store 就是明确退出了"租户默认路由"。</p>
     */
    private static CompositeRetrieveEngine resolveBoundEngine(RetrieveEngineRegistry registry,
                                                              TenantStoreOwnership ownership,
                                                              long tenantId, String storeId) {
        boolean owned;
        try {
            owned = ownership.storeOwnedBy(storeId, tenantId);
        } catch (RuntimeException e) {
            // 这次查询用调用方的上下文打数据库，通常是停机/断连最先被发现的地方。
            // 把它报成"对 store 的判定"，异步 worker 就会丢掉只需重跑一次的任务。
            if (RetrieveEngineException.isCancellation(e)) {
                throw e;
            }
            // 基础设施故障：原始错误只进日志，不泄漏内部细节。store 本身可能好着，
            // 所以这是"可重试"而不是"不存在"。
            log.error("[retriever.factory] ownership lookup failed tenant_id={} store_id={}: {}",
                    tenantId, storeId, e.toString());
            throw RetrieveEngineException.VECTOR_STORE_UNAVAILABLE;
        }
        if (!owned) {
            // 跨租户尝试（或 store 期间被删）。WARN 便于审计发现试探行为。
            log.warn("[retriever.factory] cross-tenant store access attempted: tenant={} store={}",
                    tenantId, storeId);
            throw RetrieveEngineException.VECTOR_STORE_FORBIDDEN;
        }

        RetrieveEngineService svc;
        try {
            svc = registry.getOrLoadByStoreId(tenantId, storeId);
        } catch (RuntimeException e) {
            if (RetrieveEngineException.isCancellation(e)) {
                throw e;
            }
            log.error("[retriever.factory] store engine could not be resolved tenant_id={} "
                    + "store_id={}: {}", tenantId, storeId, e.toString());
            throw classifyLookupError(e);
        }
        return CompositeRetrieveEngine.ofSingle(svc);
    }

    /**
     * 对照 {@code classifyLookupError}：把引擎查表失败收敛成调用方<b>允许看到</b>的那几种，
     * 同时保住"决定任务是被重试还是被丢弃"的那个分野。
     *
     * <p>取消/超时与三个 store 哨兵原样透传；其余一律当可重试——
     * 把未知失败当永久失败，才是静默丢单的根源。</p>
     */
    static RuntimeException classifyLookupError(RuntimeException err) {
        if (RetrieveEngineException.isCancellation(err)) {
            // 取消/超时原样透传：它不是对 store 的判定。
            return err;
        }
        if (err instanceof RetrieveEngineException e
                && (e.kind() == RetrieveEngineException.Kind.VECTOR_STORE_NOT_FOUND
                        || e.kind() == RetrieveEngineException.Kind.VECTOR_STORE_UNAVAILABLE
                        || e.kind() == RetrieveEngineException.Kind.VECTOR_STORE_FORBIDDEN)) {
            return e;
        }
        // 未知失败一律当可重试——把未知当永久，正是静默丢单的根源。
        return RetrieveEngineException.VECTOR_STORE_UNAVAILABLE;
    }
}
