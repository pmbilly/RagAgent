package com.ragagent.mcp.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.BizException;
import com.ragagent.mcp.domain.McpConfigFingerprint;
import com.ragagent.mcp.domain.McpMetadata;
import com.ragagent.mcp.domain.McpMetadataSummary;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpTool;
import com.ragagent.mcp.mapper.McpMetadataRepository;
import com.ragagent.mcp.mapper.McpServiceMapper;
import com.ragagent.mcp.protocol.InitializeResult;
import com.ragagent.mcp.protocol.McpClient;
import com.ragagent.mcp.protocol.McpClientConfig;
import com.ragagent.mcp.protocol.McpClientFactory;
import com.ragagent.mcp.protocol.McpContext;
import com.ragagent.mcp.protocol.McpOAuthSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * MCP 目录快照应用层（对照 Go internal/application/service/mcp_metadata.go，
 * 那些方法在 Go 里挂在 mcpServiceService 上；Java 拆成独立类，依赖方向单向：
 * {@link McpServiceService} → 本类）。
 *
 * <p>设计要点（逐条对照 Go 注释）：</p>
 * <ul>
 *   <li>快照是**完整且显式同步**的目录，绝不做部分发布；</li>
 *   <li>刷新失败保留上一次快照供排查（配置指纹把旧连接的快照挡在执行路径之外）；</li>
 *   <li>OAuth 快照属于**授权 principal**，不是创建服务的管理员，更不是租户级；</li>
 *   <li>提交时三重护栏：工具名非空且不重复、序列化不超过 8 MiB、连接未被并发修改。</li>
 * </ul>
 */
@Service
public class McpMetadataService {

    private static final Logger log = LoggerFactory.getLogger(McpMetadataService.class);

    /** 对照 Go commitMCPMetadata：8 MiB 上限 */
    static final long MAX_METADATA_BYTES = 8L * 1024 * 1024;

    /** 对照 Go maxLoggedTools */
    private static final int MAX_LOGGED_TOOLS = 30;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 对照 Go：整个刷新的 30 秒超时 */
    private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(30);

    private final McpServiceMapper mcpServiceMapper;
    private final McpMetadataRepository metadataRepo;
    private final Optional<McpOAuthSupport> oauthSupport;

