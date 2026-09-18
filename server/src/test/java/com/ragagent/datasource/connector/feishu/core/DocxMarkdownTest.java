package com.ragagent.datasource.connector.feishu.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.datasource.connector.feishu.core.DocxBlocks.BlockFileRef;
import com.ragagent.datasource.connector.feishu.core.DocxBlocks.BlockText;
import com.ragagent.datasource.connector.feishu.core.DocxBlocks.BlockTokenRef;
import com.ragagent.datasource.connector.feishu.core.DocxBlocks.DocxBlock;
import com.ragagent.datasource.connector.feishu.core.DocxMarkdown.MarkdownResult;
import com.ragagent.datasource.connector.feishu.core.DocxMarkdown.PendingAttachment;

/**
 * 对照 Go {@code core/markdown_test.go} 全文。
 *
 * <p>期望值全部是 <b>Go 实录</b>：把 {@code blocksToMarkdown} 及其全部依赖逐字抄进
 * {@code /tmp/gochk-md} 独立程序跑出来的输出（含整篇富文档的完整 Markdown），
 * 因此任何一处换行/空行/空格差异都会被抓住。</p>
 */
class DocxMarkdownTest {

    /** 对照 Go 测试里的 {@code fakeReader}。 */
    static final class FakeReader implements DocxMarkdown.SheetReader {

        List<List<String>> sheet;
        boolean sheetTruncated;
        RuntimeException sheetErr;
        List<List<String>> bitable;
        boolean bitableTruncated;
        RuntimeException bitableErr;

        @Override
        public FeishuClient.SheetRange readSheetRange(String embedToken) {
            if (sheetErr != null) {
                throw sheetErr;
            }
            return new FeishuClient.SheetRange(sheet == null ? List.of() : sheet, sheetTruncated);
        }

        @Override
        public FeishuClient.BitableTable readBitableRecords(String embedToken) {
            if (bitableErr != null) {
                throw bitableErr;
            }
            return new FeishuClient.BitableTable(bitable == null ? List.of() : bitable, bitableTruncated);
        }
    }

