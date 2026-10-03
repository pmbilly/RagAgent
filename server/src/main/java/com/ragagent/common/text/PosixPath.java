package com.ragagent.common.text;

/**
 * POSIX 斜杠语义的路径清洗（纯字符串实现）。
 *
 * <p><b>为何不能用 {@link java.nio.file.Path}</b>：那是平台相关的（Windows 分隔符为
 * {@code \}）；本类服务于 sandbox 路径处理（输出目录前缀、会话工作目录校验）——
 * 一律按 POSIX {@code /} 语义。</p>
 *
 * <p><b>2026-10-03（B47）</b>：自 {@code agent.tools.GoPath} 改名归位（原先名字暗示
 * "Go 复刻"，实为 POSIX 语义契约；B46 已裁决保留行为）。</p>
 */
public final class PosixPath {

    private PosixPath() {
    }

    /** 消除 .、..、多余斜杠；结果化简为最短路径名。 */
    public static String clean(String path) {
        if (path.isEmpty()) {
            return ".";
        }
        boolean rooted = path.charAt(0) == '/';
        int n = path.length();
        StringBuilder out = new StringBuilder(n);
        int r = 0;
        int dotdot = 0;
        if (rooted) {
            out.append('/');
            r = 1;
            dotdot = 1;
        }
        while (r < n) {
            if (path.charAt(r) == '/') {
                // 空路径元素
                r++;
            } else if (path.charAt(r) == '.' && (r + 1 == n || path.charAt(r + 1) == '/')) {
                // . 元素
                r++;
            } else if (path.charAt(r) == '.' && path.charAt(r + 1) == '.'
                    && (r + 2 == n || path.charAt(r + 2) == '/')) {
                // .. 元素：向上退
                r += 2;
                if (out.length() > dotdot) {
                    // 可以退
                    int len = out.length() - 1;
                    while (len > dotdot && out.charAt(len) != '/') {
                        len--;
                    }
                    out.setLength(len);
                } else if (!rooted) {
                    // 不能退
                    if (out.length() > 0) {
                        out.append('/');
                    }
                    out.append("..");
                    dotdot = out.length();
                }
            } else {
                // 真实路径元素
                if ((rooted && out.length() != 1) || (!rooted && out.length() != 0)) {
                    out.append('/');
                }
                while (r < n && path.charAt(r) != '/') {
                    out.append(path.charAt(r));
                    r++;
                }
            }
        }
        if (out.length() == 0) {
            return ".";
        }
        return out.toString();
    }
}
