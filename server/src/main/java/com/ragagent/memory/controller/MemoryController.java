package com.ragagent.memory.controller;

import com.ragagent.common.web.JsonMappers;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.memory.domain.MemoryConflictException;
import com.ragagent.memory.domain.MemoryConsolidationResult;
import com.ragagent.memory.domain.MemoryDocView;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.common.settings.MemoryKinds;
import com.ragagent.memory.domain.MemoryPage;
import com.ragagent.memory.domain.MemorySettings;
import com.ragagent.memory.domain.MemoryTopicView;
import com.ragagent.memory.dto.MemoryExportResponse;
import com.ragagent.memory.dto.MemoryListResponse;
import com.ragagent.memory.service.MemoryConsolidationService;
import com.ragagent.memory.service.MemoryScopeExceptions;
import com.ragagent.memory.service.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 长期记忆的 HTTP 层（对照 Go {@code internal/handler/memory.go} 全文，
 * 路由对照 {@code internal/router/routes_memory.go} 的 16 条）。
 *
 * <h2>为什么所有端点都没有 subject 参数</h2>
 * <p>每一条路由操作的都是从请求上下文里推导出来的记忆空间，所以没有任何端点接受
 * subject id。这是刻意的：它把"改个 id 能不能读到别人的记忆"这一整类缺陷
 * <b>从根上消掉</b>，而不是靠每条路由各自做一次所有权判定
 * （Go handler 的类注释原文）。Java 侧同理——{@code MemoryScopes.resolve()}
 * 只读 {@code TenantContext} / principal，不读任何请求参数。</p>
 *
 * <h2>响应形态：契约换锚后（2026-10-01，§14.9k M1）</h2>
 * <p>成功响应不再包 {@code {"data":…,"success":true}} 信封；JSON 字段名＝Java 字段名
 * （camelCase，见 {@code docs/knowledge-api-contract-v1.md} §1.1）。逐类形态：</p>
 * <ul>
 *   <li>单资源（settings / 条目 / 整理结果）→ <b>裸对象</b>；</li>
 *   <li>列表（items / topics / documents）→ {@code {items, page, pageSize, total}}
 *       （{@link com.ragagent.memory.dto.MemoryListResponse}，§2.1 分页形态）；</li>
 *   <li>创建条目 → <b>201</b> + 裸条目（§1.15）；删除 / 拒绝 → <b>204</b>（§1.13）；</li>
 *   <li>Export 保持下载语义：{@code {items, total, truncated}} + {@code Content-Disposition}。</li>
 * </ul>
 * <p>{@code Clear} 是同步删除，按 §1.13 返 204——旧 Go 的 {@code {"removed":N}} 计数随信封一并退役；
 * 前端不再展示条数（如需恢复，须先改标准）。</p>
 *
 * <h2>错误形态：AppError 信封，逐条对照 Go 的 {@code fail()}</h2>
 * <pre>
 *   NoScope          → 401 {"code":1001,…,"message":"no principal in request"}
 *   ItemNotFound     → 404 {"code":1003,…,"message":"memory not found"}
 *   MemoryConflict   → 409 err.Error()（domain 包，不是 service 包的那个）
 *   SensitiveContent → 400 err.Error()
 *   Disabled         → 400 "memory is disabled"
 *   其余（含 PreviouslyForgotten / EmptyContent）→ 500 + message(handler 传的) + details=err.Error()
 * </pre>
 * <p>{@code PreviouslyForgotten} 与 {@code EmptyContent} <b>刻意不在</b> switch 里——
 * 已对运行中的 Go dev server 实测（2026-09-18）：</p>
 * <pre>
 *   POST /memory/items {"content":"   "}
 *   → 500 {"error":{"code":1007,"details":"memory: empty content",
 *                   "message":"Failed to create memory"},"success":false}
 * </pre>
 *
 * <h2>分页</h2>
 * <p>{@code memoryListPaging} 是<b>容错</b>的：{@code limit} 非法、≤0 或 &gt;200 一律归 50，
 * {@code offset} 为负归 0。实测 {@code ?limit=abc&offset=-5} 返回 200 而不是 400。</p>
 */
