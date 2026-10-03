package com.ragagent.im.mattermost;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImAdapterVerify;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;

/**
 * Mattermost 适配器（outgoing webhook 入站 + REST 出站）。
 *
 * <h2>行为要点</h2>
 * <ul>
 *   <li>入站体双态：{@code application/json}（含 {@code +json} 后缀）直接解 JSON；
 *       {@code application/x-www-form-urlencoded} <b>或空 Content-Type</b> 走表单；
 *       其他类型先试 JSON，只有 token 或 channel_id 非空才认，否则
 *       {@code unsupported content-type: X}；</li>
 *   <li>验签 = outgoing token 精确相等（走共享 {@link ImAdapterVerify#mattermostTokenMatches}；
 *       未配 token 则跳过）；</li>
 *   <li>自环防护：{@code user_id == bot_user_id} 直接丢；文本空且无 file_ids 也丢；</li>
 *   <li>线程根：{@code post_to_main} → 空（顶层帖）；否则取 {@code root_id}，
 *       缺省用 {@code GET /posts/{id}} 查真根，查不到就用自身 {@code post_id}
 *       （即"顶层消息自成根"）——两处都落进 extra 的 {@code thread_root_id}；</li>
 *   <li>带 file_ids → 文件消息（取第一个；多于一个时 extra 放逗号连接的 {@code file_ids}）；</li>
 *   <li>发送：{@code channel_id} 取 ChatID 回落 extra；缺失报 {@code missing channel_id}；
 *       走 {@code POST /posts}（带 {@code root_id} 即线程内回复）；</li>
 *   <li>流式：StartStream 先发 "正在思考..." 的帖，stream ID = {@code <channel>:<post_id>}；
 *       更新走 {@code PUT /posts/{id}/patch}（失败只告警），EndStream 用累积内容再 patch 一次；
 *       <b>流表无回收</b>（条目就地删，无 TTL 清扫）；</li>
 *   <li>下载：先 {@code GET /files/{id}/info} 拿名字，名字三级回落
 *       （info.name → msg.fileName → fileKey），再取内容。</li>
 * </ul>
 */
