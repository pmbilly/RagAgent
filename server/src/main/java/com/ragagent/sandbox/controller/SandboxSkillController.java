package com.ragagent.sandbox.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.llm.domain.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.sandbox.domain.SkillStatus;
import com.ragagent.sandbox.domain.TenantSkillEntity;
import com.ragagent.sandbox.service.SkillBundleParser;
import com.ragagent.sandbox.service.SkillProgress;
import com.ragagent.sandbox.service.SkillProgressStore;
import com.ragagent.sandbox.service.TenantSkillService;
import com.ragagent.session.sse.SseContract;
import com.ragagent.session.sse.SseFrameWriter;
import com.ragagent.stream.StreamBatch;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 对照 Go {@code handler.SandboxSkillHandler}（internal/handler/sandbox_skill.go，
 * 937 行全文；routes_infra.go L65-77 的 skills 组路由，<b>全程 Admin+</b>——注释原文：
 * 上传会驱动一个 root shell，其输出被烘进这份配置启动的每一个会话镜像）。
 *
 * <h2>响应形状（golden 钉死）</h2>
 * <ul>
 *   <li>{@link SkillResponse}：字段序 id,name,version?,description?,enabled,status,error?,
 *       bundle_sha256?,installed_snapshot_id?,install_session_id?,install_message_id?,
 *       created_at,updated_at,envs?——omitempty 全 NON_EMPTY/NON_DEFAULT；
 *       {@code envs[].value} 按构造不出现（{@code json:"-"}</li>
 *   <li>上传 202 {@code {"data":{"skill_id":…},"success":true}}；删除同形；</li>
 *   <li>SSE 帧格式 {@code event:message\ndata:<json>\n\n}（gin-contrib/sse 直译）；</li>
 *   <li>resolveSkill 的 404 以 JSON 返回、在<b>任何</b> SSE 头之前。</li>
 * </ul>
 *
 * <h2>错误分类（respondSkillServiceError L174-193，按 sentinel 而非消息）</h2>
 * bundle 校验整类与 source 校验 → 400；其余原样上抛（AppError 信封 / 500 plain）。
 */
@RestController
public class SandboxSkillController {

    private static final Logger log = LoggerFactory.getLogger(SandboxSkillController.class);

    private static final ObjectMapper BIND_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 对照 {@code skillSourceJSONMaxBytes}（upload_limit.go）。 */
    private static final long SKILL_SOURCE_JSON_MAX_BYTES = 64L << 10;

    /** 对照 {@code skillEventPollInterval}：活流重读 durable 状态的频率。 */
    private static final Duration SKILL_EVENT_POLL_INTERVAL = Duration.ofSeconds(5);

    /** 对照 {@code skillEventMaxDuration}：一条流的上限（卡死 run 的 reaper 兜底前的止损）。 */
    private static final Duration SKILL_EVENT_MAX_DURATION = Duration.ofMinutes(60);

    /** 对照 {@code skillStageDone / Failed / Detached} 与 {@code skillStatusRemoved}。 */
    private static final String STAGE_DONE = "done";
    private static final String STAGE_FAILED = "failed";
    private static final String STAGE_DETACHED = "detached";
    private static final String STATUS_REMOVED = "removed";

    private final TenantSkillService service;
    private final SseFrameWriter sseFrames;
    /** 全局 mapper（HTML 转义 = Go 的 json.Encoder），SSE 帧序列化用它。 */
    private final ObjectMapper frameMapper;
    /** 可为 null：无 Redis 的部署只损失 transcript 端点（Go 注释原文）。 */
    private final com.ragagent.stream.StreamManager streams;

    public SandboxSkillController(TenantSkillService service,
            SseFrameWriter sseFrames,
            ObjectMapper frameMapper,
            ObjectProvider<com.ragagent.stream.StreamManager> streams) {
        this.service = service;
        this.sseFrames = sseFrames;
        this.frameMapper = frameMapper;
        this.streams = streams.getIfAvailable();
    }

