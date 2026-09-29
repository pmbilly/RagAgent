package com.ragagent.knowledge.chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Markdown 标题层级栈。
 * <p>维护按层级（1..6）索引的活动标题栈；压入 N 级标题会弹出所有 ≥N 的条目
 * 概念相似，但 Markdown 标题没有显式结束，故建模为显式层级栈。</p>
 */
public final class HeadingHierarchy {

    /** stack[i] 保存 i+1 级标题文本（stack[0] = H1），最深活动层级以外的条目为空串。 */
    private final String[] stack = new String[6];
    private int depth;

    public HeadingHierarchy() {
    }

    /** 复制构造。 */
    public HeadingHierarchy(HeadingHierarchy other) {
        System.arraycopy(other.stack, 0, this.stack, 0, 6);
        this.depth = other.depth;
    }

    public HeadingHierarchy copy() {
        return new HeadingHierarchy(this);
    }

    /**
     * 解析一行并在其为 Markdown 标题时更新层级。
     * @return 标题层级（非标题返回 0）；标题文本经 {@link StringBuilder} 返回不方便，用 {@link #observe} + 需要时自行匹配。
     */
    public int observe(String line) {
        Matcher m = ChunkPatterns.MARKDOWN_HEADING.matcher(line);
        if (!m.matches()) {
            return 0;
        }
        int level = m.group(1).length();
        if (level < 1 || level > 6) {
            return 0;
        }
        String heading = m.group(2).strip();
        // 替换本级并清空更深层级
        stack[level - 1] = heading;
        for (int i = level; i < 6; i++) {
            stack[i] = "";
        }
        if (level > depth) {
            depth = level;
        } else {
            // 压入了更浅标题，depth 可能收缩
            depth = 0;
            for (int i = 0; i < 6; i++) {
                if (!stack[i].isEmpty()) {
                    depth = i + 1;
                }
            }
        }
        return level;
    }

    public String breadcrumb() {
        if (depth == 0) {
            return "";
        }
        List<String> parts = new ArrayList<>(depth);
        for (int i = 0; i < depth; i++) {
            if (!stack[i].isEmpty()) {
                parts.add(stack[i]);
            }
        }
        return String.join(" > ", parts);
    }

    public String breadcrumbWithHashes() {
        if (depth == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (stack[i].isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("#".repeat(i + 1));
            sb.append(' ');
            sb.append(stack[i]);
        }
        return sb.toString();
    }

    public int depth() {
        return depth;
    }

    public void reset() {
        for (int i = 0; i < 6; i++) {
            stack[i] = "";
        }
        depth = 0;
    }
}
