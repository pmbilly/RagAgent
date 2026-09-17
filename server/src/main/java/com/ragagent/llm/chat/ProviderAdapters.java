package com.ragagent.llm.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.MessageContentPart;
import com.ragagent.llm.provider.AliyunProvider;
import com.ragagent.llm.provider.MoonshotProvider;
import com.ragagent.llm.provider.OpenAIProvider;
import com.ragagent.llm.provider.ProviderName;

import org.springframework.http.HttpHeaders;

/**
 * 13 个 OpenAI 兼容厂商适配器 + 有序注册表（对照 Go internal/models/chat/provider.go 全文）。
 *
 * <p>注册表<b>顺序即语义</b>：带 Matches 谓词的专有适配器必须排在同 provider 的兜底适配器之前
 * （例如 azureReasoningProvider 在 azureProvider 之前、openAIReasoningProvider 在所有 OpenAI
 * 兜底之前），逐条保序照抄，见 {@link #REGISTRY}。</p>
 *
 * <p>Go 的 {@code ForceRawHTTP()} 在 Java 侧无对应物（见 {@link ProviderAdapter} 类注释），
 * 因此 weKnoraCloud / deepseek / gemini 的该项覆写被丢弃——它们"必须走裸 HTTP"的诉求在
 * Java 的单路径设计里天然满足。</p>
 *
 * <p>唯一的 Java 侧新增：{@link WeKnoraCloud} 的签名算法。Go 复用
 * {@code internal/models/utils/signer.go} 的 {@code modelutils.Sign}，而 Java 侧的同名实现
 * （{@code model.service.WeKnoraCloudService.sign}）是包内可见，本包够不着，故此处按
 * signer.go 逐行复刻（见 {@link WeKnoraCloud#sign}）。**候选去重点**，待主会话统一收敛。</p>
 */
public final class ProviderAdapters {

    /** 有序注册表（对照 Go providerRegistry，逐个保序；别重排）。 */
    private static final List<ProviderAdapter> REGISTRY = List.of(
            new WeKnoraCloud(),
            new QwenThinking(),
            new Lkeap(),
            new Deepseek(),
            new Generic(),
            new LiteLlm(),
            new Gemini(),
            new Volcengine(),
            new Nvidia(),
            new AzureReasoning(),
            new Azure(),
            new OpenAiReasoning(),
            new Moonshot());

    private ProviderAdapters() {
    }

    /**
     * 对照 Go resolveProvider：返回处理该 provider+model 的适配器；
     * 无匹配时返回 {@link BaseProvider}（Bearer 鉴权、标准 endpoint、不发 thinking）。
     *
     * <p>{@code name} 为 null（= Go 里 DB 写入的未知厂商名，Java 侧 {@code ProviderName} 表达不了）
     * 时不会有任何适配器命中，与 Go 的行为一致。</p>
     */
    public static ProviderAdapter resolve(ProviderName name, String model) {
        String wanted = name == null ? "" : name.value();
        for (ProviderAdapter p : REGISTRY) {
            if (p.name().equals(wanted) && p.matches(model)) {
                return p;
            }
        }
        return new BaseProvider();
    }

    // ------------------------------------------------------------------
    // WeKnoraCloud：自定义 endpoint + 请求签名 + 多内容降级
    // （对照 provider.go:81-120）
    // ------------------------------------------------------------------

    public static final class WeKnoraCloud implements ProviderAdapter {

        @Override
        public String name() {
            return ProviderName.WEKNORA_CLOUD.value();
        }

        @Override
        public String endpoint(String baseUrl, String modelId, boolean isStream) {
            return trimRightSlash(baseUrl) + "/api/v1/chat/completions";
        }

        @Override
        public void auth(HttpHeaders headers, AuthCreds creds, byte[] body) {
            String requestId = UUID.randomUUID().toString();
            sign(creds.appId(), creds.appSecret(), requestId,
                    body == null ? "" : new String(body, StandardCharsets.UTF_8))
                    .forEach(headers::set);
        }

