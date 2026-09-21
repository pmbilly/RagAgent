package com.ragagent.retrieval.vlm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.ragagent.retrieval.vlm.VlmClient.VlmConfig;
import com.ragagent.retrieval.vlm.VlmClient.VlmException;

/**
 * VLM Predict 客户端（对照 Go vlm/remote_api.go）：请求体形状（multipart、
 * data-URI、max_tokens/temperature）、reasoning/GPT5 整形、错误族、MIME 嗅探。
 * 出站用 stub transport 捕获（约定 §5：测试禁真实网络）。
 */
class VlmClientTest {

    private static VlmConfig config(String modelName) {
        return new VlmConfig("remote", "https://api.example.com/v1", modelName, "sk-key",
                "model-1", "openai", "", Map.of());
    }

    private static byte[] png() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0};
    }

    @Test
    void predictBuildsMultipartRequestAndParsesContent() throws Exception {
        AtomicReference<String> url = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        VlmClient.Transport transport = (u, apiKey, jsonBody) -> {
            url.set(u);
            body.set(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(jsonBody));
            return "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"图里是一张表格\"},\"finish_reason\":\"stop\"}]}";
        };

        String out = VlmClient.predict(config("gpt-4o"), transport,
                new byte[][] {png(), new byte[0], "junk".getBytes(StandardCharsets.UTF_8)},
                "描述图片");
        assertEquals("图里是一张表格", out);
        assertTrue(url.get().endsWith("/v1/chat/completions"));
        // 空 image 跳过；PNG 嗅探为 image/png；text prompt 先行
        assertTrue(body.get().contains("\"type\":\"text\""));
        assertTrue(body.get().contains("\"text\":\"描述图片\""));
        assertTrue(body.get().contains("data:image/png;base64,"));
        assertEquals(2, body.get().split("\"detail\":\"auto\"").length - 1, "空图不计入 parts");
        assertTrue(body.get().contains("\"detail\":\"auto\""));
        assertTrue(body.get().contains("\"max_tokens\":5000"));
        assertTrue(body.get().contains("\"temperature\":0.1"));
    }

    @Test
    void reasoningModelShapesRequest() throws Exception {
        Map<String, String> bodyByUrl = new LinkedHashMap<>();
        VlmClient.Transport t = (u, apiKey, jsonBody) -> {
            bodyByUrl.put(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(jsonBody), u);
            return "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        };
        VlmClient.predict(config("gpt-5"), t, new byte[0][], "p");
        String gpt5Body = bodyByUrl.keySet().iterator().next();
        assertTrue(gpt5Body.contains("\"max_completion_tokens\":5000"), gpt5Body);
        assertTrue(gpt5Body.contains("\"temperature\":0"), "reasoning 模型采样清零");
        assertFalse2(gpt5Body.contains("\"max_tokens\":"), gpt5Body);

        VlmClient.predict(config("o4-mini"), t, new byte[0][], "p");
        VlmClient.predict(config("deepseek-r1"), t, new byte[0][], "p");
        String nonReasoning = bodyByUrl.keySet().stream()
                .filter(b -> b.contains("\"model\":\"deepseek-r1\"")).findFirst().orElse("");
        assertTrue(nonReasoning.contains("\"max_tokens\":5000"), "非 reasoning 模型不整形");
    }

    private static void assertFalse2(boolean cond, String msg) {
        org.junit.jupiter.api.Assertions.assertFalse(cond, msg);
    }

    @Test
    void errorFamilies() {
        VlmClient.Transport noChoices = (u, apiKey, body) -> "{\"choices\":[]}";
        VlmException e1 = assertThrows(VlmException.class,
                () -> VlmClient.predict(config("gpt-4o"), noChoices, new byte[0][], "p"));
        assertEquals("OpenAI VLM returned no choices", e1.getMessage());

        VlmClient.Transport truncated = (u, apiKey, body) ->
                "{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"length\"}]}";
        VlmException e2 = assertThrows(VlmException.class,
                () -> VlmClient.predict(config("gpt-4o"), truncated, new byte[0][], "p"));
        assertEquals("OpenAI VLM returned no content: completion truncated at 5000 tokens "
                + "(finish_reason=length)", e2.getMessage());

        VlmClient.Transport transportError = (u, apiKey, body) -> {
            throw new java.io.IOException("connection refused");
        };
        VlmException e3 = assertThrows(VlmException.class,
                () -> VlmClient.predict(config("gpt-4o"), transportError, new byte[0][], "p"));
        assertTrue(e3.getMessage().startsWith("OpenAI VLM request: "));
    }

    @Test
    void mimeSniffing() {
        assertEquals("image/png", VlmClient.detectImageMime(png()));
        assertEquals("image/jpeg", VlmClient.detectImageMime(
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0}));
        assertEquals("image/gif", VlmClient.detectImageMime("GIF89a".getBytes()));
        assertEquals("image/png", VlmClient.detectImageMime("plain text".getBytes()),
                "非图片回落 image/png");
    }

    @Test
    void base64RoundTripMatchesDataUri() throws Exception {
        byte[] img = "fakepng".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> body = new AtomicReference<>();
        VlmClient.Transport t = (u, apiKey, jsonBody) -> {
            body.set(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(jsonBody));
            return "{\"choices\":[{\"message\":{\"content\":\"x\"}}]}";
        };
        VlmClient.predict(config("gpt-4o"), t, new byte[][] {img}, "p");
        String expect = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(img);
        assertTrue(body.get().contains(expect));
    }
}
