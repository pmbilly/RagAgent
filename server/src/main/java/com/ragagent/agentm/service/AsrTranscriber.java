package com.ragagent.agentm.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.llm.chat.LlmTransport;

/**
 * ASR（语音识别）provider 调用接缝（对照 Go internal/models/asr 全包，381 行）。
 *
 * <p><b>为什么是接缝而不是整个 asr 包照搬</b>：VLM/ASR 是 §2.3 标记的"前置缺口"，
 * 本批不整体翻译 provider 库——但 asr/check 端点的行为验证需要真实出站调用
 * （Go dev 用 stub ASR server 双端同打），因此按 Go 的<b>唯一 provider</b>
 * （OpenAIASR：所有 ASR 厂商共用 OpenAI 兼容 /v1/audio/transcriptions API，
 * asr.go NewASR 注释原文）做一份薄复刻作为缺省实现；将来翻整个 asr 包时
 * 只需替换本接口的实现。</p>
 *
 * <p>复刻范围（go-openai@v1.41.2 经由 OpenAIASR 的可观察行为）：</p>
 * <ul>
 *   <li>构造期 {@code validateASRBaseURL}：SSRF 校验，失败报
 *       "base URL SSRF check failed: ..."（对应 Go NewASR 的 error 返回）；</li>
 *   <li>multipart POST {baseURL}/audio/transcriptions，字段序
 *       file → model → response_format=verbose_json → language（go-openai audio.go）；
 *       300s 超时（asrDefaultTimeout）；</li>
 *   <li>非 2xx 错误文案<b>逐字节</b>对照 go-openai error.go：
 *       body 可解析且带合法 message → {@code error, status code: %d, status: %s, message: %s}；
 *       否则 RequestError → {@code error, status code: %d, status: %s, message: %s, body: %s}
 *       （message 段为 json 解析错误原文；Err=nil 时是 Go 的 {@code %!s(<nil>)}）；</li>
 *   <li>200 响应 {text, segments}：text 过 strings.TrimSpace。</li>
 * </ul>
 */
public interface AsrTranscriber {

    /** 对照 Go asr.Config（本端点只用其中这些字段）。 */
    record AsrConfig(String baseUrl, String modelName, String apiKey, String modelId,
                     String language, Map<String, String> customHeaders) {}

    /** 对照 Go TranscriptionResult（text + segments；segments omitempty）。 */
    record TranscriptionResult(String text, List<Segment> segments) {
        public record Segment(double start, double end, String text) {}
    }

    /** Go NewASR 的 error 形态（message 已含 "base URL SSRF check failed" 等前缀原文）。 */
    class AsrCreateException extends RuntimeException {
        public AsrCreateException(String message) { super(message); }
    }

    /** Go Transcribe 的 error 形态（message 已按 "ASR transcription request failed: %w" 包裹）。 */
    class AsrTranscribeException extends RuntimeException {
        public AsrTranscribeException(String message) { super(message); }
    }

    /**
     * 对照 NewASR + Transcribe 的合成入口。构造失败抛 {@link AsrCreateException}，
     * 调用失败抛 {@link AsrTranscribeException}，成功返回结果。
     */
    TranscriptionResult transcribe(AsrConfig config, byte[] audioBytes, String fileName);

    // ==================================================================
    // 缺省实现：OpenAI 兼容 transcription（对照 internal/models/asr/openai.go）
    // ==================================================================

    class OpenAiAsrTranscriber implements AsrTranscriber {

        /** 对照 asrDefaultTimeout = 300s（audio transcription can be slow）。 */
        private static final Duration ASR_DEFAULT_TIMEOUT = Duration.ofSeconds(300);

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final SsrfGuard ssrfGuard;

        public OpenAiAsrTranscriber(SsrfGuard ssrfGuard) {
            this.ssrfGuard = ssrfGuard;
        }

