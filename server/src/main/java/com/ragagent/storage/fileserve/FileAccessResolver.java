package com.ragagent.storage.fileserve;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.storage.domain.StoredResource;
import com.ragagent.storage.service.ResourceCatalogService;

/**
 * KB / 消息两个 scoped 文件代理的授权判定（对照 Go
 * {@code application/access/files.go} 的 ResolveKBFile / ResolveMessageFile /
 * AuthorizeMessageFile / MessageReferencesFile / resolveFile，全文移植；
 * 跨租户双授予链随空间分享裁撤）。
 *
 * <p>错误以 {@link FileAccessException} 抛出，由代理服务按 Go 的
 * {@code fileAccessError} 映射写响应。</p>
 *
 * <p>W5α3 已收口跨租户两条授予路径：①org-shared KB 证据链（消息的持久化检索
 * 证据 + KB org 共享 viewer + 存活绑定）；②shared-agent 授予
 * （GetSharedAgentForTenant + GetMessageFileBindings + scope.Allows + API-Key 白名单）。
 * 撤销 share 即撤销历史消息文件访问（每次请求重查，Go 同款）。</p>
 */
@Component
public class FileAccessResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ResourceCatalogService catalog;

    public FileAccessResolver(ResourceCatalogService catalog) {
        this.catalog = catalog;
    }

    // ── resolveFile（access/files.go L28-54）────────────────────────────────

    private record ResolvedFile(FileAccess file, StoredResource resource) {
    }

    private ResolvedFile resolveFile(String reference) {
        FileAccess file = new FileAccess(0, reference, reference, "");
        ResourceCatalogService.ResolvedPath resolved = catalog.resolvePath(reference);
        if (resolved.error()) {
            throw FileAccessException.notFound();
        }
        StoredResource resource = resolved.resource();
        String path = resolved.physicalPath();
        String filename = resolved.physicalPath();
        long owner = 0;
        String backendId = "";
        if (resource != null) {
            owner = resource.getTenantId();
            backendId = resource.getStorageBackendId();
            if (resource.getOriginalName() != null && !resource.getOriginalName().trim().isEmpty()) {
                filename = resource.getOriginalName();
            }
        }
        return new ResolvedFile(new FileAccess(owner, path, filename, backendId), resource);
    }

    // ── ResolveKBFile（access/files.go L58-96）──────────────────────────────

    /**
     * 对照 Go {@code ResolveKBFile}：要求路由的<b>精确 KB grant</b> 与独立的
     * 存活绑定检查。重写执行租户不足以授权。
     *
     * <p>grant 段（grant.KnowledgeBase.ID == kbID、grant.Caller 一致、Viewer 权限、
     * API-Key 白名单）由 Java 侧的 {@code ChunkAccessGuard.requireKbAccess} 在控制器
     * 内先行完成（对照 Go 的 RequireKBAccess 中间件），本方法从 owner 段接续。</p>
     *
     * API-Key KB 白名单的越界异常由 requireKbAccess 按中间件形态抛出。
     */
        /** 形参由 KB 实体改为其租户 id（本方法只用这一个字段；存储域不应持有知识域实体）。 */
    public FileAccess resolveKbFile(Long kbTenantId, String kbId, String reference) {
        // grant 检查通过后：owner = grant.EffectiveTenantID（= KB 行的租户）
        long owner = kbTenantId == null ? 0 : kbTenantId;
        ResolvedFile resolved = resolveFile(reference);
        FileAccess file = resolved.file();
        StoredResource resource = resolved.resource();
        if (owner == 0 || (resource != null && resource.getTenantId() != owner)) {
            throw FileAccessException.forbidden();
        }
        String pathError = StoragePaths.validateKbScopedStoragePathError(file.path(), owner);
        if (pathError != null) {
            throw FileAccessException.forbidden();
        }
        boolean bound = catalog.isReferencedByKnowledgeBase(owner, kbId, reference);
        if (!bound) {
            throw FileAccessException.forbidden();
        }
        return new FileAccess(owner, file.path(), file.filename(), file.storageBackendId());
    }

    // ── MessageReferencesFile（access/files.go L100-130）────────────────────

    /**
     * 只查<b>持久化的渲染/输出字段</b>（content / artifacts[].url / knowledge_references /
     * images / agent_steps[].tool_calls[].result）——工具参数与请求元数据不算证据。
     * Go 对 List 字段整体 json.Marshal 后跑整 token 匹配；Java 用 Jackson 等价序列化
     * （引用 token 本身不含 HTML 特殊字符，转义差异不影响匹配）。
     */
    public boolean messageReferencesFile(MessageFileFacts facts, String reference) {
        if (facts == null) {
            return false;
        }
        if (StoragePaths.containsStorageReference(facts.content(), reference)) {
            return true;
        }
        if (facts.artifactUrls() != null) {
            for (String url : facts.artifactUrls()) {
                if (url != null && reference.equals(url)) {
                    return true;
                }
            }
        }
        for (List<?> value : List.of(facts.knowledgeReferences(), facts.images())) {
            try {
                String data = value == null ? "null" : MAPPER.writeValueAsString(value);
                if (StoragePaths.containsStorageReference(data, reference)) {
                    return true;
                }
            } catch (Exception ignored) {
                // Go: json.Marshal 失败被吞（data 为空串 → 匹配不上）
            }
        }
        for (Object result : facts.toolResults()) {
            if (result == null) {
                continue;
            }
            try {
                String data = MAPPER.writeValueAsString(result);
                if (StoragePaths.containsStorageReference(data, reference)) {
                    return true;
                }
            } catch (Exception ignored) {
                // Go: json.Marshal 失败被吞（data 为空串 → 匹配不上）
            }
        }
        return false;
    }

    // ── ResolveMessageFile / AuthorizeMessageFile ───────────────────────────

    /**
     * 消息中「与文件引用匹配 / 授权」相关的字段（端口载荷）。
     *
     * <p>不让 storage 依赖 session 的 {@code Message} 实体：会话侧只把需要的字段映射进来，
     * 授权与匹配逻辑也只看这几段（与 Go 的 {@code MessageReferencesFile}/{@code AuthorizeMessageFile}
     * 实际读取的字段一一对应）。**别改成"整条消息序列化"**——那会让匹配范围变宽，等于越权。</p>
     */
    public record MessageFileFacts(
            String content,
            List<String> artifactUrls,
            List<?> knowledgeReferences,
            List<?> images,
            List<Object> toolResults,
            long agentTenantId,
            String role) {
    }

    /** 消息加载端口（对照 Go 的 messageFileLookup：GetMessage 已内含会话可见性判定）。 */
    public interface MessageFileLookup {
        MessageFileFacts getMessage(String sessionId, String messageId);
    }

    /**
     * 对照 Go {@code ResolveMessageFile}：用原始 caller 加载会话授权的消息，并每次
     * 请求重查当前共享关系。
     */
    public FileAccess resolveMessageFile(String sessionId, String messageId, String reference,
            MessageFileLookup messages) {
        Long callerTenant = TenantContext.currentTenantId();
        if (callerTenant == null || callerTenant == 0) {
            throw FileAccessException.unauthorized();
        }
        MessageFileFacts facts;
        try {
            facts = messages.getMessage(sessionId, messageId);
        } catch (RuntimeException e) {
            // Go: messages.GetMessage 的任何 error → ErrNotFound（fileAccessError 折 404 无体）
            throw FileAccessException.notFound();
        }
        if (facts == null) {
            throw FileAccessException.notFound();
        }
        return authorizeMessageFile(facts, reference);
    }

    /** 对照 Go {@code AuthorizeMessageFile}（files.go L154-230 逐行，含跨租户双授予）。 */
    private FileAccess authorizeMessageFile(MessageFileFacts facts, String reference) {
        Long callerTenant = TenantContext.currentTenantId();
        if (callerTenant == null || callerTenant == 0) {
            throw FileAccessException.unauthorized();
        }
        if (!messageReferencesFile(facts, reference)) {
            throw FileAccessException.forbidden();
        }
        ResolvedFile resolved = resolveFile(reference);
        FileAccess file = resolved.file();
        StoredResource resource = resolved.resource();

        long owner = facts.agentTenantId();
        if (resource != null) {
            owner = resource.getTenantId();
        }
        if (owner == 0) {
            throw FileAccessException.forbidden();
        }
        if ("user".equals(facts.role()) && owner != callerTenant) {
            throw FileAccessException.forbidden();
        }
        // 空间分享裁撤：跨租户双授予（org-shared KB 证据链 + shared-agent 授予）已退役，
        // 消息文件授权只认本租户。
        if (owner != callerTenant) {
            throw FileAccessException.forbidden();
        }
        if (resource == null) {
            String pathError = StoragePaths.validateStoragePathTenantError(file.path(), owner);
            if (pathError != null) {
                throw FileAccessException.forbidden();
            }
        }
        return new FileAccess(owner, file.path(), file.filename(), file.storageBackendId());
    }

    /** Go 的 {@code AuthorizeTenantAPIKeyKnowledgeBases(...) == nil} 判定（受限 Key 越白名单 → false）。 */
    private static boolean apiKeyAllowsKb(String kbId) {
        try {
            com.ragagent.auth.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeBases(java.util.List.of(kbId));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 对照 Go {@code searchResultHasResourceHandle}（content / matched_content / image_info）。 */
    private static boolean searchResultHasResourceHandle(
            com.ragagent.common.retrieval.SearchResult ref, String handle) {
        return textHasResourceHandle(ref.getContent(), handle)
                || textHasResourceHandle(ref.getMatchedContent(), handle)
                || textHasResourceHandle(ref.getImageInfo(), handle);
    }

    /** 对照 Go {@code textHasResourceHandle}：整 token 相等才算命中。 */
    private static boolean textHasResourceHandle(String text, String handle) {
        if (text == null || text.isEmpty() || handle == null || handle.isEmpty()) {
            return false;
        }
        return StoragePaths.containsStorageReference(text, StoragePaths.buildResourcePath(handle));
    }

    /**
     * 对照 Go {@code collectKBEvidenceFromValue}（message_files.go L168-224）：
     * 递归遍历 AgentSteps 的 ToolCall Result（Output/Data），map 下行时继承
     * knowledge_base_id / knowledge_base / knowledge_id 上下文；命中 handle 的字符串
     * 按当时上下文归因 KB（无 KB 上下文则记 knowledge 待二次解析）。
     */

    private static String stringFromMap(java.util.Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof String s ? s.trim() : "";
    }

    private static String firstNonEmptyString(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}
