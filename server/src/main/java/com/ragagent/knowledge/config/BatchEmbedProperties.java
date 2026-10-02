package com.ragagent.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 批量向量化的批大小配置（{@code BATCH_EMBED_SIZE}，B6 批 5）。
 *
 * <p>env 名保持原样：{@code BATCH_EMBED_SIZE} → {@code batch.embed-size}（Spring 松散绑定）。</p>
 *
 * <p>只承载**原始串**（含 {@code null} 语义）：各读点的缺省与非法值语义不同——
 * 知识处理/分块索引写侧「空 → 5，非法 → 抛异常（文案落 knowledge.error_message）」，
 * 两个错误文案各自保留，故解析留在各自读点。</p>
 */
@ConfigurationProperties(prefix = "batch")
public record BatchEmbedProperties(String embedSize) {
}
