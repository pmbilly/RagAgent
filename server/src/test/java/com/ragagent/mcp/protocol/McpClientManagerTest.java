package com.ragagent.mcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpTransportType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/mcp/manager_startup_test.go：
 * {@code TestManagerConcurrentStartupCancellationAndConfigReplacement}
 * + {@code TestManagerCloseRetiresPendingConnection}。
 */
class McpClientManagerTest {

    private com.ragagent.common.security.SsrfGuard.Whitelist ssrfSnapshot;

    @BeforeEach
    void allowLoopback() {
        // 对照 Go 的 utils.SetSSRFWhitelistFromRaw("127.0.0.1")。
        // SsrfGuard 的白名单是进程级 static（known-issues W5a「互踩」家族），
        // 必须快照/还原，否则 127.0.0.1 泄漏给同 JVM 的后续契约测试。
        ssrfSnapshot = SsrfGuard.snapshotWhitelist();
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("127.0.0.1");
        McpServiceUrls.setSsrfGuard(guard);
    }

    @AfterEach
    void resetGuard() {
        McpServiceUrls.setSsrfGuard(new SsrfGuard());
        SsrfGuard.restoreWhitelist(ssrfSnapshot);
    }

    private static McpService service(String id, String url) {
        McpService service = new McpService();
        service.setId(id);
        service.setName(id);
        service.setEnabled(true);
        service.setUrl(url);
        service.setTransportType(McpTransportType.HTTP_STREAMABLE.value());
        return service;
    }

    private static McpContext deadlineSoon() {
        return McpContext.deadline(Instant.now().plusSeconds(5));
    }

    /** 对照 Go TestManagerConcurrentStartupCancellationAndConfigReplacement。 */
    @Test
    @DisplayName("并发建连：调用方取消不杀共享连接；配置替换触发重建")
    void concurrentStartupCancellationAndConfigReplacement() throws Exception {
        try (McpServerStub server = new McpServerStub()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            server.initializeGates.put("/slow", () -> {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            McpClientManager manager = new McpClientManager(null);
            McpService slow = service("slow", server.url("/slow"));
            McpService fast = service("fast", server.url("/fast"));
            try {
                McpCancellation callerCancellation = new McpCancellation();
                CompletableFuture<RuntimeException> done = new CompletableFuture<>();
                Thread.ofVirtual().start(() -> {
                    try {
                        manager.getOrCreateClient(McpContext.cancellable(callerCancellation), slow);
                        done.complete(null);
                    } catch (RuntimeException e) {
                        done.complete(e);
                    }
                });

                assertTrue(started.await(5, TimeUnit.SECONDS), "慢服务必须已经进入建连");
                // 慢服务不得占着 manager 全局锁
                McpClient fastClient = manager.getOrCreateClient(deadlineSoon(), fast);
                assertEquals("full server instructions", fastClient.serverInstructions());

                callerCancellation.cancel();
                RuntimeException err = done.get(5, TimeUnit.SECONDS);
                assertNotNull(err, "取消的等待者必须拿到错误");
                assertEquals("context canceled", err.getMessage());

                release.countDown();
                McpClient slowClient = manager.getOrCreateClient(deadlineSoon(), slow);
                assertNotNull(slowClient);
                assertEquals(1, server.initializeCount("/slow"),
                        "取消一个等待者不能杀掉共享连接（只应 initialize 一次）");

                McpService snapshot = service("fast", server.url("/fast"));
                snapshot.setUpdatedAt(OffsetDateTime.now());
                McpClient replacement = manager.getOrCreateClient(deadlineSoon(), snapshot);
                assertNotSame(fastClient, replacement, "updatedAt 变化必须触发重建");
                assertEquals(2, server.initializeCount("/fast"));
            } finally {
                manager.shutdown();
            }
        }
    }

    /** 对照 Go TestManagerCloseRetiresPendingConnection。 */
    @Test
    @DisplayName("CloseClient 退役建连中的 pending")
    void closeRetiresPendingConnection() throws Exception {
        try (McpServerStub server = new McpServerStub()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            server.initializeGates.put("/pending", () -> {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            McpClientManager manager = new McpClientManager(null);
            McpService service = service("pending", server.url("/pending"));
            try {
                CompletableFuture<RuntimeException> done = new CompletableFuture<>();
                Thread.ofVirtual().start(() -> {
                    try {
                        manager.getOrCreateClient(McpContext.none(), service);
                        done.complete(null);
                    } catch (RuntimeException e) {
                        done.complete(e);
                    }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS), "建连必须已经打到服务端");

                manager.closeClient(service.getId());

                RuntimeException err = done.get(5, TimeUnit.SECONDS);
                assertNotNull(err, "被退役的建连必须把错误交给等待者");
                assertEquals(0, manager.getActiveClients());
                assertEquals(0, manager.pendingCount(), "pending 必须被清掉");
            } finally {
                release.countDown();
                manager.shutdown();
            }
        }
    }

    /** 对照 Go manager 的 Shutdown：取消 manager 级 ctx 并关闭所有连接。 */
    @Test
    @DisplayName("Shutdown 关闭全部连接并让后续取连接直接失败")
    void shutdownClosesEverything() throws Exception {
        try (McpServerStub server = new McpServerStub()) {
            McpClientManager manager = new McpClientManager(null);
            McpService svc = service("one", server.url("/m"));
            McpClient client = manager.getOrCreateClient(deadlineSoon(), svc);
            assertEquals(1, manager.getActiveClients());
            assertEquals(1, manager.listActiveServices().size());
            assertNotNull(manager.getClient("one"));

            manager.shutdown();
            assertEquals(0, manager.getActiveClients());
            assertTrue(!client.isConnected());

            McpException err = org.junit.jupiter.api.Assertions.assertThrows(McpException.class,
                    () -> manager.getOrCreateClient(deadlineSoon(), svc));
            assertEquals("context canceled", err.getMessage());
        }
    }

    /** 对照 Go manager：stdio 与未启用的服务在取连接时就被拒。 */
    @Test
    @DisplayName("stdio 硬拒绝；未启用服务被拒")
    void rejectsStdioAndDisabled() {
        McpClientManager manager = new McpClientManager(null);
        try {
            McpService stdio = service("stdio", "http://127.0.0.1:1/x");
            stdio.setTransportType(McpTransportType.STDIO.value());
            McpException err = org.junit.jupiter.api.Assertions.assertThrows(McpException.class,
                    () -> manager.getOrCreateClient(deadlineSoon(), stdio));
            assertTrue(err.getMessage().contains("stdio transport is disabled"), err.getMessage());

            McpService disabled = service("off", "http://127.0.0.1:1/x");
            disabled.setEnabled(false);
            McpException err2 = org.junit.jupiter.api.Assertions.assertThrows(McpException.class,
                    () -> manager.getOrCreateClient(deadlineSoon(), disabled));
            assertTrue(err2.getMessage().contains("is not enabled"), err2.getMessage());
        } finally {
            manager.shutdown();
        }
    }
}
