package com.ragagent.agentm.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agentm.domain.CustomAgentEntity;

/**
 * 自定义 Agent 的「行 + 解析后配置」对（{@code CustomAgentService} 各方法的结果形状）。
 *
 * <p>原先是 {@code CustomAgentService} 的嵌套记录（{@code Result}）——响应装配 DTO
 * {@link AgentResponses} 要命名它，于是形成了"dto → service"的倒挂。提成 dto 类型后，
 * 服务返回它、响应装配消费它，方向回到 service/dto → dto。</p>
 */
public record CustomAgentResult(CustomAgentEntity row, ObjectNode config) {
}
