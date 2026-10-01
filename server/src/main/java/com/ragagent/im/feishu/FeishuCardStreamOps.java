package com.ragagent.im.feishu;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.im.runtime.IncomingMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书 CardKit 流式卡片协作者（对照 Go adapter.go 的流式段，
 * 自 {@link FeishuAdapter} 机械搬出）：建卡 → interactive 发出 → 严格递增 seq
 * 逐次 PUT 元素 → EndStream 关 streaming_mode 并回填摘要；markdown 图片
 * 先换 image_key（外部缓存仍在门面）。孤儿流惰性回收（STREAMS 流表留门面，
 * 测试直摸）。持门面回引取 http/token/region 等。
 */
final class FeishuCardStreamOps {

    private static final Logger log = LoggerFactory.getLogger(FeishuCardStreamOps.class);

    private final FeishuAdapter service;

    FeishuCardStreamOps(FeishuAdapter service) {
        this.service = service;
    }

    /** 流式卡片里承载内容的元素 id。 */
    static final String STREAMING_ELEMENT_ID = "streaming_content";
    static final long STREAM_ORPHAN_TTL_MS = 5 * 60 * 1000L;
    // ── 流式（CardKit v1） ──────────────────────────────────────────────────

    public String startStream(IncomingMessage incoming) throws Exception {
        purgeOrphans();
        String accessToken = service.getTenantAccessToken();

        String cardId = cardkitCreate(accessToken, buildStreamingCardJson(service.region));
        sendCardByCardId(accessToken, incoming, cardId);

        FeishuAdapter.STREAMS.put(cardId, new FeishuAdapter.StreamState());
        log.info("[{}] Streaming started: card_id={}", service.region.label(), cardId);
        return cardId;
    }

    public void updateStreamContent(IncomingMessage incoming, String streamId, String fullContent)
            throws Exception {
        if (fullContent == null || fullContent.isEmpty()) {
            return;
        }
        FeishuAdapter.StreamState state = FeishuAdapter.STREAMS.get(streamId);
        if (state == null) {
            throw new IllegalStateException("unknown stream ID: " + streamId);
        }
        int seq = state.nextSeq();
        String accessToken = service.getTenantAccessToken();

        // 卡片 markdown 只接受上传后的 image_key（外链会 200570）→ 先换 image_key
        String content = service.resolveMarkdownImages(accessToken, fullContent);

        cardkitUpdateElement(accessToken, streamId, STREAMING_ELEMENT_ID, content, seq);

        synchronized (state.lock) {
            if (seq > state.contentSeq) {
                state.content.setLength(0);
                state.content.append(fullContent);
                state.contentSeq = seq;
            }
        }
    }

    public void finalizeStream(IncomingMessage incoming, String streamId, String finalContent)
            throws Exception {
        updateStreamContent(incoming, streamId, finalContent);
    }

    public void endStream(IncomingMessage incoming, String streamId) throws Exception {
        FeishuAdapter.StreamState state = FeishuAdapter.STREAMS.remove(streamId);
        String accessToken = service.getTenantAccessToken();

        int seq = 0;
        String summary = null;
        if (state != null) {
            seq = state.nextSeq();
            String preview;
            synchronized (state.lock) {
                preview = cardSummaryPreview(state.content.toString());
            }
            if (!preview.isEmpty()) {
                summary = preview;
            }
        }
        // 始终关 streaming_mode（CardKit 拒绝顶层 streaming_mode 字段）；
        // 只有真的产出过文本才回填摘要，空摘要会把"思考中"预览留在聊天列表里
        try {
            cardkitSetStreaming(accessToken, streamId, false, summary, seq);
        } catch (Exception e) {
            log.warn("[{}] Failed to disable streaming_mode: {}", service.region.label(), e.toString());
        }
        log.info("[{}] Streaming ended: card_id={}", service.region.label(), streamId);
    }

    /** 对照 Go 的 ticker 回收：惰性清掉超过 TTL 的孤儿流。 */
    static void purgeOrphans() {
        long cutoff = System.currentTimeMillis() - STREAM_ORPHAN_TTL_MS;
        FeishuAdapter.STREAMS.entrySet().removeIf(e -> e.getValue().createdAt < cutoff);
    }

    /** 对照 {@code cardSummaryPreview}：去图片/链接语法 → 折叠空白 → 截 120 字符。 */
    static String cardSummaryPreview(String content) {
        String value = content == null ? "" : content;
        value = FeishuAdapter.MD_IMAGE_RE.matcher(value).replaceAll("$1");
        value = FeishuAdapter.MD_LINK_RE.matcher(value).replaceAll("$1");
        String collapsed = String.join(" ", value.trim().split("\\s+"));
        return collapsed.length() > 120 ? collapsed.substring(0, 120) : collapsed;
    }

