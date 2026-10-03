package com.ragagent.retrieval.vlm;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VLM（视觉语言模型）Predict 客户端。
 *
 * <p>OpenAI 兼容 chat.completions：multipart 内容（text prompt 先行 + 每张图
 * base64 data-URI，detail=auto）、max_tokens=5000、temperature 缺省 0.1
 * （extra_config.temperature 覆盖）、reasoning/GPT5 模型请求整形
 * （max_tokens→max_completion_tokens，采样参数清零）、错误族
 * （no choices / 空 content + finish_reason=length 的截断语义）。
 * <b>ollama interface</b>：经既有 {@code OllamaService} 走
 * {@code POST /api/chat}（images 为原始字节，Jackson 序列化成 base64），
 * stream=false、temperature=0.1，取响应的
 * {@code message.content}；weknoracloud 走云契约。</p>
 */
public final class VlmClient {

    private static final int DEFAULT_MAX_TOKS = 5000;
    private static final double DEFAULT_TEMP = 0.1;

    /** VLM 消费面配置（appId/appSecret 为 WeKnoraCloud 已解密凭证）。 */
    public record VlmConfig(String source, String baseUrl, String modelName, String apiKey,
            String modelId, String interfaceType, String provider,
            Map<String, String> extra, String appId, String appSecret) {

        /** 便捷构造（凭证缺省空——非 WeKnoraCloud 路径用）。 */
        public VlmConfig(String source, String baseUrl, String modelName, String apiKey,
                String modelId, String interfaceType, String provider,
                Map<String, String> extra) {
            this(source, baseUrl, modelName, apiKey, modelId, interfaceType, provider, extra,
                    "", "");
        }

        public boolean isWeKnoraCloud() {
            return "weknoracloud".equals(provider);
        }

        public double temperature() {
            String v = extra == null ? null : extra.get("temperature");
            if (v != null) {
                try {
                    return Double.parseDouble(v);
                } catch (NumberFormatException ignored) {
                    // 解析失败保持缺省
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



    private VlmClient() {
    }

    /** HTTP 非 2xx（带状态码与响应体原文；WeKnoraCloud 的错误文案需要它们）。 */
    public static final class HttpStatusException extends RuntimeException {

        private final int status;
        private final String body;

        public HttpStatusException(int status, String body) {
            super("status " + status + ": " + body);
            this.status = status;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }
    }

    /** Predict 失败（受检异常）。 */
    public static final class VlmException extends Exception {
        public VlmException(String message) {
            super(message);
        }
    }

    /**
     * OpenAI 兼容预测入口。
     *
     * @param transport POST {base}/chat/completions 的出站通道（注入以便测试与
     *                  provider 接线）
     * @return choices[0].message.content
     */
    public static String predict(VlmConfig config, Transport transport, byte[][] imgBytesList,
            String prompt) throws VlmException {
        if (config != null && config.isOllama()) {
            return predictOllama(com.ragagent.llm.ollama.OllamaService.getOllamaService(), config,
                    imgBytesList, prompt);
        }
        if (config != null && config.isWeKnoraCloud()) {
            return predictWeKnoraCloud(config, transport, imgBytesList, prompt);
        }
        // 请求体构建（chat.completions 标准字段）
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
     * 本地 Ollama 预测：{@code /api/chat}——
     * 单条 user 消息（prompt + 各图原始字节）、{@code stream=false}、
     * {@code options.temperature=0.1}，回调里取最后一次响应的 {@code message.content}。
     * 错误文案：{@code Ollama VLM request: …}。
     */
    static String predictOllama(com.ragagent.llm.ollama.OllamaService service, VlmConfig config,
            byte[][] imgBytesList, String prompt) throws VlmException {
        List<byte[]> images = new ArrayList<>();
        int totalImageSize = 0;
        for (byte[] img : imgBytesList) {
            if (img != null && img.length > 0) {
                images.add(img);
                totalImageSize += img.length;
            }
        }
        com.ragagent.llm.ollama.OllamaMessage message =
                new com.ragagent.llm.ollama.OllamaMessage("user", prompt);
        message.setImages(images);

        com.ragagent.llm.ollama.OllamaChatRequest request =
                new com.ragagent.llm.ollama.OllamaChatRequest();
        request.setModel(config.modelName());
        request.setMessages(new ArrayList<>(List.of(message)));
        request.setStream(false);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", DEFAULT_TEMP);
        request.setOptions(options);

        final String[] result = new String[1];
        try {
            // 日志口径：model / numImages / totalImageSize
            service.chat(request, response -> {
                if (response != null && response.getMessage() != null) {
                    result[0] = response.getMessage().getContent();
                }
            });
        } catch (RuntimeException e) {
            throw new VlmException("Ollama VLM request: " + e.getMessage());
        }
        return result[0] == null ? "" : result[0];
    }

    /** WeKnoraCloud 的 VLM 端点路径。 */
    static final String WEKNORA_CLOUD_VLM_PATH = "/api/v1/chat/completions";

    /**
     * WeKnoraCloud 预测：{@code POST /api/v1/chat/completions}——multipart 内容（text + 每图 data URI）、
     * {@code max_tokens=5000}、{@code temperature=0.1}（**用常量，不读 extra 覆盖**）、
     * {@code stream=false}；鉴权走 {@code WeknoraCloudSign}（与 embedding/rerank/chat 同一份
     * 实现）；模型名可被 {@code extra.remote_model_name} 覆盖（{@code effectiveModelName}）。
     *
     * <p>错误族：构造期 {@code WeKnoraCloud VLM: AppID is required} /
     * {@code AppSecret is required}；运行期 {@code weknoracloud VLM: status %d: %s} /
     * {@code WeKnoraCloud VLM: no choices in response}。</p>
     */
    static String predictWeKnoraCloud(VlmConfig config, Transport transport, byte[][] imgBytesList,
            String prompt) throws VlmException {
        if (config.appId() == null || config.appId().isEmpty()) {
            throw new VlmException("WeKnoraCloud VLM: AppID is required");
        }
        if (config.appSecret() == null || config.appSecret().isEmpty()) {
            throw new VlmException("WeKnoraCloud VLM: AppSecret is required");
        }

        List<Object> parts = new ArrayList<>();
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        parts.add(textPart);
        for (byte[] img : imgBytesList) {
            if (img != null && img.length > 0) {
                Map<String, Object> imageUrl = new LinkedHashMap<>();
                imageUrl.put("url", "data:" + detectImageMime(img) + ";base64,"
                        + Base64.getEncoder().encodeToString(img));
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
        req.put("model", effectiveCloudModelName(config));
        req.put("messages", List.of(message));
        req.put("max_tokens", DEFAULT_MAX_TOKS);
        req.put("temperature", DEFAULT_TEMP);
        req.put("stream", false);

        String bodyJson;
        try {
            bodyJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(req);
        } catch (Exception e) {
            throw new VlmException("weknoracloud VLM: marshal: " + e.getMessage());
        }
        Map<String, String> headers = com.ragagent.embedding.provider.WeknoraCloudSign.sign(config.appId(),
                config.appSecret(), java.util.UUID.randomUUID().toString(), bodyJson);

        String baseUrl = config.baseUrl() == null ? "" : config.baseUrl().replaceAll("/+$", "");
        String respBody;
        try {
            respBody = transport.postWithHeaders(baseUrl + WEKNORA_CLOUD_VLM_PATH, headers, req);
        } catch (HttpStatusException e) {
            // weknoracloud VLM: status %d: %s
            throw new VlmException("weknoracloud VLM: status " + e.status() + ": " + e.body());
        } catch (Exception e) {
            throw new VlmException("weknoracloud VLM: do request: " + e.getMessage());
        }

        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(respBody);
        } catch (Exception e) {
            throw new VlmException("weknoracloud VLM: unmarshal: " + e.getMessage());
        }
        var choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new VlmException("WeKnoraCloud VLM: no choices in response");
        }
        return choices.get(0).path("message").path("content").asText("");
    }

    /** 云端模型名：{@code extra.remote_model_name} 优先。 */
    static String effectiveCloudModelName(VlmConfig config) {
        String remote = config.extra() == null ? null : config.extra().get("remote_model_name");
        if (remote != null && !remote.trim().isEmpty()) {
            return remote.trim();
        }
        return config.modelName();
    }

    /**
     * 请求整形：OpenAI reasoning /
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

    /** OpenAI reasoning / GPT5 家族判定。 */
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

    /** 图片 MIME 嗅探（非 image/ 前缀时回落 image/png）。 */
    static String detectImageMime(byte[] data) {
        String ct = sniff(data);
        return ct.startsWith("image/") ? ct : "image/png";
    }

    private static String sniff(byte[] b) {
        // 魔数嗅探表的相关子集（JPEG 3B / PNG 8B / GIF 6B /
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

        /**
         * 带自定义头的 POST（WeKnoraCloud 的签名头走这条；**不带** Authorization）。
         * 缺省实现抛异常——保持接口的函数式接口性质（现有 lambda stub 不受影响）。
         */
        default String postWithHeaders(String url, Map<String, String> headers, Object jsonBody)
                throws Exception {
            throw new UnsupportedOperationException("postWithHeaders not supported");
        }
    }
}