        /**
         * 对照 Go TransformMessages：把 MultiContent 降级为纯文本，
         * 同时**保留** tool_calls / tool_call_id / name，函数调用协议不断。
         */
        @Override
        public List<ChatMessage> transformMessages(
                List<ChatMessage> messages) {
            if (messages == null) {
                return null;
            }
            List<ChatMessage> result = new ArrayList<>(messages.size());
            for (ChatMessage m : messages) {
                ChatMessage msg = m;
                if (isBlank(m.getContent()) && m.getMultiContent() != null && !m.getMultiContent().isEmpty()) {
                    StringBuilder text = new StringBuilder();
                    for (MessageContentPart part : m.getMultiContent()) {
                        if (MessageContentPart.TYPE_TEXT.equals(part.getType())
                                && part.getText() != null && !part.getText().isEmpty()) {
                            if (text.length() > 0) {
                                text.append('\n');
                            }
                            text.append(part.getText());
                        }
                    }
                    msg = new ChatMessage(m.getRole(), text.toString());
                    msg.setName(m.getName());
                    msg.setToolCallId(m.getToolCallId());
                    msg.setToolCalls(m.getToolCalls());
                    msg.setReasoningContent(m.getReasoningContent());
                    msg.setKind(m.getKind());
                }
                result.add(msg);
            }
            return result;
        }

        // ── 对照 internal/models/utils/signer.go（Sign/md5Hex/generateNonce/rfc3986Encode）──

        /** 对照 Go Sign：按 WeKnoraCloud 参考实现生成请求头（apiKey 槽位由 AppSecret 承载）。 */
        static Map<String, String> sign(String appID, String apiKey, String requestID, String bodyJSON) {
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            String nonce = generateNonce(16);

            String bodyForHash = bodyJSON == null || bodyJSON.isEmpty() ? "{}" : bodyJSON;
            String bodyMD5 = md5Hex(bodyForHash);

            // Go 用 sort.Strings 排序后用 "&" 拼接（等价于 TreeMap 的字母序遍历）
            Map<String, String> params = new TreeMap<>();
            params.put("x-appid", appID);
            params.put("x-api-key", apiKey);
            params.put("x-request-id", requestID);
            params.put("x-timestamp", timestamp);
            params.put("x-nonce", nonce);
            params.put("body", bodyMD5);

            List<String> parts = new ArrayList<>(params.size());
            params.forEach((k, v) -> parts.add(rfc3986Encode(k) + "=" + rfc3986Encode(v)));
            String signature = md5Hex(String.join("&", parts));

            Map<String, String> out = new LinkedHashMap<>();
            out.put("X-APPID", appID);
            out.put("X-API-Key", apiKey);
            out.put("X-Request-ID", requestID);
            out.put("X-Timestamp", timestamp);
            out.put("X-Nonce", nonce);
            out.put("X-Signature", signature);
            return out;
        }

        private static final String NONCE_CHARS =
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        private static final SecureRandom NONCE_RANDOM = new SecureRandom();

        private static String generateNonce(int length) {
            StringBuilder b = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                b.append(NONCE_CHARS.charAt(NONCE_RANDOM.nextInt(NONCE_CHARS.length())));
            }
            return b.toString();
        }

