package com.ragagent.model.controller;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.common.tenant.TenantRole;
import com.ragagent.common.web.NonNullBody;
import com.ragagent.common.web.RejectEmptyBody;
import com.ragagent.model.domain.Model;
import com.ragagent.model.domain.ModelParameters;
import com.ragagent.model.dto.CreateModelRequest;
import com.ragagent.model.dto.ModelProviderDTO;
import com.ragagent.model.dto.ModelResponse;
import com.ragagent.model.dto.UpdateModelRequest;
import com.ragagent.model.service.ModelService;
import com.ragagent.model.service.ModelService.ModelNotFoundException;
import com.ragagent.model.service.ProviderRegistry;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型配置端点（CRUD + providers；写操作 = Admin+，读 = Viewer+）。
 *
 * <p>请求体为标准 DTO（字段名即 JSON 键名，camelCase）；响应直接返回
 * {@link ModelResponse} 或列表（无信封）；删除为 HTTP 204 无响应体。
 * 校验失败/空体/未找到/SSRF 拒绝统一走 AppError 信封。</p>
 */
@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

    private static final Logger log = LoggerFactory.getLogger(ModelController.class);

    private final ModelService modelService;
    private final ProviderRegistry providerRegistry;
    private final SsrfGuard ssrfGuard;

    public ModelController(ModelService modelService, ProviderRegistry providerRegistry,
                           SsrfGuard ssrfGuard) {
        this.modelService = modelService;
        this.providerRegistry = providerRegistry;
        this.ssrfGuard = ssrfGuard;
    }

    /** 创建模型（Admin+）。 */
    @PostMapping
    public ResponseEntity<ModelResponse> createModel(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false) CreateModelRequest req) {
        log.info("Start creating model");
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            throw new BizException(AppError.badRequest("Workspace ID cannot be empty"));
        }
        ModelParameters parameters = req.parameters().toDomain();
        // SSRF 校验
        if (!parameters.getBaseUrl().isEmpty()) {
            try {
                ssrfGuard.validateURLForSSRF(parameters.getBaseUrl());
            } catch (SsrfGuard.SsrfException e) {
                log.warn("SSRF validation failed for model BaseURL: {}", e.getMessage());
                throw new BizException(AppError.badRequest(
                        ssrfGuard.formatSSRFError("Base URL", parameters.getBaseUrl(), e)));
            }
        }

        Model model = new Model();
        model.setTenantId(tenantId);
        model.setName(req.name());
        model.setDisplayName(req.displayName());
        model.setType(req.type());
        model.setSource(req.source());
        model.setDescription(req.description());
        model.setParameters(parameters);
        model = modelService.createModel(model);
        log.info("Model created successfully, ID: {}, Name: {}", model.getId(), model.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ModelResponse.from(model, canViewIntegrationSecrets(), canManageBuiltin(model)));
    }

    /** 读取模型（Viewer+）。 */
    @GetMapping("/{id}")
    public ResponseEntity<ModelResponse> getModel(@PathVariable("id") String id) {
        log.info("Start retrieving model");
        if (isBlank(id)) {
            throw new BizException(AppError.badRequest("Model ID cannot be empty"));
        }
        Model model;
        try {
            model = modelService.getModelByID(id);
        } catch (ModelNotFoundException e) {
            log.warn("Model not found, ID: {}", id);
            throw new BizException(AppError.notFound("Model not found"));
        }
        return ResponseEntity.ok(ModelResponse.from(model, canViewIntegrationSecrets(), canManageBuiltin(model)));
    }

    /** 模型列表（Viewer+）。 */
    @GetMapping
    public ResponseEntity<List<ModelResponse>> listModels() {
        log.info("Start retrieving model list");
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            throw new BizException(AppError.badRequest("Workspace ID cannot be empty"));
        }
        List<Model> models = modelService.listModels();
        List<ModelResponse> data = new ArrayList<>(models.size());
        for (Model m : models) {
            data.add(ModelResponse.from(m, canViewIntegrationSecrets(), canManageBuiltin(m)));
        }
        return ResponseEntity.ok(data);
    }

    /**
     * 更新模型（Admin+；内置模型 SystemAdmin）。
     * 凭证快照保留 + 后端托管字段保留；type/source/description/parameters 无条件覆盖。
     */
    @PutMapping("/{id}")
    public ResponseEntity<ModelResponse> updateModel(@PathVariable("id") String id,
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false) UpdateModelRequest req) {
        log.info("Start updating model");
        if (isBlank(id)) {
            throw new BizException(AppError.badRequest("Model ID cannot be empty"));
        }
        Model model;
        try {
            model = modelService.getModelByID(id);
        } catch (ModelNotFoundException e) {
            log.warn("Model not found, ID: {}", id);
            throw new BizException(AppError.notFound("Model not found"));
        }

        if (req.name() != null && !req.name().isEmpty()) {
            model.setName(req.name());
        }
        if (req.displayName() != null) {
            model.setDisplayName(req.displayName());
        }
        model.setDescription(req.description());

        if (req.parameters() != null && req.parameters().baseUrl() != null
                && !req.parameters().baseUrl().isEmpty()) {
            try {
                ssrfGuard.validateURLForSSRF(req.parameters().baseUrl());
            } catch (SsrfGuard.SsrfException e) {
                log.warn("SSRF validation failed for model BaseURL: {}", e.getMessage());
                throw new BizException(AppError.badRequest(
                        ssrfGuard.formatSSRFError("Base URL", req.parameters().baseUrl(), e)));
            }
        }
        // 凭证永不经 PUT 正文：快照保留
        var stored = model.getParameters();
        String storedApiKey = stored.getApiKey();
        String storedAppSecret = stored.getAppSecret();
        ModelParameters newParams = req.parameters() != null
                ? req.parameters().toDomain() : new ModelParameters();
        if (!isBlank(newParams.getApiKey()) && !newParams.getApiKey().equals(storedApiKey)) {
            log.warn("deprecated: apiKey in PUT /models/{} body is ignored; use PUT /credentials instead", id);
        }
        if (!isBlank(newParams.getAppSecret()) && !newParams.getAppSecret().equals(storedAppSecret)) {
            log.warn("deprecated: appSecret in PUT /models/{} body is ignored; use PUT /credentials instead", id);
        }
        newParams.setApiKey(storedApiKey);
        newParams.setAppSecret(storedAppSecret);
        // 后端托管字段：前端不传的保留原值
        newParams.setParameterSize(stored.getParameterSize());
        if (newParams.getInterfaceType().isEmpty()) {
            newParams.setInterfaceType(stored.getInterfaceType());
        }
        if (newParams.getAppId().isEmpty()) {
            newParams.setAppId(stored.getAppId());
        }
        if (newParams.getExtraConfig() == null) {
            newParams.setExtraConfig(stored.getExtraConfig());
        }
        model.setParameters(newParams);
        model.setSource(req.source());
        model.setType(req.type());

        model = modelService.updateModel(model);
        log.info("Model updated successfully, ID: {}", id);
        return ResponseEntity.ok(ModelResponse.from(model, canViewIntegrationSecrets(), canManageBuiltin(model)));
    }

    /** 删除模型（Admin+）→ 204 无响应体。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteModel(@PathVariable("id") String id) {
        log.info("Start deleting model");
        if (isBlank(id)) {
            throw new BizException(AppError.badRequest("Model ID cannot be empty"));
        }
        try {
            modelService.deleteModel(id);
        } catch (ModelNotFoundException e) {
            log.warn("Model not found, ID: {}", id);
            throw new BizException(AppError.notFound("Model not found"));
        }
        log.info("Model deleted successfully, ID: {}", id);
        return ResponseEntity.noContent().build();
    }

    /** 模型提供方列表（Viewer+）；{@code modelType} 支持的取值由 ProviderRegistry 映射。 */
    @GetMapping("/providers")
    public ResponseEntity<List<ModelProviderDTO>> listModelProviders(
            @RequestParam(name = "modelType", required = false) String modelType) {
        log.info("Listing model providers for type: {}", modelType);
        List<ModelProviderDTO> providers;
        if (modelType != null && !modelType.isEmpty()) {
            providers = providerRegistry.listByModelType(ProviderRegistry.queryToBackend(modelType));
        } else {
            providers = providerRegistry.list();
        }
        log.info("Retrieved {} providers", providers.size());
        return ResponseEntity.ok(providers);
    }

    // ── 权限投影 ──────────────────────────────────────────────────────────

    private static boolean canViewIntegrationSecrets() {
        return TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.ADMIN);
    }

    private static boolean canManageBuiltin(Model m) {
        return m.isIsBuiltin() && TenantContext.isSystemAdmin();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
