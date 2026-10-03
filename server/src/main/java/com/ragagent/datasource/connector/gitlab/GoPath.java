package com.ragagent.datasource.connector.gitlab;

/**
 * 按 Go 标准库 {@code path.Clean} / {@code path.Join} 语义实现的路径工具。
 *
 * <h2>为什么不能用 {@code java.nio.file.Path.normalize()}</h2>
 * <p>两者在<b>很多</b>输入上给出不同答案，而 {@code normalizePath} 的接受/拒绝判定
 * 直接建立在 {@code path.Clean(v) != v} 上——用错实现会<b>放行或拒绝完全不同的路径集合</b>：</p>
 * <pre>
 *   {@code path.Clean} 的行为（Go 语义，纯字符串、永远是正斜杠）：
 *     "docs//guide" → "docs/guide"    （折叠重复斜杠）
 *     "docs/./a"    → "docs/a"        （吃掉 .）
 *     "docs/"       → "docs"          （去尾斜杠）
 *     ""            → "."             （空串变点！）
 *     "/a/../b"     → "/b"
 *     ".."          → ".."            （无根时 .. 无法回退，原样保留）
 *     "a/.."        → "."
 *     "\\a\\b"       → "\\a\\b"        （<b>不是</b>分隔符，原样）
 *   {@code Path.normalize()} 的行为（JDK，平台相关）：
 *     "docs//guide" → "docs/guide"    （这一条相同）
 *     ""            → ""              （<b>不同</b>：Go 是 "."）
 *     ".."          → ".."            （相同）
 *     "a/.."        → ""              （<b>不同</b>：Go 是 "."）
 *     "\\a\\b"       → "a\\b"           （macOS/Linux 下被当成单个文件名，但 "" 的处置仍不同）
 * </pre>
 * <p>差异集中在"空结果"这一支：Go 永远回落到 {@code "."}，JDK 回落成空串。
 * 而 {@code normalizePath} 恰好有一条 {@code v == "."} 的显式拒绝，
 * 与 {@code Clean(v) != v} 并列——正是为了接住这个"Clean 之后才是点"的形态。</p>
 *
 * <h2>字符处理与字节处理等价</h2>
 * <p>{@code Clean} 只对 {@code /} 与 {@code .} 做判断，这两个都是 ASCII，
 * 而 UTF-8 的续字节恒 &ge; 0x80，不会伪装成它们。所以按
 * {@code char} 遍历与按字节遍历结果一致（{@code gitlabFilePathEscape} 则<b>必须</b>
 * 按字节，因为它的规则是"非 a-zA-Z0-9-_ 的每个字节都转义"）。</p>
 *
 * <p><b>内部工具，不是契约</b>：服务的是配置解析与 KB 相对路径生成，不落 jsonb、不进响应体。</p>
 */
final class GoPath {

    private GoPath() {
    }

