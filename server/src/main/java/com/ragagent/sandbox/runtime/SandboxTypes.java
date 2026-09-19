package com.ragagent.sandbox.runtime;

/**
 * 对照 Go {@code internal/sandbox/sandbox.go} 的 SandboxType 常量与
 * {@code internal/sandbox/tenant_config.go} 的 ParseSandboxType。
 *
 * <p>哨兵错误用异常类表达（对照 {@code errors.Is} 的分类用法，见
 * {@link UnsupportedSandboxTypeException}）。</p>
 */
public final class SandboxTypes {

    /** 每个会话跑在自己的长活 Docker 容器里，走 Docker Engine API。 */
    public static final String TYPE_DOCKER = "docker";
    /** 腾讯 CubeSandbox（E2B 兼容）MicroVM 隔离。 */
    public static final String TYPE_CUBE = "cube";
    /** E2B 托管 MicroVM 沙箱服务。 */
    public static final String TYPE_E2B = "e2b";
    /** disabled：脚本执行停用（也是隐藏策略行的类型）。 */
    public static final String TYPE_DISABLED = "disabled";

    private SandboxTypes() {
    }

    /**
     * 对照 {@code IsNamedSandboxBackendType}：raw 是否能存成用户可见的具名沙箱后端。
     * Cube、E2B、Docker 都会话持久并共享同一工作区配置面。
     */
    public static boolean isNamedSandboxBackendType(String raw) {
        switch (raw == null ? "" : raw) {
            case TYPE_CUBE, TYPE_E2B, TYPE_DOCKER:
                return true;
            default:
                return false;
        }
    }

    /**
     * 对照 {@code ParseSandboxType}：把存储串映射到类型。未知值被拒——拼写错误在管理员
     * 保存配置时暴露，而不是悄悄在首次使用时停用该租户的沙箱。
     *
     * @throws UnsupportedSandboxTypeException 消息形如
     *         {@code sandbox: unsupported sandbox type "xxx"}
     */
    public static String parseSandboxType(String raw) {
        switch (raw == null ? "" : raw) {
            case TYPE_CUBE:
                return TYPE_CUBE;
            case TYPE_E2B:
                return TYPE_E2B;
            case TYPE_DOCKER:
                return TYPE_DOCKER;
            case TYPE_DISABLED:
                return TYPE_DISABLED;
            default:
                throw new UnsupportedSandboxTypeException(
                        "sandbox: unsupported sandbox type \"" + raw + "\"");
        }
    }
}
