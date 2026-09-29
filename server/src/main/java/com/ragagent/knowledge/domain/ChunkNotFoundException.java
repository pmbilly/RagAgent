package com.ragagent.knowledge.domain;

/**
 * （internal/application/repository/chunk）。
 * {@code session.domain.MessageNotFoundException} 的先例）。</p>
 * <p>消息文案<b>不是契约</b>：handler 层（后续任务）会用常量覆盖输出的错误文本，
 * 本 message 只用于日志。</p>
 */
public class ChunkNotFoundException extends RuntimeException {

    public ChunkNotFoundException() {
        super("chunk not found");
    }
}
