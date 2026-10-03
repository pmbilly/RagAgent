package com.ragagent.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.session.domain.Session;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * sessions 的 MyBatis-Plus 基础仓储。
 *
 * <p><b>为什么软删除是手写的 UPDATE 而不是 {@code @TableLogic}</b>：约定 §9 定下的做法——
 * datetime 逻辑删除值在 MyBatis-Plus 各版本行为敏感，显式条件语义确定。所以：
 * 每条 SELECT 自带 {@code deleted_at IS NULL}，删除一律 {@code UPDATE ... SET deleted_at}。</p>
 *
 * <p><b>为什么 {@code update_last_request_state} 单独一个方法</b>：它刻意绕开常规 Update 路径，**只写 agent_config + updated_at**，
 * 不碰 title/description——两个方法不能合并。</p>
 */
@Mapper
public interface SessionMapper extends BaseMapper<Session> {

    /**
     * 读取会话绑定的 IM 平台。
     *
     * <p>SQL：{@code im_channel_sessions ics JOIN sessions s ON s.id = ics.session_id
     * WHERE ics.session_id = ? AND s.tenant_id = ? LIMIT 1}。</p>
     *
     * <p><b>刻意不加软删除条件</b>：任何映射（活的或被清掉的）都表明这个会话
     * 来自 IM——所以 {@code im_channel_sessions.deleted_at} 不筛。{@code sessions} 那张表
     * 是内连接且只取 platform 列，也不筛。</p>
     *
     * @return 平台名；没有映射行时返回 null，由仓储归一为 ""
     */
    @Select("SELECT ics.platform FROM im_channel_sessions AS ics "
            + "JOIN sessions AS s ON s.id = ics.session_id "
            + "WHERE ics.session_id = #{sessionId} AND s.tenant_id = #{tenantId} LIMIT 1")
    String selectImPlatform(@Param("tenantId") long tenantId, @Param("sessionId") String sessionId);

    /**
     * 会话存在性（沙箱绑定回收用，
     * "session 行还在吗"语义：tenant + id + 软删过滤）。生命周期协调器在回收
     * 孤儿绑定时调用——会话已消失才允许删除 provider 资源。
     */
    @Select("SELECT COUNT(1) FROM sessions "
            + "WHERE tenant_id = #{tenantId} AND id = #{sessionId} AND deleted_at IS NULL")
    long countSessionExists(@Param("tenantId") long tenantId, @Param("sessionId") String sessionId);



    /**
     * 写 {@code agent_config}（承载 {@code SessionLastRequestState}）+ {@code updated_at}。
     *
     * <p>jsonb 列必须显式挂 {@code PgJsonTypeHandler}：MyBatis-Plus 的 {@code UpdateWrapper.set()}
     * 走的是无名参数，类型处理器不会生效，PG 会以 "column is of type jsonb but expression is of
     * type character varying" 拒绝。</p>
     *
     * <p>{@code state} 为 null 时 Go 写的是 SQL NULL（{@code state.Value()} 返回 nil），
     * 所以这里也用 {@code jdbcType=OTHER} 让 PG 接受 jsonb 列的 NULL。</p>
     *
     * <p>{@code userScoped} 是 Go {@code applySessionUserScope} 在这个方法里的那一份
     * （L355-357）：userID 非空时才加
     * {@code (user_id = ? OR user_id IS NULL OR user_id = '')}。</p>
     */
    @Update("<script>"
            + "UPDATE sessions SET "
            + "agent_config = #{state, typeHandler=com.ragagent.common.web.PgJsonTypeHandler, "
            + "jdbcType=OTHER}, "
            + "updated_at = #{updatedAt} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_at IS NULL "
            + "<if test='userScoped'>"
            + "  AND (user_id = #{userId} OR user_id IS NULL OR user_id = '')"
            + "</if>"
            + "</script>")
    int updateLastRequestState(@Param("tenantId") long tenantId,
                               @Param("id") String id,
                               @Param("state") Object state,
                               @Param("updatedAt") java.time.OffsetDateTime updatedAt,
                               @Param("userScoped") boolean userScoped,
                               @Param("userId") String userId);

    // ── 列表查询 ──────────

