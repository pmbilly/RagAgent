package com.ragagent.im.wechat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * 微信扫码登录服务（W5γ3.10b）——对照 Go {@code internal/im/wechat/qrcode.go}：
 * 取码端点与参数、状态轮询的四个状态、confirmed 凭证、非 200 折错、以及
 * <b>客户端超时算 wait</b>（长轮询的正常形态，照 Go）。
 */
class WechatQRCodeServiceTest {

    private record Captured(String path, String query, Map<String, List<String>> headers) {
    }

    private HttpServer server;
    private String apiBase;
    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private Function<Captured, byte[]> responder = c -> "{}".getBytes(StandardCharsets.UTF_8);
    private int status = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.add(new Captured(exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(), exchange.getRequestHeaders()));
            byte[] resp = responder.apply(captured.get(captured.size() - 1));
            exchange.sendResponseHeaders(status, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("取码：GET /ilink/bot/get_bot_qrcode?bot_type=3 → {qrcode, qrcode_url}；空码/非 200 折错")
    void fetchesLoginQrCode() throws Exception {
        responder = c -> ("{\"qrcode\":\"q-1\",\"qrcode_img_content\":\"https://x/qr.png\"}")
                .getBytes(StandardCharsets.UTF_8);
        WechatQRCodeService service = new WechatQRCodeService(apiBase, null);

        WechatQRCodeService.QRCodeResult result = service.getLoginQRCode();
        assertEquals("q-1", result.qrcode());
        assertEquals("https://x/qr.png", result.qrcodeUrl());
        assertEquals("/ilink/bot/get_bot_qrcode", captured.get(0).path());
        assertEquals("bot_type=3", captured.get(0).query());

        responder = c -> "{\"qrcode\":\"\"}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, service::getLoginQRCode);

        status = 500;
        responder = c -> "boom".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, service::getLoginQRCode);
    }

    @Test
    @DisplayName("轮询：四个状态 + confirmed 凭证；带 iLink-App-ClientVersion 头；非 200 折错")
    void pollsStatus() throws Exception {
        WechatQRCodeService service = new WechatQRCodeService(apiBase, null);

        responder = c -> "{\"status\":\"scaned\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("scaned", service.pollQRCodeStatus("q 1").status());
        assertEquals("/ilink/bot/get_qrcode_status", captured.get(0).path());
        assertEquals("qrcode=q+1", captured.get(0).query());
        assertEquals("1", captured.get(0).headers().get("Ilink-app-clientversion").get(0));

        responder = c -> ("{\"status\":\"confirmed\",\"bot_token\":\"tk\","
                + "\"ilink_bot_id\":\"bot-9\",\"ilink_user_id\":\"u-9\","
                + "\"baseurl\":\"https://ilink.example\"}").getBytes(StandardCharsets.UTF_8);
        WechatQRCodeService.LoginResult confirmed = service.pollQRCodeStatus("q-1");
        assertEquals("confirmed", confirmed.status());
        assertEquals("tk", confirmed.botToken());
        assertEquals("bot-9", confirmed.ilinkBotId());
        assertEquals("u-9", confirmed.ilinkUserId());
        assertEquals("https://ilink.example", confirmed.baseUrl());

        responder = c -> "{\"status\":\"expired\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("expired", service.pollQRCodeStatus("q-1").status());

        status = 500;
        responder = c -> "bad".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> service.pollQRCodeStatus("q-1"));
    }

    @Test
    @DisplayName("客户端超时是长轮询的正常形态：算 wait 不报错（照 Go 的 pollCtx.Err 分支）")
    void clientTimeoutMeansWait() throws Exception {
        // 服务端挂住 400ms，客户端 120ms 就超时
        responder = c -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{\"status\":\"confirmed\"}".getBytes(StandardCharsets.UTF_8);
        };
        WechatQRCodeService service = new WechatQRCodeService(apiBase, null, 120);
        WechatQRCodeService.LoginResult result = service.pollQRCodeStatus("q-1");
        assertEquals("wait", result.status());
        assertTrue(result.botToken().isEmpty());
    }
}
