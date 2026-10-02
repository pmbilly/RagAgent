/**
 * ReAct 引擎域：AgentEngine 门面与八个段协作者（think/act/observe/prompt/finalize/steer/context-debug/ReAct 迭代）、
 * 提示词装配、token 预算与取消探测。对外入口 = AgentEngine.execute。
 *
 * <p>子包：{@code tools/}（工具族，按能力分 wiki / knowledge / sql / data / web）、{@code compaction/}（上下文压缩）、
 * {@code skills/}（技能装载）、{@code domain/}（引擎值类型）、{@code support/}（抓取等支撑）、
 * {@code modelcontext/}（模型输出上下文协议：来源注册表 / 句柄表 / 流解码 / 工具策略；B35 由顶层包并入）、
 * {@code management/}（智能体管理面：内建注册表 / 自定义智能体 CRUD / 技能目录 / 类型预设；B36 由顶层
 * {@code agentm} 并入——本域由此获得唯一 HTTP 面）。</p>
 */
package com.ragagent.agent;
