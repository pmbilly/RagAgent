package com.ragagent.sandbox.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.sandbox.domain.TenantSkillCatalogEntity;
import com.ragagent.sandbox.domain.TenantSkillEntity;
import com.ragagent.sandbox.service.SkillBundleParser;
import com.ragagent.sandbox.service.SkillCatalogView;
import com.ragagent.sandbox.service.TenantSkillService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 对照 Go {@code handler.SkillHandler}（internal/handler/skill_handler.go 全文 +
 * skill_catalog.go 全文；routes_agent.go RegisterSkillRoutes L70-90）。
 *
 * <h2>路由与门禁</h2>
 * <ul>
 *   <li>GET /skills、GET /skills/catalog：Viewer+（catalog 读是 Viewer+，让 agent
 *       编辑器能展示未安装的 skill——Go 注释原文）；API key 能力未声明 → 默认拒绝
 *       （不在 APIKeyRoutePolicies 登记）。</li>
 *   <li>catalogWrite 五条：Admin+ 且 full-access key（catalog 写会烤进沙箱镜像，
 *       scoped key 不能持有它们——Go 注释原文）。</li>
 * </ul>
 *
 * <h2>响应形状（golden 钉死）</h2>
 * <ul>
 *   <li>ListSkills（无 sandbox_config_id）→ gin.H 字母序
 *       {@code {"data":[],"skills_available":false,"success":true}}；</li>
 *   <li>register → <b>201</b> {@code {"data":{description,id,name,version},"success":true}}
 *       （gin.H 字母序；version 空串也输出——map 无 omitempty）；</li>
 *   <li>install → <b>202</b> {@code {"data":{"installs":{configId:skillId}},"success":true}}
 *       （异步受理，首个 provider 调用在后台失败——同子批 3 模式）；errors 非空时
 *       success=false（gin.H 字母序 errors &lt; installs）；</li>
 *   <li>delete 被安装钉住 → 409 code 1005。</li>
 * </ul>
 */
