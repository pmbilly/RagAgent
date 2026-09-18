package com.ragagent.memory.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.memory.domain.MemoryItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code memory_items} 的仓储（对照 Go internal/application/repository/memory.go 的
 * {@code scoped} 系列与 memory_lifecycle.go）。
 *
 * <h2>逐条 SQL 的对齐说明</h2>
 * <ul>
 *   <li><b>{@code notExpired} 的括号</b>：Go 写 {@code Where("expires_at IS NULL OR expires_at > ?")}，
 *       GORM 的 {@code clause.Where} 检测到表达式里有 {@code " OR "} 会**自动加括号**
 *       （{@code clause/where.go:61}），所以实际 SQL 是
 *       {@code … AND (expires_at IS NULL OR expires_at > ?)}。这里照抄括号——
 *       少了它整条查询的优先级就变了。</li>
 *   <li><b>{@code id DESC} 破平局</b>：{@code ListItems} 的 {@code valid_from DESC, id DESC}
 *       不是装饰。一次蒸馏会同时写好几条，只按 valid_from 排会让数据库在翻页时
 *       给出不同顺序，于是 offset 遍历会**既重复又漏行**。</li>
 *   <li><b>{@code COALESCE(last_used_at, valid_from)}</b>：容量归档的排名是
 *       重要度 → 使用时间（没有就退回生效时间）→ 生效时间，**没有衰减曲线**。</li>
 *   <li><b>子查询里的 scope 重复</b>：{@code ItemsMissingEmbeddings} 的子查询必须自己带
 *       {@code tenant_id}/{@code subject_id}，否则会看到别的 subject 的向量。</li>
 * </ul>
 *
 * <p>所有方法都不带事务注解：它们由 {@link MemoryRepository} 在
 * {@code MemoryTxTemplate.withSubject} 的事务里调用（Go 的 {@code tx} 参数）。</p>
 */
@Mapper
public interface MemoryItemMapper extends BaseMapper<MemoryItem> {

    // ── 读 ─────────────────────────────────────────────────────────────────

    @Select("SELECT * FROM memory_items WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND id = #{id}")
    MemoryItem selectScoped(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                            @Param("id") String id);

    /** 对照 {@code CountActive}。 */
    @Select("SELECT COUNT(*) FROM memory_items WHERE tenant_id = #{tenantId} "
            + "AND subject_id = #{subjectId} AND status = #{status}")
    long countByStatus(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                       @Param("status") String status);

    /**
     * 对照 {@code ListActiveByKinds}：{@code kinds} 为空时 Go 直接回 {@code (nil, nil)}，
     * 所以调用方必须先判空、别指望这里兜。
     */
    @Select("<script>"
            + "SELECT * FROM memory_items WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND status = #{status} AND (expires_at IS NULL OR expires_at &gt; #{now}) "
            + "AND kind IN <foreach collection='kinds' item='k' open='(' separator=',' close=')'>#{k}</foreach> "
            + "ORDER BY importance DESC, valid_from DESC"
            + "<if test='limit &gt; 0'> LIMIT #{limit}</if>"
            + "</script>")
    List<MemoryItem> listActiveByKinds(@Param("tenantId") long tenantId,
                                       @Param("subjectId") String subjectId,
                                       @Param("kinds") List<String> kinds,
                                       @Param("status") String status,
                                       @Param("now") OffsetDateTime now,
                                       @Param("limit") int limit);

    /**
     * 对照 {@code ListActiveResident}：{@code kind IN (常驻三种) OR origin = 'explicit'}。
     *
     * <p>用户**明确要求**记住的东西不问 kind：他说了"记住这个"，
     * 而让这件事取决于他之后的问题恰好与它共享词汇，是让这个功能失去信任最快的方式。</p>
     */
    @Select("<script>"
            + "SELECT * FROM memory_items WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND status = #{status} AND (expires_at IS NULL OR expires_at &gt; #{now}) "
            + "AND (kind IN <foreach collection='kinds' item='k' open='(' separator=',' close=')'>#{k}</foreach> "
            + "     OR origin = #{explicitOrigin}) "
            + "ORDER BY importance DESC, valid_from DESC"
            + "<if test='limit &gt; 0'> LIMIT #{limit}</if>"
            + "</script>")
    List<MemoryItem> listActiveResident(@Param("tenantId") long tenantId,
                                        @Param("subjectId") String subjectId,
                                        @Param("kinds") List<String> kinds,
                                        @Param("explicitOrigin") String explicitOrigin,
                                        @Param("status") String status,
                                        @Param("now") OffsetDateTime now,
                                        @Param("limit") int limit);

