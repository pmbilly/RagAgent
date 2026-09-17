package com.ragagent.mcp.service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.mcp.domain.McpAdvancedConfig;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;
import com.ragagent.mcp.domain.McpMetadataSummary;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.domain.McpResource;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpTestResult;
import com.ragagent.mcp.domain.McpTool;
import com.ragagent.mcp.domain.McpTransportType;
import com.ragagent.mcp.mapper.McpOAuthRepository;
import com.ragagent.mcp.mapper.McpServiceMapper;
import com.ragagent.mcp.protocol.InitializeResult;
import com.ragagent.mcp.protocol.McpClient;
import com.ragagent.mcp.protocol.McpClientConfig;
import com.ragagent.mcp.protocol.McpClientFactory;
import com.ragagent.mcp.protocol.McpClientManager;
import com.ragagent.mcp.protocol.McpContext;
import com.ragagent.mcp.protocol.McpOAuthRequiredException;
import com.ragagent.mcp.protocol.McpOAuthSupport;
import com.ragagent.mcp.protocol.McpServiceUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * MCP 服务应用层（对照 Go internal/application/service/mcp_service.go 的
 * mcpServiceService；目录快照部分见 {@link McpMetadataService}）。
 *
 * <p><b>两条硬契约</b>：</p>
 * <ol>
 *   <li>Get/List **返回未脱敏实体**（含明文 AuthConfig）——脱敏是 DTO 的构造期职责
 *       （dto.McpServiceResponse 里根本没有秘密字段）。内部调用方（MCP 客户端构造、
 *       凭据元数据推导）需要未脱敏形态才能工作。</li>
 *   <li>凭据写入后**必须**关闭该服务的活动连接，否则下一次上游调用仍带旧凭据。</li>
 * </ol>
 *
 * <p>协议客户端（{@code com.ragagent.mcp.protocol}，对照 internal/mcp/*）由协议模块提供；
 * 本层只通过 {@link McpClientFactory} / {@link McpClientManager} 使用它。
 * 两者都可缺省装配（{@code Optional}）：协议模块未接线时服务仍能启动，
 * 只有真的去连 MCP 服务时才会报"not available"。</p>
 */
@Service
public class McpServiceService {

    private static final Logger log = LoggerFactory.getLogger(McpServiceService.class);

    /** 对照 Go：测试连接的 30 秒超时 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private static final String STDIO_DISABLED_MESSAGE =
            "stdio transport is disabled for security reasons; "
                    + "please use SSE or HTTP Streamable transport instead";

    private final McpServiceMapper mcpServiceMapper;
    private final McpOAuthRepository oauthRepo;
    private final McpMetadataService metadataService;
    private final Optional<McpClientManager> clientManager;
    private final Optional<McpOAuthSupport> oauthSupport;

    public McpServiceService(McpServiceMapper mcpServiceMapper,
                             McpOAuthRepository oauthRepo,
                             McpMetadataService metadataService,
                             SsrfGuard ssrfGuard,
                             Optional<McpClientManager> clientManager,
                             Optional<McpOAuthSupport> oauthSupport) {
        this.mcpServiceMapper = mcpServiceMapper;
        this.oauthRepo = oauthRepo;
        this.metadataService = metadataService;
        this.clientManager = clientManager;
        this.oauthSupport = oauthSupport;
        // 出站 URL 校验器与协议层共用同一实例（对照 LlmTransport.setSsrfGuard 的注入方式），
        // 否则 DB 运行时调谐过的白名单只对一半调用点生效。
        McpServiceUrls.setSsrfGuard(ssrfGuard);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ── 创建 / 读取 ──────────────────────────────────────────────────────

    /**
     * 对照 CreateMCPService：stdio 硬拒绝 → 出站 URL SSRF 校验 → 默认高级配置 → 落库。
     *
     * <p>SSRF 校验在 service 层与 handler 层各做一次（Go 亦然）：handler 负责给用户
     * 友好的 400 文案，service 层保证"无论谁调用都过不了"。此处的失败按 Go 的
     * service 错误路径处理（handler 会包成 500）。</p>
     */
    public void createMCPService(McpService service) {
        // stdio 传输因安全原因被禁用（命令注入）
        if (McpTransportType.STDIO.value().equals(service.getTransportType())) {
            throw BizException.internal(STDIO_DISABLED_MESSAGE);
        }
        validateOutboundUrls(service);

        if (service.getAdvancedConfig() == null) {
            service.setAdvancedConfig(McpAdvancedConfig.defaults());
        }
        if (service.getId() == null || service.getId().isEmpty()) {
            // 对照 Go BeforeCreate 钩子
            service.setId(UUID.randomUUID().toString());
        }
        OffsetDateTime ts = now();
        service.setCreatedAt(ts);
        service.setUpdatedAt(ts);

        mcpServiceMapper.insert(service);
    }

