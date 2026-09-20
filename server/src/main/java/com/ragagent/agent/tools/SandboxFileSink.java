package com.ragagent.agent.tools;

/**
 * write_sandbox_file 需要的文件存储切片（对照 Go {@code SandboxFileSink}，
 * internal/agent/tools/sandbox_write.go:91-95）。
 *
 * <p>不是纯"只写"：append 模式必须先看到已有文件。远端没有原子 append，
 * 因此读整份再写回——edit_sandbox_file 也是这么做的。</p>
 */
public interface SandboxFileSink {

    RemoteStatEntry statSessionFile(String sessionId, String filePath) throws Exception;

    byte[] readSessionFile(String sessionId, String filePath) throws Exception;

    /** 写会话 workspace 文件（对照 WriteSessionWorkspaceFile；路径必须在 /workspace 下）。 */
    void writeSessionWorkspaceFile(String sessionId, String filePath, byte[] content) throws Exception;
}
