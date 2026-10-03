package com.ragagent.retrieval.graph;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 图仓储装配。
 *
 * <p>门与默认：{@code NEO4J_ENABLE}（忽略大小写）不等于 {@code true} → 不建驱动，
 * 仓储退化为 no-op（三个操作都告警 {@code NOT SUPPORT RETRIEVE GRAPH} 后静默），
 * 调用方无需判空。</p>
 *
 * <p>启用时的连接纪律：{@code NEO4J_URI}/{@code NEO4J_USERNAME}/
 * {@code NEO4J_PASSWORD} + <b>30 次 × 2 秒重试</b>（每次重建驱动并
 * 验证连通性）。全部失败 → 抛出（装配失败即让启动失败，
 * 而不是带着半个图库继续跑）。</p>
 */
@Configuration
public class Neo4jGraphConfig {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphConfig.class);

    /** 连接重试参数：30 次、间隔 2s。 */
    static final int MAX_RETRIES = 30;
    static final long RETRY_INTERVAL_MS = 2000L;

    @Bean
    public RetrieveGraphRepository retrieveGraphRepository(Neo4jProperties properties) {
        return new Neo4jGraphRepository(createDriverIfEnabled(properties));
    }

    /**
     * 建驱动：未启用 → null；启用 → 重试建连；
     * 30 次仍失败 → 抛出（文案 {@code failed to connect to Neo4j after %d attempts}）。
     */
    static Driver createDriverIfEnabled(Neo4jProperties properties) {
        String enable = properties == null ? null : properties.enable();
        if (!"true".equals(enable == null ? "" : enable.toLowerCase())) {
            log.debug("NOT SUPPORT RETRIEVE GRAPH");
            return null;
        }
        String uri = properties.uri();
        String username = properties.username();
        String password = properties.password();

        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Driver driver = null;
            try {
                driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
                // 握手即验凭据
                driver.verifyConnectivity();
                if (attempt > 1) {
                    log.info("Successfully connected to Neo4j after {} attempts", attempt);
                }
                return driver;
            } catch (RuntimeException e) {
                last = e;
                log.warn("Failed to verify Neo4j authentication (attempt {}/{}): {}",
                        attempt, MAX_RETRIES, e.toString());
                if (driver != null) {
                    try {
                        driver.close();
                    } catch (RuntimeException ignored) {
                        // 关闭失败不影响重试
                    }
                }
                sleepQuietly();
            }
        }
        throw new IllegalStateException(
                "failed to connect to Neo4j after " + MAX_RETRIES + " attempts: " + last);
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(RETRY_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
