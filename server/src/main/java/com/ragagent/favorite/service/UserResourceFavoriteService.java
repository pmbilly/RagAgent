package com.ragagent.favorite.service;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.common.error.BizException;
import com.ragagent.favorite.domain.UserResourceFavorite;
import com.ragagent.favorite.mapper.UserResourceFavoriteMapper;
import org.springframework.stereotype.Service;

/**
 * 收藏 service（对照 Go {@code internal/application/service/user_resource_favorite.go}）。
 *
 * <p>Go 侧刻意保持薄：收藏是非业务动作，不进审计、无跨聚合副作用；
 * service 只做输入校验（类型白名单 + 非空 id），错误串逐字对照 Go 的
 * sentinel error：</p>
 * <pre>
 *   ErrFavoriteInvalidType → "invalid favorite resource type"
 *   ErrFavoriteEmptyID     → "favorite resource id is required"
 * </pre>
 * <p>Handler 把这两类映射为 400（信封 code 1000），其余 500。仓库层错误在
 * Java 侧直接抛（DataAccessException），由全局兜底成 500——与 Go 的
 * NewInternalServerError(err.Error()) 语义一致。</p>
 */
@Service
public class UserResourceFavoriteService {

    private final UserResourceFavoriteMapper mapper;

    public UserResourceFavoriteService(UserResourceFavoriteMapper mapper) {
        this.mapper = mapper;
    }

    /** 对照 Go List：类型必须命中白名单，否则 400。 */
    public List<UserResourceFavorite> list(String userId, Long tenantId, String resourceType) {
        requireValidType(resourceType);
        return mapper.list(userId, tenantId, resourceType);
    }

    /** 对照 Go Add：先校验再 upsert（已存在则幂等跳过，不报错）。 */
    public void add(String userId, Long tenantId, String resourceType, String resourceId) {
        requireValidType(resourceType);
        requireNonEmptyId(resourceId);
        if (mapper.find(userId, tenantId, resourceType, resourceId) != null) {
            return;
        }
        UserResourceFavorite favorite = new UserResourceFavorite();
        favorite.setUserId(userId);
        favorite.setTenantId(tenantId);
        favorite.setResourceType(resourceType);
        favorite.setResourceId(resourceId);
        favorite.setCreatedAt(OffsetDateTime.now());
        mapper.insert(favorite);
    }

    /**
     * 对照 Go Remove：删不存在的行不报错（repo 返回 false，Handler 照样 200
     * ——golden fav-remove-ghost.json 已钉：幽灵删除也是 {@code {"success":true}}）。
     */
    public void remove(String userId, Long tenantId, String resourceType, String resourceId) {
        requireValidType(resourceType);
        requireNonEmptyId(resourceId);
        mapper.delete(userId, tenantId, resourceType, resourceId);
    }

    private static void requireValidType(String resourceType) {
        if (!UserResourceFavorite.isValidResourceType(resourceType)) {
            throw BizException.badRequest("invalid favorite resource type");
        }
    }

    private static void requireNonEmptyId(String resourceId) {
        if (resourceId == null || resourceId.strip().isEmpty()) {
            throw BizException.badRequest("favorite resource id is required");
        }
    }
}
