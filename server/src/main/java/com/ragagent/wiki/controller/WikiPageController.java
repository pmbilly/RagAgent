package com.ragagent.wiki.controller;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.wiki.domain.WikiActivityAudit;
import com.ragagent.wiki.service.page.WikiLintService;
import com.ragagent.wiki.service.page.WikiPageService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * "own / org-shared / via shared agent" 解析，故 <b>KB 访问与所有权判定由 {@link WikiKbAccessGuard} 承接</b>，角色下限仍由 WebConfig 注册的规则负责。跨空间的两条授予路径
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
 */
@RestController
@RequestMapping("/api/v1/knowledgebase/{kb_id}/wiki")
public class WikiPageController {

    private static final Logger log = LoggerFactory.getLogger(WikiPageController.class);

    private final WikiKbAccessGuard kbGuard;
    private final WikiFolderOps folderOps;
    private final WikiPageOps pageOps;
    private final WikiStatsOps statsOps;
    private final WikiMaintenanceOps maintenanceOps;

    public WikiPageController(WikiPageService wikiService,
                              WikiLintService lintService,
                              KnowledgeBaseMapper kbMapper,
                              ObjectMapper json,
                              ObjectProvider<WikiActivityAudit> activityAudit) {
        this.kbGuard = new WikiKbAccessGuard(kbMapper);
        this.pageOps = new WikiPageOps(wikiService, kbGuard,
                new WikiActivityRecorder(activityAudit), json);
        this.folderOps = new WikiFolderOps(wikiService, kbGuard, json);
        this.statsOps = new WikiStatsOps(wikiService, kbGuard);
        this.maintenanceOps = new WikiMaintenanceOps(wikiService, lintService, kbGuard, json);
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

    @GetMapping("/index")
    public ResponseEntity<?> getIndex(@PathVariable("kb_id") String kbId, HttpServletRequest request) {
        return statsOps.getIndex(kbId, request);
    }

    // ════════════════════════════ 图谱 / 统计 ════════════════════════════

    @GetMapping("/graph")
    public ResponseEntity<?> getGraph(@PathVariable("kb_id") String kbId, HttpServletRequest request) {
        return statsOps.getGraph(kbId, request);
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats(@PathVariable("kb_id") String kbId) {
        return statsOps.getStats(kbId);
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchPages(@PathVariable("kb_id") String kbId, HttpServletRequest request) {
        return statsOps.searchPages(kbId, request);
    }

    // ════════════════════════════ 检索 / 维护 ════════════════════════════

    @PostMapping("/rebuild-links")
    public ResponseEntity<?> rebuildLinks(@PathVariable("kb_id") String kbId) {
        return maintenanceOps.rebuildLinks(kbId);
    }

    @GetMapping("/lint")
    public ResponseEntity<?> lint(@PathVariable("kb_id") String kbId) {
        return maintenanceOps.lint(kbId);
    }

    @PostMapping("/auto-fix")
    public ResponseEntity<?> autoFix(@PathVariable("kb_id") String kbId) {
        return maintenanceOps.autoFix(kbId);
    }

    // ══════════════════════════════ 问题 ══════════════════════════════

    @GetMapping("/issues")
    public ResponseEntity<?> listIssues(@PathVariable("kb_id") String kbId, HttpServletRequest request) {
        return maintenanceOps.listIssues(kbId, request);
    }

    @PutMapping("/issues/{issue_id}/status")
    public ResponseEntity<?> updateIssueStatus(@PathVariable("kb_id") String kbId,
                                               @PathVariable("issue_id") String issueIdParam,
                                               @RequestBody(required = false) String rawBody) {
        return maintenanceOps.updateIssueStatus(kbId, issueIdParam, rawBody);
    }

    // ══════════════════════════ 守卫 / 校验 ══════════════════════════



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
        return WikiRequestSupport.rawError(ex.status(), ex.getMessage());
    }
}
