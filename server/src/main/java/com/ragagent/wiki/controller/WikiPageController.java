package com.ragagent.wiki.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.wiki.domain.WikiActivityAudit;
import com.ragagent.wiki.domain.WikiGraph;
import com.ragagent.wiki.domain.WikiIndex;
import com.ragagent.wiki.domain.WikiLintReport;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageIssue;
import com.ragagent.wiki.domain.WikiStats;
import com.ragagent.wiki.service.page.WikiLintService;
import com.ragagent.wiki.service.page.WikiPageService;
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
 * Wiki 页面的 HTTP 层。
 *
 * <p><b>响应形态（逐端点保持原样，不要"统一"它们）</b>：</p>
 * <ol>
 *   <li><b>实体直出</b>：直接序列化领域对象，
 *       键序 = 领域字段声明序（由各 domain 类的 {@code @JsonPropertyOrder} 固定），
 *       <b>没有</b> {@code {data,success}} 信封。wiki 层几乎所有端点都是这个形态。</li>
 *   <li><b>raw JSON map 直出</b>：{@code {"message":...}}（UpdateIssueStatus / RebuildLinks）、
 *       {@code {"fixed":N,"message":...}}（AutoFix）、{@code {"pages":[...]}}（SearchPages）、
 *       {@code {"current_version":N,"error":...}}（UpdatePage 的乐观锁冲突）。
 *       原实现的 map 序列化按<b>键字母序</b>输出，故 Java 用 LinkedHashMap 按字母序插入。</li>
 *   <li><b>裸数组</b>：ListIssues 的响应就是问题数组本身。</li>
 *   <li><b>handler 直接写的错误</b>：单键 map，形态是 {@code {"error":"..."}}，与全局错误信封
 *       {@code {"success":false,"error":{code,details,message}}} <b>不同</b>。
 *       21 个端点里除"KB 访问被拒"外全部走这一形态（见下）。</li>
 *   <li><b>守卫写的错误</b>：KB 访问拒绝走全局错误处理，形态是
 *       {@code {"success":false,"error":{...}}}。
 *       Java 侧对应 {@link BizException}（GlobalExceptionHandler 逐字节对齐原实现）。</li>
 * </ol>
 *
 * <p><b>⚠️ 错误文案的前缀</b>：KB 校验失败写出去的错误文案带
 * {@code "error code: %d, error message: %s"} 前缀，<b>不是</b>裸消息。
 * 例：KB 未启用 wiki 时客户端收到的是
 * {@code {"error":"error code: 400, error message: Wiki feature is not enabled for this knowledge base"}}
 * 且 HTTP 状态是 <b>400</b>。Java 的 {@code BizException.getMessage()} 恰好是同一格式
 * （见 {@link BizException#BizException}），因此这里复用同一文案函数。</p>
 *
 * <p><b>守卫矩阵</b>（角色下限在 WebConfig 里注册）：</p>
 * <pre>
 * 读端点：Viewer+ 角色 + KB 读权限  （同租户可读；跨租户经 org-share / shared-agent 只读授予）
 * 写端点：创建者本人或 Admin+（否则 403）+ KB 写权限
 * </pre>
 * <p>Java 的 {@code RbacInterceptor} 只能表达"角色下限"，无法表达 KB 访问的
 * "own / org-shared / via shared agent" 解析，故 <b>KB 访问与所有权判定由 {@link WikiKbAccessGuard} 承接</b>
 * （门面 {@link #requireWikiKB} 薄委托），角色下限仍由 WebConfig 注册的规则负责。跨空间的两条授予路径
 * 复用 org 模块已落地的积木（{@code com.ragagent.org} 包的 {@code KbShareService} /
 * {@code AgentShareService} / {@code SharedAgentKBScope}）。</p>
 *
 * <p><b>⚠️ 通配 slug</b>：catch-all 路径参数捕获值<b>带前导 "/"</b>，
 * 取用前要先剥掉再 TrimSpace 清洗，见 {@link #getSlugParam}。</p>
 *
 * <p><b>已知差异</b>：</p>
 * <ul>
 *   <li>图谱的"熟悉知识"叠加层（FamiliarKnowledgeIDs）无对应模块 → 该字段恒为 null
 *       （等价原实现的空值分支）。</li>
 *   <li>审计埋点由 {@link WikiActivityAudit} 接缝承接；实现 bean
 *       （{@code com.ragagent.audit.service.WikiActivityAuditRecorder}）已随审计模块
 *       翻译落地，缺失时才退化为 debug 日志。</li>
 *   <li>原实现的 nil 切片序列化成 {@code null}，Java 侧沿用既有 DTO/服务层的"空列表"归一
 *       （ListIssues / SearchPages / ListPages 的空结果）。</li>
 *   <li>请求体 JSON 语法错误用 Jackson 的消息（与原实现的 JSON 库文案不同，
 *       约定 §9 阶段 1 已记录的同类差异）。</li>
 *   <li><b>写路径与原实现对齐</b>：跨租户 creator 查不到 → 透传，org-share 的
 *       Editor 角色可写；共享 agent 分支对 Editor 不可达。同租户写仍走创建者/Admin+。</li>
 * </ul>
 *
 * <p>例外说明(§14.5):1,342 行超 800 硬顶——全部端点为 raw-JSON 对齐形态,
 * wiki 域契约换锚时将整体重写为 DTO 端点,当前不做结构重构。</p>
 */
@RestController
@RequestMapping("/api/v1/knowledgebase/{kb_id}/wiki")
public class WikiPageController {

    private static final Logger log = LoggerFactory.getLogger(WikiPageController.class);

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
    private final WikiLintService lintService;
    private final WikiKbAccessGuard kbGuard;
    private final ObjectMapper json;
    private final WikiFolderOps folderOps;
    private final WikiPageOps pageOps;

    public WikiPageController(WikiPageService wikiService,
                              WikiLintService lintService,
                              KnowledgeBaseMapper kbMapper,
                              ObjectMapper json,
                              ObjectProvider<WikiActivityAudit> activityAudit) {
        this.wikiService = wikiService;
        this.lintService = lintService;
        this.kbGuard = new WikiKbAccessGuard(kbMapper);
        this.json = json;
        this.pageOps = new WikiPageOps(wikiService, kbGuard,
                new WikiActivityRecorder(activityAudit), json);
        this.folderOps = new WikiFolderOps(wikiService, kbGuard, json);
    }

    // ════════════════════════════ 页面 CRUD ════════════════════════════

    @GetMapping("/pages")
    public ResponseEntity<?> listPages(@PathVariable("kb_id") String kbId, HttpServletRequest request) {
        return pageOps.listPages(kbId, request);
    }

    @PostMapping("/pages")
    public ResponseEntity<?> createPage(@PathVariable("kb_id") String kbId,
                                        @RequestBody(required = false) String rawBody) {
        return pageOps.createPage(kbId, rawBody);
    }

    @GetMapping("/pages/{*slug}")
    public ResponseEntity<?> getPage(@PathVariable("kb_id") String kbId,
                                     @PathVariable(value = "slug", required = false) String slugParam) {
        return pageOps.getPage(kbId, slugParam);
    }

    @PutMapping("/pages/{*slug}")
    public ResponseEntity<?> updatePage(@PathVariable("kb_id") String kbId,
                                        @PathVariable(value = "slug", required = false) String slugParam,
                                        @RequestBody(required = false) String rawBody) {
        return pageOps.updatePage(kbId, slugParam, rawBody);
    }

    @DeleteMapping("/pages/{*slug}")
    public ResponseEntity<?> deletePage(@PathVariable("kb_id") String kbId,
                                        @PathVariable(value = "slug", required = false) String slugParam) {
        return pageOps.deletePage(kbId, slugParam);
    }

    // ════════════════════════════ 修订历史 ════════════════════════════

    @GetMapping("/revisions/{*slug}")
    public ResponseEntity<?> listRevisions(@PathVariable("kb_id") String kbId,
                                           @PathVariable(value = "slug", required = false) String slugParam,
                                           HttpServletRequest request) {
        return pageOps.listRevisions(kbId, slugParam, request);
    }

    @PostMapping("/revert")
    public ResponseEntity<?> revertPage(@PathVariable("kb_id") String kbId,
                                        @RequestBody(required = false) String rawBody) {
        return pageOps.revertPage(kbId, rawBody);
    }

    // ════════════════════════════ 文件夹树 ════════════════════════════

    @GetMapping("/folders")
    public ResponseEntity<?> listFolders(@PathVariable("kb_id") String kbId, HttpServletRequest request) {
        return folderOps.listFolders(kbId, request);
    }

    @PostMapping("/folders")
    public ResponseEntity<?> createFolder(@PathVariable("kb_id") String kbId,
                                          @RequestBody(required = false) String rawBody) {
        return folderOps.createFolder(kbId, rawBody);
    }

    @PutMapping("/folders/{folder_id}")
    public ResponseEntity<?> updateFolder(@PathVariable("kb_id") String kbId,
                                          @PathVariable("folder_id") String folderIdParam,
                                          @RequestBody(required = false) String rawBody) {
        return folderOps.updateFolder(kbId, folderIdParam, rawBody);
    }

    @DeleteMapping("/folders/{folder_id}")
    public ResponseEntity<?> deleteFolder(@PathVariable("kb_id") String kbId,
                                          @PathVariable("folder_id") String folderIdParam) {
        return folderOps.deleteFolder(kbId, folderIdParam);
    }

    @PutMapping("/move-page")
    public ResponseEntity<?> movePage(@PathVariable("kb_id") String kbId,
                                      @RequestBody(required = false) String rawBody) {
        return folderOps.movePage(kbId, rawBody);
    }

    // ══════════════════════════════ 特殊页 ══════════════════════════════

    /**
     * 索引页——读端点（Viewer+ 角色 + KB 读权限）。
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
     * 图谱——读端点（Viewer+ 角色 + KB 读权限）。
     *
     * <p>参数校验顺序固定：mode → center（仅 ego）→ depth → limit → types。
     * depth / limit 的"非正整数"是 400，超上限则<b>静默夹紧</b>而不是报错。</p>
     *
     * <p>"熟悉知识"叠加层（FamiliarKnowledgeIDs）无对应模块 → 传 null
     * （等价原实现的空值分支）。</p>
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

    /** 统计——读端点（Viewer+ 角色 + KB 读权限）。 */
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
     * 检索——读端点（Viewer+ 角色 + KB 读权限）。
     *
     * <p>⚠️ 响应是包着一层 {@code "pages"} 键的对象，<b>不是</b>裸数组（ListIssues 才是裸数组）。</p>
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

    /** 重建链接——写端点（创建者/Admin+ + KB 写权限）。 */
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
     * 体检——读端点（Viewer+ 角色 + KB 读权限）。
     *
     * <p>⚠️ 报告里的 {@code issues} 在"零问题"时是 JSON {@code null}（与原实现的 nil 切片一致），
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
     * 自动修复——写端点（创建者/Admin+ + KB 写权限）。
     * 响应 {@code {"fixed":N,"message":"Auto-fixed N issues"}}（字母序：fixed &lt; message）。
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

    /** 问题列表——读端点（Viewer+ 角色 + KB 读权限）；响应是<b>裸数组</b>。 */
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
     * 更新问题状态——写端点（创建者/Admin+ + KB 写权限）。
     *
     * <p>⚠️ 这里的请求体按<b>匿名结构</b>校验：报错的 Key 不带结构体前缀
     * （{@code Key: 'Status' ...}），与具名结构的
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

    // ══════════════════════════ 守卫 / 校验 ══════════════════════════

    /** KB 访问与所有权判定——实现与完整判定矩阵见 {@link WikiKbAccessGuard#requireWikiKB}。 */
    private KnowledgeBase requireWikiKB(String kbId, boolean write) {
        return kbGuard.requireWikiKB(kbId, write);
    }


    // ══════════════════════════════ 工具方法（实现移至 WikiRequestSupport，门面薄委托） ══════════════════════════════

    static String getSlugParam(String raw) {
        return WikiRequestSupport.getSlugParam(raw);
    }

    static List<String> parseWikiCategoryPath(String raw) {
        return WikiRequestSupport.parseWikiCategoryPath(raw);
    }

    private static String q(HttpServletRequest request, String name) {
        return WikiRequestSupport.q(request, name);
    }

    private static boolean hasParam(HttpServletRequest request, String name) {
        return WikiRequestSupport.hasParam(request, name);
    }

    private static String query(HttpServletRequest request, String name, String def) {
        return WikiRequestSupport.query(request, name, def);
    }

    static int atoi(String s) {
        return WikiRequestSupport.atoi(s);
    }

    static Integer atoiOrNull(String s) {
        return WikiRequestSupport.atoiOrNull(s);
    }

    static String trimSpace(String s) {
        return WikiRequestSupport.trimSpace(s);
    }

    private static String errText(RuntimeException e) {
        return WikiRequestSupport.errText(e);
    }

    static String appErrorText(int code, String message) {
        return WikiRequestSupport.appErrorText(code, message);
    }

    private static ResponseEntity<Map<String, Object>> rawError(int status, String message) {
        return WikiRequestSupport.rawError(status, message);
    }

    private static RawJsonError internal(String message) {
        return WikiRequestSupport.internal(message);
    }

    private static Map<String, Object> message(String text) {
        return WikiRequestSupport.message(text);
    }

    private static long currentTenantId() {
        return WikiRequestSupport.currentTenantId();
    }

    private static String sanitize(String value) {
        return WikiRequestSupport.sanitize(value);
    }

    private static RawJsonError mapFolderError(RuntimeException e) {
        return WikiRequestSupport.mapFolderError(e);
    }

    private JsonNode readJsonBody(String rawBody) {
        return WikiRequestSupport.readJsonBody(json, rawBody);
    }

    private <T> T toType(JsonNode node, Class<T> type) {
        return WikiRequestSupport.toType(json, node, type);
    }

    private <T> T bind(String rawBody, Class<T> type) {
        return WikiRequestSupport.bind(json, rawBody, type);
    }

    static String requiredFieldErrors(JsonNode node, String structName, String... fields) {
        return WikiRequestSupport.requiredFieldErrors(node, structName, fields);
    }


    /**
     * handler 直写的 raw JSON 错误
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

    /** handler 直写的错误信封：{@code {"error": ...}}。 */
    @ExceptionHandler(RawJsonError.class)
    public ResponseEntity<Map<String, Object>> handleRawJsonError(RawJsonError ex) {
        return rawError(ex.status(), ex.getMessage());
    }
}
