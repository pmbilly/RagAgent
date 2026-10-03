package com.ragagent.agent.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.ragagent.common.text.PosixPath;

/**
 * 从文件系统做 skill 发现与加载。
 * 按 Progressive Disclosure 把元数据发现（Level 1）与指令加载（Level 2/3）分开。
 */
public final class Loader implements SkillSource {

    /** 搜索的目录集。 */
    private final List<String> skillDirs;
    /** 已发现 skill 元数据的缓存。 */
    private final Map<String, Skill> discoveredSkills = new ConcurrentHashMap<>();

    public Loader(List<String> skillDirs) {
        this.skillDirs = skillDirs == null ? List.of() : skillDirs;
    }

    /** 扫描全部配置目录找 SKILL.md，抽元数据（Level 1）。 */
    @Override
    public List<Skill.SkillMetadata> discoverSkills() {
        List<Skill.SkillMetadata> allMetadata = new ArrayList<>();
        for (String dir : skillDirs) {
            List<Skill.SkillMetadata> metadata;
            try {
                metadata = discoverInDirectory(dir);
            } catch (Exception e) {
                // 记 warning 后继续其他目录
                continue;
            }
            allMetadata.addAll(metadata);
        }
        return allMetadata;
    }

    /** 扫单个目录下的 skill 子目录。 */
    private List<Skill.SkillMetadata> discoverInDirectory(String dir) throws IOException {
        List<Skill.SkillMetadata> metadata = new ArrayList<>();
        Path root = Path.of(dir);
        if (!Files.exists(root)) {
            return metadata; // 目录不存在，静默跳过
        }
        if (!Files.isDirectory(root)) {
            throw new IOException(dir + " is not a directory");
        }
        // 按文件名字节序排序，保证发现顺序稳定
        List<Path> entries;
        try (Stream<Path> stream = Files.list(root)) {
            entries = new ArrayList<>(stream.toList());
        }
        entries.sort(Path::compareTo);
        for (Path entry : entries) {
            if (!Files.isDirectory(entry)) {
                continue;
            }
            Path skillPath = entry;
            Path skillFile = skillPath.resolve(Skill.SKILL_FILE_NAME);
            if (!Files.exists(skillFile)) {
                continue;
            }
            Skill skill;
            try {
                skill = Skill.parseSkillFile(Files.readString(skillFile));
            } catch (RuntimeException e) {
                continue;
            }
            skill.basePath = skillPath.toString();
            skill.filePath = skillFile.toString();
            discoveredSkills.put(skill.name, skill);
            metadata.add(skill.toMetadata());
        }
        return metadata;
    }

    /** 加载一个 skill 的完整指令（Level 2）。已加载走缓存。 */
    @Override
    public Skill loadSkillInstructions(String skillName) {
        Skill cached = discoveredSkills.get(skillName);
        if (cached != null && cached.loaded) {
            return cached;
        }
        for (String dir : skillDirs) {
            Skill skill = loadSkillFromDirectory(dir, skillName);
            if (skill != null) {
                discoveredSkills.put(skillName, skill);
                return skill;
            }
        }
        throw new Skill.SkillValidationException("skill not found: " + skillName);
    }

    /** 在指定目录尝试加载 skill；找不到返回 null。 */
    private Skill loadSkillFromDirectory(String dir, String skillName) {
        // 先按目录名 = skill 名直查
        Path directPath = Path.of(dir, skillName);
        Path directFile = directPath.resolve(Skill.SKILL_FILE_NAME);
        if (Files.exists(directFile)) {
            return loadAndParseSkillFile(directPath.toString(), directFile.toString());
        }
        // 否则扫描全部子目录按 name 找
        Path root = Path.of(dir);
        List<Path> entries;
        try (Stream<Path> stream = Files.list(root)) {
            entries = new ArrayList<>(stream.toList());
        } catch (IOException e) {
            return null;
        }
        entries.sort(Path::compareTo);
        for (Path entry : entries) {
            if (!Files.isDirectory(entry)) {
                continue;
            }
            Path skillPath = entry;
            Path skillFile = skillPath.resolve(Skill.SKILL_FILE_NAME);
            if (!Files.exists(skillFile)) {
                continue;
            }
            Skill skill;
            try {
                skill = Skill.parseSkillFile(Files.readString(skillFile));
            } catch (Exception e) {
                continue;
            }
            if (skill.name.equals(skillName)) {
                skill.basePath = skillPath.toString();
                skill.filePath = skillFile.toString();
                return skill;
            }
        }
        return null;
    }