    /** 对照 GetMCPServiceByID：**返回未脱敏实体**；不存在 → "MCP service not found" */
    public McpService getMCPServiceByID(long tenantId, String id) {
        McpService service = mcpServiceMapper.getByIdForTenant(tenantId, id);
        if (service == null) {
            throw BizException.notFound("MCP service not found");
        }
        return service;
    }

    /** 对照 repo.GetByID 的裸查询：不存在返回 null（内部/可选场景用） */
    public McpService findByID(long tenantId, String id) {
        return mcpServiceMapper.getByIdForTenant(tenantId, id);
    }

    /** 对照 ListMCPServices：同样返回未脱敏实体 */
    public List<McpService> listMCPServices(long tenantId) {
        return mcpServiceMapper.listForTenant(tenantId);
    }

    /** 对照 ListMCPServicesByIDs：空 ids 直接返回空列表（不查库） */
    public List<McpService> listMCPServicesByIDs(long tenantId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mcpServiceMapper.listByIdsForTenant(tenantId, ids);
    }

    /** 对照 ListMCPMetadataSummaries（实现在 McpMetadataService，此处转发以对齐接口形状） */
    public Map<String, McpMetadataSummary> listMCPMetadataSummaries(long tenantId,
                                                                    List<McpService> services) {
        return metadataService.listMCPMetadataSummaries(tenantId, services);
    }

    // ── 更新（Go 侧 206 行的单函数：标量存在性 + 非标量 + 不碰密钥） ────────

