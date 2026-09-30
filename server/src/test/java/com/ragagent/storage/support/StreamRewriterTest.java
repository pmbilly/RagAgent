package com.ragagent.storage.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ragagent.storage.support.RewriterTest.FixedResolver;
import com.ragagent.storage.support.RewriterTest.StubFileService;

/**
 * {@link StreamRewriter} 的对等测试（对照 Go
 * {@code internal/storageurl/stream_test.go}）。
 *
 * <p>期望值全部照抄 Go 的表驱动用例——尤其是那几个"边界该落在哪个偏移"的用例，
 * 它们记录的是正则与扣留规则的真实行为，不是直觉。</p>
 */
class StreamRewriterTest {

    private static StreamRewriter stub(String url) {
        return new StreamRewriter(RewriterTest.stubRewriter(url));
    }

    // ── FindIncompleteRef ──

    @Test
    void findIncompleteRef() {
        // 完整的 URL 被 `)` 终止：正则 [^\s)\]>"]* 在 `)` 处停下，
        // 所以匹配没延伸到字符串末尾 → 不是"不完整"。
        assertThat(StreamRewriter.findIncompleteRef("![img](local://1/abc/img.png)")).isEqualTo(-1);
        // 被空格终止，同理
        assertThat(StreamRewriter.findIncompleteRef("text local://1/abc/img.png more text")).isEqualTo(-1);
        // 末尾截断：偏移落在 `l`（"text ![img](" 之后）
        assertThat(StreamRewriter.findIncompleteRef("text ![img](local://1/abc/im")).isEqualTo(12);
        assertThat(StreamRewriter.findIncompleteRef("text minio://")).isEqualTo(5);
        assertThat(StreamRewriter.findIncompleteRef("text storage://backend-a/co")).isEqualTo(5);
        assertThat(StreamRewriter.findIncompleteRef("just plain text http://example.com")).isEqualTo(-1);
        assertThat(StreamRewriter.findIncompleteRef("local://1/img.png")).isZero();
        assertThat(StreamRewriter.findIncompleteRef("see ![img](resource://xifDo7NTSL")).isEqualTo(11);
    }

    /**
     * Go 的 {@code $} 只匹配文本末尾，Java 的 {@code $} 还匹配"末尾换行符之前"。
     * 用错锚点的话，一个以换行结尾的普通分片会被 Java 整段扣住——而 Go 照常发出。
     * 这条用例专门钉住 {@code \z} 的改写。
     */
    @Test
    void trailingNewlineIsNotAnIncompleteReference() {
        assertThat(StreamRewriter.findIncompleteRef("text local://1/abc/img.png\n")).isEqualTo(-1);
        assertThat(StreamRewriter.holdbackCutoff("text local://1/abc/img.png\n"))
                .isEqualTo("text local://1/abc/img.png\n".length());
    }

    // ── FindIncompleteMarkdownImage ──

    @Test
    void findIncompleteMarkdownImage() {
        assertThat(StreamRewriter.findIncompleteMarkdownImage("![img](local://1/a.png)")).isEqualTo(-1);
        assertThat(StreamRewriter.findIncompleteMarkdownImage("![img](local://1/a.png) trailing")).isEqualTo(-1);
        assertThat(StreamRewriter.findIncompleteMarkdownImage(
                "![知识助理\"知识库\"管理视图界面](minio://wizard-test/10000/exports/c91cf852")).isZero();
        assertThat(StreamRewriter.findIncompleteMarkdownImage("text ![alt](")).isEqualTo(5);
        // 裸的 provider 后缀不带 Markdown 结构 → 不是图片问题
        assertThat(StreamRewriter.findIncompleteMarkdownImage("text minio://wizard-test/10000/exp")).isEqualTo(-1);
        assertThat(StreamRewriter.findIncompleteMarkdownImage(
                "![a](local://1/a.png) ![b](local://1/b.png)")).isEqualTo(-1);
        assertThat(StreamRewriter.findIncompleteMarkdownImage(
                "![a](local://1/a.png) ![b](minio://part")).isEqualTo(22);
        // alt 文本本身含 ']'：要靠"引用碎片 + 最近的 `![…](` 配对"才能切对
        assertThat(StreamRewriter.findIncompleteMarkdownImage("![a[b]](minio://wizard-test/10000/part")).isZero();
        // 目标里有空白 → 是散文不是链接
        assertThat(StreamRewriter.findIncompleteMarkdownImage("![alt](see the figure below")).isEqualTo(-1);
        // 目标太长 → 不像链接
        assertThat(StreamRewriter.findIncompleteMarkdownImage(
                "![alt](" + "x".repeat(StreamRewriter.MAX_INCOMPLETE_IMAGE_BYTES))).isEqualTo(-1);
    }

