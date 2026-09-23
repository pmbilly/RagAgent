package com.ragagent.sandbox.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对照 Go {@code sandbox.Manager} 接口与 {@code ExecuteConfig}/{@code ExecuteResult}
 * （internal/sandbox/sandbox.go L128-242）。
 *
 * <p>Java 侧把三个 Go 类型收进本文件：接口本体、嵌套的 {@link Sandbox}（对照
 * Sandbox 接口）、可变的 {@link ExecuteConfig}/{@link ExecuteResult}。
 * 会话绑定实现见 {@link SessionBoundManager}；无状态一次性执行器见
 * {@link RemoteSandbox}。</p>
 *
 * <p>Go 的 {@code error} 返回在 Java 折叠为 {@link SandboxException}（哨兵对照见该类）。
 * Go 的「校验失败同时返回 result 与 error」形态由 {@code SandboxException.partialResult}
 * 承载。</p>
 */
public interface SandboxManager {

    /** 对照 Execute：跑一段脚本；失败抛 SandboxException。 */
    SandboxManager.ExecuteResult execute(SandboxManager.ExecuteConfig config);

    /** 对照 Cleanup：释放管理器持有的资源（幂等）。 */
    void cleanup();

    /** 对照 GetSandbox：暴露诊断用的 Sandbox 面。 */
    Sandbox getSandbox();

    /** 对照 GetType：当前生效的沙箱类型（SandboxTypes 常量）。 */
    String getType();

    /** 对照 Sandbox 接口：隔离脚本执行面。 */
    interface Sandbox {

        SandboxManager.ExecuteResult execute(SandboxManager.ExecuteConfig config);

        void cleanup();

        String type();

        boolean isAvailable();
    }

    /**
     * 对照 ExecuteConfig。字段名与 Go 一致（camelCase）；Timeout 用 Duration，
     * null/零值 = 用默认。可变：调用方（工具层）就地构造。
     */
    final class ExecuteConfig {

        /** 绝对路径的脚本文件（沙箱外）。 */
        public String script;
        /** 命令行参数。 */
        public List<String> args;
        /** 脚本工作目录。 */
        public String workDir;
        /** 最大执行时长；null 或 <=0 用默认。 */
        public Duration timeout;
        /** 额外环境变量。 */
        public Map<String, String> env;
        /** Docker 专用：允许网络。 */
        public boolean allowNetwork;
        /** Docker 专用：内存上限（字节）。 */
        public long memoryLimit;
        /** Docker 专用：CPU 上限（核）。 */
        public double cpuLimit;
        /** Docker 专用：只读根文件系统。 */
        public boolean readOnlyRootfs;
        /** 传给脚本的标准输入。 */
        public String stdin;
        /** 跳过安全校验（仅限受信脚本）。 */
        public boolean skipValidation;
        /** 脚本内容（可选；缺省时从 script 路径读盘）。 */
        public String scriptContent;
        /**
         * 会话作用域：非空时跑在该会话的持久沙箱上（Cube/E2B/Docker）；
         * 空 = 一次性沙箱。
         */
        public String sessionId;
        /**
         * 沙箱内已存在的脚本的绝对路径；设置后跳过上传就地执行。允许位置：
         * SkillsImageRoot 下的已安装 skill 文件，或 /workspace 下（且不在
         * /workspace/input）的会话可写文件——后者必须同时给 skillDir。
         */
        public String remoteScriptPath;
        /**
         * remoteScriptPath 位于 /workspace 时，用哪个已安装 skill 的解释器跑它。
         * 镜像内路径从脚本自身派生目录，忽略本字段。
         */
        public String skillDir;
    }

    /** 对照 ExecuteResult。 */
    final class ExecuteResult {

        public String stdout = "";
        public String stderr = "";
        public int exitCode;
        public Duration duration = Duration.ZERO;
        public boolean killed;
        public String error = "";

        /** 对照 IsSuccess：零退出、未被杀、无错误。 */
        public boolean isSuccess() {
            return exitCode == 0 && !killed && (error == null || error.isEmpty());
        }

        public static ExecuteResult of(String stdout, String stderr, int exitCode) {
            ExecuteResult r = new ExecuteResult();
            r.stdout = stdout == null ? "" : stdout;
            r.stderr = stderr == null ? "" : stderr;
            r.exitCode = exitCode;
            return r;
        }
    }

    /** 深拷贝辅助（对照 Go 的 {@code copy := *config} 值语义）。 */
    static SandboxManager.ExecuteConfig copyOf(SandboxManager.ExecuteConfig src) {
        SandboxManager.ExecuteConfig c = new SandboxManager.ExecuteConfig();
        c.script = src.script;
        c.args = src.args == null ? null : new ArrayList<>(src.args);
        c.workDir = src.workDir;
        c.timeout = src.timeout;
        c.env = src.env == null ? null : new LinkedHashMap<>(src.env);
        c.allowNetwork = src.allowNetwork;
        c.memoryLimit = src.memoryLimit;
        c.cpuLimit = src.cpuLimit;
        c.readOnlyRootfs = src.readOnlyRootfs;
        c.stdin = src.stdin;
        c.skipValidation = src.skipValidation;
        c.scriptContent = src.scriptContent;
        c.sessionId = src.sessionId;
        c.remoteScriptPath = src.remoteScriptPath;
        c.skillDir = src.skillDir;
        return c;
    }
}
