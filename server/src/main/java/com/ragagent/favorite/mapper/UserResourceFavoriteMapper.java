package com.ragagent.favorite.mapper;

import java.util.List;

import com.ragagent.favorite.domain.UserResourceFavorite;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 收藏仓储。
 *
 * <h2>为什么用纯 SQL 而不是 BaseMapper</h2>
 * <p>表是<b>复合主键</b> {@code (user_id, tenant_id, resource_type, resource_id)}，
 * MyBatis-Plus 的 {@code @TableId} 只支持单列；四条查询全部显式写 SQL。
 * 无 jsonb 列，无需方法级 @Results。</p>
 */
@Mapper
public interface UserResourceFavoriteMapper {

    /** 按 (user, tenant, type) 过滤，created_at DESC（新建在前）。 */
    @Select("SELECT user_id, tenant_id, resource_type, resource_id, created_at"
            + " FROM user_resource_favorites"
            + " WHERE user_id = #{userId} AND tenant_id = #{tenantId} AND resource_type = #{resourceType}"
            + " ORDER BY created_at DESC")
    List<UserResourceFavorite> list(String userId, Long tenantId, String resourceType);

    /** 先查段：四键全等查存在性（与 insert 组成先查后插的幂等写入）。 */
    @Select("SELECT user_id, tenant_id, resource_type, resource_id, created_at"
            + " FROM user_resource_favorites"
            + " WHERE user_id = #{userId} AND tenant_id = #{tenantId}"
            + " AND resource_type = #{resourceType} AND resource_id = #{resourceId}")
    UserResourceFavorite find(String userId, Long tenantId, String resourceType, String resourceId);

    /** 后插段：created_at 由应用侧传 now。 */
    @Insert("INSERT INTO user_resource_favorites"
            + " (user_id, tenant_id, resource_type, resource_id, created_at)"
            + " VALUES (#{userId}, #{tenantId}, #{resourceType}, #{resourceId}, #{createdAt})")
    int insert(UserResourceFavorite favorite);

    /** 返回受影响行数（0 = 幽灵删除，调用方仍按成功处理）。 */
    @Delete("DELETE FROM user_resource_favorites"
            + " WHERE user_id = #{userId} AND tenant_id = #{tenantId}"
            + " AND resource_type = #{resourceType} AND resource_id = #{resourceId}")
    int delete(String userId, Long tenantId, String resourceType, String resourceId);
}
