package com.ragagent.favorite.controller;

import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.favorite.domain.UserResourceFavorite;
import com.ragagent.favorite.service.UserResourceFavoriteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户收藏的 HTTP 层：3 条路由——GET/POST {@code /api/v1/user/favorites} +
 * DELETE {@code /api/v1/user/favorites/{type}/{id}}。
 *
 * <h2>授权模型</h2>
 * <p>handler 永远从 auth 上下文推导 (user_id, tenant_id)，没有"看别人收藏"的路径；
 * favorites 属于<b>做收藏动作的人</b>而非资源创建者，不走 OwnedXOrAdmin。
 * Viewer+ 即可，API key 默认拒绝（路由未对 API key 声明）。</p>
 *
 * <h2>响应形态</h2>
 * <ul>
 *   <li>列表：裸数组；空结果序列化为 {@code []} 而非 null</li>
 *   <li>add：201 无响应体；remove：204（幽灵删除也是 204）</li>
 * </ul>
 *
 * <h2>错误形态：AppError + binding 细节进 details</h2>
 * <pre>
 *   非法 body → 400 message="invalid request body" details=解码措辞（EOF / invalid character…）
 *   非法类型  → 400 message="invalid favorite resource type" details=null
 *   空 id     → 400 message="favorite resource id is required" details=null
 * </pre>
 * <p>auth 中间件保证租户与 principal 之后，401 防御分支（"user ID not found" /
 * "workspace ID not found"）<b>不可达</b>；同位保留。</p>
 */
@RestController
public class UserFavoriteController {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final UserResourceFavoriteService service;

    public UserFavoriteController(UserResourceFavoriteService service) {
        this.service = service;
    }

 /** 列表 = 裸数组（§2.1）；?type= 缺失/非法 → 400（空串不命中白名单）。 */
    @GetMapping("/api/v1/user/favorites")
    public ResponseEntity<List<UserResourceFavorite>> listFavorites(@RequestParam(required = false) String type) {
        String userId = favoriteUserId();
        Long tenantId = favoriteTenantId();
        return ResponseEntity.ok(service.list(userId, tenantId, type));
    }

    /** 添加收藏：body 是 {type,id}，创建成功 201（§2.1；绑定文案见类注释）。 */
    @PostMapping("/api/v1/user/favorites")
    public ResponseEntity<Void> addFavorite(@RequestBody(required = false) String rawBody) {
        AddFavoriteRequest req = bindBody(rawBody);
        service.add(favoriteUserId(), favoriteTenantId(), req.type, req.id);
        return ResponseEntity.status(201).build();
    }

    /** 移除收藏：类型/id 校验失败 400；删 0 行照样成功 → 204（§1.13）。 */
    @DeleteMapping("/api/v1/user/favorites/{type}/{id}")
    public ResponseEntity<Void> removeFavorite(@PathVariable String type, @PathVariable String id) {
        service.remove(favoriteUserId(), favoriteTenantId(), type, id);
        return ResponseEntity.noContent().build();
    }

    /** 请求体键为小写 {@code type}/{@code id}。 */
    private record AddFavoriteRequest(String type, String id) {
    }

    private AddFavoriteRequest bindBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw invalidBody("EOF");
        }
        try {
            return MAPPER.readValue(rawBody, AddFavoriteRequest.class);
        } catch (Exception e) {
            throw invalidBody(GoJsonBindError.message(rawBody, e.getMessage()));
        }
    }

    /** 400 "invalid request body"，解码错误措辞进 details。 */
    private static BizException invalidBody(String details) {
        return new BizException(AppError.badRequest("invalid request body").withDetails(details));
    }

    /** userId 缺失 → 401 "user ID not found"（防御位，中间件完备时不可达）。 */
    private static String favoriteUserId() {
        String userId = TenantContext.currentUserId();
        if (userId == null || userId.isEmpty()) {
            throw BizException.unauthorized("user ID not found");
        }
        return userId;
    }

    /** tenant 缺失 → 401 "workspace ID not found"（防御位，中间件完备时不可达）。 */
    private static Long favoriteTenantId() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            throw BizException.unauthorized("workspace ID not found");
        }
        return tenantId;
    }
}
