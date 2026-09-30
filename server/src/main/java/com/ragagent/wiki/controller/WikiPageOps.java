package com.ragagent.wiki.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageConflictException;
import com.ragagent.wiki.domain.WikiPageListRequest;
import com.ragagent.wiki.domain.WikiPageListResponse;
import com.ragagent.wiki.domain.WikiPageNotFoundException;
import com.ragagent.wiki.domain.WikiPageRevertRequest;
import com.ragagent.wiki.domain.WikiPageRevision;
import com.ragagent.wiki.domain.WikiPageRevisionListResponse;
import com.ragagent.wiki.domain.WikiPageUpdateRequest;
import com.ragagent.wiki.service.page.WikiEditContext;
import com.ragagent.wiki.service.page.WikiPageService;
import com.ragagent.wiki.service.page.WikiRevertToCurrentVersionException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ragagent.wiki.controller.WikiPageController.RawJsonError;

import static com.ragagent.wiki.controller.WikiRequestSupport.atoi;
import static com.ragagent.wiki.controller.WikiRequestSupport.atoiOrNull;
import static com.ragagent.wiki.controller.WikiRequestSupport.currentTenantId;
import static com.ragagent.wiki.controller.WikiRequestSupport.errText;
import static com.ragagent.wiki.controller.WikiRequestSupport.getSlugParam;
import static com.ragagent.wiki.controller.WikiRequestSupport.hasParam;
import static com.ragagent.wiki.controller.WikiRequestSupport.internal;
import static com.ragagent.wiki.controller.WikiRequestSupport.parseWikiCategoryPath;
import static com.ragagent.wiki.controller.WikiRequestSupport.q;
import static com.ragagent.wiki.controller.WikiRequestSupport.query;
import static com.ragagent.wiki.controller.WikiRequestSupport.requiredFieldErrors;
import static com.ragagent.wiki.controller.WikiRequestSupport.trimSpace;

/**
 * 页面资源端点的执行体：CRUD、修订历史、回滚。KB 访问守卫与活动记账由
 * 协作者承接；参数清洗/绑定/错误映射走 WikiRequestSupport 静态面。
 */
final class WikiPageOps {

    private final WikiPageService wikiService;
    private final WikiKbAccessGuard kbGuard;
    private final WikiActivityRecorder activity;
    private final ObjectMapper json;

    WikiPageOps(WikiPageService wikiService, WikiKbAccessGuard kbGuard,
                WikiActivityRecorder activity, ObjectMapper json) {
        this.wikiService = wikiService;
        this.kbGuard = kbGuard;
        this.activity = activity;
        this.json = json;
    }

    // ── 页面 CRUD ──

