package com.ragagent.datasource.connector.notion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@code BlocksToMarkdown} / 富文本渲染的**逐字符**对等测试。
 *
 * <h2>期望值的来源</h2>
 * <p>全部是 <b>Go 实录</b>：在 {@code /tmp/weknora-copy}（WeKnora 的完整副本）里
 * 加一个 {@code probe_test.go}，把生产代码原样跑起来、用 {@code %q} 打印结果，
 * 再把输出抄进下面的断言。所以这里没有"我觉得应该是这样"的期望值。</p>
 *
 * <p>块对象一律**用 JSON 构造**（走 {@link NotionBlock.Deserializer}），
 * 好让"以 type 命名的字段被抽进 RawContent"这条自定义反序列化也一起被测到，
 * 而不是绕过它手工塞 RawContent。</p>
 */
class NotionMarkdownTest {

    // ── 构造工具 ──────────────────────────────────────────────────────────

    private static NotionBlock block(String type, String contentJson) {
        String json = "{\"id\":\"b-" + type + "\",\"type\":\"" + type
                + "\",\"has_children\":false"
                + (contentJson == null ? "" : ",\"" + type + "\":" + contentJson) + "}";
        try {
            return NotionJson.MAPPER.readValue(json, NotionBlock.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static NotionBlock withChildren(NotionBlock block, NotionBlock... children) {
        block.hasChildren = true;
        block.children = new ArrayList<>(Arrays.asList(children));
        return block;
    }

    /** 一段 {@code rich_text} 数组 JSON（{@code type=text} 片段）。 */
    private static String rt(String... contents) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < contents.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(NotionStubServer.richText(contents[i]));
        }
        return sb.append(']').toString();
    }

    private static String paragraph(String... contents) {
        return "{\"rich_text\":" + rt(contents) + "}";
    }

    private static NotionMarkdown.Result markdown(List<NotionBlock> blocks) {
        return NotionMarkdown.blocksToMarkdown(blocks);
    }

    // ── 块渲染 ────────────────────────────────────────────────────────────

    @Test
    void paragraph() {
        NotionMarkdown.Result result = markdown(List.of(
                block("paragraph", paragraph("Hello world"))));
        assertThat(result.markdown).isEqualTo("Hello world\n");
        assertThat(result.attachments).isEmpty();
    }

    @Test
    void headings() {
        NotionMarkdown.Result result = markdown(List.of(
                block("heading_1", paragraph("H1")),
                block("heading_2", paragraph("H2")),
                block("heading_3", paragraph("H3")),
                block("heading_4", paragraph("H4"))));
        assertThat(result.markdown).isEqualTo("# H1\n\n## H2\n\n### H3\n\n#### H4\n");
    }

    @Test
    void headingWithChildren() {
        NotionBlock heading = withChildren(block("heading_2", paragraph("H2")),
                block("paragraph", paragraph("under")));
        assertThat(markdown(List.of(heading)).markdown).isEqualTo("## H2\n\nunder\n");
    }

    @Test
    void listsMixedAndTrailingClose() {
        NotionMarkdown.Result result = markdown(List.of(
                block("bulleted_list_item", paragraph("A")),
                block("numbered_list_item", paragraph("N1")),
                block("numbered_list_item", paragraph("N2")),
                block("numbered_list_item", paragraph("N3")),
                block("bulleted_list_item", paragraph("B")),
                block("paragraph", paragraph("after"))));
        assertThat(result.markdown).isEqualTo("- A\n1. N1\n2. N2\n3. N3\n- B\n\nafter\n");
    }

    @Test
    void numberedListRestartsAfterOtherBlock() {
        NotionMarkdown.Result result = markdown(List.of(
                block("numbered_list_item", paragraph("1")),
                block("paragraph", paragraph("mid")),
                block("numbered_list_item", paragraph("1again")),
                block("to_do", "{\"checked\":true,\"rich_text\":" + rt("todo") + "}"),
                block("to_do", "{\"checked\":true,\"rich_text\":" + rt("todo2") + "}")));
        assertThat(result.markdown).isEqualTo("1. 1\n\nmid\n\n1. 1again\n- [x] todo\n- [x] todo2\n");
    }

    @Test
    void nestedListIndentsByTwoSpaces() {
        NotionBlock parent = withChildren(
                block("bulleted_list_item", paragraph("Parent")),
                block("bulleted_list_item", paragraph("Child")));
        assertThat(markdown(List.of(parent)).markdown).isEqualTo("- Parent\n  - Child\n");
    }

    @Test
    void todo() {
        NotionMarkdown.Result result = markdown(List.of(
                block("to_do", "{\"checked\":false,\"rich_text\":" + rt("Un") + "}"),
                block("to_do", "{\"checked\":true,\"rich_text\":" + rt("Ch") + "}"),
                block("to_do", "{\"rich_text\":" + rt("Missing") + "}")));
        assertThat(result.markdown).isEqualTo("- [ ] Un\n- [x] Ch\n- [ ] Missing\n");
    }

    @Test
    void toggle() {
        NotionBlock toggle = withChildren(
                block("toggle", paragraph("Sum")),
                block("paragraph", paragraph("body")));
        assertThat(markdown(List.of(toggle)).markdown)
                .isEqualTo("<details><summary>Sum</summary>\n\nbody\n\n</details>\n");
    }

    @Test
    void codeWithAndWithoutLanguage() {
        NotionMarkdown.Result result = markdown(List.of(
                block("code", "{\"language\":\"go\",\"rich_text\":" + rt("fmt.Println()") + "}"),
                block("code", paragraph("no lang"))));
        assertThat(result.markdown)
                .isEqualTo("```go\nfmt.Println()\n```\n\n```\nno lang\n```\n");
    }

    @Test
    void quoteAndMeetingNotesPrefixEveryLine() {
        NotionMarkdown.Result result = markdown(List.of(
                block("quote", paragraph("l1\nl2")),
                block("meeting_notes", paragraph("note"))));
        assertThat(result.markdown).isEqualTo("> l1\n> l2\n\n> note\n");
    }

    @Test
    void calloutUsesEmojiIconOnly() {
        NotionMarkdown.Result result = markdown(List.of(
                block("callout", "{\"rich_text\":" + rt("warn")
                        + ",\"icon\":{\"type\":\"emoji\",\"emoji\":\"OK\"}}"),
                block("callout", paragraph("plain")),
                block("callout", "{\"rich_text\":" + rt("img")
                        + ",\"icon\":{\"type\":\"file\",\"file\":{\"url\":\"u\"}}}")));
        assertThat(result.markdown).isEqualTo("> OK warn\n\n> plain\n\n> img\n");
    }

    @Test
    void dividerAndEquation() {
        NotionMarkdown.Result result = markdown(List.of(
                block("divider", null),
                block("equation", "{\"expression\":\"E = mc^2\"}")));
        assertThat(result.markdown).isEqualTo("---\n\n$$E = mc^2$$\n");
    }

    @Test
    void tableEscapesPipeAndAddsSeparatorAfterFirstRow() {
        NotionBlock table = withChildren(
                block("table", "{\"table_width\":2}"),
                block("table_row", "{\"cells\":[[" + NotionStubServer.richText("Name")
                        + "],[" + NotionStubServer.richText("Age") + "]]}"),
                block("table_row", "{\"cells\":[[" + NotionStubServer.richText("Alice")
                        + "],[" + NotionStubServer.richText("30") + "]]}"),
                block("table_row", "{\"cells\":[[" + NotionStubServer.richText("A|B")
                        + "],[]]}"));
        assertThat(markdown(List.of(table)).markdown)
                .isEqualTo("| Name | Age |\n| --- | --- |\n| Alice | 30 |\n| A\\|B |  |\n");
    }

    @Test
    void tableWithoutChildrenRendersNothing() {
        assertThat(markdown(List.of(block("table", null))).markdown).isEqualTo("\n");
    }

    @Test
    void imageWithCaptionCollectsAttachment() {
        NotionMarkdown.Result result = markdown(List.of(block("image",
                "{\"type\":\"file\",\"file\":{\"url\":\"https://s3.example.com/dir/img.png?X-Amz-Sig=1\""
                        + ",\"expiry_time\":\"2026-01-15T11:00:00.000Z\"}"
                        + ",\"caption\":[{\"type\":\"text\",\"plain_text\":\"A photo\""
                        + ",\"text\":{\"content\":\"A photo\"}}]}")));
        assertThat(result.markdown)
                .isEqualTo("![A photo](https://s3.example.com/dir/img.png?X-Amz-Sig=1)\n");
        assertThat(result.attachments).hasSize(1);
        assertThat(result.attachments.get(0).url)
                .isEqualTo("https://s3.example.com/dir/img.png?X-Amz-Sig=1");
        assertThat(result.attachments.get(0).fileName).isEqualTo("img.png");
        assertThat(result.attachments.get(0).type).isEqualTo("image");
    }

    @Test
    void imageExternalWithoutCaption() {
        NotionMarkdown.Result result = markdown(List.of(block("image",
                "{\"type\":\"external\",\"external\":{\"url\":\"https://example.com/a.png\"}}")));
        assertThat(result.markdown).isEqualTo("![](https://example.com/a.png)\n");
        assertThat(result.attachments).hasSize(1);
        assertThat(result.attachments.get(0).fileName).isEqualTo("a.png");
    }

    @Test
    void imageWithoutUrlProducesNoAttachment() {
        NotionMarkdown.Result result = markdown(List.of(block("image",
                "{\"type\":\"file\",\"caption\":[]}")));
        assertThat(result.markdown).isEqualTo("![]()\n");
        assertThat(result.attachments).isEmpty();
    }

    /** file_upload 型：GetURL 刻意回空串（要先 resolveFileUploads 换掉）。 */
    @Test
    void imageFileUploadHasNoUrlBeforeResolution() {
        NotionMarkdown.Result result = markdown(List.of(block("image",
                "{\"type\":\"file_upload\",\"file_upload\":{\"id\":\"fu-1\"}}")));
        assertThat(result.markdown).isEqualTo("![]()\n");
        assertThat(result.attachments).isEmpty();
    }

    @Test
    void mediaBlocksRenderLinkAndCollectTypedAttachment() {
        for (String type : List.of("file", "pdf", "video", "audio")) {
            NotionMarkdown.Result result = markdown(List.of(block(type,
                    "{\"type\":\"file\",\"file\":{\"url\":\"https://s3.example.com/f.pdf?x=1\"}}")));
            assertThat(result.markdown).as(type)
                    .isEqualTo("[f.pdf](https://s3.example.com/f.pdf?x=1)\n");
            assertThat(result.attachments).as(type).hasSize(1);
            assertThat(result.attachments.get(0).type).as(type).isEqualTo(type);
            assertThat(result.attachments.get(0).fileName).as(type).isEqualTo("f.pdf");
        }
    }

    @Test
    void mediaBlockUsesExplicitNameWhenPresent() {
        NotionMarkdown.Result result = markdown(List.of(block("file",
                "{\"type\":\"file\",\"name\":\"My Doc.pdf\",\"file\":{\"url\":\"https://s3.example.com/f.pdf\"}}")));
        assertThat(result.markdown).isEqualTo("[My Doc.pdf](https://s3.example.com/f.pdf)\n");
        assertThat(result.attachments.get(0).fileName).isEqualTo("My Doc.pdf");
    }

    @Test
    void mediaBlockWithoutUrlUsesBlockTypeAsName() {
        NotionMarkdown.Result result = markdown(List.of(block("pdf", "{}")));
        assertThat(result.markdown).isEqualTo("[pdf]()\n");
        assertThat(result.attachments).isEmpty();
    }

    @Test
    void bookmarkLinkPreviewAndEmbed() {
        NotionMarkdown.Result result = markdown(List.of(
                block("bookmark", "{\"url\":\"https://ex.com\",\"caption\":" + rt("Cap") + "}"),
                block("bookmark", "{\"url\":\"https://ex.com\"}"),
                block("link_preview", "{\"url\":\"https://ex.com\"}"),
                block("embed", "{\"url\":\"https://ex.com/x\"}")));
        assertThat(result.markdown).isEqualTo("[Cap](https://ex.com)\n\n"
                + "[https://ex.com](https://ex.com)\n\n"
                + "[https://ex.com](https://ex.com)\n\n"
                + "[https://ex.com/x](https://ex.com/x)\n");
    }

    @Test
    void linkToPageStripsDashesFromId() {
        assertThat(markdown(List.of(block("link_to_page",
                "{\"type\":\"page_id\",\"page_id\":\"abc-def-123\"}"))).markdown)
                .isEqualTo("[Page abc-def-123](https://notion.so/abcdef123)\n");
        // database_id 不是被读的键 → pageId 为空
        assertThat(markdown(List.of(block("link_to_page",
                "{\"type\":\"database_id\",\"database_id\":\"db-1\"}"))).markdown)
                .isEqualTo("[Page ](https://notion.so/)\n");
    }

    @Test
    void childPageAndChildDatabaseRenderLinks() {
        NotionMarkdown.Result result = markdown(List.of(
                block("child_page", "{\"title\":\"Sub\"}"),
                block("child_page", "{}"),
                block("child_database", "{\"title\":\"DB\"}"),
                block("child_database", "{}")));
        assertThat(result.markdown).isEqualTo(
                "- [Sub](https://notion.so/bchild_page)\n"
                        + "- [Untitled](https://notion.so/bchild_page)\n"
                        + "- [DB](https://notion.so/bchild_database)\n"
                        + "- [Database](https://notion.so/bchild_database)\n");
    }

    @Test
    void containerBlocksRenderChildrenSequentially() {
        NotionBlock columnList = withChildren(block("column_list", null),
                withChildren(block("column", null), block("paragraph", paragraph("colA"))),
                withChildren(block("column", null), block("paragraph", paragraph("colB"))));
        NotionBlock tabList = withChildren(block("tab_list", null),
                withChildren(block("tab", null), block("paragraph", paragraph("tabA"))));
        NotionBlock synced = withChildren(block("synced_block", null),
                block("paragraph", paragraph("synced")));
        assertThat(markdown(List.of(columnList, tabList, synced)).markdown)
                .isEqualTo("colA\n\ncolB\n\ntabA\n\nsynced\n");
    }

    @Test
    void skippedAndUnknownTypesProduceEmptyBody() {
        NotionMarkdown.Result result = markdown(List.of(
                block("table_of_contents", null),
                block("breadcrumb", null),
                block("template", null),
                block("table_row", null),
                block("unsupported", null),
                block("future_block_type", null)));
        assertThat(result.markdown).isEqualTo("\n");
    }

    @Test
    void paragraphWithoutRawContent() {
        assertThat(markdown(List.of(block("paragraph", null))).markdown).isEqualTo("\n");
    }

    @Test
    void collapsesThreeOrMoreBlankLines() {
        NotionMarkdown.Result result = markdown(List.of(
                block("paragraph", paragraph("A")),
                block("paragraph", null),
                block("paragraph", null),
                block("paragraph", paragraph("B"))));
        assertThat(result.markdown).isEqualTo("A\n\nB\n");
    }

    @Test
    void emptyInputsStillYieldNewline() {
        assertThat(markdown(List.of(block("paragraph", null))).markdown).isEqualTo("\n");
        assertThat(markdown(new ArrayList<>()).markdown).isEqualTo("\n");
        assertThat(markdown(null).markdown).isEqualTo("\n");
        assertThat(markdown(null).attachments).isEmpty();
    }

    /**
     * NBSP（U+00A0）必须被当作空白裁掉——这正是 {@code strings.TrimSpace} 与
     * {@code String.strip()} 分叉的地方（Java 的 {@code isWhitespace} 不含 U+00A0）。
     * Go 实录：{@code "  \t"} 的段落 → {@code "\n"}。
     */
    @Test
    void trimsNonBreakingSpaceLikeGo() {
        NotionMarkdown.Result result = markdown(List.of(
                block("paragraph", paragraph("  \t"))));
        assertThat(result.markdown).isEqualTo("\n");
        assertThat(NotionValues.trimSpace("  \t")).isEmpty();
        // 对照组：Java 自己的 strip 会把 NBSP 留下来（说明这里不是白写的）
        assertThat("  \t".strip()).isNotEmpty();
    }

    // ── 富文本渲染 ────────────────────────────────────────────────────────

    @Test
    void renderRichTextCanonicalCases() {
        assertThat(render(richText("text", "\"plain_text\":\"hello\",\"text\":{\"content\":\"hello\"}")))
                .isEqualTo("hello");
        assertThat(render(richText("text", "\"plain_text\":\"fallback\"")))
                .isEqualTo("fallback");
        assertThat(render(richText("text", "\"plain_text\":\"b\",\"text\":{\"content\":\"b\"},"
                + "\"annotations\":{\"bold\":true}"))).isEqualTo("**b**");
        assertThat(render(richText("text", "\"plain_text\":\"i\",\"text\":{\"content\":\"i\"},"
                + "\"annotations\":{\"italic\":true}"))).isEqualTo("*i*");
        assertThat(render(richText("text", "\"plain_text\":\"bi\",\"text\":{\"content\":\"bi\"},"
                + "\"annotations\":{\"bold\":true,\"italic\":true}"))).isEqualTo("***bi***");
        assertThat(render(richText("text", "\"plain_text\":\"c\",\"text\":{\"content\":\"c\"},"
                + "\"annotations\":{\"code\":true}"))).isEqualTo("`c`");
        assertThat(render(richText("text", "\"plain_text\":\"s\",\"text\":{\"content\":\"s\"},"
                + "\"annotations\":{\"strikethrough\":true}"))).isEqualTo("~~s~~");
        assertThat(render(richText("text", "\"plain_text\":\"u\",\"text\":{\"content\":\"u\"},"
                + "\"annotations\":{\"underline\":true}"))).isEqualTo("<u>u</u>");
        assertThat(render(richText("text", "\"plain_text\":\"x\",\"text\":{\"content\":\"x\"},"
                + "\"annotations\":{\"code\":true,\"bold\":true,\"italic\":true,"
                + "\"strikethrough\":true,\"underline\":true}"))).isEqualTo("<u>~~***`x`***~~</u>");

        // 空文本不套任何标记（Go 的 applyAnnotations 首行 return）
        assertThat(render(richText("text", "\"plain_text\":\"\",\"text\":{\"content\":\"\"},"
                + "\"annotations\":{\"bold\":true}"))).isEmpty();

        // 链接：code 样式下**不**加包装
        assertThat(render(richText("text", "\"plain_text\":\"click\",\"href\":\"https://example.com\","
                + "\"text\":{\"content\":\"click\"}"))).isEqualTo("[click](https://example.com)");
        assertThat(render(richText("text", "\"plain_text\":\"cc\",\"href\":\"https://example.com\","
                + "\"text\":{\"content\":\"cc\"},\"annotations\":{\"code\":true}"))).isEqualTo("`cc`");
    }

    @Test
    void renderRichTextMentionsAndEquations() {
        assertThat(render(richText("equation", "\"plain_text\":\"E\","
                + "\"equation\":{\"expression\":\"E=mc^2\"}"))).isEqualTo("$E=mc^2$");
        assertThat(render(richText("equation", "\"plain_text\":\"E\""))).isEqualTo("E");
        assertThat(render(richText("mention", "\"plain_text\":\"M\""))).isEqualTo("M");
        assertThat(render(richText("mention", "\"plain_text\":\"d\","
                + "\"mention\":{\"type\":\"date\",\"date\":{\"start\":\"2026-01-15\"}}"))).isEqualTo("2026-01-15");
        assertThat(render(richText("mention", "\"plain_text\":\"d\","
                + "\"mention\":{\"type\":\"date\",\"date\":{\"start\":\"2026-01-15\","
                + "\"end\":\"2026-01-20\"}}"))).isEqualTo("2026-01-15 → 2026-01-20");
        assertThat(render(richText("mention", "\"plain_text\":\"d\","
                + "\"mention\":{\"type\":\"date\"}"))).isEqualTo("d");
        assertThat(render(richText("mention", "\"plain_text\":\"PageTitle\","
                + "\"mention\":{\"type\":\"page\",\"page\":{\"id\":\"p1\"}}"))).isEqualTo("PageTitle");
        assertThat(render(richText("mention", "\"plain_text\":\"PT\","
                + "\"mention\":{\"type\":\"page\"}"))).isEqualTo("PT");
        assertThat(render(richText("mention", "\"plain_text\":\"DBTitle\","
                + "\"mention\":{\"type\":\"database\",\"database\":{\"id\":\"d1\"}}"))).isEqualTo("DBTitle");
        assertThat(render(richText("mention", "\"plain_text\":\"DSTitle\","
                + "\"mention\":{\"type\":\"data_source\",\"database\":{\"id\":\"d1\"}}"))).isEqualTo("DSTitle");
        assertThat(render(richText("mention", "\"plain_text\":\"LP\","
                + "\"mention\":{\"type\":\"link_preview\",\"link_preview\":{\"url\":\"https://lp\"}}"))).isEqualTo("https://lp");
        // user mention 没有专属分支 → 直接落 PlainText
        assertThat(render(richText("mention", "\"plain_text\":\"User\","
                + "\"mention\":{\"type\":\"user\"}"))).isEqualTo("User");
        // 未知 type → PlainText
        assertThat(render(richText("mystery", "\"plain_text\":\"unk\""))).isEqualTo("unk");
        assertThat(render(richText("text", "\"plain_text\":\"\",\"text\":{\"content\":\"\"}"))).isEmpty();
    }

    @Test
    void renderRichTextConcatenatesSegments() {
        List<NotionRichText> texts = new ArrayList<>();
        texts.add(parseRichText("{\"type\":\"text\",\"plain_text\":\"hello\",\"text\":{\"content\":\"hello\"}}"));
        texts.add(parseRichText("{\"type\":\"text\",\"plain_text\":\"b\",\"text\":{\"content\":\"b\"},"
                + "\"annotations\":{\"bold\":true}}"));
        assertThat(NotionMarkdown.renderRichText(texts)).isEqualTo("hello**b**");
        assertThat(NotionMarkdown.renderRichText(null)).isEmpty();
    }

    // ── 文件名 / MIME ─────────────────────────────────────────────────────

    @Test
    void fileNameFromUrl() {
        assertThat(NotionMarkdown.fileNameFromURL("", "image")).isEqualTo("image");
        assertThat(NotionMarkdown.fileNameFromURL(
                "https://s3.example.com/dir/img.png?X=1&Y=2", "image")).isEqualTo("img.png");
        // 没有 / 之后的部分 → 回落
        assertThat(NotionMarkdown.fileNameFromURL("https://s3.example.com", "pdf")).isEqualTo("s3.example.com");
        assertThat(NotionMarkdown.fileNameFromURL("https://s3.example.com/", "pdf")).isEqualTo("pdf");
        assertThat(NotionMarkdown.fileNameFromURL("https://s3.example.com?x=1", "audio"))
                .isEqualTo("s3.example.com");
        assertThat(NotionMarkdown.fileNameFromURL("https://s3.example.com/a%20b.pdf", "file"))
                .isEqualTo("a%20b.pdf");
        assertThat(NotionMarkdown.fileNameFromURL("img.png", "image")).isEqualTo("image");
        assertThat(NotionMarkdown.fileNameFromURL("/", "image")).isEqualTo("image");
        assertThat(NotionMarkdown.fileNameFromURL(null, "image")).isEqualTo("image");
    }

    @Test
    void mimeTypeForAttachment() {
        assertThat(NotionMarkdown.mimeTypeForAttachment("image")).isEqualTo("image/png");
        assertThat(NotionMarkdown.mimeTypeForAttachment("pdf")).isEqualTo("application/pdf");
        assertThat(NotionMarkdown.mimeTypeForAttachment("video")).isEqualTo("video/mp4");
        assertThat(NotionMarkdown.mimeTypeForAttachment("audio")).isEqualTo("audio/mpeg");
        assertThat(NotionMarkdown.mimeTypeForAttachment("file")).isEqualTo("application/octet-stream");
        assertThat(NotionMarkdown.mimeTypeForAttachment("")).isEqualTo("application/octet-stream");
    }

    @Test
    void isFileBlockCoversFiveTypes() {
        for (String type : List.of("image", "file", "pdf", "video", "audio")) {
            assertThat(NotionMarkdown.isFileBlock(type)).as(type).isTrue();
        }
        assertThat(NotionMarkdown.isFileBlock("paragraph")).isFalse();
        assertThat(NotionMarkdown.isFileBlock("")).isFalse();
        assertThat(NotionMarkdown.isFileBlock(null)).isFalse();
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────

    private static String render(String richTextJson) {
        return NotionMarkdown.renderRichText(List.of(parseRichText(richTextJson)));
    }

    private static String richText(String type, String inner) {
        return "{\"type\":\"" + type + "\"," + inner + "}";
    }

    private static NotionRichText parseRichText(String json) {
        try {
            return NotionJson.MAPPER.readValue(json, NotionRichText.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
