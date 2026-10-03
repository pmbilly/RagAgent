package com.ragagent.retrieval.engine;

/**
 * 检索引擎注册表端口——env-store（按引擎类型）与 DB-store（按 store ID）两张表的
 * 注册/查询口；实现同时充当 store 注册表。
 *
 * <h2>两张表</h2>
 * <ul>
 *   <li>{@code byEngineType}：{@code RETRIEVE_DRIVER} 注册的 env-store（向后兼容路径）；</li>
 *   <li>{@code byStoreID}：{@code vector_stores} 表注册的 DB-store（实例化路径，
 *       同一引擎类型可以有多个实例）。</li>
 * </ul>
 *
 * <p><b>重要</b>：{@link #getByStoreId} <b>不校验租户归属</b>。
 * 调用方必须走 {@link RetrieveEngineFactories} 的工厂函数（它包了归属校验，
 * 是对跨租户 IDOR 的纵深防御）。租户过滤是纵深防御而非归属校验的替代品。</p>
 *
 * <p>失败以 {@link RetrieveEngineException} 表达。</p>
 */
public interface RetrieveEngineRegistry {

    /** 按引擎类型注册；重复注册报错。 */
    void register(RetrieveEngineService service);

    /** 只查 byEngineType（env-store）。 */
    RetrieveEngineService getRetrieveEngineService(String engineType);

    /** 只返回 byEngineType 条目（向后兼容）。 */
    java.util.List<RetrieveEngineService> getAllRetrieveEngineServices();

    /** 按 store ID 注册，upsert 语义（静默覆盖）。 */
    void registerWithStoreId(String storeId, RetrieveEngineService service);

    /** 按 store ID 取（<b>不校验归属</b>，见类注释）。 */
    RetrieveEngineService getByStoreId(String storeId);

    /** miss 时按需从 DB 重建（见实现类注释）。 */
    RetrieveEngineService getOrLoadByStoreId(long tenantId, String storeId);

    /** 幂等移除并清冷却。 */
    void unregisterByStoreId(String storeId);

    /**
     * 是否具备按需重建能力。暴露出来是为了让"接线漏了"
     * 能被断言——缺依赖的注册表仍能服务查询，别的地方看不出重建被静默关掉了。
     */
    boolean canRebuildStores();
}