@RestController
public class MemoryController {

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    /**
     * 对照 {@code memoryExportPageSize}：一页导出读多少行。
     */
    static final int EXPORT_PAGE_SIZE = 500;

    /**
     * 对照 {@code memoryExportMaxItems}：单次导出的硬上限，防止一个巨大的仓库
     * 把一次下载变成无界读。
     */
    static final int EXPORT_MAX_ITEMS = 20000;

    /**
     * 请求体解析器：<b>必须</b>忽略未知字段。
     *
     * <p>Go 的 {@code c.ShouldBindJSON} 走 {@code encoding/json}，默认忽略未知字段；
     * Jackson 的裸 {@code ObjectMapper} 默认<b>失败</b>（§7.5 第 6 条的同族坑，
     * 只是这次在请求方向）。前端多带一个字段就整条请求 400 是这里最不该发生的事。</p>
     */
    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final MemoryService memoryService;
    private final MemoryConsolidationService consolidationService;

    public MemoryController(MemoryService memoryService,
                            MemoryConsolidationService consolidationService) {
        this.memoryService = memoryService;
        this.consolidationService = consolidationService;
    }

    // ══════════════════════════ 设置 ══════════════════════════

    /** 对照 Go {@code GetSettings}（L39-47）。换锚后响应是裸 {@link MemorySettings}（camelCase）。 */
    @GetMapping("/api/v1/memory/settings")
    public MemorySettings getSettings() {
        try {
            return memoryService.getSettings();
        } catch (RuntimeException e) {
            throw fail(e, "Failed to load memory settings");
        }
    }

    /**
     * 对照 Go {@code UpdateSettings}（L63-84）。
     *
     * <p>两处 400 的门槛顺序有语义：先"请求体能不能解析"，
     * 再"enabled 在不在"——{@code {"enabled":null}} 落后者。</p>
     */
    @PutMapping("/api/v1/memory/settings")
    public MemorySettings updateSettings(
            @RequestBody(required = false) String rawBody) {
        UpdateMemorySettingsRequest req = parse(rawBody, UpdateMemorySettingsRequest.class);
        if (req == null || req.enabled() == null) {
            throw new BizException(AppError.badRequest("enabled is required"));
        }
        try {
            memoryService.setEnabled(req.enabled());
        } catch (RuntimeException e) {
            throw fail(e, "Failed to update memory settings");
        }
        return getSettings();
    }

    /** 对照 Go 的 {@code updateMemorySettingsRequest}：{@code Enabled *bool}。 */
    record UpdateMemorySettingsRequest(Boolean enabled) {
    }

    // ══════════════════════════ 条目 ══════════════════════════

