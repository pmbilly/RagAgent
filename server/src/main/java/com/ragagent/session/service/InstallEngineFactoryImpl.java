package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ragagent.agent.AgentConfig;
import com.ragagent.agent.AgentEngine;
import com.ragagent.agent.AgentPrompts;
import com.ragagent.agent.AgentPromptTemplates;
import com.ragagent.agent.tools.EditSkillFileTool;
import com.ragagent.agent.tools.SandboxExecuteResult;
import com.ragagent.agent.tools.SandboxInstallCommandExecutor;
import com.ragagent.agent.tools.SandboxPaths;
import com.ragagent.agent.tools.ShellExecOptions;
import com.ragagent.agent.tools.ShellExecTool;
import com.ragagent.agent.tools.SkillFileStore;
import com.ragagent.agent.tools.ToolRegistry;
import com.ragagent.agent.tools.WriteSkillFileTool;
import com.ragagent.event.EventBus;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.sandbox.runtime.SandboxManager;
import com.ragagent.sandbox.runtime.SessionBoundManager;
import com.ragagent.sandbox.service.InstallEngineFactory;

/**
 * {@link InstallEngineFactory} 的生产实现（D2 批；对照 Go
 * {@code agentService.CreateAgentEngine} 的安装模式最小形状）。
 *
 * <p>装配面：ToolRegistry 注册 {@link ShellExecTool#newInstallShellExecTool}
 * （root + skills root，{@code SessionBoundManager.execShellCommandWithOptions(asRoot)}
 * 的适配器写法参照 {@link SessionSandboxExecutionService} 的 shell 适配器）+
 * {@link WriteSkillFileTool}/{@link EditSkillFileTool}（skillDir 经
 * {@link SandboxPaths#validatedImageSkillDir}）；无知识库（kbInfos 全空）、
 * 无 MCP（不 prepareMcpTools）、无检索工具族——安装模式白名单只有 shell +
 * skill 文件三件（Go installerAgentConfig 的 AllowedTools 同）。</p>
 *
 * <p>已知接缝（备案）：不走 {@link SessionSandboxExecutionService#registerSandboxShellIfAllowed}
 * ——那是会话（非 root）变体；安装 shell 必须走 install 变体。</p>
 */
@Service
public class InstallEngineFactoryImpl implements InstallEngineFactory {

    private static final Logger log = LoggerFactory.getLogger(InstallEngineFactoryImpl.class);

    /**
     * 延迟解析（ObjectProvider）：TenantSkillService → pipeline → 本工厂 →
     * SessionSandboxExecutionService → TenantSkillService 是一条 bean 图上的环；
     * 引擎只在后台管线的执行时刻（安装 run 打开时）才解析执行面，启动期不需要。
     */
    private final org.springframework.beans.factory.ObjectProvider<SessionSandboxExecutionService> sandboxExecution;

    public InstallEngineFactoryImpl(
            org.springframework.beans.factory.ObjectProvider<SessionSandboxExecutionService> sandboxExecution) {
        this.sandboxExecution = sandboxExecution;
    }

    @Override
    public AgentEngine create(long tenantId, AgentConfig config, LlmChatClient chatModel,
            EventBus bus, String sessionId, String assistantMessageId) {
        String skillDir = SandboxPaths.validatedImageSkillDir(config.getSkillInstallDir());
        if (skillDir == null) {
            throw new IllegalStateException("sandbox: invalid skill name \""
                    + config.getSkillInstallDir() + "\"");
        }

        SandboxManager mgr = resolveManager(tenantId, sessionId, config);
        if (!(mgr instanceof SessionBoundManager bound)) {
            throw new IllegalStateException(
                    "sandbox backend does not support install-mode shell");
        }

        SandboxInstallCommandExecutor installExec = installExecutor(bound, tenantId);
        SkillFileStore fileStore = skillFileStore(bound, tenantId);

        ToolRegistry toolRegistry = new ToolRegistry();
        if (config.getMaxToolOutputChars() > 0) {
            toolRegistry.setMaxToolOutputSize(config.getMaxToolOutputChars());
        }
        toolRegistry.registerTool(ShellExecTool.newInstallShellExecTool(installExec, skillDir));
        toolRegistry.registerTool(new WriteSkillFileTool(fileStore, skillDir));
        toolRegistry.registerTool(new EditSkillFileTool(fileStore, skillDir));

        // 知识面全空（Go CreateAgentEngine 的安装分支同形）；自定义模板即终选模板
        String systemPromptTemplate =
                config instanceof QaAgentConfig q ? q.getSystemPrompt() : "";
        AgentEngine engine = new AgentEngine(config, chatModel, toolRegistry, bus,
                new ArrayList<AgentPrompts.KnowledgeBaseInfo>(),
                new ArrayList<AgentPrompts.SelectedDocumentInfo>(),
                sessionId, systemPromptTemplate);
        engine.setAppConfig(new AgentPromptTemplates.TemplatesConfig(new ArrayList<>()));
        log.info("Created skill installer engine: session={}, skillDir={}", sessionId, skillDir);
        return engine;
    }

    private SandboxManager resolveManager(long tenantId, String sessionId, AgentConfig config) {
        SessionSandboxExecutionService.Resolution r = sandboxExecution.getObject()
                .resolveForExecution(tenantId, sessionId, config.getSandboxConfigId());
        return r.manager();
    }

    /** 会话 Manager → 特权执行面的窄适配（对照 Go installExecutor 的能力取用）。 */
    private static SandboxInstallCommandExecutor installExecutor(SessionBoundManager bound,
            long tenantId) {
        return (String sid, String command, ShellExecOptions opts) -> {
            SandboxManager.ExecuteResult r = bound.execShellCommandWithOptions(tenantId, sid,
                    command, new SessionBoundManager.ShellExecOptions(
                            opts.onOutput() == null ? null : opts.onOutput()::onOutput,
                            opts.workDir(), opts.timeout(), opts.env(),
                            opts.allowSkillsRoot(), opts.asRoot()));
            return new SandboxExecuteResult(r.stdout, r.stderr, r.exitCode, r.duration,
                    r.killed, r.error);
        };
    }

    /** 会话 Manager → SkillFileStore 窄适配（WriteSessionFile 本身只许 skills 镜像根）。 */
    private static SkillFileStore skillFileStore(SessionBoundManager bound, long tenantId) {
        return new SkillFileStore() {
            @Override
            public com.ragagent.agent.tools.RemoteStatEntry statSessionFile(String sessionId,
                    String filePath) throws Exception {
                var s = bound.statSessionFile(tenantId, sessionId, filePath);
                if (s == null) {
                    return null;
                }
                return new com.ragagent.agent.tools.RemoteStatEntry(s.path(),
                        s.type() == null ? "file" : s.type().name().toLowerCase(),
                        s.size(), s.modTime() == null ? null : s.modTime().toInstant());
            }

            @Override
            public byte[] readSessionFile(String sessionId, String filePath) throws Exception {
                return bound.readSessionFile(tenantId, sessionId, filePath);
            }

            @Override
            public void writeSessionFile(String sessionId, String filePath, byte[] content)
                    throws Exception {
                bound.writeSessionFile(tenantId, sessionId, filePath, content);
            }
        };
    }
}
