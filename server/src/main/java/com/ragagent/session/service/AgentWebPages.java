package com.ragagent.session.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.ragagent.agent.tools.ReadFileTool;
import com.ragagent.agent.tools.WebFetchTool;
import com.ragagent.storage.service.ResourceCatalogService;

/**
 * agent 抓取页的完整快照存取（对照 Go {@code agent_web_pages.go} 全文）。
 *
 * <p>复用资源目录与文件驱动：一个 {@code web://} 地址不授予一般资源访问——每次读
 * 都必须匹配「本租户 + 本 owner + 本会话中一条活跃 assistant 消息」的 web_page
 * 绑定。同时实现 {@link ReadFileTool.WebPageSource} 与
 * {@link WebFetchTool.WebPageSource}（Go 两件工具共享同一接口）。</p>
 *
 * <h2>接缝与备案</h2>
 * <p>文件写字节面（{@code interfaces.FileService.SaveBytes} / 本地 provider 的
 * 资源行创建）尚未翻译（HANDOFF §0.-2 剩余 1，与 ArtifactCollector 生产装配
 * 共栈）——{@link Store} 为其接缝：缺省 null 时 {@link #save} 走 Go 的
 * save-failure 分支（storageError 文案逐字对照），页仍进本轮缓存；
 * {@link #read} 报 "saved web page is no longer available"（Go 的 GetFile 失败
 * 同文案）。存储面落地后把生产 Store 注入即可，无需改本类。</p>
 */
public class AgentWebPages implements ReadFileTool.WebPageSource, WebFetchTool.WebPageSource {

    /** 对照 webPageRelation / maxSavedWebPageBytes。 */
    public static final String WEB_PAGE_RELATION = "web_page";
    public static final long MAX_SAVED_WEB_PAGE_BYTES = 8L << 20;

    /**
     * 文件存取面（对照 Go files 回调返回的 interfaces.FileService 的 SaveBytes/
     * GetFile/DeleteFile 三方法）。资源绑定面（对照 interfaces.ResourceCatalog.Bind）。
     * 二者任一为 null = Go 的 storageResolver/fileService 缺位分支。
     */
    public interface Store {
        /** 返回 resource:// 引用（对照 SaveBytes）。 */
        String saveBytes(byte[] data, long tenantId, String name) throws Exception;

        byte[] readFile(String reference) throws Exception;

        void deleteFile(String reference);
    }

    public interface Binding {
        void bind(String reference, String ownerType, String ownerId, String relation) throws Exception;
    }

    private final JdbcTemplate jdbc;
    private final ResourceCatalogService catalog;
    private final Store store;
    private final Binding binding;
    private final long tenantId;
    private final String ownerId;
    private final String sessionId;
    private final String messageId;

    /** 全参装配（store/binding 为存储写字节面的接缝，dev 可空）。 */
    public AgentWebPages(DataSource dataSource, ResourceCatalogService catalog,
            Store store, Binding binding, long tenantId,
            String ownerId, String sessionId, String messageId) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.catalog = catalog;
        this.store = store;
        this.binding = binding;
        this.tenantId = tenantId;
        this.ownerId = ownerId;
        this.sessionId = sessionId;
        this.messageId = messageId;
    }

    /** assistant 消息在位检查（对照 messages(ctx) + Count==1）。 */
    private boolean assistantMessageExists() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages JOIN sessions ON sessions.id = messages.session_id "
                        + "WHERE sessions.id = ? AND sessions.tenant_id = ? "
                        + "AND sessions.user_id = ? "
                        + "AND sessions.deleted_at IS NULL AND messages.role = 'assistant' "
                        + "AND messages.id = ?",
                Long.class, sessionId, tenantId, ownerId, messageId);
        return count != null && count == 1;
    }

    /** 对照 Save：尺寸守卫 → 消息在位 → 落存储 → resource 绑定 → web:// 地址。 */
    @Override
    public String save(String content) throws Exception {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_SAVED_WEB_PAGE_BYTES) {
            throw new IllegalStateException("web page exceeds the saved-page size limit");
        }
        if (!assistantMessageExists()) {
            throw new IllegalStateException("web page message is unavailable");
        }
        if (store == null) {
            throw new IllegalStateException("web page storage is unavailable");
        }
        String ref = store.saveBytes(data, tenantId, "web-" + UUID.randomUUID() + ".md");
        String handle = parseResourceHandle(ref);
        if (handle == null) {
            store.deleteFile(ref);
            throw new IllegalStateException("web page storage did not return a resource reference");
        }
        try {
            if (binding != null) {
                binding.bind(ref, "message", messageId, WEB_PAGE_RELATION);
            } else {
                throw new IllegalStateException("resource catalog is unavailable");
            }
        } catch (Exception e) {
            store.deleteFile(ref);
            throw e;
        }
        return "web://" + handle;
    }

    /** 对照 Read：web:// → resource:// 解析 + 租户与绑定双重授权 + 8MB 读限。 */
    @Override
    public byte[] read(String address) throws Exception {
        if (address == null || !address.startsWith("web://")) {
            throw new IllegalStateException("invalid saved web page address");
        }
        String ref = "resource://" + address.substring("web://".length());
        var resource = catalog == null ? null : catalog.resolve(ref).orElse(null);
        if (resource == null || resource.getTenantId() != tenantId) {
            throw new IllegalStateException("saved web page is unavailable in this session");
        }
        if (!assistantMessageExists() || !bindingExists(resource.getId())) {
            throw new IllegalStateException("saved web page is unavailable in this session");
        }
        if (store == null) {
            throw new IllegalStateException("web page storage is unavailable");
        }
        byte[] data;
        try {
            data = store.readFile(ref);
        } catch (Exception e) {
            throw new IllegalStateException("saved web page is no longer available");
        }
        if (data.length > MAX_SAVED_WEB_PAGE_BYTES) {
            throw new IllegalStateException("saved web page exceeds the read limit");
        }
        return data;
    }

    /** 绑定在位检查（对照 messages ⨝ resource_bindings 的 Count 段）。 */
    private boolean bindingExists(String resourceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages "
                        + "JOIN sessions ON sessions.id = messages.session_id "
                        + "JOIN resource_bindings ON resource_bindings.owner_id = messages.id "
                        + "WHERE sessions.id = ? AND sessions.tenant_id = ? "
                        + "AND sessions.user_id = ? "
                        + "AND sessions.deleted_at IS NULL AND messages.role = 'assistant' "
                        + "AND messages.id = ? "
                        + "AND resource_bindings.resource_id = ? AND resource_bindings.tenant_id = ? "
                        + "AND resource_bindings.owner_type = 'message' "
                        + "AND resource_bindings.relation = ?",
                Long.class, sessionId, tenantId, ownerId, messageId, resourceId, tenantId,
                WEB_PAGE_RELATION);
        return count != null && count > 0;
    }

    /** 对照 types.ParseResourcePath：resource://<handle> → handle，不合式 → 不 ok。 */
    static String parseResourceHandle(String reference) {
        if (reference == null || !reference.startsWith("resource://")) {
            return null;
        }
        String handle = reference.substring("resource://".length());
        if (handle.isEmpty() || handle.contains("/") || handle.contains("?") || handle.contains("#")) {
            return null;
        }
        return handle;
    }

}
