package com.ragagent.wiki.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.wiki.domain.WikiGraph;
import com.ragagent.wiki.domain.WikiIndex;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiStats;
import com.ragagent.wiki.service.page.WikiPageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ragagent.wiki.controller.WikiPageController.RawJsonError;

import static com.ragagent.wiki.controller.WikiRequestSupport.atoi;
import static com.ragagent.wiki.controller.WikiRequestSupport.atoiOrNull;
import static com.ragagent.wiki.controller.WikiRequestSupport.errText;
import static com.ragagent.wiki.controller.WikiRequestSupport.internal;
import static com.ragagent.wiki.controller.WikiRequestSupport.q;
import static com.ragagent.wiki.controller.WikiRequestSupport.query;
import static com.ragagent.wiki.controller.WikiRequestSupport.trimSpace;

/**
 * 索引/图谱/统计/检索端点的执行体。图谱查询参数边界（上限与静默夹紧）随簇在此声明。
 */
final class WikiStatsOps {

    // ── 图谱查询参数边界 ──
    /** 默认节点上限 */
    static final int GRAPH_DEFAULT_LIMIT = 500;
    /** 节点数硬上限 */
    static final int GRAPH_MAX_LIMIT = 2000;
    /** ego 深度硬上限 */
    static final int GRAPH_MAX_DEPTH = 3;
    /** ego 深度默认值 */
    static final int GRAPH_DEFAULT_DEPTH = 1;

    private final WikiPageService wikiService;
    private final WikiKbAccessGuard kbGuard;

    WikiStatsOps(WikiPageService wikiService, WikiKbAccessGuard kbGuard) {
        this.wikiService = wikiService;
        this.kbGuard = kbGuard;
    }

    /**
     * 索引页——读端点（Viewer+ 角色 + KB 读权限）。
     *
     * <p>{@code limit} 只在"解析成功且 &gt; 0"时才采用（解析失败静默回落 50），
     * 上限由服务层夹到 200。</p>
     */
    ResponseEntity<?> getIndex(String kbId, HttpServletRequest request) {
        kbGuard.requireWikiKB(kbId, false);

        List<String> pageTypes = new ArrayList<>();
        String rawTypes = request.getParameter("types");
        if (rawTypes != null && !rawTypes.isEmpty()) {
            for (String t : rawTypes.split(",", -1)) {
                String trimmed = trimSpace(t);
                if (!trimmed.isEmpty()) {
                    pageTypes.add(trimmed);
                }
            }
        }

        int limit = 50;
        String rawLimit = request.getParameter("limit");
        if (rawLimit != null && !rawLimit.isEmpty()) {
            Integer v = atoiOrNull(rawLimit);
            if (v != null && v > 0) {
                limit = v;
            }
        }

        WikiIndex.Response resp;
        try {
            resp = wikiService.getIndexView(kbId, pageTypes, limit, q(request, "cursor"));
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * 图谱——读端点（Viewer+ 角色 + KB 读权限）。
     *
     * <p>参数校验顺序固定：mode → center（仅 ego）→ depth → limit → types。
     * depth / limit 的"非正整数"是 400，超上限则<b>静默夹紧</b>而不是报错。</p>
     *
     * <p>"熟悉知识"叠加层（FamiliarKnowledgeIDs）无对应模块 → 传 null（空值分支）。</p>
     */
    ResponseEntity<?> getGraph(String kbId, HttpServletRequest request) {
        kbGuard.requireWikiKB(kbId, false);

        String mode = trimSpace(request.getParameter("mode"));
        if (mode.isEmpty()) {
            mode = WikiGraph.MODE_OVERVIEW;
        }
        if (!WikiGraph.MODE_OVERVIEW.equals(mode) && !WikiGraph.MODE_EGO.equals(mode)) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "mode must be 'overview' or 'ego'");
        }

        String center = trimSpace(request.getParameter("center"));
        if (WikiGraph.MODE_EGO.equals(mode) && center.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "center is required when mode=ego");
        }

        int depth = GRAPH_DEFAULT_DEPTH;
        String rawDepth = request.getParameter("depth");
        if (rawDepth != null && !rawDepth.isEmpty()) {
            Integer parsed = atoiOrNull(rawDepth);
            if (parsed == null || parsed < 1) {
                throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                        "depth must be a positive integer");
            }
            if (parsed > GRAPH_MAX_DEPTH) {
                parsed = GRAPH_MAX_DEPTH;
            }
            depth = parsed;
        }

        int limit = GRAPH_DEFAULT_LIMIT;
        String rawLimit = request.getParameter("limit");
        if (rawLimit != null && !rawLimit.isEmpty()) {
            Integer parsed = atoiOrNull(rawLimit);
            if (parsed == null || parsed < 1) {
                throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                        "limit must be a positive integer");
            }
            if (parsed > GRAPH_MAX_LIMIT) {
                parsed = GRAPH_MAX_LIMIT;
            }
            limit = parsed;
        }

        List<String> typesFilter = new ArrayList<>();
        String rawTypes = trimSpace(request.getParameter("types"));
        if (!rawTypes.isEmpty()) {
            for (String t : rawTypes.split(",", -1)) {
                String trimmed = trimSpace(t);
                if (!trimmed.isEmpty()) {
                    typesFilter.add(trimmed);
                }
            }
        }

        WikiGraph.Data graph;
        try {
            graph = wikiService.getGraph(new WikiGraph.Request(kbId, mode, center, depth,
                    typesFilter, limit, null));
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(graph);
    }

    /** 统计——读端点（Viewer+ 角色 + KB 读权限）。 */
    ResponseEntity<?> getStats(String kbId) {
        kbGuard.requireWikiKB(kbId, false);
        WikiStats stats;
        try {
            stats = wikiService.getStats(kbId);
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(stats);
    }

    /**
     * 检索——读端点（Viewer+ 角色 + KB 读权限）。
     *
     * <p>⚠️ 响应是包着一层 {@code "pages"} 键的对象，<b>不是</b>裸数组（ListIssues 才是裸数组）。</p>
     */
    ResponseEntity<?> searchPages(String kbId, HttpServletRequest request) {
        kbGuard.requireWikiKB(kbId, false);

        String searchQuery = q(request, "q");
        if (searchQuery.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Search query 'q' is required");
        }
        int limit = atoi(query(request, "limit", "10"));

        List<WikiPage> pages;
        try {
            pages = wikiService.searchPages(kbId, searchQuery, limit);
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pages", pages);
        return ResponseEntity.ok(body);
    }
}