    /**
     * 对照 UpdateMCPService（mcp_service.go:122-325）。
     *
     * <p><b>标量字段的存在性更新语义</b>：name / description / usage_instructions /
     * enabled 的零值无法区分"没传"与"显式清空"，所以用 handler 传来的
     * updateFields 存在性映射；未提供的字段**保持不变**。</p>
     *
     * <p><b>更新不碰密钥</b>：AuthConfig 里的 apiKey / token 从不经主 PUT 合并
     * （handler 已剥离，这里是纵深防御）。凭据变更走 /credentials 子资源，
     * 由 {@link #updateMCPCredentials} 自己负责关连接。</p>
     *
     * <p><b>configChanged 必须与合并前的快照比较</b>：下面的合并会让
     * existing.url 等字段直接指向入参对象，合并后再比较恒等。</p>
     */
    public void updateMCPService(McpService service, Map<String, Boolean> updateFields) {
        Map<String, Boolean> fields = updateFields == null ? Map.of() : updateFields;

        McpService existing = mcpServiceMapper.getByIdForTenant(service.getTenantId(), service.getId());
        if (existing == null) {
            throw BizException.internal("MCP service not found");
        }
        if (existing.isIsBuiltin()) {
            throw BizException.internal("builtin MCP services cannot be updated");
        }

        // 合并后的最终传输类型
        String finalTransportType = existing.getTransportType();
        if (service.getTransportType() != null && !service.getTransportType().isEmpty()) {
            finalTransportType = service.getTransportType();
        }
        if (McpTransportType.STDIO.value().equals(finalTransportType)) {
            throw BizException.internal(STDIO_DISABLED_MESSAGE);
        }

        if (Boolean.TRUE.equals(fields.get("usage_instructions"))) {
            existing.setUsageInstructions(service.getUsageInstructions());
        }

        // 记录更新前的 enabled（必须在任何合并之前）
        boolean oldEnabled = existing.isEnabled();

        // 合并前快照：驱动 configChanged 的字段（AuthConfig 有意不参与比较——
        // 凭据走 /credentials 子资源，主 PUT 不接受秘密字段）
        boolean preUrlSet = existing.getUrl() != null;
        String preUrl = preUrlSet ? existing.getUrl() : "";
        boolean preStdioSet = existing.getStdioConfig() != null;
        String preStdioCommand = "";
        List<String> preStdioArgs = List.of();
        if (preStdioSet) {
            preStdioCommand = nullToEmpty(existing.getStdioConfig().getCommand());
            preStdioArgs = new ArrayList<>(nullToEmpty(existing.getStdioConfig().getArgs()));
        }
        String preTransportType = existing.getTransportType();
        Map<String, String> preHeaders = new HashMap<>();
        if (existing.getAuthConfig() != null && existing.getAuthConfig().getCustomHeaders() != null) {
            preHeaders.putAll(existing.getAuthConfig().getCustomHeaders());
        }
        McpAuthType preAuthType = authTypeOf(existing.getAuthConfig());
        String preApiKeyHeader = existing.getAuthConfig() == null
                ? "" : nullToEmpty(existing.getAuthConfig().getApiKeyHeader());

        // CustomHeaders 走主 PUT（结构性、非秘密）：nil 保持、非 nil 替换。
        // auth_type / scopes / auth_server_metadata_url 也是非秘密配置，同样在此合并。
        if (service.getAuthConfig() != null) {
            if (existing.getAuthConfig() == null) {
                existing.setAuthConfig(new McpAuthConfig());
            }
            McpAuthConfig incoming = service.getAuthConfig();
            McpAuthConfig target = existing.getAuthConfig();

            if (incoming.getCustomHeaders() != null) {
                target.setCustomHeaders(incoming.getCustomHeaders());
            }
            // 只在显式提供时覆盖 OAuth 配置，避免只带 custom_headers 的部分 PUT
            // 把既有 auth_type / scopes 抹掉（空/缺省 = 不改）
            if (Boolean.TRUE.equals(fields.get("auth_type"))
                    || authTypeOf(incoming) != McpAuthType.NONE) {
                target.setAuthType(incoming.getAuthType());
            }
            // APIKeyHeader 非秘密；空串的含义是"用默认 X-API-Key"，故与 scopes 同规则
            if (Boolean.TRUE.equals(fields.get("api_key_header"))
                    || !nullToEmpty(incoming.getApiKeyHeader()).isEmpty()) {
                target.setApiKeyHeader(incoming.getApiKeyHeader());
            }
            if (incoming.getScopes() != null) {
                target.setScopes(incoming.getScopes());
            }
            if (!nullToEmpty(incoming.getAuthServerMetadataUrl()).isEmpty()) {
                target.setAuthServerMetadataUrl(incoming.getAuthServerMetadataUrl());
            }
        }

        // 标量零值无法区分省略与显式更新 → 用存在性映射
        if (Boolean.TRUE.equals(fields.get("name"))) {
            existing.setName(service.getName());
        }
        if (Boolean.TRUE.equals(fields.get("description"))) {
            existing.setDescription(service.getDescription());
        }
        if (Boolean.TRUE.equals(fields.get("enabled"))) {
            existing.setEnabled(service.isEnabled());
        }
        if (service.getTransportType() != null && !service.getTransportType().isEmpty()) {
            existing.setTransportType(service.getTransportType());
        }
        if (service.getUrl() != null) {
            existing.setUrl(service.getUrl());
        }
        if (service.getStdioConfig() != null) {
            existing.setStdioConfig(service.getStdioConfig());
        }
        if (service.getEnvVars() != null) {
            existing.setEnvVars(service.getEnvVars());
        }
        if (service.getHeaders() != null) {
            existing.setHeaders(service.getHeaders());
        }
        if (service.getAdvancedConfig() != null) {
            existing.setAdvancedConfig(service.getAdvancedConfig());
        }

        validateOutboundUrls(existing);
        existing.setUpdatedAt(now());

        mcpServiceMapper.updatePartial(existing);

        // ── 是否发生了"关键配置变更"（URL / stdio / 传输类型 / 自定义头 / 鉴权形态） ──
        boolean configChanged = false;
        boolean currUrlSet = existing.getUrl() != null;
        if (currUrlSet != preUrlSet) {
            configChanged = true;
        } else if (currUrlSet && !existing.getUrl().equals(preUrl)) {
            configChanged = true;
        }
        boolean currStdioSet = existing.getStdioConfig() != null;
        if (currStdioSet != preStdioSet) {
            configChanged = true;
        } else if (currStdioSet) {
            // 对照 Go slices.Equal：nil 与空切片相等
            List<String> currArgs = nullToEmpty(existing.getStdioConfig().getArgs());
            if (!nullToEmpty(existing.getStdioConfig().getCommand()).equals(preStdioCommand)
                    || !currArgs.equals(preStdioArgs)) {
                configChanged = true;
            }
        }
        if (!Objects.equals(existing.getTransportType(), preTransportType)) {
            configChanged = true;
        }
        Map<String, String> currHeaders = new HashMap<>();
        if (existing.getAuthConfig() != null && existing.getAuthConfig().getCustomHeaders() != null) {
            currHeaders.putAll(existing.getAuthConfig().getCustomHeaders());
        }
        if (!currHeaders.equals(preHeaders)) {
            configChanged = true;
        }
        if (existing.getAuthConfig() != null
                && (authTypeOf(existing.getAuthConfig()) != preAuthType
                || !nullToEmpty(existing.getAuthConfig().getApiKeyHeader()).equals(preApiKeyHeader))) {
            configChanged = true;
        }

        String name = LogSanitizer.sanitize(existing.getName());
        // 关连接的三种情形（按顺序，互斥）：
        // 1) 服务已被禁用；2) 关键配置变了（要用新配置重连）；3) 刚从禁用切到启用
        if (!existing.isEnabled()) {
            closeClientQuietly(service.getId());
            log.info("MCP service disabled, connection closed: {} (ID: {})", name, service.getId());
        } else if (configChanged) {
            closeClientQuietly(service.getId());
            log.info("MCP service config changed, connection closed: {} (ID: {})",
                    name, service.getId());
        } else if (oldEnabled != existing.isEnabled() && existing.isEnabled()) {
            closeClientQuietly(service.getId());
            log.info("MCP service enabled, existing connection closed: {} (ID: {})",
                    name, service.getId());
        }
        log.info("MCP service updated: {} (ID: {}), enabled: {}", name, service.getId(),
                existing.isEnabled());
    }

