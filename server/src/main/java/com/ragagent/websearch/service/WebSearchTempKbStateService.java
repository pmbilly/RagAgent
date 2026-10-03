package com.ragagent.websearch.service;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeService;

/**
 * web 搜索临时 KB 的 Redis 状态（Get/Save/Delete 三方法，键
 * {@code tempkb:<sessionID>}，JSON 形态
 * {@code {"kbID":…,"knowledgeIDs":[…],"seenURLs":{…}}}）。
 *
 * <h2>调用面</h2>
 * <p>状态压缩路径休眠：get/save 当前无调用方，真正消费的只有会话删除清理——
 * 因此 {@link com.ragagent.chatpipeline.PipelinePorts.WebSearchStateService} 端口保持
 * 存而不读，本服务只被 {@code SessionService} 的清理三件套调用。</p>
 *
 * <h2>Delete 语义（错误被调用方吞掉）</h2>
 * <ol>
 *   <li>键不存在 → 无事可做；</li>
 *   <li>JSON 损坏 → 只删键；</li>
 *   <li>kbID 空白 → 只删键；</li>
 *   <li>否则逐个删知识条目（失败逐条 warn 继续）→ 删临时 KB（失败 warn 继续）→
 *       删 Redis 键（失败才上抛，文案 {@code failed to delete Redis key: ...}）。</li>
 * </ol>
 */
@Service
public class WebSearchTempKbStateService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTempKbStateService.class);

    /** Redis 键前缀，完整键 = tempkb:&lt;sessionID&gt;。 */
    private static final String STATE_KEY_PREFIX = "tempkb:";

    /**
     * web 搜索临时 KB 的 Redis 状态载荷；键名 = 字段名（camelCase）。
     * Redis 是跨进程 + TTL 窗口（同 mcp OAuthState）：部署窗口内读到旧键
     * {@code kbID/knowledgeIDs/seenURLs} 时按已知映射改名后再反序列化（{@link
     * #migrateLegacyKeys}，窗口过后连同该方法删除）。
     */
    public record TempKbState(
            String kbId,
            List<String> knowledgeIds,
            Map<String, Boolean> seenUrls) {
    }

    /** 忽略未知字段（容忍键演进）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final StringRedisTemplate redis;
    private final KnowledgeService knowledgeService;
    private final KnowledgeBaseService knowledgeBaseService;

    public WebSearchTempKbStateService(StringRedisTemplate redis,
            KnowledgeService knowledgeService,
            KnowledgeBaseService knowledgeBaseService) {
        this.redis = redis;
        this.knowledgeService = knowledgeService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /** 旧 blob 的键名 → 新键名（仅部署窗口用；窗口过后连同本方法删除）。 */
    static String migrateLegacyKeys(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(json);
            if (root instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
                var moved = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
                for (String[] pair : new String[][]{{"kbID", "kbId"}, {"knowledgeIDs", "knowledgeIds"},
                        {"seenURLs", "seenUrls"}}) {
                    var v = obj.remove(pair[0]);
                    if (v != null && !obj.has(pair[1])) {
                        moved.put(pair[1], v);
                    }
                }
                moved.forEach(obj::set);
                return MAPPER.writeValueAsString(obj);
            }
            return json;
        } catch (Exception e) {
            return json; // 损坏载荷走既有的"回空三元组"分支
        }
    }

    /** 无状态/损坏一律回空三元组（""/空 map/空列表）。 */
    public TempKbState getTempKbState(String sessionId) {
        String stateKey = STATE_KEY_PREFIX + sessionId;
        try {
            String raw = redis.opsForValue().get(stateKey);
            if (raw != null && !raw.isEmpty()) {
                TempKbState state = MAPPER.readValue(migrateLegacyKeys(raw), TempKbState.class);
                if (state != null) {
                    return new TempKbState(
                            state.kbId() == null ? "" : state.kbId(),
                            state.knowledgeIds() == null ? List.of() : state.knowledgeIds(),
                            state.seenUrls() == null ? Map.of() : state.seenUrls());
                }
            }
        } catch (RuntimeException | JsonProcessingException e) {
            // 读取或反序列化失败都返回空三元组
        }
        return new TempKbState("", List.of(), Map.of());
    }

    /** 保存：序列化失败静默丢弃。 */
    public void saveTempKbState(String sessionId, String tempKbId,
            Map<String, Boolean> seenUrls, List<String> knowledgeIds) {
        String stateKey = STATE_KEY_PREFIX + sessionId;
        try {
            byte[] json = MAPPER.writeValueAsBytes(
                    new TempKbState(tempKbId, knowledgeIds, seenUrls));
            redis.opsForValue().set(stateKey, new String(json, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException | JsonProcessingException e) {
            // 序列化失败静默丢弃
        }
    }

    /**
     * 删除临时 KB 状态。仅最后的 Redis 删除失败才上抛
     * （文案 {@code failed to delete Redis key: ...}），调用方
     * （会话删除三件套）吞掉并 warn。
     */
    public void deleteTempKbState(String sessionId) {
        String stateKey = STATE_KEY_PREFIX + sessionId;
        String raw;
        try {
            raw = redis.opsForValue().get(stateKey);
        } catch (RuntimeException e) {
            // Redis 读取失败 → 视同无状态，无需清理
            return;
        }
        if (raw == null || raw.isEmpty()) {
            return;
        }
        TempKbState state;
        try {
            state = MAPPER.readValue(raw, TempKbState.class);
        } catch (JsonProcessingException e) {
            // Invalid state, just delete the key
            redis.delete(stateKey);
            return;
        }
        String kbId = state == null || state.kbId() == null ? "" : state.kbId().trim();
        if (kbId.isEmpty()) {
            // If KBID is empty, just delete the Redis key
            redis.delete(stateKey);
            return;
        }

        log.info("Cleaning temporary KB for session {}: {}", sessionId, kbId);

        // 删除全部知识条目（清理面按既有备案走 deleteKnowledge 的同步尽力而为形态）
        List<String> knowledgeIds = state.knowledgeIds() == null ? List.of() : state.knowledgeIds();
        for (String kid : knowledgeIds) {
            try {
                knowledgeService.deleteKnowledge(kid);
            } catch (RuntimeException e) {
                log.warn("Failed to delete temp knowledge {}: {}", kid, e.toString());
            }
        }

        // Delete the knowledge base
        try {
            knowledgeBaseService.deleteKnowledgeBase(kbId);
        } catch (RuntimeException e) {
            log.warn("Failed to delete temp knowledge base {}: {}", kbId, e.toString());
        }

        // Delete the Redis key（唯一上抛点）
        try {
            redis.delete(stateKey);
        } catch (RuntimeException e) {
            log.warn("Failed to delete Redis key {}: {}", stateKey, e.toString());
            throw new IllegalStateException("failed to delete Redis key: " + e.getMessage(), e);
        }

        log.info("Successfully cleaned up temporary KB for session {}", sessionId);
    }
}
