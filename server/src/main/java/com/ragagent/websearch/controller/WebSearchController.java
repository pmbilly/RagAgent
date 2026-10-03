package com.ragagent.websearch.controller;

import java.util.Map;

import com.ragagent.websearch.dto.WebSearchProviderTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/web-search/providers 的处理器（本控制器唯一路由）。
 *
 * <p>返回的是与 /web-search-providers/types **同一份静态元数据**
 * （均来自 {@link WebSearchProviderTypes#all()}）——纯静态，无运行时依赖。
 * 注意：该路由注册在**原始 group** 上（无 apiKeyGroup 包装）→ API Key default-deny
 * （刻意不登记进 APIKeyRoutePolicies）。</p>
 */
@RestController
public class WebSearchController {

    @GetMapping("/api/v1/web-search/providers")
    public ResponseEntity<Map<String, Object>> getProviders() {
        return ResponseEntity.ok(WebSearchProviderController.envelopeData(WebSearchProviderTypes.all()));
    }
}