    // ── HoldbackCutoff ──

    @Test
    void holdbackCutoff() {
        assertThat(StreamRewriter.holdbackCutoff("plain text with complete ![img](local://1/img.png) content"))
                .isEqualTo("plain text with complete ![img](local://1/img.png) content".length());
        assertThat(StreamRewriter.holdbackCutoff("text ![img](local://1/abc/im")).isEqualTo(5);
        assertThat(StreamRewriter.holdbackCutoff("text local://1/abc/im")).isEqualTo(5);
        assertThat(StreamRewriter.holdbackCutoff("prefix ![alt](")).isEqualTo(7);
        assertThat(StreamRewriter.holdbackCutoff("")).isZero();
    }

    // ── Push / FlushAll ──

    /**
     * 横跨两个增量的引用必须被扣住、等完整了再重写，
     * **绝不能**以残缺碎片的形式发出去。
     */
    @Test
    void holdsSplitReference() {
        StreamRewriter sr = stub("https://cdn.example.com/x.png");

        String first = sr.push("answer-1", "here it is: ![img](resource://xifDo7", false, null);
        assertThat(first).as("the incomplete image must be held back").isEqualTo("here it is: ");

        String second = sr.push("answer-1", "NTSL300Lp1goVutw) done", false, null);
        assertThat(second).isEqualTo("![img](https://cdn.example.com/x.png) done");

        assertThat(sr.push("answer-1", "", true, null)).isEmpty();
        assertThat(sr.flushAll()).as("nothing should remain held").isNull();
    }

    /** 每条流独立成键，交错的事件不会互相弄坏对方的扣留缓冲。 */
    @Test
    void keysAreIndependent() {
        StreamRewriter sr = stub("https://cdn.example.com/x.png");

        assertThat(sr.push("a", "![x](resource://aaaa", false, null)).isEmpty();
        assertThat(sr.push("b", "plain b", false, null)).isEqualTo("plain b");
        assertThat(sr.push("a", "bbbbccccdddddd)", false, null))
                .isEqualTo("![x](https://cdn.example.com/x.png)");
    }

    /** 没有终止分片就结束的流不得悄悄丢掉尾巴。 */
    @Test
    void flushAllReleasesHeldTail() {
        StreamRewriter sr = stub("https://cdn.example.com/x.png");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("event_id", "answer-1");
        meta.put("is_fallback", true);

        assertThat(sr.push("answer-1", "tail ![img](resource://partial", false, meta))
                .isEqualTo("tail ");

        Map<String, StreamRewriter.Held> flushed = sr.flushAll();
        assertThat(flushed).containsOnlyKeys("answer-1");
        assertThat(flushed.get("answer-1").content())
                .as("the held tail must still reach the client, rewritten, with its metadata")
                .isEqualTo("![img](https://cdn.example.com/x.png");
        assertThat(flushed.get("answer-1").meta()).isSameAs(meta);
    }

    /**
     * 仅仅"看起来像"没写完的图片不能让流卡住：正文里可以有字面量 {@code ](}，
     * 而 URL 绝不会有这么长。
     */
    @Test
    void longUnclosedImageIsNotHeld() {
        StreamRewriter sr = stub("https://cdn.example.com/x.png");
        String chunk = "![never closed](" + "x".repeat(StreamRewriter.MAX_INCOMPLETE_IMAGE_BYTES);
        assertThat(sr.push("answer-1", chunk, false, null)).isEqualTo(chunk);
    }

