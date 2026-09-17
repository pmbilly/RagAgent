package com.ragagent.model.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.model.domain.Model;
import com.ragagent.model.dto.CredentialsResponse;
import com.ragagent.model.dto.ModelCredentialsPutRequest;
import com.ragagent.model.service.ModelService;
import com.ragagent.model.service.ModelService.ModelNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照 Go internal/handler/model_credentials.go：模型凭证子资源。
 * 秘密字段永不经主资源 PUT 正文，只能经本资源按字段写入/清除；
 * 双字段均缺省时 PUT 退化为"已配置状态查询"（对照 Go Put 的 nil 分支）。
 */
@RestController
@RequestMapping("/api/v1/models")
public class ModelCredentialsController {

    private static final Logger log = LoggerFactory.getLogger(ModelCredentialsController.class);

    private final ModelService modelService;

    public ModelCredentialsController(ModelService modelService) {
        this.modelService = modelService;
    }

    @PutMapping("/{id}/credentials")
    public ResponseEntity<?> put(@PathVariable("id") String id,
                                 @RequestBody(required = false) ModelCredentialsPutRequest req) {
        Long tenantId = com.ragagent.common.context.TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            throw new BizException(AppError.badRequest("Workspace ID cannot be empty"));
        }
        if (req == null) {
            req = new ModelCredentialsPutRequest(null, null);
        }
        if (req.apiKey() == null && req.appSecret() == null) {
            Model m;
            try {
                m = modelService.getModelByID(id);
            } catch (ModelNotFoundException e) {
                throw new BizException(AppError.notFound("Model not found"));
            }
            return ResponseEntity.ok(envelope(CredentialsResponse.of(
                    !m.getParameters().getApiKey().isEmpty(),
                    !m.getParameters().getAppSecret().isEmpty())));
        }
        Model updated;
        try {
            updated = modelService.updateModelCredentials(id, req.apiKey(), req.appSecret());
        } catch (ModelNotFoundException e) {
            throw new BizException(AppError.notFound("Model not found"));
        }
        return ResponseEntity.ok(envelope(CredentialsResponse.of(
                !updated.getParameters().getApiKey().isEmpty(),
                !updated.getParameters().getAppSecret().isEmpty())));
    }

    @DeleteMapping("/{id}/credentials/{field}")
    public ResponseEntity<?> deleteField(@PathVariable("id") String id,
                                         @PathVariable("field") String field) {
        Long tenantId = com.ragagent.common.context.TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            throw new BizException(AppError.badRequest("Workspace ID cannot be empty"));
        }
        if (!"api_key".equals(field) && !"app_secret".equals(field)) {
            throw new BizException(AppError.badRequest("unknown credential field: " + field));
        }
        try {
            modelService.clearModelCredential(id, field);
        } catch (ModelNotFoundException e) {
            throw new BizException(AppError.notFound("Model not found"));
        }
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }
}
