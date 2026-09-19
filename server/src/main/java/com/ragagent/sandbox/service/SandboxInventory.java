package com.ragagent.sandbox.service;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对照 Go {@code service.SandboxInventory}（internal/application/service/tenant_sandbox_config.go
 * L241-250）：一份配置持有什么、一次变更会打扰到谁。直接进 409 拒绝体（struct 声明序）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SandboxInventory(
        /** 恒输出（无 omitempty） */
        @JsonProperty("sandbox_count") int sandboxCount,
        @JsonProperty("session_ids") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> sessionIds,
        @JsonProperty("agent_names") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> agentNames,
        /**
         * 报告 SandboxCount 是未知而非零——provider 联系不上。管理页必须如实说，
         * 而不是渲染一个安心的 "0 sandboxes"。
         */
        @JsonProperty("unverifiable") @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean unverifiable) {
}
