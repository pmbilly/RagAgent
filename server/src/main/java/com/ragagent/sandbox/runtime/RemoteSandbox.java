package com.ragagent.sandbox.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * provider 中立的一次性/句柄执行器（对照 Go internal/sandbox/remote_sandbox.go 全文）。
 *
 * <p>两种用法：</p>
 * <ul>
 *   <li><b>一次性</b>：{@link #execute} 分配沙箱、跑脚本、无论成败都销毁——
 *       无 SessionID 的调用方的语义。</li>
 *   <li><b>会话绑定</b>：SessionBoundManager 经生命周期协调解析出句柄后调
 *       {@link #executeOnHandle}；句柄归管理器所有，此模式从不创建或删除沙箱。</li>
 * </ul>
 *
 * <p>Go 的 exec 错误折叠语义保留：{@code Exec} 的失败（RemoteError）折进
 * ExecuteResult.Error（ExitCode=-1）而不抛出——只有超时杀死的 killed 形态把
 * ErrTimeout 文案写进 Error。</p>
 */
public class RemoteSandbox implements SandboxManager.Sandbox {

    private static final Logger LOG = Logger.getLogger(RemoteSandbox.class.getName());

    /**
     * 每个远程沙箱里 WeKnora 上传脚本的绝对目录。与历史 Cube 路径保持一致，
     * 存量租户脚本才能继续引用 /workspace 下的文件。
     */
    public static final String REMOTE_SCRIPT_DIR = "/workspace";

    /** 对照 remoteCleanupTimeout：一次性 Execute 后的脱钩删除预算（30s）。 */
    static final Duration REMOTE_CLEANUP_TIMEOUT = Duration.ofSeconds(30);

    private final SandboxSessionClient client;
    private final SandboxSessionClient.CreateRequest createRequest;

    /** 对照 NewRemoteSandbox：只跑 executeOnHandle 的调用方可传零值 createRequest（null）。 */
    public RemoteSandbox(SandboxSessionClient client, SandboxSessionClient.CreateRequest createRequest) {
        this.client = client;
        this.createRequest = createRequest;
    }

    // ---- SandboxManager.Sandbox ----

    @Override
    public String type() {
        if (client == null) {
            return SandboxTypes.TYPE_DISABLED;
        }
        return client.provider();
    }

    @Override
    public boolean isAvailable() {
        if (client == null) {
            return false;
        }
        try {
            client.health();
            return true;
        } catch (RuntimeException err) {
            return false;
        }
    }

    @Override
    public void cleanup() {
        // no-op：RemoteSandbox 不持有长活状态；会话清理归 SessionBoundManager
    }

    /** 对照 Execute：一次性路径——建、跑、恒拆。 */
    @Override
    public SandboxManager.ExecuteResult execute(SandboxManager.ExecuteConfig cfg) {
        if (client == null) {
            throw SandboxException.sandboxDisabled();
        }
        if (cfg == null) {
            throw SandboxException.invalidScript();
        }
        if (createRequest == null || createRequest.templateId() == null
                || createRequest.templateId().isEmpty()) {
            throw SandboxException.internal("sandbox: remote sandbox has no template configured");
        }

        SandboxSessionClient.Handle handle;
        try {
            handle = client.create(createRequest);
        } catch (RemoteError err) {
            throw SandboxException.internal("remote sandbox: create: " + err.getMessage(), err);
        }
        try {
            return executeOnHandle(handle, cfg);
        } finally {
            disposeEphemeral(handle);
        }
    }

    /** 对照 ExecuteOnHandle：对既有句柄跑脚本，不分配也不删除沙箱。 */
    public SandboxManager.ExecuteResult executeOnHandle(
            SandboxSessionClient.Handle handle, SandboxManager.ExecuteConfig cfg) {
        if (client == null) {
            throw SandboxException.sandboxDisabled();
        }
        if (handle == null) {
            throw SandboxException.internal("sandbox: remote handle is required");
        }
        if (cfg == null) {
            throw SandboxException.invalidScript();
        }

        // 已在沙箱磁盘上的脚本跳过上传，免得遮住 skill venv 布局。镜像路径派生
        // skill 目录；workspace 路径要求显式 SkillDir，任意文件不能意外继承
        // 一个 skill 解释器。
        String remote = cfg.remoteScriptPath == null ? "" : cfg.remoteScriptPath.strip();
        if (!remote.isEmpty()) {
            String skillDir = SessionSandboxPaths.interpreterSkillDir(remote, cfg.skillDir);
            if (skillDir == null) {
                throw SandboxException.invalidScript();
            }
            SessionSandboxPaths.InterpreterCommand interpreter =
                    SessionSandboxPaths.skillInterpreterCommand(skillDir,
                            SessionSandboxPaths.clean(remote));
            List<String> args = new ArrayList<>(interpreter.args());
            if (cfg.args != null) {
                args.addAll(cfg.args);
            }
            SandboxSessionClient.ExecRequest request = new SandboxSessionClient.ExecRequest(
                    null, interpreter.command(), args, false, cfg.stdin, cfg.env,
                    SessionSandboxPaths.SESSION_WORKSPACE_ROOT,
                    SandboxSessionClient.ExecRequest.DEFAULT_SANDBOX_EXEC_USER,
                    effectiveTimeout(cfg, Duration.ZERO));
            Instant start = Instant.now();
            SandboxManager.ExecuteResult result;
            try {
                SandboxSessionClient.ExecResult execResult = client.exec(handle, request);
                result = remoteExecuteResult(execResult, null, Duration.between(start, Instant.now()));
            } catch (RemoteError err) {
                result = remoteExecuteResult(null, err, Duration.between(start, Instant.now()));
            }
            return result;
        }

        byte[] content = readScriptContent(cfg);
        String scriptName = SessionSandboxPaths.base(cfg.script == null ? "" : cfg.script);
        if (scriptName.isEmpty() || scriptName.equals(".") || scriptName.equals("/")) {
            throw SandboxException.invalidScript();
        }
        String remoteScript = SessionSandboxPaths.join(REMOTE_SCRIPT_DIR, scriptName);

        try {
            client.writeFile(handle, remoteScript, content);
        } catch (RemoteError err) {
            throw SandboxException.internal(
                    "remote sandbox: upload script " + remoteScript + ": " + err.getMessage(), err);
        }

        Duration timeout = effectiveTimeout(cfg, Duration.ZERO);
        List<String> args = new ArrayList<>();
        args.add(remoteScript);
        if (cfg.args != null) {
            args.addAll(cfg.args);
        }
        SandboxSessionClient.ExecRequest request = new SandboxSessionClient.ExecRequest(
                null, getInterpreter(remoteScript), args, false, cfg.stdin, cfg.env,
                REMOTE_SCRIPT_DIR,
                SandboxSessionClient.ExecRequest.DEFAULT_SANDBOX_EXEC_USER,
                timeout);
        Instant start = Instant.now();
        SandboxSessionClient.ExecResult execResult;
        try {
            execResult = client.exec(handle, request);
        } catch (RemoteError err) {
            return remoteExecuteResult(null, err, Duration.between(start, Instant.now()));
        }
        return remoteExecuteResult(execResult, null, Duration.between(start, Instant.now()));
    }

    /**
     * 对照 disposeEphemeral：一次性 Execute 完成后删除沙箱。错误已在 client 的
     * 归一化层记日志；这里吞掉，调用方永远看到主结果。Go 用 WithoutCancel 脱钩；
     * Java 用 best-effort 同步删除（差异备案）。
     */
    private void disposeEphemeral(SandboxSessionClient.Handle handle) {
        if (handle == null || handle.id() == null || handle.id().isEmpty()) {
            return;
        }
        try {
            client.delete(handle.id());
        } catch (RuntimeException err) {
            LOG.warning("[sandbox] ephemeral sandbox cleanup failed for " + handle.id()
                    + ": " + err.getMessage());
        }
    }

    /**
     * 对照 remoteExecuteResult：RemoteExecResult → ExecuteResult 的投影。
     * 保持超时契约：超时返回 killed 结果（ExitCode=-1、Error=ErrTimeout 文案），
     * 绝不是异常返回。
     */
    static SandboxManager.ExecuteResult remoteExecuteResult(
            SandboxSessionClient.ExecResult result, RemoteError err, Duration duration) {
        if (err != null) {
            // Go 的 InvalidRequest 分支与通用分支产物相同——逐行保留以便 diff 对照
            SandboxManager.ExecuteResult r = new SandboxManager.ExecuteResult();
            r.duration = duration == null ? Duration.ZERO : duration;
            r.exitCode = -1;
            r.error = err.getMessage();
            return r;
        }
        if (result == null) {
            SandboxManager.ExecuteResult r = new SandboxManager.ExecuteResult();
            r.duration = duration == null ? Duration.ZERO : duration;
            r.exitCode = -1;
            r.error = "sandbox: remote provider returned no result";
            return r;
        }
        if (result.killed()) {
            SandboxManager.ExecuteResult r = new SandboxManager.ExecuteResult();
            r.stdout = result.stdout();
            r.stderr = result.stderr();
            r.duration = result.duration() == null ? duration : result.duration();
            r.killed = true;
            r.exitCode = -1;
            r.error = SandboxException.Kind.TIMEOUT.defaultMessage;
            return r;
        }
        SandboxManager.ExecuteResult r = new SandboxManager.ExecuteResult();
        r.stdout = result.stdout();
        r.stderr = result.stderr();
        r.exitCode = result.exitCode();
        r.duration = result.duration() == null ? duration : result.duration();
        return r;
    }

    /**
     * 对照 getInterpreter：按扩展名挑解释器。远程后端先上传脚本再对该路径执行
     * 此解释器，所以选择不能依赖任何宿主侧状态。
     */
    static String getInterpreter(String scriptName) {
        switch (SessionSandboxPaths.ext(scriptName).toLowerCase()) {
            case ".py":
                return "python3";
            case ".sh", ".bash":
                return "bash";
            case ".js", ".mjs", ".cjs":
                return "node";
            case ".rb":
                return "ruby";
            case ".pl":
                return "perl";
            default:
                return "sh";
        }
    }

    /**
     * 对照 readScriptContent：优先 cfg.scriptContent（安全校验器填充），
     * 回落读 cfg.script 本地文件。
     */
    static byte[] readScriptContent(SandboxManager.ExecuteConfig cfg) {
        if (cfg.scriptContent != null && !cfg.scriptContent.isEmpty()) {
            return cfg.scriptContent.getBytes(StandardCharsets.UTF_8);
        }
        if (cfg.script == null || cfg.script.isEmpty()) {
            throw SandboxException.invalidScript();
        }
        try {
            return Files.readAllBytes(Path.of(cfg.script));
        } catch (java.nio.file.NoSuchFileException e) {
            throw SandboxException.scriptNotFound();
        } catch (java.io.IOException e) {
            throw SandboxException.internal("remote sandbox: read script: " + e.getMessage(), e);
        }
    }

    /** 对照 effectiveTimeout：cfg.timeout &gt; 0 优先，否则默认 60s。 */
    static Duration effectiveTimeout(SandboxManager.ExecuteConfig cfg, Duration fallback) {
        if (cfg != null && cfg.timeout != null && !cfg.timeout.isZero() && !cfg.timeout.isNegative()) {
            return cfg.timeout;
        }
        if (fallback != null && !fallback.isZero() && !fallback.isNegative()) {
            return fallback;
        }
        return Duration.ofSeconds(EffectiveConfig.DEFAULT_TIMEOUT_SEC);
    }
}
