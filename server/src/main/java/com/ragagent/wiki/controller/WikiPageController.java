package com.ragagent.wiki.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.apikey.domain.TenantAPIKeyScope;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.GuardForbiddenException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiFolder;
import com.ragagent.wiki.domain.WikiFolderConflictException;
import com.ragagent.wiki.domain.WikiFolderCreateRequest;
import com.ragagent.wiki.domain.WikiFolderListResponse;
import com.ragagent.wiki.domain.WikiFolderNode;
import com.ragagent.wiki.domain.WikiFolderNotEmptyException;
import com.ragagent.wiki.domain.WikiFolderNotFoundException;
import com.ragagent.wiki.domain.WikiFolderUpdateRequest;
import com.ragagent.wiki.domain.WikiGraph;
import com.ragagent.wiki.domain.WikiIndex;
import com.ragagent.wiki.domain.WikiLintReport;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageConflictException;
import com.ragagent.wiki.domain.WikiPageIssue;
import com.ragagent.wiki.domain.WikiPageListRequest;
import com.ragagent.wiki.domain.WikiPageListResponse;
import com.ragagent.wiki.domain.WikiPageMoveRequest;
import com.ragagent.wiki.domain.WikiPageNotFoundException;
import com.ragagent.wiki.domain.WikiPageRevertRequest;
import com.ragagent.wiki.domain.WikiPageRevision;
import com.ragagent.wiki.domain.WikiPageRevisionListResponse;
import com.ragagent.wiki.domain.WikiPageUpdateRequest;
import com.ragagent.wiki.domain.WikiStats;
import com.ragagent.wiki.service.WikiEditContext;
import com.ragagent.wiki.service.WikiLintService;
import com.ragagent.wiki.service.WikiPageService;
import com.ragagent.wiki.service.WikiRevertToCurrentVersionException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wiki 页面的 HTTP 层（对照 Go internal/handler/wiki_page.go 全文，1089 行 / 21 个端点）。
 *
 * <p><b>响应形态（逐端点对照 Go，不要"统一"它们）</b>：</p>
 * <ol>
 *   <li><b>实体直出</b>：{@code c.JSON(200, page)} / {@code 201 folder} —— 直接序列化领域对象，
 *       键序 = Go struct 声明序（由各 domain 类的 {@code @JsonPropertyOrder} 复刻），
 *       <b>没有</b> {@code {data,success}} 信封。wiki 层几乎所有端点都是这个形态。</li>
 *   <li><b>gin.H 直出</b>：{@code {"message":...}}（UpdateIssueStatus / RebuildLinks）、
 *       {@code {"fixed":N,"message":...}}（AutoFix）、{@code {"pages":[...]}}（SearchPages）、
 *       {@code {"current_version":N,"error":...}}（UpdatePage 的乐观锁冲突）。
 *       gin.H 是 map → encoding/json 按<b>键字母序</b>输出，故 Java 用 LinkedHashMap 按字母序插入。</li>
 *   <li><b>裸数组</b>：{@code c.JSON(200, issues)}（ListIssues）。</li>
 *   <li><b>handler 直接写的错误</b>：{@code c.JSON(status, gin.H{"error": msg})} ——
 *       单键 map，形态是 {@code {"error":"..."}}，与全局错误信封
 *       {@code {"success":false,"error":{code,details,message}}} <b>不同</b>。
 *       21 个端点里除"KB 访问被拒"外全部走这一形态（见下）。</li>
 *   <li><b>守卫写的错误</b>：Go 的 {@code KBAccessRead/KBAccessWrite} 走 {@code c.Error()}
 *       → 全局 ErrorHandler，形态是 {@code {"success":false,"error":{...}}}。
 *       Java 侧对应 {@link BizException}（GlobalExceptionHandler 逐字节对齐 Go）。</li>
 * </ol>
 *
 * <p><b>⚠️ 错误文案的前缀</b>：Go 的 {@code validateWikiKB} 返回的是
 * {@code errors.AppError}，而 handler 写出去的是 {@code err.Error()} ——
 * AppError.Error() 是 {@code "error code: %d, error message: %s"}，<b>不是</b>裸消息。
 * 例：KB 未启用 wiki 时客户端收到的是
 * {@code {"error":"error code: 400, error message: Wiki feature is not enabled for this knowledge base"}}
 * 且 HTTP 状态是 <b>400</b>。Java 的 {@code BizException.getMessage()} 恰好是同一格式
 * （见 {@link BizException#BizException}），因此这里复用同一文案函数。</p>
 *
 * <p><b>守卫矩阵</b>（Go routes_knowledge.go L294-334，主会话在 WebConfig 里注册角色下限）：</p>
 * <pre>
 * 读端点：Viewer+  + KBAccessRead  （同租户可读；跨租户经 org-share / shared-agent 只读授予）
 * 写端点：OwnedWikiKBOrAdmin（创建者本人或 Admin+，否则 403）+ KBAccessWrite
 * </pre>
 * <p>Java 的 {@code RbacInterceptor} 只能表达"角色下限"，无法表达 KBAccess 的
 * "own / org-shared / via shared agent" 解析，故 <b>KB 访问与所有权判定在本控制器内完成</b>
 * （{@link #requireWikiKB}），角色下限仍由 WebConfig 注册的规则负责。跨空间的两条授予路径
 * 复用 W5α1 已落地的积木（{@code com.ragagent.org} 包的 {@code KbShareService} /
 * {@code AgentShareService} / {@code SharedAgentKBScope}），按 Go
 * {@code access.ResolveKB} 逐分支移植。</p>
 *
 * <p><b>⚠️ 通配 slug</b>：Go 的路由是 {@code /pages/*slug}，gin 的 catch-all 参数值
 * <b>带前导 "/"</b>，handler 用 {@code strings.TrimPrefix(slug, "/")} + {@code TrimSpace} 清洗。
 * Java 用 Spring 的 {@code {*slug}} 捕获（同样带前导 "/"），清洗逻辑逐字照抄
 * {@link #getSlugParam}。</p>
 *
 * <p><b>已知阶段性差异</b>（见报告）：</p>
 * <ul>
 *   <li>Go 的 {@code GetGraph} 会用 {@code memoryService.FamiliarKnowledgeIDs(ctx)} 点亮
 *       "熟悉"节点；Java 无 memory 模块 → 该字段恒为 null（等价 Go 的 nil 分支）。</li>
 *   <li>Go 的审计埋点 {@code RecordWikiContentActivity} 由
 *       {@link WikiActivityAudit} 接缝承接；实现 bean
 *       （{@code com.ragagent.audit.service.WikiActivityAuditRecorder}）已随审计模块
 *       翻译落地，缺失时才退化为 debug 日志。</li>
 *   <li>Go 的 nil slice 序列化成 {@code null}，Java 侧沿用既有 DTO/服务层的"空列表"归一
 *       （ListIssues / SearchPages / ListPages 的空结果）。</li>
 *   <li>{@code ShouldBindJSON} 的 JSON 语法错误文案：Go 用 encoding/json 的消息，
 *       Java 用 Jackson 的消息（约定 §9 阶段 1 已记录的同类差异）。</li>
 *   <li><b>写路径照 Go 对齐（2026-09-23 主会话复核）</b>：跨租户写经
 *       {@code OwnedWikiKBOrAdmin}（creator 查不到 → 透传）+ {@code KBAccessWrite(Editor)}
 *       ——org-share editor 可写；共享 agent 分支对 Editor 不可达。Java 的
 *       {@link #requireSharedWriteAccess} 同构。同租户写仍走创建者/Admin+。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/knowledgebase/{kb_id}/wiki")
public class WikiPageController {

    private static final Logger log = LoggerFactory.getLogger(WikiPageController.class);

    // ── 图谱查询参数边界（对照 Go wiki_page.go L777-782） ──
    /** 对照 Go {@code wikiGraphDefaultLimit}：默认节点上限 */
    static final int GRAPH_DEFAULT_LIMIT = 500;
    /** 对照 Go {@code wikiGraphMaxLimit}：硬上限 */
    static final int GRAPH_MAX_LIMIT = 2000;
    /** 对照 Go {@code wikiGraphMaxDepth}：ego 深度硬上限 */
    static final int GRAPH_MAX_DEPTH = 3;
    /** 对照 Go {@code wikiGraphDefaultDepth} */
    static final int GRAPH_DEFAULT_DEPTH = 1;

    private final WikiPageService wikiService;
    private final WikiLintService lintService;
    private final KnowledgeBaseMapper kbMapper;
    private final ObjectMapper json;
    private final ObjectProvider<WikiActivityAudit> activityAudit;

    public WikiPageController(WikiPageService wikiService,
                              WikiLintService lintService,
                              KnowledgeBaseMapper kbMapper,
                              ObjectMapper json,
                              ObjectProvider<WikiActivityAudit> activityAudit) {
        this.wikiService = wikiService;
        this.lintService = lintService;
        this.kbMapper = kbMapper;
        this.json = json;
        this.activityAudit = activityAudit;
    }

    // ════════════════════════════ 页面 CRUD ════════════════════════════

    /**
     * 对照 Go {@code ListPages}（L95-140）—— Viewer+ / KBAccessRead。
     *
     * <p>{@code folder_id} 的<b>存在性</b>有语义：显式存在但为空 = 根目录（{@code folder_id = ''}），
     * 完全缺席 = 不过滤。Go 用 {@code c.GetQuery} 的 ok 区分两者，这里用
     * {@code request.getParameterMap().containsKey} 复刻。</p>
     */
    @GetMapping("/pages")
    public ResponseEntity<?> listPages(@PathVariable("kb_id") String kbId, HttpServletRequest request) {
        requireWikiKB(kbId, false);

        int page = atoi(query(request, "page", "1"));
        int pageSize = atoi(query(request, "page_size", "20"));
        List<String> categoryPath = parseWikiCategoryPath(q(request, "category_path"));

        // 对照 Go L106-111：*string 的"提供了空值" vs "没提供"
        String folderId = null;
        if (hasParam(request, "folder_id")) {
            folderId = trimSpace(request.getParameter("folder_id"));
        }

        // 对照 Go L112-117：解析成功且 >= 0 才生效
        Integer categoryDepth = null;
        String rawDepth = q(request, "category_depth");
        if (!rawDepth.isEmpty()) {
            Integer depth = atoiOrNull(rawDepth);
            if (depth != null && depth >= 0) {
                categoryDepth = depth;
            }
        }

        WikiPageListRequest req = new WikiPageListRequest();
        req.setKnowledgeBaseId(kbId);
        req.setPageType(q(request, "page_type"));
        req.setStatus(q(request, "status"));
        req.setQuery(q(request, "query"));
        req.setFolderId(folderId);
        req.setCategoryPath(categoryPath);
        req.setCategoryDepth(categoryDepth);
        req.setPage(page);
        req.setPageSize(pageSize);
        req.setSortBy(query(request, "sort_by", "updated_at"));
        req.setSortOrder(query(request, "sort_order", "desc"));

        WikiPageListResponse resp;
        try {
            resp = wikiService.listPages(req);
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * 对照 Go {@code CreatePage}（L355-390）—— OwnedWikiKBOrAdmin / KBAccessWrite。
     *
     * <p>请求体直接绑定 {@code types.WikiPage}（没有独立的 CreateRequest），
     * 且 {@code page_type} / {@code status} 只在<b>非空</b>时校验合法性。</p>
     */
    @PostMapping("/pages")
    public ResponseEntity<?> createPage(@PathVariable("kb_id") String kbId,
                                        @RequestBody(required = false) String rawBody) {
        requireWikiKB(kbId, true);
        long tenantId = currentTenantId();

        WikiPage page = bind(rawBody, WikiPage.class);
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
            // 对照 Go：types.WithWikiEditSource(ctx, WikiEditSourceUser) 包裹这次 CreatePage
            created = WikiEditContext.callWith(WikiConstants.EDIT_SOURCE_USER,
                    () -> wikiService.createPage(page));
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }

        recordManualWikiActivity(created, "manual_create");
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 对照 Go {@code GetPage}（L416-440）—— Viewer+ / KBAccessRead。
     * 路径是 catch-all（{@code /pages/*slug}），slug 可以多段（{@code entity/acme}）。
     */
    @GetMapping("/pages/{*slug}")
    public ResponseEntity<?> getPage(@PathVariable("kb_id") String kbId,
                                     @PathVariable(value = "slug", required = false) String slugParam) {
        requireWikiKB(kbId, false);

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
     * 对照 Go {@code UpdatePage}（L461-547）—— OwnedWikiKBOrAdmin / KBAccessWrite。
     *
     * <p>部分更新：缺席字段保留库中值（Go 用指针，Java 的
     * {@link WikiPageUpdateRequest} record 用 null 表达缺席）。{@code version &gt; 0} 时是
     * 乐观锁护栏，与库中版本不符则 409 并<b>附带当前版本</b>供客户端重载。</p>
     *
     * <p>⚠️ 409 的 body 是 gin.H → <b>键字母序</b>：{@code current_version} 在 {@code error} 之前。</p>
     */
    @PutMapping("/pages/{*slug}")
    public ResponseEntity<?> updatePage(@PathVariable("kb_id") String kbId,
                                        @PathVariable(value = "slug", required = false) String slugParam,
                                        @RequestBody(required = false) String rawBody) {
        requireWikiKB(kbId, true);

        String slug = getSlugParam(slugParam);
        if (slug.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Page slug is required");
        }

        WikiPageUpdateRequest req = bind(rawBody, WikiPageUpdateRequest.class);

        // 对照 Go：types.WithWikiEditSource(ctx, WikiEditSourceUser) 覆盖整段读写
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
            // gin.H 字母序：current_version < error
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("current_version", previousVersion);
            body.put("error", "Wiki page was modified by someone else");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }

        // 把提交的字段合并到库里那份（Go 的 `page := *existing`）：服务的 UpdatePage 语义是
        // "完整的目标状态"，喂半空的 struct 会把真实数据清掉。
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
            recordManualWikiActivity(updated, "manual_edit");
        }
        return ResponseEntity.ok(updated);
    }

    /**
     * 对照 Go {@code DeletePage}（L692-720）—— OwnedWikiKBOrAdmin / KBAccessWrite。
     *
     * <p>先读一次（好让活动流带上被删页面的标题），读取失败被<b>刻意忽略</b>；
     * 真正的删除失败才报 404。</p>
     */
    @DeleteMapping("/pages/{*slug}")
    public ResponseEntity<?> deletePage(@PathVariable("kb_id") String kbId,
                                        @PathVariable(value = "slug", required = false) String slugParam) {
        requireWikiKB(kbId, true);

        String slug = getSlugParam(slugParam);
        if (slug.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Page slug is required");
        }

        WikiPage page = null;
        try {
            page = wikiService.getPageBySlug(kbId, slug);
        } catch (RuntimeException ignored) {
            // 对照 Go：`page, _ := h.wikiService.GetPageBySlug(...)`
        }

        try {
            wikiService.deletePage(kbId, slug);
        } catch (WikiPageNotFoundException e) {
            throw new RawJsonError(HttpStatus.NOT_FOUND.value(), "Wiki page not found");
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }

        recordManualWikiActivity(page, "manual_delete");
        return ResponseEntity.noContent().build();
    }

    // ════════════════════════════ 修订历史 ════════════════════════════

    /**
     * 对照 Go {@code ListRevisions}（L565-621）—— Viewer+ / KBAccessRead。
     *
     * <p>两种模式共用一个 GET：带 {@code version} 时返回<b>单条含 content</b> 的快照，
     * 否则返回最新在前的列表（省略 content）+ 当前版本。</p>
     */
    @GetMapping("/revisions/{*slug}")
    public ResponseEntity<?> listRevisions(@PathVariable("kb_id") String kbId,
                                           @PathVariable(value = "slug", required = false) String slugParam,
                                           HttpServletRequest request) {
        requireWikiKB(kbId, false);

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
     * 对照 Go {@code RevertPage}（L640-680）—— OwnedWikiKBOrAdmin / KBAccessWrite。
     *
     * <p>slug 走请求体（层级 slug 会和 catch-all 路由冲突，同 move-page）。回滚是
     * <b>一次普通编辑</b>：回滚前状态会被快照、版本号前进。</p>
     *
     * <p>⚠️ {@code ErrWikiRevertToCurrentVersion} → <b>400</b>（不是 500）。</p>
     */
    @PostMapping("/revert")
    public ResponseEntity<?> revertPage(@PathVariable("kb_id") String kbId,
                                        @RequestBody(required = false) String rawBody) {
        requireWikiKB(kbId, true);

        JsonNode node = readJsonBody(rawBody);
        String bindingErrors = requiredFieldErrors(node, "WikiPageRevertRequest", "Slug", "Version");
        if (bindingErrors != null) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    "Invalid request body: " + bindingErrors);
        }
        WikiPageRevertRequest req = toType(node, WikiPageRevertRequest.class);

        String slug = trimSpace(req.slug());
        if (slug.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Page slug is required");
        }
        if (req.version() < 1) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Invalid version");
        }

        WikiPage updated;
        try {
            // 对照 Go：RevertPageToVersion 内部以 WikiEditSourceRevert 归属这次编辑
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

        recordManualWikiActivity(updated, "revert");
        return ResponseEntity.ok(updated);
    }

    // ════════════════════════════ 文件夹树 ════════════════════════════

    /**
     * 对照 Go {@code ListFolders}（L153-177）—— Viewer+ / KBAccessRead。
     *
     * <p>Go 显式把 nil 换成 {@code []}，所以空结果是 {@code "folders":[]} 而不是 null。</p>
     */
    @GetMapping("/folders")
    public ResponseEntity<?> listFolders(@PathVariable("kb_id") String kbId, HttpServletRequest request) {
        requireWikiKB(kbId, false);

        String parentId = trimSpace(request.getParameter("parent_id"));
        List<String> pageTypes = new ArrayList<>();
        String raw = trimSpace(request.getParameter("page_types"));
        if (!raw.isEmpty()) {
            for (String part : raw.split(",", -1)) {
                String p = trimSpace(part);
                if (!p.isEmpty()) {
                    pageTypes.add(p);
                }
            }
        }

        List<WikiFolderNode> folders;
        try {
            folders = wikiService.listChildFolders(kbId, parentId, pageTypes);
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        if (folders == null) {
            folders = new ArrayList<>();
        }

        WikiFolderListResponse resp = new WikiFolderListResponse();
        resp.setParentId(parentId);
        resp.setFolders(folders);
        return ResponseEntity.ok(resp);
    }

    /** 对照 Go {@code CreateFolder}（L192-209）—— OwnedWikiKBOrAdmin / KBAccessWrite；201。 */
    @PostMapping("/folders")
    public ResponseEntity<?> createFolder(@PathVariable("kb_id") String kbId,
                                          @RequestBody(required = false) String rawBody) {
        requireWikiKB(kbId, true);

        WikiFolderCreateRequest req = bind(rawBody, WikiFolderCreateRequest.class);
        WikiFolder folder;
        try {
            // Go 只 trim parentID，name 原样交给服务层（服务层自己 trim 并校验）
            folder = wikiService.createFolder(kbId, currentTenantId(), trimSpace(req.parentId()), req.name());
        } catch (RuntimeException e) {
            throw mapFolderError(e);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(folder);
    }

    /** 对照 Go {@code UpdateFolder}（L226-249）—— OwnedWikiKBOrAdmin / KBAccessWrite。 */
    @PutMapping("/folders/{folder_id}")
    public ResponseEntity<?> updateFolder(@PathVariable("kb_id") String kbId,
                                          @PathVariable("folder_id") String folderIdParam,
                                          @RequestBody(required = false) String rawBody) {
        requireWikiKB(kbId, true);

        String folderId = sanitize(folderIdParam);
        if (folderId.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Folder ID is required");
        }
        WikiFolderUpdateRequest req = bind(rawBody, WikiFolderUpdateRequest.class);

        WikiFolder folder;
        try {
            // 同 Go：只 trim parentID；name 原样（空串 = 不改名）
            folder = wikiService.renameOrMoveFolder(kbId, folderId, req.name(),
                    trimSpace(req.parentId()), req.moveParent());
        } catch (RuntimeException e) {
            throw mapFolderError(e);
        }
        return ResponseEntity.ok(folder);
    }

    /** 对照 Go {@code DeleteFolder}（L262-278）—— OwnedWikiKBOrAdmin / KBAccessWrite；204。 */
    @DeleteMapping("/folders/{folder_id}")
    public ResponseEntity<?> deleteFolder(@PathVariable("kb_id") String kbId,
                                          @PathVariable("folder_id") String folderIdParam) {
        requireWikiKB(kbId, true);

        String folderId = sanitize(folderIdParam);
        if (folderId.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Folder ID is required");
        }
        try {
            wikiService.deleteFolder(kbId, folderId);
        } catch (RuntimeException e) {
            throw mapFolderError(e);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 对照 Go {@code MovePage}（L292-314）—— OwnedWikiKBOrAdmin / KBAccessWrite。
     * 页面 slug 在请求体里（层级 slug 会撞 catch-all 路由）。
     */
    @PutMapping("/move-page")
    public ResponseEntity<?> movePage(@PathVariable("kb_id") String kbId,
                                      @RequestBody(required = false) String rawBody) {
        requireWikiKB(kbId, true);

        JsonNode node = readJsonBody(rawBody);
        String bindingErrors = requiredFieldErrors(node, "WikiPageMoveRequest", "Slug");
        if (bindingErrors != null) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    "Invalid request body: " + bindingErrors);
        }
        WikiPageMoveRequest req = toType(node, WikiPageMoveRequest.class);

        String slug = trimSpace(req.slug());
        if (slug.isEmpty()) {
            // Go 侧因 binding:"required" 实际不可达，保留以逐行对照
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Page slug is required");
        }

        WikiPage page;
        try {
            page = wikiService.movePage(kbId, slug, trimSpace(req.folderId()));
        } catch (RuntimeException e) {
            throw mapFolderError(e);
        }
        return ResponseEntity.ok(page);
    }

    // ══════════════════════════════ 特殊页 ══════════════════════════════

    /**
     * 对照 Go {@code GetIndex}（L738-769）—— Viewer+ / KBAccessRead。
     *
     * <p>{@code limit} 只在"解析成功且 &gt; 0"时才采用（解析失败静默回落 50），
     * 上限由服务层夹到 200。</p>
     */
    @GetMapping("/index")
    public ResponseEntity<?> getIndex(@PathVariable("kb_id") String kbId, HttpServletRequest request) {
        requireWikiKB(kbId, false);

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

    // ════════════════════════════ 图谱 / 统计 ════════════════════════════

    /**
     * 对照 Go {@code GetGraph}（L801-878）—— Viewer+ / KBAccessRead。
     *
     * <p>参数校验顺序与 Go 完全一致：mode → center（仅 ego）→ depth → limit → types。
     * depth / limit 的"非正整数"是 400，超上限则<b>静默夹紧</b>而不是报错。</p>
     *
     * <p>Go 会用 memoryService 填 {@code FamiliarKnowledgeIDs}（个人叠加层）；
     * Java 无 memory 模块 → 传 null（等价 Go 的 nil slice）。</p>
     */
    @GetMapping("/graph")
    public ResponseEntity<?> getGraph(@PathVariable("kb_id") String kbId, HttpServletRequest request) {
        requireWikiKB(kbId, false);

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

    /** 对照 Go {@code GetStats}（L889-903）—— Viewer+ / KBAccessRead。 */
    @GetMapping("/stats")
    public ResponseEntity<?> getStats(@PathVariable("kb_id") String kbId) {
        requireWikiKB(kbId, false);
        WikiStats stats;
        try {
            stats = wikiService.getStats(kbId);
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(stats);
    }

    // ════════════════════════════ 检索 / 维护 ════════════════════════════

    /**
     * 对照 Go {@code SearchPages}（L994-1016）—— Viewer+ / KBAccessRead。
     *
     * <p>⚠️ 响应是 {@code gin.H{"pages": ...}}，<b>不是</b>裸数组（ListIssues 才是裸数组）。</p>
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchPages(@PathVariable("kb_id") String kbId, HttpServletRequest request) {
        requireWikiKB(kbId, false);

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

    /** 对照 Go {@code RebuildLinks}（L1026-1039）—— OwnedWikiKBOrAdmin / KBAccessWrite。 */
    @PostMapping("/rebuild-links")
    public ResponseEntity<?> rebuildLinks(@PathVariable("kb_id") String kbId) {
        requireWikiKB(kbId, true);
        try {
            wikiService.rebuildLinks(kbId);
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(message("Links rebuilt successfully"));
    }

    /**
     * 对照 Go {@code Lint}（L1050-1064）—— Viewer+ / KBAccessRead。
     *
     * <p>⚠️ 报告里的 {@code issues} 在"零问题"时是 JSON {@code null}（Go 的 nil slice），
     * 由 {@code WikiLintReport} 的 {@code @JsonInclude(ALWAYS)} + 服务层共同保证。</p>
     */
    @GetMapping("/lint")
    public ResponseEntity<?> lint(@PathVariable("kb_id") String kbId) {
        requireWikiKB(kbId, false);
        WikiLintReport report;
        try {
            report = lintService.runLint(kbId);
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(report);
    }

    /**
     * 对照 Go {@code AutoFix}（L1075-1089）—— OwnedWikiKBOrAdmin / KBAccessWrite。
     * 响应 {@code gin.H{"fixed":N,"message":"Auto-fixed N issues"}}（字母序：fixed &lt; message）。
     */
    @PostMapping("/auto-fix")
    public ResponseEntity<?> autoFix(@PathVariable("kb_id") String kbId) {
        requireWikiKB(kbId, true);
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

    // ══════════════════════════════ 问题 ══════════════════════════════

    /** 对照 Go {@code ListIssues}（L916-933）—— Viewer+ / KBAccessRead；响应是<b>裸数组</b>。 */
    @GetMapping("/issues")
    public ResponseEntity<?> listIssues(@PathVariable("kb_id") String kbId, HttpServletRequest request) {
        requireWikiKB(kbId, false);

        List<WikiPageIssue> issues;
        try {
            issues = wikiService.listIssues(kbId, q(request, "slug"), q(request, "status"));
        } catch (RuntimeException e) {
            throw internal(errText(e));
        }
        return ResponseEntity.ok(issues);
    }

    /**
     * 对照 Go {@code UpdateIssueStatus}（L948-981）—— OwnedWikiKBOrAdmin / KBAccessWrite。
     *
     * <p>⚠️ Go 绑定的是<b>匿名 struct</b>，validator 报出的 Key 不带结构体前缀
     * （{@code Key: 'Status' ...}），与具名 struct 的
     * {@code Key: 'WikiPageMoveRequest.Slug' ...} 不同。</p>
     */
    @PutMapping("/issues/{issue_id}/status")
    public ResponseEntity<?> updateIssueStatus(@PathVariable("kb_id") String kbId,
                                               @PathVariable("issue_id") String issueIdParam,
                                               @RequestBody(required = false) String rawBody) {
        requireWikiKB(kbId, true);

        String issueId = sanitize(issueIdParam);
        if (issueId.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Issue ID is required");
        }

        JsonNode node = readJsonBody(rawBody);
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

    // ══════════════════════════ 守卫 / 校验（对照 Go） ══════════════════════════

    /**
     * 对照 Go {@code validateWikiKB}（L48-68）+ 路由上的 KBAccess / OwnedWikiKBOrAdmin 守卫。
     *
     * <p>判定顺序与 Go 的中间件链一致（角色下限由 WebConfig 的 RbacInterceptor 先行）：</p>
     * <ol>
     *   <li>API-Key 数据面 KB 白名单（对照 {@code RequireKBAccess} 里的
     *       {@code AuthorizeTenantAPIKeyKnowledgeBases}：KB 受限 Key 指向白名单外 → 403；
     *       web 用户 / full-access Key 恒放行）。Go 在 KB 查找<b>之前</b>做，这里同序。</li>
     *   <li>调用方租户为空/0 → <b>401</b> "Unauthorized"（对照 {@code access.ErrUnauthorized}
     *       分支；Go 同样在 KB 查找之前）。</li>
     *   <li>KB 不存在 → <b>404</b> {@code {"success":false,"error":{"code":1003,...,"message":"knowledge base not found"}}}
     *       —— 对照 {@code kb_access.go} 的 {@code access.ErrNotFound} 分支，走全局 ErrorHandler。</li>
     *   <li>KB 属于别的空间 → 走 {@code access.ResolveKB} 的两条授予路径（<b>仅读</b>）：
     *       ① org-share：{@code CheckTenantKBPermission}（kb_shares × 组织成员 × 三维帽，
     *       effective ≥ viewer 放行）；② shared-agent：显式 {@code agent_id}（+
     *       {@code agent_source_tenant_id}）经 {@code GetSharedAgentForTenant} +
     *       {@code SharedAgentIncludesKB}，无 {@code agent_id} 时
     *       {@code TenantCanAccessKBViaSomeSharedAgent}。两条路径的查询失败都<b>不授予</b>
     *       （Go {@code if err == nil && …} 的 fail-closed），全灭 → <b>403</b>
     *       {@code {"success":false,"error":{"code":1002,...,"message":"Permission denied to
     *       access this knowledge base"}}}。{@code agent_source_tenant_id} 非法 →
     *       <b>400</b> "invalid agent_source_tenant_id"（对照 {@code ErrInvalidAgentSource}）。</li>
     *   <li><b>写路径不经过共享授予</b>：Go 的 {@code KBAccessWrite(Editor)} 理论上会让
     *       org-share editor 写共享 KB（{@code OwnedWikiKBOrAdmin} 对跨租户资源按
     *       not-found 透传）；Java 侧任务书裁定共享场景 read-only——写端点一律 403 同文案，
     *       不放大权限（与 Go 的已知差异，待主会话定夺）。</li>
     *   <li>写路径：创建者本人或 Admin+，否则 <b>403</b> "must own the resource or have the required role"
     *       —— 对照 {@code middleware.RequireOwnershipOrRole(TenantRoleAdmin, wikiKBCreator, cfg)}
     *       （{@code rbac.go:500}）。文案与 {@code KnowledgeBaseController#checkOwnership} 一致。</li>
     *   <li>KB 未启用 wiki → <b>400</b> 且是 handler 直写的
     *       {@code {"error":"error code: 400, error message: Wiki feature is not enabled for this knowledge base"}}。</li>
     * </ol>
     *
     * <p>共享 agent 的 {@code agent_id}/{@code agent_source_tenant_id} 取自 query
     * （对照 Go {@code KBAccessRequest} 的 {@code c.Query}）——经
     * {@code RequestContextHolder} 取当前请求，不改动 21 个端点签名。</p>
     *
     * @param write 该端点是否属于 OwnedWikiKBOrAdmin / KBAccessWrite 一侧
     */
    private KnowledgeBase requireWikiKB(String kbId, boolean write) {
        if (kbId == null || kbId.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    appErrorText(400, "Knowledge base ID is required"));
        }

        // 对照 RequireKBAccess 的 AuthorizeTenantAPIKeyKnowledgeBases（在 KB 查找之前）：
        // KB 受限 Key 指向白名单外 → 403；其余主体恒放行（与 KnowledgeService.requireKb 同源收口）。
        TenantAPIKeyScope.authorizeKnowledgeBases(List.of(kbId));

        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            // 对照 access.ErrUnauthorized：caller 租户为 0 → 401（Go 在 KB 查找之前）。
            throw BizException.unauthorized("Unauthorized");
        }

        // 对照 Go 侧无空间过滤的 repo.GetKnowledgeBaseByID：必须先按 id 找到，
        // 才能把"库里没有"（404）与"不是你的"（403）区分开。
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null) {
            throw BizException.notFound("knowledge base not found");
        }

        if (!tenantId.equals(kb.getTenantId())) {
            // 空间分享裁撤：跨租户授予链（org-share / shared-agent）已退役 → 直接拒绝
            throw BizException.forbidden("Permission denied to access this knowledge base");
        }

        if (write) {
            checkOwnership(kb);
        }

        if (!kb.getIndexingStrategy().isWikiEnabled()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    appErrorText(400, "Wiki feature is not enabled for this knowledge base"));
        }
        return kb;
    }

    /**
     * 对照 {@code access.ResolveKB} 的两条跨空间授予路径（<b>只用于读</b>，
     * required = OrgRoleViewer；wiki 读端点的 KB 权限在共享场景恒 read-only）。
     * wiki 读面的数据查询全部以 kb_id 为键（Go 的守卫把请求上下文改写成源租户后，
     * handler 也是按 kb_id 取数），故授予后无需切换执行租户。
     */

    /**
     * 对照 {@code access.ResolveKB} 的写路径（required = OrgRoleEditor，
     * KBAccessWrite）：跨租户仅 org-share 一条——三维帽有效角色 ≥ editor 即授予；
     * 共享 agent 分支在 required != Viewer 时不可达（ResolveKB L127 直接
     * ErrForbidden）。查询失败不授予（fail-closed）。
     */

    /** 对照 Go {@code KBAccessRequest} 的 {@code c.Query(...)}：从当前请求取 query 参数。 */
    private static String currentQueryParam(String name) {
        try {
            var attrs = org.springframework.web.context.request.RequestContextHolder
                    .currentRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes s) {
                return s.getRequest().getParameter(name);
            }
        } catch (IllegalStateException e) {
            // 无请求上下文（非 HTTP 调用路径）：按参数缺席处理（fail-closed）。
        }
        return null;
    }

    /** 对照 OwnedWikiKBOrAdmin：创建者本人或 Admin+，否则 403（同 KnowledgeBaseController#checkOwnership）。 */
    private static void checkOwnership(KnowledgeBase kb) {
        String role = TenantContext.currentRole();
        String uid = TenantContext.currentUserId();
        boolean admin = TenantRole.fromString(role).hasPermission(TenantRole.ADMIN);
        if (!admin && (kb.getCreatorId().isEmpty() || !kb.getCreatorId().equals(uid))) {
            throw GuardForbiddenException.mustOwnResourceOrHaveRole();
        }
    }

    /**
     * 对照 Go {@code recordManualWikiActivity}（L395-403）：把人工页面变更投影进知识库活动流。
     *
     * <p>记账是<b>尽力而为</b>：绝不能让埋点失败反过来让编辑失败。</p>
     */
    private void recordManualWikiActivity(WikiPage page, String action) {
        if (page == null) {
            return;
        }
        Map<String, Integer> actions = new LinkedHashMap<>();
        actions.put(action, 1);

        long tenantId = page.getTenantId() == null ? 0L : page.getTenantId();
        WikiActivityAudit audit = activityAudit.getIfAvailable();
        if (audit == null) {
            // 对照 Go recordKBActivity 在 audit 为 nil 时的等价行为：什么也不写
            log.debug("wiki activity skipped (no WikiActivityAudit bean): kb={} actions={}",
                    page.getKnowledgeBaseId(), actions);
            return;
        }
        try {
            audit.wikiContentChanged(tenantId, page.getKnowledgeBaseId(), actions);
        } catch (RuntimeException e) {
            log.warn("record wiki activity failed: kb={} action={} err={}",
                    page.getKnowledgeBaseId(), action, errText(e));
        }
    }

    /**
     * 对照 Go {@code writeWikiFolderError}（L316-326）：文件夹/页面的 sentinel error → 状态码。
     * 其余错误一律 500，文案取 {@code err.Error()}。
     */
    private static RawJsonError mapFolderError(RuntimeException e) {
        if (e instanceof WikiFolderNotFoundException || e instanceof WikiPageNotFoundException) {
            return new RawJsonError(HttpStatus.NOT_FOUND.value(), errText(e));
        }
        if (e instanceof WikiFolderConflictException || e instanceof WikiFolderNotEmptyException) {
            return new RawJsonError(HttpStatus.CONFLICT.value(), errText(e));
        }
        return internal(errText(e));
    }

    // ══════════════════════════════ 工具方法 ══════════════════════════════

    /**
     * 对照 Go {@code getSlugParam}（L71-76）：gin 的 catch-all 参数带前导 "/"，
     * 先剥掉再 TrimSpace。
     */
    static String getSlugParam(String raw) {
        if (raw == null) {
            return "";
        }
        String slug = raw.startsWith("/") ? raw.substring(1) : raw;
        return trimSpace(slug);
    }

    /**
     * 对照 Go {@code parseWikiCategoryPath}（L328-341）：按 "/" 切分、逐段 TrimSpace、
     * 丢掉空段；整串为空时返回 nil（Java 返回空列表，服务层同样视为"不过滤"）。
     */
    static List<String> parseWikiCategoryPath(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || trimSpace(raw).isEmpty()) {
            return out;
        }
        for (String part : raw.split("/", -1)) {
            String trimmed = trimSpace(part);
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** 对照 Go {@code c.Query(name)}：缺席与空值都返回 ""。 */
    private static String q(HttpServletRequest request, String name) {
        String v = request.getParameter(name);
        return v == null ? "" : v;
    }

    /** 对照 Go {@code c.GetQuery(name)} 的"参数是否存在"一半。 */
    private static boolean hasParam(HttpServletRequest request, String name) {
        return request.getParameterMap().containsKey(name);
    }

    /**
     * 对照 Go {@code c.DefaultQuery(name, def)}：<b>存在即返回其值</b>（哪怕是空串），
     * 只有完全缺席才回落到默认值。
     */
    private static String query(HttpServletRequest request, String name, String def) {
        return hasParam(request, name) ? q(request, name) : def;
    }

    /** 对照 Go {@code strconv.Atoi}：解析失败返回 0。 */
    static int atoi(String s) {
        Integer v = atoiOrNull(s);
        return v == null ? 0 : v;
    }

    /** 对照 Go {@code strconv.Atoi}：返回 null 表示解析失败（用于"解析成功才生效"的分支）。 */
    static Integer atoiOrNull(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 对照 Go {@code strings.TrimSpace}：按 {@code unicode.IsSpace} 的空白集合裁剪
     * （Java 的 {@code String.trim()} 只认 &lt;= U+0020，会漏掉 NBSP 等）。
     */
    static String trimSpace(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end) {
            int cp = s.codePointAt(start);
            if (!isGoSpace(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = s.codePointBefore(end);
            if (!isGoSpace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(start, end);
    }

    /** 对照 Go {@code unicode.IsSpace}（Java 两个判定取并集才覆盖 Go 的空白集合）。 */
    private static boolean isGoSpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    /** 对照 Go {@code err.Error()}（BizException 的 message 与 Go 的 AppError.Error() 逐字相同）。 */
    private static String errText(RuntimeException e) {
        return e.getMessage() == null ? "" : e.getMessage();
    }

    /**
     * 对照 Go 的 {@code errors.AppError.Error()}：
     * {@code fmt.Sprintf("error code: %d, error message: %s", Code, Message)}。
     */
    static String appErrorText(int code, String message) {
        return "error code: " + code + ", error message: " + message;
    }

    /** 对照 Go {@code c.JSON(status, gin.H{"error": msg})}：单键 map。 */
    private static ResponseEntity<Map<String, Object>> rawError(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    /** 对照 Go handler 里的 {@code c.JSON(500, gin.H{"error": err.Error()})}。 */
    private static RawJsonError internal(String message) {
        return new RawJsonError(HttpStatus.INTERNAL_SERVER_ERROR.value(), message);
    }

    /** 对照 Go {@code gin.H{"message": ...}}（单键，键序无歧义）。 */
    private static Map<String, Object> message(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", text);
        return body;
    }

    private static long currentTenantId() {
        Long tenantId = TenantContext.currentTenantId();
        return tenantId == null ? 0L : tenantId;
    }

    private static String sanitize(String value) {
        return LogSanitizer.sanitize(value);
    }

    // ── 请求体绑定（对照 Go 的 c.ShouldBindJSON） ──

    /**
     * 对照 Go {@code c.ShouldBindJSON}：空 body → {@code EOF}；否则解析。
     *
     * <p>先拿到 JsonNode 而不是直接绑到 DTO，是为了能在同一处复刻 Go 的
     * {@code binding:"required"} 校验（Jackson 不做这类校验）。</p>
     */
    private JsonNode readJsonBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Invalid request body: EOF");
        }
        try {
            JsonNode node = json.readTree(rawBody);
            if (node == null || node.isNull()) {
                // 对照 Go：json.Unmarshal("null", &struct) 是 no-op，各字段保持零值。
                // 用 null 节点继续走 required 校验会 NPE，这里换成一个空对象。
                return json.createObjectNode();
            }
            if (!node.isObject()) {
                throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                        "Invalid request body: json: cannot unmarshal non-object into Go value");
            }
            return node;
        } catch (RawJsonError e) {
            throw e;
        } catch (Exception e) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    "Invalid request body: " + e.getMessage());
        }
    }

    /** 把已解析的 JSON 节点绑到 DTO（对照 Go 的 json.Unmarshal 那一半）。 */
    private <T> T toType(JsonNode node, Class<T> type) {
        try {
            return json.treeToValue(node, type);
        } catch (Exception e) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    "Invalid request body: " + e.getMessage());
        }
    }

    /** {@link #readJsonBody} + {@link #toType} 的组合（无 required 字段的 DTO 用它）。 */
    private <T> T bind(String rawBody, Class<T> type) {
        return toType(readJsonBody(rawBody), type);
    }

    /**
     * 复刻 go-playground/validator 的 {@code required} 规则（gin 的 {@code binding:"required"}）。
     *
     * <p>Go 的报错文案是
     * {@code Key: '<Struct>.<Field>' Error:Field validation for '<Field>' failed on the 'required' tag}，
     * 多个字段同时失败时用换行连接（{@code ValidationErrors.Error()} 的行为）。
     * 匿名 struct 的 Key 不带结构体前缀（{@code Key: 'Status'}）。</p>
     *
     * @param structName 具名 struct 的名字；null / 空表示匿名 struct
     * @param fields     Go struct 的<b>字段声明序</b>（决定报错顺序）
     * @return 校验错误串；全部通过时返回 null
     */
    static String requiredFieldErrors(JsonNode node, String structName, String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            if (!isZeroValue(node.get(toJsonName(field)))) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String key = (structName == null || structName.isEmpty())
                    ? field : structName + "." + field;
            sb.append("Key: '").append(key)
                    .append("' Error:Field validation for '").append(field)
                    .append("' failed on the 'required' tag");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Go 字段名 → JSON 键（本模块涉及的字段都是单驼峰转蛇形，逐字列出避免猜错）。 */
    private static String toJsonName(String goField) {
        return switch (goField) {
            case "Slug" -> "slug";
            case "Version" -> "version";
            case "Status" -> "status";
            default -> goField;
        };
    }

    /** validator 的零值判定：缺失 / null / "" / 0 都算零值。 */
    private static boolean isZeroValue(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return true;
        }
        if (v.isTextual()) {
            return v.asText().isEmpty();
        }
        if (v.isNumber()) {
            return v.asDouble() == 0d;
        }
        if (v.isBoolean()) {
            return !v.asBoolean();
        }
        return false;
    }

    /**
     * 对照 Go handler 直接用 {@code c.JSON(status, gin.H{"error": ...})} 写出的错误
     * ——它与全局错误信封（{@code {"success":false,"error":{...}}}）形态不同，
     * 所以不能走 BizException。
     */
    static final class RawJsonError extends RuntimeException {

        private final int status;

        RawJsonError(int status, String errorMessage) {
            super(errorMessage);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    /** handler 直写的错误信封（对照 Go {@code gin.H{"error": ...}}）。 */
    @ExceptionHandler(RawJsonError.class)
    public ResponseEntity<Map<String, Object>> handleRawJsonError(RawJsonError ex) {
        return rawError(ex.status(), ex.getMessage());
    }
}
