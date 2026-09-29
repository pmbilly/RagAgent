package com.ragagent.agent.skills;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * skill 生命周期管理——**指令型（playbook）**。
 *
 * <p>2026-09 裁剪定稿（选项 B）：技能 = 提示词注入的指令文档。模型凭 SKILL.md 指令
 * 用现有工具执行；沙箱镜像源、staging、shell 环境注入随沙箱一起退役（执行型扩展
 * 需求引导走 MCP）。本类只剩三级注入面：元数据目录（Level 1）、完整指令（Level 2）、
 * 附属文件（Level 3），数据源一律是 {@link Loader} 扫描的宿主 skillDirs。</p>
 */
public final class Manager {

    private final Loader loader;
    private final List<String> allowedSkills;
    private final boolean enabled;

    private final ReentrantReadWriteLock mu = new ReentrantReadWriteLock();
    private List<Skill.SkillMetadata> metadataCache = new ArrayList<>();

    /** Manager 的配置。 */
    public record ManagerConfig(List<String> skillDirs, List<String> allowedSkills, boolean enabled) {
    }

    public Manager(ManagerConfig config) {
        ManagerConfig cfg = config != null ? config : new ManagerConfig(List.of(), List.of(), false);
        this.loader = new Loader(cfg.skillDirs());
        this.allowedSkills = cfg.allowedSkills() == null ? List.of() : cfg.allowedSkills();
        this.enabled = cfg.enabled();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 发现全部 skills 并缓存元数据；启动时调用。 */
    public void initialize() throws Exception {
        if (!enabled) {
            return;
        }
        List<Skill.SkillMetadata> metadata = loader.reload();
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

    /** 全部已发现 skill 的元数据；系统提示词注入用（Level 1）。 */
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

    /** 加载一个 skill 的完整指令（Level 2）。 */
    public Skill loadSkill(String skillName) throws Exception {
        requireUsable(skillName);
        return loader.loadSkillInstructions(skillName);
    }

    /** 读 skill 目录里的一个额外文件（Level 3）。 */
    public String readSkillFile(String skillName, String filePath) throws Exception {
        requireUsable(skillName);
        return loader.loadSkillFile(skillName, filePath).content();
    }

    /** 列出 skill 目录的全部文件。 */
    public List<String> listSkillFiles(String skillName) throws Exception {
        requireUsable(skillName);
        return loader.listSkillFiles(skillName);
    }

    private void requireUsable(String skillName) {
        if (!enabled) {
            throw new Skill.SkillValidationException("skills are not enabled");
        }
        if (!isSkillAllowed(skillName)) {
            throw new Skill.SkillValidationException("skill not allowed: " + skillName);
        }
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

    /** 详细信息。 */
    public record SkillInfo(String name, String description, String basePath, String instructions, List<String> files) {
    }

    public SkillInfo getSkillInfo(String skillName) throws Exception {
        requireUsable(skillName);
        Skill skill = loader.loadSkillInstructions(skillName);
        List<String> files;
        try {
            files = loader.listSkillFiles(skillName);
        } catch (Exception e) {
            files = List.of(); // 非致命错误
        }
        return new SkillInfo(skill.name, skill.description, skill.basePath, skill.instructions, files);
    }

    /** 刷新 skill 缓存。 */
    public void reload() throws Exception {
        if (!enabled) {
            return;
        }
        List<Skill.SkillMetadata> metadata = loader.reload();
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
}
