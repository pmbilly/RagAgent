package com.ragagent.sandbox.controller;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.error.BizException;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.sandbox.domain.SandboxConfigRedaction;
import com.ragagent.sandbox.domain.TenantSandboxConfig;
import com.ragagent.sandbox.domain.TenantSandboxConfigEntity;
import com.ragagent.sandbox.runtime.DockerBackendDisabledException;
import com.ragagent.sandbox.runtime.SandboxConfigIncompleteException;
import com.ragagent.sandbox.runtime.UnsafeOutboundURLException;
import com.ragagent.sandbox.runtime.UnsupportedSandboxTypeException;
import com.ragagent.sandbox.service.SandboxConfigServiceErrors.NamedSandboxBackendUnsupportedException;
import com.ragagent.sandbox.service.SandboxConfigServiceErrors.SandboxConfigCordonedException;
import com.ragagent.sandbox.service.SandboxConfigServiceErrors.SandboxConfigNameRequiredException;
import com.ragagent.sandbox.service.SandboxConfigServiceErrors.SandboxInventoryUnverifiableException;
import com.ragagent.sandbox.service.SandboxConfigServiceErrors.SandboxesStillLiveException;
import com.ragagent.sandbox.service.SandboxConfigServiceErrors.SkillSnapshotBlocksTemplateChangeException;
import com.ragagent.sandbox.service.SandboxConfigServiceErrors.SkillSnapshotReleaseFailedException;
import com.ragagent.sandbox.service.SandboxInventory;
import com.ragagent.sandbox.service.TenantSandboxConfigService;
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
 * 对照 Go {@code handler.SandboxConfigHandler}（internal/handler/sandbox_config.go，全文；
 * routes_infra.go L52-74 的 8 条路由，角色门：List/Get Viewer+、其余 Admin+）。
 *
 * <h2>错误形态分层（逐端点照抄，不统一）</h2>
 * <ul>
 *   <li><b>特殊拒绝体全是裸 gin.H，非 AppError 信封</b>（map → 键按字母序）：
 *       409 sandboxes_still_live（error.code 是<b>字符串</b>、中文 message、data=inventory）、
 *       409 sandbox_inventory_unverifiable、409 skill_snapshot_release_failed、
 *       409 skill_snapshot_blocks_template、423 sandbox_config_cordoned——逐字对照
 *       handler L113-193；</li>
 *   <li>sentinel→400 分类（respondSandboxConfigServiceError，L198-210）：name required /
 *       backend unsupported / unsupported type / unsafe URL / incomplete / docker disabled
 *       六类按<b>类型</b>（对照 errors.Is）转 400 AppError，其余原样上抛
 *       （BizException → 信封；普通异常 → 500 固定文案）；</li>
 *   <li>Get/Update 的 404 是 AppError 信封 "sandbox config not found"；Delete 的
 *       c.Error 直通（信封或 500）；</li>
 *   <li>binding 失败 → 400 code 1000，EOF/顶层解析错误经 {@link GoJsonBindError}
 *       仿真 Go encoding/json 措辞。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sandbox-configs")
