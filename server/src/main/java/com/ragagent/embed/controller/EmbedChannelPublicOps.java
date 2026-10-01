package com.ragagent.embed.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ragagent.common.security.LogSanitizer;
import com.ragagent.embed.EmbedTokens;
import com.ragagent.embed.EmbedError;
import com.ragagent.embed.domain.EmbedChannelEntity;
import com.ragagent.embed.service.EmbedChannelService;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionOwnerIds;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * embed 公开面协作者（对照 Go embedChannelHandler 公开段，
 * 自 {@link EmbedChannelController} 机械搬出）：session token 兑换、公开配置、
 * 推荐问题、chunk 读取与访客会话创建。EmbedAuthFilter 已在过滤器层跑完。
 * 持门面回引（ctrl）取 service/sessionService/sessionRepository；渠道解析与
 * 响应组构件经门面类名调用。
 */
final class EmbedChannelPublicOps {

    private final EmbedChannelController ctrl;

    EmbedChannelPublicOps(EmbedChannelController ctrl) {
        this.ctrl = ctrl;
    }

    // ═══════════════════ 公开面（EmbedAuthFilter 已跑） ═══════════════════

    /** 对照 ExchangeEmbedSession：只有 publish token 能换 session token。 */
    public ResponseEntity<Map<String, Object>> exchange(@PathVariable("channel_id") String channelId) {
        EmbedChannelEntity ch = EmbedChannelController.channel(EmbedChannelController.request0());
        String auth = EmbedChannelController.trim(EmbedChannelController.request0().getHeader("Authorization"));
        boolean publishToken = auth.startsWith("Embed ")
                && !EmbedTokens.isSessionToken(auth.substring("Embed ".length()));
        if (!publishToken) {
            return EmbedChannelController.plainError(403, "publish token required");
        }
        EmbedChannelService.IssueResult result;
        try {
            result = ctrl.service.issueSessionToken(ch.getId());
        } catch (EmbedError e) {
            if (e.kind == EmbedError.Kind.SESSION_UNAVAILABLE) {
                return EmbedChannelController.plainError(503, "session tokens unavailable");
            }
            return EmbedChannelController.plainError(500, "failed to issue session token");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("expires_in", result.expiresIn());
        data.put("session_token", result.token());
        return ResponseEntity.ok(EmbedChannelController.dataEnvelope(data));
    }

    /** 对照 GetEmbedConfig。 */
    public ResponseEntity<Map<String, Object>> config(@PathVariable("channel_id") String channelId) {
        EmbedChannelEntity ch = EmbedChannelController.channel(EmbedChannelController.request0());
        return ResponseEntity.ok(EmbedChannelController.dataEnvelope(ctrl.service.publicConfig(ch)));
    }

    /** 对照 GetEmbedSuggestedQuestions。 */
    public ResponseEntity<Map<String, Object>> suggestedQuestions(
            @PathVariable("channel_id") String channelId,
            @RequestParam(name = "limit", required = false) String limit) {
        EmbedChannelEntity ch = EmbedChannelController.channel(EmbedChannelController.request0());
        if (!ch.isShowSuggestedQuestions()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("questions", new ArrayList<>());
            return ResponseEntity.ok(EmbedChannelController.dataEnvelope(data));
        }
        int limitInt = 0;
        if (limit != null && !limit.isEmpty()) {
            try {
                int n = Integer.parseInt(limit);
                if (n > 0) {
                    limitInt = Math.min(n, 12);
                }
            } catch (NumberFormatException ignored) {
                // Go：解析失败按"未指定"处理
            }
        }
        com.fasterxml.jackson.databind.node.ArrayNode questions;
        try {
            questions = ctrl.service.suggestedQuestions(ch, limitInt);
        } catch (RuntimeException e) {
            return EmbedChannelController.plainError(500, "failed to load suggested questions");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("questions", questions == null ? new ArrayList<>() : questions);
        return ResponseEntity.ok(EmbedChannelController.dataEnvelope(data));
    }

    /** 对照 GetEmbedChunk。 */
    public ResponseEntity<Map<String, Object>> chunk(@PathVariable("chunk_id") String chunkId) {
        EmbedChannelEntity ch = EmbedChannelController.channel(EmbedChannelController.request0());
        String cid = LogSanitizer.sanitize(chunkId);
        if (cid.isEmpty()) {
            return EmbedChannelController.plainError(400, "chunk_id is required");
        }
        try {
            Chunk chunk = ctrl.service.embedChunk(ch, cid);
            return ResponseEntity.ok(EmbedChannelController.dataEnvelope(chunk));
        } catch (EmbedChannelService.ChunkNotFoundError e) {
            return EmbedChannelController.plainError(404, "chunk not found");
        } catch (EmbedChannelService.ChunkForbiddenError e) {
            return EmbedChannelController.plainError(403, "chunk not accessible");
        }
    }

    /** 对照 CreateEmbedSession：201 {id, sig}。 */
    public ResponseEntity<Map<String, Object>> createSession(
            @PathVariable("channel_id") String channelId) {
        EmbedChannelEntity ch = EmbedChannelController.channel(EmbedChannelController.request0());
        long tenantId = EmbedChannelController.currentTenant();
        Session created;
        try {
            created = ctrl.sessionService.createSession(
                    EmbedChannelService.newEmbedSession(tenantId, ch.getId()));
        } catch (RuntimeException e) {
            return EmbedChannelController.plainError(500, "failed to create session");
        }
        String owner = SessionOwnerIds.EMBED_SESSION_PREFIX + tenantId + ":" + ch.getId()
                + ":" + created.getId();
        try {
            ctrl.sessionRepository.setOwnerId(tenantId, created.getId(), owner);
            created.setUserId(owner);
        } catch (RuntimeException e) {
            // 对照 Go：Warnf 后继续
        }
        String sig = EmbedTokens.signHandle(ch, created.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", created.getId());
        data.put("sig", sig);
        return ResponseEntity.status(201).body(EmbedChannelController.dataEnvelope(data));
    }
}
