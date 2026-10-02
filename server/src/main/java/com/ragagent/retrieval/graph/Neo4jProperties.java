package com.ragagent.retrieval.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Neo4j 图检索的部署配置（B6 批 9）。
 *
 * <p>env 名保持原样：{@code NEO4J_ENABLE} / {@code NEO4J_URI} / {@code NEO4J_USERNAME} /
 * {@code NEO4J_PASSWORD} → {@code neo4j.*}（Spring 松散绑定）。</p>
 *
 * <p>只承载原始串：{@code enable} 的大小写不敏感比较、「不等于 true 即视为未启用」的
 * 判定留在 {@link Neo4jGraphConfig}（门语义与改前逐字一致）。</p>
 */
@ConfigurationProperties(prefix = "neo4j")
public record Neo4jProperties(String enable, String uri, String username, String password) {
}