    // ── 删除 ─────────────────────────────────────────────────────────────

    /** 对照 DeleteMCPService：builtin 拒绝；先关连接再软删 */
    public void deleteMCPService(long tenantId, String id) {
        McpService existing = mcpServiceMapper.getByIdForTenant(tenantId, id);
        if (existing == null) {
            throw BizException.internal("MCP service not found");
        }
        if (existing.isIsBuiltin()) {
            throw BizException.internal("builtin MCP services cannot be deleted");
        }
        closeClientQuietly(id);
        mcpServiceMapper.softDelete(tenantId, id, now());
        log.info("MCP service deleted: {} (ID: {})", LogSanitizer.sanitize(existing.getName()), id);
    }

    // ── 连接测试 / 工具 / 资源 ────────────────────────────────────────────

    /**
     * 对照 mcpTestFailure：把普通连接错误升级为显式的 "OAuth required" 信号
     * （服务端回了 RFC 9728 挑战），让 UI 引导用户改用 OAuth 策略。
     */
    static McpTestResult mcpTestFailure(RuntimeException err, String prefix) {
        if (err instanceof McpOAuthRequiredException) {
            McpTestResult r = McpTestResult.fail("This MCP server requires OAuth authorization. "
                    + "Switch the auth method to OAuth 2.0 and authorize.");
            r.setOauthRequired(true);
            return r;
        }
        return McpTestResult.fail(prefix + ": " + err.getMessage());
    }