public class SandboxConfigController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SandboxConfigController.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final TenantSandboxConfigService service;

    public SandboxConfigController(TenantSandboxConfigService service) {
        this.service = service;
    }

    // ── 请求/响应形状（对照 sandboxConfigRequest / sandboxConfigResponse 等） ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SandboxConfigRequest {
        @JsonProperty("name")
        String name = "";

        @JsonProperty("description")
        String description = "";

        @JsonProperty("config")
        TenantSandboxConfig config;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class WorkspacePolicyRequest {
        @JsonProperty("scripts_disabled")
        boolean scriptsDisabled;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SandboxTemplateQueryRequest {
        @JsonProperty("config")
        TenantSandboxConfig config;

        @JsonProperty("config_id")
        String configId = "";

        @JsonProperty("ensure_standard")
        boolean ensureStandard;

        @JsonProperty("replace_standard")
        boolean replaceStandard;
    }

    /**
     * 对照 {@code sandboxConfigResponse}：存储配置对外的唯一投影——新的读路径不能
     * 意外返回解密凭据。键序 = Go struct 声明序；description 是唯一的 omitempty。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SandboxConfigResponse(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("description") @JsonInclude(JsonInclude.Include.NON_EMPTY) String description,
            @JsonProperty("sandbox_type") String sandboxType,
            @JsonProperty("config") TenantSandboxConfig config,
            @JsonProperty("created_at") @JsonSerialize(using = GoTimeSerializer.class) OffsetDateTime createdAt,
            @JsonProperty("updated_at") @JsonSerialize(using = GoTimeSerializer.class) OffsetDateTime updatedAt) {

        static SandboxConfigResponse from(TenantSandboxConfigEntity e) {
            return new SandboxConfigResponse(
                    e.getId(),
                    e.getName(),
                    e.getDescription(),
                    e.getSandboxType(),
                    SandboxConfigRedaction.sandboxConfigForResponse(e.getConfig(), true),
                    e.getCreatedAt(),
                    e.getUpdatedAt());
        }
    }

    // ── 8 条路由 ────────────────────────────────────────────────────────

    /** 对照 List：gin.H 三键字母序 data &lt; success &lt; workspace_scripts_disabled。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        long tenantId = tenantId();
        List<TenantSandboxConfigEntity> configs = service.list(tenantId);
        boolean disabled = service.workspaceScriptsDisabled(tenantId);
        List<SandboxConfigResponse> data = new ArrayList<>(configs.size());
        for (TenantSandboxConfigEntity cfg : configs) {
            data.add(SandboxConfigResponse.from(cfg));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        body.put("workspace_scripts_disabled", disabled);
        return ResponseEntity.ok(body);
    }

    /** 对照 SetWorkspacePolicy：全工作区切换脚本执行。 */
    @PutMapping("/workspace-policy")
    public ResponseEntity<Map<String, Object>> setWorkspacePolicy(
            @RequestBody(required = false) String rawBody) {
        WorkspacePolicyRequest req = bind(rawBody, WorkspacePolicyRequest.class);
        service.setWorkspaceScriptsDisabled(tenantId(), req.scriptsDisabled);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("workspace_scripts_disabled", req.scriptsDisabled);
        return ResponseEntity.ok(body);
    }

    /** 对照 QueryTemplates：读未保存连接可见的模板；本批失败分支确定（service 类注释）。 */
    @PostMapping("/templates/query")
    public ResponseEntity<?> queryTemplates(@RequestBody(required = false) String rawBody) {
        SandboxTemplateQueryRequest req = bind(rawBody, SandboxTemplateQueryRequest.class);
        try {
            var result = service.queryTemplates(tenantId(), new TenantSandboxConfigService
                    .SandboxTemplateQueryInput(
                    req.config, req.configId, req.ensureStandard, req.replaceStandard));
            return ResponseEntity.ok(envelopeData(result));
        } catch (RuntimeException err) {
            ResponseEntity<?> refusal = respondRefusal(err);
            if (refusal != null) {
                return refusal;
            }
            respondServiceError(err);
            return null; // respondServiceError 恒抛
        }
    }

    /** 对照 Create：201 + 掩码响应。 */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) String rawBody) {
        SandboxConfigRequest req = bindSandboxConfigRequest(rawBody);
        try {
            var created = service.create(tenantId(), new TenantSandboxConfigService
                    .CreateSandboxConfigInput(req.name, req.description, req.config));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(envelopeData(SandboxConfigResponse.from(created)));
        } catch (RuntimeException err) {
            respondServiceError(err);
            return null; // respondServiceError 恒抛
        }
    }

    /** 对照 Get：404 "sandbox config not found"（AppError 信封）。 */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable("id") String id) {
        TenantSandboxConfigEntity cfg = service.get(tenantId(), id);
        if (cfg == null) {
            throw BizException.notFound("sandbox config not found");
        }
        return ResponseEntity.ok(envelopeData(SandboxConfigResponse.from(cfg)));
    }

    /**
     * 对照 Update：配置拥有活/暂停沙箱时拒绝身份字段变更（409 三态 + 423 cordoned）。
     * 404 来自 service 返回 nil 的分支（对照 Go 的 updated == nil）。
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        SandboxConfigRequest req = bindSandboxConfigRequest(rawBody);
        TenantSandboxConfigEntity updated;
        try {
            updated = service.update(tenantId(), id, new TenantSandboxConfigService
                    .UpdateSandboxConfigInput(req.name, req.description, req.config));
        } catch (RuntimeException err) {
            ResponseEntity<?> refusal = respondRefusal(err);
            if (refusal != null) {
                return refusal;
            }
            respondServiceError(err);
            return null; // respondServiceError 恒抛
        }
        if (updated == null) {
            throw BizException.notFound("sandbox config not found");
        }
        return ResponseEntity.ok(envelopeData(SandboxConfigResponse.from(updated)));
    }

    /** 对照 Delete：?force=true 只覆盖无法核实的 provider 清单，从不覆盖确认活着的沙箱。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") String id,
            @RequestParam(value = "force", defaultValue = "false") String force) {
        try {
            service.delete(tenantId(), id, "true".equals(force));
            return ResponseEntity.ok(successOnly());
        } catch (RuntimeException err) {
            // 对照 Go：Delete 走 refusal → c.Error 直通（不经 sentinel→400 分类）
            ResponseEntity<?> refusal = respondRefusal(err);
            if (refusal != null) {
                return refusal;
            }
            throw err;
        }
    }

    /** 对照 Inventory：一份配置的活/暂停沙箱清单与受影响 agent 名。 */
    @GetMapping("/{id}/sandboxes")
    public ResponseEntity<?> inventory(@PathVariable("id") String id) {
        SandboxInventory inv = service.inventory(tenantId(), id);
        return ResponseEntity.ok(envelopeData(inv));
    }

    // ── 拒绝体与错误分类（L113-210 逐字对照） ───────────────────────────

    /** 对照 respondSandboxesStillLive：携带恰好拒绝这次写入的 inventory。 */
    private static ResponseEntity<Map<String, Object>> respondSandboxesStillLive(SandboxInventory inv) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "sandboxes_still_live");
        error.put("data", inv);
        error.put("message", "该配置仍有运行中或已暂停的沙箱，请先结束或删除相关会话，或新建一份配置");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("success", false);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private static ResponseEntity<Map<String, Object>> respondSandboxInventoryUnverifiable() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "sandbox_inventory_unverifiable");
        error.put("message", "无法连接该后端核实是否仍有沙箱");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("success", false);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private static ResponseEntity<Map<String, Object>> respondSkillSnapshotReleaseFailed(
            List<String> remaining) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("snapshot_ids", remaining);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "skill_snapshot_release_failed");
        error.put("data", data);
        error.put("message", "无法销毁该配置下的技能快照，已中止删除以免快照继续计费");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("success", false);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private static ResponseEntity<Map<String, Object>> respondSkillSnapshotBlocksTemplate() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "skill_snapshot_blocks_template");
        error.put("message", "该配置已安装 Skill，不能更换连接、DNS 或重建运行模板。请新建一份沙箱后再装 Skill。");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("success", false);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private static ResponseEntity<Map<String, Object>> respondSandboxConfigCordoned() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "sandbox_config_cordoned");
        error.put("message", "该配置正在被其他人修改，请稍后重试");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("success", false);
        return ResponseEntity.status(HttpStatus.LOCKED).body(body);
    }

    /** 对照 respondSandboxConfigRefusal：五类拒绝体；非拒绝错误返回 null。 */
    private static ResponseEntity<?> respondRefusal(RuntimeException err) {
        if (err instanceof SandboxesStillLiveException liveErr) {
            return respondSandboxesStillLive(liveErr.inventory());
        }
        if (err instanceof SandboxInventoryUnverifiableException) {
            return respondSandboxInventoryUnverifiable();
        }
        if (err instanceof SkillSnapshotReleaseFailedException releaseErr) {
            return respondSkillSnapshotReleaseFailed(releaseErr.remaining());
        }
        if (err instanceof SkillSnapshotBlocksTemplateChangeException) {
            return respondSkillSnapshotBlocksTemplate();
        }
        if (err instanceof SandboxConfigCordonedException) {
            return respondSandboxConfigCordoned();
        }
        return null;
    }

    /**
     * 对照 respondSandboxConfigServiceError：service 的输入校验 sentinel 升为 400。
     * 按<b>类型</b>匹配而非消息（Go 注释：改写措辞的错误不能悄悄开始对坏输入返回 500）。
     * 恒以抛出收尾。
     */
    private static void respondServiceError(RuntimeException err) {
        if (err instanceof SandboxConfigNameRequiredException
                || err instanceof NamedSandboxBackendUnsupportedException
                || err instanceof UnsupportedSandboxTypeException
                || err instanceof UnsafeOutboundURLException
                || err instanceof SandboxConfigIncompleteException
                || err instanceof DockerBackendDisabledException) {
            throw BizException.badRequest(err.getMessage());
        }
        throw err;
    }

    // ── 绑定与信封辅助 ──────────────────────────────────────────────────

    /**
     * 对照 sandboxConfigTenantID：gin 的 GetUint64 缺席即 0——handler 不做租户守卫
     * （路由组已要求 Viewer+，实际不可达 0）。刻意不加 401，保持 Go 形状。
     */
    private static long tenantId() {
        Long tenantId = TenantContext.currentTenantId();
        return tenantId == null ? 0L : tenantId;
    }

    /** 对照 ShouldBindJSON：EOF/顶层解析错误仿真 Go 措辞；null 字面量按零值绑定。 */
    private static <T> T bind(String rawBody, Class<T> type) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw BizException.badRequest("EOF");
        }
        try {
            T value = MAPPER.readValue(rawBody, type);
            if (value == null) {
                value = MAPPER.readValue("{}", type);
            }
            return value;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.badRequest(GoJsonBindError.message(rawBody, e.getMessage()));
        }
    }

    /**
     * 对照 sandboxConfigRequest 的 {@code binding:"required"}：name 是 required string
     * （空串/缺失都失败）。validator 原文稳定，逐字复刻。
     */
    private static SandboxConfigRequest bindSandboxConfigRequest(String rawBody) {
        SandboxConfigRequest req = bind(rawBody, SandboxConfigRequest.class);
        if (req.name == null || req.name.isEmpty()) {
            throw BizException.badRequest("Key: 'sandboxConfigRequest.Name' "
                    + "Error:Field validation for 'Name' failed on the 'required' tag");
        }
        return req;
    }

    /** gin.H：{"data":..., "success":true}（字母序 data &lt; success）。 */
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

    // ══════════════════════════ 错误形态分支 ══════════════════════════

    /**
     * 非 AppError（provider 盘点/目录读取/仓储错误）→ Go 全局 ErrorHandler 的
     * plain 分支：500 + {@code {"error":{"code":1007,"message":"Internal server
     * error"},"success":false}}（<b>无 details 键</b>——与 AppError 信封的
     * "details":null 刻意不同，golden tpl-cube-unreachable 实录）。与 FAQ 批同款：
     * 只处理本控制器的异常，不污染全局（common 文件零改动）。刻意不列
     * BizException——它必须继续走全局的 AppError 信封。
     */
    @org.springframework.web.bind.annotation.ExceptionHandler({IllegalStateException.class,
            com.ragagent.sandbox.runtime.RemoteError.class,
            org.springframework.dao.DataAccessException.class})
    public ResponseEntity<Map<String, Object>> handlePlainInternal(Exception ex) {
        log.error("sandbox config operation failed", ex);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", com.ragagent.common.error.ErrorCode.INTERNAL_SERVER.value());
        error.put("message", "Internal server error");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("success", false);
        return ResponseEntity.status(500).body(body);
    }
}
