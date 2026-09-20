package com.ragagent.agent.tools;

/**
 * edit_sandbox_file 的读-改-写面（对照 Go {@code SandboxFileEditor}，
 * internal/agent/tools/sandbox_edit.go:42-46）。生产用 *sandbox.SessionBoundManager。
 */
public interface SandboxFileEditor {

    RemoteStatEntry statSessionFile(String sessionId, String filePath) throws Exception;

    byte[] readSessionFile(String sessionId, String filePath) throws Exception;

    void writeSessionWorkspaceFile(String sessionId, String filePath, byte[] content) throws Exception;
}
