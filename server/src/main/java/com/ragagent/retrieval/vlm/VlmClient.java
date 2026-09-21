package com.ragagent.retrieval.vlm;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VLM（视觉语言模型）Predict 客户端（对照 Go internal/models/vlm/{vlm.go,
 * remote_api.go} 全文的确定性面，收尾批 2026-09-22 翻译）。
 *
 * <p>OpenAI 兼容 chat.completions：multipart 内容（text prompt 先行 + 每张图
 * base64 data-URI，detail=auto）、max_tokens=5000、temperature 缺省 0.1
 * （extra_config.temperature 覆盖）、reasoning/GPT5 模型请求整形
 * （max_tokens→max_completion_tokens，采样参数清零）、错误族
 * （no choices / 空 content + finish_reason=length 的截断语义）。
 * ollama interface 经既有 {@code OllamaService}；weknoracloud 源随云契约批。</p>
 */
public final class VlmClient {

    private static final int DEFAULT_MAX_TOKS = 5000;
    private static final double DEFAULT_TEMP = 0.1;

    /** 对照 types.VLM 的 Config（消费面子集）。 */
    public record VlmConfig(String source, String baseUrl, String modelName, String apiKey,
            String modelId, String interfaceType, String provider,
            Map<String, String> extra) {

        public double temperature() {
            String v = extra == null ? null : extra.get("temperature");
            if (v != null) {
                try {
                    return Double.parseDouble(v);
                } catch (NumberFormatException ignored) {
                    // Go ParseFloat 失败保持缺省
                }
            }
            return DEFAULT_TEMP;
        }

        public boolean isOllama() {
            // ConfigFromModel：interfaceType 空时 source==local → ollama，否则 openai
            String ifType = interfaceType == null || interfaceType.isEmpty()
                    ? ("local".equals(source) ? "ollama" : "openai")
                    : interfaceType;
            return "ollama".equals(ifType);
        }
    }

    /** 对照 ConfigFromModel（vlm.go L46-75）。 */
    public static VlmConfig configFromModel(com.ragagent.model.domain.Model m, String appId,
            String appSecret) {
        if (m == null) {
            return null;
        }
        var p = m.getParameters();
        String ifType = p == null ? "" : p.getInterfaceType();
        if (ifType == null || ifType.isEmpty()) {
            ifType = "local".equals(m.getSource()) ? "ollama" : "openai";
        }
        Map<String, String> extra = p == null ? Map.of()
                : new LinkedHashMap<>(p.getExtraConfig() == null ? Map.of() : p.getExtraConfig());
        return new VlmConfig(m.getSource(), p == null ? "" : p.getBaseUrl(),
                m.getName(), p == null ? "" : p.getApiKey(), m.getId(), ifType,
                p == null ? "" : p.getProvider(), extra);
    }

    private VlmClient() {
    }

    /** Predict 失败（Go 的 error 返回；message 对照 Go 原文）。 */
    public static final class VlmException extends Exception {
        public VlmException(String message) {
            super(message);
        }
    }

    /**
     * 对照 RemoteAPIVLM.Predict（remote_api.go L110-186）。
     *
     * @param transport POST {base}/chat/completions 的出站通道（注入以便测试与
     *                  provider 接线）
     * @return choices[0].message.content
     */
    public static String predict(VlmConfig config, Transport transport, byte[][] imgBytesList,
            String prompt) throws VlmException {
        // 请求体构建：与 Go openai.ChatCompletionRequest 字段一一对应
        List<Object> parts = new ArrayList<>();
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        parts.add(textPart);
        for (byte[] img : imgBytesList) {
            if (img != null && img.length > 0) {
                String mime = detectImageMime(img);
                String dataUri = "data:" + mime + ";base64,"
                        + Base64.getEncoder().encodeToString(img);
                Map<String, Object> imageUrl = new LinkedHashMap<>();
                imageUrl.put("url", dataUri);
                imageUrl.put("detail", "auto");
                Map<String, Object> imgPart = new LinkedHashMap<>();
                imgPart.put("type", "image_url");
                imgPart.put("image_url", imageUrl);
                parts.add(imgPart);
            }
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", parts);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", config.modelName());
        req.put("messages", List.of(message));
        req.put("max_tokens", DEFAULT_MAX_TOKS);
        req.put("temperature", config.temperature());
        shapeReasoningVlmRequest(config.modelName(), req);

        String url = config.baseUrl() == null ? "" : config.baseUrl().replaceAll("/+$", "")
                + "/chat/completions";
        String respBody;
        try {
            respBody = transport.post(url, config.apiKey(), req);
        } catch (Exception e) {
            throw new VlmException("OpenAI VLM request: " + e.getMessage());
        }

        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(respBody);
        } catch (Exception e) {
            throw new VlmException("OpenAI VLM request: " + e.getMessage());
        }
        var choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new VlmException("OpenAI VLM returned no choices");
        }
        var choice = choices.get(0);
        String content = choice.path("message").path("content").asText("");
        if (content.strip().isEmpty()
                && "length".equals(choice.path("finish_reason").asText(""))) {
            throw new VlmException("OpenAI VLM returned no content: completion truncated at "
                    + DEFAULT_MAX_TOKS + " tokens (finish_reason=length)");
        }
        return content;
    }

    /**
     * 对照 shapeReasoningVLMRequest（remote_api.go L190-202）：OpenAI reasoning /
     * GPT5 家族把 max_tokens 平移到 max_completion_tokens，采样参数清零。
     */
    static void shapeReasoningVlmRequest(String modelName, Map<String, Object> req) {
        if (!isReasoningOrGpt5(modelName)) {
            return;
        }
        Object maxTokens = req.get("max_tokens");
        if (!req.containsKey("max_completion_tokens") && maxTokens instanceof Number n) {
            req.put("max_completion_tokens", n.intValue());
        }
        req.remove("max_tokens");
        req.put("temperature", 0);
    }

    /** 对照 provider.IsOpenAIReasoningOrGPT5Model（provider/openai.go L70-86）。 */
    static boolean isReasoningOrGpt5(String modelName) {
        String name = modelName == null ? "" : modelName.strip().toLowerCase();
        if (name.isEmpty()) {
            return false;
        }
        if (name.startsWith("gpt-5")) {
            return true;
        }
        for (String prefix : new String[] {"o1", "o3", "o4"}) {
            if (name.equals(prefix) || name.startsWith(prefix + "-")) {
                return true;
            }
        }
        return false;
    }

    /** 对照 detectImageMIME（remote_api.go L208-214，http.DetectContentType 子集）。 */
    static String detectImageMime(byte[] data) {
        String ct = sniff(data);
        return ct.startsWith("image/") ? ct : "image/png";
    }

    private static String sniff(byte[] b) {
        // Go http.DetectContentType 嗅探表的相关子集（JPEG 3B / PNG 8B / GIF 6B /
        // WEBP 12B / XML 前缀）
        if (b.length >= 3 && b[0] == (byte) 0xFF && b[1] == (byte) 0xD8 && b[2] == (byte) 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "image/gif";
        }
        if (b.length >= 12 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        if (b.length >= 5 && b[0] == '<' && b[1] == '?') {
            return "text/xml";
        }
        return "application/octet-stream";
    }

    /** 出站 POST 通道（生产由 HTTP 客户端实现；测试用内存 stub）。 */
    public interface Transport {
        String post(String url, String apiKey, Object jsonBody) throws Exception;
    }
}