        private static String md5Hex(String s) {
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(digest.length * 2);
                for (byte b : digest) {
                    sb.append(String.format(Locale.ROOT, "%02x", b));
                }
                return sb.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** 对照 rfc3986Encode：保留 A-Z a-z 0-9 - _ . ~，其余 %XX（按码点，非 UTF-8 字节）。 */
        static String rfc3986Encode(String s) {
            StringBuilder buf = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                    buf.append(c);
                } else {
                    buf.append(String.format(Locale.ROOT, "%%%02X", (int) c));
                }
            }
            return buf.toString();
        }
    }

    // ------------------------------------------------------------------
    // 阿里云 Qwen thinking 模型：enable_thinking（每次必发，非流式强制关闭）
    // （对照 provider.go:124-130）
    // ------------------------------------------------------------------

    public static final class QwenThinking implements ProviderAdapter {

        @Override
        public String name() {
            return ProviderName.ALIYUN.value();
        }

        @Override
        public boolean matches(String model) {
            return AliyunProvider.isQwenThinkingModel(model);
        }

        @Override
        public ThinkingStrategy thinking() {
            return new ThinkingStrategies.EnableThinking(true, true);
        }
    }

    // ------------------------------------------------------------------
    // LKEAP：thinking 走 {"thinking":{"type":...}}，仅 DeepSeek V3.x
    // R1 系列默认开启思维链、保持不动（落回 BaseProvider）。
    // （对照 provider.go:132-142）
    // ------------------------------------------------------------------

    public static final class Lkeap implements ProviderAdapter {

        @Override
        public String name() {
            return ProviderName.LKEAP.value();
        }

        @Override
        public boolean matches(String model) {
            return model != null && model.toLowerCase(Locale.ROOT).contains("deepseek-v3");
        }

        @Override
        public ThinkingStrategy thinking() {
            return new ThinkingStrategies.ThinkingTypeField();
        }
    }

    // ------------------------------------------------------------------
    // DeepSeek：不支持 tool_choice
    // （对照 provider.go:144-157）
    // ------------------------------------------------------------------

    public static final class Deepseek implements ProviderAdapter {

        @Override
        public String name() {
            return ProviderName.DEEPSEEK.value();
        }

        @Override
        public void shapeRequest(ObjectNode body, ChatOptions opts, boolean isStream) {
            if (opts != null && opts.getToolChoice() != null && !opts.getToolChoice().isEmpty()) {
                body.remove("tool_choice");
            }
        }
    }

    // ------------------------------------------------------------------
    // Generic(vLLM) / NVIDIA / LiteLLM：thinking 走 chat_template_kwargs
    // （对照 provider.go:159-174）
    // ------------------------------------------------------------------

    public static final class Generic implements ProviderAdapter {

        @Override
        public String name() {
            return ProviderName.GENERIC.value();
        }

        @Override
        public ThinkingStrategy thinking() {
            return new ThinkingStrategies.ChatTemplateKwargs();
        }
    }

    public static final class Nvidia implements ProviderAdapter {

        @Override
        public String name() {
            return ProviderName.NVIDIA.value();
        }

        @Override
        public ThinkingStrategy thinking() {
            return new ThinkingStrategies.ChatTemplateKwargs();
        }
    }

    public static final class LiteLlm implements ProviderAdapter {

        @Override
        public String name() {
            return ProviderName.LITELLM.value();
        }

        @Override
        public ThinkingStrategy thinking() {
            return new ThinkingStrategies.ChatTemplateKwargs();
        }
    }

    // ------------------------------------------------------------------
    // Gemini OpenAI 兼容层：工具思考签名放在 extra_content
    // （对照 provider.go:176-208）
    // ------------------------------------------------------------------

    public static final class Gemini implements ProviderAdapter {

        @Override
        public String name() {
            return ProviderName.GEMINI.value();
        }

        @Override
        public Map<String, JsonNode> extractToolCallMetadata(JsonNode raw) {
            if (raw == null || !raw.isObject()) {
                return null;
            }
            // 对照 Go：raw 反序列化失败（这里等价于非对象）→ nil
            JsonNode extraContent = raw.get("extra_content");
            if (extraContent == null || !extraContent.isObject()) {
                return null; // 对照 Go：tc.ExtraContent["google"] 不存在 → nil
            }
            JsonNode google = extraContent.get("google");
            if (google == null) {
                return null;
            }
            // 注意：显式 null 在 Go 里是 RawMessage("null")（len==4）→ 仍算命中并原样注入
            Map<String, JsonNode> out = new LinkedHashMap<>();
            out.put("google", google);
            return out;
        }

        @Override
        public void injectToolCallMetadata(ObjectNode toolCall, Map<String, JsonNode> metadata) {
            if (toolCall == null || metadata == null || metadata.isEmpty()) {
                return;
            }
            JsonNode google = metadata.get("google");
            if (google == null) {
                return;
            }
            toolCall.putObject("extra_content").set("google", google);
        }
    }

    // ------------------------------------------------------------------
    // 火山引擎 Ark：thinking 走 {"thinking":{"type":...}}
    // （对照 provider.go:210-215）
    // ------------------------------------------------------------------

    public static final class Volcengine implements ProviderAdapter {

        @Override
        public String name() {
            return ProviderName.VOLCENGINE.value();
        }

        @Override
        public ThinkingStrategy thinking() {
            return new ThinkingStrategies.ThinkingTypeField();
        }
    }

    // ------------------------------------------------------------------
    // Azure OpenAI：api-key 鉴权（reasoning 变体另剥采样参数）
    // （对照 provider.go:217-233）
    // ------------------------------------------------------------------

    public static class Azure implements ProviderAdapter {

        @Override
        public String name() {
            return ProviderName.AZURE_OPEN_AI.value();
        }

        @Override
        public void auth(HttpHeaders headers, AuthCreds creds, byte[] body) {
            headers.set("api-key", creds.apiKey());
        }
    }

    public static final class AzureReasoning extends Azure {

        @Override
        public boolean matches(String model) {
            return OpenAIProvider.isOpenAIReasoningOrGPT5Model(model);
        }

        @Override
        public void shapeRequest(ObjectNode body, ChatOptions opts, boolean isStream) {
            shapeOpenAiReasoning(body);
        }
    }

    // ------------------------------------------------------------------
    // OpenAI reasoning / GPT-5：无采样参数，必须用 max_completion_tokens
    // （对照 provider.go:235-245）
    // ------------------------------------------------------------------

    public static final class OpenAiReasoning implements ProviderAdapter {

        @Override
        public String name() {
            return ProviderName.OPENAI.value();
        }

        @Override
        public boolean matches(String model) {
            return OpenAIProvider.isOpenAIReasoningOrGPT5Model(model);
        }

        @Override
        public void shapeRequest(ObjectNode body, ChatOptions opts, boolean isStream) {
            shapeOpenAiReasoning(body);
        }
    }

    // ------------------------------------------------------------------
    // Moonshot：v1 模型只接受 temperature=1
    // （对照 provider.go:247-262）
    // ------------------------------------------------------------------

    public static final class Moonshot implements ProviderAdapter {

        @Override
        public String name() {
            return ProviderName.MOONSHOT.value();
        }

        @Override
        public boolean matches(String model) {
            return MoonshotProvider.isMoonshotFixedTempModel(model);
        }

        @Override
        public void shapeRequest(ObjectNode body, ChatOptions opts, boolean isStream) {
            // 钉死 temperature=1 并丢掉其它采样参数，与重构前"这些字段从不设置"的行为一致。
            body.put("temperature", 1);
            body.remove("top_p");
            body.remove("frequency_penalty");
            body.remove("presence_penalty");
        }
    }

    /**
     * 对照 Go shapeOpenAIReasoning：剥掉 o-series / GPT-5 不支持的采样参数，
     * 并把 max_tokens 迁移到 max_completion_tokens（见 issue #1283）。
     *
     * <p>注意 Java 的组装顺序是 shapeRequest 在前、完成预算在后（见 RemoteApiChat#buildOutbound），
     * 故正常情况下 body 里还没有任何 token 字段，迁移分支是防御性的空操作；
     * 真正保证"恰好一个 token 字段"的是 {@link CompletionBudget#wireField}。</p>
     */
    private static void shapeOpenAiReasoning(ObjectNode body) {
        body.remove("temperature");
        body.remove("top_p");
        body.remove("frequency_penalty");
        body.remove("presence_penalty");
        JsonNode maxTokens = body.get("max_tokens");
        if (maxTokens == null || maxTokens.isNull()) {
            body.remove("max_tokens");
            return;
        }
        if (!body.hasNonNull("max_completion_tokens")) {
            body.set("max_completion_tokens", maxTokens);
        }
        body.remove("max_tokens");
    }

    /** 对照 Go strings.TrimRight(s, "/")（包内共用，RemoteApiChat 处理 baseURL 时也用）。 */
    static String trimRightSlash(String s) {
        if (s == null) {
            return "";
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
