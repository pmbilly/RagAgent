package com.ragagent.llm.ollama;

import java.time.OffsetDateTime;

/**
 * Ollama 模型概要（对照 Go ollama.OllamaModelInfo，
 * internal/models/utils/ollama/ollama.go:262-268）。
 *
 * <p>JSON 键序 = Go struct 声明序（name, size, digest, modified_at）。</p>
 */
public record OllamaModelInfo(String name, long size, String digest, OffsetDateTime modifiedAt) {
}