    /** 对照 TestMCPService：临时客户端；OAuth 服务接上按 principal 的 token 存储 */
    public McpTestResult testMCPService(long tenantId, String id) {
        McpService service = getMCPServiceByID(tenantId, id);

        McpClientConfig config = new McpClientConfig(service);
        if (service.getAuthConfig() != null && service.getAuthConfig().isOAuth()) {
            // 对照 Go：config.TenantID / config.Principal / config.OAuthRepo
            config = new McpClientConfig(service, TenantContext.currentTenantId(),
                    McpPrincipal.fromContext(), null, oauthSupport.orElse(null));
        }

        McpClient client;
        try {
            client = McpClientFactory.createClient(config);
        } catch (RuntimeException e) {
            return McpTestResult.fail("Failed to create client: " + e.getMessage());
        }

        McpContext testCtx = McpContext.deadline(Instant.now().plus(CONNECT_TIMEOUT));
        try {
            client.connect(testCtx);
        } catch (RuntimeException e) {
            return mcpTestFailure(e, "Connection failed");
        }

        try {
            InitializeResult initResult;
            try {
                initResult = client.initialize(testCtx);
            } catch (RuntimeException e) {
                return mcpTestFailure(e, "Initialization failed");
            }

            List<McpTool> tools;
            try {
                tools = client.listTools(testCtx);
            } catch (RuntimeException e) {
                log.warn("Failed to list tools: {}", e.getMessage());
                tools = List.of();
            }
            List<McpResource> resources;
            try {
                resources = client.listResources(testCtx);
            } catch (RuntimeException e) {
                log.warn("Failed to list resources: {}", e.getMessage());
                resources = List.of();
            }

            McpTestResult result = McpTestResult.ok(
                    "Connected successfully to " + initResult.serverInfo().name()
                            + " v" + initResult.serverInfo().version());
            result.setDescription(initResult.serverInfo().description());
            result.setTools(new ArrayList<>(tools));
            result.setResources(new ArrayList<>(resources));
            return result;
        } finally {
            try {
                client.disconnect();
            } catch (RuntimeException ignored) {
                // 对照 Go defer client.Disconnect()：清理失败不影响结果
            }
        }
    }

    /** 对照 GetMCPServiceTools：走缓存连接（manager 负责 connect + initialize） */
    public List<McpTool> getMCPServiceTools(long tenantId, String id) {
        McpService service = getMCPServiceByID(tenantId, id);
        McpClient client;
        try {
            client = clients().getOrCreateClient(McpContext.none(), service);
        } catch (RuntimeException e) {
            throw BizException.internal("failed to get MCP client: " + e.getMessage());
        }
        try {
            return client.listTools(McpContext.none());
        } catch (RuntimeException e) {
            throw BizException.internal("failed to list tools: " + e.getMessage());
        }
    }

    /** 对照 GetMCPServiceResources */
    public List<McpResource> getMCPServiceResources(long tenantId, String id) {
        McpService service = getMCPServiceByID(tenantId, id);
        McpClient client;
        try {
            client = clients().getOrCreateClient(McpContext.none(), service);
        } catch (RuntimeException e) {
            throw BizException.internal("failed to get MCP client: " + e.getMessage());
        }
        try {
            return client.listResources(McpContext.none());
        } catch (RuntimeException e) {
            throw BizException.internal("failed to list resources: " + e.getMessage());
        }
    }

    // ── 凭据子资源 ───────────────────────────────────────────────────────

