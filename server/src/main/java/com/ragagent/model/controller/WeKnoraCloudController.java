package com.ragagent.model.controller;

import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.web.NonNullBody;
import com.ragagent.common.web.RejectEmptyBody;
import com.ragagent.model.dto.WeKnoraCloudCredentialsRequest;
import com.ragagent.model.dto.WeKnoraCloudStatusResponse;
import com.ragagent.model.service.WeKnoraCloudService;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WeKnoraCloud 端点：凭证保存（先外呼校验再加密落库）+ 凭证状态。
 *
 * <p>请求体为 {@code {appId, appSecret}}；保存成功 → 204 无响应体；
 * 校验/外呼失败 → 400 错误信封（含失败原因）。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class WeKnoraCloudController {

    private static final Logger log = LoggerFactory.getLogger(WeKnoraCloudController.class);

    private final WeKnoraCloudService service;

    public WeKnoraCloudController(WeKnoraCloudService service) {
        this.service = service;
    }

    /** 保存凭证（Admin+）：appId/appSecret 必填，先外呼校验再加密落库 → 204。 */
    @PostMapping("/weknoracloud/credentials")
    public ResponseEntity<Void> saveCredentials(
            @Valid @RejectEmptyBody @NonNullBody @RequestBody(required = false)
                    WeKnoraCloudCredentialsRequest req) {
        try {
            service.saveCredentials(req.appId(), req.appSecret());
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
        log.info("WeKnoraCloud credentials saved");
        return ResponseEntity.noContent().build();
    }

    /** 凭证状态（Viewer+）：hasModels = appId + appSecret 均已配置。 */
    @GetMapping("/models/weknoracloud/status")
    public ResponseEntity<WeKnoraCloudStatusResponse> status() {
        return ResponseEntity.ok(service.checkStatus());
    }
}
