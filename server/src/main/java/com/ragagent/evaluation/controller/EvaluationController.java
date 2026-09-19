package com.ragagent.evaluation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.evaluation.dto.EvaluationDtos.EvaluationDetail;
import com.ragagent.evaluation.service.EvaluationService;

/**
 * 评估端点（对照 Go internal/handler/evaluation.go，routes_infra.go L81-89：
 * POST /evaluation = Admin（驱动 LLM+检索、跨 KB 读）、GET = Viewer）。
 *
 * <p>错误形态全走 AppError 信封（c.Error）：bind 失败 → 400 "Invalid request parameters"
 * + details=validator/解析器原文；service 失败 → 500 + message=err.Error() 原文
 * （"no default models found for evaluation" / "task not found" /
 * "tenant ID does not match" / "knowledge base not found"）。</p>
 */
@RestController
@RequestMapping("/api/v1/evaluation")
public class EvaluationController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对照 EvaluationRequest（chat_id/rerank_id 是 json 键名，不是字段名照抄）。 */
    public record EvaluationRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("dataset_id") String datasetId,
            @com.fasterxml.jackson.annotation.JsonProperty("knowledge_base_id") String knowledgeBaseId,
            @com.fasterxml.jackson.annotation.JsonProperty("chat_id") String chatId,
            @com.fasterxml.jackson.annotation.JsonProperty("rerank_id") String rerankId) {
    }

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping
    public ResponseEntity<?> evaluation(@RequestBody(required = false) String rawBody) {
        EvaluationRequest request = bind(rawBody);
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            // Go: NewUnauthorizedError("Unauthorized")（中间件之下不可达，分支保留）
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        // Go 把 SanitizeForLog 的输出**当真实入参**（batch-delete 先例）
        EvaluationDetail detail;
        try {
            detail = evaluationService.evaluation(
                    tenantId,
                    LogSanitizer.sanitize(orEmpty(request.datasetId())),
                    LogSanitizer.sanitize(orEmpty(request.knowledgeBaseId())),
                    LogSanitizer.sanitize(orEmpty(request.chatId())),
                    LogSanitizer.sanitize(orEmpty(request.rerankId())));
        } catch (IllegalStateException e) {
            throw new BizException(AppError.internal(e.getMessage()));
        }
        // gin.H 字母序：data < success
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("data", detail);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    @GetMapping
    public ResponseEntity<?> getEvaluationResult(
            @RequestParam(name = "task_id", required = false) String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new BizException(AppError.badRequest("Invalid request parameters")
                    .withDetails("Key: 'GetEvaluationRequest.TaskID' Error:Field validation for "
                            + "'TaskID' failed on the 'required' tag"));
        }
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        try {
            EvaluationDetail detail = evaluationService.evaluationResult(
                    tenantId, LogSanitizer.sanitize(taskId));
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("data", detail);
            body.put("success", true);
            return ResponseEntity.ok(body);
        } catch (IllegalStateException e) {
            throw new BizException(AppError.internal(e.getMessage()));
        }
    }

    /** 对照 ShouldBind(&request)：解析失败 → 400 "Invalid request parameters" + details 原文。 */
    private static EvaluationRequest bind(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest("Invalid request parameters").withDetails("EOF"));
        }
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            if (node == null || node.isNull()) {
                // Go：`null` 字面量绑定不报错 → 零值 struct
                return new EvaluationRequest(null, null, null, null);
            }
            return MAPPER.treeToValue(node, EvaluationRequest.class);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("Invalid request parameters")
                    .withDetails(GoJsonBindError.message(rawBody, e.getMessage())));
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
