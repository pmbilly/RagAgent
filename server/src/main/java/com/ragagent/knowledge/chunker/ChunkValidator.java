package com.ragagent.knowledge.chunker;

import java.util.List;

/**
 * 层级输出校验（对照 Go internal/infrastructure/chunker/validator.go）。
 *
 * <p>校验器刻意宽容：只有"明显坏掉"的输出被拒绝，看似合理的差异都被接受，
 * 避免层级间来回震荡。</p>
 */
public final class ChunkValidator {

    /** 对照 Go ValidationResult（validator.go:11）。 */
    public record ValidationResult(boolean ok, String reason) {
    }

    private ChunkValidator() {
    }

    /** 对照 Go ValidateChunks（validator.go:19）。 */
    public static ValidationResult validate(List<ParsedChunk> chunks, int totalChars, int chunkSize) {
        if (chunks == null || chunks.isEmpty()) {
            return new ValidationResult(false, "no chunks produced");
        }

        // 文档远大于 chunkSize 却只切出一块 = 该策略没有真正切分 → 失败换下一层
        if (chunks.size() == 1 && totalChars > 2 * chunkSize) {
            return new ValidationResult(false, "single chunk for large document");
        }

        int maxLen = 0;
        for (ParsedChunk c : chunks) {
            int l = Runes.len(c.getContent());
            if (l > maxLen) {
                maxLen = l;
            }
        }

        // 除最后一块外都应携带有意义的内容；最后一块允许很小（尾部残留正常）
        int tinyCount = 0;
        for (int i = 0; i < chunks.size() - 1; i++) {
            if (Runes.len(chunks.get(i).getContent()) < 50) {
                tinyCount++;
            }
        }
        if (tinyCount > chunks.size() / 4 && tinyCount > 2) {
            return new ValidationResult(false, "too many tiny chunks");
        }

        // 没有任何 chunk 达到目标 25% → 切得过碎
        if (maxLen < chunkSize / 4 && totalChars > chunkSize) {
            return new ValidationResult(false, "all chunks far below target size");
        }

        // 绝对上限：超过 2x chunkSize 是危险信号
        if (maxLen > 2 * chunkSize && chunkSize > 0) {
            return new ValidationResult(false, "chunk exceeds 2x target size");
        }

        return new ValidationResult(true, "");
    }
}
