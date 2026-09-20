package com.ragagent.agent.tools;

/**
 * write_skill_file / edit_skill_file 的写面（对照 Go {@code SkillFileStore}，
 * internal/agent/tools/skill_file.go:38-42）。生产用 *sandbox.SessionBoundManager 的
 * WriteSessionFile——它本身就拒绝 skills image root 之外的路径，下面的 per-skill
 * 作用域再收窄到本次安装拥有的那一个目录。
 */
public interface SkillFileStore {

    RemoteStatEntry statSessionFile(String sessionId, String filePath) throws Exception;

    byte[] readSessionFile(String sessionId, String filePath) throws Exception;

    /** 写 skills image root 内的文件（对照 WriteSessionFile——注意与 workspace 写不同名）。 */
    void writeSessionFile(String sessionId, String filePath, byte[] content) throws Exception;
}
