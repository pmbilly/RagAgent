package com.ragagent.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.session.domain.Session;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * sessions 的 MyBatis-Plus 基础仓储（对照 Go
 * internal/application/repository/session.go 的 {@code sessionRepository}，整个文件）。
 *
 * <p><b>为什么软删除是手写的 UPDATE 而不是 {@code @TableLogic}</b>：约定 §9 定下的做法——
 * datetime 逻辑删除值在 MyBatis-Plus 各版本行为敏感，显式条件语义确定。所以：
 * 每条 SELECT 自带 {@code deleted_at IS NULL}，删除一律 {@code UPDATE ... SET deleted_at}。</p>
 *
 * <p><b>为什么 {@code update_last_request_state} 单独一个方法</b>：Go 的
 * {@code UpdateLastRequestState} 刻意绕开常规 Update 路径，**只写 agent_config + updated_at**，
 * 不碰 title/description——两个方法不能合并。</p>
 */
@Mapper
public interface SessionMapper extends BaseMapper<Session> {

    /**
     * 读取会话绑定的 IM 平台（对照 Go {@code GetIMPlatform}，L78-92）。
     *
     * <p>SQL 逐字对照：{@code im_channel_sessions ics JOIN sessions s ON s.id = ics.session_id
     * WHERE ics.session_id = ? AND s.tenant_id = ? LIMIT 1}，{@code Pluck("ics.platform")}。</p>
     *
     * <p><b>刻意不加软删除条件</b>：Go 的注释写明"任何映射（活的或被清掉的）都表明这个会话
     * 来自 IM"——所以 {@code im_channel_sessions.deleted_at} 不筛。{@code sessions} 那张表
     * 是内连接且只取 platform 列，也不筛。</p>
     *
     * @return 平台名；没有映射行时 Go 的 Pluck 不报错、留空串，Java 返回 null 由仓储归一为 ""
     */
    @Select("SELECT ics.platform FROM im_channel_sessions AS ics "
            + "JOIN sessions AS s ON s.id = ics.session_id "
            + "WHERE ics.session_id = #{sessionId} AND s.tenant_id = #{tenantId} LIMIT 1")
    String selectImPlatform(@Param("tenantId") long tenantId, @Param("sessionId") String sessionId);

    /**
     * 写 {@code agent_config}（承载 {@code SessionLastRequestState}）+ {@code updated_at}
     * （对照 Go {@code UpdateLastRequestState}，L342-363）。
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
}
