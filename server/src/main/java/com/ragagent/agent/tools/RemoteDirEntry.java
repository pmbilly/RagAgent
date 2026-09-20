package com.ragagent.agent.tools;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * sandbox 目录项（对照 Go {@code sandbox.RemoteDirEntry}，internal/sandbox/remote_client.go:362-369）。
 *
 * <p>Go 的 {@code Type} 是字符串枚举 file/dir/other（symlink、socket、device 等对 WeKnora
 * 是不透明的 other，产物代码直接跳过）。工具包只依赖这个中性形状；真 sandbox Manager
 * （波 4.6 装配）负责把自己的目录项投影到这里。</p>
 */
public record RemoteDirEntry(String name, String path, String type, long size, Instant modTime) {

    /** 对照 sandbox.RemoteEntryFile。 */
    public static final String TYPE_FILE = "file";
    /** 对照 sandbox.RemoteEntryDir。 */
    public static final String TYPE_DIR = "dir";
    /** 对照 sandbox.RemoteEntryOther：symlink/socket/device 等，一律按不透明跳过。 */
    public static final String TYPE_OTHER = "other";

    public RemoteDirEntry {
        name = name == null ? "" : name;
        path = path == null ? "" : path;
        type = type == null ? "" : type;
        modTime = modTime == null ? Instant.EPOCH : modTime;
    }

    /** Go time.Time 的零值语义：stat/列表后端没给时间时用 epoch 占位（formatSandboxModTime 认它）。 */
    public static final Instant ZERO_TIME = Instant.EPOCH;

    /** 方便从 OffsetDateTime（DB/Jackson 侧常用）构造。 */
    public static RemoteDirEntry of(String name, String path, String type, long size, OffsetDateTime modTime) {
        return new RemoteDirEntry(name, path, type, size,
                modTime == null ? ZERO_TIME : modTime.toInstant());
    }

    /** 是否 file 类型。 */
    public boolean isFile() {
        return TYPE_FILE.equals(type);
    }

    /** 是否 dir 类型。 */
    public boolean isDir() {
        return TYPE_DIR.equals(type);
    }

    /** Go time.Time 零值判断（epoch 视为零值——Go 侧后端不会返回 year-1 的真实时间）。 */
    public static boolean isZeroTime(Instant t) {
        return t == null || t.equals(ZERO_TIME) || t.toEpochMilli() == 0;
    }

    /** Instant → OffsetDateTime（UTC）便捷。 */
    public static OffsetDateTime toOffset(Instant t) {
        return t == null ? null : t.atOffset(ZoneOffset.UTC);
    }
}
