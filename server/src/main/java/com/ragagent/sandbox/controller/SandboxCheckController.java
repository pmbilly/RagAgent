package com.ragagent.sandbox.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.sandbox.domain.CubeSandboxConfig;
import com.ragagent.sandbox.domain.E2BSandboxConfig;
import com.ragagent.sandbox.domain.TenantSandboxConfig;
import com.ragagent.sandbox.runtime.EffectiveConfig;
import com.ragagent.sandbox.runtime.EffectiveConfigResolver;
import com.ragagent.sandbox.runtime.RemoteConfigSandboxClient;
import com.ragagent.sandbox.runtime.RemoteError;
import com.ragagent.sandbox.runtime.RemoteErrorKind;
import com.ragagent.sandbox.service.SandboxClientFactory;
import com.ragagent.sandbox.service.TenantSandboxConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 沙箱连通性测试（对照 Go {@code internal/handler/sandbox_check.go} 全文，
 * 路由 routes_auth_tenant.go L257：system 组、g.Admin()）。使用当前填写的参数
 * 测试沙箱后端，<b>不保存</b>配置；deep=true 会执行临时脚本（远端后端还会创建
 * 并销毁一个沙箱）。密钥可带打码占位——对照存量配置解析（L21-23 注释）。
 *
 * <h2>错误形态：老式 {@code {"code":1,"msg":"…"}}（非 AppError 信封）</h2>
 * <p>binding → "请求体格式错误"；无空间 → "空间为空"；config_id 未知 →
 * "沙箱配置不存在"；sanitize/effective 失败 → 原文（L101-144 逐条对照）。
 * 这是系统组遗留风格，与 favorites/configs 的信封形态刻意并存。</p>
 *
 * <h2>成功形态：200 {@code {"success":true,"data":SandboxCheckResponse}}</h2>
 * <p>check 的 {@code ok} 是三态（<b>null = 跳过</b>，reason 是稳定 code 供前端
 * 本地化措辞）；latency_ms 只在真实探测上出现、随运行环境变化（A/B 掩码）。</p>
 *
 * <h2>sandboxCheckReason（L538-567）逐分支翻译</h2>
 * <p>RemoteError.Kind → 固定中文文案——provider 传输错误的原始措辞不进响应体，
 * 这是 dev A/B 字节稳定的关键。</p>
 *
 * <h2>deep 分支边界（显式接缝）</h2>
 * <p>deep 只在 Health 成功（控制面可达）后才会创建临时沙箱——dev 恒不可达。
 * {@code runDeepSandboxCheck} 翻译到 client.Create 的分类失败为止；
 * {@code explainSandboxCreateFailure} 的模板列举回溯（L447-503）是子批 3/波 4
 * 的接缝，此处以 create 的分类文案直接呈现。</p>
 */
@RestController
public class SandboxCheckController {

    // ── 跳过原因（对照 L52-62 的 stable codes） ───────────────────────────
    static final String SKIP_NEEDS_DEEP_CHECK = "needs_deep_check";
    static final String SKIP_CONTROL_PLANE_UNREACHABLE = "control_plane_unreachable";
    static final String SKIP_SANDBOX_NOT_CREATED = "sandbox_not_created";
    static final String SKIP_SANDBOX_EXEC_FAILED = "sandbox_exec_failed";
    static final String SKIP_EGRESS_RESTRICTED_BY_POLICY = "egress_restricted_by_policy";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final TenantSandboxConfigService sandboxConfigService;
    private final SandboxClientFactory clientFactory;

    public SandboxCheckController(TenantSandboxConfigService sandboxConfigService,
            SandboxClientFactory clientFactory) {
        this.sandboxConfigService = sandboxConfigService;
        this.clientFactory = clientFactory;
    }

    // ── 请求/响应（对照 L24-70） ──────────────────────────────────────────

    record SandboxCheckRequest(
            @JsonProperty("config") TenantSandboxConfig config,
            @JsonProperty("config_id") String configId,
            @JsonProperty("deep") boolean deep) {
    }

    static final class SandboxCheckItem {
        @JsonProperty("name")
        String name;
        /** 三态：null = 未执行（跳过）——区分"跳过"与"失败"。 */
        @JsonProperty("ok")
        Boolean ok;
        @JsonProperty("message")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        String message;
        @JsonProperty("reason")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        String reason;
        @JsonProperty("latency_ms")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        long latencyMs;
    }

    static final class SandboxCheckResponse {
        @JsonProperty("ok")
        boolean ok = true;
        @JsonProperty("provider")
        String provider;
        @JsonProperty("checks")
        List<SandboxCheckItem> checks = new ArrayList<>();
        @JsonProperty("capabilities")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        Map<String, Boolean> capabilities;

        /** 单个失败即整体失败（对照 L72-81）。 */
        void add(String name, boolean ok, String message, long latencyMs) {
            SandboxCheckItem item = new SandboxCheckItem();
            item.name = name;
            item.ok = ok;
            item.message = message;
            item.latencyMs = latencyMs;
            checks.add(item);
            if (!ok) {
                this.ok = false;
            }
        }