        @Override
        public TranscriptionResult transcribe(AsrConfig config, byte[] audioBytes, String fileName) {
            // 对照 validateASRBaseURL（NewASR 构造期）
            if (config.baseUrl() != null && !config.baseUrl().isEmpty()) {
                try {
                    ssrfGuard.validateURLForSSRF(config.baseUrl());
                } catch (RuntimeException e) {
                    throw new AsrCreateException("base URL SSRF check failed: " + e.getMessage());
                }
            }
            if (audioBytes == null || audioBytes.length == 0) {
                throw new AsrTranscribeException("ASR transcription request failed: audio bytes are empty");
            }
            String name = fileName == null || fileName.isEmpty() ? "audio.mp3" : fileName;

            String url = config.baseUrl() + "/audio/transcriptions";
            String boundary = "go-openai-" + UUID.randomUUID();
            byte[] body = multipartBody(boundary, config, audioBytes, name);

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Authorization", "Bearer " + (config.apiKey() == null ? "" : config.apiKey()))
                    .timeout(ASR_DEFAULT_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (config.customHeaders() != null) {
                config.customHeaders().forEach(builder::header);
            }

            HttpResponse<InputStream> resp;
            try {
                resp = LlmTransport.send(builder.build());
            } catch (IOException e) {
                // Go：url.Error 形态（Post "...": dial tcp ...），这里取 JDK 消息同语义包裹
                throw new AsrTranscribeException("ASR transcription request failed: Post \""
                        + url + "\": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AsrTranscribeException("ASR transcription request failed: interrupted");
            }
            byte[] respBody;
            try (InputStream stream = resp.body()) {
                respBody = stream == null ? new byte[0] : stream.readAllBytes();
            } catch (IOException e) {
                throw new AsrTranscribeException("ASR transcription request failed: " + e.getMessage());
            }
            int status = resp.statusCode();
            if (status != 200) {
                // 对照 go-openai handleErrorResp + Error() 的字节形态
                throw new AsrTranscribeException(
                        "ASR transcription request failed: " + goOpenAiError(status, respBody));
            }
            JsonNode node;
            try {
                node = MAPPER.readTree(respBody);
            } catch (IOException e) {
                throw new AsrTranscribeException(
                        "ASR transcription request failed: decode response: " + e.getMessage());
            }
            String text = node.path("text").asText("").trim();
            List<TranscriptionResult.Segment> segments = new ArrayList<>();
            JsonNode segs = node.get("segments");
            if (segs != null && segs.isArray()) {
                for (JsonNode s : segs) {
                    segments.add(new TranscriptionResult.Segment(s.path("start").asDouble(0), s.path("end").asDouble(0),
                            s.path("text").asText("").trim()));
                }
            }
            return new TranscriptionResult(text, segments);
        }

        /** 对照 audioMultipartForm 的字段序：file → model → response_format → language。 */
        private static byte[] multipartBody(String boundary, AsrConfig config, byte[] audio, String fileName) {
            StringBuilder sb = new StringBuilder();
            String fileContentType = probeContentType(fileName);
            sb.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                    .append(fileName).append("\"\r\n")
                    .append("Content-Type: ").append(fileContentType).append("\r\n\r\n");
            byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
            StringBuilder tail = new StringBuilder();
            tail.append("\r\n--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                    .append(config.modelName() == null ? "" : config.modelName()).append("\r\n")
                    .append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n")
                    .append("verbose_json\r\n");
            if (config.language() != null && !config.language().isEmpty()) {
                tail.append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
                        .append(config.language()).append("\r\n");
            }
            tail.append("--").append(boundary).append("--\r\n");
            byte[] tailBytes = tail.toString().getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[head.length + audio.length + tailBytes.length];
            System.arraycopy(head, 0, out, 0, head.length);
            System.arraycopy(audio, 0, out, head.length, audio.length);
            System.arraycopy(tailBytes, 0, out, head.length + audio.length, tailBytes.length);
            return out;
        }

        /** 粗略 MIME 推断（stub A/B 不校验该头；对照 DetectAudioFormat 只留常见后缀）。 */
        private static String probeContentType(String fileName) {
            String lower = fileName.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".wav")) {
                return "audio/wav";
            }
            if (lower.endsWith(".flac")) {
                return "audio/flac";
            }
            if (lower.endsWith(".ogg")) {
                return "audio/ogg";
            }
            return "audio/mpeg";
        }

        /**
         * 对照 go-openai error.go：APIError（body JSON 且 error.message 可解）与
         * RequestError（其余）两族的 Error() 文本。
         */
        /**
         * 对照 Go {@code encoding/json} 的顶层解析错误文案（go-openai 把 body 塞进
         * RequestError.Err 的 {@code %s} 段）。Jackson 的措辞完全不同，这里按
         * Go 的逐字符扫描规则仿真常见形态：空体 → "unexpected end of JSON input"；
         * 非 JSON 起始字符 → "invalid character 'x' looking for beginning of value"；
         * 字面量中间坏掉 → "invalid character 'x' in literal ... (expecting 'y')"。
         * 深结构坏掉时回落占位文案（golden 未覆盖，备案）。
         */
        private static String goJsonErrorText(byte[] body) {
            String text = new String(body, StandardCharsets.UTF_8);
            int i = 0;
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= text.length()) {
                return "unexpected end of JSON input";
            }
            char c = text.charAt(i);
            switch (c) {
                case 'n':
                case 't':
                case 'f': {
                    String literal = c == 'n' ? "null" : c == 't' ? "true" : "false";
                    for (int k = 1; k < literal.length(); k++) {
                        int idx = i + k;
                        char expected = literal.charAt(k);
                        if (idx >= text.length()) {
                            return "unexpected end of JSON input";
                        }
                        char got = text.charAt(idx);
                        if (got != expected) {
                            return "invalid character '" + got + "' in literal " + literal
                                    + " (expecting '" + expected + "')";
                        }
                    }
                    int after = i + literal.length();
                    if (after < text.length()) {
                        return "invalid character '" + text.charAt(after)
                                + "' after top-level value";
                    }
                    return "unexpected end of JSON input";
                }
                case '{':
                case '[':
                case '"':
                case '-':
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    // 深结构错误：不可复刻，回落占位（备案）
                    return "unparsable JSON body";
                default:
                    return "invalid character '" + c + "' looking for beginning of value";
            }
        }

