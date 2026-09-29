package com.ragagent.knowledge.domain;

/**
 * （internal/application/repository/chunk：{@code errors.New("chunk revision conflict")}）。
 * <p>{@code SaveChunkRevision} 的乐观锁 UPDATE 影响行数 != 1 时抛出（事务回滚）。
 * 覆盖输出（409 语义，见后续模块的 契约样例）。</p>
 */
public class ChunkRevisionConflictException extends RuntimeException {

    public ChunkRevisionConflictException() {
        super("chunk revision conflict");
    }
}
