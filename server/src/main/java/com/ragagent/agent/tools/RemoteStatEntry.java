package com.ragagent.agent.tools;

import java.time.Instant;

/**
 * sandbox 文件 stat 结果（对照 Go {@code sandbox.RemoteStatEntry}，
 * internal/sandbox/remote_client.go:382-387）。path/type/size/modTime 四字段中性投影。
 */
public record RemoteStatEntry(String path, String type, long size, Instant modTime) {

    public RemoteStatEntry {
        path = path == null ? "" : path;
        type = type == null ? "" : type;
        modTime = modTime == null ? RemoteDirEntry.ZERO_TIME : modTime;
    }

    public static RemoteStatEntry of(String path, String type, long size, Instant modTime) {
        return new RemoteStatEntry(path, type, size, modTime);
    }

    public boolean isFile() {
        return RemoteDirEntry.TYPE_FILE.equals(type);
    }

    public boolean isDir() {
        return RemoteDirEntry.TYPE_DIR.equals(type);
    }
}
