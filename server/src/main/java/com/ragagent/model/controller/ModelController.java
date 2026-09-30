package com.ragagent.model.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.common.tenant.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.model.domain.Model;
import com.ragagent.model.dto.CreateModelRequest;
import com.ragagent.model.dto.ModelProviderDTO;
import com.ragagent.model.dto.ModelResponse;
import com.ragagent.model.dto.UpdateModelRequest;
import com.ragagent.model.service.ModelService;
import com.ragagent.model.service.ModelService.ModelNotFoundException;
import com.ragagent.model.service.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照 Go internal/handler/model.go ModelHandler（阶段 2：CRUD + providers；
 * DebugModel 随阶段 7 运行时客户端翻译，本阶段路由不存在 → 404，见约定 §9）。
 *
 * 响应信封均为 gin.H → JSON key 字母序：{"data":...,"success":true} / {"message":...,"success":true}。
 * 绑定校验错误消息复刻 go-playground validator 格式（message 承载，details 恒 null——
 * 对照 Go NewBadRequestError(err.Error())）。
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

    /** 对照 CreateModel — Admin+ */
    @PostMapping
    public ResponseEntity<?> createModel(@RequestBody(required = false) CreateModelRequest req) {
        log.info("Start creating model");
        List<String> bindingErrors = validateCreateBinding(req);
        if (!bindingErrors.isEmpty()) {
            throw new BizException(AppError.badRequest(String.join("\n", bindingErrors)));
        }
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            throw new BizException(AppError.badRequest("Workspace ID cannot be empty"));
        }
        // SSRF 校验（对照 handler L88-94）
        if (req.parameters() != null && !req.parameters().getBaseUrl().isEmpty()) {
            try {
                ssrfGuard.validateURLForSSRF(req.parameters().getBaseUrl());
            } catch (SsrfGuard.SsrfException e) {
                log.warn("SSRF validation failed for model BaseURL: {}", e.getMessage());
                throw new BizException(AppError.badRequest(
                        ssrfGuard.formatSSRFError("Base URL", req.parameters().getBaseUrl(), e)));
            }
        }

        Model model = new Model();
        model.setTenantId(tenantId);
        model.setName(req.name());
        model.setDisplayName(req.displayName());
        model.setType(req.type());
        model.setSource(req.source());
        model.setDescription(req.description());
        model.setParameters(req.parameters());
        model = modelService.createModel(model);
        log.info("Model created successfully, ID: {}, Name: {}", model.getId(), model.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(envelopeData(ModelResponse.from(model, canViewIntegrationSecrets(), canManageBuiltin(model))));
    }

    /** 对照 gin binding:"required"（name/type/source/parameters），validator 错误格式复刻 */
    private static List<String> validateCreateBinding(CreateModelRequest req) {
        List<String> errors = new ArrayList<>();
        if (req == null) {
            errors.add("EOF");
            return errors;
        }
        if (isBlank(req.name())) {
            errors.add(bindingError("Name", "required"));
        }
        if (isBlank(req.type())) {
            errors.add(bindingError("Type", "required"));
        }
        if (isBlank(req.source())) {
            errors.add(bindingError("Source", "required"));
        }
        if (req.parameters() == null) {
            errors.add(bindingError("Parameters", "required"));
        }
        return errors;
    }

    private static String bindingError(String field, String tag) {
        return "Key: 'CreateModelRequest." + field + "' Error:Field validation for '" + field
                + "' failed on the '" + tag + "' tag";
    }

    /** 对照 GetModel — Viewer+ */
    @GetMapping("/{id}")
    public ResponseEntity<?> getModel(@PathVariable("id") String id) {
        log.info("Start retrieving model");
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.badRequest("Model ID cannot be empty"));
        }
        Model model;
        try {
            model = modelService.getModelByID(id);
        } catch (ModelNotFoundException e) {
            log.warn("Model not found, ID: {}", id);
            throw new BizException(AppError.notFound("Model not found"));
        }
        return ResponseEntity.ok(envelopeData(ModelResponse.from(model, canViewIntegrationSecrets(), canManageBuiltin(model))));
    }

    /** 对照 ListModels — Viewer+ */
    @GetMapping
    public ResponseEntity<?> listModels() {
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
        return ResponseEntity.ok(envelopeData(data));
    }

    /**
     * 对照 UpdateModel — Admin+（内置模型 SystemAdmin）。
     * 凭证快照保留 + 后端托管字段保留；type/source/description 无条件覆盖。
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateModel(@PathVariable("id") String id,
                                         @RequestBody(required = false) UpdateModelRequest req) {
        log.info("Start updating model");
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.badRequest("Model ID cannot be empty"));
        }
        if (req == null) {
            throw new BizException(AppError.badRequest("EOF"));
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

        if (req.parameters() != null && !req.parameters().getBaseUrl().isEmpty()) {
            try {
                ssrfGuard.validateURLForSSRF(req.parameters().getBaseUrl());
            } catch (SsrfGuard.SsrfException e) {
                log.warn("SSRF validation failed for model BaseURL: {}", e.getMessage());
                throw new BizException(AppError.badRequest(
                        ssrfGuard.formatSSRFError("Base URL", req.parameters().getBaseUrl(), e)));
            }
        }
        // 凭证永不经 PUT 正文：快照保留（对照 handler L614-626）
        var stored = model.getParameters();
        String storedApiKey = stored.getApiKey();
        String storedAppSecret = stored.getAppSecret();
        var newParams = req.parameters() != null ? req.parameters() : new com.ragagent.model.domain.ModelParameters();
        if (newParams.getApiKey() != null && !newParams.getApiKey().isEmpty()
                && !newParams.getApiKey().equals(storedApiKey)) {
            log.warn("deprecated: api_key in PUT /models/{} body is ignored; use PUT /credentials instead", id);
        }
        if (newParams.getAppSecret() != null && !newParams.getAppSecret().isEmpty()
                && !newParams.getAppSecret().equals(storedAppSecret)) {
            log.warn("deprecated: app_secret in PUT /models/{} body is ignored; use PUT /credentials instead", id);
        }
        newParams.setApiKey(storedApiKey);
        newParams.setAppSecret(storedAppSecret);
        // 后端托管字段：前端不传的保留原值（对照 L628-637）
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
        return ResponseEntity.ok(envelopeData(ModelResponse.from(model, canViewIntegrationSecrets(), canManageBuiltin(model))));
    }

    /** 对照 DeleteModel — Admin+ */
    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public ResponseEntity<?> deleteModel(@PathVariable("id") String id) {
        log.info("Start deleting model");
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.badRequest("Model ID cannot be empty"));
        }
        try {
            modelService.deleteModel(id);
        } catch (ModelNotFoundException e) {
            log.warn("Model not found, ID: {}", id);
            throw new BizException(AppError.notFound("Model not found"));
        }
        log.info("Model deleted successfully, ID: {}", id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Model deleted");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 ListModelProviders — Viewer+；model_type 查询参数做前端→后端映射 */
    @GetMapping("/providers")
    public ResponseEntity<?> listModelProviders(@RequestParam(value = "model_type", required = false) String modelType) {
        log.info("Listing model providers for type: {}", modelType);
        List<ModelProviderDTO> providers;
        if (modelType != null && !modelType.isEmpty()) {
            providers = providerRegistry.listByModelType(ProviderRegistry.queryToBackend(modelType));
        } else {
            providers = providerRegistry.list();
        }
        log.info("Retrieved {} providers", providers.size());
        return ResponseEntity.ok(envelopeData(providers));
    }

    // ── 权限投影（对照 dto.CanViewIntegrationSecrets / canManageBuiltin） ────

    private static boolean canViewIntegrationSecrets() {
        return TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.ADMIN);
    }

    private static boolean canManageBuiltin(Model m) {
        return m.isIsBuiltin() && TenantContext.isSystemAdmin();
    }

    /** gin.H 信封：key 字母序（data < success） */
    private static Map<String, Object> envelopeData(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