        /** 跳过的探测不影响 ok（对照 L83-86）。 */
        void skip(String name, String reason) {
            SandboxCheckItem item = new SandboxCheckItem();
            item.name = name;
            item.reason = reason;
            checks.add(item);
        }
    }

    // ── 端点（对照 CheckSandboxConfig L97-188） ──────────────────────────

    @PostMapping("/api/v1/system/sandbox-check")
    public ResponseEntity<Map<String, Object>> checkSandboxConfig(
            @RequestBody(required = false) String rawBody) {
        // binding 失败 → 老式固定文案（Go 不把解析细节透出）
        SandboxCheckRequest req = null;
        if (rawBody != null && !rawBody.isBlank()) {
            try {
                req = MAPPER.readValue(rawBody, SandboxCheckRequest.class);
            } catch (Exception ignored) {
                req = null;
            }
        }
        if (req == null) {
            return legacyError("请求体格式错误");
        }
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            return legacyError("空间为空");
        }

        TenantSandboxConfig stored = null;
        TenantSandboxConfig incoming = req.config();
        if (req.configId() != null && !req.configId().isEmpty()) {
            var entity = sandboxConfigService.get(tenantId, req.configId());
            if (entity == null) {
                return legacyError("沙箱配置不存在");
            }
            stored = entity.getConfig();
            if (incoming == null) {
                incoming = stored;
            }
        }
        if (!req.deep()) {
            incoming = sandboxConnectionCheckConfig(incoming);
        }
        TenantSandboxConfig merged;
        try {
            merged = sandboxConfigService.sanitizeSandboxConfig(incoming, stored);
        } catch (BizException e) {
            return legacyError(e.appError().message());
        } catch (RuntimeException e) {
            // ResolveEffectiveConfig 等运行时 sentinel（missing fields / unsafe url）
            return legacyError(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        }
        if (merged == null) {
            return legacyError("sandbox: config is missing required fields");
        }
        EffectiveConfig effective;
        try {
            effective = EffectiveConfigResolver.resolveEffectiveConfig(
                    merged, EffectiveConfig.defaultConfig());
        } catch (RuntimeException e) {
            return legacyError(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        }

        SandboxCheckResponse result = new SandboxCheckResponse();
        result.provider = effective.type;
        RemoteConfigSandboxClient client;
        try {
            client = (RemoteConfigSandboxClient) clientFactory.create(effective);
        } catch (RuntimeException err) {
            // 对照 client_build 探测：docker 禁用原文 / 其它构建错误
            result.add("client_build", false, err.getMessage() == null
                    ? err.getClass().getSimpleName() : err.getMessage(), 0);
            return ok(result);
        }

        // Level 1：一次带鉴权的控制面调用，同时验证端点可达性与凭据（L154-165）
        long start = System.nanoTime();
        String healthFailure = null;
        try {
            client.health();
        } catch (RuntimeException err) {
            healthFailure = sandboxCheckReason(err);
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        if (healthFailure != null) {
            result.add("api_url_reachable", false, healthFailure, latencyMs);
            result.skip("credential_valid", SKIP_CONTROL_PLANE_UNREACHABLE);
        } else {
            result.add("api_url_reachable", true, "", latencyMs);
            result.add("credential_valid", true, "", 0);
        }

        // 对照 caps（L167-172）：三个具名后端的探测面能力同表——
        // supports_volumes=false（三方一致：volume 面未映射，docker L217 注释）、
        // pause_resume/reconnect=true。gin.H 是 map：键字母序（pause_resume <
        // reconnect < volumes）。
        result.capabilities = new LinkedHashMap<>();
        result.capabilities.put("supports_pause_resume", true);
        result.capabilities.put("supports_reconnect", true);
        result.capabilities.put("supports_volumes", false);

        if (!req.deep() || healthFailure != null) {
            String reason = healthFailure == null
                    ? SKIP_NEEDS_DEEP_CHECK
                    : SKIP_CONTROL_PLANE_UNREACHABLE;
            result.skip("template_exists", reason);
            result.skip("sandbox_exec", reason);
            result.skip("egress_available", reason);
            return ok(result);
        }

        // deep 路径（控制面可达才可达——dev 恒不可达）
        runDeepSandboxCheck(client, result);
        return ok(result);
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    /** deep 探测（对照 runDeepSandboxCheck L262-287 的首个 provider 调用）。 */
    private void runDeepSandboxCheck(RemoteConfigSandboxClient client,
            SandboxCheckResponse result) {
        String createFailure;
        try {
            client.createProbeSandbox();
            createFailure = null;
        } catch (RuntimeException err) {
            // explainSandboxCreateFailure 的模板列举回溯是接缝：用 create 的
            // 分类文案直接呈现（控制面不可达时与 Go 主分支同形）
            createFailure = sandboxCheckReason(err);
        }
        if (createFailure != null) {
            result.add("template_exists", false, createFailure, 0);
            result.skip("sandbox_exec", SKIP_SANDBOX_NOT_CREATED);
            result.skip("egress_available", SKIP_SANDBOX_NOT_CREATED);
        }
        // 创建成功后的 exec/egress 探测随 exec 接缝（波 4）补齐
    }

    /**
     * 对照 sandboxConnectionCheckConfig（L193-219）：浅探测给 cube/e2b 补一个私有
     * 占位模板——连通性检查刻意先于模板发现执行，Health 不使用该值。
     */
    static TenantSandboxConfig sandboxConnectionCheckConfig(TenantSandboxConfig cfg) {
        if (cfg == null) {
            return null;
        }
        TenantSandboxConfig copy = cfg.shallowCopy();
        switch (cfg.getSandboxType() == null ? "" : cfg.getSandboxType()) {
            case "cube" -> {
                CubeSandboxConfig cube = copy.getCube() == null
                        ? new CubeSandboxConfig() : copy.getCube();
                if (cube.getTemplateId() == null || cube.getTemplateId().trim().isEmpty()) {
                    cube.setTemplateId("__connection_check__");
                }
                copy.setCube(cube);
            }
            case "e2b" -> {
                E2BSandboxConfig e2b = copy.getE2b() == null
                        ? new E2BSandboxConfig() : copy.getE2b();
                if (e2b.getTemplateId() == null || e2b.getTemplateId().trim().isEmpty()) {
                    e2b.setTemplateId("__connection_check__");
                }
                copy.setE2b(e2b);
            }
            default -> {
                // docker/disabled：不带模板占位
            }
        }
        return copy;
    }

    /**
     * 对照 sandboxCheckReason（L538-567）：provider 错误 → 可读原因；
     * 固定中文文案是 A/B 字节稳定的关键。
     */
    static String sandboxCheckReason(RuntimeException err) {
        if (!(err instanceof RemoteError remoteErr)) {
            return err.getMessage() == null ? err.toString() : err.getMessage();
        }
        return switch (remoteErr.kind) {
            case AUTHENTICATION -> "认证失败：API Key 无效或无权限";
            case NOT_FOUND -> "资源不存在：请检查模板 ID";
            case TIMEOUT -> "请求超时：端点不可达或响应过慢";
            case UNAVAILABLE -> "docker".equals(remoteErr.provider)
                    ? dockerUnavailableCheckReason(remoteErr.message)
                    : "服务不可用：端点拒绝连接";
            case CAPACITY -> "配额不足或触发限流";
            case UNSUPPORTED -> "该后端不支持此操作";
            case INVALID_REQUEST -> "参数无效：" + remoteErr.message;
            default -> remoteErr.message == null ? "" : remoteErr.message;
        };
    }

    /** 对照 dockerUnavailableCheckReason（L509-522）。 */
    static String dockerUnavailableCheckReason(String message) {
        String host = dockerHostFromUnavailableMessage(message);
        if (host.isEmpty()) {
            String detail = firstProbeLine(message);
            if (detail.isEmpty()) {
                return "无法连接 Docker 守护进程";
            }
            return "无法连接 Docker 守护进程：" + detail;
        }
        return "无法连接 Docker 守护进程 " + host
                + "。留空地址时跟随本机 docker CLI（DOCKER_HOST 或当前 docker context）。"
                + "Colima 一般是 unix://$HOME/.colima/default/docker.sock；"
                + "WeKnora 跑在容器里时需要把该 socket 挂进 app。";
    }

    /** 对照 dockerHostFromUnavailableMessage（L524-535）。 */
    static String dockerHostFromUnavailableMessage(String message) {
        String prefix = "Cannot connect to the Docker daemon at ";
        if (message == null || !message.startsWith(prefix)) {
            return "";
        }
        String rest = message.substring(prefix.length());
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == ' ' || c == '\t') {
                rest = rest.substring(0, i);
                break;
            }
        }
        rest = rest.trim();
        return rest.endsWith(".") ? rest.substring(0, rest.length() - 1) : rest;
    }

    /** 对照 firstProbeLine（L246-258）：首个非空行，封顶 300 字符。 */
    static String firstProbeLine(String output) {
        if (output == null) {
            return "";
        }
        for (String line : output.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            return trimmed.length() > 300 ? trimmed.substring(0, 300) + "…" : trimmed;
        }
        return "";
    }

    /** 老式错误体：{"code":1,"msg":"..."}。 */
    private static ResponseEntity<Map<String, Object>> legacyError(String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 1);
        body.put("msg", msg);
        return ResponseEntity.status(400).body(body);
    }

    /** 老式成功体：gin.H 是 map → 键字母序 {"data":…,"success":true}。 */
    private static ResponseEntity<Map<String, Object>> ok(SandboxCheckResponse data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return ResponseEntity.status(200).body(body);
    }
}
