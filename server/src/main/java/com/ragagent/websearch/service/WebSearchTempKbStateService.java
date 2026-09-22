package com.ragagent.websearch.service;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
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
 * web 搜索临时 KB 的 Redis 状态（对照 Go internal/application/service/web_search_state.go
 * 全文：Get/Save/Delete 三方法，键 {@code tempkb:<sessionID>}，JSON 形态
 * {@code {"kbID":…,"knowledgeIDs":[…],"seenURLs":{…}}}——与 Go 双端可互读）。
 *
 * <h2>调用面（对照 Go 现状）</h2>
 * <p>Go 的 chat_pipeline/search.go 里 Get/Save 两个调用点<b>当前被注释掉</b>（状态压缩
 * 路径休眠），真正会调的只有会话删除的 {@code DeleteWebSearchTempKBState}——因此
 * {@link com.ragagent.chatpipeline.PipelinePorts.WebSearchStateService} 端口保持
 * 存而不读，本服务只被 {@code SessionService} 的清理三件套消费。</p>
 *
 * <h2>Delete 语义（对照 DeleteWebSearchTempKBState L89-136，错误被调用方吞掉）</h2>
 * <ol>
 *   <li>键不存在 → 无事可做；</li>
 *   <li>JSON 损坏 → 只删键；</li>
 *   <li>kbID 空白 → 只删键；</li>
 *   <li>否则逐个删知识条目（失败逐条 warn 继续）→ 删临时 KB（失败 warn 继续）→
 *       删 Redis 键（失败才上抛，Go 形态 {@code failed to delete Redis key: %w}）。</li>
 * </ol>
 */
@Service
public class WebSearchTempKbStateService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTempKbStateService.class);

    /** 对照 Go 的 stateKey：fmt.Sprintf("tempkb:%s", sessionID)。 */
    private static final String STATE_KEY_PREFIX = "tempkb:";

    /**
     * 对照 Go 的匿名 struct（字段序 kbID → knowledgeIDs → seenURLs）。
     * record 分量序 = 序列化序，与 Go marshal 的键序一致。
     */
    public record TempKbState(
            @JsonProperty("kbID") String kbId,
            @JsonProperty("knowledgeIDs") List<String> knowledgeIds,
            @JsonProperty("seenURLs") Map<String, Boolean> seenUrls) {
    }

    /** 对照 encoding/json：忽略未知字段（Go 默认语义）。 */
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

    /** 对照 GetWebSearchTempKBState：无状态/损坏一律回空三元组（""/空 map/空列表）。 */
    public TempKbState getTempKbState(String sessionId) {
        String stateKey = STATE_KEY_PREFIX + sessionId;
        try {
            String raw = redis.opsForValue().get(stateKey);
            if (raw != null && !raw.isEmpty()) {
                TempKbState state = MAPPER.readValue(raw, TempKbState.class);
                if (state != null) {
                    return new TempKbState(
                            state.kbId() == null ? "" : state.kbId(),
                            state.knowledgeIds() == null ? List.of() : state.knowledgeIds(),
                            state.seenUrls() == null ? Map.of() : state.seenUrls());
                }
            }
        } catch (RuntimeException | JsonProcessingException e) {
            // Go：getErr 或 unmarshal err 都走空三元组返回
        }
        return new TempKbState("", List.of(), Map.of());
    }

    /** 对照 SaveWebSearchTempKBState：marshal 失败静默丢弃（Go 的 {@code _ =}）。 */
    public void saveTempKbState(String sessionId, String tempKbId,
            Map<String, Boolean> seenUrls, List<String> knowledgeIds) {
        String stateKey = STATE_KEY_PREFIX + sessionId;
        try {
            byte[] json = MAPPER.writeValueAsBytes(
                    new TempKbState(tempKbId, knowledgeIds, seenUrls));
            redis.opsForValue().set(stateKey, new String(json, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException | JsonProcessingException e) {
            // Go：marshal err == nil 才 Set，失败静默
        }
    }

    /**
     * 对照 DeleteWebSearchTempKBState（L89-136）。仅最后的 Redis 删除失败才上抛
     * （Go {@code fmt.Errorf("failed to delete Redis key: %w", delErr)}），调用方
     * （会话删除三件套）吞掉并 warn。
     */
    public void deleteTempKbState(String sessionId) {
        String stateKey = STATE_KEY_PREFIX + sessionId;
        String raw;
        try {
            raw = redis.opsForValue().get(stateKey);
        } catch (RuntimeException e) {
            // Go：getErr != nil → "No state found, nothing to clean up"
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

        // Delete all knowledge items（对照 deleteReferencedKnowledge(ctx, svc, kbID, [kid])；
        // Java 侧清理面按既有备案走 deleteKnowledge 的同步尽力而为形态）
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
