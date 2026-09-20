package com.ragagent.agent.tools;

import java.util.List;

/**
 * 会话感知 sandbox manager 的窄工具面（对照 Go {@code SandboxFileSource}，
 * internal/agent/tools/sandbox_ls.go:48-52）。
 *
 * <p>生产实现是 Go 的 {@code *sandbox.SessionBoundManager}（Java 侧波 4.6 装配）；
 * 测试用内存 fake。接口收敛在本包，避免工具层向上依赖 application/service。</p>
 */
public interface SandboxFileSource {

    /** 列出会话 sandbox 内 dir 下的文件（对照 ListSessionFiles）。 */
    List<RemoteDirEntry> listSessionFiles(String sessionId, String dir) throws Exception;

    /** stat 会话 sandbox 内的单个路径（对照 StatSessionFile；不存在时返回 null）。 */
    RemoteStatEntry statSessionFile(String sessionId, String path) throws Exception;

    /** 读会话 sandbox 内的文件字节（对照 ReadSessionFile）。 */
    byte[] readSessionFile(String sessionId, String path) throws Exception;
}