    /**
     * 列表查询的 FROM + WHERE，两条 SQL（count / rows）共用一份。
     *
     * <p>注意几处刻意的写法：</p>
     * <ul>
     *   <li>{@code ics.id IS NULL} 的 {@code web}/{@code embed} 桶、以及
     *       {@code d.description} 的 NOT LIKE——**软删除的 IM 映射也要算数**
     *       （一个曾经绑过 IM 的会话就属于那个平台，/clear 会软删映射并另起会话）。</li>
     *   <li>{@code s.deleted_at IS NULL} 手写：这条查询走的是 {@code Table(...)}，
     *       框架的自动软删不会介入。</li>
     *   <li>{@code keyword} 的 LIKE 转义（{@code \%} / {@code \_} / {@code \\}）在仓储层
     *       做好再传进来（{@code escapeLikeKeyword} 之后拼 {@code %...%}）。</li>
     * </ul>
     */
    String PAGED_FROM_WHERE = """
            FROM sessions AS s
            LEFT JOIN im_channel_sessions ics ON ics.session_id = s.id
            <where>
              s.tenant_id = #{q.tenantId} AND s.deleted_at IS NULL
              <if test="q.userId != null and q.userId != ''">
                AND (s.user_id = #{q.userId} OR s.user_id IS NULL OR s.user_id = '')
              </if>
              AND (s.description IS NULL OR s.description NOT LIKE #{skillMarker})
              <if test="keywordLike != null">
                AND <choose>
                  <when test="postgres">s.title ILIKE #{keywordLike}</when>
                  <otherwise>LOWER(s.title) LIKE LOWER(#{keywordLike})</otherwise>
                </choose>
              </if>
              <if test="src != null and src != ''">
                <choose>
                  <when test="src == 'api'">
                    AND (s.user_id LIKE #{apiTenantLike} OR s.user_id LIKE #{apiExternalLike})
                  </when>
                  <when test="src == 'web'">
                    AND (ics.id IS NULL AND (s.description = '' OR s.description NOT LIKE #{embedLike})
                         AND (s.user_id IS NULL
                              OR (s.user_id NOT LIKE #{apiTenantLike}
                                  AND s.user_id NOT LIKE #{apiExternalLike})))
                  </when>
                  <when test="src == 'embed'">
                    AND (ics.id IS NULL AND s.description LIKE #{embedLike})
                  </when>
                  <when test="channelDesc != null">
                    AND (ics.id IS NULL AND s.description = #{channelDesc})
                  </when>
                  <otherwise>
                    AND ics.platform = #{src}
                  </otherwise>
                </choose>
              </if>
              <if test="q.agentId != null and q.agentId != ''">
                AND ics.agent_id = #{q.agentId}
              </if>
            </where>
            """;

    /**
     * 列表总数。
     *
     * <p>用 {@code COUNT(DISTINCT s.id)} 而不是 {@code COUNT(*)}：LEFT JOIN 理论上可能
     * 扇出（当前不会发生；一旦"重映射已有会话"就需要加一行一会话的守卫）。</p>
     */
    @Select("<script>SELECT COUNT(DISTINCT s.id) " + PAGED_FROM_WHERE + "</script>")
    long countPaged(@Param("q") com.ragagent.session.domain.SessionListQuery q,
                    @Param("postgres") boolean postgres,
                    @Param("keywordLike") String keywordLike,
                    @Param("src") String src,
                    @Param("channelDesc") String channelDesc,
                    @Param("embedLike") String embedLike,
                    @Param("apiTenantLike") String apiTenantLike,
                    @Param("apiExternalLike") String apiExternalLike,
                    @Param("skillMarker") String skillMarker);

    /**
     * 列表数据页。
     *
     * <p>{@code agent_config} 必须显式挂 {@code PgJsonTypeHandler}：自定义 {@code @Select}
     * 的结果映射不会自动套实体的 {@code @TableField(typeHandler=...)}，得在 {@code @Results}
     * 里再声明一次，否则这一列会被当成裸字符串塞给 {@code SessionLastRequestState} 而炸。</p>
     *
     * <p>排序差异（{@code NULLS LAST}）用内联 {@code <if>} 表达，不用 {@code ${}} 拼接——
     * 少一处字符串注入面。</p>
     */
    @Select("<script>"
            + "SELECT s.id, s.tenant_id, s.title, s.description, s.user_id, s.is_pinned, "
            + "s.pinned_at, s.agent_config, s.created_at, s.updated_at, "
            + "s.deleted_at, "
            + "ics.platform AS im_platform, ics.chat_id AS im_chat_id, "
            + "ics.thread_id AS im_thread_id, ics.user_id AS im_user_id, "
            + "ics.agent_id AS im_agent_id, ics.im_channel_id AS im_channel_id "
            + PAGED_FROM_WHERE
            + "ORDER BY s.is_pinned DESC, s.pinned_at DESC "
            + "<if test='postgres'>NULLS LAST </if>"
            + ", s.updated_at DESC "
            + "LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    @Results({
            @Result(column = "id", property = "id", id = true),
            // ⚠️ is_pinned 必须显式映射：实体属性名是 pinned（字段名刻意去掉 is 前缀，
            // 见 Session.pinned 的注释），自动映射按 is_pinned → "isPinned" 找不到属性，
            // 列表里的置顶态会恒为 false（golden 契约测试抓到的真实缺陷）。
            @Result(column = "is_pinned", property = "pinned"),
            // jsonb 列要显式挂类型处理器（见方法注释）
            @Result(column = "agent_config", property = "lastRequestState",
                    typeHandler = com.ragagent.common.web.PgJsonTypeHandler.class)
    })
    java.util.List<com.ragagent.session.domain.SessionListItem> queryPaged(
            @Param("q") com.ragagent.session.domain.SessionListQuery q,
            @Param("postgres") boolean postgres,
            @Param("keywordLike") String keywordLike,
            @Param("src") String src,
            @Param("channelDesc") String channelDesc,
            @Param("embedLike") String embedLike,
            @Param("apiTenantLike") String apiTenantLike,
            @Param("apiExternalLike") String apiExternalLike,
            @Param("skillMarker") String skillMarker,
            @Param("limit") int limit,
            @Param("offset") int offset);
}
