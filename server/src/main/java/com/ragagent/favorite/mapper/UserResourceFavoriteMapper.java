package com.ragagent.favorite.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.favorite.domain.UserResourceFavorite;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 收藏仓储（对照 Go {@code internal/application/repository/user_resource_favorite.go}）。
 *
 * <h2>为什么用纯 SQL 而不是 BaseMapper</h2>
 * <p>表是<b>复合主键</b> {@code (user_id, tenant_id, resource_type, resource_id)}，
 * MyBatis-Plus 的 {@code @TableId} 只支持单列；四条查询全部显式写 SQL
 * （对照 Go 侧同样显式 Where 条件）。无 jsonb 列，无需方法级 @Results。</p>
 */
@Mapper
public interface UserResourceFavoriteMapper {

    /** 对照 Go List：按 (user, tenant, type) 过滤，created_at DESC（新建在前）。 */
    @Select("SELECT user_id, tenant_id, resource_type, resource_id, created_at"
            + " FROM user_resource_favorites"
            + " WHERE user_id = #{userId} AND tenant_id = #{tenantId} AND resource_type = #{resourceType}"
            + " ORDER BY created_at DESC")
    List<UserResourceFavorite> list(String userId, Long tenantId, String resourceType);

    /** 对照 Go FirstOrCreate 的 First 段：四键全等查存在性。 */
    @Select("SELECT user_id, tenant_id, resource_type, resource_id, created_at"
            + " FROM user_resource_favorites"
            + " WHERE user_id = #{userId} AND tenant_id = #{tenantId}"
            + " AND resource_type = #{resourceType} AND resource_id = #{resourceId}")
    UserResourceFavorite find(String userId, Long tenantId, String resourceType, String resourceId);

    /** 对照 Go FirstOrCreate 的 Create 段：GORM autoCreateTime → 应用侧传 now。 */
    @Insert("INSERT INTO user_resource_favorites"
            + " (user_id, tenant_id, resource_type, resource_id, created_at)"
            + " VALUES (#{userId}, #{tenantId}, #{resourceType}, #{resourceId}, #{createdAt})")
    int insert(UserResourceFavorite favorite);

    /** 对照 Go Delete：返回受影响行数（0 = 幽灵删除，Handler 仍 200）。 */
    @Delete("DELETE FROM user_resource_favorites"
            + " WHERE user_id = #{userId} AND tenant_id = #{tenantId}"
            + " AND resource_type = #{resourceType} AND resource_id = #{resourceId}")
    int delete(String userId, Long tenantId, String resourceType, String resourceId);
}
