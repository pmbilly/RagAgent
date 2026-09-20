package com.ragagent.agent.tools;

/**
 * Go {@code path.Clean} 的等价实现（纯字符串、POSIX 斜杠语义；不能用 java.nio.file.Path——
 * 那是平台相关的）。对照 golang.org 源码逐分支移植，sandbox 路径处理（输出目录前缀、
 * 会话工作目录校验）都依赖它的归一化行为。
 */
public final class GoPath {

    private GoPath() {
    }

    /** 等价 path.Clean：消除 .、..、多余斜杠；结果化简为最短路径名。 */
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
