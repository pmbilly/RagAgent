/**
 * 智能体管理域：内建智能体注册表与类型预设、自定义智能体 CRUD（{@code custom_agents} 表）、技能目录、
 * 推荐问题流与 agent config jsonb。2026-09-30 与 {@code initialization}（初始化/模型能力）分离——
 * 本域只回答"有哪些智能体、各是什么配置"。
 * <p>自有契约；agent config jsonb 的内层键（{@code agent_mode}/{@code system_prompt} 族）是跨
 * agentm/engine/embed/session 五包共享的自洽 schema，**保持 snake**（见 HANDOFF §11 边界清单）。</p>
 */
package com.ragagent.agentm;