public class MattermostAdapter implements AdapterInterfaces.Adapter,
        AdapterInterfaces.StreamSender, AdapterInterfaces.FileDownloader {

    private static final Logger log = LoggerFactory.getLogger(MattermostAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String EXTRA_THREAD_ROOT = "thread_root_id";
    static final String EXTRA_CHANNEL_ID = "channel_id";

    /** 流表。 */
    static final Map<String, StreamState> STREAMS = new ConcurrentHashMap<>();

    /** 一条流的状态。 */
    static final class StreamState {
        final Object lock = new Object();
        final StringBuilder content = new StringBuilder();
        final String postId;
        final String channel;

        StreamState(String postId, String channel) {
            this.postId = postId;
            this.channel = channel;
        }
    }

    /** outgoing webhook 载荷。 */
    record OutgoingPayload(String token, String userId, String userName, String channelId,
                           String postId, String text, String rootId, JsonNode fileIdsRaw) {
    }

    private final MattermostClient client;
    private final String outgoingToken;
    private final String botUserId;
    private final boolean postReplyToMain;

    public MattermostAdapter(MattermostClient client, String outgoingToken, String botUserId,
                             boolean postReplyToMain) {
        this.client = client;
        this.outgoingToken = outgoingToken == null ? "" : outgoingToken.trim();
        this.botUserId = botUserId == null ? "" : botUserId.trim();
        this.postReplyToMain = postReplyToMain;
    }

    @Override
    public String platform() {
        return ImTypes.PLATFORM_MATTERMOST;
    }

    /** 不走 URL 挑战。 */
    @Override
    public boolean handleURLVerification(CallbackExchange exchange) {
        return false;
    }

    /** token 精确相等（未配则跳过）。 */
    @Override
    public Exception verifyCallback(CallbackExchange exchange) {
        OutgoingPayload payload;
        try {
            payload = parseOutgoingBody(exchange.header("Content-Type"), exchange.body());
        } catch (RuntimeException e) {
            return new AdapterInterfaces.VerifyException("parse outgoing payload: "
                    + e.getMessage());
        }
        if (!outgoingToken.isEmpty()
                && !ImAdapterVerify.mattermostTokenMatches(payload.token(), outgoingToken)) {
            return new AdapterInterfaces.VerifyException("invalid outgoing webhook token");
        }
        return null;
    }

    /** 自环/空消息丢弃；线程根三级回落。 */
    @Override
    public IncomingMessage parseCallback(CallbackExchange exchange) {
        OutgoingPayload payload = parseOutgoingBody(exchange.header("Content-Type"),
                exchange.body());

        if (!botUserId.isEmpty() && botUserId.equals(payload.userId())) {
            log.info("[Mattermost] Skip callback: user_id matches bot_user_id"
                    + " (avoid self-reply loop)");
            return null;
        }
        List<String> fileIds = parseFileIds(payload.fileIdsRaw());
        String text = payload.text() == null ? "" : payload.text().trim();
        if (text.isEmpty() && fileIds.isEmpty()) {
            log.info("[Mattermost] Skip callback: empty text and no file_ids");
            return null;
        }

        String threadRoot;
        if (postReplyToMain) {
            threadRoot = "";
        } else {
            threadRoot = payload.rootId() == null ? "" : payload.rootId();
            if (threadRoot.isEmpty()) {
                // outgoing webhook 可能不给线程回复的 root_id → 查真根，查不到就用自身
                String actualRoot = "";
                try {
                    actualRoot = client.getPostRootId(payload.postId());
                } catch (Exception e) {
                    log.debug("[Mattermost] get post failed: {}", e.toString());
                }
                threadRoot = actualRoot.isEmpty() ? payload.postId() : actualRoot;
            }
        }

        IncomingMessage msg = new IncomingMessage();
        msg.platform = ImTypes.PLATFORM_MATTERMOST;
        msg.userId = payload.userId();
        msg.userName = payload.userName() == null ? "" : payload.userName();
        msg.chatId = payload.channelId() == null ? "" : payload.channelId();
        msg.chatType = ImTypes.CHAT_TYPE_GROUP;
        msg.content = text;
        msg.messageId = payload.postId();
        msg.threadId = threadRoot;
        msg.extra.put(EXTRA_THREAD_ROOT, threadRoot);
        msg.extra.put(EXTRA_CHANNEL_ID, msg.chatId);

        if (!fileIds.isEmpty()) {
            msg.messageType = ImTypes.MESSAGE_TYPE_FILE;
            msg.fileKey = fileIds.get(0);
            if (fileIds.size() > 1) {
                msg.extra.put("file_ids", String.join(",", fileIds));
            }
        } else {
            msg.messageType = ImTypes.MESSAGE_TYPE_TEXT;
        }
        return msg;
    }

    /** JSON / 表单 / 兜底三支。 */
    static OutgoingPayload parseOutgoingBody(String contentType, byte[] body) {
        String raw = contentType == null ? "" : contentType.trim();
        String ct = (raw.contains(";") ? raw.substring(0, raw.indexOf(';')) : raw)
                .trim().toLowerCase(java.util.Locale.ROOT);
        byte[] bytes = body == null ? new byte[0] : body;

        if (ct.equals("application/json") || ct.endsWith("+json")) {
            return jsonPayload(bytes);
        }
        if (ct.equals("application/x-www-form-urlencoded") || ct.isEmpty()) {
            return formPayload(bytes);
        }
        // 未知类型：先试 JSON，token/channel_id 有一个非空才认
        OutgoingPayload parsed;
        try {
            parsed = jsonPayload(bytes);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("unsupported content-type: " + raw);
        }
        if (!parsed.token().isEmpty() || !parsed.channelId().isEmpty()) {
            return parsed;
        }
        throw new IllegalArgumentException("unsupported content-type: " + raw);
    }

    private static OutgoingPayload jsonPayload(byte[] body) {
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("invalid json payload");
        }
        return new OutgoingPayload(
                node.path("token").asText(""),
                node.path("user_id").asText(""),
                node.path("user_name").asText(""),
                node.path("channel_id").asText(""),
                node.path("post_id").asText(""),
                node.path("text").asText(""),
                node.path("root_id").asText(""),
                node.has("file_ids") ? node.get("file_ids") : null);
    }

    private static OutgoingPayload formPayload(byte[] body) {
        Map<String, String> values = new LinkedHashMap<>();
        String text = new String(body, StandardCharsets.UTF_8);
        for (String pair : text.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        JsonNode fileIdsRaw = null;
        String csv = values.getOrDefault("file_ids", "");
        if (!csv.isEmpty()) {
            ArrayNode array = MAPPER.createArrayNode();
            splitFileIds(csv).forEach(array::add);
            fileIdsRaw = array;
        }
        return new OutgoingPayload(
                values.getOrDefault("token", ""),
                values.getOrDefault("user_id", ""),
                values.getOrDefault("user_name", ""),
                values.getOrDefault("channel_id", ""),
                values.getOrDefault("post_id", ""),
                values.getOrDefault("text", ""),
                values.getOrDefault("root_id", ""),
                fileIdsRaw);
    }

    /** JSON 数组或逗号串两形态。 */
    static List<String> parseFileIds(JsonNode raw) {
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            return List.of();
        }
        if (raw.isArray()) {
            List<String> out = new ArrayList<>();
            for (JsonNode item : raw) {
                String value = item.asText("");
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
            return out;
        }
        String text = raw.asText("");
        return text.isEmpty() ? List.of() : splitFileIds(text);
    }

    /** 逗号切分 + 去空白。 */
    static List<String> splitFileIds(String csv) {
        List<String> out = new ArrayList<>();
        for (String part : (csv == null ? "" : csv).split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    // ── 发送 ────────────────────────────────────────────────────────────────

    @Override
    public void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception {
        String channelId = incoming.chatId == null || incoming.chatId.isEmpty()
                ? extraOf(incoming, EXTRA_CHANNEL_ID) : incoming.chatId;
        if (channelId.isEmpty()) {
            throw new IllegalArgumentException("missing channel_id");
        }
        client.createPost(channelId, extraOf(incoming, EXTRA_THREAD_ROOT), reply.content);
    }

    private static String extraOf(IncomingMessage incoming, String key) {
        if (incoming == null || incoming.extra == null) {
            return "";
        }
        return incoming.extra.getOrDefault(key, "");
    }

    @Override
    public String startStream(IncomingMessage incoming) throws Exception {
        String channelId = incoming.chatId == null || incoming.chatId.isEmpty()
                ? extraOf(incoming, EXTRA_CHANNEL_ID) : incoming.chatId;
        String threadRoot = extraOf(incoming, EXTRA_THREAD_ROOT);

        String postId = client.createPost(channelId, threadRoot, "正在思考...");
        String streamId = channelId + ":" + postId;
        STREAMS.put(streamId, new StreamState(postId, channelId));
        log.info("[Mattermost] Streaming started: stream_id={}", streamId);
        return streamId;
    }

    @Override
    public void updateStreamContent(IncomingMessage incoming, String streamId, String fullContent)
            throws Exception {
        if (fullContent == null || fullContent.isEmpty()) {
            return;
        }
        StreamState state = STREAMS.get(streamId);
        if (state == null) {
            throw new IllegalStateException("unknown stream ID: " + streamId);
        }
        synchronized (state.lock) {
            state.content.setLength(0);
            state.content.append(fullContent);
        }
        try {
            client.patchPostMessage(state.postId, fullContent);
        } catch (Exception e) {
            log.warn("[Mattermost] Patch post failed: {}", e.toString());
        }
    }

    @Override
    public void finalizeStream(IncomingMessage incoming, String streamId, String finalContent)
            throws Exception {
        updateStreamContent(incoming, streamId, finalContent);
    }

    @Override
    public void endStream(IncomingMessage incoming, String streamId) throws Exception {
        StreamState state = STREAMS.remove(streamId);
        if (state == null) {
            return;
        }
        String full;
        synchronized (state.lock) {
            full = state.content.toString();
        }
        try {
            client.patchPostMessage(state.postId, full);
        } catch (Exception e) {
            log.warn("[Mattermost] EndStream patch failed: {}", e.toString());
        }
        log.info("[Mattermost] Streaming ended: post_id={}", state.postId);
    }

    // ── 下载 ────────────────────────────────────────────────────────────────

    @Override
    public DownloadedFile downloadFile(IncomingMessage msg) throws Exception {
        if (msg.fileKey == null || msg.fileKey.isEmpty()) {
            throw new IllegalArgumentException("file_key is required");
        }
        MattermostClient.FileInfo info;
        try {
            info = client.getFileInfo(msg.fileKey);
        } catch (Exception e) {
            throw new IllegalStateException("file info: " + e.getMessage(), e);
        }
        String name = info.name() == null ? "" : info.name();
        if (name.isEmpty()) {
            name = msg.fileName == null ? "" : msg.fileName;
        }
        if (name.isEmpty()) {
            name = msg.fileKey;
        }
        return new DownloadedFile(client.getFileBytes(msg.fileKey), name);
    }
}
