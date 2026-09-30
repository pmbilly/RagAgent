package com.ragagent.knowledge.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.repository.KnowledgeSpanRepository;
import org.springframework.stereotype.Service;
import com.ragagent.knowledge.domain.KnowledgeProcessingSpan;

/**
 * 知识处理 spans 合成树。静态 canonical 时间线复用门面公开常量
 * {@link KnowledgeService#ALL_STAGES}；时间渲染复用门面同包 helper {@code timeString}。
 */
@Service
public class KnowledgeSpanService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeSpanRepository spanRepository;

    public KnowledgeSpanService(KnowledgeSpanRepository spanRepository) {
        this.spanRepository = spanRepository;
    }

    // ── 文档操作面 ──────────────────────────────────────────────────

    /**
     * attempt 选择（显式 ?attempt=N 优先，
     * 否则 spans 表的 latestAttempt）→ ListByAttempt → buildSpanTree（真实行建树 +
     * 缺失 canonical stage 合成）→ last_error（span 失败行优先）。
     * 2026-09-23 起 span 写入侧已接线（此前 spanRepo==nil 分支的备案差异作废）。
     * @return data 信封内层（gin.H 键按字母序：attempt/current_attempt/current_stage/
     *         knowledge_id/[last_error]/latest_attempt/parse_status/trace）
     */
    public ObjectNode knowledgeSpans(Knowledge knowledge, int requestedAttempt) {
        int latestAttempt = spanRepository.latestAttempt(knowledge.getId());
        int currentAttempt = requestedAttempt > 0 ? requestedAttempt : latestAttempt;
        List<KnowledgeProcessingSpan> rows =
                currentAttempt > 0
                        ? spanRepository.listByAttempt(knowledge.getId(), currentAttempt)
                        : List.of();
        SpanTree tree = buildSpanTree(knowledge.getId(), currentAttempt, rows,
                knowledge.getParseStatus());

        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("attempt", currentAttempt);
        resp.put("currentAttempt", currentAttempt);
        resp.put("currentStage", tree.currentStage());
        resp.put("knowledgeId", knowledge.getId());
        JsonNode lastError = knowledgeSpansLastError(currentAttempt, latestAttempt,
                knowledge, tree.lastFailure());
        if (lastError != null) {
            resp.set("lastError", lastError);
        }
        resp.put("latestAttempt", latestAttempt);
        resp.put("parseStatus", knowledge.getParseStatus() == null ? "" : knowledge.getParseStatus());
        resp.set("trace", tree.root());
        return resp;
    }

    record SpanTree(ObjectNode root, String currentStage,
                    KnowledgeProcessingSpan lastFailure) {
    }

    /**
     * 真实行按 span_id 建索引
     * （保 rows 序）→ root（首个 kind=root）/首个 running stage（current_stage）/
     * 末个 failed 行（lastFailure）→ children 按 rows 序链接（无父/孤儿挂 root）→
     * 缺失 canonical stage 合成占位（AllStages 序）。rows 为空时与旧实现逐字节一致
     * （全合成，status 由 parse_status 推导：completed→done、failed→failed、其余 pending）。
     */
    private static SpanTree buildSpanTree(
            String knowledgeId, int attempt,
            List<KnowledgeProcessingSpan> rows,
            String parseStatus) {
        String syntheticStatus = "pending";
        if (Knowledge.PARSE_COMPLETED.equals(parseStatus)) {
            syntheticStatus = "done";
        } else if (Knowledge.PARSE_FAILED.equals(parseStatus)) {
            syntheticStatus = "failed";
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        Map<String, ObjectNode> nodes = new java.util.LinkedHashMap<>();
        KnowledgeProcessingSpan rootRow = null;
        Map<String, KnowledgeProcessingSpan> stageRowByName =
                new java.util.LinkedHashMap<>();
        String currentStage = "";
        KnowledgeProcessingSpan lastFailure = null;
        for (KnowledgeProcessingSpan r : rows) {
            nodes.put(r.getSpanId(), spanNodeFromRow(r));
            if ("root".equals(r.getKind()) && rootRow == null) {
                rootRow = r;
            }
            if ("stage".equals(r.getKind())) {
                stageRowByName.put(r.getName(), r);
            }
            if ("running".equals(r.getStatus()) && "stage".equals(r.getKind())
                    && currentStage.isEmpty()) {
                currentStage = r.getName();
            }
            if ("failed".equals(r.getStatus())) {
                lastFailure = r;
            }
        }

        ObjectNode root;
        if (rootRow == null) {
            root = spanNode(knowledgeId, attempt, "", "knowledge_processing", "root",
                    syntheticStatus, null, now);
        } else {
            root = nodes.get(rootRow.getSpanId());
        }

        for (KnowledgeProcessingSpan r : rows) {
            ObjectNode n = nodes.get(r.getSpanId());
            if (n == null || n == root) {
                continue;
            }
            ObjectNode parent = r.getParentSpanId().isEmpty()
                    ? null : nodes.get(r.getParentSpanId());
            if (parent == null) {
                parent = root;
            }
            ArrayNode children = parent.has("children")
                    ? (ArrayNode) parent.get("children") : MAPPER.createArrayNode();
            children.add(n);
            parent.set("children", children);
        }

        // 缺失 stage 合成（AllStages 序，保证 5 段布局确定性）
        for (String stage : KnowledgeService.ALL_STAGES) {
            if (stageRowByName.containsKey(stage)) {
                continue;
            }
            ArrayNode children = root.has("children")
                    ? (ArrayNode) root.get("children") : MAPPER.createArrayNode();
            children.add(spanNode(knowledgeId, attempt, "", stage, "stage",
                    syntheticStatus, null, now));
            root.set("children", children);
        }
        return new SpanTree(root, currentStage, lastFailure);
    }

    /**
     * omitempty 字段（parent_span_id/input/output/metadata/error_code/error_message/
     * started_at/finished_at/duration_ms）缺席即省略；error_detail 是 {@code json:"-"} 不输出；
     * created_at/updated_at 恒输出。
     */
    private static ObjectNode spanNodeFromRow(
            KnowledgeProcessingSpan r) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("knowledgeId", r.getKnowledgeId());
        n.put("attempt", r.getAttempt());
        n.put("spanId", r.getSpanId());
        if (!r.getParentSpanId().isEmpty()) {
            n.put("parentSpanId", r.getParentSpanId());
        }
        n.put("name", r.getName());
        n.put("kind", r.getKind());
        n.put("status", r.getStatus());
        if (r.getInput() != null) {
            n.set("input", MAPPER.valueToTree(r.getInput()));
        }
        if (r.getOutput() != null) {
            n.set("output", MAPPER.valueToTree(r.getOutput()));
        }
        if (r.getMetadata() != null) {
            n.set("metadata", MAPPER.valueToTree(r.getMetadata()));
        }
        if (!r.getErrorCode().isEmpty()) {
            n.put("errorCode", r.getErrorCode());
        }
        if (!r.getErrorMessage().isEmpty()) {
            n.put("errorMessage", r.getErrorMessage());
        }
        if (r.getStartedAt() != null) {
            n.put("startedAt", KnowledgeService.timeString(r.getStartedAt()));
        }
        if (r.getFinishedAt() != null) {
            n.put("finishedAt", KnowledgeService.timeString(r.getFinishedAt()));
        }
        if (r.getDurationMs() != 0) {
            n.put("durationMs", r.getDurationMs());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        n.put("createdAt", KnowledgeService.timeString(r.getCreatedAt() == null ? now : r.getCreatedAt()));
        n.put("updatedAt", KnowledgeService.timeString(r.getUpdatedAt() == null ? now : r.getUpdatedAt()));
        return n;
    }

    private static ObjectNode spanNode(String knowledgeId, int attempt, String spanId,
                                       String name, String kind, String status,
                                       String parentSpanId, OffsetDateTime now) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("knowledgeId", knowledgeId);
        n.put("attempt", attempt);
        n.put("spanId", spanId);
        if (parentSpanId != null && !parentSpanId.isEmpty()) {
            n.put("parentSpanId", parentSpanId);
        }
        n.put("name", name);
        n.put("kind", kind);
        n.put("status", status);
        n.put("createdAt", KnowledgeService.timeString(now));
        n.put("updatedAt", KnowledgeService.timeString(now));
        return n;
    }

    /**
     * span 失败行优先（字母序
     * code/error_code/error_message/finished_at/message/name/stage；finished_at 为
     * null 时输出 null）；否则 currentAttempt==latestAttempt 且 parse_status=failed
     * 且 error_message 非空才落知识行回退（SERVER_RESTART 文案 EqualFold 判定照抄）。
     */
    private static JsonNode knowledgeSpansLastError(
            int currentAttempt, int latestAttempt, Knowledge knowledge,
            KnowledgeProcessingSpan spanFailure) {
        if (spanFailure != null) {
            ObjectNode e = MAPPER.createObjectNode();
            e.put("code", spanFailure.getErrorCode());
            e.put("errorCode", spanFailure.getErrorCode());
            e.put("errorMessage", spanFailure.getErrorMessage());
            if (spanFailure.getFinishedAt() == null) {
                e.putNull("finished_at");
            } else {
                e.put("finishedAt", KnowledgeService.timeString(spanFailure.getFinishedAt()));
            }
            e.put("message", spanFailure.getErrorMessage());
            e.put("name", spanFailure.getName());
            e.put("stage", spanFailure.getName());
            return e;
        }
        String parseStatus = knowledge.getParseStatus() == null ? "" : knowledge.getParseStatus();
        String message = knowledge.getErrorMessage() == null ? "" : knowledge.getErrorMessage();
        if (currentAttempt != latestAttempt || !Knowledge.PARSE_FAILED.equals(parseStatus)
                || message.isEmpty()) {
            return null;
        }
        String errorCode = "UNKNOWN";
        if ("Task interrupted due to application restart"
                .equalsIgnoreCase(message.trim())) {
            errorCode = "SERVER_RESTART";
        }
        // gin.H → encoding/json 键按字母序输出（code < error_code < error_message <
        // finished_at < message < name < stage），契约样例锁定
        ObjectNode e = MAPPER.createObjectNode();
        e.put("code", errorCode);
        e.put("errorCode", errorCode);
        e.put("errorMessage", message);
        e.put("finishedAt", knowledge.getUpdatedAt() == null
                ? null : KnowledgeService.timeString(knowledge.getUpdatedAt()));
        e.put("message", message);
        e.put("name", "knowledge_processing");
        e.put("stage", "knowledge_processing");
        return e;
    }

}