    public McpMetadataService(McpServiceMapper mcpServiceMapper,
                              McpMetadataRepository metadataRepo,
                              Optional<McpOAuthSupport> oauthSupport) {
        this.mcpServiceMapper = mcpServiceMapper;
        this.metadataRepo = metadataRepo;
        this.oauthSupport = oauthSupport;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** 对照 metadataPrincipal：非 OAuth → ""；OAuth 但无 principal → ErrMCPOAuthPrincipalRequired */
    static String metadataPrincipal(McpService service) {
        if (service.getAuthConfig() == null || !service.getAuthConfig().isOAuth()) {
            return "";
        }
        String storageId = McpPrincipal.storageId(McpPrincipal.oauthPrincipalFromContext());
        if (storageId.isEmpty()) {
            throw McpMetadataException.principalRequired();
        }
        return storageId;
    }

    /** 对照 loadServiceForMetadata：服务不存在或 tenant==0 一律 ErrMCPServiceNotFound */
    private McpService loadService(long tenant, String id) {
        McpService service = mcpServiceMapper.getByIdForTenant(tenant, id);
        if (service == null || tenant == 0) {
            throw McpMetadataException.serviceNotFound();
        }
        return service;
    }

    // ── 读 ───────────────────────────────────────────────────────────────

    /**
     * 对照 GetMCPMetadata：只读已落库快照；null = 从未同步过。
     * Stale = 快照指纹 ≠ 当前服务配置指纹（文档编辑不影响上游身份，故不算陈旧）。
     */
    public McpMetadata getMCPMetadata(long tenant, String id) {
        McpService service = loadService(tenant, id);
        String principal = metadataPrincipal(service);
        McpMetadata snapshot = metadataRepo.getMetadata(tenant, id, principal);
        if (snapshot != null) {
            snapshot.setStale(!Objects.equals(snapshot.getConfigFingerprint(),
                    McpConfigFingerprint.of(service)));
        }
        return snapshot;
    }

    /**
     * 对照 ListMCPMetadataSummaries：只带计数的列表卡片。
     *
     * <p>principals 恒含 ""（非 OAuth 快照），若上下文里有 OAuth principal 再加入它——
     * 因此每个服务只会命中"自己该看的那一份"（OAuth 服务看自己的，
     * 其它服务看共享的那份）。</p>
     */
    public Map<String, McpMetadataSummary> listMCPMetadataSummaries(long tenant,
                                                                    List<McpService> services) {
        Map<String, McpMetadataSummary> out = new LinkedHashMap<>();
        if (services == null || services.isEmpty()) {
            return out;
        }
        List<String> principals = new ArrayList<>();
        principals.add("");
        String p = McpPrincipal.storageId(McpPrincipal.oauthPrincipalFromContext());
        if (!p.isEmpty()) {
            principals.add(p);
        }
        List<McpMetadataSummary> rows = metadataRepo.listMetadataSummaries(tenant, principals);

        Map<String, McpMetadataSummary> byKey = new LinkedHashMap<>();
        for (McpMetadataSummary row : rows) {
            if (row == null) {
                continue;
            }
            byKey.put(key(row.getServiceId(), row.getPrincipal()), row);
        }
        for (McpService service : services) {
            if (service == null) {
                continue;
            }
            String principal;
            try {
                principal = metadataPrincipal(service);
            } catch (RuntimeException e) {
                // 对照 Go：OAuth 服务缺 principal 时跳过该服务，不影响整批列表
                continue;
            }
            McpMetadataSummary row = byKey.get(key(service.getId(), principal));
            if (row != null) {
                out.put(service.getId(), row);
            }
        }
        return out;
    }

    /** 对照 Go 的 {@code service.ID + "\x00" + principal} 复合键 */
    private static String key(String serviceId, String principal) {
        return (serviceId == null ? "" : serviceId) + '\0' + (principal == null ? "" : principal);
    }

    // ── 写 ───────────────────────────────────────────────────────────────

    /**
     * 对照 commitMCPMetadata：校验 → 8 MiB 上限 → 连接未变校验 → 落库 → 回读。
     *
     * @param started 刷新**开始**的时间（不是提交时间），保证迟到但更旧的刷新不会覆盖
     */
    McpMetadata commitMCPMetadata(long tenant, McpService service, String principal,
                                  List<McpTool> listed, String instructions,
                                  String serverName, String serverVersion, String serverDescription,
                                  OffsetDateTime started) {
        Set<String> seen = new HashSet<>();
        List<McpTool> tools = listed == null ? List.of() : listed;
        for (McpTool tool : tools) {
            if (tool == null || tool.getName().isEmpty() || !seen.add(tool.getName())) {
                throw McpMetadataException.invalidOrTooLarge(McpMetadataException.Kind.INVALID_TOOLS);
            }
        }

        McpMetadata snapshot = new McpMetadata();
        snapshot.setTenantId(tenant);
        snapshot.setServiceId(service.getId());
        snapshot.setPrincipal(principal);
        snapshot.setConfigFingerprint(McpConfigFingerprint.of(service));
        snapshot.setTools(new ArrayList<>(tools));
        snapshot.setInstructions(instructions);
        snapshot.setServerName(serverName);
        snapshot.setServerVersion(serverVersion);
        snapshot.setServerDescription(serverDescription);
        snapshot.setSyncedAt(started);

        if (serializedSize(snapshot) > MAX_METADATA_BYTES) {
            throw McpMetadataException.invalidOrTooLarge(McpMetadataException.Kind.TOO_LARGE);
        }

        McpService current = mcpServiceMapper.getByIdForTenant(tenant, service.getId());
        if (current == null
                || !Objects.equals(McpConfigFingerprint.of(current), snapshot.getConfigFingerprint())) {
            // 刷新途中连接配置被改：不发布任何部分目录
            throw McpMetadataException.connectionChanged();
        }
        metadataRepo.saveMetadata(snapshot);
        return getMCPMetadata(tenant, service.getId());
    }

    /**
     * 对照 Go：{@code json.Marshal(snapshot)} 的字节长度 > 8 MiB 即拒绝。
     *
     * <p>Go 序列化的字段是 service_id / tools / instructions / server_name /
     * server_version / server_description / synced_at（TenantID、Principal、
     * ConfigFingerprint、Stale 是 {@code json:"-"}）。Java 复刻同样的形状；
     * 时间戳用 ISO-8601 而非 RFC3339Nano，长度差异在毫秒/纳秒尾零级别，
     * 对 8 MiB 量级的门禁没有影响。</p>
     */
    static long serializedSize(McpMetadata snapshot) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("service_id", snapshot.getServiceId());
        shape.put("tools", snapshot.getTools());
        shape.put("instructions", snapshot.getInstructions());
        shape.put("server_name", snapshot.getServerName());
        shape.put("server_version", snapshot.getServerVersion());
        shape.put("server_description", snapshot.getServerDescription());
        shape.put("synced_at", snapshot.getSyncedAt() == null ? null
                : snapshot.getSyncedAt().toString());
        try {
            // 工具用与本模块相同的序列化视角（含 require_approval 别名），避免形状偏差
            return JSON.writeValueAsBytes(shape).length;
        } catch (Exception e) {
            throw BizException.internal("failed to serialize MCP metadata: " + e.getMessage());
        }
    }

