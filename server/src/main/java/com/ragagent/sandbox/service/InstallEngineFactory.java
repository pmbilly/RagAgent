package com.ragagent.sandbox.service;

import com.ragagent.agent.AgentConfig;
import com.ragagent.agent.AgentEngine;
import com.ragagent.event.EventBus;
import com.ragagent.llm.LlmChatClient;

/**
 * install 管线的引擎工厂接缝（架构决策，D2 批任务书）。
 *
 * <p>Go 的管线直接调 {@code agents.CreateAgentEngine}；Java 的 createAgentEngine 在
 * {@code SessionAgentQaService}（私有）。接口放在 sandbox/service、实现在
 * session/service（{@code InstallEngineFactoryImpl}）——依赖方向 sandbox → session
 * 由本接口翻转，管线只依赖接口。</p>
 *
 * <p>与任务书形状的唯一偏差（备案）：{@code create} 多一个 {@code tenantId}
 * 首参。会话 Manager 的 shell/file 调用都以 (tenantId, sessionId) 寻址绑定
 * （{@code SessionBoundManager.sandboxKey}），而安装管线跑在后台虚拟线程上、
 * TenantContext 不可用——缺它则安装模式工具拿不到执行体。</p>
 */
public interface InstallEngineFactory {

    /**
     * 装配安装模式引擎：ToolRegistry 注册 newInstallShellExecTool(executor, skillDir)
     * + WriteSkillFileTool/EditSkillFileTool，无知识库、无 MCP；skillDir 取自
     * {@link AgentConfig#getSkillInstallDir()}（{@code EnableSkillInstallMode} 已授）。
     */
    AgentEngine create(long tenantId, AgentConfig config, LlmChatClient chatModel,
            EventBus bus, String sessionId, String assistantMessageId);
}
