package com.ragagent.browserskill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.browserskill.domain.AccountStatus;
import com.ragagent.browserskill.domain.Scope;
import com.ragagent.browserskill.service.BrowserSkillManager;
import com.ragagent.common.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;

/**
 * 对照 Go session.Handler 的 BrowserSkillAccount / BrowserSkillDownload
 * （internal/handler/session/browserskill.go L88-158）：
 * <ul>
 *   <li>{@code GET/POST /api/v1/me/browser}——挂 v1 组标准中间件（Auth + API-Key
 *       门禁；Go 里直接注册在 v1 上，**无角色守卫**，API-Key 对未声明路由 default
 *       deny——见 APIKeyRoutePolicies 不登记本组）。scope 从请求主体推导（对照
 *       browserSkillScope 的 TenantIDFromContext/UserIDFromContext）。Java 侧
 *       {@code /api/v1/me/**} 不在 RbacInterceptor 的 pattern 清单里，与之等价。</li>
 *   <li>{@code GET /api/v1/me/browser/extension}——扩展包下载（同一中间件链）。</li>
 * </ul>
 *
 * <p>行序对照（golden 依赖）：scope 检查（401 "login required"——经 Auth 的部署
 * 不可达，保留对齐）→ Cache-Control: no-store → GET 分支 / Enabled 检查（503）→
 * 4096 限长绑定（400 "invalid browser action"）→ action 分派。POST 在 dev 默认
 * （未配 BROWSERSKILL_BINARY）恒 503 "local browser is unavailable"。</p>
 */
@RestController
@RequestMapping("/api/v1/me/browser")
public class BrowserSkillAccountController {

    private static final int MAX_BODY_BYTES = 4096;

    private final BrowserSkillManager browserSkill;
    private final ObjectMapper mapper;

    public BrowserSkillAccountController(BrowserSkillManager browserSkill, ObjectMapper mapper) {
        this.browserSkill = browserSkill;
        this.mapper = mapper;
    }

    /** 对照 browserSkillScope：认证主体 → (tenant, user) */
    private static Scope scope() {
        Long tenant = TenantContext.currentTenantId();
        String user = TenantContext.currentUserId();
        return new Scope(tenant == null ? 0 : tenant, user == null ? "" : user);
    }

    /** GET+POST 同一 handler（对照 Go 两条路由共用 BrowserSkillAccount） */
    @GetMapping
    public void account(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleAccount(request, response);
    }

    @PostMapping
    public void bindAccount(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleAccount(request, response);
    }

    private void handleAccount(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Scope current = scope();
        if (current.user().isEmpty() || current.tenant() == 0) {
            GinJson.error(response, 401, mapper, "login required");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        if ("GET".equals(request.getMethod())) {
            try {
                AccountStatus status = browserSkill.account(current);
                GinJson.dataSuccess(response, mapper, status);
            } catch (RuntimeException e) {
                GinJson.error(response, 503, mapper, "browser connection status unavailable");
            }
            return;
        }
        if (!browserSkill.enabled()) {
            GinJson.error(response, 503, mapper, "local browser is unavailable");
            return;
        }
        AccountInput input = readInput(request);
        if (input == null) {
            GinJson.error(response, 400, mapper, "invalid browser action");
            return;
        }
        String action = input.action == null ? "" : input.action;
        switch (action) {
            case "pair" -> {
                try {
                    String link = browserSkill.pair(current, input.origin == null ? "" : input.origin);
                    LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                    data.put("pairing_link", link);
                    GinJson.dataSuccess(response, mapper, data);
                } catch (RuntimeException e) {
                    GinJson.error(response, 409, mapper,
                            e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
            case "revoke" -> {
                try {
                    browserSkill.revoke(current);
                } catch (RuntimeException e) {
                    GinJson.error(response, 503, mapper, "could not revoke browser authorization");
                    return;
                }
                try {
                    AccountStatus status = browserSkill.account(current);
                    GinJson.dataSuccess(response, mapper, status);
                } catch (RuntimeException e) {
                    GinJson.error(response, 503, mapper, "browser connection status unavailable");
                }
            }
            default -> GinJson.error(response, 400, mapper, "invalid account browser action");
        }
    }

    /**
     * 对照 MaxBytesReader(4096) + ShouldBindJSON：超限/非 JSON/空 body 一律绑定失败
     * → 400 "invalid browser action"；"null" 字面量绑定成功、action 空走 default
     * （对照 gin 的 JSON 绑定语义）。
     */
    private AccountInput readInput(HttpServletRequest request) throws IOException {
        InputStream in = request.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (buf.size() + n > MAX_BODY_BYTES) {
                return null;
            }
            buf.write(chunk, 0, n);
        }
        try {
            AccountInput parsed = mapper.readValue(buf.toByteArray(), AccountInput.class);
            // Go 的 JSON 绑定遇 "null" 字面量不报错（零值 struct、action 空）——
            // 按空对象处理（约定 §9 波 1 G1 第 6 条），落到 default 分支
            return parsed != null ? parsed : new AccountInput();
        } catch (IOException e) {
            return null;
        }
    }

    /** 账户请求体（对照匿名 struct：action/origin；未知键忽略 = Go 语义） */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class AccountInput {
        public String action;
        public String origin;
    }

    /** GET /api/v1/me/browser/extension（对照 BrowserSkillDownload） */
    @RequestMapping(value = "/extension", method = RequestMethod.GET)
    public void download(HttpServletRequest request, HttpServletResponse response) throws IOException {
        browserSkill.downloadExtension(request, response);
    }
}