    /** 对照 {@code buildStreamingCardJSON}：schema 2.0 + streaming_mode + 区域占位文案。 */
    static String buildStreamingCardJson(FeishuRegion region) throws Exception {
        ObjectNode card = FeishuAdapter.MAPPER.createObjectNode();
        card.put("schema", "2.0");

        ObjectNode config = FeishuAdapter.MAPPER.createObjectNode();
        config.put("streaming_mode", true);
        config.set("summary", FeishuAdapter.MAPPER.createObjectNode().put("content", region.thinkingText()));
        card.set("config", config);

        ObjectNode header = FeishuAdapter.MAPPER.createObjectNode();
        header.put("template", "blue");
        ObjectNode title = FeishuAdapter.MAPPER.createObjectNode();
        title.put("tag", "plain_text");
        title.put("content", "WeKnora");
        header.set("title", title);
        card.set("header", header);

        ObjectNode element = FeishuAdapter.MAPPER.createObjectNode();
        element.put("tag", "markdown");
        element.put("content", "💭 " + region.thinkingText());
        element.put("text_size", "normal");
        element.put("element_id", STREAMING_ELEMENT_ID);
        ArrayNode elements = FeishuAdapter.MAPPER.createArrayNode();
        elements.add(element);
        card.set("body", FeishuAdapter.MAPPER.createObjectNode().set("elements", elements));

        return FeishuAdapter.MAPPER.writeValueAsString(card);
    }

    /** 对照 {@code cardkitCreate}：POST cardkit/v1/cards（type=card_json）→ card_id。 */
    private String cardkitCreate(String accessToken, String cardJson) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "card_json");
        payload.put("data", cardJson);

        HttpRequest request = HttpRequest.newBuilder(URI.create(service.api("/open-apis/cardkit/v1/cards")))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(FeishuAdapter.MAPPER.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = service.http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        JsonNode result = FeishuAdapter.readTree(response.body());
        int code = result.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException("code=" + code + " msg=" + result.path("msg").asText(""));
        }
        String cardId = result.path("data").path("card_id").asText("");
        if (cardId.isEmpty()) {
            throw new IllegalStateException("parse card_id: empty (raw: " + response.body().length
                    + " bytes)");
        }
        return cardId;
    }

    /** 对照 {@code sendCardByCardID}：interactive 消息（content.type=card）+ 回落。 */
    private void sendCardByCardId(String accessToken, IncomingMessage incoming, String cardId)
            throws Exception {
        String[] receive = FeishuAdapter.resolveReceiveId(incoming);
        ObjectNode content = FeishuAdapter.MAPPER.createObjectNode();
        content.put("type", "card");
        content.set("data", FeishuAdapter.MAPPER.createObjectNode().put("card_id", cardId));
        String contentJson = FeishuAdapter.MAPPER.writeValueAsString(content);

        Map<String, Object> replyPayload = new LinkedHashMap<>();
        replyPayload.put("msg_type", "interactive");
        replyPayload.put("content", contentJson);

        Map<String, Object> fallbackPayload = new LinkedHashMap<>();
        fallbackPayload.put("receive_id", receive[1]);
        fallbackPayload.put("msg_type", "interactive");
        fallbackPayload.put("content", contentJson);

        service.sendWithFallback(accessToken, incoming, replyPayload, fallbackPayload, receive[0]);
    }

    /** 对照 {@code cardkitUpdateElement}：PUT 元素内容（带 sequence）。 */
    private void cardkitUpdateElement(String accessToken, String cardId, String elementId,
                                      String content, int sequence) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("sequence", sequence);

        String url = service.api("/open-apis/cardkit/v1/cards/" + cardId + "/elements/" + elementId
                + "/content");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofString(FeishuAdapter.MAPPER.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = service.http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        JsonNode result = FeishuAdapter.readTree(response.body());
        int code = result.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException("update element error: code=" + code + " msg="
                    + result.path("msg").asText(""));
        }
    }

    /** 对照 {@code cardkitSetStreaming}：PATCH settings（config.streaming_mode + 可选 summary）。 */
    private void cardkitSetStreaming(String accessToken, String cardId, boolean streaming,
                                     String finalSummary, int sequence) throws Exception {
        ObjectNode config = FeishuAdapter.MAPPER.createObjectNode();
        config.put("streaming_mode", streaming);
        if (finalSummary != null && !finalSummary.trim().isEmpty()) {
            config.set("summary", FeishuAdapter.MAPPER.createObjectNode().put("content", finalSummary));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settings", FeishuAdapter.MAPPER.writeValueAsString(Map.of("config", config)));
        payload.put("sequence", sequence);

        String url = service.api("/open-apis/cardkit/v1/cards/" + cardId + "/settings");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        FeishuAdapter.MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = service.http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        JsonNode result = FeishuAdapter.readTree(response.body());
        int code = result.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException("set streaming error: code=" + code + " msg="
                    + result.path("msg").asText(""));
        }
    }
}
