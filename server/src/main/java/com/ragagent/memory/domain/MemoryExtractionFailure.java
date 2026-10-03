package com.ragagent.memory.domain;

/**
 * 一次抽取失败的描述：标识"哪一段输出不合法"，但**不保留消息原文**。
 *
 * <p>它不出 HTTP 响应，只作 {@code RecordExtractionFailure} 的入参。</p>
 *
 * @param session 出错的会话进度行（只有 {@code sessionId} 与 {@code revision} 被读；
 *                仓库层用它定位那一行，并把 {@code revision} 与当前行比较以决定是否保持 pending）
 * @param end     失败区间结束游标
 * @param code    失败码，落进 {@code failure_code} 列
 */
public record MemoryExtractionFailure(MemoryExtractionSession session, MemoryMessageCursor end, String code) {

    public MemoryExtractionFailure {
        if (end == null) {
            end = new MemoryMessageCursor();
        }
        if (code == null) {
            code = "";
        }
    }
}
