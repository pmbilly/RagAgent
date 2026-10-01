package com.ragagent.evaluation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.web.RejectEmptyBody;
import com.ragagent.evaluation.dto.EvaluationDtos.EvaluationDetail;
import com.ragagent.evaluation.dto.EvaluationRequest;
import com.ragagent.evaluation.service.EvaluationService;

import jakarta.validation.Valid;

/**
 * 评估端点（POST = Admin：驱动 LLM+检索、跨 KB 读；GET = Viewer 读同租户任务）。
 *
 * <p>请求侧走标准 DTO 绑定（{@link EvaluationRequest}，字段全部可选，缺省由服务层
 * 缺省链补齐）；响应直接返回 {@link EvaluationDetail}（无信封）。错误统一走
 * AppError 信封：空体 → 400「请求体不能为空」；service 失败 → 500 + message=原文
 * （"no default models found for evaluation" / "task not found" /
 * "tenant ID does not match" / "knowledge base not found"）。</p>
 */
@RestController
@RequestMapping("/api/v1/evaluation")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping
    public ResponseEntity<EvaluationDetail> evaluation(
            @Valid @RejectEmptyBody @RequestBody(required = false) EvaluationRequest request) {
        // 字面量 null 体 = 零值请求（与 Go 绑定行为一致）
        EvaluationRequest req = request == null ? EvaluationRequest.empty() : request;
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            // 鉴权中间件之下不可达；分支保留为防御
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        EvaluationDetail detail;
        try {
            detail = evaluationService.evaluation(
                    tenantId, sanitized(req.datasetId()), sanitized(req.knowledgeBaseId()),
                    sanitized(req.chatId()), sanitized(req.rerankId()));
        } catch (IllegalStateException e) {
            throw new BizException(AppError.internal(e.getMessage()));
        }
        return ResponseEntity.ok(detail);
    }

    /** {@code taskId} 为必填查询参数，缺失/空白 → 400（手写校验，保持与全局校验同款文案）。 */
    @GetMapping
    public ResponseEntity<EvaluationDetail> getEvaluationResult(
            @RequestParam(name = "taskId", required = false) String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new BizException(AppError.badRequest("请求参数不合法").withDetails("taskId: 不能为空"));
        }
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        try {
            return ResponseEntity.ok(evaluationService.evaluationResult(tenantId, sanitized(taskId)));
        } catch (IllegalStateException e) {
            throw new BizException(AppError.internal(e.getMessage()));
        }
    }

    /** 入参先净化再进服务（净化输出即真实入参）。 */
    private static String sanitized(String value) {
        return LogSanitizer.sanitize(value == null ? "" : value);
    }
}
