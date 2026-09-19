package com.ragagent.browserskill.controller;

import com.ragagent.browserskill.service.BrowserSkillManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 对照 Go router.go L184-186 的**引擎级注册**三条（不走 v1 组、不经 Auth/API-Key
 * 门禁；Java 侧经 AuthFilter NO_AUTH_API 白名单放行，APIKeyGateInterceptor 因无
 * 主体 scope 直通）：
 * <ul>
 *   <li>{@code GET /api/v1/local-browser/extension}——设备令牌（WebSocket 子协议
 *       bsk-auth.*）自证的扩展连接升级入口（对照 BrowserSkillExtension）。</li>
 *   <li>{@code POST /api/v1/local-browser/extension/authorize}——一次性配对/可
 *       续期凭据兑换（Bearer 票据自证；对照 BrowserSkillAuthorize）。</li>
 *   <li>{@code POST /api/v1/local-browser/internal}——集群内部签名 RPC 转发
 *       （HMAC + 时间窗 + nonce 防重放；对照 BrowserSkillInternal）。</li>
 * </ul>
 * 响应形态：鉴权失败族是 http.Error 纯文本（"消息\n" + text/plain; charset=utf-8 +
 * nosniff）；成功是标准库 json.Encoder 形态（Content-Type 无 charset、尾随换行）。
 */
@RestController
@RequestMapping("/api/v1/local-browser")
public class BrowserSkillGatewayController {

    private final BrowserSkillManager browserSkill;

    public BrowserSkillGatewayController(BrowserSkillManager browserSkill) {
        this.browserSkill = browserSkill;
    }

    /** GET /api/v1/local-browser/extension（对照 BrowserSkillExtension → ServeHTTP） */
    @RequestMapping(value = "/extension", method = RequestMethod.GET)
    public void extension(HttpServletRequest request, HttpServletResponse response) throws IOException {
        browserSkill.serveExtension(request, response);
    }

    /** POST /api/v1/local-browser/extension/authorize（对照 BrowserSkillAuthorize） */
    @RequestMapping(value = "/extension/authorize", method = RequestMethod.POST)
    public void authorize(HttpServletRequest request, HttpServletResponse response) throws IOException {
        browserSkill.authorizeHTTP(request, response);
    }

    /** POST /api/v1/local-browser/internal（对照 BrowserSkillInternal → InternalHTTP） */
    @RequestMapping(value = "/internal", method = RequestMethod.POST)
    public void internal(HttpServletRequest request, HttpServletResponse response) throws IOException {
        browserSkill.internalHTTP(request, response);
    }
}
