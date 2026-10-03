package com.ragagent.memory.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.memory.domain.MemoryTopicStat;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code memory_topic_stats} 的仓储。
 *
 * <h2>⚠️ {@code aliases} 是 jsonb，且**绝不能写 NULL**</h2>
 * <p>DDL 是 {@code aliases JSONB NOT NULL DEFAULT '[]'}。落库语义对 null 与空列表
 * 都写 {@code "[]"}，所以这一列**从不** NULL。三件事都要做到：</p>
 * <ol>
 *   <li>写入路径**必须带** {@code MemoryStringListTypeHandler}——包括
 *       {@code @Update} 里的 {@code #{aliases}}，以及 {@code BaseMapper.insert} 走的
 *       实体 {@code @TableField(typeHandler=…)}（后者已配好）；</li>
 *   <li>值永远别是 {@code null}（仓储层负责归一成空列表）；</li>
 *   <li>实体上的 {@code insertStrategy/updateStrategy = ALWAYS} 保证空列表也会被写出去。</li>
 * </ol>
 */
@Mapper
public interface MemoryTopicStatMapper extends BaseMapper<MemoryTopicStat> {

    /**
     * 对照 {@code BumpTopic} 的第一步："先插入、再自增"——这个形状让两个并发轮次
     * 不会都认为这个话题是新的。
     *
     * <p>插入的 {@code hits} 是 **0**，
     * 真正的 1 来自紧随其后的自增。</p>
     *
     * @return 1 = 真的插入了；0 = 已存在
     */
    @Insert("INSERT INTO memory_topic_stats "
            + "(id, tenant_id, subject_id, normalized_key, topic, aliases, hits, last_seen_at, "
            + " created_at, updated_at) "
            + "VALUES (#{s.id}, #{s.tenantId}, #{s.subjectId}, #{s.normalizedKey}, #{s.topic}, "
            + "        #{s.aliases, typeHandler=com.ragagent.memory.domain.MemoryStringListTypeHandler, "
            + "         jdbcType=OTHER}, "
            + "        #{s.hits}, #{s.lastSeenAt}, #{s.createdAt}, #{s.updatedAt}) "
            + "ON CONFLICT (tenant_id, subject_id, normalized_key) DO NOTHING")
    int insertIfAbsentPostgres(@Param("s") MemoryTopicStat stat);

    /** H2 没有 {@code ON CONFLICT}——等价的条件插入。{@code aliases} 写空列表（不是 NULL）。 */
    @Insert("INSERT INTO memory_topic_stats "
            + "(id, tenant_id, subject_id, normalized_key, topic, aliases, hits, last_seen_at, "
            + " created_at, updated_at) "
            + "SELECT #{s.id}, #{s.tenantId}, #{s.subjectId}, #{s.normalizedKey}, #{s.topic}, "
            + "       #{s.aliases, typeHandler=com.ragagent.memory.domain.MemoryStringListTypeHandler, "
            + "        jdbcType=OTHER}, "
            + "       #{s.hits}, #{s.lastSeenAt}, #{s.createdAt}, #{s.updatedAt} "
            + "WHERE NOT EXISTS (SELECT 1 FROM memory_topic_stats "
            + "  WHERE tenant_id = #{s.tenantId} AND subject_id = #{s.subjectId} "
            + "  AND normalized_key = #{s.normalizedKey})")
    int insertIfAbsentOther(@Param("s") MemoryTopicStat stat);

    /** 对照 {@code BumpTopic} 的自增：{@code hits = hits + 1} 必须在 SQL 侧做。 */
    @Update("UPDATE memory_topic_stats SET hits = hits + 1, last_seen_at = #{now}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} AND normalized_key = #{normalizedKey}")
    int bumpHits(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                 @Param("normalizedKey") String normalizedKey, @Param("now") OffsetDateTime now);

    /**
     * 对照 {@code BumpTopic} 记录别名的那一次 {@code Updates(map)}。
     *
     * <p>{@code aliases} 必须走类型处理器；写成裸 {@code #{aliases}} 的话，
     * MyBatis 会拿 {@code List} 找默认处理器，在 PG 上直接报类型不匹配。</p>
     */
    @Update("UPDATE memory_topic_stats SET "
            + "aliases = #{aliases, typeHandler=com.ragagent.memory.domain.MemoryStringListTypeHandler, "
            + "jdbcType=OTHER}, "
            + "updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} AND normalized_key = #{normalizedKey}")
    int updateAliases(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                      @Param("normalizedKey") String normalizedKey,
                      @Param("aliases") List<String> aliases, @Param("now") OffsetDateTime now);

    /**
     * 对照 {@code RenameTopic}：改标签、改 key、换一组别名。
     *
     * <p>调用方负责两件事：先确认新 key 没被别的行占（那个 {@code clash} 计数），
     * 以及把"要采纳的新说法"从别名里剔掉——否则规范标签会被列成它自己的别名。</p>
     */
    @Update("UPDATE memory_topic_stats SET topic = #{newLabel}, normalized_key = #{newKey}, "
            + "aliases = #{aliases, typeHandler=com.ragagent.memory.domain.MemoryStringListTypeHandler, "
            + "jdbcType=OTHER}, "
            + "updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} AND normalized_key = #{oldKey}")
    int rename(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
               @Param("oldKey") String oldKey, @Param("newKey") String newKey,
               @Param("newLabel") String newLabel, @Param("aliases") List<String> aliases,
               @Param("now") OffsetDateTime now);

    /** 对照 {@code MarkTopicPromoted}。 */
    @Update("UPDATE memory_topic_stats SET promoted_at = #{now}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} AND normalized_key = #{normalizedKey}")
    int markPromoted(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                     @Param("normalizedKey") String normalizedKey, @Param("now") OffsetDateTime now);

    /**
     * ⚠️ 自定义 {@code @Select} 的结果映射**不会**自动套实体上的
     * {@code @TableField(typeHandler=…)}（约定 §9）——{@code aliases} 必须在方法上再声明一次，
     * 否则读回来恒为 {@code null}（实体自带的 insert/updateById 路径不受影响，
     * 所以这个坑只在"库里明明有值、读出来却是 null"时才暴露）。
     *
     * <p>其余列交给 MyBatis 的自动映射（{@code mapUnderscoreToCamelCase} 已开）。</p>
     * <p>对照 {@code TopicByKey}。</p>
     */
    @Results({
            @Result(column = "aliases", property = "aliases",
                    typeHandler = com.ragagent.memory.domain.MemoryStringListTypeHandler.class),
    })
    @Select("SELECT * FROM memory_topic_stats WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND normalized_key = #{normalizedKey}")
    MemoryTopicStat selectByKey(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                                @Param("normalizedKey") String normalizedKey);

    /** 对照 {@code RenameTopic} 里的 {@code clash} 计数。 */
    @Select("SELECT COUNT(*) FROM memory_topic_stats WHERE tenant_id = #{tenantId} "
            + "AND subject_id = #{subjectId} AND normalized_key = #{normalizedKey}")
    long countByKey(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                    @Param("normalizedKey") String normalizedKey);

    /**
     * ⚠️ 自定义 {@code @Select} 的结果映射**不会**自动套实体上的
     * {@code @TableField(typeHandler=…)}（约定 §9）——{@code aliases} 必须在方法上再声明一次，
     * 否则读回来恒为 {@code null}（实体自带的 insert/updateById 路径不受影响，
     * 所以这个坑只在"库里明明有值、读出来却是 null"时才暴露）。
     *
     * <p>其余列交给 MyBatis 的自动映射（{@code mapUnderscoreToCamelCase} 已开）。</p>
     * <p>对照 {@code TopicByID}。</p>
     */
    @Results({
            @Result(column = "aliases", property = "aliases",
                    typeHandler = com.ragagent.memory.domain.MemoryStringListTypeHandler.class),
    })
    @Select("SELECT * FROM memory_topic_stats WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND id = #{id}")
    MemoryTopicStat selectScopedById(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                                     @Param("id") String id);

    /**
     * ⚠️ 自定义 {@code @Select} 的结果映射**不会**自动套实体上的
     * {@code @TableField(typeHandler=…)}（约定 §9）——{@code aliases} 必须在方法上再声明一次，
     * 否则读回来恒为 {@code null}（实体自带的 insert/updateById 路径不受影响，
     * 所以这个坑只在"库里明明有值、读出来却是 null"时才暴露）。
     *
     * <p>其余列交给 MyBatis 的自动映射（{@code mapUnderscoreToCamelCase} 已开）。</p>
     * <p>对照 {@code TopTopics}：{@code hits DESC, last_seen_at DESC}。</p>
     */
    @Results({
            @Result(column = "aliases", property = "aliases",
                    typeHandler = com.ragagent.memory.domain.MemoryStringListTypeHandler.class),
    })
    @Select("<script>"
            + "SELECT * FROM memory_topic_stats WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "ORDER BY hits DESC, last_seen_at DESC"
            + "<if test='limit &gt; 0'> LIMIT #{limit}</if>"
            + "</script>")
    List<MemoryTopicStat> topTopics(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                                    @Param("limit") int limit);

    /** 对照 {@code ListUnpromotedTopics} 的计数。 */
    @Select("SELECT COUNT(*) FROM memory_topic_stats WHERE tenant_id = #{tenantId} "
            + "AND subject_id = #{subjectId} AND promoted_at IS NULL")
    long countUnpromoted(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId);

    /**
     * 对照 {@code ListUnpromotedTopics}：{@code promoted_at IS NULL} 的那些，
     * 按"离阈值最近"排（= hits DESC）。
     */
    /**
     * ⚠️ 自定义 {@code @Select} 的结果映射**不会**自动套实体上的
     * {@code @TableField(typeHandler=…)}（约定 §9）——{@code aliases} 必须在方法上再声明一次，
     * 否则读回来恒为 {@code null}（实体自带的 insert/updateById 路径不受影响，
     * 所以这个坑只在"库里明明有值、读出来却是 null"时才暴露）。
     *
     * <p>其余列交给 MyBatis 的自动映射（{@code mapUnderscoreToCamelCase} 已开）。</p>
     */
    @Results({
            @Result(column = "aliases", property = "aliases",
                    typeHandler = com.ragagent.memory.domain.MemoryStringListTypeHandler.class),
    })
    @Select("SELECT * FROM memory_topic_stats WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND promoted_at IS NULL ORDER BY hits DESC, last_seen_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<MemoryTopicStat> listUnpromoted(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                                         @Param("limit") int limit, @Param("offset") int offset);

    /**
     * 对照 {@code DeleteTopic}：带 scope 的物理删。
     *
     * <p>⚠️ 不能用 MyBatis-Plus 的 {@code deleteById}——传一个别的主体的 id
     * 会真的删掉它的行。</p>
     */
    @Delete("DELETE FROM memory_topic_stats WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND id = #{id}")
    int deleteScoped(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                     @Param("id") String id);

    /** 对照 {@code DeleteAllTopics}：整 scope 的物理删（"清空记忆"必须包含计数器）。 */
    @Delete("DELETE FROM memory_topic_stats WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId}")
    int deleteAllInScope(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId);
}
