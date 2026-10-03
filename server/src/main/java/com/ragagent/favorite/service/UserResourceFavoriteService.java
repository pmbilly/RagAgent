package com.ragagent.favorite.service;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.common.error.BizException;
import com.ragagent.favorite.domain.UserResourceFavorite;
import com.ragagent.favorite.mapper.UserResourceFavoriteMapper;
import org.springframework.stereotype.Service;

/**
 * 收藏 service。
 *
 * <p>刻意保持薄：收藏是非业务动作，不进审计、无跨聚合副作用；
 * service 只做输入校验（类型白名单 + 非空 id），错误文案固定为：</p>
 * <pre>
 *   ErrFavoriteInvalidType → "invalid favorite resource type"
 *   ErrFavoriteEmptyID     → "favorite resource id is required"
 * </pre>
 * <p>这两类校验失败映射为 400，其余错误 500。仓库层错误直接抛
 * （DataAccessException），由全局兜底成 500。</p>
 */
@Service
public class UserResourceFavoriteService {

    private final UserResourceFavoriteMapper mapper;

    public UserResourceFavoriteService(UserResourceFavoriteMapper mapper) {
        this.mapper = mapper;
    }

    /** 类型必须命中白名单，否则 400。 */
    public List<UserResourceFavorite> list(String userId, Long tenantId, String resourceType) {
        requireValidType(resourceType);
        return mapper.list(userId, tenantId, resourceType);
    }

    /** 先校验再插入（已存在则幂等跳过，不报错）。 */
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
     * 删不存在的行不报错（repo 返回 0）——幽灵删除同样按成功处理，接口返回 204
     * （契约金片钉住）。
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
