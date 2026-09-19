package com.ragagent.sandbox.runtime;

/**
 * provider 中立的失败分类（对照 Go {@code RemoteErrorKind}，
 * internal/sandbox/remote_errors.go L29-68）。字符串值与 Go 常量逐字一致。
 *
 * <p>分类的用途是让上层（会话绑定生命周期、sandbox-check 文案）在不知道后端
 * 是谁的情况下做决策。取值规则见 remote_errors.go 文件头注释：
 * NotFound/Terminal → 绑定可替换；Timeout/Unavailable/Capacity/Conflict/
 * Authentication → 保留绑定；InvalidRequest/Unsupported → 直通调用方。</p>
 */
public enum RemoteErrorKind {

    NOT_FOUND("not_found"),
    TERMINAL("terminal"),
    AUTHENTICATION("authentication"),
    INVALID_REQUEST("invalid_request"),
    UNSUPPORTED("unsupported"),
    CONFLICT("conflict"),
    CAPACITY("capacity"),
    TIMEOUT("timeout"),
    UNAVAILABLE("unavailable"),
    INTERNAL("internal");

    private final String wire;

    RemoteErrorKind(String wire) {
        this.wire = wire;
    }

    /** 与 Go 常量一致的字符串值（RemoteError.Error() 拼接用）。 */
    public String wire() {
        return wire;
    }
}
