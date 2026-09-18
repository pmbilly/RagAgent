package com.ragagent.stream;

/**
 * 会话已有另一个 assistant 在生成（对照 Go 的 {@code stream.ErrLiveRunExists}）。
 *
 * <p>{@code SetLiveRun} 是**排他**的：它的存在是为了让 executeQA 无法在同一会话上
 * 叠起第二个引擎。需要接手的后续轮次走 {@code ClaimLiveRun}（覆盖语义）。</p>
 *
 * <p>调用方按类型精确识别（对照 Go 的 {@code stderrors.Is(err, stream.ErrLiveRunExists)}，
 * 见 internal/handler/session/qa.go:1148）。刻意**不**继承
 * {@link StreamStoreException}——后者表示存储故障，两者要分开捕获。</p>
 */
public class LiveRunExistsException extends RuntimeException {

    public LiveRunExistsException() {
        super("session already has a live run");
    }
}
