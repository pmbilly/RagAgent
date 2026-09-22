package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.llm.domain.ChatMessage;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/models/chat/ollama_test.go
 * （TestResolveImageForOllamaRejectsInternalURL / TestResolveImageForOllamaBlocksRedirectToInternalURL）
 * 与 image_resolve.go 的各分支。
 *
 * <p>与 Go 测试的对应差异：Go 用 {@code t.Setenv("SSRF_WHITELIST", ...)} + 包级重置；
 * Java 侧改用等价的 {@link SsrfGuard#reloadWhitelist(String)}（对照 Go 的
 * SetSSRFWhitelistFromRaw 运行时调谐路径），并把它注入 {@link LlmTransport}。</p>
 */
class ImageResolverTest {

    /** 1x1 PNG 的头部（足以让 DetectContentType 认出 image/png） */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'};

    private final SsrfGuard guard = new SsrfGuard();
    private SsrfGuard.Whitelist whitelistSnapshot;

    @BeforeEach
    void snapshotWhitelist() {
        // reloadWhitelist 改的是进程级 static——不还原会踩坏同 JVM 的后续测试
        whitelistSnapshot = SsrfGuard.snapshotWhitelist();
    }

    @AfterEach
    void tearDown() {
        SsrfGuard.restoreWhitelist(whitelistSnapshot);
        LlmTransport.setSsrfGuard(new SsrfGuard());
        ImageResolver.setLocalImageResolver(null);
    }

    /**
     * 对照 Go TestResolveImageForOllamaRejectsInternalURL：
     * 云元数据地址在 SSRF 校验阶段就被拒，连请求都不会发出去。
     */
    @Test
    void resolveImageForOllamaRejectsInternalUrl() {
        guard.reloadWhitelist("");
        LlmTransport.setSsrfGuard(guard);

        assertNull(ImageResolver.resolveImageForOllama("http://169.254.169.254/latest/meta-data/"),
                "resolveImageForOllama returned data for blocked internal URL");
    }

    /**
     * 对照 Go TestResolveImageForOllamaBlocksRedirectToInternalURL：
     * 起始 URL 在白名单里（放行），但重定向目标必须重新过 SSRF 校验。
     */
    @Test
    void resolveImageForOllamaBlocksRedirectToInternalUrl() throws IOException {
        guard.reloadWhitelist("127.0.0.1");
        LlmTransport.setSsrfGuard(guard);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/image.png";
            assertNull(ImageResolver.resolveImageForOllama(url),
                    "resolveImageForOllama returned data after redirect to blocked internal URL");
        } finally {
            server.stop(0);
        }
    }

    /** 白名单放行的本机服务：正常取回图片字节（证明 SSRF 校验不是"一律拒绝"）。 */
    @Test
    void resolveImageForOllamaFetchesWhitelistedHost() throws IOException {
        guard.reloadWhitelist("127.0.0.1");
        LlmTransport.setSsrfGuard(guard);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, PNG_BYTES.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(PNG_BYTES);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/image.png";
            assertArrayEquals(PNG_BYTES, ImageResolver.resolveImageForOllama(url));
        } finally {
            server.stop(0);
        }
    }

    /** data: URI 与 http(s) 的行为分叉（对照 Go resolveImageURLForOllama / resolveImageURLForLLM）。 */
    @Test
    void dataAndHttpBranches() {
        String base64 = Base64.getEncoder().encodeToString(PNG_BYTES);

        assertEquals("data:image/png;base64," + base64,
                ImageResolver.resolveImageUrlForLlm("data:image/png;base64," + base64));
        assertEquals("https://example.com/a.png", ImageResolver.resolveImageUrlForLlm("https://example.com/a.png"));
        assertEquals("http://example.com/a.png", ImageResolver.resolveImageUrlForLlm("http://example.com/a.png"));

        assertArrayEquals(PNG_BYTES, ImageResolver.resolveImageUrlForOllama("data:image/png;base64," + base64));
        assertNull(ImageResolver.resolveImageUrlForOllama("data:image/png," + base64),
                "缺少 ;base64, 分隔符时返回 nil");
        assertNull(ImageResolver.resolveImageUrlForOllama("data:image/png;base64,!!!not-base64!!!"),
                "base64 解不开时返回 nil");
        assertNull(ImageResolver.resolveImageUrlForOllama("https://example.com/a.png"),
                "远程 http(s) 不在本函数范围内");
    }

    /** 应用托管路径经 LocalImageResolver 转成 base64 data URI；解析不出来则原样返回。 */
    @Test
    void applicationStoredImageUsesResolver() {
        ImageResolver.setLocalImageResolver(url -> "resource://kb/1.png".equals(url) ? PNG_BYTES : null);

        String got = ImageResolver.resolveImageUrlForLlm("resource://kb/1.png");
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES), got);

        assertTrue(ImageResolver.isApplicationStoredImage("resource://x"));
        assertTrue(ImageResolver.isApplicationStoredImage("local://x"));
        assertTrue(ImageResolver.isApplicationStoredImage("storage://x"));
        assertFalse(ImageResolver.isApplicationStoredImage("https://x"));

        // 解析器给不出数据 + 磁盘上没有该文件 → 原样返回（对照 Go）
        String missing = "local://" + UUID.randomUUID() + "/missing.png";
        assertEquals(missing, ImageResolver.resolveImageUrlForLlm(missing));
        assertNull(ImageResolver.readLocalStorageBytes(missing));
    }

    /** 对照 Go isMultimodalNotSupportedError 的与/或条件。 */
    @Test
    void multimodalNotSupportedDetection() {
        assertFalse(ImageResolver.isMultimodalNotSupportedMessage(null));
        assertTrue(ImageResolver.isMultimodalNotSupportedMessage("This model does not support image input"));
        assertTrue(ImageResolver.isMultimodalNotSupportedMessage("MULTIMODAL INPUT UNSUPPORTED"));
        assertTrue(ImageResolver.isMultimodalNotSupportedMessage("invalid request: 400 vision not supported"));

        // 只命中一半不算
        assertFalse(ImageResolver.isMultimodalNotSupportedMessage("invalid image format"));
        assertFalse(ImageResolver.isMultimodalNotSupportedMessage("model not support tools"));
        assertFalse(ImageResolver.isMultimodalNotSupportedError(null));
        assertTrue(ImageResolver.isMultimodalNotSupportedError(
                new RuntimeException("model does not support vision")));
    }

    /** 对照 Go stripImagesFromMessages：返回副本，原消息不受影响。 */
    @Test
    void stripImagesFromMessagesCopiesAndClears() {
        ChatMessage user = ChatMessage.user("看图");
        user.setImages(List.of("data:image/png;base64,AAA"));
        user.setReasoningContent("r");
        ChatMessage tool = ChatMessage.tool("call-1", "search", "result");

        List<ChatMessage> cleaned = ImageResolver.stripImagesFromMessages(List.of(user, tool));

        assertEquals(2, cleaned.size());
        assertNull(cleaned.get(0).getImages());
        assertEquals("看图", cleaned.get(0).getContent());
        assertEquals("r", cleaned.get(0).getReasoningContent());
        assertEquals("call-1", cleaned.get(1).getToolCallId());
        assertEquals("search", cleaned.get(1).getName());
        // 原消息仍然带着图片（Go 是结构体值拷贝）
        assertEquals(1, user.getImages().size());
    }

    /** 对照 Go http.DetectContentType 的图片/文本/兜底分支。 */
    @Test
    void detectContentType() {
        assertEquals("image/png", ImageResolver.detectContentType(PNG_BYTES));
        assertEquals("image/jpeg", ImageResolver.detectContentType(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}));
        assertEquals("image/gif", ImageResolver.detectContentType("GIF89a...".getBytes(StandardCharsets.UTF_8)));
        assertEquals("image/bmp", ImageResolver.detectContentType("BM1234567890".getBytes(StandardCharsets.UTF_8)));
        assertEquals("image/x-icon",
                ImageResolver.detectContentType(new byte[]{0, 0, 1, 0, 1, 0, 2, 0, 3, 0}));
        // RIFF + 4 个任意字节 + WEBPVP（mask 把中间 4 字节置 0，不参与匹配）
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P'};
        assertEquals("image/webp", ImageResolver.detectContentType(webp));
        assertEquals("text/plain; charset=utf-8", ImageResolver.detectContentType("hello world".getBytes(StandardCharsets.UTF_8)));
        // 含二进制控制字符 → 不是文本 → 兜底 octet-stream
        assertEquals("application/octet-stream", ImageResolver.detectContentType(new byte[]{0x00, 0x01, 0x02, 0x03}));
        // 空数据在 Go 里由 textSig 兜住（实测 Go 行为不是 octet-stream）
        assertEquals("text/plain; charset=utf-8", ImageResolver.detectContentType(new byte[0]));
        // 最多只看前 512 字节：图片头之后的内容不影响判定
        byte[] big = new byte[1024];
        System.arraycopy(PNG_BYTES, 0, big, 0, PNG_BYTES.length);
        assertEquals("image/png", ImageResolver.detectContentType(big));
    }
}