    /**
     * 扣留必须有界，否则永远不闭合引用的流会把整篇回答缓冲下来；
     * 而且按字节释放时不能把一个字符劈成两半。
     */
    @Test
    void holdbackIsBounded() {
        StreamRewriter sr = stub("https://cdn.example.com/x.png");

        StringBuilder emitted = new StringBuilder();
        emitted.append(sr.push("answer-1", "开头 resource://", false, null));
        for (int i = 0; i < 20; i++) {
            emitted.append(sr.push("answer-1", "中".repeat(1024), false, null));
        }
        assertThat(emitted.length()).as("bounded holdback must release the excess").isGreaterThan(0);
        // 有界释放之后引用碎片已被冲掉，缓冲里不再有任何东西扣着
        assertThat(sr.flushAll()).isNull();
    }

    /**
     * <b>上限按 UTF-8 字节数算</b>（Go 的 {@code maxHeldBytes} 是字节）。
     * 直接拿 Java 的字符数当字节数，会让中文尾巴的扣留上限放宽 3 倍——
     * 差别在这条用例里是可观察的：pending 有 9015 字节但只有 3016 个 Java 字符，
     * <b>按字节算会释放</b>（emitted 非空），<b>按字符算什么都不会释放</b>（emitted 为空）。
     */
    @Test
    void holdbackCapCountsUtf8BytesNotJavaChars() {
        StreamRewriter sr = stub("https://cdn.example.com/x.png");

        String first = sr.push("answer-1", "![a](resource://", false, null);
        String second = sr.push("answer-1", "中".repeat(3000), false, null);
        assertThat(first + second)
                .as("cap must be measured in UTF-8 bytes: 3016 chars / 9015 bytes must overflow 4096")
                .isNotEmpty();

        Map<String, StreamRewriter.Held> held = sr.flushAll();
        // 释放点对齐到字符边界，最多溢出 3 字节（一个 UTF-8 字符的宽度）
        assertThat(StreamRewriter.utf8Length(held.get("answer-1").content()))
                .isLessThanOrEqualTo(StreamRewriter.MAX_HELD_BYTES + 3);
    }

    /** 禁用的重写器是直通的：默认 API 模式不得引入延迟或缓冲。 */
    @Test
    void disabledIsPassThrough() {
        StreamRewriter sr = new StreamRewriter(new Rewriter(null, "TEST"));
        String in = "![img](resource://xifDo7";
        assertThat(sr.push("answer-1", in, false, null)).isEqualTo(in);
        assertThat(sr.enabled()).isFalse();
    }

    /** 两条流共用同一个 resolver，所以解析必须串行——这条在 Go 侧靠 -race 兜底。 */
    @Test
    void concurrentPushIsSafe() throws Exception {
        StubFileService svc = new StubFileService();
        StreamRewriter sr = new StreamRewriter(new Rewriter(new FixedResolver(svc), "TEST"));

        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            int index = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 20; j++) {
                    // 每次推一条不同的引用，确保真的走到 resolver 而不是命中 memo
                    String ref = "minio://bucket/10000/" + index + "-" + j + ".png";
                    sr.push("answer-" + index, "![x](" + ref + ") ", false, null);
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        // 每个引用都被 `)` 完整终止，故没有残留
        assertThat(sr.flushAll()).isNull();
        assertThat(svc.calls.get()).isGreaterThan(0);
    }

    // ── 与 Go 的差分语料（期望值全部来自 Go 实录） ──────────────────────────

