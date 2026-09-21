package com.ragagent.storage.fileserve;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.session.domain.Message;
import com.ragagent.storage.domain.StoredResource;
import com.ragagent.storage.service.ResourceCatalogService;

/**
 * KB / 消息两个 scoped 文件代理的授权判定（对照 Go
 * {@code application/access/files.go} 的 ResolveKBFile / ResolveMessageFile /
 * AuthorizeMessageFile / MessageReferencesFile / resolveFile 与
 * {@code application/access/message_files.go} 的 resourceAccessibleViaSharedKB /
 * collectSharedKBEvidenceIDs / collectKBEvidenceFromValue，全文移植）。
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
    private final com.ragagent.org.service.AgentShareService agentShareService;
    private final com.ragagent.org.service.KbShareService kbShareService;
    private final com.ragagent.knowledge.service.KnowledgeService knowledgeService;
    private final com.ragagent.knowledge.service.KnowledgeBaseService knowledgeBaseService;

    public FileAccessResolver(ResourceCatalogService catalog,
            com.ragagent.org.service.AgentShareService agentShareService,
            com.ragagent.org.service.KbShareService kbShareService,
            com.ragagent.knowledge.service.KnowledgeService knowledgeService,
            com.ragagent.knowledge.service.KnowledgeBaseService knowledgeBaseService) {
        this.catalog = catalog;
        this.agentShareService = agentShareService;
        this.kbShareService = kbShareService;
        this.knowledgeService = knowledgeService;
        this.knowledgeBaseService = knowledgeBaseService;
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
    public FileAccess resolveKbFile(KnowledgeBase kb, String kbId, String reference) {
        // grant 检查通过后：owner = grant.EffectiveTenantID（= KB 行的租户）
        long owner = kb == null || kb.getTenantId() == null ? 0 : kb.getTenantId();
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
    public boolean messageReferencesFile(Message message, String reference) {
        if (message == null) {
            return false;
        }
        if (StoragePaths.containsStorageReference(message.getContent(), reference)) {
            return true;
        }
        if (message.getArtifacts() != null) {
            for (var artifact : message.getArtifacts()) {
                if (artifact != null && reference.equals(artifact.getUrl())) {
                    return true;
                }
            }
        }
        for (List<?> value : List.of(message.getKnowledgeReferences(), message.getImages())) {
            try {
                String data = value == null ? "null" : MAPPER.writeValueAsString(value);
                if (StoragePaths.containsStorageReference(data, reference)) {
                    return true;
                }
            } catch (Exception ignored) {
                // Go: json.Marshal 失败被吞（data 为空串 → 匹配不上）
            }
        }
        if (message.getAgentSteps() != null) {
            for (var step : message.getAgentSteps()) {
                if (step == null || step.getToolCalls() == null) {
                    continue;
                }
                for (var call : step.getToolCalls()) {
                    if (call == null || call.getResult() == null) {
                        continue;
                    }
                    try {
                        String data = MAPPER.writeValueAsString(call.getResult());
                        if (StoragePaths.containsStorageReference(data, reference)) {
                            return true;
                        }
                    } catch (Exception ignored) {
                        // 同上
                    }
                }
            }
        }
        return false;
    }

    // ── ResolveMessageFile / AuthorizeMessageFile ───────────────────────────

    /** 消息加载端口（对照 Go 的 messageFileLookup：GetMessage 已内含会话可见性判定）。 */
    public interface MessageFileLookup {
        Message getMessage(String sessionId, String messageId);
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
        Message message;
        try {
            message = messages.getMessage(sessionId, messageId);
        } catch (RuntimeException e) {
            // Go: messages.GetMessage 的任何 error → ErrNotFound（fileAccessError 折 404 无体）
            throw FileAccessException.notFound();
        }
        if (message == null) {
            throw FileAccessException.notFound();
        }
        return authorizeMessageFile(message, reference);
    }

    /** 对照 Go {@code AuthorizeMessageFile}（files.go L154-230 逐行，含跨租户双授予）。 */
    private FileAccess authorizeMessageFile(Message message, String reference) {
        Long callerTenant = TenantContext.currentTenantId();
        if (callerTenant == null || callerTenant == 0) {
            throw FileAccessException.unauthorized();
        }
        if (!messageReferencesFile(message, reference)) {
            throw FileAccessException.forbidden();
        }
        ResolvedFile resolved = resolveFile(reference);
        FileAccess file = resolved.file();
        StoredResource resource = resolved.resource();

        long owner = message.getAgentTenantId();
        if (resource != null) {
            owner = resource.getTenantId();
        }
        if (owner == 0) {
            throw FileAccessException.forbidden();
        }
        if ("user".equals(message.getRole()) && owner != callerTenant) {
            throw FileAccessException.forbidden();
        }
        com.ragagent.auth.domain.TenantRole callerRole =
                com.ragagent.org.service.OrganizationService.callerTenantRole();
        boolean kbAuthorized = false;
        if (resource != null && message.getAgentTenantId() != 0 && message.getAgentTenantId() != owner) {
            kbAuthorized = resourceAccessibleViaSharedKB(message, resource, callerTenant, callerRole);
            if (!kbAuthorized) {
                throw FileAccessException.forbidden();
            }
        }
        if (owner != callerTenant && !kbAuthorized) {
            if (message.getAgentTenantId() == 0) {
                kbAuthorized = resourceAccessibleViaSharedKB(message, resource, callerTenant, callerRole);
            }
            if (!kbAuthorized) {
                // shared-agent 授予路径（Go files.go L200-224）
                if (message.getAgentId() == null || message.getAgentId().isEmpty()) {
                    throw FileAccessException.forbidden();
                }
                com.ragagent.org.domain.AgentRow agent;
                try {
                    agent = agentShareService.getSharedAgentForTenant(callerTenant, callerRole,
                            message.getAgentId(), owner);
                } catch (RuntimeException e) {
                    throw FileAccessException.forbidden();
                }
                if (agent == null || agent.getTenantId() == null || agent.getTenantId() != owner) {
                    throw FileAccessException.forbidden();
                }
                ResourceCatalogService.MessageFileBindings origins =
                        catalog.getMessageFileBindings(owner, reference, message.getId());
                boolean allowed = origins.messageArtifact();
                com.ragagent.org.service.SharedAgentKBScope scope =
                        com.ragagent.org.service.SharedAgentKBScope.from(agent);
                for (String kbId : origins.knowledgeBaseIds()) {
                    if (scope.allows(kbId, owner) && apiKeyAllowsKb(kbId)) {
                        allowed = true;
                    }
                }
                if (!allowed) {
                    throw FileAccessException.forbidden();
                }
            }
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
            com.ragagent.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeBases(java.util.List.of(kbId));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── org-shared KB 证据链（access/message_files.go L73-112）──────────────

    /**
     * 对照 Go {@code resourceAccessibleViaSharedKB}：消息的<b>持久化检索证据</b>证明
     * 资源来自调用方可读的 org-shared KB（自有 agent + 他方 KB 的 #3022 场景）。
     * 证据要求：规范 resource:// handle 出现在 chunk 文本或 image_info、该 KB 属于
     * 资源租户、KB org 共享给调用方（≥viewer）、独立存活绑定确认文件仍属于它。
     * 任何查找失败 fail-closed。
     */
    private boolean resourceAccessibleViaSharedKB(Message message, StoredResource resource,
                                                  long callerTenant,
                                                  com.ragagent.auth.domain.TenantRole callerRole) {
        if (message == null || resource == null) {
            return false;
        }
        String handle = StoragePaths.parseResourcePath(
                StoragePaths.buildResourcePath(resource.getHandle()));
        if (handle == null) {
            return false;
        }
        java.util.List<String> kbIds = collectSharedKBEvidenceIDs(message, handle);
        if (kbIds.isEmpty()) {
            return false;
        }
        for (String kbId : kbIds) {
            KnowledgeBase kb;
            try {
                kb = knowledgeBaseService.getAllTenantById(kbId);
            } catch (RuntimeException e) {
                return false;
            }
            if (kb == null || kb.getTenantId() == null || kb.getTenantId() != resource.getTenantId()) {
                continue;
            }
            boolean shared = kbShareService.checkTenantKBPermission(kb.getId(), callerTenant, callerRole)
                    .permits("viewer");
            if (shared && catalog.isReferencedByKnowledgeBase(resource.getTenantId(), kb.getId(),
                    StoragePaths.buildResourcePath(handle))) {
                return true;
            }
        }
        return false;
    }

    /** 对照 Go {@code collectSharedKBEvidenceIDs}（KnowledgeReferences + AgentSteps 递归）。 */
    private java.util.List<String> collectSharedKBEvidenceIDs(Message message, String handle) {
        java.util.Set<String> seenKB = new java.util.LinkedHashSet<>();
        java.util.Set<String> seenKnowledge = new java.util.LinkedHashSet<>();
        if (message.getKnowledgeReferences() != null) {
            for (var ref : message.getKnowledgeReferences()) {
                if (ref == null || !searchResultHasResourceHandle(ref, handle)) {
                    continue;
                }
                if (ref.getKnowledgeBaseId() != null && !ref.getKnowledgeBaseId().isEmpty()) {
                    seenKB.add(ref.getKnowledgeBaseId());
                } else {
                    seenKnowledge.add(ref.getKnowledgeId());
                }
            }
        }
        collectKBEvidenceFromValue(message.getAgentSteps(), handle, "", "", seenKB, seenKnowledge);
        for (String knowledgeId : seenKnowledge) {
            com.ragagent.knowledge.domain.Knowledge knowledge;
            try {
                knowledge = knowledgeService.getKnowledgeByIdOnly(knowledgeId);
            } catch (RuntimeException e) {
                continue;
            }
            if (knowledge != null && knowledge.getKnowledgeBaseId() != null
                    && !knowledge.getKnowledgeBaseId().isEmpty()) {
                seenKB.add(knowledge.getKnowledgeBaseId());
            }
        }
        return new java.util.ArrayList<>(seenKB);
    }

    /** 对照 Go {@code searchResultHasResourceHandle}（content / matched_content / image_info）。 */
    private static boolean searchResultHasResourceHandle(
            com.ragagent.retrieval.domain.SearchResult ref, String handle) {
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
    private static void collectKBEvidenceFromValue(Object v, String handle, String kbId,
                                                   String knowledgeId,
                                                   java.util.Set<String> addKB,
                                                   java.util.Set<String> addKnowledge) {
        switch (v) {
            case null -> { }
            case String s -> {
                if (!textHasResourceHandle(s, handle)) {
                    return;
                }
                if (kbId != null && !kbId.isEmpty()) {
                    addKB.add(kbId);
                } else if (knowledgeId != null && !knowledgeId.isEmpty()) {
                    addKnowledge.add(knowledgeId);
                }
            }
            case com.ragagent.agent.domain.AgentStep step ->
                    collectKBEvidenceFromValue(step.getToolCalls(), handle, kbId, knowledgeId,
                            addKB, addKnowledge);
            case com.ragagent.agent.domain.ToolCall call -> {
                if (call.getResult() != null) {
                    collectKBEvidenceFromValue(call.getResult().getOutput(), handle, kbId, knowledgeId,
                            addKB, addKnowledge);
                    collectKBEvidenceFromValue(call.getResult().getData(), handle, kbId, knowledgeId,
                            addKB, addKnowledge);
                }
            }
            case java.util.Map<?, ?> map -> {
                String nextKB = firstNonEmptyString(
                        stringFromMap(map, "knowledge_base_id"),
                        stringFromMap(map, "knowledge_base"),
                        kbId);
                String nextKnowledge = firstNonEmptyString(stringFromMap(map, "knowledge_id"), knowledgeId);
                for (Object nested : map.values()) {
                    collectKBEvidenceFromValue(nested, handle, nextKB, nextKnowledge, addKB, addKnowledge);
                }
            }
            case java.util.List<?> list -> {
                for (Object item : list) {
                    collectKBEvidenceFromValue(item, handle, kbId, knowledgeId, addKB, addKnowledge);
                }
            }
            default -> { }
        }
    }

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
