package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * skill 资源树渲染（对照 Go {@code skill_resources.go}，逐字移植）。
 * read_file 的 skill 资源分支用它列 bundled 文件。
 */
public final class SkillResources {

    /** SKILL.md 文件名（对照 skills.SkillFileName）。 */
    public static final String SKILL_FILE_NAME = "SKILL.md";

    /**
     * 安装/缓存目录树：会遍历 skill 根，但不是模型该打开的东西。按扁平 bullet 列出
     * （甚至按树列出）会把上千条路径灌进上下文。
     */
    private static final Set<String> SKILL_TREE_SKIP_DIRS = Set.of(
            ".venv", "node_modules", "__pycache__", ".git");

    private SkillResources() {
    }

    /**
     * 把 skill 文件渲染成缩进树，每个目录名只付一次成本（对照 formatSkillFileTree）。
     * 刻意不用 box-drawing 的 tree 字符：费 token，且不是 file_path 的一部分。
     */
    public static String formatSkillFileTree(List<String> files) {
        Node root = new Node();
        if (files != null) {
            for (String raw : files) {
                String rel = raw == null ? "" : raw.replace("\\", "/");
                rel = trimSlashes(rel);
                if (rel.isEmpty() || rel.equals(SKILL_FILE_NAME)) {
                    continue;
                }
                String[] parts = rel.split("/", -1);
                boolean skip = false;
                for (String part : parts) {
                    if (SKILL_TREE_SKIP_DIRS.contains(part)) {
                        skip = true;
                        break;
                    }
                }
                if (skip) {
                    continue;
                }
                Node n = root;
                for (String part : parts) {
                    Node child = n.children.get(part);
                    if (child == null) {
                        child = new Node();
                        n.children.put(part, child);
                    }
                    n = child;
                }
            }
        }
        if (root.children.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        writeSkillFileTree(b, root, "");
        return b.toString();
    }

    private static final class Node {
        final Map<String, Node> children = new TreeMap<>();
    }

    private static String trimSlashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '/') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(start, end);
    }

    private static void writeSkillFileTree(StringBuilder b, Node n, String indent) {
        List<String> names = new ArrayList<>(n.children.keySet());
        names.sort((i, j) -> {
            Node left = n.children.get(i);
            Node right = n.children.get(j);
            boolean leftDir = !left.children.isEmpty();
            boolean rightDir = !right.children.isEmpty();
            if (leftDir != rightDir) {
                return leftDir ? -1 : 1;
            }
            return i.compareTo(j);
        });
        for (String name : names) {
            Node child = n.children.get(name);
            b.append(indent);
            b.append(name);
            if (!child.children.isEmpty()) {
                b.append('/');
                b.append('\n');
                writeSkillFileTree(b, child, indent + "  ");
                continue;
            }
            b.append('\n');
        }
    }
}