    /** 对照 {@code ListItems} 的计数（先 Count 再取页）。 */
    @Select("<script>"
            + "SELECT COUNT(*) FROM memory_items WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId}"
            + "<if test='status != null and status != \"\"'> AND status = #{status}</if>"
            + "</script>")
    long countListItems(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                        @Param("status") String status);

    /** 对照 {@code ListItems} 的数据页：{@code valid_from DESC, id DESC}（见类注释）。 */
    @Select("<script>"
            + "SELECT * FROM memory_items WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId}"
            + "<if test='status != null and status != \"\"'> AND status = #{status}</if>"
            + " ORDER BY valid_from DESC, id DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<MemoryItem> listItems(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                               @Param("status") String status,
                               @Param("limit") int limit, @Param("offset") int offset);

    /** 对照 {@code ListLive}：用户当前**看得到**的条目 = 在用 + 待确认。 */
    @Select("<script>"
            + "SELECT * FROM memory_items WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND status IN <foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach> "
            + "AND kind = #{kind} AND (expires_at IS NULL OR expires_at &gt; #{now}) "
            + "ORDER BY importance DESC, valid_from DESC"
            + "<if test='limit &gt; 0'> LIMIT #{limit}</if>"
            + "</script>")
    List<MemoryItem> listLive(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                              @Param("statuses") List<String> statuses, @Param("kind") String kind,
                              @Param("now") OffsetDateTime now, @Param("limit") int limit);

    /**
     * 对照 {@code FindActiveByKey}：{@code pending} 在这里算"活着"——
     * 一条等待确认的记忆是用户已经看得到的，忽略它会让同一推断每重推一次就多堆一份。
     */
    @Select("<script>"
            + "SELECT * FROM memory_items WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND status IN <foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach> "
            + "AND normalized_key = #{normalizedKey} ORDER BY valid_from DESC LIMIT 1"
            + "</script>")
    MemoryItem findLiveByKey(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                             @Param("statuses") List<String> statuses,
                             @Param("normalizedKey") String normalizedKey);

    /** 对照 {@code SaveItem} 里 {@code normalized_key = ? AND status IN ?} 的那次 {@code Find}。 */
    @Select("<script>"
            + "SELECT * FROM memory_items WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND normalized_key = #{normalizedKey} "
            + "AND status IN <foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach>"
            + "</script>")
    List<MemoryItem> listByNormalizedKey(@Param("tenantId") long tenantId,
                                         @Param("subjectId") String subjectId,
                                         @Param("normalizedKey") String normalizedKey,
                                         @Param("statuses") List<String> statuses);

