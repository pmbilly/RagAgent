package com.ragagent.mcp.protocol;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpResource;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpTool;
import com.ragagent.mcp.domain.McpTransportType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * MCP 连接池。
 *
 * <p>五条必须保持的语义：</p>
 * <ol>
 *   <li><b>按 cacheKey 复用</b>：OAuth 服务按 principal 隔离（每个身份用自己的 token），
 *       其余服务按 serviceId 共享一条连接；</li>
 *   <li><b>并发建连去重</b>：同一 key 上只跑一次建连，其余调用者等同一个 pending；
 *       无关服务之间<b>不得</b>因 I/O 持锁互相阻塞（建连在锁外、独立线程里跑）；</li>
 *   <li><b>UpdatedAt 版本失效</b>：服务的 updatedAt 变了 ⇒ 旧连接退役、重新建连；</li>
 *   <li><b>等待者可独立取消</b>：某个等待者取消不影响共享连接；</li>
 *   <li><b>CloseClient 退役 pending</b>：正在建连的尝试也要能被关掉，并把错误交给等待者。</li>
 * </ol>
 */
public final class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    /** cacheKey 的分隔符（"<serviceID>\0<principal.storageID>"）。 */
    private static final String KEY_SEPARATOR = "\0";

    private final Map<String, ManagedMcpClient> clients = new HashMap<>();
    private final Map<String, PendingConnection> connecting = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final McpOAuthSupport oauthSupport;
    /** manager 级取消信号：Shutdown 时级联取消所有连接生命周期。 */
    private final McpCancellation managerCancellation = new McpCancellation();
    private final ScheduledExecutorService idleCleaner;

    public McpClientManager(McpOAuthSupport oauthSupport) {
        this.oauthSupport = oauthSupport;
        AtomicInteger seq = new AtomicInteger();
        this.idleCleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-idle-cleaner-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        idleCleaner.scheduleWithFixedDelay(this::removeDisconnectedClients,
                McpProtocol.IDLE_CLEANUP_INTERVAL.toMillis(),
                McpProtocol.IDLE_CLEANUP_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** 连接中的在建尝试。 */
    private static final class PendingConnection {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final McpCancellation cancellation;
        final Instant version;
        volatile ManagedMcpClient client;
        volatile RuntimeException error;

        PendingConnection(McpCancellation cancellation, Instant version) {
            this.cancellation = cancellation;
            this.version = version;
        }
    }

    /** 带生命周期的客户端包装：Disconnect 时连带取消生命周期。 */
    private static final class ManagedMcpClient implements McpClient {
        private final McpClient delegate;
        private final McpCancellation cancellation;
        private final Instant version;

        ManagedMcpClient(McpClient delegate, McpCancellation cancellation, Instant version) {
            this.delegate = delegate;
            this.cancellation = cancellation;
            this.version = version;
        }

        @Override
        public void connect(McpContext ctx) {
            delegate.connect(ctx);
        }

        @Override
        public void disconnect() {
            cancellation.cancel();
            delegate.disconnect();
        }

        @Override
        public InitializeResult initialize(McpContext ctx) {
            return delegate.initialize(ctx);
        }

        @Override
        public List<McpTool> listTools(McpContext ctx) {
            return delegate.listTools(ctx);
        }

        @Override
        public List<McpResource> listResources(McpContext ctx) {
            return delegate.listResources(ctx);
        }

        @Override
        public CallToolResult callTool(String name, Map<String, Object> args, McpContext ctx) {
            return delegate.callTool(name, args, ctx);
        }

        @Override
        public ReadResourceResult readResource(String uri, McpContext ctx) {
            return delegate.readResource(uri, ctx);
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public String serviceId() {
            return delegate.serviceId();
        }

        @Override
        public String serverInstructions() {
            return delegate.serverInstructions();
        }
    }

    // ------------------------------------------------------------------
    // 取连接
    // ------------------------------------------------------------------

    /**
     * 取已有连接或新建连接。
     *
     * @param callerCtx 调用方上下文：取消/超时都只影响<b>本次等待</b>，不影响共享连接
     */
    public McpClient getOrCreateClient(McpContext callerCtx, McpService service) {
        if (callerCtx.isCancelled()) {
            callerCtx.throwIfCancelled();
        }
        if (!service.isEnabled()) {
            throw new McpException("MCP service " + service.getName() + " is not enabled");
        }
        McpTransportType transportType = McpTransportType.fromValue(service.getTransportType());
        if (transportType == McpTransportType.STDIO) {
            throw new McpException(McpClientFactory.STDIO_DISABLED_MESSAGE);
        }

        Long tenantId = null;
        TenantContext.Principal principal = null;
        if (McpClientFactory.isOAuth(service)) {
            tenantId = TenantContext.currentTenantId();
            principal = oauthPrincipal();
            if (!valid(principal)) {
                throw new McpException("principal context is required to connect to OAuth MCP service "
                        + service.getName());
            }
        }
        String key = cacheKey(service, principal);
        Instant version = versionOf(service);

        PendingConnection pending;
        lock.writeLock().lock();
        try {
            if (managerCancellation.isCancelled()) {
                throw new McpException(McpErrorCode.CONNECTION_CLOSED, "context canceled");
            }
            ManagedMcpClient existing = clients.get(key);
            if (existing != null && existing.isConnected()) {
                if (Objects.equals(existing.version, version)) {
                    return existing;
                }
                // 配置已更新（updatedAt 变了）：退役旧连接，重建
                existing.disconnect();
                clients.remove(key);
            }
            PendingConnection current = connecting.get(key);
            if (current != null && !Objects.equals(current.version, version)) {
                current.cancellation.cancel();
                connecting.remove(key);
                current = null;
            }
            if (current == null) {
                McpCancellation life = managerCancellation.child();
                PendingConnection created = new PendingConnection(life, version);
                connecting.put(key, created);
                McpClientConfig config = new McpClientConfig(service, tenantId, principal, null, oauthSupport);
                Thread.ofVirtual().name("mcp-connect-" + service.getId())
                        .start(() -> connectClient(life, key, config, created));
                current = created;
            }
            pending = current;
        } finally {
            lock.writeLock().unlock();
        }

        // 等待时绝不再持锁：无关服务不能因为一条慢连接被卡住
        awaitPending(callerCtx, pending);
        if (pending.error != null) {
            throw pending.error;
        }
        return pending.client;
    }

    /** 等待"调用方取消"与"建连完成"两者先到者。 */
    private void awaitPending(McpContext callerCtx, PendingConnection pending) {
        CompletableFuture<Void> caller = callerCtx.cancellation().future();
        Duration remaining = callerCtx.remaining();
        try {
            if (remaining == null) {
                CompletableFuture.anyOf(caller, pending.done).get();
            } else {
                CompletableFuture.anyOf(caller, pending.done)
                        .get(Math.max(remaining.toMillis(), 1), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(McpErrorCode.CONNECTION_CLOSED, "context canceled", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new McpException(McpErrorCode.CONNECTION_CLOSED, "connection failed: " + cause, cause);
        } catch (TimeoutException e) {
            throw new McpException(McpErrorCode.TIMEOUT, "context deadline exceeded");
        }
        if (!pending.done.isDone()) {
            // 等待者自己被取消（共享连接不受影响，继续建连）
            callerCtx.throwIfCancelled();
            throw new McpException(McpErrorCode.CONNECTION_CLOSED, "context canceled");
        }
    }

    /** 建连 + initialize，跑在独立虚拟线程里。 */
    private void connectClient(McpCancellation life, String key, McpClientConfig config, PendingConnection pending) {
        McpContext ctx = McpContext.cancellable(life);
        // 取消 ⇒ 中断本线程：阻塞的 HTTP 调用靠中断立即返回，
        // 阻塞式 HttpClient.send 只能这样达到同样效果。
        life.onCancelInterrupt(Thread.currentThread());

        McpClient client = null;
        RuntimeException error = null;
        try {
            client = McpClientFactory.createClient(config);
            // SSE 需要的是连接生命周期，而不是发起方这一轮的 deadline
            client.connect(ctx);
            initializeClient(ctx, config.service(), client);
        } catch (RuntimeException e) {
            error = e;
        }

        lock.writeLock().lock();
        try {
            // CloseClient / CloseAll 可能在这次建连途中把它退役掉
            if (connecting.get(key) != pending || life.isCancelled()) {
                if (error == null) {
                    error = new McpException(McpErrorCode.CONNECTION_CLOSED, "context canceled");
                }
            }
            if (error == null) {
                pending.client = new ManagedMcpClient(client, life, pending.version);
                clients.put(key, pending.client);
            } else {
                pending.error = error;
                life.cancel();
                if (client != null) {
                    try {
                        client.disconnect();
                    } catch (RuntimeException ignored) {
                        // 建连失败路径上的清理失败无需上报
                    }
                }
            }
            if (connecting.get(key) == pending) {
                connecting.remove(key);
            }
            pending.done.complete(null);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 初始化握手 + 超时封顶（默认 30s，最多 60s）。 */
    private void initializeClient(McpContext lifeCtx, McpService service, McpClient client) {
        Duration initTimeout = McpProtocol.DEFAULT_TIMEOUT;
        if (service.getAdvancedConfig() != null && service.getAdvancedConfig().getTimeout() > 0) {
            initTimeout = Duration.ofSeconds(service.getAdvancedConfig().getTimeout());
            if (initTimeout.compareTo(McpProtocol.MAX_INITIALIZE_TIMEOUT) > 0) {
                initTimeout = McpProtocol.MAX_INITIALIZE_TIMEOUT;
            }
        }
        McpContext initCtx = McpContext.of(Instant.now().plus(initTimeout), lifeCtx.cancellation());
        try {
            client.initialize(initCtx);
        } catch (RuntimeException e) {
            client.disconnect();
            throw new McpException(
                    e instanceof McpException me ? me.code() : null,
                    "failed to initialize MCP client: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 查询 / 关闭
    // ------------------------------------------------------------------

    /** 只按"纯 serviceId"键查（OAuth 的按 principal 连接不在此列）。 */
    public McpClient getClient(String serviceId) {
        lock.readLock().lock();
        try {
            return clients.get(serviceId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 关掉该服务的<b>所有</b>缓存连接（OAuth 下是每个 principal 一条），
     * 并退役仍在建连中的尝试（键前缀匹配 {@code "<serviceID>\x00"}）。
     */
    public void closeClient(String serviceId) {
        lock.writeLock().lock();
        try {
            for (Map.Entry<String, PendingConnection> entry : new ArrayList<>(connecting.entrySet())) {
                if (matchesService(entry.getKey(), serviceId)) {
                    entry.getValue().cancellation.cancel();
                    connecting.remove(entry.getKey());
                }
            }
            for (Map.Entry<String, ManagedMcpClient> entry : new ArrayList<>(clients.entrySet())) {
                if (!matchesService(entry.getKey(), serviceId)) {
                    continue;
                }
                try {
                    entry.getValue().disconnect();
                } catch (RuntimeException e) {
                    log.error("Failed to disconnect MCP client {}: {}", entry.getKey(), e.toString());
                }
                clients.remove(entry.getKey());
                log.info("MCP client closed: {}", entry.getKey());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 关闭全部连接与在建尝试。 */
    public void closeAll() {
        lock.writeLock().lock();
        try {
            for (PendingConnection pending : connecting.values()) {
                pending.cancellation.cancel();
            }
            connecting.clear();
            for (Map.Entry<String, ManagedMcpClient> entry : clients.entrySet()) {
                try {
                    entry.getValue().disconnect();
                } catch (RuntimeException e) {
                    log.error("Failed to disconnect MCP client {}: {}", entry.getKey(), e.toString());
                }
            }
            clients.clear();
            log.info("All MCP clients closed");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 先取消 manager 级取消信号（级联所有连接生命周期），再关闭全部连接。 */
    public void shutdown() {
        managerCancellation.cancel();
        closeAll();
        idleCleaner.shutdownNow();
    }

    /** 清掉已断开的缓存条目。 */
    void removeDisconnectedClients() {
        lock.writeLock().lock();
        try {
            List<String> dropped = new ArrayList<>();
            for (Map.Entry<String, ManagedMcpClient> entry : clients.entrySet()) {
                if (!entry.getValue().isConnected()) {
                    dropped.add(entry.getKey());
                }
            }
            for (String key : dropped) {
                clients.remove(key);
                log.info("Removed disconnected MCP client: {}", key);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 当前活跃连接数。 */
    public int getActiveClients() {
        lock.readLock().lock();
        try {
            int count = 0;
            for (ManagedMcpClient client : clients.values()) {
                if (client.isConnected()) {
                    count++;
                }
            }
            return count;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 当前活跃连接的键列表。 */
    public List<String> listActiveServices() {
        lock.readLock().lock();
        try {
            List<String> services = new ArrayList<>();
            for (Map.Entry<String, ManagedMcpClient> entry : clients.entrySet()) {
                if (entry.getValue().isConnected()) {
                    services.add(entry.getKey());
                }
            }
            return services;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 测试可见：当前在建连接数。 */
    int pendingCount() {
        lock.readLock().lock();
        try {
            return connecting.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 缓存键：OAuth 服务按 principal 隔离，其余按 serviceId。 */
    static String cacheKey(McpService service, TenantContext.Principal principal) {
        if (McpClientFactory.isOAuth(service)) {
            return service.getId() + KEY_SEPARATOR + storageId(principal);
        }
        return service.getId();
    }

    private static boolean matchesService(String key, String serviceId) {
        return key.equals(serviceId) || key.startsWith(serviceId + KEY_SEPARATOR);
    }

    /** principal 的存储标识（"type:id"）。 */
    static String storageId(TenantContext.Principal principal) {
        TenantContext.Principal normalized = normalize(principal);
        if (!valid(normalized)) {
            return "";
        }
        return normalized.type() + ":" + normalized.id();
    }

    private static TenantContext.Principal normalize(TenantContext.Principal principal) {
        if (principal == null) {
            return null;
        }
        return new TenantContext.Principal(trim(principal.type()), trim(principal.id()));
    }

    private static boolean valid(TenantContext.Principal principal) {
        TenantContext.Principal normalized = normalize(principal);
        return normalized != null && !normalized.type().isEmpty() && !normalized.id().isEmpty();
    }

    /**
     * embed 会话在有 X-Embed-Visitor 时
     * 下沉到"访客"主体，让同一会话里的不同访客各自持 token。
     */
    private static TenantContext.Principal oauthPrincipal() {
        TenantContext.Principal principal = normalize(TenantContext.currentPrincipal());
        if (principal == null) {
            return null;
        }
        if (!TenantContext.PrincipalTypes.EMBED_SESSION.equals(principal.type())) {
            return principal;
        }
        String visitorId = trim(TenantContext.currentEmbedVisitorId());
        if (visitorId.isEmpty()) {
            return principal;
        }
        return new TenantContext.Principal(TenantContext.PrincipalTypes.EMBED_VISITOR, visitorId);
    }

    /** 服务版本号 = updatedAt 的瞬时值（比瞬时，不比时区）。 */
    private static Instant versionOf(McpService service) {
        OffsetDateTime updatedAt = service.getUpdatedAt();
        return updatedAt == null ? null : updatedAt.toInstant();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
