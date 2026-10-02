package com.ragagent.system.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.knowledge.client.DocReaderClient;
import com.ragagent.system.dto.SystemDtos;
import com.ragagent.system.service.ParserEngineRegistry;
import com.ragagent.system.service.SystemInfoService;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/system 组：读端（capabilities/info/parser-engines/storage-engine-status）Viewer+；
 * 探测端（parser-engines/check、docreader/reconnect、storage-engine-check）Admin+——
 * 它们拿租户凭据主动探测远端服务。
 *
 * <p>响应一律为裸资源对象（无 {@code code/data/msg} 信封）；错误走 AppError 信封，
 * docreader 连接失败 → 503、被禁用的存储引擎 → 403。
 * parser/storage 的请求键名保持 snake（§11 边界：与租户配置 jsonb 同形，待解冻后
 * 随批次 DTO 化）。</p>
 *
 * <p><b>POST /system/sandbox-check 未实现</b>（依赖波 3 sandbox）——Go 注册了该路由，
 * Java 侧不映射 → Spring 404。RBAC 与 API-Key 策略表的同名死登记已于
 * 2026-09-28 评审删除（落地 handler 时随批恢复）。</p>
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** cosFieldPattern/ossFieldPattern（同一正则，Go 两条 var 同值）。 */
    private static final Pattern FIELD_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}$");

    private final SystemInfoService infoService;
    private final ParserEngineRegistry parserEngines;
    private final TenantService tenantService;
    private final DocReaderClient docReader;
    private final SsrfGuard ssrfGuard;
    private final com.ragagent.system.service.DeploymentCapabilitiesHolder capabilitiesHolder;
    private final com.ragagent.storage.service.StorageBackendService storageBackendService;
    /** env 读取面（B6 批 4：取代裸 System.getenv；含 DOCREADER_* 与存储 env 可用性探测）。 */
    private final Environment environment;

    public SystemController(SystemInfoService infoService,
                            ParserEngineRegistry parserEngines,
                            TenantService tenantService,
                            DocReaderClient docReader,
                            SsrfGuard ssrfGuard,
                            com.ragagent.system.service.DeploymentCapabilitiesHolder capabilitiesHolder,
                            com.ragagent.storage.service.StorageBackendService storageBackendService,
                            Environment environment) {
        this.infoService = infoService;
        this.parserEngines = parserEngines;
        this.tenantService = tenantService;
        this.docReader = docReader;
        this.ssrfGuard = ssrfGuard;
        this.capabilitiesHolder = capabilitiesHolder;
        this.storageBackendService = storageBackendService;
        this.environment = environment;
    }

    // ── GET /capabilities ─────────────────────────────────────────────────

    /**
     * 对照 GetDeploymentCapabilities：启动快照 + 运行时 docker 覆盖
     * （overlayLiveDockerSandboxCapability）。Java 无 docker 后端 → docker 键恒按
     * sandbox=false 推导（route_not_registered）。
     */
    @GetMapping("/capabilities")
    public ResponseEntity<SystemDtos.DeploymentCapabilitiesData> capabilities() {
        return ResponseEntity.ok(capabilitiesHolder.snapshot());
    }


    // ── GET /info ─────────────────────────────────────────────────────────

    @GetMapping("/info")
    public ResponseEntity<SystemDtos.SystemInfoResponse> info() {
        long tenantId = currentTenantId();
        boolean minioEnabled = isMinioConfigured(tenantId);
        String dbVersion = infoService.dbVersion();
        SystemDtos.SystemInfoResponse response = new SystemDtos.SystemInfoResponse(
                infoService.getVersion(),
                infoService.getEdition(),
                infoService.getCommitId(),
                infoService.getBuildTime(),
                infoService.getJavaVersion(),
                infoService.keywordIndexEngine(),
                infoService.vectorStoreEngine(),
                infoService.graphDatabaseEngine(),
                minioEnabled,
                dbVersion,
                null,
                infoService.startedAt(),
                infoService.uptimeSeconds());
        return ResponseEntity.ok(response);
    }

    // ── GET /parser-engines ───────────────────────────────────────────────

    @GetMapping("/parser-engines")
    public ResponseEntity<SystemDtos.ParserEnginesResponse> listParserEngines() {
        return ResponseEntity.ok(engineListBody(engineOverrides()));
    }

    // ── POST /parser-engines/check ────────────────────────────────────────

    /**
     * 请求体 = ParserEngineConfig（未保存的表单值）。
     *
     * <p>请求键名保持 snake：与租户配置 jsonb（§11 边界）同形，待该边界解冻后随批次 DTO 化。</p>
     */
    @PostMapping("/parser-engines/check")
    public ResponseEntity<SystemDtos.ParserEnginesResponse> checkParserEngines(
            @RequestBody(required = false) String rawBody) {
        JsonNode body = parseBody(rawBody);
        if (body == null) {
            throw new BizException(AppError.badRequest("请求参数不合法").withDetails("请求体格式不正确"));
        }
        Map<String, String> overrides = overridesFromRaw(body);
        // 现有租户配置的 weknoracloud_app_id 叠加（配置保存前的凭证步）
        String cloudAppId = tenantWeKnoraCloudAppId();
        if (!cloudAppId.isEmpty()) {
            overrides.put("weknoracloud_app_id", cloudAppId);
        }
        return ResponseEntity.ok(engineListBody(overrides));
    }

    // ── POST /docreader/reconnect ─────────────────────────────────────────

    @PostMapping("/docreader/reconnect")
    public ResponseEntity<SystemDtos.ParserEnginesResponse> reconnectDocReader(
            @RequestBody(required = false) String rawBody) {
        JsonNode body = parseBody(rawBody);
        String addr = body == null || !body.has("addr") ? null : body.path("addr").asText(null);
        if (addr == null) {
            throw new BizException(AppError.badRequest("请提供 addr 参数"));
        }
        addr = addr.trim();
        if (addr.isEmpty()) {
            throw new BizException(AppError.badRequest("addr 不能为空"));
        }
        // SSRF 校验（DocReader 地址）
        try {
            ssrfGuard.validateURLForSSRF(addr);
        } catch (RuntimeException e) {
            throw new BizException(AppError.badRequest(
                    ssrfGuard.formatSSRFError("DocReader 地址", addr, e)));
        }
        try {
            docReader.reconnect(addr);
        } catch (RuntimeException e) {
            // 下游 docreader 不可达：503（错误语义化）
            throw new BizException(AppError.serviceUnavailable("连接失败: " + e.getMessage()));
        }
        List<SystemDtos.ParserEngineInfo> engines = parserEngines.listAllEngines(
                true, engineOverrides(),
                parserEngines.fetchRemoteEngines(true, engineOverrides()));
        return ResponseEntity.ok(new SystemDtos.ParserEnginesResponse(
                true, addr, docreaderTransport(), engines));
    }

    // ── GET /storage-engine-status ────────────────────────────────────────

    @GetMapping("/storage-engine-status")
    public ResponseEntity<SystemDtos.StorageEngineStatusResponse> storageEngineStatus() {
        long tenantId = currentTenantId();
        Map<String, Boolean> activeBackend = infoService.activeBackendProviders(tenantId);
        boolean minioConfigured = isMinioConfigured(tenantId) || truthy(activeBackend.get("minio"));
        boolean minioEnvAvailable = infoService.isMinioEnvAvailable();
        var allowed = infoService.allowList().allowedMap();
        List<String> allowedProviders = new ArrayList<>();
        for (String provider : infoService.allowList().supported()) {
            if (allowed.contains(provider)) {
                allowedProviders.add(provider);
            }
        }
        List<SystemDtos.StorageEngineStatusItem> engines = List.of(
                new SystemDtos.StorageEngineStatusItem("local", allowed.contains("local"), true,
                        "本地文件系统存储，仅适合单机部署"),
                new SystemDtos.StorageEngineStatusItem("minio", allowed.contains("minio"),
                        minioConfigured || minioEnvAvailable,
                        "S3 兼容的自托管对象存储，适合内网和私有云部署"),
                new SystemDtos.StorageEngineStatusItem("cos", allowed.contains("cos"),
                        isCosConfigured(tenantId) || truthy(activeBackend.get("cos")),
                        "腾讯云对象存储服务，适合公有云部署，支持 CDN 加速"),
                new SystemDtos.StorageEngineStatusItem("tos", allowed.contains("tos"),
                        isTosConfigured(tenantId) || truthy(activeBackend.get("tos")),
                        "火山引擎对象存储服务，适合公有云部署"),
                new SystemDtos.StorageEngineStatusItem("s3", allowed.contains("s3"),
                        isS3Configured(tenantId) || truthy(activeBackend.get("s3")),
                        "AWS S3 与兼容对象存储服务，适合公有云与混合云部署"),
                new SystemDtos.StorageEngineStatusItem("oss", allowed.contains("oss"),
                        isOssConfigured(tenantId) || truthy(activeBackend.get("oss")),
                        "阿里云对象存储服务，适合公有云部署，支持 S3 兼容协议"),
                new SystemDtos.StorageEngineStatusItem("ks3", allowed.contains("ks3"),
                        isKs3Configured(tenantId) || truthy(activeBackend.get("ks3")),
                        "金山云对象存储服务，适合公有云部署"),
                new SystemDtos.StorageEngineStatusItem("obs", allowed.contains("obs"),
                        isObsConfigured(tenantId) || truthy(activeBackend.get("obs")),
                        "华为云对象存储服务，适合公有云部署"));
        return ResponseEntity.ok(new SystemDtos.StorageEngineStatusResponse(
                engines, allowedProviders, minioEnvAvailable));
    }

    // ── POST /storage-engine-check ────────────────────────────────────────

    /**
     * 连通性检测。
     *
     * <p>请求体 = 存储配置表单（键名 snake，与前端的存储设置一致；该配置最终落租户
     * jsonb，属 §11 边界——请求键名随该边界解冻后统一 DTO 化）。</p>
     */
    @PostMapping("/storage-engine-check")
    public ResponseEntity<SystemDtos.StorageCheckResponse> storageEngineCheck(
            @RequestBody(required = false) String rawBody) {
        JsonNode body = parseBody(rawBody);
        if (body == null) {
            throw new BizException(AppError.badRequest("请求参数不合法").withDetails("请求体格式不正确"));
        }
        String provider = body.path("provider").asText("");
        if (!infoService.allowList().isAllowed(provider)) {
            throw new BizException(AppError.forbidden("该存储引擎已被禁用"));
        }
        JsonNode cfg = body.get(provider);
        return switch (provider) {
            case "minio" -> checkMinio(cfg);
            case "cos" -> checkCos(cfg);
            case "tos" -> checkGeneric(cfg, "TOS",
                    List.of("Endpoint", "Region", "Access Key", "Secret Key", "Bucket 名称"), "endpoint");
            case "s3" -> checkS3(cfg);
            case "oss" -> checkOss(cfg);
            case "ks3" -> checkGeneric(cfg, "KS3",
                    List.of("Endpoint", "Region", "Access Key", "Secret Key", "Bucket 名称"), "endpoint");
            case "obs" -> checkObs(cfg);
            // default（含 local 与空 provider）：本地存储无需检测
            default -> ResponseEntity.ok(
                    new SystemDtos.StorageCheckResponse(true, "本地存储无需检测", false));
        };
    }

    private ResponseEntity<SystemDtos.StorageCheckResponse> checkResponse(boolean ok, String message) {
        return ResponseEntity.ok(new SystemDtos.StorageCheckResponse(ok, message, false));
    }

    private ResponseEntity<SystemDtos.StorageCheckResponse> checkMinio(JsonNode cfg) {
        if (cfg == null || cfg.isNull()) {
            return checkResponse(false, "未提供 MinIO 配置");
        }
        String bucketName = cfg.path("bucket_name").asText("");
        if (!bucketName.isEmpty() && !FIELD_PATTERN.matcher(bucketName).matches()) {
            return checkResponse(false, "Bucket 名称格式不正确，仅允许字母、数字、点、连字符");
        }
        // mode != "remote" → env 兜底（Go checkMinio 的 env 路径；任一缺失即拒绝）
        String endpoint = cfg.path("endpoint").asText("");
        String accessKey = cfg.path("access_key_id").asText("");
        String secretKey = cfg.path("secret_access_key").asText("");
        if (!"remote".equals(cfg.path("mode").asText(""))) {
            endpoint = orEnv(endpoint, "MINIO_ENDPOINT");
            accessKey = orEnv(accessKey, "MINIO_ACCESS_KEY_ID");
            secretKey = orEnv(secretKey, "MINIO_SECRET_ACCESS_KEY");
        }
        if (endpoint.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty()) {
            return checkResponse(false, "Endpoint、Access Key、Secret Key 不能为空");
        }
        if ("remote".equals(cfg.path("mode").asText(""))) {
            String blocked = blockedStorageEndpoint(endpoint);
            if (blocked != null) {
                return checkResponse(false, blocked);
            }
        }
        return connectivityFallback("minio", cfg, endpoint, bucketName);
    }

    private ResponseEntity<SystemDtos.StorageCheckResponse> checkCos(JsonNode cfg) {
        if (cfg == null || cfg.isNull()) {
            return checkResponse(false, "未提供 COS 配置");
        }
        String secretId = cfg.path("secret_id").asText("");
        String secretKey = cfg.path("secret_key").asText("");
        String region = cfg.path("region").asText("");
        String bucketName = cfg.path("bucket_name").asText("");
        if (secretId.isEmpty() || secretKey.isEmpty() || region.isEmpty() || bucketName.isEmpty()) {
            return checkResponse(false, "Secret ID、Secret Key、Region、Bucket 名称不能为空");
        }
        if (!FIELD_PATTERN.matcher(region).matches()) {
            return checkResponse(false, "Region 格式不正确，仅允许字母、数字、点、连字符");
        }
        if (!FIELD_PATTERN.matcher(bucketName).matches()) {
            return checkResponse(false, "Bucket 名称格式不正确，仅允许字母、数字、点、连字符");
        }
        return connectivityFallback("cos", cfg, region, bucketName);
    }

    private ResponseEntity<SystemDtos.StorageCheckResponse> checkS3(JsonNode cfg) {
        if (cfg == null || cfg.isNull()) {
            return checkResponse(false, "未提供 S3 配置");
        }
        String region = cfg.path("region").asText("");
        String bucketName = cfg.path("bucket_name").asText("");
        String accessKey = cfg.path("access_key").asText("");
        String secretKey = cfg.path("secret_key").asText("");
        if (region.isEmpty() || bucketName.isEmpty()) {
            return checkResponse(false, "Region、Bucket 名称不能为空");
        }
        if (accessKey.isEmpty() != secretKey.isEmpty()) {
            return checkResponse(false, "Access Key 与 Secret Key 必须同时填写或同时留空（使用 AWS 默认凭证链）");
        }
        String endpoint = cfg.path("endpoint").asText("");
        if (!endpoint.isEmpty()) {
            String blocked = blockedStorageEndpoint(endpoint);
            if (blocked != null) {
                return checkResponse(false, blocked);
            }
        }
        return connectivityFallback("s3", cfg, endpoint, bucketName);
    }

    private ResponseEntity<SystemDtos.StorageCheckResponse> checkOss(JsonNode cfg) {
        if (cfg == null || cfg.isNull()) {
            return checkResponse(false, "未提供 OSS 配置");
        }
        String endpoint = cfg.path("endpoint").asText("");
        String accessKey = cfg.path("access_key").asText("");
        String secretKey = cfg.path("secret_key").asText("");
        String bucketName = cfg.path("bucket_name").asText("");
        if (endpoint.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty() || bucketName.isEmpty()) {
            return checkResponse(false, "Endpoint、Access Key、Secret Key、Bucket Name 不能为空");
        }
        String ssrfEndpoint = endpoint.replaceFirst("^https://", "").replaceFirst("^http://", "");
        String blocked = blockedStorageEndpoint(ssrfEndpoint);
        if (blocked != null) {
            return checkResponse(false, blocked);
        }
        String region = cfg.path("region").asText("");
        if (!FIELD_PATTERN.matcher(region).matches()) {
            return checkResponse(false, "Region 格式不正确，仅允许字母、数字、点、连字符");
        }
        if (!FIELD_PATTERN.matcher(bucketName).matches()) {
            return checkResponse(false, "Bucket 名称格式不正确，仅允许字母、数字、点、连字符");
        }
        return connectivityFallback("oss", cfg, endpoint, bucketName);
    }

    private ResponseEntity<SystemDtos.StorageCheckResponse> checkObs(JsonNode cfg) {
        if (cfg == null || cfg.isNull()) {
            return checkResponse(false, "未提供 OBS 配置");
        }
        String endpoint = cfg.path("endpoint").asText("");
        String region = cfg.path("region").asText("");
        String accessKey = cfg.path("access_key").asText("");
        String secretKey = cfg.path("secret_key").asText("");
        String bucketName = cfg.path("bucket_name").asText("");
        if (endpoint.isEmpty() || region.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty()
                || bucketName.isEmpty()) {
            return checkResponse(false, "Endpoint、Region、Access Key、Secret Key、Bucket 名称不能为空");
        }
        String ssrfEndpoint = endpoint.replaceFirst("^https://", "").replaceFirst("^http://", "");
        String blocked = blockedStorageEndpoint(ssrfEndpoint);
        if (blocked != null) {
            return checkResponse(false, blocked);
        }
        if (!FIELD_PATTERN.matcher(region).matches()) {
            return checkResponse(false, "Region 格式不正确，仅允许字母、数字、点、连字符");
        }
        if (!FIELD_PATTERN.matcher(bucketName).matches()) {
            return checkResponse(false, "Bucket 名称格式不正确，仅允许字母、数字、点、连字符");
        }
        return connectivityFallback("obs", cfg, endpoint, bucketName);
    }

    /** TOS/KS3 共用（同样的必填与 SSRF 分支顺序）。 */
    private ResponseEntity<SystemDtos.StorageCheckResponse> checkGeneric(
            JsonNode cfg, String label, List<String> requiredFields, String endpointField) {
        if (cfg == null || cfg.isNull()) {
            return checkResponse(false, "未提供 " + label + " 配置");
        }
        String endpoint = cfg.path(endpointField).asText("");
        String region = cfg.path("region").asText("");
        String accessKey = cfg.path("access_key").asText("");
        String secretKey = cfg.path("secret_key").asText("");
        String bucketName = cfg.path("bucket_name").asText("");
        if (endpoint.isEmpty() || region.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty()
                || bucketName.isEmpty()) {
            return checkResponse(false, String.join("、", requiredFields) + "不能为空");
        }
        String blocked = blockedStorageEndpoint(endpoint);
        if (blocked != null) {
            return checkResponse(false, blocked);
        }
        return connectivityFallback(label.toLowerCase(), cfg, endpoint, bucketName);
    }

    /**
     * 真实连通性检测：复用波 2 第五批的 StorageBackendService.test（同一批 SDK 探测）。
     * Go 的失败文案分派按 err 串子串（403 / 404|NoSuchBucket|NotFound / AccessDenied），
     * 这里对异常消息做同款子串分派；两侧 SDK 错误串不完全一致 → 失败文案是已知差异
     * （golden 只覆盖 nil-config / SSRF / 禁用 provider 等确定性分支）。
     */
    private ResponseEntity<SystemDtos.StorageCheckResponse> connectivityFallback(
            String provider, JsonNode cfg, String endpoint, String bucketName) {
        com.ragagent.storage.domain.StorageBackend backend =
                new com.ragagent.storage.domain.StorageBackend();
        backend.setTenantId(currentTenantId());
        backend.setProvider(provider);
        ObjectNode config = MAPPER.createObjectNode();
        config.put("mode", "remote");
        config.put("endpoint", endpoint);
        config.put("region", cfg.path("region").asText(""));
        config.put("access_key_id", accessKeyOf(provider, cfg));
        config.put("secret_access_key", secretKeyOf(provider, cfg));
        config.put("bucket_name", bucketName);
        config.put("use_ssl", cfg.path("use_ssl").asBoolean(false));
        backend.setConfig(config);
        try {
            storageBackendService.test(backend);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("403") || msg.contains("AccessDenied")) {
                return checkResponse(false, authFailedMessage(provider));
            }
            if (msg.contains("404") || msg.contains("NoSuchBucket") || msg.contains("NotFound")) {
                return checkResponse(false, "Bucket「" + bucketName + "」不存在，请检查名称和 Region");
            }
            return checkResponse(false, com.ragagent.storage.service.StorageBackendService.sanitizeConnectivity(msg));
        }
        String message = "连接成功，Bucket「" + bucketName + "」已确认存在";
        if ("minio".equals(provider) && bucketName.isEmpty()) {
            message = "连接成功";
        }
        return checkResponse(true, message);
    }

    private static String accessKeyOf(String provider, JsonNode cfg) {
        return "minio".equals(provider)
                ? cfg.path("access_key_id").asText("")
                : cfg.path("access_key").asText("");
    }

    private static String secretKeyOf(String provider, JsonNode cfg) {
        return "minio".equals(provider)
                ? cfg.path("secret_access_key").asText("")
                : cfg.path("secret_key").asText("");
    }

    private static String authFailedMessage(String provider) {
        return switch (provider) {
            case "minio" -> "认证失败，请检查 Access Key / Secret Key 是否正确";
            case "cos" -> "认证失败，请检查 Secret ID / Secret Key 是否正确";
            case "tos" -> "认证失败，请检查 Access Key / Secret Key 是否正确";
            case "s3" -> "认证失败，请检查静态密钥或 AWS IAM Role / 默认凭证链权限";
            case "oss" -> "认证失败，请检查 Access Key / Secret Key 是否正确";
            case "ks3" -> "认证失败，请检查 Access Key / Secret Key 是否正确";
            default -> "认证失败，请检查 Access Key / Secret Key 是否正确";
        };
    }

    private String blockedStorageEndpoint(String endpoint) {
        String trimmed = endpoint == null ? "" : endpoint.trim();
        if (trimmed.isEmpty()) {
            return "无效的地址";
        }
        try {
            ssrfGuard.validateURLForSSRF(trimmed);
            return null;
        } catch (RuntimeException e) {
            return ssrfGuard.formatSSRFError("存储 Endpoint", trimmed, e);
        }
    }

    // ── 租户/引擎上下文 ───────────────────────────────────────────────────

    /** ShouldBindJSON 等价：解析失败（含 EOF）→ null，调用方落各自的 400 文案。 */
    private static JsonNode parseBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode node = new ObjectMapper().readTree(rawBody);
            return node == null || !node.isObject() ? null : node;
        } catch (Exception e) {
            return null;
        }
    }

    private long currentTenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    private Tenant currentTenant() {
        long tid = currentTenantId();
        return tid <= 0 ? null : tenantService.getTenantById(tid);
    }

    /**
     * 对照 ListParserEngines 的 overrides 组装：tenant.ParserEngineConfig.ToOverridesMap()
     * + weknoracloud_app_id（tenant.Credentials.GetWeKnoraCloud）。
     */
    private Map<String, String> engineOverrides() {
        Map<String, String> overrides = new LinkedHashMap<>();
        Tenant tenant = currentTenant();
        if (tenant != null) {
            overrides.putAll(overridesFromRaw(tenant.getParserEngineConfig()));
            String appId = tenantWeKnoraCloudAppId();
            if (!appId.isEmpty()) {
                overrides.put("weknoracloud_app_id", appId);
            }
        }
        return overrides;
    }

    /** 从 jsonb（Go ParserEngineConfig 的 json 形状）按 ToOverridesMap 的键提取。 */
    static Map<String, String> overridesFromRaw(JsonNode node) {
        Map<String, String> overrides = new LinkedHashMap<>();
        if (node == null || node.isNull() || !node.isObject()) {
            return overrides;
        }
        putString(overrides, node, "mineru_endpoint");
        putString(overrides, node, "mineru_api_key");
        putString(overrides, node, "mineru_model");
        putString(overrides, node, "mineru_vlm_server_url");
        putBool(overrides, node, "mineru_enable_formula");
        putBool(overrides, node, "mineru_enable_table");
        // mineru_parse_method：显式值归一；否则 legacy enable_ocr 推导（Go ResolveMinerUParseMethod）
        String method = node.path("mineru_parse_method").asText("").trim().toLowerCase();
        JsonNode legacyOcr = node.get("mineru_enable_ocr");
        String resolved = switch (method) {
            case "auto" -> "auto";
            case "ocr" -> "ocr";
            case "txt" -> "txt";
            default -> legacyOcr != null && legacyOcr.isBoolean() && !legacyOcr.asBoolean() ? "txt" : "auto";
        };
        if (!node.path("mineru_parse_method").asText("").isEmpty() || (legacyOcr != null && legacyOcr.isBoolean())) {
            overrides.put("mineru_parse_method", resolved);
        }
        putBool(overrides, node, "mineru_enable_ocr");
        putString(overrides, node, "mineru_language");
        putString(overrides, node, "mineru_cloud_model");
        putBool(overrides, node, "mineru_cloud_enable_formula");
        putBool(overrides, node, "mineru_cloud_enable_table");
        putBool(overrides, node, "mineru_cloud_enable_ocr");
        putString(overrides, node, "mineru_cloud_language");
        putString(overrides, node, "odl_hybrid");
        putString(overrides, node, "odl_hybrid_url");
        putString(overrides, node, "odl_hybrid_mode");
        putBool(overrides, node, "odl_hybrid_fallback");
        putBool(overrides, node, "odl_markdown_with_html");
        putString(overrides, node, "paddleocr_vl_endpoint");
        putBool(overrides, node, "paddleocr_vl_use_seal_recognition");
        putBool(overrides, node, "paddleocr_vl_use_chart_recognition");
        putString(overrides, node, "paddleocr_vl_cloud_token");
        putString(overrides, node, "paddleocr_vl_cloud_model");
        putBool(overrides, node, "paddleocr_vl_cloud_use_seal_recognition");
        putBool(overrides, node, "paddleocr_vl_cloud_use_chart_recognition");
        return overrides;
    }

    private static void putString(Map<String, String> m, JsonNode node, String field) {
        String v = node.path(field).asText("");
        if (!v.isEmpty()) {
            m.put(field, v);
        }
    }

    private static void putBool(Map<String, String> m, JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v != null && v.isBoolean()) {
            m.put(field, String.valueOf(v.asBoolean()));
        }
    }

    /** tenant.Credentials.GetWeKnoraCloud().AppID（无凭据 → ""）。 */
    private String tenantWeKnoraCloudAppId() {
        Tenant tenant = currentTenant();
        if (tenant == null || tenant.getCredentials() == null) {
            return "";
        }
        JsonNode cloud = tenant.getCredentials().get("weknoracloud");
        if (cloud == null || cloud.isNull()) {
            return "";
        }
        return cloud.path("app_id").asText("");
    }

    private boolean isMinioConfigured(long tenantId) {
        Tenant tenant = currentTenant();
        if (tenant != null && tenant.getStorageEngineConfig() != null) {
            JsonNode minio = tenant.getStorageEngineConfig().get("minio");
            if (minio != null && !minio.isNull() && "remote".equals(minio.path("mode").asText(""))) {
                return !minio.path("endpoint").asText("").isEmpty()
                        && !minio.path("access_key_id").asText("").isEmpty()
                        && !minio.path("secret_access_key").asText("").isEmpty();
            }
        }
        return infoService.isMinioEnvAvailable();
    }

    private boolean hasAllNonEmpty(long tenantId, String provider, List<String> fields, boolean envFallback) {
        Tenant tenant = currentTenant();
        if (tenant != null && tenant.getStorageEngineConfig() != null) {
            JsonNode conf = tenant.getStorageEngineConfig().get(provider);
            if (conf != null && !conf.isNull()) {
                for (String f : fields) {
                    if (conf.path(f).asText("").isEmpty()) {
                        return false;
                    }
                }
                return true;
            }
        }
        return envFallback;
    }

    private boolean isTosConfigured(long tenantId) {
        return hasAllNonEmpty(tenantId, "tos",
                List.of("endpoint", "region", "access_key", "secret_key", "bucket_name"),
                infoService.isMinioEnvAvailable() && envAll("TOS_ENDPOINT", "TOS_REGION",
                        "TOS_ACCESS_KEY", "TOS_SECRET_KEY", "TOS_BUCKET_NAME"));
    }

    private boolean isObsConfigured(long tenantId) {
        return hasAllNonEmpty(tenantId, "obs",
                List.of("endpoint", "region", "access_key", "secret_key", "bucket_name"),
                envAll("OBS_ENDPOINT", "OBS_REGION", "OBS_ACCESS_KEY", "OBS_SECRET_KEY", "OBS_BUCKET_NAME"));
    }

    private boolean envAll(String... names) {
        for (String n : names) {
            String v = environment.getProperty(n);
            if (v == null || v.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean isCosConfigured(long tenantId) {
        return hasAllNonEmpty(tenantId, "cos",
                List.of("secret_id", "secret_key", "region", "bucket_name"), false);
    }

    private boolean isOssConfigured(long tenantId) {
        return hasAllNonEmpty(tenantId, "oss",
                List.of("endpoint", "region", "access_key", "secret_key", "bucket_name"), false);
    }

    private boolean isKs3Configured(long tenantId) {
        return hasAllNonEmpty(tenantId, "ks3",
                List.of("endpoint", "region", "access_key", "secret_key", "bucket_name"), false);
    }

    /** 对照 isS3Configured：region/bucket 非空 + (accessKey 空) == (secretKey 空)。 */
    private boolean isS3Configured(long tenantId) {
        Tenant tenant = currentTenant();
        if (tenant != null && tenant.getStorageEngineConfig() != null) {
            JsonNode s3 = tenant.getStorageEngineConfig().get("s3");
            if (s3 != null && !s3.isNull()) {
                String accessKey = s3.path("access_key").asText("");
                String secretKey = s3.path("secret_key").asText("");
                return !s3.path("region").asText("").isEmpty()
                        && !s3.path("bucket_name").asText("").isEmpty()
                        && (accessKey.isEmpty()) == (secretKey.isEmpty());
            }
        }
        return false;
    }

    // ── 响应组装辅助 ──────────────────────────────────────────────────────

    /** parser-engines 响应组装（连接态 + docreader 信息 + 引擎清单）。 */
    private SystemDtos.ParserEnginesResponse engineListBody(Map<String, String> overrides) {
        boolean connected = docReader.isConnected();
        List<SystemDtos.ParserEngineInfo> remote =
                parserEngines.fetchRemoteEngines(connected, overrides);
        List<SystemDtos.ParserEngineInfo> engines =
                parserEngines.listAllEngines(connected, overrides, remote);
        return new SystemDtos.ParserEnginesResponse(
                connected, docReaderAddr(), docreaderTransport(), engines);
    }

    private String docReaderAddr() {
        String addr = environment.getProperty("DOCREADER_ADDR");
        return addr == null ? "" : addr.trim();
    }

    /** 对照 getDocReaderConnInfo：transport 缺省 grpc（小写归一）。 */
    private String docreaderTransport() {
        String transport = environment.getProperty("DOCREADER_TRANSPORT");
        if (transport == null || transport.trim().isEmpty()) {
            return "grpc";
        }
        return transport.trim().toLowerCase();
    }

    private String orEnv(String value, String envName) {
        if (!value.isEmpty()) {
            return value;
        }
        String v = environment.getProperty(envName);
        return v == null ? "" : v;
    }

    private static boolean truthy(Boolean b) {
        return b != null && b;
    }

}
