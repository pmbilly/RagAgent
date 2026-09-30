package com.ragagent.memory.controller;

import com.ragagent.common.web.JsonMappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.ragagent.memory.service.MemoryConsolidationService;
import com.ragagent.memory.service.MemoryScopeExceptions;
import com.ragagent.memory.service.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
 * <h2>响应形态：gin.H = map = 键按字母序（§9 的 JSON 键序规则）</h2>
 * <p>Go 这里每一个成功响应都是 {@code gin.H}，经 {@code encoding/json} 序列化后
 * <b>键按字母序</b>输出，不是源码里的书写顺序。所以线上真实字节是：</p>
 * <ul>
 *   <li>{@code {"data":…,"success":true}}（data &lt; success，恰好与书写序相同）</li>
 *   <li>{@code {"data":[…],"success":true,"total":N}}（列表三键）</li>
 *   <li>{@code {"removed":N,"success":true}}（Clear——<b>removed 在 success 前</b>）</li>
 *   <li>{@code {"data":[…],"success":true,"total":N,"truncated":bool}}（Export 四键）</li>
 * </ul>
 * <p>故 Java 侧一律用 {@link LinkedHashMap} <b>按字母序 put</b>，与
 * {@code GlobalExceptionHandler.errorBody} 的做法一致。<b>不</b>给顶层 body 包一层
 * {@code R<T>}——{@code R} 只会输出 data+success 两键，列表与 Clear 用不了。</p>
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

    /** 对照 Go {@code GetSettings}（L39-47）。 */
    @GetMapping("/api/v1/memory/settings")
    public ResponseEntity<Map<String, Object>> getSettings() {
        MemorySettings settings;
        try {
            settings = memoryService.getSettings();
        } catch (RuntimeException e) {
            throw fail(e, "Failed to load memory settings");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", settings);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /**
     * 对照 Go {@code UpdateSettings}（L63-84）。
     *
     * <p>两处 400 的门槛顺序有语义：先"请求体能不能解析"，
     * 再"enabled 在不在"——{@code {"enabled":null}} 落后者。</p>
     */
    @PutMapping("/api/v1/memory/settings")
    public ResponseEntity<Map<String, Object>> updateSettings(
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
     * <p>⚠️ 实测：Go 空仓库输出 {@code "data":[]}（<b>不是</b> {@code null}）——
     * GORM 的 {@code Find} 把 nil slice 初始化成非 nil 空切片。
     * 与 Export 的 {@code "data":null} 是两种形态，别统一。</p>
     */
    @GetMapping("/api/v1/memory/items")
    public ResponseEntity<Map<String, Object>> listItems(
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
        return pageBody(page);
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

    /** 对照 Go {@code CreateItem}（L262-275）。 */
    @PostMapping("/api/v1/memory/items")
    public ResponseEntity<Map<String, Object>> createItem(
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
        return dataBody(item);
    }

    /** 对照 Go 的 {@code createMemoryItemRequest}：三个非指针字段（缺失即零值）。 */
    record CreateMemoryItemRequest(String kind, String content, Integer importance) {
    }

    /** 对照 Go {@code UpdateItem}（L293-306）。 */
    @PutMapping("/api/v1/memory/items/{id}")
    public ResponseEntity<Map<String, Object>> updateItem(
            @PathVariable("id") String id,
            @RequestBody(required = false) String rawBody) {
        UpdateMemoryItemRequest req = parse(rawBody, UpdateMemoryItemRequest.class);
        MemoryItem item;
        try {
            item = memoryService.updateItem(id,
                    req == null || req.content() == null ? "" : req.content(),
                    req == null || req.importance() == null ? 0 : req.importance());
        } catch (RuntimeException e) {
            throw fail(e, "Failed to update memory");
        }
        return dataBody(item);
    }

    /**
     * 对照 Go 的 {@code updateMemoryItemRequest}：只有 content + importance
     * ——{@code kind} 不在请求体里，前端传了也读不到（Go 的 struct 没有那个字段）。
     */
    record UpdateMemoryItemRequest(String content, Integer importance) {
    }

    /** 对照 Go {@code DeleteItem}（L317-324）。 */
    @DeleteMapping("/api/v1/memory/items/{id}")
    public ResponseEntity<Map<String, Object>> deleteItem(@PathVariable("id") String id) {
        try {
            memoryService.deleteItem(id);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to delete memory");
        }
        return ack();
    }

    /** 对照 Go {@code ConfirmItem}（L338-346）。 */
    @PostMapping("/api/v1/memory/items/{id}/confirm")
    public ResponseEntity<Map<String, Object>> confirmItem(@PathVariable("id") String id) {
        MemoryItem item;
        try {
            item = memoryService.confirmItem(id);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to confirm memory");
        }
        return dataBody(item);
    }

    /**
     * 对照 Go {@code RejectItem}（L357-364）。
     *
     * <p>响应是 {@code {"success":true}} ——<b>不带 data</b>，
     * 与 DeleteItem 的响应同形（Go 里 {@code RejectItem} 的 service 侧就是删除）。</p>
     */
    @PostMapping("/api/v1/memory/items/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectItem(@PathVariable("id") String id) {
        try {
            memoryService.rejectItem(id);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to reject memory");
        }
        return ack();
    }

    /**
     * 对照 Go {@code Clear}（L374-382）。
     *
     * <p>注意是 {@code DELETE /memory/items}（集合本身），
     * 与 {@code DELETE /memory/items/{id}} 是两条不同的路由。</p>
     */
    @DeleteMapping("/api/v1/memory/items")
    public ResponseEntity<Map<String, Object>> clear() {
        long removed;
        try {
            removed = memoryService.clear();
        } catch (RuntimeException e) {
            throw fail(e, "Failed to clear memories");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("removed", removed);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ══════════════════════════ 主题 ══════════════════════════

    /** 对照 Go {@code ListTopics}（L151-164）。 */
    @GetMapping("/api/v1/memory/topics")
    public ResponseEntity<Map<String, Object>> listTopics(
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "offset", required = false) String offset) {
        int[] paging = listPaging(limit, offset);
        MemoryPage<MemoryTopicView> page;
        try {
            page = memoryService.listTopics(paging[0], paging[1]);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to list topics");
        }
        return pageBody(page);
    }

    /** 对照 Go {@code PromoteTopic}（L175-183）。 */
    @PostMapping("/api/v1/memory/topics/{id}/promote")
    public ResponseEntity<Map<String, Object>> promoteTopic(@PathVariable("id") String id) {
        MemoryItem item;
        try {
            item = memoryService.promoteTopic(id);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to promote topic");
        }
        return dataBody(item);
    }

    /** 对照 Go {@code DeleteTopic}（L194-201）。 */
    @DeleteMapping("/api/v1/memory/topics/{id}")
    public ResponseEntity<Map<String, Object>> deleteTopic(@PathVariable("id") String id) {
        try {
            memoryService.deleteTopic(id);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to delete topic");
        }
        return ack();
    }

    // ══════════════════════════ 文档亲和度 ══════════════════════════

    /** 对照 Go {@code ListDocuments}（L213-226）。 */
    @GetMapping("/api/v1/memory/documents")
    public ResponseEntity<Map<String, Object>> listDocuments(
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "offset", required = false) String offset) {
        int[] paging = listPaging(limit, offset);
        MemoryPage<MemoryDocView> page;
        try {
            page = memoryService.listDocuments(paging[0], paging[1]);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to list documents");
        }
        return pageBody(page);
    }

    /** 对照 Go {@code DeleteDocument}（L237-244）。 */
    @DeleteMapping("/api/v1/memory/documents/{id}")
    public ResponseEntity<Map<String, Object>> deleteDocument(@PathVariable("id") String id) {
        try {
            memoryService.deleteDocument(id);
        } catch (RuntimeException e) {
            throw fail(e, "Failed to delete document affinity");
        }
        return ack();
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
     * <h2>两个必须照抄的形态</h2>
     * <ol>
     *   <li>⚠️ <b>空仓库的 {@code data} 是 {@code null} 而不是 {@code []}</b>——
     *       Go 是 {@code var items []*types.MemoryItem} 且只在有行时才
     *       {@code append}，nil slice 序列化成 {@code null}。已实测：
     *       {@code {"data":null,"success":true,"total":0,"truncated":false}}。
     *       这与 {@code GET /memory/items} 的 {@code []} <b>不同</b>，别统一。</li>
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
    public ResponseEntity<Map<String, Object>> export() {
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", items);
        body.put("success", true);
        body.put("total", total);
        body.put("truncated", itemCount(items) < total);
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

    /** 对照 Go {@code Consolidate}（L437-445）。 */
    @PostMapping("/api/v1/memory/consolidate")
    public ResponseEntity<Map<String, Object>> consolidate() {
        MemoryConsolidationResult result;
        try {
            result = consolidationService.consolidateNow();
        } catch (RuntimeException e) {
            throw fail(e, "Failed to consolidate memories");
        }
        return dataBody(result);
    }

    // ══════════════════════════ 工具方法 ══════════════════════════

    /** {@code {"success":true}}——DeleteTopic / DeleteDocument / DeleteItem / RejectItem 的响应。 */
    private static ResponseEntity<Map<String, Object>> ack() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** {@code {"data":…,"success":true}}——单条资源的响应。 */
    private static ResponseEntity<Map<String, Object>> dataBody(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /**
     * {@code {"data":[…],"success":true,"total":N}}——三个列表端点的响应。
     *
     * <p>{@code data} 直接透传 service 的 {@code Page.items()}：Go 侧
     * {@code ListItems} 的 GORM {@code Find} 与 {@code ListTopics}/{@code ListDocuments}
     * 的 {@code make(..., 0, n)} 都产出<b>非 nil</b>切片，空时是 {@code []}。</p>
     */
    private static ResponseEntity<Map<String, Object>> pageBody(MemoryPage<?> page) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", page.items());
        body.put("success", true);
        body.put("total", page.total());
        return ResponseEntity.ok(body);
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