    /** 行构造助手：{@code rows(List.of("a","b"), List.of("c","d"))}。 */
    @SafeVarargs
    private static List<List<String>> rows(List<String>... rs) {
        return new ArrayList<>(List.of(rs));
    }

    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Go 实录：文本构造的完整输出是 \"# 标题\\n\\n一段正文\\n\\n- 要点\\n\"")
    void textConstructsExact() {
        List<DocxBlock> blocks = List.of(
                DocxBlock.of("root", DocxBlocks.BLOCK_TYPE_PAGE),
                DocxBlock.text("h", DocxBlocks.BLOCK_TYPE_HEADING1, "标题"),
                DocxBlock.text("p", DocxBlocks.BLOCK_TYPE_TEXT, "一段正文"),
                DocxBlock.text("b", DocxBlocks.BLOCK_TYPE_BULLET, "要点"));

        MarkdownResult md = DocxMarkdown.blocksToMarkdown(null, blocks);
        assertThat(md.text()).isEqualTo("# 标题\n\n一段正文\n\n- 要点\n");
        assertThat(md.attachments()).isEmpty();
    }

    @Test
    @DisplayName("Go 实录：富文档（1..9 级标题/代码/引用/待办/callout/表格/sheet/图片/附件）的完整 Markdown")
    void richDocumentExact() {
        List<DocxBlock> blocks = new ArrayList<>();
        blocks.add(DocxBlock.of("root", DocxBlocks.BLOCK_TYPE_PAGE));
        blocks.add(DocxBlock.text("h1", DocxBlocks.BLOCK_TYPE_HEADING1, "H1"));
        blocks.add(DocxBlock.text("h2", DocxBlocks.BLOCK_TYPE_HEADING1 + 1, "H2"));
        blocks.add(DocxBlock.text("h3", DocxBlocks.BLOCK_TYPE_HEADING1 + 2, "H3"));
        blocks.add(DocxBlock.text("h9", DocxBlocks.BLOCK_TYPE_HEADING9, "H9"));
        blocks.add(DocxBlock.text("code1", DocxBlocks.BLOCK_TYPE_CODE, "SELECT 1\nFROM t"));
        blocks.add(DocxBlock.text("q1", DocxBlocks.BLOCK_TYPE_QUOTE, "引用"));
        blocks.add(DocxBlock.of("div1", DocxBlocks.BLOCK_TYPE_DIVIDER));
        blocks.add(DocxBlock.text("todo1", DocxBlocks.BLOCK_TYPE_TODO, "完成复盘"));
        blocks.add(DocxBlock.text("call1", DocxBlocks.BLOCK_TYPE_CALLOUT, "注意风险"));
        blocks.add(DocxBlock.table("tbl1", 2, "c1", "c2", "c3", "c4", "c5"));
        blocks.add(DocxBlock.cell("c1"));
        blocks.add(DocxBlock.cell("c2"));
        blocks.add(DocxBlock.cell("c3"));
        blocks.add(DocxBlock.cell("c4"));
        blocks.add(DocxBlock.cell("c5"));
        blocks.add(DocxBlock.text("c1_txt", DocxBlocks.BLOCK_TYPE_TEXT, "列A"));
        blocks.add(DocxBlock.text("c2_txt", DocxBlocks.BLOCK_TYPE_TEXT, "列|B"));
        blocks.add(DocxBlock.text("c3_txt", DocxBlocks.BLOCK_TYPE_TEXT, "1"));
        blocks.add(DocxBlock.text("c4_txt", DocxBlocks.BLOCK_TYPE_TEXT, "2"));
        blocks.add(DocxBlock.text("c5_txt", DocxBlocks.BLOCK_TYPE_TEXT, "3"));
        DocxBlock sheet = DocxBlock.of("sh1", DocxBlocks.BLOCK_TYPE_SHEET);
        sheet.setSheet(new BlockTokenRef("sht_x_0"));
        blocks.add(sheet);
        DocxBlock image = DocxBlock.of("im1", DocxBlocks.BLOCK_TYPE_IMAGE);
        image.setImage(new BlockTokenRef("img_tok"));
        blocks.add(image);
        DocxBlock file1 = DocxBlock.of("f1", DocxBlocks.BLOCK_TYPE_FILE);
        file1.setFile(new BlockFileRef("tok1", "手册.pdf"));
        blocks.add(file1);
        DocxBlock file2 = DocxBlock.of("f2", DocxBlocks.BLOCK_TYPE_FILE);
        file2.setFile(new BlockFileRef("tok2", ""));
        blocks.add(file2);
        blocks.add(DocxBlock.text("mn", DocxBlocks.BLOCK_TYPE_TEXT + 100, "unknown type"));

        FakeReader reader = new FakeReader();
        reader.sheet = rows(List.of("名称", "数量"), List.of("苹果", "3"));

        MarkdownResult md = DocxMarkdown.blocksToMarkdown(reader, blocks);

        String want = "# H1\n"
                + "\n"
                + "## H2\n"
                + "\n"
                + "### H3\n"
                + "\n"
                + "######### H9\n"
                + "\n"
                + "```\n"
                + "SELECT 1\n"
                + "FROM t\n"
                + "```\n"
                + "\n"
                + "> 引用\n"
                + "\n"
                + "---\n"
                + "\n"
                + "- [ ] 完成复盘\n"
                + "\n"
                + "> 注意风险\n"
                + "\n"
                + "| 列A | 列\\|B |\n"
                + "| --- | --- |\n"
                + "| 1 | 2 |\n"
                + "| 3 |  |\n"
                + "\n"
                + "| 名称 | 数量 |\n"
                + "| --- | --- |\n"
                + "| 苹果 | 3 |\n"
                + "\n"
                + "![图片]()\n"
                + "\n"
                + "📎 附件：手册.pdf\n"
                + "\n"
                + "📎 附件：tok2\n";
        assertThat(md.text()).isEqualTo(want);

        // 附件收集：无名字的用 token 输出引用，但 PendingAttachment.name 保持原值（空串）
        assertThat(md.attachments()).containsExactly(
                new PendingAttachment("tok1", "手册.pdf"),
                new PendingAttachment("tok2", ""));
    }

    @Test
    @DisplayName("内嵌 sheet 与附件/图片占位（Go 实录的片段）")
    void embeddedSheetAndFile() {
        List<DocxBlock> blocks = List.of(
                DocxBlock.of("root", DocxBlocks.BLOCK_TYPE_PAGE),
                sheetBlock("s", "sht_a_0"),
                imageBlock("img", "img_t"),
                fileBlock("f", "file_t", "报表.pdf"));
        FakeReader fr = new FakeReader();
        fr.sheet = rows(List.of("名称", "数量"), List.of("苹果", "3"));

        MarkdownResult md = DocxMarkdown.blocksToMarkdown(fr, blocks);
        assertThat(md.text()).contains("| 名称 | 数量 |", "| 苹果 | 3 |");
        assertThat(md.text()).contains("![图片]()");
        assertThat(md.text()).doesNotContain("feishu-media");
        assertThat(md.attachments()).containsExactly(new PendingAttachment("file_t", "报表.pdf"));
        assertThat(md.text()).contains("📎 附件：报表.pdf");
    }

    @Test
    @DisplayName("Go 实录：sheet 截断说明的确切输出")
    void sheetTruncatedNote() {
        List<DocxBlock> blocks = List.of(sheetBlock("s", "sht_a_0"));
        FakeReader fr = new FakeReader();
        fr.sheet = rows(List.of("h"), List.of("1"));
        fr.sheetTruncated = true;

        MarkdownResult md = DocxMarkdown.blocksToMarkdown(fr, blocks);
        assertThat(md.text()).isEqualTo("| h |\n| --- |\n| 1 |\n\n> 表格已截断（仅显示前 500 行）\n");
    }

    @Test
    @DisplayName("权限错误降解成占位（Go 实录：\"> [无法读取内嵌电子表格]\\n\"）")
    void sheetPermissionDegrades() {
        List<DocxBlock> blocks = List.of(sheetBlock("s", "sht_a_0"));
        FakeReader fr = new FakeReader();
        fr.sheetErr = new RuntimeException("code=99991672 permission denied");

        MarkdownResult md = DocxMarkdown.blocksToMarkdown(fr, blocks);
        assertThat(md.text()).isEqualTo("> [无法读取内嵌电子表格]\n");
    }

    @Test
    void bitableInlinedAndDegrades() {
        List<DocxBlock> inlined = List.of(bitableBlock("bt", "bascabc_tblxyz"));
        FakeReader ok = new FakeReader();
        ok.bitable = rows(List.of("任务", "状态"), List.of("写文档", "进行中"));
        MarkdownResult md = DocxMarkdown.blocksToMarkdown(ok, inlined);
        assertThat(md.text()).contains("| 任务 | 状态 |", "| 写文档 | 进行中 |");

        FakeReader bad = new FakeReader();
        bad.bitableErr = new RuntimeException("code=99991672 permission denied");
        MarkdownResult md2 = DocxMarkdown.blocksToMarkdown(bad, inlined);
        assertThat(md2.text()).isEqualTo("> [无法读取内嵌多维表格]\n");
    }

    @Test
    @DisplayName("Go 实录：零列表格的 sheet/bitable 什么都不渲染（截断说明也一起跳过）")
    void zeroColumnInlineTableRendersNothing() {
        FakeReader fr = new FakeReader();
        fr.bitable = rows(List.of());
        MarkdownResult md = DocxMarkdown.blocksToMarkdown(fr, List.of(bitableBlock("b", "t")));
        assertThat(md.text()).isEmpty();
    }

    @Test
    @DisplayName("原生表格从单元格子块取文本，且不再作为散段落重复输出")
    void nativeTableFromCellChildren() {
        List<DocxBlock> blocks = new ArrayList<>(List.of(
                DocxBlock.of("root", DocxBlocks.BLOCK_TYPE_PAGE),
                DocxBlock.table("t", 2, "c1", "c2", "c3", "c4"),
                DocxBlock.cell("c1"), DocxBlock.cell("c2"), DocxBlock.cell("c3"), DocxBlock.cell("c4"),
                DocxBlock.text("c1_txt", DocxBlocks.BLOCK_TYPE_TEXT, "姓名"),
                DocxBlock.text("c2_txt", DocxBlocks.BLOCK_TYPE_TEXT, "分数"),
                DocxBlock.text("c3_txt", DocxBlocks.BLOCK_TYPE_TEXT, "张三"),
                DocxBlock.text("c4_txt", DocxBlocks.BLOCK_TYPE_TEXT, "95")));

        MarkdownResult md = DocxMarkdown.blocksToMarkdown(null, blocks);
        assertThat(md.text()).contains("| 姓名 | 分数 |", "| 张三 | 95 |");
        assertThat(countOccurrences(md.text(), "姓名")).isEqualTo(1);
    }

    @Test
    @DisplayName("表格单元格里的附件仍要被收集（consumed 不能吞掉 File/Image 块）")
    void attachmentInsideTableCellStillCollected() {
        List<DocxBlock> blocks = List.of(
                DocxBlock.of("root", DocxBlocks.BLOCK_TYPE_PAGE),
                DocxBlock.table("t", 1, "c1"),
                DocxBlock.cell("c1"),
                fileBlock("f1", "tok-in-cell", "内嵌.pdf"));

        MarkdownResult md = DocxMarkdown.blocksToMarkdown(null, blocks);
        assertThat(md.attachments()).containsExactly(new PendingAttachment("tok-in-cell", "内嵌.pdf"));
        // Go 实录：单元格文本为空 → 仍渲染出单列表格体
        assertThat(md.text()).isEqualTo("|  |\n| --- |\n\n📎 附件：内嵌.pdf\n");
    }

    @Test
    @DisplayName("Go 实录：参差行被夹到表头宽度")
    void markdownTableRaggedRowClamped() {
        String out = DocxMarkdown.markdownTable(rows(
                List.of("名称", "数量"), List.of("苹果", "3", "多余1", "多余2")));
        assertThat(out).isEqualTo("| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |");
        assertThat(out.lines().count()).isEqualTo(3);
        assertThat(out).doesNotContain("多余");
    }

    @Test
    @DisplayName("Go 实录：零列表格什么都不渲染（{} / [{},{},{}] / nil）")
    void markdownTableZeroColumnRendersNothing() {
        assertThat(DocxMarkdown.markdownTable(rows(List.of()))).isEmpty();
        assertThat(DocxMarkdown.markdownTable(rows(List.of(), List.of(), List.of()))).isEmpty();
        assertThat(DocxMarkdown.markdownTable(null)).isEmpty();
        assertThat(DocxMarkdown.markdownTable(List.of())).isEmpty();
    }

    @Test
    @DisplayName("Go 实录：短行补空单元格 / 单元格里的换行与竖线被转义")
    void markdownTablePadsAndEscapes() {
        assertThat(DocxMarkdown.markdownTable(rows(List.of("a", "b", "c"), List.of("1"))))
                .isEqualTo("| a | b | c |\n| --- | --- | --- |\n| 1 |  |  |");
        assertThat(DocxMarkdown.markdownTable(rows(List.of("a\nb", "c"))))
                .isEqualTo("| a b | c |\n| --- | --- |");
    }

    @Test
    @DisplayName("空白文档渲染成空字符串（触发导出回落的那条不变式）")
    void blankDocRendersEmpty() {
        List<DocxBlock> blocks = List.of(
                DocxBlock.of("root", DocxBlocks.BLOCK_TYPE_PAGE),
                DocxBlock.text("p", DocxBlocks.BLOCK_TYPE_TEXT, ""));
        MarkdownResult md = DocxMarkdown.blocksToMarkdown(null, blocks);
        assertThat(md.text().trim()).isEmpty();
        assertThat(md.attachments()).isEmpty();
    }

    @Test
    void todoAndCallout() {
        List<DocxBlock> blocks = List.of(
                DocxBlock.of("root", DocxBlocks.BLOCK_TYPE_PAGE),
                DocxBlock.text("t", DocxBlocks.BLOCK_TYPE_TODO, "买牛奶"),
                DocxBlock.text("c", DocxBlocks.BLOCK_TYPE_CALLOUT, "注意事项"));
        MarkdownResult md = DocxMarkdown.blocksToMarkdown(null, blocks);
        assertThat(md.text()).contains("- [ ] 买牛奶");
        assertThat(md.text()).contains("> 注意事项");
    }

    @Test
    @DisplayName("纯容器 callout（没有直接文本）不输出任何东西")
    void calloutContainerNoOp() {
        List<DocxBlock> blocks = List.of(
                DocxBlock.of("root", DocxBlocks.BLOCK_TYPE_PAGE),
                DocxBlock.of("c", DocxBlocks.BLOCK_TYPE_CALLOUT));
        MarkdownResult md = DocxMarkdown.blocksToMarkdown(null, blocks);
        assertThat(md.text().trim()).isEmpty();
    }

    @Test
    @DisplayName("Go 实录：不可渲染的原生表格保留单元格文本为散段落")
    void unrenderableTablePreservesCellText() {
        DocxBlock unrenderable = DocxBlock.of("t", DocxBlocks.BLOCK_TYPE_TABLE);
        unrenderable.setTable(new DocxBlocks.BlockTable(List.of("c1"), null));
        List<DocxBlock> blocks = List.of(
                DocxBlock.of("root", DocxBlocks.BLOCK_TYPE_PAGE),
                unrenderable,
                DocxBlock.cell("c1"),
                DocxBlock.text("c1_txt", DocxBlocks.BLOCK_TYPE_TEXT, "重要内容"));

        MarkdownResult md = DocxMarkdown.blocksToMarkdown(null, blocks);
        assertThat(md.text()).isEqualTo("重要内容\n");
    }

    @Test
    @DisplayName("Go 实录：client 为 null 且块集合里有 sheet → 渲染成空（不是 NPE）")
    void nilClientRendersNothingForDowndrillBlock() {
        MarkdownResult md = DocxMarkdown.blocksToMarkdown(null, List.of(sheetBlock("s", "t")));
        assertThat(md.text()).isEmpty();
    }

    @Test
    @DisplayName("图片占位绝不泄漏内部 media token")
    void imagePlaceholderLeaksNoToken() {
        MarkdownResult md = DocxMarkdown.blocksToMarkdown(null,
                List.of(imageBlock("im", "img-tok-SECRET")));
        assertThat(md.text()).isEqualTo("![图片]()\n");
        assertThat(md.text()).doesNotContain("img-tok-SECRET");
    }

    @Test
    @DisplayName("未知块类型（不在标题区间）什么都不输出")
    void unknownBlockTypeIsIgnored() {
        MarkdownResult md = DocxMarkdown.blocksToMarkdown(null,
                List.of(DocxBlock.text("x", 99, "不应出现")));
        assertThat(md.text()).isEmpty();
    }

    // ── 构造助手（对照 Go 测试里的 blk 工厂） ──────────────────────────────

    static DocxBlock sheetBlock(String id, String token) {
        DocxBlock b = DocxBlock.of(id, DocxBlocks.BLOCK_TYPE_SHEET);
        b.setSheet(new BlockTokenRef(token));
        return b;
    }

    static DocxBlock bitableBlock(String id, String token) {
        DocxBlock b = DocxBlock.of(id, DocxBlocks.BLOCK_TYPE_BITABLE);
        b.setBitable(new BlockTokenRef(token));
        return b;
    }

    static DocxBlock imageBlock(String id, String token) {
        DocxBlock b = DocxBlock.of(id, DocxBlocks.BLOCK_TYPE_IMAGE);
        b.setImage(new BlockTokenRef(token));
        return b;
    }

    static DocxBlock fileBlock(String id, String token, String name) {
        DocxBlock b = DocxBlock.of(id, DocxBlocks.BLOCK_TYPE_FILE);
        b.setFile(new BlockFileRef(token, name));
        return b;
    }

    /** 对照 Go 的 {@code txt(s)}。 */
    static BlockText txt(String s) {
        return BlockText.of(s);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
