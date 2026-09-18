package com.ragagent.knowledge.mapper;

/**
 * 对照 Go {@code repository.ErrChunkRevisionConflict}
 * （internal/application/repository/chunk.go L17：{@code errors.New("chunk revision conflict")}）。
 *
 * <p>{@code SaveChunkRevision} 的乐观锁 UPDATE 影响行数 != 1 时抛出（事务回滚）。
 * 消息文案<b>不是契约</b>，controller 层（后续任务）会按 Go handler 的实际形态
 * 覆盖输出（409 语义，见后续模块的 golden）。</p>
 */
public class ChunkRevisionConflictException extends RuntimeException {

    public ChunkRevisionConflictException() {
        super("chunk revision conflict");
    }
}
