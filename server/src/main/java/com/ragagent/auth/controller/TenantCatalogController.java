package com.ragagent.auth.controller;

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
 * 对照 Go handler/tenant.go 的跨空间租户目录 + KV 配置分发器（波 2 扫尾批 3，
 * routes_auth_tenant.go L53-76 五条路由）：
 *
 * <ul>
 *   <li>GET  /tenants/all     —— ListAllTenants（L1192-1216），viewer 形态裁剪</li>
 *   <li>GET  /tenants/search  —— SearchTenants（L1218-1280），分页 + keyword/tenant_id</li>
 *   <li>POST /tenants         —— CreateTenant（L226-513）：自助/超管双路径、配额、
 *       owner 引导、tenantless 回填、auto_create_api_key 兼容</li>
 *   <li>GET/PUT /tenants/kv/{key} —— KV 分发器（L1304-1395），6 个 DB-backed key
 *       + GET prompt-templates（走查补翻：PromptTemplateCatalog 装载 vendored
 *       yaml + LocalizeTemplates 本地化；PUT 分发器 Go 本来就没有它 → 400）</li>
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
     *  仅供 tenantWithApiKey 把实体转成与 Go 字节同形态的时间串 */
    final ObjectMapper springMapper;

    /** 创建租户协作者（对照 Go CreateTenant 段）。 */
    final TenantCreateOps createOps;

    /** tenants CRUD 协作者（对照 Go W5a 段）。 */
    final TenantCrudOps crudOps;

    /** KV 配置分发协作者（对照 Go GetTenantKV/UpdateTenantKV 段）。 */
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
    public ResponseEntity<Map<String, Object>> createTenant(
            @RequestBody(required = false) String rawBody) {
        return createOps.createTenant(rawBody);
    }


    @GetMapping("/api/v1/tenants")
    public Map<String, Object> listTenants() {
        return crudOps.listTenants();
    }

    @GetMapping("/api/v1/tenants/{id}")
    public Map<String, Object> getTenant(@PathVariable("id") String id) {
        return crudOps.getTenant(id);
    }

    @PutMapping("/api/v1/tenants/{id}")
    public Map<String, Object> updateTenant(@PathVariable("id") String id,
                                            @RequestBody(required = false) String rawBody) {
        return crudOps.updateTenant(id, rawBody);
    }

    @DeleteMapping("/api/v1/tenants/{id}")
    public Map<String, Object> deleteTenant(@PathVariable("id") String id) {
        return crudOps.deleteTenant(id);
    }

    @GetMapping("/api/v1/tenants/kv/{key}")
    public Map<String, Object> getTenantKV(@PathVariable String key,
                                           jakarta.servlet.http.HttpServletRequest request) {
        return configOps.getTenantKV(key, request);
    }

    @PutMapping("/api/v1/tenants/kv/{key}")
    public Map<String, Object> updateTenantKV(@PathVariable String key,
                                              @RequestBody(required = false) String rawBody) {
        return configOps.updateTenantKV(key, rawBody);
    }

}
