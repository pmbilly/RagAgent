package com.ragagent.knowledge.domain;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 对照 Go {@code types.KnowledgeProcessingSpan}（types/knowledge_span.go L83-103）：
 * {@code knowledge_processing_spans} 表的一行（每次尝试一棵 span 树）。
 *
 * <p>本类供<b>仓储/追踪器</b>使用；HTTP 响应（trace 树）由读侧手写 ObjectNode 组装
 * （键序/omitempty 与 Go 逐字节一致，见 {@code KnowledgeService.buildSpanTree}）。</p>
 *
 * <p>字段语义同 Go：{@code input/output/metadata} 是 jsonb（null = 「本次不写」，
 * 对应 Upsert 的动态列——EndSpan 只写 output，不得清掉 Begin 写的 input）；
 * {@code errorDetail} 不对 API 暴露（Go json:"-"）。</p>
 */
public class KnowledgeProcessingSpan {

    // ── span kind（types/knowledge_span.go L18-23） ──────────────────────
    public static final String KIND_ROOT = "root";
    public static final String KIND_STAGE = "stage";
    public static final String KIND_SUB_SPAN = "subspan";
    public static final String KIND_GENERATION = "generation";

    // ── span status（L29-36） ───────────────────────────────────────────
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_SKIPPED = "skipped";
    public static final String STATUS_CANCELLED = "cancelled";

    // ── stage 名（L42-48） ──────────────────────────────────────────────
    public static final String STAGE_DOC_READER = "docreader";
    public static final String STAGE_CHUNKING = "chunking";
    public static final String STAGE_EMBEDDING = "embedding";
    public static final String STAGE_MULTIMODAL = "multimodal";
    public static final String STAGE_POST_PROCESS = "postprocess";

    private long id;
    private String knowledgeId = "";
    private int attempt;
    private String spanId = "";
    private String parentSpanId = "";
    private String name = "";
    private String kind = "";
    private String status = "";
    private Map<String, Object> input;
    private Map<String, Object> output;
    private Map<String, Object> metadata;
    private String errorCode = "";
    private String errorMessage = "";
    private String errorDetail = "";
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private long durationMs;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public long getId() { return id; }
    public void setId(long v) { id = v; }

    public String getKnowledgeId() { return knowledgeId == null ? "" : knowledgeId; }
    public void setKnowledgeId(String v) { knowledgeId = v == null ? "" : v; }

    public int getAttempt() { return attempt; }
    public void setAttempt(int v) { attempt = v; }

    public String getSpanId() { return spanId == null ? "" : spanId; }
    public void setSpanId(String v) { spanId = v == null ? "" : v; }

    public String getParentSpanId() { return parentSpanId == null ? "" : parentSpanId; }
    public void setParentSpanId(String v) { parentSpanId = v == null ? "" : v; }

    public String getName() { return name == null ? "" : name; }
    public void setName(String v) { name = v == null ? "" : v; }

    public String getKind() { return kind == null ? "" : kind; }
    public void setKind(String v) { kind = v == null ? "" : v; }

    public String getStatus() { return status == null ? "" : status; }
    public void setStatus(String v) { status = v == null ? "" : v; }

    public Map<String, Object> getInput() { return input; }
    public void setInput(Map<String, Object> v) { input = v; }

    public Map<String, Object> getOutput() { return output; }
    public void setOutput(Map<String, Object> v) { output = v; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> v) { metadata = v; }

    public String getErrorCode() { return errorCode == null ? "" : errorCode; }
    public void setErrorCode(String v) { errorCode = v == null ? "" : v; }

    public String getErrorMessage() { return errorMessage == null ? "" : errorMessage; }
    public void setErrorMessage(String v) { errorMessage = v == null ? "" : v; }

    public String getErrorDetail() { return errorDetail == null ? "" : errorDetail; }
    public void setErrorDetail(String v) { errorDetail = v == null ? "" : v; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime v) { startedAt = v; }

    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime v) { finishedAt = v; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long v) { durationMs = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }

    /** 复制（Go 的 struct 值拷贝语义；map 保持同引用）。 */
    public KnowledgeProcessingSpan copy() {
        KnowledgeProcessingSpan c = new KnowledgeProcessingSpan();
        c.id = id;
        c.knowledgeId = knowledgeId;
        c.attempt = attempt;
        c.spanId = spanId;
        c.parentSpanId = parentSpanId;
        c.name = name;
        c.kind = kind;
        c.status = status;
        c.input = input;
        c.output = output;
        c.metadata = metadata;
        c.errorCode = errorCode;
        c.errorMessage = errorMessage;
        c.errorDetail = errorDetail;
        c.startedAt = startedAt;
        c.finishedAt = finishedAt;
        c.durationMs = durationMs;
        c.createdAt = createdAt;
        c.updatedAt = updatedAt;
        return c;
    }
}
