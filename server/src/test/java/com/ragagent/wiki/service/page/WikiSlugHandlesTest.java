package com.ragagent.wiki.service.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/application/service/wiki_slug_handles_test.go（72 行）的 3 个用例。
 *
 * <p>纯内存测试，不需要 Spring 上下文。Go 的句柄表是
 * {@code modelcontext.HandleTable("ref-", 0, 1)}，Java 侧由
 * {@link WikiSlugHandles} 内部实现等价分配器。</p>
 */
class WikiSlugHandlesTest {

    /**
     * 对照 Go {@code TestWikiSlugHandles_RoundTripEscapesUUIDSlugs}：
     * 高熵 UUID 不再出现在模型看到的内容里，且往返解码必须<b>逐字节</b>还原。
     */
    @Test
    void roundTripEscapesUuidSlugs() {
        String summarySlug = "summary/07a20bb1-a662-47cf-9929-06fb5d5b5b5e";
        String entitySlug = "entity/mongodb";
        Set<String> known = Set.of(summarySlug, entitySlug);

        WikiSlugHandles h = new WikiSlugHandles();
        // 像 ingest 的清单循环那样预先分配句柄
        String tSummary = h.handle(summarySlug);
        String tEntity = h.handle(entitySlug);
        assertThat(tSummary).isNotEqualTo(summarySlug);
        assertThat(tEntity).isNotEqualTo(entitySlug);
        assertThat(tSummary).doesNotContain("/");
        // 对照 Go HandleTable(prefix="ref-", width=0, start=1)：ref-1 起编号
        assertThat(tSummary).isEqualTo("ref-1");
        assertThat(tEntity).isEqualTo("ref-2");

        String body = "See [[" + summarySlug + "|Weknora 试错记录.md - Summary]] and [["
                + entitySlug + "]].";
        String aliased = h.encodeContent(body, known);

        // 高熵 UUID 绝不能再出现在模型看到的内容里
        assertThat(aliased).doesNotContain("06fb5d5b5b5e");
        assertThat(aliased).contains("[[" + tSummary + "|Weknora 试错记录.md - Summary]]");
        assertThat(aliased).contains("[[" + tEntity + "]]");

        // 往返必须精确还原原始正文
        assertThat(h.decodeContent(aliased)).isEqualTo(body);
    }

    /**
     * 对照 Go {@code TestWikiSlugHandles_UnknownHandlesAndSlugsUntouched}：
     * 不在 {@code known} 里的 slug 不别名化；没有映射的句柄原样保留
     * （落到常规的死链清理路径）。
     */
    @Test
    void unknownHandlesAndSlugsUntouched() {
        WikiSlugHandles h = new WikiSlugHandles();
        h.handle("summary/real-uuid");

        // 不在 known 里的 slug 不得被别名化
        Set<String> known = Set.of("summary/real-uuid");
        String body = "keep [[entity/invented-by-model]] as-is";
        assertThat(h.encodeContent(body, known)).isEqualTo(body);

        // 输出里没有映射的句柄必须原样保留
        String out = "stray [[ref-999|X]] token";
        assertThat(h.decodeContent(out)).isEqualTo(out);
    }

    /** 对照 Go {@code TestWikiSlugHandles_EmptyIsNoop}：空表解码是 no-op */
    @Test
    void emptyIsNoop() {
        WikiSlugHandles h = new WikiSlugHandles();
        assertThat(h.isEmpty()).isTrue();

        String body = "[[summary/x]] unchanged";
        assertThat(h.decodeContent(body)).isEqualTo(body);
        // 空 known 集合时编码也是 no-op
        assertThat(h.encodeContent(body, Set.of())).isEqualTo(body);
        // 空内容直接返回
        assertThat(h.decodeContent("")).isEmpty();
        assertThat(h.encodeContent(null, Set.of("a"))).isNull();
    }

    /** 空 slug / 空句柄的边界（对照 Go {@code register} 的 {@code key == ""} 分支） */
    @Test
    void blankInputsReturnBlank() {
        WikiSlugHandles h = new WikiSlugHandles();
        assertThat(h.handle("")).isEmpty();
        assertThat(h.handle(null)).isEmpty();
        assertThat(h.resolve("")).isNull();
        assertThat(h.resolve("ref-does-not-exist")).isNull();
        assertThat(h.isEmpty()).as("空 slug 不占号").isTrue();
    }

    /** 同一个 slug 重复 register 必须返回同一个句柄（Go 的 handleByKey 记忆） */
    @Test
    void handleIsStablePerSlug() {
        WikiSlugHandles h = new WikiSlugHandles();
        String first = h.handle("entity/a");
        assertThat(h.handle("entity/a")).isEqualTo(first);
        assertThat(h.handle("entity/b")).isEqualTo("ref-2");
        assertThat(h.size()).isEqualTo(2);

        // 正文里只出现（不在清单里）的 slug 也能按需拿到一致句柄
        Set<String> known = Set.of("entity/a");
        String encoded = h.encodeContent("[[entity/a]] and [[entity/b]]", known);
        assertThat(encoded).isEqualTo("[[ref-1]] and [[entity/b]]");
    }

    /** 显示文本原样保留；句柄形态的 display 部分不被改写 */
    @Test
    void displayTextPreservedVerbatim() {
        WikiSlugHandles h = new WikiSlugHandles();
        Set<String> known = Set.of("summary/x");
        String encoded = h.encodeContent("看 [[summary/x|显示 名称 原样]]。", known);
        assertThat(encoded).isEqualTo("看 [[ref-1|显示 名称 原样]]。");
        assertThat(h.decodeContent(encoded)).isEqualTo("看 [[summary/x|显示 名称 原样]]。");
    }
}