    /** 路径清洗（含 {@code "" → "."} 与 {@code "a/.." → "."}）。 */
    static String clean(String path) {
        if (path == null || path.isEmpty()) {
            return ".";
        }
        boolean rooted = path.charAt(0) == '/';
        int n = path.length();

        LazyBuf out = new LazyBuf(path);
        int r = 0;
        int dotdot = 0;
        if (rooted) {
            out.append('/');
            r = 1;
            dotdot = 1;
        }

        while (r < n) {
            char c = path.charAt(r);
            if (c == '/') {
                // 空路径元素
                r++;
            } else if (c == '.' && (r + 1 == n || path.charAt(r + 1) == '/')) {
                // "." 元素
                r++;
            } else if (c == '.' && r + 1 < n && path.charAt(r + 1) == '.'
                    && (r + 2 == n || path.charAt(r + 2) == '/')) {
                // ".." 元素：回退到上一个 '/'
                r += 2;
                if (out.length() > dotdot) {
                    // ⚠️ index(w) 读的是<b>逻辑游标处</b>的字符（该字符此刻
                    // 已不在输出里），不是"当前输出的最后一个字符"。写成
                    // `charAt(length-1)` 会少退一格——见 LazyBuf 的说明。
                    out.unwrite();
                    while (out.length() > dotdot && out.index(out.length()) != '/') {
                        out.unwrite();
                    }
                } else if (!rooted) {
                    // 无法回退且无根，就把 ".." 原样留下
                    if (out.length() > 0) {
                        out.append('/');
                    }
                    out.append('.');
                    out.append('.');
                    dotdot = out.length();
                }
            } else {
                // 普通路径元素：需要时补分隔符
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
        return out.string();
    }

    /**
     * 内部缓冲——<b>不是一个普通的 StringBuilder</b>，
     * 它有一个"逻辑游标" {@code w}，而 {@code index(i)} 可以读到 {@code w} 之后的
     * 陈旧内容（未物化时更是直接读源串）。
     *
     * <p>这个区别是真实缺陷级的：{@code Clean("docs-main/a/../b.md")} 在
     * {@code ".."} 回退时，先 {@code w--}（11→10）再判 {@code index(10)}——读的是
     * <b>刚被排除掉的那个 'a'</b>（{'!='}{@code '/'}）于是继续退到 {@code w=9}（停在
     * {@code '/'} 上）。若按"看当前输出最后一个字符"来写，{@code w=10} 时最后一个字符
     * 已经是 {@code '/'}，循环立刻停下，结果多留一个 {@code '/'}：
     * {@code "docs-main//b.md"}。这个 bug 被
     * {@code GitLabConfigTest.knowledgeRelativePath…} 抓到。</p>
     *
     * <p>另一处语义是 {@link #append}：未物化时若 {@code s[w] == c} 就只推进游标、
     * 不复制——所以"输出"在前缀与源串相同的部分是<b>借用</b>源串的，
     * 这也是为什么 {@code index(w)} 能读到尚未被覆盖的内容。</p>
     */
    private static final class LazyBuf {

        private final String source;
        private char[] buf;
        private int w;

        LazyBuf(String source) {
            this.source = source;
        }

        /**
         * 追加语义：<b>物化后是"改写"而不是"追加"</b>——
         * 写入位置是逻辑游标 {@code w}，不是缓冲末尾（缓冲长度恒为 {@code len(s)}）。
         * 用 {@code StringBuilder.append} 会让游标之后的陈旧内容留在前面，
         * {@code "a//b//../c"} 这种输入就会拼出 {@code "a/b"}（正确结果是 {@code "a/c"}）。
         */
        void append(char c) {
            if (buf == null) {
                if (w < source.length() && source.charAt(w) == c) {
                    w++;
                    return;
                }
                // 物化：整串拷一份（标准库只拷前 w 个字节，其余留零；
                // 但 index() 只在 i < w 时被调，两种做法在可达路径上等价）
                buf = source.toCharArray();
            }
            buf[w] = c;
            w++;
        }

        /** 只挪逻辑游标，不真的删字符。 */
        void unwrite() {
            w--;
        }

        /** 物化前读源串，物化后读缓冲（可越过游标）。 */
        char index(int i) {
            return buf == null ? source.charAt(i) : buf[i];
        }

        int length() {
            return w;
        }

        /** 只取游标之前的部分。 */
        String string() {
            return buf == null ? source.substring(0, w) : new String(buf, 0, w);
        }
    }

    /**
     * 用 {@code /} 连接后 {@link #clean}。
     *
     * <p>空元素<b>不</b>被丢弃（只在已有内容时补分隔符，然后照样 append），
     * 但这不影响结果——{@code Clean} 会把多出来的斜杠折叠掉。
     * {@code knowledgeRelativePath} 依赖这个性质：{@code projectName} 或
     * {@code ref} 为空串时根名会退化成 {@code "-"} / {@code "docs-"}。</p>
     */
    static String join(String... elem) {
        int size = 0;
        for (String e : elem) {
            size += e == null ? 0 : e.length();
        }
        if (size == 0) {
            // 全部元素为空串时返回 ""（不是 clean 出来的 "."）
            return "";
        }
        StringBuilder b = new StringBuilder(size + elem.length);
        for (String e : elem) {
            if (b.length() > 0 || (e != null && !e.isEmpty())) {
                if (b.length() > 0) {
                    b.append('/');
                }
                if (e != null) {
                    b.append(e);
                }
            }
        }
        return clean(b.toString());
    }
}
