package com.ragagent.agent.skills;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.ragagent.agent.tools.GoPath;
import com.ragagent.agent.tools.RemoteStatEntry;
import com.ragagent.agent.tools.SandboxPaths;

/**
 * skill 生命周期管理：发现、读取与 shell 环境准备（对照 Go
 * internal/agent/skills/manager.go + shell_staging.go + shell_environment.go，全文移植）。
 * 协调 skill source 与会话资源 staging；shell_exec 负责执行。
 */
public final class Manager {

    /** sandbox 会话文件系统的窄切片（对照 sandbox.SessionFileStore 被 skills 用到的三个方法）。 */
    public interface SessionFileStore {
        /** stat 会话 sandbox 内单个路径；不存在返回 null（对照 StatSessionFile + IsRemoteNotFound 折叠）。 */
        RemoteStatEntry statSessionFile(String sessionId, String path) throws Exception;

        /** 读会话 sandbox 内文件字节（对照 ReadSessionFile）。 */
        byte[] readSessionFile(String sessionId, String path) throws Exception;

        /** 会话布局准备一次后批量写 workspace 文件（对照 WriteSessionWorkspaceFiles）。 */
        void writeSessionWorkspaceFiles(String sessionId, List<StagedFile> files) throws Exception;
    }

    /** WriteSessionWorkspaceFiles 的一个路径/内容对（对照 sandbox.SessionWorkspaceFile）。 */
    public record StagedFile(String path, byte[] content) {
    }

    /**
     * sandbox 管理入口的窄切片（对照 sandbox.Manager 的 Cleanup + 
     * SessionCapabilityProvider.SessionFileStore）。
     */
    public interface SandboxGateway {
        /** 该部署无法暴露会话文件系统时返回 null。 */
        SessionFileStore sessionFileStore();

        /** 对照 Manager.Cleanup。 */
        void cleanup();
    }

    /** staging 限额（Go shell_staging.go 常量）。 */
    static final int MAX_STAGED_SKILL_FILES = 1000;
    static final int MAX_STAGED_SKILL_BYTES = 32 * 1024 * 1024;

    /** 缺省产物输出目录：基础镜像可写树内，同会话多次 Execute 间存续。 */
    static final String DEFAULT_ARTIFACT_OUTPUT_DIR = "/workspace/output";

    private final Loader loader;
    private final SandboxGateway sandboxGateway;

    /** 本轮 sandbox 镜像里已安装的 skills。设置后它是模型唯一被告知的 source。 */
    private SkillSource tenantSource;

    private final List<String> skillDirs;
    private final List<String> allowedSkills;
    private final boolean enabled;

    private final ReentrantReadWriteLock mu = new ReentrantReadWriteLock();
    private List<Skill.SkillMetadata> metadataCache = new ArrayList<>();
    private final ReentrantLock stageMu = new ReentrantLock();
    private Map<String, String> stagedSkills;

    /** Manager 的配置（对照 ManagerConfig）。 */
    public record ManagerConfig(List<String> skillDirs, List<String> allowedSkills, boolean enabled) {
    }