    /**
     * 页面列表——读端点（Viewer+ 角色 + KB 读权限）。
     *
     * <p>{@code folder_id} 的<b>存在性</b>有语义：显式存在但为空 = 根目录（{@code folder_id = ''}），
     * 完全缺席 = 不过滤。用 {@code request.getParameterMap().containsKey} 区分两者。</p>
     */
    ResponseEntity<?> listPages(String kbId, HttpServletRequest request) {
        kbGuard.requireWikiKB(kbId, false);

        int page = atoi(query(request, "page", "1"));
        int pageSize = atoi(query(request, "pageSize", "20"));
        List<String> categoryPath = parseWikiCategoryPath(q(request, "categoryPath"));

        // "提供了空值" vs "没提供"是两种不同语义
        String folderId = null;
        if (hasParam(request, "folderId")) {
            folderId = trimSpace(request.getParameter("folderId"));
        }

        // 解析成功且 >= 0 才生效
        Integer categoryDepth = null;
        String rawDepth = q(request, "categoryDepth");
        if (!rawDepth.isEmpty()) {
            Integer depth = atoiOrNull(rawDepth);
            if (depth != null && depth >= 0) {
                categoryDepth = depth;
            }
        }

        WikiPageListRequest req = new WikiPageListRequest();
        req.setKnowledgeBaseId(kbId);
        req.setPageType(q(request, "pageType"));
        req.setStatus(q(request, "status"));
        req.setQuery(q(request, "query"));
        req.setFolderId(folderId);
        req.setCategoryPath(categoryPath);
        req.setCategoryDepth(categoryDepth);
        req.setPage(page);
        req.setPageSize(pageSize);
        req.setSortBy(query(request, "sortBy", "updated_at"));
        req.setSortOrder(query(request, "sortOrder", "desc"));

        WikiPageListResponse resp;
        try {
            resp = wikiService.listPages(req);
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * 新建页面——写端点（创建者/Admin+ + KB 写权限）。
     *
     * <p>请求体直接绑定 {@link WikiPage}（没有独立的 CreateRequest），
     * 且 {@code page_type} / {@code status} 只在<b>非空</b>时校验合法性。</p>
     */
    ResponseEntity<?> createPage(String kbId,
                                        String rawBody) {
        kbGuard.requireWikiKB(kbId, true);
        long tenantId = currentTenantId();

        WikiPage page = WikiRequestSupport.bind(json, rawBody, WikiPage.class);
        page.setKnowledgeBaseId(kbId);
        page.setTenantId(tenantId);
        page.setPageType(trimSpace(page.getPageType()));
        page.setStatus(trimSpace(page.getStatus()));
        if (!page.getPageType().isEmpty() && !WikiConstants.isValidPageType(page.getPageType())) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    "Invalid page_type: " + page.getPageType());
        }
        if (!page.getStatus().isEmpty() && !WikiConstants.isValidPageStatus(page.getStatus())) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    "Invalid status: " + page.getStatus());
        }

        WikiPage created;
        try {
            // 以"用户编辑"来源标记包裹这次 CreatePage
            created = WikiEditContext.callWith(WikiConstants.EDIT_SOURCE_USER,
                    () -> wikiService.createPage(page));
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }

        activity.recordManualWikiActivity(created, "manual_create");
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 页面详情——读端点（Viewer+ 角色 + KB 读权限）。
     * 路径是 catch-all，slug 可以多段（{@code entity/acme}）。
     */
    ResponseEntity<?> getPage(String kbId,
                                     String slugParam) {
        kbGuard.requireWikiKB(kbId, false);

        String slug = getSlugParam(slugParam);
        if (slug.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Page slug is required");
        }

        WikiPage page;
        try {
            page = wikiService.getPageBySlug(kbId, slug);
        } catch (WikiPageNotFoundException e) {
            throw new RawJsonError(HttpStatus.NOT_FOUND.value(), "Wiki page not found");
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(page);
    }

    /**
     * 更新页面——写端点（创建者/Admin+ + KB 写权限）。
     *
     * <p>部分更新：缺席字段保留库中值（
     * {@link WikiPageUpdateRequest} record 用 null 表达缺席）。{@code version &gt; 0} 时是
     * 乐观锁护栏，与库中版本不符则 409 并<b>附带当前版本</b>供客户端重载。</p>
     *
     * <p>⚠️ 409 的 body 是 raw JSON map → <b>键字母序</b>：{@code current_version} 在 {@code error} 之前。</p>
     */
    ResponseEntity<?> updatePage(String kbId,
                                        String slugParam,
                                        String rawBody) {
        kbGuard.requireWikiKB(kbId, true);

        String slug = getSlugParam(slugParam);
        if (slug.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Page slug is required");
        }

        WikiPageUpdateRequest req = WikiRequestSupport.bind(json, rawBody, WikiPageUpdateRequest.class);

        // 以"用户编辑"来源标记覆盖整段读写
        return WikiEditContext.callWith(WikiConstants.EDIT_SOURCE_USER,
                () -> applyPageUpdate(kbId, slug, req));
    }


    private ResponseEntity<?> applyPageUpdate(String kbId, String slug, WikiPageUpdateRequest req) {
        WikiPage existing;
        try {
            existing = wikiService.getPageBySlug(kbId, slug);
        } catch (WikiPageNotFoundException e) {
            throw new RawJsonError(HttpStatus.NOT_FOUND.value(), "Wiki page not found");
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }

        int previousVersion = existing.getVersion();
        if (req.version() > 0 && req.version() != previousVersion) {
            // 键字母序：current_version < error
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("currentVersion", previousVersion);
            body.put("error", "Wiki page was modified by someone else");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }

        // 把提交的字段合并到库里那份：服务的更新语义是
        // "完整的目标状态"，喂半空的结构体会把真实数据清掉。
        if (req.title() != null) {
            existing.setTitle(trimSpace(req.title()));
        }
        if (req.content() != null) {
            existing.setContent(req.content());
        }
        if (req.summary() != null) {
            existing.setSummary(req.summary());
        }
        if (req.pageType() != null) {
            existing.setPageType(trimSpace(req.pageType()));
            if (!WikiConstants.isValidPageType(existing.getPageType())) {
                throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                        "Invalid page_type: " + existing.getPageType());
            }
        }
        if (req.status() != null) {
            existing.setStatus(trimSpace(req.status()));
            if (!WikiConstants.isValidPageStatus(existing.getStatus())) {
                throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                        "Invalid status: " + existing.getStatus());
            }
        }
        if (req.aliases() != null) {
            existing.setAliases(new ArrayList<>(req.aliases()));
        }

        WikiPage updated;
        try {
            updated = wikiService.updatePage(existing);
        } catch (WikiPageNotFoundException e) {
            throw new RawJsonError(HttpStatus.NOT_FOUND.value(), "Wiki page not found");
        } catch (WikiPageConflictException e) {
            throw new RawJsonError(HttpStatus.CONFLICT.value(),
                    "Wiki page was modified by someone else");
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }

        if (updated.getVersion() != previousVersion) {
            activity.recordManualWikiActivity(updated, "manual_edit");
        }
        return ResponseEntity.ok(updated);
    }

    /**
     * 删除页面——写端点（创建者/Admin+ + KB 写权限）。
     *
     * <p>先读一次（好让活动流带上被删页面的标题），读取失败被<b>刻意忽略</b>；
     * 真正的删除失败才报 404。</p>
     */
    ResponseEntity<?> deletePage(String kbId,
                                        String slugParam) {
        kbGuard.requireWikiKB(kbId, true);

        String slug = getSlugParam(slugParam);
        if (slug.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Page slug is required");
        }

        WikiPage page = null;
        try {
            page = wikiService.getPageBySlug(kbId, slug);
        } catch (RuntimeException ignored) {
            // 读取失败刻意忽略（只影响活动流里的标题）
        }

        try {
            wikiService.deletePage(kbId, slug);
        } catch (WikiPageNotFoundException e) {
            throw new RawJsonError(HttpStatus.NOT_FOUND.value(), "Wiki page not found");
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }

        activity.recordManualWikiActivity(page, "manual_delete");
        return ResponseEntity.noContent().build();
    }

    // ── 修订历史 ──

    /**
     * 修订历史——读端点（Viewer+ 角色 + KB 读权限）。
     *
     * <p>两种模式共用一个 GET：带 {@code version} 时返回<b>单条含 content</b> 的快照，
     * 否则返回最新在前的列表（省略 content）+ 当前版本。</p>
     */
    ResponseEntity<?> listRevisions(String kbId,
                                           String slugParam,
                                           HttpServletRequest request) {
        kbGuard.requireWikiKB(kbId, false);

        String slug = getSlugParam(slugParam);
        if (slug.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Page slug is required");
        }

        String rawVersion = request.getParameter("version");
        if (rawVersion != null && !rawVersion.isEmpty()) {
            Integer version = atoiOrNull(rawVersion);
            if (version == null || version < 1) {
                throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Invalid version");
            }
            WikiPageRevision rev;
            try {
                rev = wikiService.getRevision(kbId, slug, version);
            } catch (WikiPageNotFoundException e) {
                throw new RawJsonError(HttpStatus.NOT_FOUND.value(), "Wiki page revision not found");
            } catch (RuntimeException e) {
                throw internal(errText(e));
            }
            return ResponseEntity.ok(rev);
        }

        int limit = atoi(query(request, "limit", "50"));
        if (limit < 1) {
            limit = 50;
        }
        if (limit > 200) {
            limit = 200;
        }
        int offset = atoi(query(request, "offset", "0"));
        if (offset < 0) {
            offset = 0;
        }

        WikiPageRevisionListResponse resp;
        try {
            resp = wikiService.listRevisions(kbId, slug, limit, offset);
        } catch (WikiPageNotFoundException e) {
            throw new RawJsonError(HttpStatus.NOT_FOUND.value(), "Wiki page not found");
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * 回滚页面——写端点（创建者/Admin+ + KB 写权限）。
     *
     * <p>slug 走请求体（层级 slug 会和 catch-all 路由冲突，同 move-page）。回滚是
     * <b>一次普通编辑</b>：回滚前状态会被快照、版本号前进。</p>
     *
     * <p>⚠️ "回滚到的就是当前版本"错误 → <b>400</b>（不是 500）。</p>
     */
    ResponseEntity<?> revertPage(String kbId,
                                        String rawBody) {
        kbGuard.requireWikiKB(kbId, true);

        JsonNode node = WikiRequestSupport.readJsonBody(json, rawBody);
        String bindingErrors = requiredFieldErrors(node, "WikiPageRevertRequest", "Slug", "Version");
        if (bindingErrors != null) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    "Invalid request body: " + bindingErrors);
        }
        WikiPageRevertRequest req = WikiRequestSupport.toType(json, node, WikiPageRevertRequest.class);

        String slug = trimSpace(req.slug());
        if (slug.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Page slug is required");
        }
        if (req.version() < 1) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Invalid version");
        }

        WikiPage updated;
        try {
            // 回滚编辑以"revert"来源归属这次编辑
            updated = wikiService.revertPageToVersion(kbId, slug, req.version());
        } catch (WikiPageNotFoundException e) {
            throw new RawJsonError(HttpStatus.NOT_FOUND.value(), "Wiki page or revision not found");
        } catch (WikiPageConflictException e) {
            throw new RawJsonError(HttpStatus.CONFLICT.value(),
                    "Wiki page was modified by someone else");
        } catch (WikiRevertToCurrentVersionException e) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), errText(e));
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }

        activity.recordManualWikiActivity(updated, "revert");
        return ResponseEntity.ok(updated);
    }
}