    /**
     * 对照 Go {@code ListItems}（L97-119）。
     *
     * <p>{@code status} 的白名单校验发生在<b>解析分页之前</b>，顺序照抄：
     * 非法 status 一律 400，哪怕 limit 也是垃圾。</p>
     *
     * <p>空仓库输出 {@code "items":[]}（<b>不是</b> {@code null}）——与 Export 的空
     * {@code items:null} 仍是两种形态，别统一（那是 Go 两条路径的既有差别）。</p>
     */
    @GetMapping("/api/v1/memory/items")
    public MemoryListResponse<MemoryItem> listItems(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "offset", required = false) String offset) {
        if (!isSupportedStatus(status)) {
            throw new BizException(AppError.badRequest("unsupported status"));
        }
        int[] paging = listPaging(limit, offset);

        MemoryPage<MemoryItem> page;
        try {
            page = memoryService.listItems(status == null ? "" : status, paging[0], paging[1]);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to list memories");
        }
        return pageBody(page, paging);
    }

    /**
     * 对照 Go 的状态白名单：空串 + 四个状态常量，其余一律 {@code unsupported status}。
     */
    private static boolean isSupportedStatus(String status) {
        if (status == null) {
            return true;
        }
        return status.isEmpty()
                || MemoryKinds.STATUS_ACTIVE.equals(status)
                || MemoryKinds.STATUS_SUPERSEDED.equals(status)
                || MemoryKinds.STATUS_ARCHIVED.equals(status)
                || MemoryKinds.STATUS_PENDING.equals(status);
    }

    /** 对照 Go {@code CreateItem}（L262-275）。换锚后：<b>201</b> + 裸条目（§1.15）。 */
    @PostMapping("/api/v1/memory/items")
    public ResponseEntity<MemoryItem> createItem(
            @RequestBody(required = false) String rawBody) {
        CreateMemoryItemRequest req = parse(rawBody, CreateMemoryItemRequest.class);
        MemoryItem item;
        try {
            item = memoryService.createItem(
                    req == null ? "" : req.kind(),
                    req == null || req.content() == null ? "" : req.content(),
                    req == null || req.importance() == null ? 0 : req.importance());
        } catch (RuntimeException e) {
            throw fail(e, "Failed to create memory");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(item);
    }

    /** 对照 Go 的 {@code createMemoryItemRequest}：三个非指针字段（缺失即零值）。 */
    record CreateMemoryItemRequest(String kind, String content, Integer importance) {
    }

    /** 对照 Go {@code UpdateItem}（L293-306）。换锚后：200 + 裸条目。 */
    @PutMapping("/api/v1/memory/items/{id}")
    public MemoryItem updateItem(
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        UpdateMemoryItemRequest req = parse(rawBody, UpdateMemoryItemRequest.class);
        try {
            return memoryService.updateItem(id,
                    req == null || req.content() == null ? "" : req.content(),
                    req == null || req.importance() == null ? 0 : req.importance());
        } catch (RuntimeException e) {
            throw fail(e, "Failed to update memory");
        }
    }

    /**
     * 对照 Go 的 {@code updateMemoryItemRequest}：只有 content + importance
     * ——{@code kind} 不在请求体里，前端传了也读不到（Go 的 struct 没有那个字段）。
     */
    record UpdateMemoryItemRequest(String content, Integer importance) {
    }

    /** 对照 Go {@code DeleteItem}（L317-324）。换锚后：<b>204</b>（§1.13）。 */
    @DeleteMapping("/api/v1/memory/items/{id}")
    public ResponseEntity<Void> deleteItem(@PathVariable("id") String id) {
        try {
            memoryService.deleteItem(id);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to delete memory");
        }
        return ResponseEntity.noContent().build();
    }

    /** 对照 Go {@code ConfirmItem}（L338-346）。换锚后：200 + 裸条目。 */
    @PostMapping("/api/v1/memory/items/{id}/confirm")
    public MemoryItem confirmItem(@PathVariable("id") String id) {
        try {
            return memoryService.confirmItem(id);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to confirm memory");
        }
    }

    /**
     * 对照 Go {@code RejectItem}（L357-364）。
     *
     * <p>拒绝就是删除（Go 的 service 侧如此），响应随 DeleteItem：<b>204</b>。</p>
     */
    @PostMapping("/api/v1/memory/items/{id}/reject")
    public ResponseEntity<Void> rejectItem(@PathVariable("id") String id) {
        try {
            memoryService.rejectItem(id);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to reject memory");
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 对照 Go {@code Clear}（L374-382）。
     *
     * <p>注意是 {@code DELETE /memory/items}（集合本身），
     * 与 {@code DELETE /memory/items/{id}} 是两条不同的路由。</p>
     *
     * <p>同步完成的一次性清空，按 §1.13 返 <b>204</b>：Go 的 {@code {"removed":N}}
     * 计数是信封里的信息，换锚后不再下发（前端原样展示条数的 toast 一并去掉）。</p>
     */
    @DeleteMapping("/api/v1/memory/items")
    public ResponseEntity<Void> clear() {
        try {
            memoryService.clear();
        } catch (RuntimeException e) {
            throw fail(e, "Failed to clear memories");
        }
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════ 主题 ══════════════════════════

    /** 对照 Go {@code ListTopics}（L151-164）。换锚后：{@code {items,page,pageSize,total}}。 */
    @GetMapping("/api/v1/memory/topics")
    public MemoryListResponse<MemoryTopicView> listTopics(
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "offset", required = false) String offset) {
        int[] paging = listPaging(limit, offset);
        MemoryPage<MemoryTopicView> page;
        try {
            page = memoryService.listTopics(paging[0], paging[1]);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to list topics");
        }
        return pageBody(page, paging);
    }

    /**
     * 对照 Go {@code PromoteTopic}（L175-183）。
     *
     * <p>它是"动作"而不是创建端点：动的是主题、产出的是那条新记忆，
     * 故按 §2.1 的单资源形态返 200 + 裸条目（不套 §1.15 的 201）。</p>
     */
    @PostMapping("/api/v1/memory/topics/{id}/promote")
    public MemoryItem promoteTopic(@PathVariable("id") String id) {
        try {
            return memoryService.promoteTopic(id);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to promote topic");
        }
    }

    /** 对照 Go {@code DeleteTopic}（L194-201）。换锚后：<b>204</b>。 */
    @DeleteMapping("/api/v1/memory/topics/{id}")
    public ResponseEntity<Void> deleteTopic(@PathVariable("id") String id) {
        try {
            memoryService.deleteTopic(id);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to delete topic");
        }
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════ 文档亲和度 ══════════════════════════

    /** 对照 Go {@code ListDocuments}（L213-226）。换锚后：{@code {items,page,pageSize,total}}。 */
    @GetMapping("/api/v1/memory/documents")
    public MemoryListResponse<MemoryDocView> listDocuments(
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "offset", required = false) String offset) {
        int[] paging = listPaging(limit, offset);
        MemoryPage<MemoryDocView> page;
        try {
            page = memoryService.listDocuments(paging[0], paging[1]);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to list documents");
        }
        return pageBody(page, paging);
    }

    /** 对照 Go {@code DeleteDocument}（L237-244）。换锚后：<b>204</b>。 */
    @DeleteMapping("/api/v1/memory/documents/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable("id") String id) {
        try {
            memoryService.deleteDocument(id);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to delete document affinity");
        }
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════ 导出 / 整理 ══════════════════════════

    /**
     * 对照 Go {@code Export}（L392-427）。
     *
     * <h2>它是快照，不是一页</h2>
     * <p>固定一页曾经被当作够用（理由是"那正好是一个工作区能配的最大容量"），
     * 但那不成立：{@code max_items} 只封顶活跃记忆，被取代与被归档的行无上限堆积，
     * 所以一个长期的仓库持有很多倍于容量的行，导出会悄悄只给出它的前缀。
     * 因此这里按 {@link #EXPORT_PAGE_SIZE} 走到 {@code len(page) < pageSize}
     * 或 {@code len(items) >= total} 或触到 {@link #EXPORT_MAX_ITEMS} 安全上限为止。</p>
     *
     * <h2>换锚后的形态（§14.9k M1）</h2>
     * <ol>
     *   <li>体是裸 {@link com.ragagent.memory.dto.MemoryExportResponse}：
     *       {@code {items, total, truncated}}——旧的 {@code {"data":…,"success":true}} 信封退役。</li>
     *   <li>⚠️ <b>空仓库的 {@code items} 仍是 {@code null} 而不是 {@code []}</b>——
     *       Go 是 {@code var items []*types.MemoryItem} 且只在有行时才
     *       {@code append}，nil slice 序列化成 {@code null}。这与
     *       {@code GET /memory/items} 的 {@code []} <b>不同</b>，别统一
     *       （契约未要求把空导出改成空数组，保留既有语义）。</li>
     *   <li>{@code Content-Disposition: attachment; filename="weknora-memories.json"}
     *       ——Go 是 {@code c.Header(...)} + {@code c.JSON(200, ...)}，
     *       所以 <b>Content-Type 仍是普通 JSON</b>（{@code application/json; charset=utf-8}），
     *       不是 {@code application/octet-stream}。实测确认。</li>
     * </ol>
     *
     * <p>{@code truncated} 是"安全上限真的砍掉了东西"，只有 {@link #EXPORT_MAX_ITEMS}
     * 能触发，所以实践中恒为 false——但要说出来，而不是让一个残缺的文件看起来完整。</p>
     */
    @GetMapping("/api/v1/memory/export")
    public ResponseEntity<MemoryExportResponse> export() {
        List<MemoryItem> items = null;
        long total = 0;
        while (true) {
            MemoryPage<MemoryItem> page;
            try {
                page = memoryService.listItems("", EXPORT_PAGE_SIZE, itemCount(items));
            } catch (RuntimeException e) {
                throw fail(e, "Failed to export memories");
            }
            total = page.total();
            List<MemoryItem> pageItems = page.items();
            if (pageItems != null && !pageItems.isEmpty()) {
                if (items == null) {
                    // 只在真的有行时才建列表：nil → null 的形态必须保住（见方法注释）。
                    items = new ArrayList<>(pageItems.size());
                }
                items.addAll(pageItems);
            }
            if (pageItems == null || pageItems.size() < EXPORT_PAGE_SIZE
                    || itemCount(items) >= total) {
                break;
            }
            if (itemCount(items) >= EXPORT_MAX_ITEMS) {
                break;
            }
        }
        MemoryExportResponse body =
                new MemoryExportResponse(items, total, itemCount(items) < total);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"weknora-memories.json\"")
                // 用**原样的字符串**而不是 MediaType：MediaType.toString() 会把
                // 分隔符后的空格去掉（`application/json;charset=utf-8`），
                // 而 Go 的 `c.JSON` 写出来是 `application/json; charset=utf-8`。
                // 两者对 HTTP 语义等价，但本项目的验收手段是 diff 字节。
                .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                .body(body);
    }

    /** 对照 Go 的 {@code len(items)}：nil slice 长度为 0。 */
    private static int itemCount(List<?> items) {
        return items == null ? 0 : items.size();
    }

    /** 对照 Go {@code Consolidate}（L437-445）。换锚后：200 + 裸结果对象。 */
    @PostMapping("/api/v1/memory/consolidate")
    public MemoryConsolidationResult consolidate() {
        try {
            return consolidationService.consolidateNow();
        } catch (RuntimeException e) {
            throw fail(e, "Failed to consolidate memories");
        }
    }

    // ══════════════════════════ 工具方法 ══════════════════════════

    /**
     * {@code {items, page, pageSize, total}}——三个列表端点的响应（§2.1 分页形态）。
     *
     * <p>{@code items} 直接透传 service 的 {@code Page.items()}：Go 侧
     * {@code ListItems} 的 GORM {@code Find} 与 {@code ListTopics}/{@code ListDocuments}
     * 的 {@code make(..., 0, n)} 都产出<b>非 nil</b>切片，空时是 {@code []}。</p>
     *
     * <p>{@code page} 由 offset/limit 换算（{@code offset / limit + 1}，整数除法），
     * {@code pageSize} 就是容错后的 limit——请求侧仍只有 limit/offset 两个参数。</p>
     *
     * @param paging {@link #listPaging} 的返回值：{limit, offset}
     */
    private static <T> MemoryListResponse<T> pageBody(MemoryPage<T> page, int[] paging) {
        int limit = paging[0];
        int offset = paging[1];
        return new MemoryListResponse<>(page.items(), offset / limit + 1L, limit, page.total());
    }

    /**
     * 对照 Go {@code memoryListPaging}（L129-139）：{@code limit} 非法、≤0 或 &gt;200
     * 一律归 50；{@code offset} 为负归 0。
     *
     * <p>Go 用的是 {@code strconv.Atoi}——{@code "abc"} 解析失败时 limit 保持 0，
     * 随即被 {@code <= 0} 归 50。溢出同理（Java 的 {@code Integer.parseInt} 抛
     * {@code NumberFormatException}，此处按"解析失败"处理，语义一致）。</p>
     *
     * @return 长度为 2 的数组 {limit, offset}
     */
    static int[] listPaging(String rawLimit, String rawOffset) {
        int limit = parseIntOrZero(rawLimit);
        if (limit <= 0 || limit > 200) {
            limit = 50;
        }
        int offset = parseIntOrZero(rawOffset);
        if (offset < 0) {
            offset = 0;
        }
        return new int[] {limit, offset};
    }

    private static int parseIntOrZero(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 对照 Go 的 {@code c.ShouldBindJSON(&req)}：空 body 与非法 JSON 都落
     * {@code Invalid request data}（code 1010），details 是解析器的消息。
     *
     * <p>⚠️ <b>已知差异</b>：非法 JSON 的 details 文案两边不同——Go 是
     * {@code encoding/json} 的 {@code invalid character 'o' in literal null (expecting 'u')}，
     * Java 是 Jackson 的等价消息（措辞不同）。前端只读 {@code message}，
     * 契约测试因此掩码 details（与登录端点的既有处置一致，见 §9 阶段 1 差异 #2）。
     * 空 body 的 {@code "EOF"} 是逐字节一致的。</p>
     */
    private static <T> T parse(String rawBody, Class<T> type) {
        if (rawBody == null || rawBody.isBlank()) {
            throw invalidRequestData("EOF");
        }
        try {
            return MAPPER.readValue(rawBody, type);
        } catch (Exception e) {
            throw invalidRequestData(e.getMessage());
        }
    }

    private static BizException invalidRequestData(String details) {
        return new BizException(AppError.validation("Invalid request data").withDetails(details));
    }

    /**
     * 对照 Go {@code fail}（L449-465）：把 service 错误映射成 HTTP 响应。
     *
     * <p>"条目不存在"与"条目属于别人"<b>刻意产生同一个 404</b>——
     * 这样一个 id 无法被用来跨用户探测存在性（Go 的注释原文）。</p>
     *
     * <p>{@code PreviouslyForgotten} / {@code EmptyContent} <b>刻意不在</b>这张表里，
     * 它们与一切未列出的异常一起落 500 + details——Go 的 {@code switch} 也是这么落的。
     * 已实测钉住（见类注释）。</p>
     *
     * <p>⚠️ {@code createItem} / {@code promoteTopic} / {@code consolidateNow}
     * 在<b>没有主体</b>时抛的是 {@link MemoryScopeExceptions.Disabled}（400）而不是
     * {@code NoScope}（401）——因为 service 侧走的是 {@code enabledScope()} 的
     * <b>布尔</b>判定，NoScope 在那里被吞成了"不许用记忆"。与 Go 逐条一致。</p>
     */
    private BizException fail(RuntimeException err, String message) {
        if (err instanceof MemoryScopeExceptions.NoScope) {
            return new BizException(AppError.unauthorized("no principal in request"));
        }
        if (err instanceof MemoryScopeExceptions.ItemNotFound) {
            return new BizException(AppError.notFound("memory not found"));
        }
        if (err instanceof MemoryConflictException) {
            return new BizException(AppError.conflict(err.getMessage()));
        }
        if (err instanceof MemoryScopeExceptions.SensitiveContent) {
            return new BizException(AppError.badRequest(err.getMessage()));
        }
        if (err instanceof MemoryScopeExceptions.Disabled) {
            return new BizException(AppError.badRequest("memory is disabled"));
        }
        // 对照 Go 的 default 分支：记日志，然后 500 + handler 传进来的 message + details=err.Error()
        log.error("memory handler failure: {}", message, err);
        return new BizException(AppError.internal(message).withDetails(err.getMessage()));
    }
}
