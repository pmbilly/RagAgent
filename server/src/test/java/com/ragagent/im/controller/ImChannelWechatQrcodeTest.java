package com.ragagent.im.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.PlainErrorException;
import com.ragagent.im.wechat.WechatQRCodeService;
import com.ragagent.im.wechat.WechatQRCodeService.LoginResult;
import com.ragagent.im.wechat.WechatQRCodeService.QRCodeResult;

/**
 * 微信扫码两个端点的响应面（W5γ3.10b）——对照 Go {@code internal/handler/wechat_qrcode.go}：
 * 成功体是 gin.H（map）→ <b>键按字典序</b>；失败是 500 固定文案；缺 qrcode 400；
 * 服务 bean 缺位时保留 W5γ2 的接缝文案。
 */
class ImChannelWechatQrcodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 可编程桩（覆盖出站调用）。 */
    private static final class StubService extends WechatQRCodeService {
        QRCodeResult qrResult;
        LoginResult pollResult;
        boolean failQr;
        boolean failPoll;

        StubService() {
            super("http://127.0.0.1:1", null);
        }

        @Override
        public QRCodeResult getLoginQRCode() {
            if (failQr) {
                throw new IllegalStateException("boom");
            }
            return qrResult;
        }

        @Override
        public LoginResult pollQRCodeStatus(String qrcode) {
            if (failPoll) {
                throw new IllegalStateException("boom");
            }
            return pollResult;
        }
    }

    private static String json(ResponseEntity<Map<String, Object>> response) throws Exception {
        return MAPPER.writeValueAsString(response.getBody());
    }

    @Test
    @DisplayName("取码：200 {\"data\":{qrcode,qrcode_url}}（字典序）；出站失败 → 500 + 前缀 + 原因")
    void qrcodeEndpoint() throws Exception {
        ImChannelController controller = new ImChannelController(null);
        StubService stub = new StubService();
        controller.wechatQRCodeService(stub);

        // 注意 record 序是 (qrcodeUrl, qrcode)，照 Go 的 QRCodeResult 字段序
        stub.qrResult = new QRCodeResult("https://x/qr.png", "q-1");
        ResponseEntity<Map<String, Object>> ok = controller.wechatQrcode();
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("{\"data\":{\"qrcode\":\"q-1\",\"qrcode_url\":\"https://x/qr.png\"}}",
                json(ok));

        stub.failQr = true;
        PlainErrorException e = assertThrows(PlainErrorException.class, controller::wechatQrcode);
        assertEquals(500, e.status());
        assertEquals("failed to generate QR code: boom", e.getMessage());

        // bean 缺位 → 保留接缝文案
        ImChannelController bare = new ImChannelController(null);
        PlainErrorException seam = assertThrows(PlainErrorException.class, bare::wechatQrcode);
        assertEquals(500, seam.status());
        assertTrue(seam.getMessage().startsWith("failed to generate QR code: "));
    }

    @Test
    @DisplayName("轮询：wait 只有 status；confirmed 带 credentials（+baseurl，字典序）；失败 500 固定文案")
    void qrcodeStatusEndpoint() throws Exception {
        ImChannelController controller = new ImChannelController(null);
        StubService stub = new StubService();
        controller.wechatQRCodeService(stub);

        stub.pollResult = new LoginResult("wait", "", "", "", "");
        String wait = json(controller.wechatQrcodeStatus("{\"qrcode\":\"q-1\"}"));
        assertEquals("{\"data\":{\"status\":\"wait\"}}", wait);

        stub.pollResult = new LoginResult("confirmed", "tk", "bot-9", "u-9",
                "https://ilink.example");
        String confirmed = json(controller.wechatQrcodeStatus("{\"qrcode\":\"q-1\"}"));
        assertEquals("{\"data\":{\"baseurl\":\"https://ilink.example\",\"credentials\":"
                + "{\"bot_token\":\"tk\",\"ilink_bot_id\":\"bot-9\",\"ilink_user_id\":\"u-9\"},"
                + "\"status\":\"confirmed\"}}", confirmed);

        // confirmed 但 baseurl 为空 → 不带该键（照 Go）
        stub.pollResult = new LoginResult("confirmed", "tk", "bot-9", "u-9", "");
        String noBase = json(controller.wechatQrcodeStatus("{\"qrcode\":\"q-1\"}"));
        assertTrue(!noBase.contains("baseurl"));

        // 缺 qrcode → 400 固定文案（golden 已锁，见 ImContractTest）
        ResponseEntity<Map<String, Object>> bad = controller.wechatQrcodeStatus("{}");
        assertEquals(400, bad.getStatusCode().value());
        assertEquals("qrcode is required", bad.getBody().get("error"));
        assertEquals(400, controller.wechatQrcodeStatus(null).getStatusCode().value());

        // 出站失败 → 500 固定文案（不带原因，照 Go）
        stub.failPoll = true;
        ResponseEntity<Map<String, Object>> failed =
                controller.wechatQrcodeStatus("{\"qrcode\":\"q-1\"}");
        assertEquals(500, failed.getStatusCode().value());
        assertEquals("failed to check QR code status", failed.getBody().get("error"));

        // bean 缺位 → 接缝文案（同一文案）
        ImChannelController bare = new ImChannelController(null);
        assertEquals(500, bare.wechatQrcodeStatus("{\"qrcode\":\"q-1\"}")
                .getStatusCode().value());
    }
}
