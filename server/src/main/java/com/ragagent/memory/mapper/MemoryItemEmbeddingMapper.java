package com.ragagent.memory.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.memory.domain.MemoryItemEmbedding;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code memory_item_embeddings} 的仓储。
 *
 * <h2>⚠️ 主键是 {@code item_id}，不是 {@code id}</h2>
 * <p>upsert 的冲突靶子是 {@code item_id}（PG 上就是主键）。</p>
 *
 * <h2>⚠️ {@code embedding} 列刻意不映射进实体</h2>
 * <p>它是 pgvector 的 {@code halfvec}，只在装了 extension 的 PG 上存在
 * （迁移 000095 条件创建）。只用裸 SQL 碰它——
 * 写进实体会让 H2 上的 insert 直接炸。所以下面两条向量 SQL 都用
 * {@code @Select}/{@code @Update} 的裸 SQL，且只在 {@code vectorColumnReady()} 为真时调用。</p>
 *
 * <h2>⚠️ 两条裸 SQL 的结果映射要显式声明</h2>
 * <p>自定义 {@code @Select} 不会自动套实体上的 {@code @TableField(typeHandler=…)}（§9），
 * 而 {@code vector} 是 {@code bytea} → 映射到 {@code byte[]} 没问题（JDBC 原生支持）。
 * 但为了让返回对象走同一套规则，这里仍显式写 {@code @Results}——
 * 列名与属性名的下划线映射在裸 {@code @Select} 上**同样不会自动发生**
 * （没有 {@code mapUnderscoreToCamelCase} 的保证，取决于全局配置）。</p>
 */
@Mapper
public interface MemoryItemEmbeddingMapper extends BaseMapper<MemoryItemEmbedding> {

    /** 对照 {@code UpsertItemEmbedding} 的 {@code OnConflict{DoUpdates: [model_id, dims, vector, updated_at]}}。 */
    @Insert("INSERT INTO memory_item_embeddings "
            + "(item_id, tenant_id, subject_id, model_id, dims, vector, created_at, updated_at) "
            + "VALUES (#{e.itemId}, #{e.tenantId}, #{e.subjectId}, #{e.modelId}, #{e.dims}, "
            + "        #{e.vector}, #{e.createdAt}, #{e.updatedAt}) "
            + "ON CONFLICT (item_id) DO UPDATE SET model_id = EXCLUDED.model_id, dims = EXCLUDED.dims, "
            + "vector = EXCLUDED.vector, updated_at = EXCLUDED.updated_at")
    int upsertPostgres(@Param("e") MemoryItemEmbedding embedding);

    /**
     * H2 没有 {@code ON CONFLICT}：先删后插。
     *
     * <p>语义与 PG 的 {@code DO UPDATE} 有细微差别——这里的 created_at 会被重置
     * （PG 的 {@code DO UPDATE} 列集里**没有** created_at，所以 PG 上它保持原值）。
     * 为对齐这一点，仓储层在走这条路时会把已有的 created_at 读出来带上。</p>
     */
    @Insert("INSERT INTO memory_item_embeddings "
            + "(item_id, tenant_id, subject_id, model_id, dims, vector, created_at, updated_at) "
            + "VALUES (#{e.itemId}, #{e.tenantId}, #{e.subjectId}, #{e.modelId}, #{e.dims}, "
            + "        #{e.vector}, #{e.createdAt}, #{e.updatedAt})")
    int insertRow(@Param("e") MemoryItemEmbedding embedding);

    /**
     * 取单行（H2 的"先删后插"需要先读出已存在的 {@code created_at}，
     * 好让 upsert 的净效果与 PG 的 {@code DO UPDATE} 一致——PG 的列集里没有 created_at）。
     */
    @Select("SELECT item_id, tenant_id, subject_id, model_id, dims, vector, created_at, updated_at "
            + "FROM memory_item_embeddings WHERE item_id = #{itemId}")
    @Results({
            @Result(column = "item_id", property = "itemId", id = true),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "subject_id", property = "subjectId"),
            @Result(column = "model_id", property = "modelId"),
            @Result(column = "dims", property = "dims"),
            @Result(column = "vector", property = "vector"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
    })
    MemoryItemEmbedding selectByItemId(@Param("itemId") String itemId);

    /** 对照 {@code DeleteItemEmbedding} 与 {@code UpdateItemContent}/{@code DeleteItem} 里的向量删除。 */
    @Delete("DELETE FROM memory_item_embeddings WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND item_id = #{itemId}")
    int deleteByItemId(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                       @Param("itemId") String itemId);

    /** 对照 {@code ItemEmbeddings}：{@code item_id IN ? AND model_id = ?}。 */
    @Select("<script>"
            + "SELECT item_id, tenant_id, subject_id, model_id, dims, vector, created_at, updated_at "
            + "FROM memory_item_embeddings WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND item_id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "AND model_id = #{modelId}"
            + "</script>")
    @Results({
            @Result(column = "item_id", property = "itemId", id = true),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "subject_id", property = "subjectId"),
            @Result(column = "model_id", property = "modelId"),
            @Result(column = "dims", property = "dims"),
            @Result(column = "vector", property = "vector"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
    })
    List<MemoryItemEmbedding> selectByItemIds(@Param("tenantId") long tenantId,
                                              @Param("subjectId") String subjectId,
                                              @Param("ids") List<String> itemIds,
                                              @Param("modelId") String modelId);

