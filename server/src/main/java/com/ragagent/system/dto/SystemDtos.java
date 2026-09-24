package com.ragagent.system.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.auth.domain.UserPreferences;

/**
 * 系统管理端的响应 DTO 集（对照 Go internal/handler/system.go 与
 * deployment_capabilities.go 的响应 struct，字段序 = Go struct 声明序）。
 */
public final class SystemDtos {

    private SystemDtos() {
    }

    /** 对照 types.UserInfo（promote/revoke/list 的响应行）。 */
    @JsonPropertyOrder({"id", "username", "email", "avatar", "tenant_id",
            "is_active", "can_access_all_tenants", "is_system_admin", "preferences",
            "created_at", "updated_at"})
    public record UserInfoResponse(
            @JsonProperty("id") String id,
            @JsonProperty("username") String username,
            @JsonProperty("email") String email,
            @JsonProperty("avatar") String avatar,
            @JsonProperty("tenant_id") long tenantId,
            @JsonProperty("is_active") boolean isActive,
            @JsonProperty("can_access_all_tenants") boolean canAccessAllTenants,
            @JsonProperty("is_system_admin") boolean isSystemAdmin,
            @JsonProperty("preferences") UserPreferences preferences,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {

        /** 对照 User.ToUserInfo：字段直拷，无归一化（avatar 等已由实体 getter 归一）。 */
        public static UserInfoResponse from(com.ragagent.auth.domain.User u) {
            return new UserInfoResponse(
                    u.getId(), u.getUsername(), u.getEmail(), u.getAvatar(),
                    u.getTenantId(), u.isIsActive(), u.isCanAccessAllTenants(),
                    u.isIsSystemAdmin(), u.getPreferences(), u.getCreatedAt(), u.getUpdatedAt());
        }
    }

    /** 对照 handler.ListSystemAdminsResponse。恒非 null 数组（空页 → []）。 */
    @JsonPropertyOrder({"total", "admins"})
    public record SystemAdminListResponse(
            @JsonProperty("total") long total,
            @JsonProperty("admins") List<UserInfoResponse> admins) {
    }

    /** 对照 handler.CreateSystemUserResponse：generated_password 生成时才出现（omitempty）。 */
    @JsonPropertyOrder({"user", "generated_password"})
    public record CreateUserResponse(
            @JsonProperty("user") UserInfoResponse user,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonProperty("generated_password") String generatedPassword) {
    }

    /** 对照 handler.DeploymentCapability（reason omitempty："" → 键省略）。 */
    public record DeploymentCapability(
            @JsonProperty("supported") boolean supported,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("reason") String reason) {

        public static DeploymentCapability yes() {
            return new DeploymentCapability(true, "");
        }

        public static DeploymentCapability notRegistered() {
            return new DeploymentCapability(false, "route_not_registered");
        }
    }

    /** 对照 handler.DeploymentCapabilitiesData。capabilities 是 map → 键按字母序输出。 */
    public record DeploymentCapabilitiesData(
            @JsonProperty("edition") String edition,
            @JsonProperty("capabilities") Map<String, DeploymentCapability> capabilities) {
    }

    /**
     * 对照 handler.GetSystemInfoResponse（version/edition 恒输出，其余 omitempty）。
     *
     * <p>已知差异：{@code go_version} 随实现改名为 {@code java_version}（Java 后端
     * 没有 Go 版本，输出 JVM 运行时版本）；前端 SystemInfo.vue 与 i18n 已同步。</p>
     */
    @JsonPropertyOrder({"version", "edition", "commit_id", "build_time", "java_version",
            "keyword_index_engine", "vector_store_engine", "graph_database_engine",
            "minio_enabled", "db_version", "db_migration_error", "started_at", "uptime_seconds"})
    public record SystemInfoResponse(
            @JsonProperty("version") String version,
            @JsonProperty("edition") String edition,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("commit_id") String commitId,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("build_time") String buildTime,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("java_version") String javaVersion,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("keyword_index_engine") String keywordIndexEngine,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("vector_store_engine") String vectorStoreEngine,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("graph_database_engine") String graphDatabaseEngine,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT)
            @JsonProperty("minio_enabled") boolean minioEnabled,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("db_version") String dbVersion,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("db_migration_error") String dbMigrationError,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("started_at") String startedAt,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT)
            @JsonProperty("uptime_seconds") long uptimeSeconds) {
    }

    /**
     * 对照 types.ParserEngineInfo——**无 json tag**：encoding/json 用 Go 字段名原样输出
     * （"Name"/"Description"/... 驼峰大写开头），不是项目惯例的 snake_case。照抄别"修正"。
     */
    @JsonPropertyOrder({"Name", "Description", "FileTypes", "Available", "UnavailableReason"})
    public record ParserEngineInfo(
            @JsonProperty("Name") String name,
            @JsonProperty("Description") String description,
            @JsonProperty("FileTypes") List<String> fileTypes,
            @JsonProperty("Available") boolean available,
            @JsonProperty("UnavailableReason") String unavailableReason) {
    }

    /** 对照 handler.StorageEngineStatusItem。 */
    @JsonPropertyOrder({"name", "allowed", "available", "description"})
    public record StorageEngineStatusItem(
            @JsonProperty("name") String name,
            @JsonProperty("allowed") boolean allowed,
            @JsonProperty("available") boolean available,
            @JsonProperty("description") String description) {
    }

    /** 对照 handler.GetStorageEngineStatusResponse。 */
    @JsonPropertyOrder({"engines", "allowed_providers", "minio_env_available"})
    public record StorageEngineStatusResponse(
            @JsonProperty("engines") List<StorageEngineStatusItem> engines,
            @JsonProperty("allowed_providers") List<String> allowedProviders,
            @JsonProperty("minio_env_available") boolean minioEnvAvailable) {
    }

    /** 对照 handler.StorageCheckResponse：bucket_created 成功建桶时才出现（omitempty）。 */
    @JsonPropertyOrder({"ok", "message", "bucket_created"})
    public record StorageCheckResponse(
            @JsonProperty("ok") boolean ok,
            @JsonProperty("message") String message,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT)
            @JsonProperty("bucket_created") boolean bucketCreated) {
    }

    /** 对照 handler.RuntimeWorkerPool。 */
    @JsonPropertyOrder({"name", "concurrency", "queue_count", "instances",
            "cluster_capacity", "active", "utilization"})
    public record RuntimeWorkerPool(
            @JsonProperty("name") String name,
            @JsonProperty("concurrency") int concurrency,
            @JsonProperty("queue_count") int queueCount,
            @JsonProperty("instances") int instances,
            @JsonProperty("cluster_capacity") int clusterCapacity,
            @JsonProperty("active") int active,
            /** float64 → Go 编码器（0 不带 .0；utilization=Active/ClusterCapacity） */
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.ragagent.common.web.GoDoubleSerializer.class)
            @JsonProperty("utilization") double utilization) {
    }

    /** 对照 limiter.RuntimeStat（无 json tag？有——model_id/name/active/waiting/limit）。 */
    @JsonPropertyOrder({"model_id", "name", "active", "waiting", "limit"})
    public record ModelLimiterStat(
            @JsonProperty("model_id") String modelId,
            @JsonProperty("name") String name,
            @JsonProperty("active") long active,
            @JsonProperty("waiting") long waiting,
            @JsonProperty("limit") int limit) {
    }

    /** 对照 handler.RuntimeQueuesResponse。 */
    @JsonPropertyOrder({"available", "upstream_concurrency", "parse_concurrency",
            "wiki_concurrency", "pools", "queues", "model_limiter_available", "models", "timestamp"})
    public record RuntimeQueuesResponse(
            @JsonProperty("available") boolean available,
            @JsonProperty("upstream_concurrency") int upstreamConcurrency,
            @JsonProperty("parse_concurrency") int parseConcurrency,
            @JsonProperty("wiki_concurrency") int wikiConcurrency,
            @JsonProperty("pools") List<RuntimeWorkerPool> pools,
            /** Lite 模式恒 []（Go 的 noopTaskInspector.QueueStats → nil → handler 兜底 []） */
            @JsonProperty("queues") List<Object> queues,
            @JsonProperty("model_limiter_available") boolean modelLimiterAvailable,
            @JsonProperty("models") List<ModelLimiterStat> models,
            @JsonProperty("timestamp") long timestamp) {
    }

    /** 对照 handler.RuntimeTasksResponse（next_cursor omitempty）。 */
    @JsonPropertyOrder({"available", "tasks", "page_size", "has_more", "next_cursor"})
    public record RuntimeTasksResponse(
            @JsonProperty("available") boolean available,
            @JsonProperty("tasks") List<Object> tasks,
            @JsonProperty("page_size") int pageSize,
            @JsonProperty("has_more") boolean hasMore,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonProperty("next_cursor") String nextCursor) {
    }
}
