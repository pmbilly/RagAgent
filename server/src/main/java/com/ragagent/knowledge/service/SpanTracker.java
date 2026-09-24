package com.ragagent.knowledge.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.ragagent.knowledge.domain.KnowledgeProcessingSpan;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.mapper.KnowledgeSpanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 对照 Go {@code spanTracker}（knowledge_span_tracker.go 全文 879 行）：处理管道面向的
 * per-attempt 进度树记录门面（root / stage / subspan / generation，Langfuse 词汇）。
 *
 * <p><b>生命周期</b>：{@link #openAttempt} 建 root → {@link #beginStage}（每 attempt 的
 * 5 个 canonical 阶段）→ {@link #endSpan}/{@link #failSpan}/{@link #skipSpan}；
 * {@link #finalizeAttempt}/{@link #abortAttempt} 收口 root。</p>
 *
 * <p><b>全部操作 best-effort</b>：DB 错误只记日志并吞掉（追踪器抖动绝不破坏处理管道）；
 * knowledge.parse_status 始终是完成状态的权威来源。</p>
 *
 * <p>与 Go 一致的细节：span_id = 去横线 UUID；duration 优先取进程内 starts 缓存
 * （跨进程调用回退 StartedAt 差值）；{@link #failSpan} 级联取消下游（子 span 用
 * CancelDescendants，依赖 STAGE 用 {@code StageDependencies} 传递闭包），MAIN 阶段
 * 失败时把 root 收口为 failed；{@link #touchKnowledgeHeartbeat} 只对 root/stage
 * 推进 knowledge.updated_at（housekeeping 心跳，fan-out 子 span 靠 spans 表自身的
 * updated_at 观察）。</p>
 */
@Component
public class SpanTracker {

    private static final Logger log = LoggerFactory.getLogger(SpanTracker.class);

    /** 对照 maxSpanNameLen（name 列 varchar(255)）。 */
    static final int MAX_SPAN_NAME_LEN = 255;

    /** 对照 types.StageDependencies（types/knowledge_span.go L70-76）。 */
    private static final Map<String, List<String>> STAGE_DEPENDENCIES = Map.of(
            KnowledgeProcessingSpan.STAGE_DOC_READER, List.of(),
            KnowledgeProcessingSpan.STAGE_CHUNKING,
            List.of(KnowledgeProcessingSpan.STAGE_DOC_READER),
            KnowledgeProcessingSpan.STAGE_EMBEDDING,
            List.of(KnowledgeProcessingSpan.STAGE_CHUNKING),
            KnowledgeProcessingSpan.STAGE_MULTIMODAL,
            List.of(KnowledgeProcessingSpan.STAGE_CHUNKING),
            KnowledgeProcessingSpan.STAGE_POST_PROCESS,
            List.of(KnowledgeProcessingSpan.STAGE_EMBEDDING,
                    KnowledgeProcessingSpan.STAGE_MULTIMODAL));

    /** 对照 Go 的 Span（in-memory handle）：End/Fail/Skip 回写所需的最小上下文。 */
    public static final class SpanHandle {
        public String knowledgeId = "";
        public int attempt;
        public String spanId = "";
        public String parentSpanId = "";
        public String name = "";
        public String kind = "";
        public String status = "";
        public OffsetDateTime startedAt;
    }

    /** openAttempt 的返回对（Go 的多返回值）。 */
    public record AttemptHandle(SpanHandle root, int attempt) {
    }

    private final KnowledgeSpanRepository repo;
    private final KnowledgeMapper knowledgeMapper;

    /** 对照 starts map：span_id → started_at（进程内 duration 缓存）。 */
    private final Map<String, OffsetDateTime> starts = new ConcurrentHashMap<>();

    public SpanTracker(KnowledgeSpanRepository repo, KnowledgeMapper knowledgeMapper) {
        this.repo = repo;
        this.knowledgeMapper = knowledgeMapper;
    }

    /**
     * 对照 fitSpanName（L51-68）：超 255 码点时截断并追加
     * {@code ~<sha256 前 4 字节 hex>} 后缀（rune 感知，对齐 PG VARCHAR 字符语义）。
     */
    public static String fitSpanName(String name) {
        int count = name.codePointCount(0, name.length());
        if (count <= MAX_SPAN_NAME_LEN) {
            return name;
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(name.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            hex.append(String.format("%02x", digest[i]));
        }
        String suffix = "~" + hex;
        int suffixCount = suffix.codePointCount(0, suffix.length());
        int keep = MAX_SPAN_NAME_LEN - suffixCount;
        if (keep < 1) {
            if (suffixCount > MAX_SPAN_NAME_LEN) {
                return suffix.substring(0, suffix.offsetByCodePoints(0, MAX_SPAN_NAME_LEN));
            }
            return suffix;
        }
        return name.substring(0, name.offsetByCodePoints(0, keep)) + suffix;
    }

    /**
     * 对照 stagesDependingOn（L677-700）：反查 StageDependencies 的传递闭包
     * （哪些阶段的（直接/间接）上游是 {@code stage}）。
     */
    static List<String> stagesDependingOn(String stage) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> frontier = new ArrayList<>(List.of(stage));
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String candidate : KnowledgeService.ALL_STAGES) {
                if (seen.contains(candidate)) {
                    continue;
                }
                List<String> deps = STAGE_DEPENDENCIES.getOrDefault(candidate, List.of());
                for (String d : deps) {
                    if (frontier.contains(d)) {
                        seen.add(candidate);
                        out.add(candidate);
                        next.add(candidate);
                        break;
                    }
                }
            }
            frontier = next;
        }
        return out;
    }

    /** 对照 isMainPipelineStage（L717-724）：5 个 canonical 阶段之一。 */
    static boolean isMainPipelineStage(String name) {
        return KnowledgeService.ALL_STAGES.contains(name);
    }

    private static String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 对照 touchKnowledgeHeartbeat（L183-224）：只对 root/stage 推进
     * knowledge.updated_at（housekeeping 判活），best-effort。
     */
    private void touchKnowledgeHeartbeat(String knowledgeId, String kind) {
        if (knowledgeId == null || knowledgeId.isEmpty()) {
            return;
        }
        if (!KnowledgeProcessingSpan.KIND_ROOT.equals(kind)
                && !KnowledgeProcessingSpan.KIND_STAGE.equals(kind)) {
            return;
        }
        try {
            knowledgeMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.ragagent.knowledge.domain.Knowledge>()
                            .eq("id", knowledgeId)
                            .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
        } catch (RuntimeException e) {
            log.warn("[SpanTracker] heartbeat update failed kid={}: {}", knowledgeId, e.toString());
        }
    }

    private void recordStart(String spanId, OffsetDateTime at) {
        starts.put(spanId, at);
    }

    private OffsetDateTime takeStart(String spanId) {
        return starts.remove(spanId);
    }

    /** 对照 OpenAttempt（L249-286）：分配 attempt + 建 running root。失败抛异常（调用方决定是否吞）。 */
    public AttemptHandle openAttempt(String knowledgeId, String langfuseTraceId) {
        int attempt = repo.nextAttempt(knowledgeId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String rootId = newSpanId();
        Map<String, Object> meta = new LinkedHashMap<>();
        if (langfuseTraceId != null && !langfuseTraceId.isEmpty()) {
            meta.put("langfuse_trace_id", langfuseTraceId);
        }
        KnowledgeProcessingSpan row = new KnowledgeProcessingSpan();
        row.setKnowledgeId(knowledgeId);
        row.setAttempt(attempt);
        row.setSpanId(rootId);
        row.setName("knowledge_processing");
        row.setKind(KnowledgeProcessingSpan.KIND_ROOT);
        row.setStatus(KnowledgeProcessingSpan.STATUS_RUNNING);
        row.setMetadata(meta);
        row.setStartedAt(now);
        try {
            repo.upsert(row);
        } catch (RuntimeException e) {
            log.warn("[SpanTracker] OpenAttempt failed kid={}: {}", knowledgeId, e.toString());
            throw e;
        }
        recordStart(rootId, now);
        touchKnowledgeHeartbeat(knowledgeId, KnowledgeProcessingSpan.KIND_ROOT);
        SpanHandle handle = new SpanHandle();
        handle.knowledgeId = knowledgeId;
        handle.attempt = attempt;
        handle.spanId = rootId;
        handle.name = "knowledge_processing";
        handle.kind = KnowledgeProcessingSpan.KIND_ROOT;
        handle.status = KnowledgeProcessingSpan.STATUS_RUNNING;
        handle.startedAt = now;
        return new AttemptHandle(handle, attempt);
    }

    /**
     * 对照 Go {@code attemptSuperseded}（knowledge.go L196-201）：attempt ≤ 0 或 knowledge
     * 为空 → false（旧版在飞任务不判取代）；否则比 {@link #latestAttempt} 更大即被取代。
     */
    public boolean isAttemptSuperseded(String knowledgeId, int attempt) {
        if (attempt <= 0 || knowledgeId == null || knowledgeId.isEmpty()) {
            return false;
        }
        return latestAttempt(knowledgeId) > attempt;
    }

    /** 对照 LatestAttempt（L288-295）：失败吞成 0。 */
    public int latestAttempt(String knowledgeId) {
        try {
            return repo.latestAttempt(knowledgeId);
        } catch (RuntimeException e) {
            log.warn("[SpanTracker] LatestAttempt failed kid={}: {}", knowledgeId, e.toString());
            return 0;
        }
    }

    /**
     * 对照 BeginStage（L297-403）：找 root 作父 + 检测同名 stage 行（重入必须复用
     * 原 span_id，重置为 running 并清终态字段）；无 root 时记录 rootless stage。
     */
    public SpanHandle beginStage(String knowledgeId, int attempt, String stage,
                                 Map<String, Object> input) {
        if (knowledgeId == null || knowledgeId.isEmpty() || stage == null || stage.isEmpty()) {
            return null;
        }
        List<KnowledgeProcessingSpan> rows;
        try {
            rows = repo.listByAttempt(knowledgeId, attempt);
        } catch (RuntimeException e) {
            log.warn("[SpanTracker] BeginStage list failed kid={} attempt={}: {}",
                    knowledgeId, attempt, e.toString());
            return null;
        }
        String rootId = "";
        KnowledgeProcessingSpan existing = null;
        for (KnowledgeProcessingSpan r : rows) {
            if (KnowledgeProcessingSpan.KIND_ROOT.equals(r.getKind()) && rootId.isEmpty()) {
                rootId = r.getSpanId();
            }
            if (KnowledgeProcessingSpan.KIND_STAGE.equals(r.getKind())
                    && stage.equals(r.getName())) {
                existing = r;
            }
        }
        if (rootId.isEmpty()) {
            log.warn("[SpanTracker] BeginStage: no root for kid={} attempt={}, recording rootless",
                    knowledgeId, attempt);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (existing != null) {
            // 重入：保留 span_id（已引用的子 span 不脱钩）→ 重置 running + 清终态字段
            KnowledgeProcessingSpan row = new KnowledgeProcessingSpan();
            row.setKnowledgeId(existing.getKnowledgeId());
            row.setAttempt(existing.getAttempt());
            row.setSpanId(existing.getSpanId());
            row.setParentSpanId(existing.getParentSpanId());
            row.setName(existing.getName());
            row.setKind(existing.getKind());
            row.setStatus(KnowledgeProcessingSpan.STATUS_RUNNING);
            row.setInput(input);
            row.setStartedAt(now);
            if (!upsertQuiet(row, "BeginStage re-enter", knowledgeId, stage)) {
                return null;
            }
            recordStart(existing.getSpanId(), now);
            touchKnowledgeHeartbeat(knowledgeId, KnowledgeProcessingSpan.KIND_STAGE);
            SpanHandle handle = new SpanHandle();
            handle.knowledgeId = existing.getKnowledgeId();
            handle.attempt = existing.getAttempt();
            handle.spanId = existing.getSpanId();
            handle.parentSpanId = existing.getParentSpanId();
            handle.name = existing.getName();
            handle.kind = existing.getKind();
            handle.status = KnowledgeProcessingSpan.STATUS_RUNNING;
            handle.startedAt = now;
            return handle;
        }
        String id = newSpanId();
        KnowledgeProcessingSpan row = new KnowledgeProcessingSpan();
        row.setKnowledgeId(knowledgeId);
        row.setAttempt(attempt);
        row.setSpanId(id);
        row.setParentSpanId(rootId);
        row.setName(stage);
        row.setKind(KnowledgeProcessingSpan.KIND_STAGE);
        row.setStatus(KnowledgeProcessingSpan.STATUS_RUNNING);
        row.setInput(input);
        row.setStartedAt(now);
        if (!upsertQuiet(row, "BeginStage", knowledgeId, stage)) {
            return null;
        }
        recordStart(id, now);
        touchKnowledgeHeartbeat(knowledgeId, KnowledgeProcessingSpan.KIND_STAGE);
        SpanHandle handle = new SpanHandle();
        handle.knowledgeId = knowledgeId;
        handle.attempt = attempt;
        handle.spanId = id;
        handle.parentSpanId = rootId;
        handle.name = stage;
        handle.kind = KnowledgeProcessingSpan.KIND_STAGE;
        handle.status = KnowledgeProcessingSpan.STATUS_RUNNING;
        handle.startedAt = now;
        return handle;
    }

    /**
     * 对照 BeginSubSpan（L405-451）：fitSpanName（kind 收敛为 subspan/generation）→
     * 先按名 supersede 残留 open 行（asynq 重试/重启不产生重复条纹）→ 建 running 子 span。
     */
    public SpanHandle beginSubSpan(SpanHandle parent, String name, String kind,
                                   Map<String, Object> input) {
        if (parent == null || name == null || name.isEmpty()) {
            return null;
        }
        String fitted = fitSpanName(name);
        if (!KnowledgeProcessingSpan.KIND_GENERATION.equals(kind)
                && !KnowledgeProcessingSpan.KIND_SUB_SPAN.equals(kind)) {
            kind = KnowledgeProcessingSpan.KIND_SUB_SPAN;
        }
        try {
            repo.cancelOpenSpansByName(parent.knowledgeId, parent.attempt, fitted,
                    "TASK_SUPERSEDED", "superseded by a new run of the same subtask");
        } catch (RuntimeException e) {
            log.warn("[SpanTracker] supersede {} before BeginSubSpan failed: {}",
                    fitted, e.toString());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String id = newSpanId();
        KnowledgeProcessingSpan row = new KnowledgeProcessingSpan();
        row.setKnowledgeId(parent.knowledgeId);
        row.setAttempt(parent.attempt);
        row.setSpanId(id);
        row.setParentSpanId(parent.spanId);
        row.setName(fitted);
        row.setKind(kind);
        row.setStatus(KnowledgeProcessingSpan.STATUS_RUNNING);
        row.setInput(input);
        row.setStartedAt(now);
        if (!upsertQuiet(row, "BeginSubSpan", parent.spanId, fitted)) {
            return null;
        }
        recordStart(id, now);
        touchKnowledgeHeartbeat(parent.knowledgeId, kind);
        SpanHandle handle = new SpanHandle();
        handle.knowledgeId = parent.knowledgeId;
        handle.attempt = parent.attempt;
        handle.spanId = id;
        handle.parentSpanId = parent.spanId;
        handle.name = fitted;
        handle.kind = kind;
        handle.status = KnowledgeProcessingSpan.STATUS_RUNNING;
        handle.startedAt = now;
        return handle;
    }

    /** 对照 EndSpan（L453-476）：status=done + output + duration。 */
    public void endSpan(SpanHandle span, Map<String, Object> output) {
        if (span == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long dur = durationSince(span, now);
        KnowledgeProcessingSpan row = new KnowledgeProcessingSpan();
        row.setKnowledgeId(span.knowledgeId);
        row.setAttempt(span.attempt);
        row.setSpanId(span.spanId);
        row.setParentSpanId(span.parentSpanId);
        row.setName(span.name);
        row.setKind(span.kind);
        row.setStatus(KnowledgeProcessingSpan.STATUS_DONE);
        row.setOutput(output);
        row.setStartedAt(span.startedAt);
        row.setFinishedAt(now);
        row.setDurationMs(dur);
        upsertQuiet(row, "EndSpan", span.spanId, null);
        touchKnowledgeHeartbeat(span.knowledgeId, span.kind);
    }

    /**
     * 对照 FailSpan（L478-538）：failed + 截断（detail 8192 / message 1024）→
     * CancelDescendants → STAGE 失败时级联依赖阶段（传递闭包）+ MAIN 阶段失败收口 root。
     */
    public void failSpan(SpanHandle span, String errorCode, String errorMessage,
                         Throwable errorDetail) {
        if (span == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long dur = durationSince(span, now);
        String detail = "";
        if (errorDetail != null) {
            detail = errorDetail.getMessage() == null
                    ? errorDetail.toString() : errorDetail.getMessage();
            if (detail.length() > 8192) {
                detail = detail.substring(0, 8192);
            }
        }
        String message = errorMessage == null ? "" : errorMessage;
        if (message.length() > 1024) {
            message = message.substring(0, 1024);
        }
        KnowledgeProcessingSpan row = new KnowledgeProcessingSpan();
        row.setKnowledgeId(span.knowledgeId);
        row.setAttempt(span.attempt);
        row.setSpanId(span.spanId);
        row.setParentSpanId(span.parentSpanId);
        row.setName(span.name);
        row.setKind(span.kind);
        row.setStatus(KnowledgeProcessingSpan.STATUS_FAILED);
        row.setErrorCode(errorCode == null ? "" : errorCode.strip());
        row.setErrorMessage(message);
        row.setErrorDetail(detail);
        row.setStartedAt(span.startedAt);
        row.setFinishedAt(now);
        row.setDurationMs(dur);
        upsertQuiet(row, "FailSpan", span.spanId, null);

        String reason = "upstream " + span.name + " failed";
        if (errorCode != null && !errorCode.isEmpty()) {
            reason = reason + " (" + errorCode + ")";
        }
        try {
            repo.cancelDescendants(span.knowledgeId, span.attempt, span.spanId, reason);
        } catch (RuntimeException e) {
            log.warn("[SpanTracker] cancel descendants failed span={}: {}", span.spanId, e.toString());
        }
        if (KnowledgeProcessingSpan.KIND_STAGE.equals(span.kind)) {
            cascadeDependentStages(span, reason);
            if (isMainPipelineStage(span.name)) {
                finalizeAttempt(span.knowledgeId, span.attempt,
                        KnowledgeProcessingSpan.STATUS_FAILED, null, errorCode, errorMessage);
            }
        }
        touchKnowledgeHeartbeat(span.knowledgeId, span.kind);
    }

    /** 对照 SkipSpan（L540-561）：skipped + reason（无 duration）。 */
    public void skipSpan(SpanHandle span, String reason) {
        if (span == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        KnowledgeProcessingSpan row = new KnowledgeProcessingSpan();
        row.setKnowledgeId(span.knowledgeId);
        row.setAttempt(span.attempt);
        row.setSpanId(span.spanId);
        row.setParentSpanId(span.parentSpanId);
        row.setName(span.name);
        row.setKind(span.kind);
        row.setStatus(KnowledgeProcessingSpan.STATUS_SKIPPED);
        row.setErrorMessage(reason);
        row.setStartedAt(span.startedAt);
        row.setFinishedAt(now);
        upsertQuiet(row, "SkipSpan", span.spanId, null);
        touchKnowledgeHeartbeat(span.knowledgeId, span.kind);
    }

    /** 对照 LookupStage（L563-591）：首个同名 stage 行 → handle。 */
    public SpanHandle lookupStage(String knowledgeId, int attempt, String stage) {
        List<KnowledgeProcessingSpan> rows;
        try {
            rows = repo.listByAttempt(knowledgeId, attempt);
        } catch (RuntimeException e) {
            log.warn("[SpanTracker] LookupStage list failed kid={} attempt={}: {}",
                    knowledgeId, attempt, e.toString());
            return null;
        }
        for (KnowledgeProcessingSpan r : rows) {
            if (!KnowledgeProcessingSpan.KIND_STAGE.equals(r.getKind())
                    || !stage.equals(r.getName())) {
                continue;
            }
            return toHandle(r);
        }
        return null;
    }

    /** 对照 LookupSpanByName（L593-625）：首个同名行（任意 kind）→ handle；空参守卫。 */
    public SpanHandle lookupSpanByName(String knowledgeId, int attempt, String name) {
        if (name == null || name.isEmpty() || knowledgeId == null || knowledgeId.isEmpty()
                || attempt <= 0) {
            return null;
        }
        String fitted = fitSpanName(name);
        List<KnowledgeProcessingSpan> rows;
        try {
            rows = repo.listByAttempt(knowledgeId, attempt);
        } catch (RuntimeException e) {
            log.warn("[SpanTracker] LookupSpanByName list failed kid={} attempt={}: {}",
                    knowledgeId, attempt, e.toString());
            return null;
        }
        for (KnowledgeProcessingSpan r : rows) {
            if (!fitted.equals(r.getName())) {
                continue;
            }
            return toHandle(r);
        }
        return null;
    }

    /**
     * 对照 cascadeDependentStages（L635-671）：把依赖闭包内的 pending/running
     * STAGE 行翻 cancelled（UPSTREAM_FAILED + reason），并对其子树再走
     * CancelDescendants（清掉已挂上的 in-flight 子 span）。
     */
    private void cascadeDependentStages(SpanHandle failedStage, String reason) {
        List<KnowledgeProcessingSpan> rows;
        try {
            rows = repo.listByAttempt(failedStage.knowledgeId, failedStage.attempt);
        } catch (RuntimeException e) {
            return;
        }
        List<String> dependents = stagesDependingOn(failedStage.name);
        if (dependents.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (KnowledgeProcessingSpan row : rows) {
            if (!KnowledgeProcessingSpan.KIND_STAGE.equals(row.getKind())) {
                continue;
            }
            if (!KnowledgeProcessingSpan.STATUS_PENDING.equals(row.getStatus())
                    && !KnowledgeProcessingSpan.STATUS_RUNNING.equals(row.getStatus())) {
                continue;
            }
            if (!dependents.contains(row.getName())) {
                continue;
            }
            KnowledgeProcessingSpan updated = row.copy();
            updated.setStatus(KnowledgeProcessingSpan.STATUS_CANCELLED);
            updated.setErrorCode("UPSTREAM_FAILED");
            updated.setErrorMessage(reason);
            updated.setFinishedAt(now);
            if (!upsertQuiet(updated, "cascade dependent stage", row.getName(), null)) {
                continue;
            }
            try {
                repo.cancelDescendants(row.getKnowledgeId(), row.getAttempt(), row.getSpanId(), reason);
            } catch (RuntimeException e) {
                log.warn("[SpanTracker] cascade descendants of dependent {}: {}",
                        row.getName(), e.toString());
            }
        }
    }

    /**
     * 对照 FinalizeAttempt（L751-818）：root 幂等收口（done/failed/cancelled/skipped
     * 已是终态则 no-op）；duration 从持久化行的 started_at 重算（不吃进程内缓存）。
     */
    public void finalizeAttempt(String knowledgeId, int attempt, String status,
                                Map<String, Object> output, String errorCode,
                                String errorMessage) {
        if (knowledgeId == null || knowledgeId.isEmpty() || attempt <= 0) {
            return;
        }
        if (status == null || status.isEmpty()) {
            status = KnowledgeProcessingSpan.STATUS_DONE;
        }
        List<KnowledgeProcessingSpan> rows;
        try {
            rows = repo.listByAttempt(knowledgeId, attempt);
        } catch (RuntimeException e) {
            log.warn("[SpanTracker] FinalizeAttempt list failed kid={} attempt={}: {}",
                    knowledgeId, attempt, e.toString());
            return;
        }
        KnowledgeProcessingSpan root = null;
        for (KnowledgeProcessingSpan r : rows) {
            if (KnowledgeProcessingSpan.KIND_ROOT.equals(r.getKind())) {
                root = r;
                break;
            }
        }
        if (root == null) {
            return;
        }
        if (KnowledgeProcessingSpan.STATUS_DONE.equals(root.getStatus())
                || KnowledgeProcessingSpan.STATUS_FAILED.equals(root.getStatus())
                || KnowledgeProcessingSpan.STATUS_CANCELLED.equals(root.getStatus())
                || KnowledgeProcessingSpan.STATUS_SKIPPED.equals(root.getStatus())) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long dur = 0;
        if (root.getStartedAt() != null) {
            dur = now.toInstant().toEpochMilli() - root.getStartedAt().toInstant().toEpochMilli();
        }
        String message = errorMessage == null ? "" : errorMessage;
        if (message.length() > 1024) {
            message = message.substring(0, 1024);
        }
        KnowledgeProcessingSpan row = new KnowledgeProcessingSpan();
        row.setKnowledgeId(root.getKnowledgeId());
        row.setAttempt(root.getAttempt());
        row.setSpanId(root.getSpanId());
        row.setParentSpanId(root.getParentSpanId());
        row.setName(root.getName());
        row.setKind(root.getKind());
        row.setStatus(status);
        row.setInput(root.getInput());
        row.setOutput(output);
        row.setMetadata(root.getMetadata());
        row.setErrorCode(errorCode == null ? "" : errorCode.strip());
        row.setErrorMessage(message);
        row.setStartedAt(root.getStartedAt());
        row.setFinishedAt(now);
        row.setDurationMs(dur);
        if (!upsertQuiet(row, "FinalizeAttempt", knowledgeId, null)) {
            return;
        }
        touchKnowledgeHeartbeat(knowledgeId, KnowledgeProcessingSpan.KIND_ROOT);
    }

    /**
     * 对照 AbortAttempt（L831-855）：平扫全部非终态行为 cancelled（不等 BFS——fan-out
     * 阶段的子 span 会在父已 done 后仍 running）→ 收口 root 为 cancelled。
     */
    public void abortAttempt(String knowledgeId, int attempt, String errorCode,
                             String errorMessage, String reason) {
        if (knowledgeId == null || knowledgeId.isEmpty() || attempt <= 0) {
            return;
        }
        if (reason == null || reason.isEmpty()) {
            reason = "user cancelled";
        }
        if (errorCode == null || errorCode.isEmpty()) {
            errorCode = "USER_CANCELLED";
        }
        try {
            long n = repo.cancelAllOpenSpans(knowledgeId, attempt, errorCode, reason);
            if (n > 0) {
                log.info("[SpanTracker] AbortAttempt swept {} open span(s) for kid={} attempt={}",
                        n, knowledgeId, attempt);
            }
        } catch (RuntimeException e) {
            log.warn("[SpanTracker] AbortAttempt sweep failed kid={} attempt={}: {}",
                    knowledgeId, attempt, e.toString());
        }
        finalizeAttempt(knowledgeId, attempt, KnowledgeProcessingSpan.STATUS_CANCELLED,
                null, errorCode, errorMessage);
    }

    /** duration 计算：优先进程内 starts 缓存，回退 handle 的 startedAt（跨进程调用）。 */
    private long durationSince(SpanHandle span, OffsetDateTime now) {
        OffsetDateTime start = takeStart(span.spanId);
        if (start != null) {
            return now.toInstant().toEpochMilli() - start.toInstant().toEpochMilli();
        }
        if (span.startedAt != null) {
            return now.toInstant().toEpochMilli() - span.startedAt.toInstant().toEpochMilli();
        }
        return 0;
    }

    private static SpanHandle toHandle(KnowledgeProcessingSpan r) {
        SpanHandle handle = new SpanHandle();
        handle.knowledgeId = r.getKnowledgeId();
        handle.attempt = r.getAttempt();
        handle.spanId = r.getSpanId();
        handle.parentSpanId = r.getParentSpanId();
        handle.name = r.getName();
        handle.kind = r.getKind();
        handle.status = r.getStatus();
        handle.startedAt = r.getStartedAt();
        return handle;
    }

    /** best-effort upsert：失败只记日志（追踪器抖动不得破坏管道）。 */
    private boolean upsertQuiet(KnowledgeProcessingSpan row, String op, String a, String b) {
        try {
            repo.upsert(row);
            return true;
        } catch (RuntimeException e) {
            log.warn("[SpanTracker] {} failed a={} b={}: {}", op, a, b, e.toString());
            return false;
        }
    }
}