    /**
     * 对照 {@code rankInProcess} 里那条"一次取全"的 SQL（内存兜底排名用）。
     *
     * <p>只取 {@code item_id} 与 {@code vector} 两列；{@code LIMIT} 是
     * {@code fallbackVectorScanCap}（5000）。</p>
     */
    @Select("<script>"
            + "SELECT e.item_id AS item_id, e.vector AS vector "
            + "FROM memory_item_embeddings e "
            + "JOIN memory_items i ON i.id = e.item_id AND i.tenant_id = e.tenant_id "
            + "  AND i.subject_id = e.subject_id "
            + "WHERE e.tenant_id = #{tenantId} AND e.subject_id = #{subjectId} "
            + "AND e.model_id = #{modelId} AND e.dims = #{dims} "
            + "AND i.status = #{status} AND (i.expires_at IS NULL OR i.expires_at &gt; #{now})"
            + "<if test='kinds != null and kinds.size() &gt; 0'>"
            + "  AND i.kind IN <foreach collection='kinds' item='k' open='(' separator=',' close=')'>#{k}</foreach>"
            + "</if>"
            + " LIMIT #{limit}"
            + "</script>")
    @Results({
            @Result(column = "item_id", property = "itemId", id = true),
            @Result(column = "vector", property = "vector"),
    })
    List<MemoryItemEmbedding> selectScopedVectors(@Param("tenantId") long tenantId,
                                                  @Param("subjectId") String subjectId,
                                                  @Param("modelId") String modelId,
                                                  @Param("dims") int dims,
                                                  @Param("kinds") List<String> kinds,
                                                  @Param("status") String status,
                                                  @Param("now") OffsetDateTime now,
                                                  @Param("limit") int limit);

    /**
     * 对照 {@code SyncVectorColumn} 的选行：只拿 blob、还没写进 vector 列的行。
     *
     * <p>只在 {@code vectorColumnReady()} 为真时调用（{@code embedding} 列可能不存在）。</p>
     */
    @Select("SELECT item_id, vector FROM memory_item_embeddings "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND embedding IS NULL AND vector IS NOT NULL LIMIT #{limit}")
    @Results({
            @Result(column = "item_id", property = "itemId", id = true),
            @Result(column = "vector", property = "vector"),
    })
    List<MemoryItemEmbedding> selectPendingVectorColumn(@Param("tenantId") long tenantId,
                                                        @Param("subjectId") String subjectId,
                                                        @Param("limit") int limit);

    /**
     * 对照 {@code rankInDatabase}：让数据库自己按余弦距离排序、只回前 k 条。
     *
     * <p><b>只在 {@code vectorColumnReady()} 为真时调用</b>（{@code embedding} 列与
     * {@code <=>} 运算符都是 pgvector 才有的东西）。SQL：</p>
     * <ul>
     *   <li>JOIN 条件同时带 {@code tenant_id}/{@code subject_id}——只按 id 连会跨主体；</li>
     *   <li>{@code e.dims = ?} 用**查询向量的长度**，与其它模型的向量不混算；</li>
     *   <li>{@code ORDER BY} 用的是**运算符本身**而不是算出来的 score，
     *       好让表达式与将来可能建的索引完全一致。</li>
     * </ul>
     *
     * <p>⚠️ 这条路径**没有测试覆盖**：H2 上没有 pgvector，测试恒走
     * {@code rankInProcess} 兜底。</p>
     */
    @Select("<script>"
            + "SELECT e.item_id AS item_id, "
            + "       1 - (e.embedding::halfvec &lt;=&gt; #{literal}::halfvec) AS score "
            + "FROM memory_item_embeddings e "
            + "JOIN memory_items i ON i.id = e.item_id AND i.tenant_id = e.tenant_id "
            + "  AND i.subject_id = e.subject_id "
            + "WHERE e.tenant_id = #{tenantId} AND e.subject_id = #{subjectId} "
            + "AND e.model_id = #{modelId} AND e.dims = #{dims} AND e.embedding IS NOT NULL "
            + "AND i.status = #{status} AND (i.expires_at IS NULL OR i.expires_at &gt; #{now})"
            + "<if test='kinds != null and kinds.size() &gt; 0'>"
            + "  AND i.kind IN <foreach collection='kinds' item='k' open='(' separator=',' close=')'>#{k}</foreach>"
            + "</if>"
            + " ORDER BY e.embedding::halfvec &lt;=&gt; #{literal}::halfvec LIMIT #{limit}"
            + "</script>")
    @Results({
            @Result(column = "item_id", property = "itemId", id = true),
            @Result(column = "score", property = "score"),
    })
    List<VectorHitRow> rankInDatabase(@Param("tenantId") long tenantId,
                                      @Param("subjectId") String subjectId,
                                      @Param("modelId") String modelId,
                                      @Param("dims") int dims,
                                      @Param("kinds") List<String> kinds,
                                      @Param("status") String status,
                                      @Param("now") OffsetDateTime now,
                                      @Param("literal") String literal,
                                      @Param("limit") int limit);

    /**
     * 对照 {@code writeVectorColumn}：把 blob 同步进 pgvector 的 {@code halfvec} 列。
     *
     * <p>{@code ?::halfvec} 的强转是 PG 专有的，H2 上不可调用。</p>
     */
    @Update("UPDATE memory_item_embeddings SET embedding = CAST(#{literal} AS halfvec) WHERE item_id = #{itemId}")
    int writeVectorColumn(@Param("itemId") String itemId, @Param("literal") String literal);
}