    private Skill loadAndParseSkillFile(String basePath, String filePath) {
        String content;
        try {
            content = Files.readString(Path.of(filePath));
        } catch (IOException e) {
            throw new Skill.SkillValidationException("failed to read skill file: " + e.getMessage());
        }
        Skill skill = Skill.parseSkillFile(content);
        skill.basePath = basePath;
        skill.filePath = filePath;
        return skill;
    }

    /** 加载 skill 目录里的一个额外文件（Level 3）。 */
    @Override
    public Skill.SkillFile loadSkillFile(String skillName, String relativePath) {
        Skill skill = discoveredSkills.get(skillName);
        if (skill == null) {
            skill = loadSkillInstructions(skillName);
        }
        String cleanPath = PosixPath.clean(relativePath);
        // 安全：防路径穿越
        if (cleanPath.startsWith("..") || cleanPath.startsWith("/")) {
            throw new Skill.SkillValidationException("invalid file path: " + relativePath);
        }
        Path fullPath = Path.of(skill.basePath, cleanPath);
        Path absSkillPath = Path.of(skill.basePath).toAbsolutePath().normalize();
        Path absFilePath = fullPath.toAbsolutePath().normalize();
        // 资源读取器不得顺着 bundle 符号链接摸到宿主文件。os.Root 在 open 期强制
        // 包含（含 symlink race）；Java 用 real path 包含校验近似（已知差异，备案）
        try {
            absFilePath = absFilePath.toRealPath();
            absSkillPath = absSkillPath.toRealPath();
        } catch (IOException e) {
            // real path 解析失败时退回 normalize 形态
        }
        if (!absFilePath.startsWith(absSkillPath)) {
            throw new Skill.SkillValidationException("file path outside skill directory: " + relativePath);
        }
        byte[] content;
        try {
            content = Files.readAllBytes(absFilePath);
        } catch (IOException e) {
            throw new Skill.SkillValidationException("failed to read file: " + e.getMessage());
        }
        return new Skill.SkillFile(relativePath, absFilePath.toString(), new String(content), Skill.isScript(relativePath));
    }

    /** 列出 skill 目录的全部文件。 */
    @Override
    public List<String> listSkillFiles(String skillName) {
        Skill skill = discoveredSkills.get(skillName);
        if (skill == null) {
            skill = loadSkillInstructions(skillName);
        }
        List<String> files = new ArrayList<>();
        walk(Path.of(skill.basePath), Path.of(skill.basePath), files);
        return files;
    }

    /** 目录内按名排序、深度优先的遍历顺序。 */
    private static void walk(Path root, Path dir, List<String> files) {
        List<Path> entries;
        try (Stream<Path> stream = Files.list(dir)) {
            entries = new ArrayList<>(stream.toList());
        } catch (IOException e) {
            return;
        }
        entries.sort(Path::compareTo);
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                walk(root, entry, files);
                continue;
            }
            files.add(root.relativize(entry).toString());
        }
    }

    /** 按名取缓存 skill。 */
    public Skill getSkillByName(String name) {
        return discoveredSkills.get(name);
    }

    /** skill 的基路径（恒绝对）。 */
    @Override
    public String getSkillBasePath(String skillName) {
        Skill skill = discoveredSkills.get(skillName);
        if (skill == null) {
            skill = loadSkillInstructions(skillName);
        }
        return Path.of(skill.basePath).toAbsolutePath().normalize().toString();
    }

    /** 清缓存并重新发现。 */
    public List<Skill.SkillMetadata> reload() {
        discoveredSkills.clear();
        return discoverSkills();
    }
}