    /** 对照 {@code SearchItemsByVector} 里按 id 载入条目的那一次 {@code Find}。 */
    @Select("<script>"
            + "SELECT * FROM memory_items WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<MemoryItem> selectByIds(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                                 @Param("ids") List<String> ids);

    // ── 删除（一律带 scope） ───────────────────────────────────────────────

    /**
     * 对照 {@code DeleteItem} 的最后一步与 {@code trimTombstones} 的同类写法：
     * Go 的 {@code Where(...).Delete(...)} 一定带 {@code tenant_id}/{@code subject_id}。
     *
     * <p>⚠️ 这就是本模块**不用** MyBatis-Plus {@code deleteById} 的原因：
     * 后者只按主键，传一个别人的 id 会真的删掉别人的行。</p>
     */
    @Delete("DELETE FROM memory_items WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND id = #{id}")
    int deleteScoped(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                     @Param("id") String id);

    /** 对照 {@code DeleteAll}：整 scope 的物理删，返回受影响行数。 */
    @Delete("DELETE FROM memory_items WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId}")
    int deleteAllInScope(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId);

    // ── 容量 / 过期 ────────────────────────────────────────────────────────

    /**
     * 对照 {@code ArchiveLowestRanked} 的 {@code Pluck("id", &survivors)}：
     * 留下排名最好的 {@code keep} 条。
     *
     * <p>排名 = 重要度 → {@code COALESCE(last_used_at, valid_from)} → {@code valid_from}</p>
     */
    @Select("SELECT id FROM memory_items WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND status = #{status} "
            + "ORDER BY importance DESC, COALESCE(last_used_at, valid_from) DESC, valid_from DESC "
            + "LIMIT #{keep}")
    List<String> selectSurvivorIds(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                                   @Param("status") String status, @Param("keep") int keep);

    /** 对照 {@code ArchiveLowestRanked} 的批量 UPDATE（{@code id NOT IN ?} 仅在幸存者非空时加）。 */
    @Update("<script>"
            + "UPDATE memory_items SET status = #{archived}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} AND status = #{active}"
            + "<if test='survivors != null and survivors.size() &gt; 0'>"
            + "  AND id NOT IN <foreach collection='survivors' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</if>"
            + "</script>")
    int archiveExcept(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                      @Param("active") String active, @Param("archived") String archived,
                      @Param("survivors") List<String> survivors, @Param("now") OffsetDateTime now);

    /** 对照 {@code ExpireOverdue}：{@code status=active AND expires_at IS NOT NULL AND expires_at <= now}。 */
    @Update("UPDATE memory_items SET status = #{archived}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND status = #{active} AND expires_at IS NOT NULL AND expires_at <= #{now}")
    int expireOverdue(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                      @Param("active") String active, @Param("archived") String archived,
                      @Param("now") OffsetDateTime now);

    /**
     * 对照 {@code TouchUsed}：{@code use_count = use_count + 1} 是 SQL 侧自增，
     * 读回来再写会丢并发。
     *
     * <p>⚠️ {@code updated_at} 在 Go 的 map 里**没有**，但 GORM 的
     * {@code ConvertToAssignments}（callbacks/update.go L236-254）会对"map 里没写
     * updated_at 的模型"自动补上 {@code updated_at = now}——所以线上确实写了这一列。
     * 这类"Go 源码没写、GORM 偷偷补"的地方本模块共三处（另两处见下），
     * 漏掉会在真 PG 上表现为"updated_at 不动"，H2 测不出来。</p>
     */
    @Update("<script>"
            + "UPDATE memory_items SET last_used_at = #{now}, use_count = use_count + 1, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    int touchUsed(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                  @Param("ids") List<String> ids, @Param("now") OffsetDateTime now);

    /**
     * 对照 {@code ItemsMissingEmbeddings}：找出向量积压。
     *
     * <p>子查询里重复 scope 是刻意的——不带就会看到别的 subject 的向量，
     * 于是本 subject 的条目被误判成"已有向量"。</p>
     */
    @Select("SELECT * FROM memory_items WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND status IN (#{active}, #{pending}) "
            + "AND id NOT IN (SELECT item_id FROM memory_item_embeddings "
            + "               WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "               AND model_id = #{modelId}) "
            + "ORDER BY valid_from DESC LIMIT #{limit}")
    List<MemoryItem> itemsMissingEmbeddings(@Param("tenantId") long tenantId,
                                            @Param("subjectId") String subjectId,
                                            @Param("active") String active,
                                            @Param("pending") String pending,
                                            @Param("modelId") String modelId,
                                            @Param("limit") int limit);

    // ── 生命周期（memory_lifecycle.go） ────────────────────────────────────

    /**
     * 对照 {@code SaveItem} 尾部的批量取代 UPDATE。
     *
     * <p>SQL 逐字对照：
     * {@code id <> ? AND (id IN ? OR replaces_id IN ?) AND status IN ?}。</p>
     */
    @Update("<script>"
            + "UPDATE memory_items SET status = #{superseded}, invalid_at = #{now}, "
            + "superseded_by = #{itemId}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND id &lt;&gt; #{itemId} "
            + "AND (id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "     OR replaces_id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>) "
            + "AND status IN <foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach>"
            + "</script>")
    int supersedeByIds(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                       @Param("itemId") String itemId, @Param("ids") List<String> ids,
                       @Param("statuses") List<String> statuses,
                       @Param("superseded") String superseded, @Param("now") OffsetDateTime now);

    /**
     * 对照 {@code SupersedeItem}。
     *
     * <p>注意 {@code (id = ? AND status = 'active') OR (replaces_id = ? AND status = 'pending')}
     * 这两支**都**带状态——直接按 id 更新会误伤已经取代过的行。</p>
     */
    @Update("UPDATE memory_items SET status = #{superseded}, invalid_at = #{now}, "
            + "superseded_by = #{supersededBy}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND ((id = #{id} AND status = #{active}) OR (replaces_id = #{id} AND status = #{pending}))")
    int supersedeItem(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                      @Param("id") String id, @Param("supersededBy") String supersededBy,
                      @Param("active") String active, @Param("pending") String pending,
                      @Param("superseded") String superseded, @Param("now") OffsetDateTime now);

    /** 对照 {@code UpdateItemContent} 里"编辑确认过的事实会作废基于旧措辞的提议"那一次 UPDATE。 */
    @Update("UPDATE memory_items SET status = #{superseded}, invalid_at = #{now}, superseded_by = #{id} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND replaces_id = #{id} AND status = #{pending}")
    int supersedeProposalsOf(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                             @Param("id") String id, @Param("pending") String pending,
                             @Param("superseded") String superseded, @Param("now") OffsetDateTime now);

    /** 对照 {@code UpdateItemContent} 对当前行的 {@code Updates(map)}（五列，无条件覆盖）。 */
    @Update("UPDATE memory_items SET content = #{content}, normalized_key = #{normalizedKey}, "
            + "importance = #{importance}, origin = #{origin}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} AND id = #{id}")
    int updateItemContent(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                          @Param("id") String id, @Param("content") String content,
                          @Param("normalizedKey") String normalizedKey, @Param("importance") int importance,
                          @Param("origin") String origin, @Param("now") OffsetDateTime now);

    /**
     * 对照 {@code DeleteItem} 对"待确认的替换者"那一次 UPDATE。
     *
     * <p>Go 的 map 里只有 status / invalid_at 两列，但 GORM 会自动补
     * {@code updated_at = now}（见 {@link #touchUsed} 的说明）——所以要写三列。</p>
     */
    @Update("UPDATE memory_items SET status = #{superseded}, invalid_at = #{now}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND replaces_id = #{id} AND status = #{pending}")
    int supersedePendingReplacements(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                                     @Param("id") String id, @Param("pending") String pending,
                                     @Param("superseded") String superseded, @Param("now") OffsetDateTime now);

    /**
     * 对照 {@code ConfirmPendingItem} 的第一步：把与待确认项同 key / 同目标的东西全部作废。
     *
     * <p>SQL 逐字对照：
     * {@code id <> ? AND (normalized_key = ? OR id = ? OR (replaces_id <> '' AND replaces_id = ?)) AND status IN ?}。
     * {@code replaces_id <> ''} 那个守卫不能省：{@code replaces_id} 为空的待确认项
     * 会匹配上所有 {@code replaces_id = ''} 的行。</p>
     */
    @Update("<script>"
            + "UPDATE memory_items SET status = #{superseded}, invalid_at = #{now}, "
            + "superseded_by = #{id}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND id &lt;&gt; #{id} "
            + "AND (normalized_key = #{normalizedKey} OR id = #{replacesId} "
            + "     OR (replaces_id &lt;&gt; '' AND replaces_id = #{replacesId})) "
            + "AND status IN <foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach>"
            + "</script>")
    int supersedeForConfirm(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                            @Param("id") String id, @Param("normalizedKey") String normalizedKey,
                            @Param("replacesId") String replacesId, @Param("statuses") List<String> statuses,
                            @Param("superseded") String superseded, @Param("now") OffsetDateTime now);

    /** 对照 {@code ConfirmPendingItem} 的第二步：把这一行置为 active（**只动 status 与 updated_at**）。 */
    @Update("UPDATE memory_items SET status = #{active}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} AND id = #{id}")
    int activateItem(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                     @Param("id") String id, @Param("active") String active,
                     @Param("now") OffsetDateTime now);
}
