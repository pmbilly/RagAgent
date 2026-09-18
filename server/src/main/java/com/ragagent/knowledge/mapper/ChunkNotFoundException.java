package com.ragagent.knowledge.mapper;

/**
 * 对照 Go {@code repository.ErrChunkNotFound}
 * （internal/application/repository/chunk.go L24）。
 *
 * <p>Go 的哨兵错误 + {@code errors.Is} 判定 → Java 的专用运行时异常（同
 * {@code session.domain.MessageNotFoundException} 的先例）。</p>
 *
 * <p>消息文案<b>不是契约</b>：handler 层（后续任务）会用常量覆盖输出的错误文本，
 * 本 message 只用于日志。</p>
 */
public class ChunkNotFoundException extends RuntimeException {

    public ChunkNotFoundException() {
        super("chunk not found");
    }
}
