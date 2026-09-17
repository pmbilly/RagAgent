package com.ragagent.model.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.ragagent.model.service.WeKnoraCloudService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照 Go internal/handler/weknoracloud.go。
 * 注意该 handler 的错误为 gin.H{"error":...} 直写（非 AppError 信封），golden 已锁定。
 */
@RestController
@RequestMapping("/api/v1")
public class WeKnoraCloudController {

    private static final Logger log = LoggerFactory.getLogger(WeKnoraCloudController.class);

    private final WeKnoraCloudService service;

    public WeKnoraCloudController(WeKnoraCloudService service) {
        this.service = service;
    }

    /** 对照 SaveCredentials — Admin+；绑定错误为 validator 原文（gin 格式） */
    @PostMapping("/weknoracloud/credentials")
    public ResponseEntity<?> saveCredentials(@RequestBody(required = false) Map<String, Object> body) {
        String appId = body != null && body.get("app_id") != null ? String.valueOf(body.get("app_id")) : "";
        String appSecret = body != null && body.get("app_secret") != null ? String.valueOf(body.get("app_secret")) : "";
        if (appId.isEmpty()) {
            return ResponseEntity.badRequest().body(plainError(
                    "Key: 'weKnoraCloudCredentialsRequest.AppID' Error:Field validation for 'AppID' failed on the 'required' tag"));
        }
        if (appSecret.isEmpty()) {
            return ResponseEntity.badRequest().body(plainError(
                    "Key: 'weKnoraCloudCredentialsRequest.AppSecret' Error:Field validation for 'AppSecret' failed on the 'required' tag"));
        }
        try {
            service.saveCredentials(appId, appSecret);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(plainError(e.getMessage()));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("message", "凭证保存成功");
        resp.put("success", true);
        return ResponseEntity.ok(resp);
    }

    /** 对照 Status — Viewer+：返回结构体（字段序 has_models, needs_reinit, reason?） */
    @GetMapping("/models/weknoracloud/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(service.checkStatus());
    }

    private static Map<String, Object> plainError(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }
}
