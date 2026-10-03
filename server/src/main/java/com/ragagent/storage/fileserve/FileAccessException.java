package com.ragagent.storage.fileserve;

/**
 * 文件授权失败的三态（NOT_FOUND / UNAUTHORIZED / FORBIDDEN），
 * 由文件代理服务映射成对应的 HTTP 响应。
 */
public class FileAccessException extends RuntimeException {

    public enum Kind {
        NOT_FOUND,
        UNAUTHORIZED,
        FORBIDDEN
    }

    private final Kind kind;

    public FileAccessException(Kind kind) {
        super(kind.name());
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static FileAccessException notFound() {
        return new FileAccessException(Kind.NOT_FOUND);
    }

    public static FileAccessException unauthorized() {
        return new FileAccessException(Kind.UNAUTHORIZED);
    }

    public static FileAccessException forbidden() {
        return new FileAccessException(Kind.FORBIDDEN);
    }
}
