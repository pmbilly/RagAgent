/**
 * agent 工具族根：工具框架（{@code AgentTool} / {@code BaseTool} / {@code ToolRegistry} / 执行上下文与预算 /
 * 参数校验 / 输出持久化）、跨族共享的接缝与值类型（{@code DocChunkSupport} / {@code SearchAuth} /
 * {@code SearchTarget}），以及通用单件工具。
 *
 * <p>按能力分出的子包：{@code wiki/}（页面工具与视图）、{@code knowledge/}（知识检索工具）、
 * {@code sql/}（SQL 守卫与 database_query）、{@code data/}（DuckDB 分析）、{@code web/}（网页两件）。</p>
 *
 * <p>MCP 族（{@code Mcp*}）暂留根：其目录 / 暴露状态与 {@code ToolRegistry} 同包耦合（含包内可见与
 * protected 成员互访），待注册表中的 MCP 段外提后再分组。</p>
 */
package com.ragagent.agent.tools;