    // ── 响应投影（skillResponse / skillEnvResponse） ─────────────────────

    /**
     * 对照 {@code skillResponse}：SKILL.md 正文刻意省略——那是给 agent 的 level 2
     * disclosure，一份列表会把响应撑爆（Go 注释原文）。
     */
    // 注解逐字段对照 Go 的 json tag：恒输出的（enabled=false 也在内）不加 Include，
    // omitempty 的加 NON_EMPTY / NON_NULL——类级 NON_DEFAULT 会把 "enabled":false 整键吞掉
    public record SkillResponse(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("version") @JsonInclude(JsonInclude.Include.NON_EMPTY) String version,
            @JsonProperty("description") @JsonInclude(JsonInclude.Include.NON_EMPTY) String description,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("status") String status,
            @JsonProperty("error") @JsonInclude(JsonInclude.Include.NON_EMPTY) String error,
            @JsonProperty("bundle_sha256") @JsonInclude(JsonInclude.Include.NON_EMPTY) String bundleSha256,
            @JsonProperty("installed_snapshot_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String installedSnapshotId,
            @JsonProperty("install_session_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String installSessionId,
            @JsonProperty("install_message_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String installMessageId,
            @JsonProperty("created_at") @JsonSerialize(using = GoTimeSerializer.class) OffsetDateTime createdAt,
            @JsonProperty("updated_at") @JsonSerialize(using = GoTimeSerializer.class) OffsetDateTime updatedAt,
            @JsonProperty("envs") @JsonInclude(JsonInclude.Include.NON_NULL) List<SkillEnvResponse> envs) {

        static SkillResponse from(TenantSkillEntity e) {
            if (e == null) {
                return new SkillResponse("", "", "", "", false, "", "", "", "", "", "",
                        null, null, null);
            }
            return new SkillResponse(e.getId(), e.getName(), e.getVersion(), e.getDescription(),
                    e.isEnabled(), e.getStatus(), e.getError(), e.getBundleSha256(),
                    e.getInstalledSnapshotId(), e.getInstallSessionId(), e.getInstallMessageId(),
                    e.getCreatedAt(), e.getUpdatedAt(), toSkillEnvResponses(e.getEnvs()));
        }
    }

    /**
     * 对照 {@code skillEnvResponse}：报告工作区值<b>是否存在</b>，永远不报告它是什么
     * ——存下的凭据只被需要它的沙箱读取（Go 注释原文）。
     */
    public record SkillEnvResponse(
            @JsonProperty("name") String name,
            @JsonProperty("description") @JsonInclude(JsonInclude.Include.NON_EMPTY) String description,
            @JsonProperty("required") @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean required,
            @JsonProperty("is_set") boolean isSet) {
    }

    private static List<SkillEnvResponse> toSkillEnvResponses(
            com.ragagent.sandbox.domain.SkillEnvVars envs) {
        if (envs == null || envs.isEmpty()) {
            return null;
        }
        List<SkillEnvResponse> out = new ArrayList<>(envs.size());
        for (var entry : envs) {
            out.add(new SkillEnvResponse(entry.getName(), entry.getDescription(),
                    entry.isRequired(), !entry.getValue().isEmpty()));
        }
        return out;
    }

    // ── 13 条路由 ────────────────────────────────────────────────────────

    /** 对照 List。 */
    @GetMapping("/api/v1/sandbox-configs/{id}/skills")
    public ResponseEntity<Map<String, Object>> list(@PathVariable("id") String id) {
        List<TenantSkillEntity> skills = service.listSkills(tenantId(), id);
        List<SkillResponse> data = new ArrayList<>(skills.size());
        for (TenantSkillEntity skill : skills) {
            data.add(SkillResponse.from(skill));
        }
        return ResponseEntity.ok(envelopeData(data));
    }

    /** 对照 Get。 */
    @GetMapping("/api/v1/sandbox-configs/{id}/skills/{skillId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("id") String id,
            @PathVariable("skillId") String skillId) {
        return ResponseEntity.ok(envelopeData(SkillResponse.from(resolveSkill(id, skillId))));
    }

    /** 对照 ListFiles：不起沙箱浏览存量归档。 */
    @GetMapping("/api/v1/sandbox-configs/{id}/skills/{skillId}/files")
    public ResponseEntity<Map<String, Object>> listFiles(@PathVariable("id") String id,
            @PathVariable("skillId") String skillId) {
        return ResponseEntity.ok(envelopeData(service.listSkillFiles(tenantId(), id, skillId)));
    }

    /** 对照 GetFile：UTF-8 文本 / 小图 base64 / 二进制三态。 */
    @GetMapping("/api/v1/sandbox-configs/{id}/skills/{skillId}/files/content")
    public ResponseEntity<Map<String, Object>> getFile(@PathVariable("id") String id,
            @PathVariable("skillId") String skillId,
            @RequestParam(value = "path", defaultValue = "") String path) {
        return ResponseEntity.ok(envelopeData(
                service.readSkillFile(tenantId(), id, skillId, path)));
    }

    /** 对照 Upload：zip 走 multipart，application/json 走 source 定位符。 */
    @PostMapping("/api/v1/sandbox-configs/{id}/skills")
    public ResponseEntity<?> upload(@PathVariable("id") String id,
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request) throws IOException {
        long maxBytes = maxSkillBundleSize();
        if (contentTypeIsJson(request)) {
            return installFromSource(id, request);
        }

        if (file == null) {
            throw BizException.badRequest("file is required");
        }
        if (file.getSize() > maxBytes) {
            throw skillTooLargeError();
        }
        byte[] archive;
        try (var in = file.getInputStream()) {
            archive = in.readAllBytes();
        } catch (IOException e) {
            throw BizException.badRequest("failed to read the uploaded skill bundle");
        }
        // multipart part 可能少报大小，所以实际读到的字节也要查（Go 注释原文）
        if (archive.length > maxBytes) {
            throw skillTooLargeError();
        }

        try {
            String skillId = service.installSkill(tenantId(), id, archive);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(skillIdEnvelope(skillId));
        } catch (RuntimeException err) {
            respondSkillServiceError(err);
            return null; // 恒抛
        }
    }

    /** 对照 installFromSource（Upload 的 JSON 分支）。 */
    private ResponseEntity<?> installFromSource(String id, HttpServletRequest request)
            throws IOException {
        String raw = new String(request.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (raw.length() > SKILL_SOURCE_JSON_MAX_BYTES) {
            throw BizException.badRequest("skill source request is too large");
        }
        SkillSourceRequest req;
        try {
            req = raw.isEmpty() ? new SkillSourceRequest()
                    : BIND_MAPPER.readValue(raw, SkillSourceRequest.class);
            if (req == null) {
                req = new SkillSourceRequest();
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.badRequest("invalid skill source request");
        }
        String source = req.source == null ? "" : req.source.trim();
        if (source.isEmpty()) {
            throw BizException.badRequest("source is required");
        }
        try {
            String skillId = service.installSkillFromSource(tenantId(), id, source);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(skillIdEnvelope(skillId));
        } catch (RuntimeException err) {
            respondSkillServiceError(err);
            return null; // 恒抛
        }
    }

    /** 对照 Reinstall：从已存归档重试；不重新上传。 */
    @PostMapping("/api/v1/sandbox-configs/{id}/skills/{skillId}/reinstall")
    public ResponseEntity<?> reinstall(@PathVariable("id") String id,
            @PathVariable("skillId") String skillId,
            @RequestBody(required = false) String rawBody) {
        String instructions = "";
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                ReinstallRequest req = BIND_MAPPER.readValue(rawBody, ReinstallRequest.class);
                instructions = req.instructions == null ? "" : req.instructions;
                if (instructions.codePointCount(0, instructions.length()) > 10000) {
                    throw BizException.badRequest("invalid reinstall instructions");
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw BizException.badRequest("invalid reinstall instructions");
            }
        }
        try {
            String newSkillId = service.reinstallSkill(tenantId(), id, skillId, instructions);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(skillIdEnvelope(newSkillId));
        } catch (RuntimeException err) {
            respondSkillServiceError(err);
            return null; // 恒抛
        }
    }

    /** 对照 Stop：中止在途安装（重启后的僵尸行也立即改写）。 */
    @PostMapping("/api/v1/sandbox-configs/{id}/skills/{skillId}/stop")
    public ResponseEntity<?> stop(@PathVariable("id") String id,
            @PathVariable("skillId") String skillId) {
        try {
            TenantSkillEntity skill = service.stopSkill(tenantId(), id, skillId);
            return ResponseEntity.ok(envelopeData(SkillResponse.from(skill)));
        } catch (RuntimeException err) {
            respondSkillServiceError(err);
            return null; // 恒抛
        }
    }

    /** 对照 Patch：可见性与声明值一次写入（全有或全无）。 */
    @PatchMapping("/api/v1/sandbox-configs/{id}/skills/{skillId}")
    public ResponseEntity<?> patch(@PathVariable("id") String id,
            @PathVariable("skillId") String skillId,
            @RequestBody(required = false) String rawBody) {
        if (rawBody != null && rawBody.length() > SKILL_SOURCE_JSON_MAX_BYTES) {
            throw BizException.badRequest("skill request is too large");
        }
        SkillPatchRequest req;
        if (rawBody == null || rawBody.isEmpty()) {
            // Go 的 ShouldBindJSON 对空 body 返回 io.EOF，err.Error() 即 "EOF"
            throw BizException.badRequest("EOF");
        }
        try {
            req = BIND_MAPPER.readValue(rawBody, SkillPatchRequest.class);
        } catch (Exception e) {
            throw BizException.badRequest(GoJsonBindError.message(rawBody, e.getMessage()));
        }
        if (req.enabled == null && req.envs == null) {
            throw BizException.badRequest("enabled or envs is required");
        }

        TenantSkillService.SkillAdminUpdate update =
                new TenantSkillService.SkillAdminUpdate(req.enabled,
                        req.envs == null ? null : req.envs);
        TenantSkillEntity updated = service.updateSkillAdmin(tenantId(), id, skillId, update);
        if (updated == null) {
            throw BizException.notFound("skill not found");
        }
        return ResponseEntity.ok(envelopeData(SkillResponse.from(updated)));
    }

    /** 对照 Delete：受理后异步重建镜像（install-events 跟踪）。 */
    @DeleteMapping("/api/v1/sandbox-configs/{id}/skills/{skillId}")
    public ResponseEntity<?> delete(@PathVariable("id") String id,
            @PathVariable("skillId") String skillId) {
        service.removeSkill(tenantId(), id, skillId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(skillIdEnvelope(skillId));
    }

    /** 对照 InstallEvents：安装/移除的 SSE 进度；流必然终止。 */
    @GetMapping("/api/v1/sandbox-configs/{id}/skills/{skillId}/install-events")
    public void installEvents(@PathVariable("id") String id,
            @PathVariable("skillId") String skillId,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        long tenantId = tenantId();
        // 订阅先于首读：两次之间发布的事件被送达而不是错过（Go 注释原文）
        SkillProgressStore.Subscription sub = service.subscribeProgress(tenantId, id, skillId);

        // 这个 404 必须在任何 SSE 头之前以 JSON 渲染
        TenantSkillEntity skill = resolveSkill(id, skillId);

        setSSEHeaders(response);

        int lastPercent = 0;
        var last = service.lastProgress(tenantId, id, skillId);
        if (last.present()) {
            Frame lastFrame = frameFromProgress(last.progress());
            lastPercent = lastFrame.event().percent;
            if (!emit(response, lastFrame.json()) || lastFrame.event().done) {
                return;
            }
        }
        TerminalFrame terminal = terminalSkillEvent(skill);
        if (terminal.terminal()) {
            emit(response, terminal.json());
            return;
        }
        if (sub.events() == null) {
            // 没有 Redis 就没有进度发布；durable 状态一帧就是这条连接的全部
            emit(response, frameJson(new SkillInstallEvent(0, skill.getStatus(),
                    "live progress is unavailable; poll the skill for its status",
                    skill.getStatus(), true)));
            return;
        }

        // 对照 Go 的 select 循环：事件到达 / 5s 轮询 / 60min 死线。ctx.Done() 分支在
        // 同步写路径下由写失败检测代替（emit 返回 false）。
        long deadline = System.nanoTime() + SKILL_EVENT_MAX_DURATION.toNanos();
        while (true) {
            if (System.nanoTime() >= deadline) {
                emit(response, frameJson(new SkillInstallEvent(lastPercent, STAGE_DETACHED,
                        "stopped following this run; reconnect or poll the skill for its status",
                        skill.getStatus(), true)));
                return;
            }
            SkillProgress p = null;
            try {
                p = sub.events().poll(SKILL_EVENT_POLL_INTERVAL.toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (p != null) {
                Frame frame = frameFromProgress(p);
                lastPercent = frame.event().percent;
                if (!emit(response, frame.json()) || frame.event().done) {
                    return;
                }
                continue;
            }
            // 5s 轮询：run 结束却没发布任何事件（重复移除的早退）时，是这个轮询结束流
            TenantSkillEntity current;
            try {
                current = service.getSkill(tenantId, id, skillId);
            } catch (RuntimeException e) {
                log.warn("[skill] re-read {} while streaming failed: {}", skillId, e.getMessage());
                if (!emitComment(response)) {
                    return;
                }
                continue;
            }
            TerminalFrame t = terminalSkillEvent(current);
            if (t.terminal()) {
                emit(response, t.json());
                return;
            }
            // 没有可报告的：注释保温，并探出已经离开的客户端
            if (!emitComment(response)) {
                return;
            }
        }
    }

    /** 对照 InstallTranscript：installer agent 转写的 SSE 回放 + 活跟踪。 */
    @GetMapping("/api/v1/sandbox-configs/{id}/skills/{skillId}/transcript")
    public void installTranscript(@PathVariable("id") String id,
            @PathVariable("skillId") String skillId,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        TenantSkillEntity skill = resolveSkill(id, skillId);
        String sessionId = skill.getInstallSessionId();
        String messageId = skill.getInstallMessageId();
        if (sessionId.isEmpty() || messageId.isEmpty()) {
            // 行在接受上传那一刻就存在；定位符要等 installer 沙箱起来才写。
            // 这里的 404 是"还没有"而不是"没了"（Go 注释原文）
            if (SkillStatus.INSTALLING.equals(skill.getStatus())) {
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return;
            }
            throw BizException.notFound("this skill has no install transcript");
        }
        if (streams == null) {
            throw BizException.notFound("install transcripts are unavailable");
        }

        StreamBatch batch;
        try {
            batch = streams.getEvents(sessionId, messageId, 0);
        } catch (RuntimeException e) {
            log.error("[skill] read install transcript of {} failed: {}", skill.getId(),
                    e.getMessage());
            throw BizException.internal(e.getMessage() == null ? "" : e.getMessage());
        }
        // 空日志 = run 早于转写或 TTL 已过；在任何 SSE 头之前拒绝，
        // 让调用方回落 durable 消息历史（Go 注释原文）
        if (batch.events().isEmpty()) {
            throw BizException.notFound("this install's event log is no longer available");
        }

        setSSEHeaders(response);

        int[] offset = {0};
        if (emitTranscript(response, sessionId, messageId, batch)) {
            return;
        }
        offset[0] = batch.nextOffset();

        // 按 chat 流的节奏 tail：installer 的 thinking token 一个个来，慢了会成串爆发
        long deadline = System.nanoTime() + SKILL_EVENT_MAX_DURATION.toNanos();
        while (true) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (System.nanoTime() >= deadline) {
                return;
            }
            StreamBatch tail;
            try {
                tail = streams.getEvents(sessionId, messageId, offset[0]);
            } catch (RuntimeException e) {
                log.warn("[skill] tail install transcript of {} failed: {}", skill.getId(),
                        e.getMessage());
                return;
            }
            offset[0] = tail.nextOffset();
            if (emitTranscript(response, sessionId, messageId, tail)) {
                return;
            }
        }
    }

    /** 对照 InstallGuidance：只暴露本 run 的指引。 */
    @GetMapping("/api/v1/sandbox-configs/{id}/skills/{skillId}/guidance")
    public ResponseEntity<Map<String, Object>> installGuidance(@PathVariable("id") String id,
            @PathVariable("skillId") String skillId) {
        try {
            TenantSkillService.SkillInstallGuidanceState state =
                    service.installGuidance(tenantId(), id, skillId);
            return ResponseEntity.ok(envelopeData(state));
        } catch (RuntimeException err) {
            respondSkillServiceError(err);
            return null; // 恒抛
        }
    }

    /** 对照 SteerInstall：接受管理员对显示中的安装 run 的指引。 */
    @PostMapping("/api/v1/sandbox-configs/{id}/skills/{skillId}/guidance")
    public ResponseEntity<?> steerInstall(@PathVariable("id") String id,
            @PathVariable("skillId") String skillId,
            @RequestBody(required = false) String rawBody) {
        SteerRequest req;
        try {
            if (rawBody == null || rawBody.isEmpty()) {
                throw new IllegalArgumentException("EOF");
            }
            req = BIND_MAPPER.readValue(rawBody, SteerRequest.class);
        } catch (Exception e) {
            throw BizException.badRequest("invalid install guidance");
        }
        try {
            service.steerInstall(tenantId(), id, skillId, req.expectedMessageId, req.steerId,
                    req.content);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(successOnly());
        } catch (RuntimeException err) {
            respondSkillServiceError(err);
            return null; // 恒抛
        }
    }

    // ── 帧形状（skillInstallEvent / terminalSkillEvent） ─────────────────

    /**
     * 对照 {@code skillInstallEvent}：Done 显式携带，客户端按标志终止而非按 stage 名。
     * percent/done 无 omitempty（0/false 也输出）；log/status 才是 omitempty。
     */
    @JsonPropertyOrder({"percent", "stage", "log", "status", "done"})
    public record SkillInstallEvent(
            @JsonProperty("percent") int percent,
            @JsonProperty("stage") String stage,
            @JsonProperty("log") @JsonInclude(JsonInclude.Include.NON_EMPTY) String log,
            @JsonProperty("status") @JsonInclude(JsonInclude.Include.NON_EMPTY) String status,
            @JsonProperty("done") boolean done) {
    }

    private record Frame(SkillInstallEvent event, String json) {
    }

    private record TerminalFrame(boolean terminal, String json) {
    }

    private Frame frameFromProgress(SkillProgress p) {
        SkillInstallEvent e = new SkillInstallEvent(p.percent, p.stage, p.log, p.status,
                STAGE_DONE.equals(p.stage) || STAGE_FAILED.equals(p.stage));
        return new Frame(e, frameJson(e));
    }

    /**
     * 对照 {@code terminalSkillEvent}：从 durable 状态推导流尾帧——每一个没有发布
     * 终帧就结束的 run（重复移除的早退、进程死掉的 run）都由此收尾。null skill 是
     * 完成的移除：行被最后一步删掉了。
     */
    private TerminalFrame terminalSkillEvent(TenantSkillEntity skill) {
        if (skill == null) {
            return new TerminalFrame(true, frameJson(new SkillInstallEvent(100, STAGE_DONE,
                    "", STATUS_REMOVED, true)));
        }
        switch (skill.getStatus()) {
            case SkillStatus.INSTALLING, SkillStatus.REMOVING:
                return new TerminalFrame(false, "");
            case SkillStatus.FAILED:
                return new TerminalFrame(true, frameJson(new SkillInstallEvent(100,
                        STAGE_FAILED, skill.getStatus(), skill.getError(), true)));
            default:
                return new TerminalFrame(true, frameJson(new SkillInstallEvent(100,
                        STAGE_DONE, skill.getError(), skill.getStatus(), true)));
        }
    }

    /**
     * 帧的 JSON 用全局 mapper（HTML 转义与 Go 的 json.Encoder 一致，见 SseFrameWriter
     * 类注释——不要在这里另挂私有 mapper）。
     */
    private String frameJson(SkillInstallEvent event) {
        try {
            return frameMapper.writeValueAsString(event);
        } catch (IOException e) {
            throw new IllegalStateException("failed to marshal skill install event", e);
        }
    }

    // ── 写帧（gin-contrib/sse 直译） ─────────────────────────────────────

    /**
     * 对照 {@code setSandboxSkillSSEHeaders}。Content-Type 随后会被渲染器覆盖成
     * {@code text/event-stream;charset=utf-8}（gin 的 WriteContentType 无条件覆盖，
     * 与 session 包同一结论）。
     */
    private static void setSSEHeaders(HttpServletResponse response) {
        SseContract.setSSEHeaders(response);
        SseFrameWriter.applyRenderedContentType(response);
    }

    /** 对照 {@code emit}：写一帧并报告流能否继续（写失败 = 客户端没了）。 */
    private boolean emit(HttpServletResponse response, String json) {
        try {
            StringBuilder frame = new StringBuilder(json.length() + 32);
            frame.append("event:message\n");
            frame.append("data:").append(json).append('\n').append('\n');
            response.getOutputStream().write(frame.toString().getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 对照 {@code emitComment}：keep-alive 注释保活并探测已离开的客户端。 */
    private static boolean emitComment(HttpServletResponse response) {
        try {
            response.getOutputStream().write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 对照 {@code emitTranscript}：写帧并报告流是否已结束（run 完成 / 观看者离开）。
     */
    private boolean emitTranscript(HttpServletResponse response, String sessionId,
            String messageId, StreamBatch batch) throws IOException {
        for (var evt : batch.events()) {
            StreamResponse payload = new StreamResponse();
            // 每帧都带 assistant 消息 ID，控制台才能把整个 run 归成一轮（Go 注释原文）
            payload.setId(messageId);
            payload.setResponseType(evt.getType());
            payload.setContent(evt.getContent());
            payload.setDone(evt.isDone());
            payload.setSessionId(sessionId);
            payload.setAssistantMessageId(messageId);
            payload.setData(evt.getData());
            sseFrames.write(response, payload);
            if (ResponseType.COMPLETE.equals(evt.getType())) {
                return true;
            }
        }
        return false;
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────

    /** 对照 {@code resolveSkill}：按调用方的工作区+配置装载 skill，不可达即 404。 */
    private TenantSkillEntity resolveSkill(String id, String skillId) {
        TenantSkillEntity skill = service.getSkill(tenantId(), id, skillId);
        if (skill == null) {
            throw BizException.notFound("skill not found");
        }
        return skill;
    }

    /**
     * 对照 {@code respondSkillServiceError}：上传归档的一切拒绝升为 400——按 sentinel
     * 匹配而非消息，改写措辞的校验错误不能悄悄开始对坏输入返回 500（Go 注释原文）。
     * 恒以抛出收尾。包内可见供分类表单测（Go 侧同为包级未导出）。
     */
    static void respondSkillServiceError(RuntimeException err) {
        if (err instanceof SkillBundleParser.BundleInvalidException
                || err instanceof TenantSkillService.SkillSourceInvalidException) {
            throw BizException.badRequest(err.getMessage());
        }
        throw err;
    }

    private static BizException skillTooLargeError() {
        return BizException.badRequest(
                "skill bundle cannot exceed " + maxSkillBundleSizeMB() + " MB");
    }

    /** 对照 {@code GetMaxSkillBundleSizeMB}：env MAX_SKILL_BUNDLE_SIZE_MB，缺省 256。 */
    static long maxSkillBundleSizeMB() {
        String raw = System.getenv("MAX_SKILL_BUNDLE_SIZE_MB");
        if (raw != null) {
            try {
                long v = Long.parseLong(raw.trim());
                if (v > 0) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // 走缺省
            }
        }
        return 256;
    }

    static long maxSkillBundleSize() {
        return maxSkillBundleSizeMB() * 1024 * 1024;
    }

    /** 对照 {@code limitSkillUploadBody} 的类型判定。 */
    private static boolean contentTypeIsJson(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase(java.util.Locale.ROOT)
                .startsWith("application/json");
    }

    /** 对照 {@code sandboxConfigTenantID}：gin 的 GetUint64 缺席即 0。 */
    private static long tenantId() {
        Long tenantId = TenantContext.currentTenantId();
        return tenantId == null ? 0L : tenantId;
    }

    /** gin.H：{"data":...,"success":true}（字母序 data &lt; success）。 */
    private static Map<String, Object> envelopeData(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }

    /** gin.H：{"success":true}。 */
    private static Map<String, Object> successOnly() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return body;
    }

    private static Map<String, Object> skillIdEnvelope(String skillId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("skill_id", skillId);
        return envelopeData(data);
    }

    // ── 请求形状 ─────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SkillSourceRequest {
        @JsonProperty("source")
        String source = "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ReinstallRequest {
        @JsonProperty("instructions")
        String instructions = "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SkillPatchRequest {
        /** Enabled 是指针：缺席不是"禁用"的请求；body 可以只带 envs。 */
        @JsonProperty("enabled")
        Boolean enabled;
        /** 指针到 map："发了空对象"与"没提 envs"是两个请求。 */
        @JsonProperty("envs")
        Map<String, String> envs;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SteerRequest {
        @JsonProperty("expected_message_id")
        String expectedMessageId = "";
        @JsonProperty("steer_id")
        String steerId = "";
        @JsonProperty("content")
        String content = "";
    }

    // ══════════════════════════ 错误形态分支 ══════════════════════════

    /**
     * 非 AppError（catalog/仓储/传输错误）→ Go 全局 ErrorHandler 的 plain 分支：
     * 500 + {@code {"error":{"code":1007,"message":"Internal server error"},"success":false}}
     * （<b>无 details 键</b>）。与 FAQ/子批 2 批同款 controller-local handler：
     * 只处理本控制器的异常，不污染全局；刻意不列 BizException（AppError 信封由
     * 全局处理器渲染）。
     */
    @org.springframework.web.bind.annotation.ExceptionHandler({IllegalStateException.class,
            com.ragagent.sandbox.runtime.RemoteError.class,
            org.springframework.dao.DataAccessException.class})
    public ResponseEntity<Map<String, Object>> handlePlainInternal(Exception ex) {
        log.error("sandbox skill operation failed", ex);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", com.ragagent.common.error.ErrorCode.INTERNAL_SERVER.value());
        error.put("message", "Internal server error");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("success", false);
        return ResponseEntity.status(500).body(body);
    }
}
