package com.ragagent.embed.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.common.security.LogSanitizer;
import com.ragagent.embed.domain.EmbedChannelEntity;
import com.ragagent.embed.service.EmbedChannelService;
import com.ragagent.embed.service.EmbedChannelService.UpdateCommand;
import com.ragagent.embed.EmbedError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * embed 渠道管理面协作者（对照 Go embedChannelHandler 管理段，
 * 自 {@link EmbedChannelController} 机械搬出）：create/list/get/update/delete、
 * rotate-token/preview-session/stats。错误经 {@code writeMgmtError} 统一形态。
 * 持门面回引（ctrl）取 EmbedChannelService 与 sessionRepository；绑定与响应
 * 组构件经门面类名调用。
 */
final class EmbedChannelMgmtOps {

    private final EmbedChannelController ctrl;

    EmbedChannelMgmtOps(EmbedChannelController ctrl) {
        this.ctrl = ctrl;
    }

    // ═══════════════════ 管理面 ═══════════════════

    /** 对照 CreateEmbedChannel：201 + gin.H（字母序）。 */
    public ResponseEntity<Map<String, Object>> create(@PathVariable("id") String agentId,
                                                      @RequestBody(required = false) String rawBody) {
        EmbedChannelController.EmbedChannelRequest req = EmbedChannelController.bind(rawBody);
        try {
            EmbedChannelService.validateAllowedOrigins(EmbedChannelController.stringList(req.allowedOrigins()));
            if (req.launcherIcon() != null) {
                EmbedChannelService.validateLauncherIcon(req.launcherIcon().trim());
            }
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
        EmbedChannelEntity input = new EmbedChannelEntity();
        input.setName(EmbedChannelController.orEmpty(req.name()));
        input.setEnabled(Boolean.TRUE.equals(req.enabled()));
        input.setAllowedOrigins(EmbedChannelController.allowedOriginsColumn(req.allowedOrigins()));
        input.setWelcomeMessage(EmbedChannelController.orEmpty(req.welcomeMessage()));
        input.setRateLimitPerMinute(req.rateLimitPerMinute() == null ? 0 : req.rateLimitPerMinute());
        input.setRateLimitPerDay(req.rateLimitPerDay() == null ? 0 : req.rateLimitPerDay());
        input.setPrimaryColor(EmbedChannelController.orEmpty(req.primaryColor()));
        input.setPageTitle(EmbedChannelController.orEmpty(req.pageTitle()));
        input.setHeaderTitleMode(EmbedChannelController.orEmpty(req.headerTitleMode()));
        input.setShowSuggestedQuestions(!Boolean.FALSE.equals(req.showSuggestedQuestions()));
        input.setShowThinking(Boolean.TRUE.equals(req.showThinking()));
        input.setWidgetPosition(EmbedChannelController.orEmpty(req.widgetPosition()));
        input.setAllowWebSearch(Boolean.TRUE.equals(req.allowWebSearch()));
        input.setAllowFileUpload(Boolean.TRUE.equals(req.allowFileUpload()));
        input.setDefaultLocale(EmbedChannelService.normalizeDefaultLocale(EmbedChannelController.orEmpty(req.defaultLocale())));
        input.setLauncherIcon(EmbedChannelController.orEmpty(req.launcherIcon()));
        try {
            EmbedChannelEntity ch = ctrl.service.create(EmbedChannelController.currentTenant(), LogSanitizer.sanitize(agentId), input);
            return ResponseEntity.status(201).body(EmbedChannelController.dataEnvelope(EmbedChannelController.row(ch, true)));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
    }

    /** 对照 ListEmbedChannels。 */
    public ResponseEntity<Map<String, Object>> listByAgent(@PathVariable("id") String agentId) {
        try {
            List<EmbedChannelEntity> rows =
                    ctrl.service.listByAgent(EmbedChannelController.currentTenant(), LogSanitizer.sanitize(agentId));
            return ResponseEntity.ok(EmbedChannelController.dataEnvelope(EmbedChannelController.rows(rows)));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
    }

    /** 对照 ListAllEmbedChannels（跨 agent，publish token 永不出现在列表里）。 */
    public ResponseEntity<Map<String, Object>> listAll() {
        try {
            return ResponseEntity.ok(EmbedChannelController.dataEnvelope(EmbedChannelController.rows(ctrl.service.listByTenant(EmbedChannelController.currentTenant()))));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
    }

    /** 对照 GetEmbedChannel：管理详情**含** publish token。 */
    public ResponseEntity<Map<String, Object>> get(@PathVariable("channel_id") String channelId) {
        try {
            EmbedChannelEntity ch = ctrl.service.getOwnedChannel(EmbedChannelController.currentTenant(), EmbedChannelController.trim(channelId));
            return ResponseEntity.ok(EmbedChannelController.dataEnvelope(EmbedChannelController.row(ch, true)));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
    }

    /** 对照 UpdateEmbedChannel：200 + gin.H（不含 publish token）。 */
    public ResponseEntity<Map<String, Object>> update(@PathVariable("channel_id") String channelId,
                                                      @RequestBody(required = false) String rawBody) {
        EmbedChannelController.EmbedChannelRequest req = EmbedChannelController.bind(rawBody);
        try {
            if (req.allowedOrigins() != null) {
                EmbedChannelService.validateAllowedOrigins(EmbedChannelController.stringList(req.allowedOrigins()));
            }
            if (req.webhookUrl() != null) {
                EmbedChannelService.validateWebhookUrl(req.webhookUrl());
            }
            if (req.launcherIcon() != null) {
                EmbedChannelService.validateLauncherIcon(req.launcherIcon().trim());
            }
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
        UpdateCommand cmd = new UpdateCommand();
        cmd.name = EmbedChannelController.orEmpty(req.name());
        cmd.welcomeMessage = req.welcomeMessage();
        cmd.primaryColor = req.primaryColor();
        cmd.pageTitle = req.pageTitle();
        cmd.headerTitleMode = req.headerTitleMode();
        cmd.widgetPosition = req.widgetPosition();
        cmd.agentId = req.agentId();
        cmd.enabled = req.enabled();
        cmd.showSuggested = req.showSuggestedQuestions();
        cmd.showThinking = req.showThinking();
        cmd.allowWebSearch = req.allowWebSearch();
        cmd.allowFileUpload = req.allowFileUpload();
        cmd.defaultLocale = req.defaultLocale();
        cmd.webhookUrl = req.webhookUrl();
        cmd.webhookSecret = req.webhookSecret();
        cmd.launcherIcon = req.launcherIcon();
        cmd.rateLimitPerMinute = req.rateLimitPerMinute() == null ? 0 : req.rateLimitPerMinute();
        cmd.rateLimitPerDay = req.rateLimitPerDay() == null ? 0 : req.rateLimitPerDay();
        // ⚠️ golden 钉死：缺键 = json.Marshal(nil) = "null" 整列覆写（allowlist 清空）
        cmd.allowedOriginsColumn = req.allowedOrigins() == null
                ? "null" : req.allowedOrigins().toString();
        try {
            EmbedChannelEntity ch = ctrl.service.update(EmbedChannelController.currentTenant(), EmbedChannelController.trim(channelId), cmd);
            return ResponseEntity.ok(EmbedChannelController.dataEnvelope(EmbedChannelController.row(ch, false)));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
    }

    /** 对照 DeleteEmbedChannel：{"success":true}。 */
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("channel_id") String channelId) {
        try {
            ctrl.service.delete(EmbedChannelController.currentTenant(), EmbedChannelController.trim(channelId));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 RotateEmbedToken：200 + 含新 token 的行。 */
    public ResponseEntity<Map<String, Object>> rotate(@PathVariable("channel_id") String channelId) {
        try {
            var result = ctrl.service.rotateToken(EmbedChannelController.currentTenant(), EmbedChannelController.trim(channelId));
            return ResponseEntity.ok(EmbedChannelController.dataEnvelope(EmbedChannelController.row(result.channel(), result.token())));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
    }

    /** 对照 IssuePreviewSession：禁用渠道 → 403 "embed channel is disabled"（专用分支）。 */
    public ResponseEntity<Map<String, Object>> preview(@PathVariable("channel_id") String channelId) {
        EmbedChannelService.IssueResult result;
        try {
            result = ctrl.service.issuePreviewSession(EmbedChannelController.currentTenant(), EmbedChannelController.trim(channelId));
        } catch (EmbedError e) {
            if (e.kind == EmbedError.Kind.CHANNEL_DISABLED) {
                return EmbedChannelController.plainError(403, "embed channel is disabled");
            }
            throw EmbedChannelController.writeMgmtError(e);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("expires_in", result.expiresIn());
        data.put("session_token", result.token());
        return ResponseEntity.ok(EmbedChannelController.dataEnvelope(data));
    }

    /** 对照 GetEmbedChannelStats：{session_count:N}。 */
    public ResponseEntity<Map<String, Object>> stats(@PathVariable("channel_id") String channelId) {
        try {
            ctrl.service.getOwnedChannel(EmbedChannelController.currentTenant(), EmbedChannelController.trim(channelId));
        } catch (EmbedError e) {
            throw EmbedChannelController.writeMgmtError(e);
        }
        long total = ctrl.service.countEmbedSessions(EmbedChannelController.currentTenant(), EmbedChannelController.trim(channelId), ctrl.sessionRepository);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_count", total);
        return ResponseEntity.ok(EmbedChannelController.dataEnvelope(data));
    }
}
