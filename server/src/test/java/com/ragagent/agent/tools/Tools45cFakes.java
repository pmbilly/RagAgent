package com.ragagent.agent.tools;

import java.time.Instant;

import com.ragagent.agent.SkillMetadata;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 4.5c 测试的内存 fake（对照 Go 探针里复用 /tools 测试文件的
 * fakeSandboxFileSource / fakeShellExecutor 等的形状）。
 */
final class Tools45cFakes {

    private Tools45cFakes() {
    }

    /** 通用失败异常（fake 抛出，等价 Go 的 error 通道）。 */
    static RuntimeException boom(String msg) {
        return new RuntimeException(msg);
    }

    /** 任意对象 → Go json.Marshal 字节形态（对 Map<String,String> 等的便捷入口）。 */
    static String goJson(Object o) {
        return com.ragagent.agent.tools.GoJsonCodec.write(RecordingSupport.PLAIN.valueToTree(o));
    }

    /** 按 group+id 取 Go 实录常量（R_<GROUP>_<ID>，id 大写化）。 */
    static com.fasterxml.jackson.databind.JsonNode rec45c(String group, String id) {
        String name = "R_" + group.toUpperCase().replace('-', '_')
                + "_" + id.toUpperCase().replace('-', '_');
        try {
            String json = (String) GoRecording45C.class.getField(name).get(null);
            return GoRecording45C.rec(json);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("unknown recording constant: " + name, e);
        }
    }

    /** 可变的 sandbox 文件源/汇（同时满足 Source/Sink/Editor 三种形状）。 */
    static final class MemStore implements SandboxFileSource, SandboxFileSink, SandboxFileEditor, SkillFileStore {
        RemoteStatEntry stat;
        RuntimeException statErr;
        byte[] data = new byte[0];
        RuntimeException readErr;
        RuntimeException writeErr;
        final Map<String, byte[]> written = new LinkedHashMap<>();
        int reads;
        List<RemoteDirEntry> entries = new ArrayList<>();
        RuntimeException listErr;

        @Override
        public List<RemoteDirEntry> listSessionFiles(String sessionId, String dir) {
            if (listErr != null) {
                throw listErr;
            }
            return entries;
        }

        @Override
        public RemoteStatEntry statSessionFile(String sessionId, String path) {
            if (statErr != null) {
                throw statErr;
            }
            if (stat == null) {
                return null;
            }
            return new RemoteStatEntry(path, stat.type(), stat.size(), stat.modTime());
        }

        @Override
        public byte[] readSessionFile(String sessionId, String path) {
            reads++;
            if (readErr != null) {
                throw readErr;
            }
            return data;
        }

        @Override
        public void writeSessionWorkspaceFile(String sessionId, String path, byte[] content) {
            if (writeErr != null) {
                throw writeErr;
            }
            written.put(path, content);
        }

        @Override
        public void writeSessionFile(String sessionId, String path, byte[] content) {
            writeSessionWorkspaceFile(sessionId, path, content);
        }
    }

    /** 记录型 shell 执行器（对照 fakeShellExecutor；实现 4.5a 的 SessionFileLister 快照切片）。 */
    static final class RecExecutor implements SandboxCommandExecutor, OutputLinks.SessionFileLister {
        SandboxExecuteResult result;
        RuntimeException err;
        int calls;
        String command = "";
        String workDir = "";
        java.time.Duration timeout = java.time.Duration.ZERO;
        Map<String, String> env;
        CommandOutputListener output;
        List<OutputLinks.DirEntry> listed = new ArrayList<>();
        RuntimeException listErr;

        @Override
        public SandboxExecuteResult execShellCommand(String sessionId, String command, String workDir,
                java.time.Duration timeout, Map<String, String> env, CommandOutputListener output) {
            this.command = command;
            this.workDir = workDir;
            this.timeout = timeout;
            this.env = env;
            this.output = output;
            calls++;
            if (err != null) {
                throw err;
            }
            if (result == null) {
                return new SandboxExecuteResult("", "", 0, java.time.Duration.ZERO, false, "");
            }
            return result;
        }

        @Override
        public List<OutputLinks.DirEntry> listSessionFiles(String sessionId, String dir) {
            if (listErr != null) {
                throw listErr;
            }
            return listed;
        }
    }

    /** skill 环境的内存 fake（镜像 Go 探针里真 skills.Manager 的观测行为）。 */
    static final class MemSkills implements SkillEnvironment {
        boolean enabled = true;
        List<SkillMetadata> metadata = new ArrayList<>();
        Map<String, SkillDocument> documents = new LinkedHashMap<>();
        Map<String, List<String>> files = new LinkedHashMap<>();
        Map<String, String> fileContents = new LinkedHashMap<>();
        Map<String, SkillDir> dirs = new LinkedHashMap<>();
        Map<String, PreparedShell> prepared = new LinkedHashMap<>();
        RuntimeException prepareErr;
        List<String> preparedCalls = new ArrayList<>();

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public List<SkillMetadata> getAllMetadata() {
            return metadata;
        }

        @Override
        public SkillDocument loadSkill(String skillName) {
            SkillDocument doc = documents.get(skillName);
            if (doc == null) {
                throw boom("skill not found: " + skillName);
            }
            return doc;
        }

        @Override
        public SkillDir sandboxSkillDir(String skillName) {
            return dirs.getOrDefault(skillName, new SkillDir("", false));
        }

        @Override
        public List<String> listSkillFiles(String skillName) {
            List<String> f = files.get(skillName);
            if (f == null) {
                throw boom("skill not found: " + skillName);
            }
            return f;
        }

        @Override
        public String readSkillFile(String skillName, String relativePath) {
            String content = fileContents.get(skillName + "/" + relativePath);
            if (content == null) {
                throw boom("open " + relativePath + ": file does not exist");
            }
            return content;
        }

        @Override
        public PreparedShell prepareShellEnvironment(String sessionId, String skillName, String command,
                Map<String, String> env) {
            if (prepareErr != null) {
                throw prepareErr;
            }
            // 对照 Go Manager：不在 allowlist 里的名字一律拒绝（哪怕 resolver 放行过）
            boolean allowed = metadata.stream().anyMatch(m -> m.name().equals(skillName));
            if (!enabled || !allowed) {
                throw boom("skill \"" + skillName + "\" is not available to this agent");
            }
            preparedCalls.add(skillName);
            return prepared.getOrDefault(skillName, new PreparedShell(command, env));
        }

        static SkillMetadata meta(String name, String description) {
            return new SkillMetadata(name, description, "");
        }
    }

    /** skill env resolver 的内存 fake。 */
    static final class MemResolver implements SkillEnvironment.SkillEnvResolver {
        Map<String, String> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        RuntimeException err;

        @Override
        public SkillEnvironment.SkillEnvResolution resolveEnv(String skillName) {
            if (err != null) {
                throw err;
            }
            return new SkillEnvironment.SkillEnvResolution(resolved, missing);
        }
    }

    static RemoteDirEntry entry(String name, String path, String type, long size, Instant modTime) {
        return new RemoteDirEntry(name, path, type, size, modTime);
    }
}
