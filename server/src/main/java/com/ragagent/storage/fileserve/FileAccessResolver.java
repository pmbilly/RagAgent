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
 * AuthorizeMessageFile / MessageReferencesFile / resolveFile，全文移植）。
 *
 * <p>错误以 {@link FileAccessException} 抛出，由代理服务按 Go 的
 * {@code fileAccessError} 映射写响应。</p>
 *
 * <p><b>已知收紧（同源备案，约定 §9 阶段 3 差异 3）</b>：跨租户消息文件的
 * shared-agent / org-shared KB 两条授予路径未翻译——owner ≠ caller 恒 403
 * （Go 在 share 在位时放行）。方向偏保守，不放行比 Go 更多的访问。</p>
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

    /** 对照 Go {@code AuthorizeMessageFile}（同租户主路径 + 跨租户收紧备案）。 */
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
        if (owner != callerTenant) {
            // 跨租户：Go 先试 org-shared KB（kbShares.resourceAccessibleViaSharedKB），
            // 再试 shared-agent 授权（GetSharedAgentForTenant + GetMessageFileBindings）。
            // 两条授予路径都未翻译（波 5）→ 恒 403（方向偏保守）。
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
}
