package com.ragagent.agent.tools;

import com.ragagent.agent.domain.ToolResult;

/**
 * write_skill_file / edit_skill_file 的共享辅助（对照 Go skill_file.go 的包级函数）：
 * skill 目录作用域校验与 data map 骨架。
 */
final class SkillFiles {

    private SkillFiles() {
    }

    /**
     * 模型给的路径 → 证明落在 skillDir 内的绝对路径（对照 resolveSkillFilePath）。
     * 相对路径以 skillDir 解析；clean 后重新按前缀复检——".."、类 symlink 拼写或指向
     * 邻居 skill 的绝对路径都在这里失败而不是到达镜像。目录本身被拒：它不是文件。
     */
    static String resolveSkillFilePath(String skillDir, String requested) {
        String dir = GoPath.clean(skillDir == null ? "" : skillDir.strip());
        if (dir.isEmpty() || dir.equals(".") || dir.equals("/")) {
            throw new IllegalArgumentException("this tool is not bound to a skill directory");
        }
        String trimmed = requested == null ? "" : requested.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("path is required; write a file inside " + dir);
        }
        if (trimmed.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "path " + ListSandboxFilesTool.quoteGo(requested) + " is not a valid file path");
        }
        String candidate = trimmed;
        if (!candidate.startsWith("/")) {
            candidate = SandboxPaths.join(dir, candidate);
        }
        String clean = GoPath.clean(candidate);
        if (clean.equals(dir) || !clean.startsWith(dir + "/")) {
            throw new IllegalArgumentException(
                    "path " + ListSandboxFilesTool.quoteGo(requested)
                            + " is outside this install's skill directory (" + dir + "); "
                            + "an install may only write its own skill");
        }
        return clean;
    }

    /** data map 骨架（display_type/session_id/path/root/name/size，Go 声明序）。 */
    static java.util.Map<String, Object> baseData(String displayType, String sessionId, String clean,
            String skillDir, int size) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("display_type", displayType);
        data.put("session_id", sessionId);
        data.put("path", clean);
        data.put("root", skillDir);
        data.put("name", SandboxPaths.base(clean));
        data.put("size", size);
        return data;
    }

    static ToolResult failure(String error) {
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(error);
        return r;
    }
}
