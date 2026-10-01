package com.ragagent.system.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.ragagent.auth.domain.UserPreferences;

/**
 * 系统管理端的响应 DTO 集（JSON 字段名即 Java 字段名，camelCase；可空字段显式 null）。
 */
public final class SystemDtos {

    private SystemDtos() {
    }

    /** 用户信息投影（promote/revoke/list 的响应行）。 */
    public record UserInfoResponse(
            String id,
            String username,
            String email,
            String avatar,
            long tenantId,
            boolean isActive,
            boolean canAccessAllTenants,
            boolean isSystemAdmin,
            UserPreferences preferences,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        /** 字段直拷（avatar 等已由实体 getter 归一）。 */
        public static UserInfoResponse from(com.ragagent.auth.domain.User u) {
            return new UserInfoResponse(
                    u.getId(), u.getUsername(), u.getEmail(), u.getAvatar(),
                    u.getTenantId(), u.isIsActive(), u.isCanAccessAllTenants(),
                    u.isIsSystemAdmin(), u.getPreferences(), u.getCreatedAt(), u.getUpdatedAt());
        }
    }

    /** 系统管理员列表（空页 → []）。 */
    public record SystemAdminListResponse(
            long total,
            List<UserInfoResponse> admins) {
    }

    /** 创建系统用户：generatedPassword 仅在生成时出现（否则显式 null）。 */
    public record CreateUserResponse(
            UserInfoResponse user,
            String generatedPassword) {
    }

    /** 部署能力项（reason 未命中时显式 null）。 */
    public record DeploymentCapability(
            boolean supported,
            String reason) {

        public static DeploymentCapability yes() {
            return new DeploymentCapability(true, null);
        }

        public static DeploymentCapability notRegistered() {
            return new DeploymentCapability(false, "route_not_registered");
        }
    }

    /** 部署能力清单（capabilities 是 map → 键按字母序输出）。 */
    public record DeploymentCapabilitiesData(
            String edition,
            Map<String, DeploymentCapability> capabilities) {
    }

    /**
     * 系统信息（version/edition 恒输出，其余未采集时显式 null）。
     *
     * <p>与上游 Go 的差异：{@code goVersion} 改为 {@code javaVersion}（输出 JVM 运行时版本）。</p>
     */
    public record SystemInfoResponse(
            String version,
            String edition,
            String commitId,
            String buildTime,
            String javaVersion,
            String keywordIndexEngine,
            String vectorStoreEngine,
            String graphDatabaseEngine,
            boolean minioEnabled,
            String dbVersion,
            String dbMigrationError,
            String startedAt,
            long uptimeSeconds) {
    }

    /** 解析引擎信息（无可用原因时显式 null）。 */
    public record ParserEngineInfo(
            String name,
            String description,
            List<String> fileTypes,
            boolean available,
            String unavailableReason) {
    }

    /** parser-engines 响应（连接态 + docreader 信息 + 引擎清单）。 */
    public record ParserEnginesResponse(
            boolean connected,
            String docreaderAddr,
            String docreaderTransport,
            List<ParserEngineInfo> engines) {
    }

    /** 存储引擎状态项。 */
    public record StorageEngineStatusItem(
            String name,
            boolean allowed,
            boolean available,
            String description) {
    }

    /** 存储引擎状态清单。 */
    public record StorageEngineStatusResponse(
            List<StorageEngineStatusItem> engines,
            List<String> allowedProviders,
            boolean minioEnvAvailable) {
    }

    /** 存储连通性检测结果（bucketCreated 仅成功建桶时为 true）。 */
    public record StorageCheckResponse(
            boolean ok,
            String message,
            boolean bucketCreated) {
    }

    /** 运行时工作池。 */
    public record RuntimeWorkerPool(
            String name,
            int concurrency,
            int queueCount,
            int instances,
            int clusterCapacity,
            int active,
            double utilization) {
    }

    /** 模型限流统计。 */
    public record ModelLimiterStat(
            String modelId,
            String name,
            long active,
            long waiting,
            int limit) {
    }

    /** 运行时队列概览（Lite 模式下 queues 恒 []）。 */
    public record RuntimeQueuesResponse(
            boolean available,
            int upstreamConcurrency,
            int parseConcurrency,
            int wikiConcurrency,
            List<RuntimeWorkerPool> pools,
            List<Object> queues,
            boolean modelLimiterAvailable,
            List<ModelLimiterStat> models,
            long timestamp) {
    }

    /** 运行时任务列表（nextCursor 无更多时显式 null）。 */
    public record RuntimeTasksResponse(
            boolean available,
            List<Object> tasks,
            int pageSize,
            boolean hasMore,
            String nextCursor) {
    }
}