    /**
     * 把 {@code stream.go} 的两个正则与三个函数原样抄进一个独立 Go 程序，
     * 对下面这批输入打印结果，再把输出抄进来。
     *
     * <p>其中 {@code "text local://1/a.png\v"} 一条<b>刻意钉住已知差异</b>：
     * Go 的 RE2 里 {@code \s} 是 {@code [\t\n\f\r ]}（<b>不含</b>垂直制表 {@code \x0B}），
     * Java 默认的 {@code \s} <b>含</b> {@code \x0B}。于是同一个 URL：
     * Go 认为 {@code local://1/a.png\v} 是一个完整 token（{@code refs} 里带上了 {@code \v}），
     * Java 在 {@code \v} 处截断。用 {@code [ \t\n\x0B\f\r]} 之类的显式字符类可以消除，
     * 但那会把 Go 的写法改得面目全非、也更容易在下一次维护时写错——
     * 权衡后保留差异并在此可见（URL 里出现垂直制表符本身是不可能的）。</p>
     */
    @Test
    void differentialCorpusAgainstGo() {
        record Case(String in, int incRef, int incImg, int cutoff) {
        }
        Case[] cases = {
                new Case("text local://1/a.png", 5, -1, 5),
                new Case("text local://1/a.png\n", -1, -1, 21),
                new Case("text local://1/a.png\n\n", -1, -1, 22),
                new Case("text local://1/a.png\r\n", -1, -1, 22),
                new Case("text local://1/a.png\t", -1, -1, 21),
                new Case("a local://b local://c", 12, -1, 12),
                new Case("x resource://aaaaaaaaaaaaaaaaaaaaaa", 2, -1, 2),
                new Case("![a](local://1/a.png)", -1, -1, 21),
                new Case("![a](local://1/a.png", 5, 0, 0),
                new Case("![a](", -1, 0, 0),
                new Case("a\u000Bb local://c", 4, -1, 4),
                new Case("storage://b-a/cos://x/y", 0, -1, 0),
                new Case("s3://", 0, -1, 0),
                new Case("osss://x/y", -1, -1, 10),
                new Case("note: cos://bucket/k.png)", -1, -1, 25),
                new Case("quote \"minio://x/y\"", -1, -1, 19),
                new Case("tag <oss://x/y>", -1, -1, 15),
                new Case("keep http://e.com/a.png minio://x/y", 24, -1, 24),
        };
        for (Case c : cases) {
            assertThat(StreamRewriter.findIncompleteRef(c.in()))
                    .as("FindIncompleteRef(%q)", c.in()).isEqualTo(c.incRef());
            assertThat(StreamRewriter.findIncompleteMarkdownImage(c.in()))
                    .as("FindIncompleteMarkdownImage(%q)", c.in()).isEqualTo(c.incImg());
            assertThat(StreamRewriter.holdbackCutoff(c.in()))
                    .as("HoldbackCutoff(%q)", c.in()).isEqualTo(c.cutoff());
        }
    }

    /** 上表那条已知差异，单独写出来免得被"修好"。Go 实录：{@code incRef=5}。 */
    @Test
    void verticalTabIsAWhitespaceDifferenceFromGo() {
        // Go: \v 不是空白 → `local://1/a.png\v` 是一个完整的尾部引用（incRef=5）
        // Java: \v 是空白 → 引用在 \v 前结束，且没到串尾 → 不算"不完整"（-1）
        assertThat(StreamRewriter.findIncompleteRef("text local://1/a.png\u000B")).isEqualTo(-1);
        assertThat(StreamRewriter.holdbackCutoff("text local://1/a.png\u000B")).isEqualTo(21);
    }

    // ── 字节 / 字符换算 ──

    @Test
    void charIndexAtByteOffsetRoundsDownToCharacterBoundary() {
        String s = "resource://" + "中".repeat(4);
        // "resource://" 是 11 个 ASCII 字符 = 11 字节；每个 '中' 占 3 字节
        assertThat(StreamRewriter.utf8Length("resource://")).isEqualTo(11);
        assertThat(StreamRewriter.charIndexAtByteOffset(s, 11)).isEqualTo(11);
        assertThat(StreamRewriter.charIndexAtByteOffset(s, 12)).isEqualTo(11); // 落在 '中' 内部 → 退到它起点
        assertThat(StreamRewriter.charIndexAtByteOffset(s, 14)).isEqualTo(12);
        assertThat(StreamRewriter.charIndexAtByteOffset(s, 0)).isZero();
    }

    /** 增补平面字符（UTF-8 4 字节 / Java 2 个 char）也必须落在它的高位代理上。 */
    @Test
    void charIndexAtByteOffsetHandlesSupplementaryCharacters() {
        String s = "a😀b"; // 'a' + 😀 + 'b'
        assertThat(StreamRewriter.utf8Length(s)).isEqualTo(1 + 4 + 1);
        assertThat(StreamRewriter.charIndexAtByteOffset(s, 1)).isEqualTo(1);
        assertThat(StreamRewriter.charIndexAtByteOffset(s, 2)).isEqualTo(1);
        assertThat(StreamRewriter.charIndexAtByteOffset(s, 4)).isEqualTo(1);
        assertThat(StreamRewriter.charIndexAtByteOffset(s, 5)).isEqualTo(3);
    }
}