    /**
     * 对照 PersistMCPMetadata：把在**已授权连接**上列出的完整目录落库
     * （聊天中的 OAuth 用户据此存自己的快照，不需要管理员去设置页刷新）。
     */
    public void persistMCPMetadata(long tenant, String id, List<McpTool> listed, String instructions) {
        McpService service = loadService(tenant, id);
        String principal = metadataPrincipal(service);
        commitMCPMetadata(tenant, service, principal, listed, instructions, "", "", "", now());
    }

    /**
     * 对照 RefreshMCPMetadata：显式连接并**原子替换**整份快照。
     *
     * <p>不做任何用户操作，也绝不发布部分的 tools/list。刷新失败保留上次快照；
     * 配置指纹把旧连接的快照挡在执行路径之外。</p>
     */
    public McpMetadata refreshMCPMetadata(long tenant, String id) {
        McpService service = loadService(tenant, id);
        String principal = metadataPrincipal(service);
        OffsetDateTime started = now();

        McpClientConfig config = new McpClientConfig(service);
        if (service.getAuthConfig() != null && service.getAuthConfig().isOAuth()) {
            config = new McpClientConfig(service, tenant, McpPrincipal.oauthPrincipalFromContext(),
                    null, oauthSupport.orElse(null));
        }

        McpClient client;
        try {
            client = McpClientFactory.createClient(config);
        } catch (RuntimeException e) {
            throw BizException.internal("could not refresh MCP directory: " + e.getMessage());
        }

        McpContext refreshCtx = McpContext.deadline(Instant.now().plus(REFRESH_TIMEOUT));
        // 对照 Go：defer Disconnect 在**整个函数退出时**执行——连接在
        // commitMCPMetadata 期间仍然保持（提交本身不碰客户端，但保持顺序一致）。
        try {
            try {
                client.connect(refreshCtx);
            } catch (RuntimeException e) {
                throw BizException.internal("could not refresh MCP directory: " + e.getMessage());
            }
            InitializeResult init;
            try {
                init = client.initialize(refreshCtx);
            } catch (RuntimeException e) {
                throw BizException.internal("could not refresh MCP directory: " + e.getMessage());
            }
            List<McpTool> listed;
            try {
                listed = client.listTools(refreshCtx);
            } catch (RuntimeException e) {
                throw BizException.internal("could not refresh complete MCP directory: "
                        + e.getMessage());
            }

            int logged = 0;
            for (int i = 0; i < listed.size(); i++) {
                McpTool tool = listed.get(i);
                if (tool == null) {
                    continue;
                }
                if (logged >= MAX_LOGGED_TOOLS) {
                    log.debug("MCP metadata omitted remaining tools from debug listing service_id={}",
                            service.getId());
                    break;
                }
                log.debug("MCP metadata tool[{}] service_id={} name={} description_len={} schema_len={}",
                        i, service.getId(), sanitizeForLog(tool.getName()),
                        utf8Length(tool.getDescription()), schemaLength(tool));
                logged++;
            }
            log.debug("MCP metadata listed {} tools service_id={} instructions_len={} "
                            + "server_description_len={}",
                    listed.size(), service.getId(), utf8Length(init.instructions()),
                    utf8Length(init.serverInfo().description()));

            return commitMCPMetadata(tenant, service, principal, listed, init.instructions(),
                    init.serverInfo().name(), init.serverInfo().version(),
                    init.serverInfo().description(), started);
        } finally {
            try {
                client.disconnect();
            } catch (RuntimeException ignored) {
                // 对照 Go defer func(){ _ = client.Disconnect() }()
            }
        }
    }

    /** 对照 Go {@code len(string)}：按 UTF-8 字节数 */
    private static int utf8Length(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 对照 Go {@code len(tool.InputSchema)}（json.RawMessage 的原始字节数） */
    private static int schemaLength(McpTool tool) {
        if (tool.getInputSchema() == null) {
            return 0;
        }
        try {
            return JSON.writeValueAsBytes(tool.getInputSchema()).length;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 日志脱敏（工具名来自上游，可能带控制字符） */
    private static String sanitizeForLog(String s) {
        return com.ragagent.common.security.LogSanitizer.sanitize(s);
    }
}