    /**
     * 对照 UpdateMCPCredentials。
     *
     * <ul>
     *   <li>{@code apiKey == null && token == null} → 空操作，返回当前状态</li>
     *   <li>{@code apiKey == ""}（显式空串）→ **空操作**：清空是 ClearMCPCredential 的职责</li>
     *   <li>{@code apiKey == "sk-..."} → 替换既有值</li>
     *   <li>builtin 服务禁止改凭据（与 UpdateMCPService 的限制对称）</li>
     *   <li>合并前**总是重新取** AuthConfig，避免抹掉 CustomHeaders 或另一个凭据字段</li>
     * </ul>
     *
     * <p>写成功后必须关连接：否则下一次上游调用仍带旧凭据。</p>
     */
    public McpService updateMCPCredentials(long tenantId, String id, String apiKey, String token) {
        McpService existing = getMCPServiceByID(tenantId, id);
        if (existing.isIsBuiltin()) {
            throw BizException.internal("builtin MCP services cannot have credentials modified");
        }
        if (existing.getAuthConfig() == null) {
            existing.setAuthConfig(new McpAuthConfig());
        }

        boolean changed = false;
        if (apiKey != null && !apiKey.isEmpty()
                && !apiKey.equals(nullToEmpty(existing.getAuthConfig().getApiKey()))) {
            existing.getAuthConfig().setApiKey(apiKey);
            changed = true;
        }
        if (token != null && !token.isEmpty()
                && !token.equals(nullToEmpty(existing.getAuthConfig().getToken()))) {
            existing.getAuthConfig().setToken(token);
            changed = true;
        }
        if (!changed) {
            return existing;
        }

        existing.setUpdatedAt(now());
        mcpServiceMapper.updatePartial(existing);

        closeClientQuietly(id);
        log.info("MCP credentials updated, connection closed: {} (ID: {})",
                LogSanitizer.sanitize(existing.getName()), id);
        return existing;
    }

    /**
     * 对照 ClearMCPCredential：幂等——清一个本来为空的字段不写库、不重连。
     *
     * @param field 只接受 "api_key" / "token"，其它值报 unknown credential field
     */
    public void clearMCPCredential(long tenantId, String id, String field) {
        McpService existing = getMCPServiceByID(tenantId, id);
        if (existing.isIsBuiltin()) {
            throw BizException.internal("builtin MCP services cannot have credentials modified");
        }
        if (existing.getAuthConfig() == null) {
            return; // 无可清空
        }

        boolean changed = false;
        switch (field == null ? "" : field) {
            case "api_key" -> {
                if (!nullToEmpty(existing.getAuthConfig().getApiKey()).isEmpty()) {
                    existing.getAuthConfig().setApiKey("");
                    changed = true;
                }
            }
            case "token" -> {
                if (!nullToEmpty(existing.getAuthConfig().getToken()).isEmpty()) {
                    existing.getAuthConfig().setToken("");
                    changed = true;
                }
            }
            default -> throw BizException.badRequest("unknown credential field: " + field);
        }
        if (!changed) {
            return;
        }

        existing.setUpdatedAt(now());
        mcpServiceMapper.updatePartial(existing);

        closeClientQuietly(id);
        log.info("MCP credential cleared by user: id={} field={}, connection closed",
                LogSanitizer.sanitize(id), field);
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────

    /** 对照 Go mcp.ValidateServiceOutboundURLs，错误按 service 侧路径包装 */
    private static void validateOutboundUrls(McpService service) {
        try {
            McpServiceUrls.validateServiceOutboundUrls(service);
        } catch (RuntimeException e) {
            throw BizException.internal(e.getMessage());
        }
    }

    /**
     * 关连接：管理器缺失（协议模块尚未接线）时静默跳过——
     * 这是**接线期的降级**，不是可选的优化；管理器一旦就位必须真的关。
     */
    private void closeClientQuietly(String serviceId) {
        clientManager.ifPresent(m -> m.closeClient(serviceId));
    }

    /** 取管理器；缺失说明协议模块尚未装配，属服务端配置错误 */
    private McpClientManager clients() {
        return clientManager.orElseThrow(() ->
                BizException.internal("MCP client manager is not available"));
    }

    /** 对照 Go：空指针是否提供了值用 null 判定，值本身用 "" 表示空 */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static List<String> nullToEmpty(List<String> l) {
        return l == null ? List.of() : l;
    }

    /** McpAuthType 的 null 归一化为 NONE（Go 的零值就是 MCPAuthNone == ""） */
    private static McpAuthType authTypeOf(McpAuthConfig config) {
        if (config == null || config.getAuthType() == null) {
            return McpAuthType.NONE;
        }
        return config.getAuthType();
    }

    /** 保留键序的便利构造（便于测试与调用方构造 updateFields） */
    public static Map<String, Boolean> updateFields(String... keys) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String k : keys) {
            m.put(k, true);
        }
        return m;
    }
}
