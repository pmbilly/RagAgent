package com.ragagent.sandbox.runtime;

import java.util.List;
import java.util.Map;

/**
 * 对照 Go {@code sandbox.ConfigSandboxClient} / {@code ConfigSandboxLister}
 * （internal/sandbox/config_sandboxes.go L23-32）+ inventory 载荷类型
 * （remote_client.go 的 RemoteSandboxSummary / RemoteListFilter / RemoteSandboxState）。
 *
 * <p><b>显式接缝（本批契约面）</b>：身份变更与配置删除都要向 provider 权威地回答
 * "这份配置还拥有活沙箱吗？"。绑定存储回答不了——绑定恰好在泄漏发生时丢失
 * （CAS 重绑覆盖旧 ID、Redis 驱逐、进程重启）。provider 自己的列表才是真相来源。</p>
 *
 * <p>子批 1 只定义接口 + 一个恒抛"客户端未接线"的占位 factory
 * （见 {@code service.UnwiredSandboxClientFactory}）；子批 2 接真实
 * cube/e2b/docker 客户端（exec/PTY/文件面）。</p>
 */
public interface ConfigSandboxClient {

    /** 对照 ConfigSandboxLister.List：按 filter 列出沙箱中立摘要。 */
    List<RemoteSandboxSummary> list(RemoteListFilter filter);

    /** 对照 ConfigSandboxClient.Delete：删除一个 provider 沙箱（更新后清扫用）。 */
    void delete(String sandboxId);

    /** 对照 {@code RemoteSandboxSummary}：列表/探测的沙箱中立视图。 */
    record RemoteSandboxSummary(
            String id,
            String templateId,
            /** 对照 RemoteSandboxState：running/paused/transitioning/terminal/unknown */
            String state,
            String rawState,
            /** 沙箱 metadata 袋；provider 不支持 metadata 时可为 null */
            Map<String, String> metadata,
            java.time.OffsetDateTime startedAt,
            java.time.OffsetDateTime endAt) {
    }

    /** 对照 {@code RemoteListFilter}：List 调用的收窄条件；空字段 = 不过滤。 */
    record RemoteListFilter(
            /** 只返回 metadata 包含全部键值对的沙箱 */
            Map<String, String> metadata,
            /** 限定归一化状态；空 = 任意状态 */
            List<String> states) {
    }

    final class ConfigSandboxes {

        public static final String METADATA_TENANT_ID = "weknora_tenant_id";
        public static final String METADATA_SESSION_ID = "weknora_session_id";
        public static final String METADATA_CONFIG_ID = "weknora_sandbox_config_id";

        public static final String STATE_RUNNING = "running";
        public static final String STATE_PAUSED = "paused";

        private ConfigSandboxes() {
        }

        /**
         * 对照 NormalizeConfigID：空 config ID 映射到 metadata 用的哨兵，
         * 使"部署默认配置"像其他配置一样可寻址。
         */
        public static String normalizeConfigId(String configId) {
            if (configId == null || configId.trim().isEmpty()) {
                return com.ragagent.sandbox.domain.SandboxConstants.SANDBOX_CONFIG_ID_GLOBAL_DEFAULT;
            }
            return configId;
        }

        /** 对照 MetadataSessionIDKey。 */
        public static String metadataSessionIdKey() {
            return METADATA_SESSION_ID;
        }

        /**
         * 对照 configSandboxFilter：把列表收窄到一个工作区的一个配置。
         * <p>暂停的沙箱不是 idle 遗留物：它们计费，且会话期待 resume 它们。
         * 漏掉它们就会在可能删除它们的凭据被替换前一刻报告"这里什么都没有"。</p>
         */
        public static RemoteListFilter configSandboxFilter(long tenantId, String configId) {
            Map<String, String> metadata = new java.util.LinkedHashMap<>();
            metadata.put(METADATA_TENANT_ID, Long.toUnsignedString(tenantId));
            metadata.put(METADATA_CONFIG_ID, normalizeConfigId(configId));
            return new RemoteListFilter(metadata, List.of(STATE_RUNNING, STATE_PAUSED));
        }

        /**
         * 对照 ListConfigSandboxes：返回该配置当前拥有的沙箱。失败时抛
         * {@code IllegalStateException}，消息对照 Go 的 fmt.Errorf 包装：
         * {@code sandbox: list workspace <n> config "<id>" sandboxes: <原因>}。
         */
        public static List<RemoteSandboxSummary> listConfigSandboxes(
                ConfigSandboxClient client, long tenantId, String configId) {
            if (client == null) {
                throw new IllegalStateException("sandbox: listing requires a client");
            }
            try {
                return client.list(configSandboxFilter(tenantId, configId));
            } catch (RuntimeException e) {
                throw new IllegalStateException("sandbox: list workspace " + tenantId + " config \""
                        + normalizeConfigId(configId) + "\" sandboxes: " + e.getMessage(), e);
            }
        }
    }
}
