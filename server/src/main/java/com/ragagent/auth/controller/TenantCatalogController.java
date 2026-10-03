package com.ragagent.auth.controller;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.apikey.service.TenantAPIKeyService;
import com.ragagent.auth.service.TenantMemberService;
import com.ragagent.auth.service.TenantService;
import com.ragagent.auth.service.UserService;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.common.tenant.TenantProperties;
import com.ragagent.common.knowledge.KnowledgeBaseProvisioner;
import com.ragagent.common.storage.StorageAllowList;
import com.ragagent.common.settings.SystemSettingGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨空间租户目录 + KV 配置分发器（五条路由）：
 *
 * <ul>
 *   <li>GET  /tenants/all     —— 全量租户列表，viewer 形态裁剪</li>
 *   <li>GET  /tenants/search  —— 分页搜索 + keyword/tenant_id</li>
 *   <li>POST /tenants         —— 创建：自助/超管双路径、配额、
 *       owner 引导、tenantless 回填、auto_create_api_key 兼容</li>
 *   <li>GET/PUT /tenants/kv/{key} —— KV 分发器，6 个 DB-backed key
 *       + GET prompt-templates（PromptTemplateCatalog 装载 vendored
 *       yaml + 本地化；PUT 分发器不支持该 key → 400）</li>
 * </ul>
 *
 * <p>跨空间守卫（all/search）在 {@code RbacInterceptor.addCrossTenantRule}；
 * 角色下限（kv GET=Viewer+、PUT=Admin+）在 {@code WebConfig}；
 * 三条敏感 key 的 admin 门在本类 {@link #canViewIntegrationSecrets()}。</p>
 */
@RestController
public class TenantCatalogController {
    final TenantService tenantService;
    final TenantMemberService memberService;
    final UserService userService;
    final SystemSettingGateway systemSettingService;
    final TenantAPIKeyService apiKeyService;
    final KnowledgeBaseProvisioner knowledgeProvisioner;
    final TenantProperties tenantProperties;
    final SsrfGuard ssrfGuard;
    final StorageAllowList storageAllowList;
    /** Spring 全局 mapper（带 JacksonConfig 的 OffsetDateTime→本地时区序列化），
     *  仅供 tenantWithApiKey 把实体转成既定形态的时间串 */
    final ObjectMapper springMapper;

    /** 创建租户协作者。 */
    final TenantCreateOps createOps;

    /** tenants CRUD 协作者。 */
    final TenantCrudOps crudOps;

    /** KV 配置分发协作者。 */
    final TenantConfigOps configOps;

    public TenantCatalogController(TenantService tenantService,
                                   TenantMemberService memberService,
                                   UserService userService,
                                   SystemSettingGateway systemSettingService,
                                   TenantAPIKeyService apiKeyService,
                                   KnowledgeBaseProvisioner knowledgeProvisioner,
                                   TenantProperties tenantProperties,
                                   SsrfGuard ssrfGuard,
                                   StorageAllowList storageAllowList,
                                   ObjectMapper springMapper) {
        this.tenantService = tenantService;
        this.memberService = memberService;
        this.userService = userService;
        this.systemSettingService = systemSettingService;
        this.apiKeyService = apiKeyService;
        this.knowledgeProvisioner = knowledgeProvisioner;
        this.tenantProperties = tenantProperties;
        this.ssrfGuard = ssrfGuard;
        this.storageAllowList = storageAllowList;
        this.springMapper = springMapper;
        this.createOps = new TenantCreateOps(this);
        this.crudOps = new TenantCrudOps(this);
        this.configOps = new TenantConfigOps(this);
    }


    @PostMapping("/api/v1/tenants")
    public ResponseEntity<?> createTenant(
            @RequestBody(required = false) String rawBody) {
        return createOps.createTenant(rawBody);
    }


    @GetMapping("/api/v1/tenants")
    public Map<String, Object> listTenants() {
        return crudOps.listTenants();
    }

    /** GET /tenants/search —— 分页 + keyword/tenantId（裸分页形态 {items,total,page,pageSize}）。 */
    @GetMapping("/api/v1/tenants/search")
    public Map<String, Object> searchTenants(
            @org.springframework.web.bind.annotation.RequestParam(value = "keyword", required = false) String keyword,
            @org.springframework.web.bind.annotation.RequestParam(value = "tenantId", required = false) Long tenantId,
            @org.springframework.web.bind.annotation.RequestParam(value = "page", defaultValue = "1") int page,
            @org.springframework.web.bind.annotation.RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        var result = tenantService.searchTenants(keyword == null ? "" : keyword,
                tenantId == null ? 0 : tenantId, page, pageSize);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("items", result.tenants());
        body.put("total", result.total());
        body.put("page", page);
        body.put("pageSize", pageSize);
        return body;
    }

    /** GET /tenants/all —— 全量目录（跨空间访问权由守卫组承担；无分页）。 */
    @GetMapping("/api/v1/tenants/all")
    public List<com.ragagent.auth.domain.Tenant> listAllTenants() {
        return tenantService.searchTenants("", 0, 0, 0).tenants();
    }

    @GetMapping("/api/v1/tenants/{id}")
    public Object getTenant(@PathVariable("id") String id) {
        return crudOps.getTenant(id);
    }

    @PutMapping("/api/v1/tenants/{id}")
    public Object updateTenant(@PathVariable("id") String id,
                                            @RequestBody(required = false) String rawBody) {
        return crudOps.updateTenant(id, rawBody);
    }

    @DeleteMapping("/api/v1/tenants/{id}")
    public Object deleteTenant(@PathVariable("id") String id) {
        return crudOps.deleteTenant(id);
    }

    @GetMapping("/api/v1/tenants/kv/{key}")
    public Object getTenantKV(@PathVariable String key,
                              jakarta.servlet.http.HttpServletRequest request) {
        return configOps.getTenantKV(key, request);
    }

    @PutMapping("/api/v1/tenants/kv/{key}")
    public Object updateTenantKV(@PathVariable String key,
                                 @RequestBody(required = false) String rawBody) {
        return configOps.updateTenantKV(key, rawBody);
    }

}
