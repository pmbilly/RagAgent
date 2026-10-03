package com.ragagent.llm.ollama;

import java.time.OffsetDateTime;

/**
 * Ollama 模型概要。
 *
 * <p>JSON 键序 = 声明序（name, size, digest, modified_at）。</p>
 */
public record OllamaModelInfo(String name, long size, String digest, OffsetDateTime modifiedAt) {
}