    public Manager(ManagerConfig config, SandboxGateway sandboxGateway) {
        ManagerConfig cfg = config != null ? config : new ManagerConfig(List.of(), List.of(), false);
        this.loader = new Loader(cfg.skillDirs());
        this.sandboxGateway = sandboxGateway;
        this.skillDirs = cfg.skillDirs() == null ? List.of() : cfg.skillDirs();
        this.allowedSkills = cfg.allowedSkills() == null ? List.of() : cfg.allowedSkills();
        this.enabled = cfg.enabled();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 挂上管理员装进本轮 sandbox 配置快照镜像的 skills（对照 WithTenantSource）。
     * 属于构造期——调用方必须在 Initialize 之前调用。
     */
    public Manager withTenantSource(SkillSource source) {
        this.tenantSource = source;
        return this;
    }

    /** 决定一个 skill 名由哪个 source 负责（对照 resolveSource）。 */
    private SkillSource resolveSource(String skillName) {
        if (tenantSource != null) {
            return tenantSource;
        }
        return loader;
    }

    /** 模型被告知的集合（对照 discoverAllSkills）。镜像装了 skill 时镜像是唯一事实。 */
    private List<Skill.SkillMetadata> discoverAllSkills() throws Exception {
        if (tenantSource != null) {
            return tenantSource.discoverSkills();
        }
        return loader.reload();
    }

    /** 发现全部 skills 并缓存元数据；启动时调用（对照 Initialize）。 */
    public void initialize() throws Exception {
        if (!enabled) {
            return;
        }
        List<Skill.SkillMetadata> metadata = discoverAllSkills();
        if (!allowedSkills.isEmpty()) {
            metadata = filterAllowedSkills(metadata);
        }
        mu.writeLock().lock();
        try {
            metadataCache = metadata;
        } finally {
            mu.writeLock().unlock();
        }
    }

    private List<Skill.SkillMetadata> filterAllowedSkills(List<Skill.SkillMetadata> metadata) {
        if (allowedSkills.isEmpty()) {
            return metadata;
        }
        Set<String> allowedSet = new HashSet<>(allowedSkills);
        List<Skill.SkillMetadata> filtered = new ArrayList<>();
        for (Skill.SkillMetadata meta : metadata) {
            if (allowedSet.contains(meta.name())) {
                filtered.add(meta);
            }
        }
        return filtered;
    }

    /** 全部已发现 skill 的元数据；系统提示词注入用（Level 1；对照 GetAllMetadata）。 */
    public List<Skill.SkillMetadata> getAllMetadata() {
        if (!enabled) {
            return null;
        }
        mu.readLock().lock();
        try {
            return new ArrayList<>(metadataCache);
        } finally {
            mu.readLock().unlock();
        }
    }

    /** 加载一个 skill 的完整指令（Level 2；对照 LoadSkill）。 */
    public Skill loadSkill(String skillName) throws Exception {
        if (!enabled) {
            throw new Skill.SkillValidationException("skills are not enabled");
        }
        if (!isSkillAllowed(skillName)) {
            throw new Skill.SkillValidationException("skill not allowed: " + skillName);
        }
        return resolveSource(skillName).loadSkillInstructions(skillName);
    }

    private boolean isSkillAllowed(String skillName) {
        if (allowedSkills.isEmpty()) {
            return true;
        }
        for (String name : allowedSkills) {
            if (name.equals(skillName)) {
                return true;
            }
        }
        return false;
    }

    /** 读 skill 目录里的一个额外文件（Level 3；对照 ReadSkillFile）。 */
    public String readSkillFile(String skillName, String filePath) throws Exception {
        if (!enabled) {
            throw new Skill.SkillValidationException("skills are not enabled");
        }
        if (!isSkillAllowed(skillName)) {
            throw new Skill.SkillValidationException("skill not allowed: " + skillName);
        }
        return resolveSource(skillName).loadSkillFile(skillName, filePath).content();
    }

    /** 列出 skill 目录的全部文件（对照 ListSkillFiles）。 */
    public List<String> listSkillFiles(String skillName) throws Exception {
        if (!enabled) {
            throw new Skill.SkillValidationException("skills are not enabled");
        }
        if (!isSkillAllowed(skillName)) {
            throw new Skill.SkillValidationException("skill not allowed: " + skillName);
        }
        return resolveSource(skillName).listSkillFiles(skillName);
    }

    /** SandboxSkillDir 的 (dir, installed) 二元组。 */
    public record SandboxDir(String dir, boolean installed) {
    }

    /**
     * skill 在 sandbox 镜像里的位置，以及该路径是否值得说出口（对照
     * SandboxSkillDir）。只有已安装 skill 有：宿主 skill 会被上传，原始基路径
     * 沙箱 shell 摸不到——说了比不说更糟。
     */
    public SandboxDir sandboxSkillDir(String skillName) {
        if (!enabled || !isSkillAllowed(skillName)) {
            return new SandboxDir("", false);
        }
        if (!(resolveSource(skillName) instanceof ImageSkillSource image)) {
            return new SandboxDir("", false);
        }
        String dir;
        try {
            dir = image.getSkillBasePath(skillName);
        } catch (Exception e) {
            return new SandboxDir("", false);
        }
        dir = dir == null ? "" : dir.strip();
        return new SandboxDir(dir, !dir.isEmpty());
    }

    /** 详细信息（对照 GetSkillInfo / SkillInfo）。 */
    public record SkillInfo(String name, String description, String basePath, String instructions, List<String> files) {
    }

    public SkillInfo getSkillInfo(String skillName) throws Exception {
        if (!enabled) {
            throw new Skill.SkillValidationException("skills are not enabled");
        }
        if (!isSkillAllowed(skillName)) {
            throw new Skill.SkillValidationException("skill not allowed: " + skillName);
        }
        SkillSource source = resolveSource(skillName);
        Skill skill = source.loadSkillInstructions(skillName);
        List<String> files;
        try {
            files = source.listSkillFiles(skillName);
        } catch (Exception e) {
            files = List.of(); // 非致命错误
        }
        return new SkillInfo(skill.name, skill.description, skill.basePath, skill.instructions, files);
    }

    /** 刷新 skill 缓存（对照 Reload）。 */
    public void reload() throws Exception {
        if (!enabled) {
            return;
        }
        List<Skill.SkillMetadata> metadata = discoverAllSkills();
        if (!allowedSkills.isEmpty()) {
            metadata = filterAllowedSkills(metadata);
        }
        mu.writeLock().lock();
        try {
            metadataCache = metadata;
        } finally {
            mu.writeLock().unlock();
        }
    }

    /** 释放资源（对照 Cleanup）。 */
    public void cleanup() {
        if (sandboxGateway != null) {
            sandboxGateway.cleanup();
        }
    }

    // ---- shell_staging.go ----

    /**
     * 把显式配置的宿主 skill 资源复制进本会话（对照 stageShellSkill）。从不在宿主
     * 执行、不装依赖。文件按内容修订寻址；每 manager/session 只准备一次；错误不
     * 留 ready 缓存条目，之后修正过的调用可以重试。
     */
    String stageShellSkill(String sessionId, String name) throws Exception {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new Skill.SkillValidationException(
                    "a session is required to prepare skill " + TenantSkillSource.GoQuote.quote(name));
        }
        if (SandboxPaths.skillDirFor(name) == null) {
            throw new Skill.SkillValidationException("sandbox: invalid skill name " + TenantSkillSource.GoQuote.quote(name));
        }
        boolean listed = false;
        List<Skill.SkillMetadata> metas = getAllMetadata();
        if (metas != null) {
            for (Skill.SkillMetadata meta : metas) {
                if (meta != null && meta.name().equals(name)) {
                    listed = true;
                    break;
                }
            }
        }
        if (!listed) {
            throw new Skill.SkillValidationException("skill " + TenantSkillSource.GoQuote.quote(name) + " is not available to this agent");
        }
        SessionFileStore store = sessionFileStore();
        if (store == null) {
            throw new Skill.SkillValidationException(
                    "preparing host skill " + TenantSkillSource.GoQuote.quote(name) + " requires a session filesystem");
        }
        stageMu.lock();
        try {
            String key = sessionId + "\u0000" + name;
            String dir = stagedSkills == null ? null : stagedSkills.get(key);
            if (dir != null && !dir.isEmpty()) {
                boolean intact = stagedSkillStillIntact(store, sessionId, name, dir);
                if (intact) {
                    return dir;
                }
                stagedSkills.remove(key);
            }
            SkillSource source = resolveSource(name);
            List<String> files;
            try {
                files = source.listSkillFiles(name);
            } catch (Exception e) {
                throw new Skill.SkillValidationException(
                        "list skill " + TenantSkillSource.GoQuote.quote(name) + " for execution: " + e.getMessage());
            }
            List<String> sorted = new ArrayList<>(files);
            java.util.Collections.sort(sorted);
            if (sorted.size() > MAX_STAGED_SKILL_FILES) {
                throw new Skill.SkillValidationException(
                        "skill " + TenantSkillSource.GoQuote.quote(name) + " exceeds the " + MAX_STAGED_SKILL_FILES
                                + "-file staging limit; install it into the sandbox image");
            }
            record Resource(String name, byte[] data) {
            }
            List<Resource> resources = new ArrayList<>();
            int total = 0;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String rel : sorted) {
                rel = rel.replace("\\", "/");
                if (rel.isEmpty() || rel.startsWith("/") || !GoPath.clean(rel).equals(rel)
                        || rel.equals("..") || rel.startsWith("../")) {
                    throw new Skill.SkillValidationException("invalid skill resource path " + TenantSkillSource.GoQuote.quote(rel));
                }
                if (skipStagedSkillRel(rel)) {
                    continue;
                }
                Skill.SkillFile file = source.loadSkillFile(name, rel);
                byte[] content = file.content().getBytes(StandardCharsets.UTF_8);
                total += content.length;
                if (total > MAX_STAGED_SKILL_BYTES) {
                    throw new Skill.SkillValidationException(
                            "skill " + TenantSkillSource.GoQuote.quote(name) + " exceeds the " + MAX_STAGED_SKILL_BYTES
                                    + "-byte staging limit; install it into the sandbox image");
                }
                // Go: fmt.Fprintf(digest, "%d:%s:%d:", len(rel), rel, len(file.Content))
                String header = lenBytesUtf8(rel) + ":" + rel + ":" + content.length + ":";
                digest.update(header.getBytes(StandardCharsets.UTF_8));
                digest.update(content);
                resources.add(new Resource(rel, content));
            }
            if (resources.isEmpty()) {
                throw new Skill.SkillValidationException(
                        "skill " + TenantSkillSource.GoQuote.quote(name) + " has no resources to stage");
            }
            String hex = hexOf(digest.digest(), 12);
            String stagedDir = SandboxPaths.join(SandboxPaths.SESSION_WORKSPACE_ROOT, ".skills", name, hex);
            List<StagedFile> payload = new ArrayList<>(resources.size());
            for (Resource file : resources) {
                payload.add(new StagedFile(SandboxPaths.join(stagedDir, file.name()), file.data()));
            }
            try {
                store.writeSessionWorkspaceFiles(sessionId, payload);
            } catch (Exception e) {
                throw new Skill.SkillValidationException("prepare skill " + TenantSkillSource.GoQuote.quote(name)
                        + ": " + e.getMessage() + "; command was not started");
            }
            if (stagedSkills == null) {
                stagedSkills = new LinkedHashMap<>();
            }
            stagedSkills.put(key, stagedDir);
            return stagedDir;
        } finally {
            stageMu.unlock();
        }
    }

    /** UTF-8 字节长度（Go len(rel) 对 string 是字节长）。 */
    private static int lenBytesUtf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String hexOf(byte[] digest, int prefixBytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < prefixBytes; i++) {
            sb.append(String.format("%02x", digest[i]));
        }
        return sb.toString();
    }

    static boolean skipStagedSkillRel(String rel) {
        for (String part : rel.split("/")) {
            switch (part) {
                case ".venv", "node_modules", ".git", "__pycache__" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    /**
     * 缓存的 staging 目录是否仍与宿主包一致（对照 stagedSkillStillIntact）。
     * SKILL.md 缺失/被改、或资源缺失都强制 restage，避免复用被模型改过的树。
     */
    boolean stagedSkillStillIntact(SessionFileStore store, String sessionId, String name, String dir)
            throws Exception {
        String manifest = SandboxPaths.join(dir, Skill.SKILL_FILE_NAME);
        RemoteStatEntry stat = store.statSessionFile(sessionId, manifest);
        if (stat == null) {
            return false;
        }
        if (!stat.isFile()) {
            throw new Skill.SkillValidationException(
                    "staged skill manifest is not a regular file; existing data was preserved");
        }
        SkillSource source = resolveSource(name);
        Skill.SkillFile expected;
        try {
            expected = source.loadSkillFile(name, Skill.SKILL_FILE_NAME);
        } catch (Exception e) {
            return false;
        }
        byte[] actual;
        try {
            actual = store.readSessionFile(sessionId, manifest);
        } catch (Exception e) {
            return false;
        }
        if (!java.util.Arrays.equals(actual, expected.content().getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        List<String> files;
        try {
            files = source.listSkillFiles(name);
        } catch (Exception e) {
            return false;
        }
        for (String rel : files) {
            rel = rel.replace("\\", "/");
            if (skipStagedSkillRel(rel)) {
                continue;
            }
            RemoteStatEntry entry = store.statSessionFile(sessionId, SandboxPaths.join(dir, rel));
            if (entry == null || !entry.isFile()) {
                return false;
            }
        }
        return true;
    }

    private SessionFileStore sessionFileStore() {
        return sandboxGateway == null ? null : sandboxGateway.sessionFileStore();
    }

    // ---- shell_environment.go ----

    /** PrepareShellEnvironment 的 (command, env) 结果。 */
    public record PreparedShell(String command, Map<String, String> env) {
    }

    /**
     * 把一个允许的、已安装 skill 的运行时接到普通命令同一 shell 原语上（对照
     * PrepareShellEnvironment）。凭据仍是调用方的责任，按工具调用逐次解析、
     * 绝不在此持久化。
     */
    public PreparedShell prepareShellEnvironment(String sessionId, String skillName, String command,
            Map<String, String> env) throws Exception {
        if (!enabled || !isSkillAllowed(skillName)) {
            throw new Skill.SkillValidationException(
                    "skill " + TenantSkillSource.GoQuote.quote(skillName) + " is not available to this agent");
        }
        SandboxDir sandboxDir = sandboxSkillDir(skillName);
        String dir = sandboxDir.dir();
        if (!sandboxDir.installed()) {
            dir = stageShellSkill(sessionId, skillName);
        } else if (SandboxPaths.validatedImageSkillDir(dir) == null) {
            throw new Skill.SkillValidationException(
                    "invalid installed directory for skill " + TenantSkillSource.GoQuote.quote(skillName));
        }
        Map<String, String> runtimeEnv = new LinkedHashMap<>();
        if (env != null) {
            runtimeEnv.putAll(env);
        }
        SkillEnvResolver.applySkillNodePath(runtimeEnv, dir);
        runtimeEnv.put(SkillEnvResolver.SKILL_DIR_ENV_VAR, dir);
        runtimeEnv.put(SkillEnvResolver.ARTIFACT_OUTPUT_ENV_VAR, artifactOutputDir());
        runtimeEnv.put(SkillEnvResolver.ARTIFACT_HISTORY_ENV_VAR, artifactOutputDir());
        runtimeEnv.put(SkillEnvResolver.SESSION_INPUT_ENV_VAR, SandboxPaths.SESSION_INPUT_ROOT);
        // PATH 在 provider 的 login shell 加载完 profiles 之后再设。用子非登录 shell，
        // 让前导赋值与任意 shell 语法保持原义、吃不掉 setup 前缀
        String prefix = skillCommandPath(dir);
        String wrapped = "export PATH=" + SandboxPaths.shellQuote(prefix) + ":\"$PATH\"; exec /bin/bash --noprofile --norc -c "
                + SandboxPaths.shellQuote(command);
        return new PreparedShell(wrapped, runtimeEnv);
    }

    /** 对照 sandbox.SkillCommandPath：skill 的 venv/node_modules/.weknora bin 前缀。 */
    static String skillCommandPath(String dir) {
        return SandboxPaths.join(dir, ".venv", "bin") + ":"
                + SandboxPaths.join(dir, "node_modules", ".bin") + ":" + SandboxPaths.join(dir, ".weknora", "bin");
    }

    // ---- manager.go 常量与环境 ----

    /** skill 环境准备写入 sandbox 环境的每个名字（对照 InjectedSandboxEnvVars）。 */
    public static List<String> injectedSandboxEnvVars() {
        List<String> vars = new ArrayList<>();
        vars.add(SkillEnvResolver.ARTIFACT_OUTPUT_ENV_VAR);
        vars.add(SkillEnvResolver.SESSION_INPUT_ENV_VAR);
        vars.add(SkillEnvResolver.ARTIFACT_HISTORY_ENV_VAR);
        vars.add(SkillEnvResolver.SKILL_DIR_ENV_VAR);
        vars.add(SkillEnvResolver.PYTHON_PATH_ENV_VAR);
        vars.add(SkillEnvResolver.NODE_PATH_ENV_VAR);
        return vars;
    }

    /**
     * 本轮 skill 脚本应写产物的绝对路径（对照 ArtifactOutputDir）。解析顺序：
     * 宿主环境里的 WEKNORA_SKILL_OUTPUT_DIR（ops 覆盖，且必须在会话 workspace 内）
     * → /workspace/output。调用方把返回值当只读（无尾斜杠，可安全 join）。
     */
    public static String artifactOutputDir() {
        return artifactOutputDir(System.getenv(SkillEnvResolver.ARTIFACT_OUTPUT_ENV_VAR));
    }

    /** 注入环境值的解析核心（测试与部署面共用；Go 直接 os.Getenv）。 */
    static String artifactOutputDir(String envValue) {
        if (envValue != null && !envValue.strip().isEmpty()) {
            String clean = GoPath.clean(envValue.strip());
            if (clean.equals(SandboxPaths.SESSION_WORKSPACE_ROOT) || clean.startsWith(SandboxPaths.SESSION_WORKSPACE_ROOT + "/")) {
                return clean;
            }
        }
        return DEFAULT_ARTIFACT_OUTPUT_DIR;
    }

    // ---- tools.SkillEnvironment 桥接（4.5c 预留的接缝；4.6d 装配用）----

    /**
     * 供 4.6d 装配：把本 Manager 适配成 shell_exec 持有的
     * {@code com.ragagent.agent.tools.SkillEnvironment}——
     * {@code new ShellExecTool(...).withSkillEnvironment(skillsManager.asSkillEnvironment())}。
     * 适配器（而非直接实现）是因为 Go-parity 方法名 getAllMetadata/loadSkill 的
     * 返回类型与窄接口冲突，Java 无法按返回类型重载。
     */
    public com.ragagent.agent.tools.SkillEnvironment asSkillEnvironment() {
        return new com.ragagent.agent.tools.SkillEnvironment() {
            @Override
            public boolean isEnabled() {
                return Manager.this.isEnabled();
            }

            @Override
            public List<com.ragagent.agent.SkillMetadata> getAllMetadata() {
                List<Skill.SkillMetadata> all = Manager.this.getAllMetadata();
                if (all == null) {
                    return null;
                }
                List<com.ragagent.agent.SkillMetadata> out = new ArrayList<>(all.size());
                for (Skill.SkillMetadata meta : all) {
                    out.add(new com.ragagent.agent.SkillMetadata(meta.name(), meta.description(), meta.basePath()));
                }
                return out;
            }

            @Override
            public com.ragagent.agent.tools.SkillEnvironment.SkillDocument loadSkill(String skillName) throws Exception {
                Skill skill = Manager.this.loadSkill(skillName);
                return new com.ragagent.agent.tools.SkillEnvironment.SkillDocument(
                        skill.name, skill.description, skill.instructions);
            }

            @Override
            public com.ragagent.agent.tools.SkillEnvironment.SkillDir sandboxSkillDir(String skillName) {
                SandboxDir dir = Manager.this.sandboxSkillDir(skillName);
                return new com.ragagent.agent.tools.SkillEnvironment.SkillDir(dir.dir(), dir.installed());
            }

            @Override
            public List<String> listSkillFiles(String skillName) throws Exception {
                return Manager.this.listSkillFiles(skillName);
            }

            @Override
            public String readSkillFile(String skillName, String relativePath) throws Exception {
                return Manager.this.readSkillFile(skillName, relativePath);
            }

            @Override
            public com.ragagent.agent.tools.SkillEnvironment.PreparedShell prepareShellEnvironment(
                    String sessionId, String skillName, String command, Map<String, String> env) throws Exception {
                Manager.PreparedShell prepared = Manager.this.prepareShellEnvironment(sessionId, skillName, command, env);
                return new com.ragagent.agent.tools.SkillEnvironment.PreparedShell(prepared.command(), prepared.env());
            }
        };
    }
}
