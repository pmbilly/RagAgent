package com.ragagent.sandbox.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.BizException;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.sandbox.service.UserEnvService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照 Go {@code handler.MeEnvVarHandler}（internal/handler/me_env_var.go 全文；
 * routes_auth_tenant.go RegisterMyEnvVarRoutes L188-202，<b>无角色门</b>）。
 *
 * <h2>为什么存在（Go 注释原文）</h2>
 * 它的存在是为了不放松 /sandbox-configs/:id/skills*（那里 Admin+ 连读都是刻意的：
 * 上传会驱动 root shell、输出烤进镜像，listing 会说出镜像携带什么）。本 handler 只
 * 返回声明与 set/unset 状态——永远不返回值。
 *
 * <h2>请求形状</h2>
 * meEnvVarRequest 刻意没有 principal 字段：不是"被忽略的字段"，是"不存在"——
 * 能指名身份的字段离被尊重要差一次重构，而尊重要它就会让任何登录成员写到别的
 * 成员的值（Go 注释原文）。DELETE 也吃 JSON body（清值是删除而不是写 ""，成员
 * 永远有一种无歧义的撤销方式）。
 *
 * <h2>错误形态</h2>
 * nothing to delete 是 404；拒绝的输入是 400；其余是 service 失败保持 500，
 * 内部消息永远到不了成员（Go 注释原文）。缺 scope 键或缺名字统一报
 * {@code "<scopeField> and name are required"}；空 body 报 {@code EOF}。
 */
@RestController
public class MeEnvVarController {

    private static final ObjectMapper BIND_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final UserEnvService service;

    public MeEnvVarController(UserEnvService service) {
        this.service = service;
    }

    /** 对照 {@code meEnvVarRequest}：所有写端点共用的 body。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class MeEnvVarRequest {
        @JsonProperty("skill_id")
        String skillId = "";
        @JsonProperty("sandbox_config_id")
        String sandboxConfigId = "";
        @JsonProperty("name")
        String name = "";
        /** 删除端点不用它：清值是删除而非写 ""（Go 注释原文）。 */
        @JsonProperty("value")
        String value = "";
    }

    // ── 5 条路由 ─────────────────────────────────────────────────────────

    /** 对照 {@code List}：每配置一组；值永不返回。 */
    @GetMapping("/api/v1/me/env-vars")
    public ResponseEntity<Map<String, Object>> list() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", service.listMine());
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 {@code SetSkill}：存调用者自己的值；只为该调用者覆盖工作区值。 */
    @PutMapping("/api/v1/me/env-vars/skill")
    public ResponseEntity<Map<String, Object>> setSkill(HttpServletRequest request)
            throws IOException {
        MeEnvVarRequest req = bind(request, "skill_id", r -> r.skillId);
        service.setMineSkill(req.skillId, req.name, req.value);
        return ResponseEntity.ok(successOnly());
    }

    /** 对照 {@code DeleteSkill}：删除后工作区值（若有）重新生效。 */
    @DeleteMapping("/api/v1/me/env-vars/skill")
    public ResponseEntity<Map<String, Object>> deleteSkill(HttpServletRequest request)
            throws IOException {
        MeEnvVarRequest req = bind(request, "skill_id", r -> r.skillId);
        service.deleteMineSkill(req.skillId, req.name);
        return ResponseEntity.ok(successOnly());
    }

    /** 对照 {@code SetSandbox}：注入到该调用者在此配置上的每次 skill 脚本与 shell。 */
    @PutMapping("/api/v1/me/env-vars/sandbox")
    public ResponseEntity<Map<String, Object>> setSandbox(HttpServletRequest request)
            throws IOException {
        MeEnvVarRequest req = bind(request, "sandbox_config_id", r -> r.sandboxConfigId);
        service.setMineSandbox(req.sandboxConfigId, req.name, req.value);
        return ResponseEntity.ok(successOnly());
    }

    /** 对照 {@code DeleteSandbox}。 */
    @DeleteMapping("/api/v1/me/env-vars/sandbox")
    public ResponseEntity<Map<String, Object>> deleteSandbox(HttpServletRequest request)
            throws IOException {
        MeEnvVarRequest req = bind(request, "sandbox_config_id", r -> r.sandboxConfigId);
        service.deleteMineSandbox(req.sandboxConfigId, req.name);
        return ResponseEntity.ok(successOnly());
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────

    /**
     * 对照 {@code bindEnvVarRequest}：解体并拒绝缺 scope 键或名字的请求，让每个
     * 端点用同一种方式报同两个字段（Go 注释原文）。body 缺席是 Go 的 io.EOF →
     * "EOF"；语法错误用 Go encoding/json 的措辞（GoJsonBindError 仿真）。
     */
    private static MeEnvVarRequest bind(HttpServletRequest request, String scopeField,
            java.util.function.Function<MeEnvVarRequest, String> scope) throws IOException {
        String raw = new String(request.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        MeEnvVarRequest req;
        if (raw.isEmpty()) {
            // Go 的 ShouldBindJSON 对空 body 返回 io.EOF，err.Error() 即 "EOF"
            throw BizException.badRequest("EOF");
        }
        try {
            req = BIND_MAPPER.readValue(raw, MeEnvVarRequest.class);
            if (req == null) {
                req = new MeEnvVarRequest();
            }
        } catch (Exception e) {
            throw BizException.badRequest(GoJsonBindError.message(raw, e.getMessage()));
        }
        if (scope.apply(req) == null || scope.apply(req).isEmpty() || req.name == null
                || req.name.isEmpty()) {
            throw BizException.badRequest(scopeField + " and name are required");
        }
        return req;
    }

    /** gin.H：{"success":true}。 */
    private static Map<String, Object> successOnly() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return body;
    }
}
