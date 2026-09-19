package com.ragagent.sandbox.service;

/**
 * 对照 Go {@code internal/application/service/tenant_sandbox_config.go} 的哨兵错误与
 * 携带数据的错误类型（L139-206）+ 仓储层的 {@code repository.ErrSandboxConfigCordoned}。
 *
 * <p>controller 的 respondSandboxConfigRefusal / respondSandboxConfigServiceError 以
 * {@code instanceof} 分类（对照 Go 的 errors.As / errors.Is）。消息会进 HTTP 响应或
 * 500 details 的按 Go 原文逐字节对应；纯分类哨兵（转 409 固定文案的）消息只用于日志。</p>
 */
public final class SandboxConfigServiceErrors {

    private SandboxConfigServiceErrors() {
    }

    /**
     * 对照 {@code ErrSandboxesStillLive} + {@code SandboxesStillLiveError}：
     * 身份变更或删除被拒，因为当前凭据仍拥有 provider 资源。
     * <p>Go 的 Error() 文本：{@code "sandbox config still owns live sandboxes: <count>"}。</p>
     */
    public static class SandboxesStillLiveException extends RuntimeException {
        private final SandboxInventory inventory;

        public SandboxesStillLiveException(SandboxInventory inventory) {
            super("sandbox config still owns live sandboxes: " + inventory.sandboxCount());
            this.inventory = inventory;
        }

        /** handler 必须展示的 provider 清单（重算可能竞态出另一个拒绝理由）。 */
        public SandboxInventory inventory() {
            return inventory;
        }
    }

    /**
     * 对照 {@code ErrSandboxInventoryUnverifiable}：provider 无法联系以回答
     * "这份配置还拥有沙箱吗？"。刻意区别于 StillLive——前者是"有沙箱，去处理"，
     * 这是"无法判断"；后者才是 force delete 唯一可覆盖的情形。
     * HTTP 层转 409 固定文案。
     */
    public static class SandboxInventoryUnverifiableException extends RuntimeException {
        public SandboxInventoryUnverifiableException(String message) {
            super(message);
        }
    }

    /** 对照 {@code ErrSandboxConfigNameRequired}：纯分类哨兵 → 400（transport 按类型分类）。 */
    public static class SandboxConfigNameRequiredException extends RuntimeException {
        public SandboxConfigNameRequiredException() {
            super("sandbox config name is required");
        }
    }

    /** 对照 {@code ErrNamedSandboxBackendUnsupported}：不能存为用户可见具名后端的类型。 */
    public static class NamedSandboxBackendUnsupportedException extends RuntimeException {
        public NamedSandboxBackendUnsupportedException() {
            super("named sandbox configs only support cube, e2b and docker backends");
        }
    }

    /**
     * 对照 {@code ErrSkillSnapshotBlocksTemplateChange}：本配置已有 skill 快照而调用方
     * 试图更换连接/DNS/重建模板。会话启的是快照，这些编辑到不了它们；出路是第二份配置。
     */
    public static class SkillSnapshotBlocksTemplateChangeException extends RuntimeException {
        public SkillSnapshotBlocksTemplateChangeException() {
            super("sandbox connection cannot change while this config has a skill snapshot");
        }
    }

    /**
     * 对照 {@code ErrSkillSnapshotReleaseFailed} + {@code SkillSnapshotReleaseFailedError}：
     * 删除配置无法销毁 ledger 上的每个 provider 快照。留下行可恢复；泄漏一个计费快照不行。
     * <p>remaining = 仍留在 provider 上的快照名（handler 展示、重试可跳过已删的）；
     * 带细节的形态对照 Go 的 {@code fmt.Errorf("%w: list snapshots: %v", ...)}
     * （Error() = {@code "failed to release skill snapshots: <细节>"}）。</p>
     */
    public static class SkillSnapshotReleaseFailedException extends RuntimeException {
        private final java.util.List<String> remaining;

        public SkillSnapshotReleaseFailedException(java.util.List<String> remaining) {
            super(remaining == null || remaining.isEmpty()
                    ? "failed to release skill snapshots"
                    : "failed to release skill snapshots: " + String.join(", ", remaining));
            this.remaining = remaining;
        }

        /** 对照 errors.Wrap 形态（如 "failed to release skill snapshots: list snapshots: …"）。 */
        public SkillSnapshotReleaseFailedException(java.util.List<String> remaining, String detail) {
            super("failed to release skill snapshots: " + detail);
            this.remaining = remaining;
        }

        public java.util.List<String> remaining() {
            return remaining;
        }
    }

    /**
     * 对照 {@code repository.ErrSandboxConfigCordoned}：另一请求已持有同一配置行的
     * 新鲜 cordon 租约。HTTP 层转 423 固定文案。
     */
    public static class SandboxConfigCordonedException extends RuntimeException {
        public SandboxConfigCordonedException() {
            super("sandbox config is being modified by another request");
        }
    }
}