        static String goOpenAiError(int statusCode, byte[] body) {
            String statusLine = statusCode + " " + reasonPhrase(statusCode);
            String errText = null;
            String message = null;
            boolean apiError = false;
            if (body.length > 0) {
                try {
                    JsonNode root = MAPPER.readTree(body);
                    JsonNode err = root == null ? null : root.get("error");
                    if (root != null && err != null && err.isObject()) {
                        JsonNode msg = err.get("message");
                        if (msg != null && !msg.isNull()) {
                            if (msg.isTextual()) {
                                message = msg.asText();
                                apiError = true;
                            } else if (msg.isArray()) {
                                List<String> parts = new ArrayList<>();
                                msg.forEach(m -> parts.add(m.asText()));
                                message = String.join(", ", parts);
                                apiError = true;
                            }
                        }
                    }
                } catch (IOException e) {
                    errText = goJsonErrorText(body);
                }
            }
            if (apiError) {
                return "error, status code: " + statusCode + ", status: " + statusLine
                        + ", message: " + message;
            }
            if (errText != null) {
                return "error, status code: " + statusCode + ", status: " + statusLine
                        + ", message: " + errText + ", body: " + new String(body, StandardCharsets.UTF_8);
            }
            return "error, status code: " + statusCode + ", status: " + statusLine
                    + ", message: %!s(<nil>), body: " + new String(body, StandardCharsets.UTF_8);
        }

        /** 对照 Go http.StatusText（go-openai 的 status: 段直接用 resp.Status）。 */
        static String reasonPhrase(int statusCode) {
            return switch (statusCode) {
                case 100 -> "Continue";
                case 101 -> "Switching Protocols";
                case 200 -> "OK";
                case 201 -> "Created";
                case 202 -> "Accepted";
                case 204 -> "No Content";
                case 301 -> "Moved Permanently";
                case 302 -> "Found";
                case 303 -> "See Other";
                case 304 -> "Not Modified";
                case 307 -> "Temporary Redirect";
                case 308 -> "Permanent Redirect";
                case 400 -> "Bad Request";
                case 401 -> "Unauthorized";
                case 402 -> "Payment Required";
                case 403 -> "Forbidden";
                case 404 -> "Not Found";
                case 405 -> "Method Not Allowed";
                case 406 -> "Not Acceptable";
                case 408 -> "Request Timeout";
                case 409 -> "Conflict";
                case 410 -> "Gone";
                case 413 -> "Request Entity Too Large";
                case 415 -> "Unsupported Media Type";
                case 422 -> "Unprocessable Entity";
                case 429 -> "Too Many Requests";
                case 500 -> "Internal Server Error";
                case 501 -> "Not Implemented";
                case 502 -> "Bad Gateway";
                case 503 -> "Service Unavailable";
                case 504 -> "Gateway Timeout";
                default -> "";
            };
        }
    }
}
