package com.ragagent.wiki.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.wiki.domain.WikiLintReport;
import com.ragagent.wiki.domain.WikiPageIssue;
import com.ragagent.wiki.service.page.WikiLintService;
import com.ragagent.wiki.service.page.WikiPageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ragagent.wiki.controller.WikiPageController.RawJsonError;

import static com.ragagent.wiki.controller.WikiRequestSupport.errText;
import static com.ragagent.wiki.controller.WikiRequestSupport.internal;
import static com.ragagent.wiki.controller.WikiRequestSupport.message;
import static com.ragagent.wiki.controller.WikiRequestSupport.q;
import static com.ragagent.wiki.controller.WikiRequestSupport.requiredFieldErrors;
import static com.ragagent.wiki.controller.WikiRequestSupport.sanitize;

/**
 * 检索维护面与问题面的端点执行体：重建链接、体检、自动修复、问题列表与状态流转。
 */
final class WikiMaintenanceOps {

    private final WikiPageService wikiService;
    private final WikiLintService lintService;
    private final WikiKbAccessGuard kbGuard;
    private final ObjectMapper json;

    WikiMaintenanceOps(WikiPageService wikiService, WikiLintService lintService,
                       WikiKbAccessGuard kbGuard, ObjectMapper json) {
        this.wikiService = wikiService;
        this.lintService = lintService;
        this.kbGuard = kbGuard;
        this.json = json;
    }

    /** 重建链接——写端点（创建者/Admin+ + KB 写权限）。 */
    ResponseEntity<?> rebuildLinks(String kbId) {
        kbGuard.requireWikiKB(kbId, true);
        try {
            wikiService.rebuildLinks(kbId);
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(message("Links rebuilt successfully"));
    }

    /**
     * 体检——读端点（Viewer+ 角色 + KB 读权限）。
     *
     * <p>⚠️ 报告里的 {@code issues} 在"零问题"时是 JSON {@code null}（与原实现的 nil 切片一致），
     * 由 {@code WikiLintReport} 的 {@code @JsonInclude(ALWAYS)} + 服务层共同保证。</p>
     */
    ResponseEntity<?> lint(String kbId) {
        kbGuard.requireWikiKB(kbId, false);
        WikiLintReport report;
        try {
            report = lintService.runLint(kbId);
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(report);
    }

    /**
     * 自动修复——写端点（创建者/Admin+ + KB 写权限）。
     * 响应 {@code {"fixed":N,"message":"Auto-fixed N issues"}}（字母序：fixed &lt; message）。
     */
    ResponseEntity<?> autoFix(String kbId) {
        kbGuard.requireWikiKB(kbId, true);
        int fixed;
        try {
            fixed = lintService.autoFix(kbId);
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fixed", fixed);
        body.put("message", "Auto-fixed " + fixed + " issues");
        return ResponseEntity.ok(body);
    }

    /** 问题列表——读端点（Viewer+ 角色 + KB 读权限）；响应是<b>裸数组</b>。 */
    ResponseEntity<?> listIssues(String kbId, HttpServletRequest request) {
        kbGuard.requireWikiKB(kbId, false);

        List<WikiPageIssue> issues;
        try {
            issues = wikiService.listIssues(kbId, q(request, "slug"), q(request, "status"));
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(issues);
    }

    /**
     * 更新问题状态——写端点（创建者/Admin+ + KB 写权限）。
     *
     * <p>⚠️ 这里的请求体按<b>匿名结构</b>校验：报错的 Key 不带结构体前缀
     * （{@code Key: 'Status' ...}），与具名结构的
     * {@code Key: 'WikiPageMoveRequest.Slug' ...} 不同。</p>
     */
    ResponseEntity<?> updateIssueStatus(String kbId,
                                               String issueIdParam,
                                               String rawBody) {
        kbGuard.requireWikiKB(kbId, true);

        String issueId = sanitize(issueIdParam);
        if (issueId.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Issue ID is required");
        }

        JsonNode node = WikiRequestSupport.readJsonBody(json, rawBody);
        String bindingErrors = requiredFieldErrors(node, null, "Status");
        if (bindingErrors != null) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    "Invalid request body: " + bindingErrors);
        }
        String status = node.path("status").asText();

        if (!"pending".equals(status) && !"ignored".equals(status) && !"resolved".equals(status)) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    "Invalid status. Must be pending, ignored, or resolved");
        }

        try {
            wikiService.updateIssueStatus(issueId, status);
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(message("Issue status updated successfully"));
    }
}