@RestController
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private static final ObjectMapper BIND_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 对照 {@code skillSourceJSONMaxBytes}（upload_limit.go）。 */
    private static final long SKILL_SOURCE_JSON_MAX_BYTES = 64L << 10;

    private final TenantSkillService service;

    public SkillController(TenantSkillService service) {
        this.service = service;
    }

    /** 对照 {@code SkillInfoResponse}（@ 选择器与 agent 编辑器消费的形状）。 */
    public record SkillInfoResponse(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description) {
    }

    // ── GET /skills ──────────────────────────────────────────────────────

    /**
     * 对照 {@code ListSkills}：不传 sandbox_config_id 时列表为空（可用 skill 集合
     * 是"当前沙箱配置镜像内"的，没有配置就没有集合——Go 注释原文）。
     */
    @GetMapping("/api/v1/skills")
    public ResponseEntity<Map<String, Object>> listSkills(
            @RequestParam(value = "sandbox_config_id", defaultValue = "") String configId) {
        Map<String, Object> body = new TreeMap<>();
        if (configId.isEmpty()) {
            body.put("data", List.of());
            body.put("skills_available", false);
            body.put("success", true);
            return ResponseEntity.ok(body);
        }
        List<TenantSkillEntity> rows = service.listUsableSkills(tenantId(), configId);
        List<SkillInfoResponse> response = new java.util.ArrayList<>(rows.size());
        for (TenantSkillEntity row : rows) {
            if (row == null) {
                continue;
            }
            response.add(new SkillInfoResponse(row.getName(), row.getDescription()));
        }
        body.put("data", response);
        body.put("skills_available", true);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ── GET /skills/catalog ──────────────────────────────────────────────

    /** 对照 {@code ListCatalog}：工作区每份 skill 定义 + 装在哪些沙箱上。 */
    @GetMapping("/api/v1/skills/catalog")
    public ResponseEntity<Map<String, Object>> listCatalog() {
        List<SkillCatalogView> rows = service.listCatalog(tenantId());
        return ResponseEntity.ok(envelopeData(rows));
    }

    // ── POST /skills/catalog（multipart | JSON source） ──────────────────

    /**
     * 对照 {@code RegisterCatalog}：multipart 字段 "file" 传 zip，或 JSON
     * {"source":"..."}（Go 注释原文）。只记定义不安装。
     */
    @PostMapping("/api/v1/skills/catalog")
    public ResponseEntity<?> registerCatalog(
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request) throws IOException {
        long maxBytes = SandboxSkillController.maxSkillBundleSize();
        if (contentTypeIsJson(request)) {
            return registerCatalogFromSource(request);
        }

        if (file == null) {
            throw BizException.badRequest("file is required");
        }
        if (file.getSize() > maxBytes) {
            throw SandboxSkillController.skillTooLargeError();
        }
        byte[] archive;
        try (var in = file.getInputStream()) {
            archive = in.readAllBytes();
        } catch (IOException e) {
            throw BizException.badRequest("failed to read the uploaded skill bundle");
        }
        // multipart part 可能少报大小，所以实际读到的字节也要查（Go 注释原文）
        if (archive.length > maxBytes) {
            throw SandboxSkillController.skillTooLargeError();
        }

        try {
            TenantSkillCatalogEntity cat = service.registerCatalogFromArchive(tenantId(),
                    archive);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(envelopeData(catalogIDResponse(cat)));
        } catch (RuntimeException err) {
            SandboxSkillController.respondSkillServiceError(err);
            return null; // 恒抛
        }
    }

    /** 对照 {@code registerCatalogFromSource}（RegisterCatalog 的 JSON 分支）。 */
    private ResponseEntity<?> registerCatalogFromSource(HttpServletRequest request)
            throws IOException {
        String raw = new String(request.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        SkillSourceRequest req;
        try {
            req = raw.isEmpty() ? new SkillSourceRequest()
                    : BIND_MAPPER.readValue(raw, SkillSourceRequest.class);
            if (req == null) {
                req = new SkillSourceRequest();
            }
        } catch (Exception e) {
            throw BizException.badRequest("invalid skill source request");
        }
        String source = req.source == null ? "" : req.source.trim();
        if (source.isEmpty()) {
            throw BizException.badRequest("source is required");
        }
        try {
            TenantSkillCatalogEntity cat = service.registerCatalogFromSource(tenantId(),
                    source);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(envelopeData(catalogIDResponse(cat)));
        } catch (RuntimeException err) {
            SandboxSkillController.respondSkillServiceError(err);
            return null; // 恒抛
        }
    }

    // ── POST /skills/catalog/{id}/install ────────────────────────────────

    /**
     * 对照 {@code InstallCatalog}：把 catalog skill 用既有快照安装管线装到每个
     * 具名沙箱。异步受理——首个 provider 调用在后台失败时行置 failed（dev 语义）。
     */
    @PostMapping("/api/v1/skills/catalog/{id}/install")
    public ResponseEntity<?> installCatalog(@PathVariable("id") String id,
            HttpServletRequest request) throws IOException {
        String raw = new String(request.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        CatalogInstallRequest req;
        try {
            req = raw.isEmpty() ? new CatalogInstallRequest()
                    : BIND_MAPPER.readValue(raw, CatalogInstallRequest.class);
            if (req == null) {
                req = new CatalogInstallRequest();
            }
        } catch (Exception e) {
            throw BizException.badRequest("invalid install request");
        }
        try {
            TenantSkillService.CatalogInstallResult result =
                    service.installCatalogToConfigs(tenantId(), id, req.sandboxConfigIDs);
            boolean success = result.errors().isEmpty();
            // gin.H：键字母序（errors < installs）
            Map<String, Object> data = new TreeMap<>();
            data.put("installs", result.installs());
            if (!result.errors().isEmpty()) {
                data.put("errors", result.errors());
            }
            Map<String, Object> body = new TreeMap<>();
            body.put("data", data);
            body.put("success", success);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
        } catch (RuntimeException err) {
            SandboxSkillController.respondSkillServiceError(err);
            return null; // 恒抛
        }
    }

    // ── GET /skills/catalog/{id}/files(+content) ─────────────────────────

    /** 对照 {@code ListCatalogFiles}：列存量 catalog bundle，不启沙箱。 */
    @GetMapping("/api/v1/skills/catalog/{id}/files")
    public ResponseEntity<?> listCatalogFiles(@PathVariable("id") String id) {
        try {
            return ResponseEntity.ok(envelopeData(service.listCatalogFiles(tenantId(), id)));
        } catch (RuntimeException err) {
            SandboxSkillController.respondSkillServiceError(err);
            return null; // 恒抛
        }
    }

    /** 对照 {@code GetCatalogFile}：UTF-8 文本 / 小图 base64 / 二进制三态。 */
    @GetMapping("/api/v1/skills/catalog/{id}/files/content")
    public ResponseEntity<?> getCatalogFile(@PathVariable("id") String id,
            @RequestParam(value = "path", defaultValue = "") String path) {
        try {
            return ResponseEntity.ok(envelopeData(
                    service.readCatalogFile(tenantId(), id, path)));
        } catch (RuntimeException err) {
            SandboxSkillController.respondSkillServiceError(err);
            return null; // 恒抛
        }
    }

    // ── DELETE /skills/catalog/{id} ──────────────────────────────────────

    /** 对照 {@code DeleteCatalog}：任何沙箱仍有安装时拒绝（409 code 1005）。 */
    @DeleteMapping("/api/v1/skills/catalog/{id}")
    public ResponseEntity<Map<String, Object>> deleteCatalog(@PathVariable("id") String id) {
        service.deleteCatalog(tenantId(), id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────

    /** 对照 {@code catalogIDResponse}：gin.H（字母序 description,id,name,version）。 */
    private static Map<String, Object> catalogIDResponse(TenantSkillCatalogEntity cat) {
        Map<String, Object> out = new TreeMap<>();
        if (cat == null) {
            return out;
        }
        out.put("description", cat.getDescription());
        out.put("id", cat.getId());
        out.put("name", cat.getName());
        out.put("version", cat.getVersion());
        return out;
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

    // ── 请求形状 ─────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SkillSourceRequest {
        @JsonProperty("source")
        String source = "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CatalogInstallRequest {
        @JsonProperty("sandbox_config_ids")
        java.util.List<String> sandboxConfigIDs;
    }

    // ══════════════════════════ 错误形态分支 ══════════════════════════

    /**
     * 非 AppError（catalog/仓储/传输错误）→ Go 全局 ErrorHandler 的 plain 分支：
     * 500 + {@code {"error":{"code":1007,"message":"Internal server error"},"success":false}}
     * （<b>无 details 键</b>）。与子批 2/3 同款 controller-local handler。
     */
    @org.springframework.web.bind.annotation.ExceptionHandler({IllegalStateException.class,
            com.ragagent.sandbox.runtime.RemoteError.class,
            org.springframework.dao.DataAccessException.class})
    public ResponseEntity<Map<String, Object>> handlePlainInternal(Exception ex) {
        log.error("skill catalog operation failed", ex);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", com.ragagent.common.error.ErrorCode.INTERNAL_SERVER.value());
        error.put("message", "Internal server error");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("success", false);
        return ResponseEntity.status(500).body(body);
    }
}
