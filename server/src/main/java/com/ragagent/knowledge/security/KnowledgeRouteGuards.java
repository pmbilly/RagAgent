package com.ragagent.knowledge.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.security.KnowledgeAccessGuard;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.knowledge.task.KnowledgeTaskIdCodec;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import com.ragagent.apikey.domain.TenantAPIKeyScope;

/**
 * 知识文档路由共用的守卫与解析面（两个控制器共享）：
 * knowledge 守卫链（全局缺失 404 → [ownership 403 纯字符串] → KB 访问 404/403）、
 * handler 层守卫（无路由中间件的 body 路由）、批量路由的 KB 访问+ownership、
 * 搬移中拒绝、任务租户隔离与重复文档 409 信封。
 */
@Component
public class KnowledgeRouteGuards {

    private final KnowledgeService knowledgeService;
    private final KnowledgeAccessGuard guard;

    public KnowledgeRouteGuards(KnowledgeService knowledgeService, KnowledgeAccessGuard guard) {
        this.knowledgeService = knowledgeService;
        this.guard = guard;
    }

    /**
     * 路由中间件链的控制器内落地：knowledge 全局缺失 → 404 "Knowledge not found"
     * （大写 K）→ [ownership 放行或 403 纯字符串] → KB 访问（404 小写 k / 403 信封）。
     */
    public Knowledge resolveKnowledgeByGuard(String knowledgeId, boolean ownership, boolean write) {
        Knowledge global = knowledgeService.getKnowledgeByIdOnly(knowledgeId);
        if (global == null) {
            throw new BizException(AppError.notFound("Knowledge not found"));
        }
        if (ownership) {
            guard.requireOwnedKnowledgeKb(knowledgeId);
        }
        guard.requireKbAccess(global.getKnowledgeBaseId());
        return knowledgeService.getKnowledge(knowledgeId);
    }

    public Knowledge resolveKnowledgeByGuard(String knowledgeId, boolean ownership) {
        return resolveKnowledgeByGuard(knowledgeId, ownership, ownership);
    }

    /**
     * handler 层守卫（无路由级 KBAccess 中间件的 body 路由）：跨租户 403 文案是
     * "Permission denied to access this knowledge"——与路由层的 "...knowledge base"
     * 一字之差，契约样例锁定。
     */
    public Knowledge resolveKnowledgeHandlerLevel(String knowledgeId, boolean write) {
        Knowledge global = knowledgeService.getKnowledgeByIdOnly(knowledgeId);
        if (global == null) {
            throw new BizException(AppError.notFound("Knowledge not found"));
        }
        TenantAPIKeyScope.authorizeKnowledgeBases(
                List.of(global.getKnowledgeBaseId()));
        Long caller = TenantContext.currentTenantId();
        if (global.getTenantId() == null || !global.getTenantId().equals(caller)) {
            throw new BizException(AppError.forbidden("Permission denied to access this knowledge"));
        }
        return knowledgeService.getKnowledge(knowledgeId);
    }

    /** KB 访问守卫透传（需要 KB 行的端点用）。 */
    public KnowledgeBase requireKbAccess(String kbId) {
        return guard.requireKbAccess(kbId);
    }

    /** 路由 ownership 守卫（非创建者且非 Admin+ → 403 纯字符串）。 */
    public void requireOwnedKb(KnowledgeBase kb) {
        guard.requireOwnedKb(kb);
    }

    /** 批处理路由共用的 KB 访问 + ownership（信封）链。 */
    public String batchAccessChecks(String kbId) {
        KnowledgeBase kb = guard.requireKbAccess(kbId);
        guard.requireKbOwnershipOrAdminEnvelope(kb);
        return kbId;
    }

    /** move 未完成 → 409（metadata 的 _knowledge_transfer 状态检查）。 */
    public void rejectMoving(Knowledge k) {
        JsonNode metadata = k.getMetadata();
        if (metadata != null && metadata.has("_knowledge_transfer")) {
            JsonNode state = metadata.get("_knowledge_transfer");
            if ("move".equals(state.path("operation").asText(""))
                    && "moving".equals(state.path("phase").asText(""))) {
                throw new BizException(AppError.conflict(
                        "knowledge has an unfinished move; retry the move first"));
            }
        }
    }

    /** 行归属校验：计数不符 → 400 "One or more..."；跨 KB → 400 "Knowledge %s does not belong..." */
    public void requireKnowledgeInKb(String kbId, List<String> ids) {
        List<Knowledge> rows = knowledgeService.getKnowledgeBatch(tenantId(), ids);
        if (rows.size() != ids.size()) {
            throw new BizException(AppError.badRequest("One or more knowledge entries not found"));
        }
        for (Knowledge k : rows) {
            if (!k.getKnowledgeBaseId().equals(kbId)) {
                throw new BizException(AppError.badRequest("Knowledge " + k.getId()
                        + " does not belong to knowledge base " + kbId));
            }
        }
    }

    /** 去重 + 空校验 + 单批上限 200。 */
    public List<String> requireBatchIds(List<String> rawIds, String field) {
        List<String> ids = dedupeIds(rawIds);
        if (ids.isEmpty()) {
            throw new BizException(AppError.badRequest(field + " cannot be empty"));
        }
        if (ids.size() > 200) {
            throw new BizException(AppError.badRequest("too many ids (max 200 per batch)"));
        }
        return ids;
    }

    /** 去重 + 剔除空白项，保持原顺序。 */
    public static List<String> dedupeIds(List<String> rawIds) {
        List<String> ids = new ArrayList<>();
        if (rawIds == null) {
            return ids;
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String id : rawIds) {
            if (id != null && !id.trim().isEmpty() && seen.add(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** 任务租户必须与调用方一致，否则 404（不泄露他租户任务存在性）。 */
    public void requireTaskProgressTenant(String taskId) {
        Long taskTenant = KnowledgeTaskIdCodec.taskTenantId(taskId);
        if (taskTenant == null) {
            throw new BizException(AppError.badRequest("invalid task ID"));
        }
        long caller = tenantId();
        if (caller == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        if (taskTenant != caller) {
            throw new BizException(AppError.notFound("task not found"));
        }
    }

    /** mime.FormatMediaType 的对位：token 安全 → filename=...；否则 RFC2231 编码。 */
    public static String contentDisposition(String disposition, String filename) {
        boolean token = !filename.isEmpty() && filename.chars().allMatch(KnowledgeRouteGuards::isTokenChar);
        if (token) {
            return disposition + "; filename=" + filename;
        }
        StringBuilder sb = new StringBuilder(disposition).append("; filename*=utf-8''");
        for (byte b : filename.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (isTokenChar(c)) {
                sb.append(c);
            } else {
                sb.append(String.format("%%%02X", b));
            }
        }
        return sb.toString();
    }

    /** 非空白非 CTL 且非 tspecials。 */
    private static boolean isTokenChar(int c) {
        if (c <= 0x20 || c >= 0x7f) {
            return false;
        }
        return "()<>@,;:\\\"/[]?=".indexOf(c) < 0;
    }

    /** multipart 的 metadata 参数：空 → null；非法 JSON → 400。 */
    public static JsonNode parseJsonParam(com.fasterxml.jackson.databind.ObjectMapper mapper,
            String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception ex) {
            throw new BizException(AppError.badRequest(label + " must be a valid JSON"));
        }
    }

    public static String safeFilename(String filename) {
        return filename == null ? "" : filename;
    }

    public static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }
}
