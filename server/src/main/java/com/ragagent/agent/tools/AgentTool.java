package com.ragagent.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;

/**
 * Agent 工具接口（对照 Go {@code types.Tool}，internal/types/agent.go:329-344）。
 *
 * <p>四方法的语义逐字保留：Name 唯一标识；Description 给模型看；Parameters 是
 * 参数 JSON Schema（<b>字节即契约</b>——发给 LLM 的工具块按此逐字节稳定，
 * 波 4.5b/c 的 schema 构造以 Go 实录为准）；Execute 跑工具。</p>
 *
 * <p>Java 侧的 error 通道：Go 的 {@code Execute(...) (*ToolResult, error)} 双返回值
 * 在这里折叠——工具出错时返回 {@code success=false} 且 {@code error} 置好文案的
 * {@link ToolResult}，不抛异常（registry 依赖这个约定做截断与日志分流）。</p>
 */
public interface AgentTool {

    /** 工具唯一标识（对照 Name()）。 */
    String getName();

    /** 给模型看的描述（对照 Description()）。 */
    String getDescription();

    /** 参数 JSON Schema（对照 Parameters() 的 json.RawMessage）。 */
    JsonNode getParameters();

    /** 执行工具（对照 Execute(ctx, args)；ctx 的内容已拆进 {@link ToolRequest}）。 */
    ToolResult execute(ToolRequest request);
}
