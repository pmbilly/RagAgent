package com.ragagent.datasource.connector.feishu.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.datasource.connector.feishu.core.DocxBlocks.BlockText;
import com.ragagent.datasource.connector.feishu.core.DocxBlocks.DocxBlock;

/**
 * docx 块 → Markdown 渲染器（对照 Go {@code core/markdown.go} 全文）。
 *
 * <h2>它做三件事</h2>
 * <ol>
 *   <li>把扁平的块数组按类型渲染成 Markdown（标题/列表/代码/引用/待办/分割线）；</li>
 *   <li><b>内联</b>内嵌表格（sheet / bitable）与原生 table 块；</li>
 *   <li>收集可下载的附件（{@link PendingAttachment}），由连接器决定白名单与大小过滤
 *       ——过滤<b>不</b>在这一层做。</li>
 * </ol>
 *
 * <h2>两条容易写错的规则</h2>
 * <ul>
 *   <li><b>{@code consumed} 集合</b>：原生表格的单元格及其内容块由表格渲染器负责输出，
 *       扁平循环必须跳过，否则同一段文字会既在表格里、又以散段落出现一次。
 *       但<b>附件/图片块不能被 consume</b>——表格渲染器只抽文本，若把
 *       File/Image 也吞掉，内嵌附件就悄无声息地消失了。</li>
 *   <li><b>图片占位不含 token</b>：{@code ![图片]()}——图片没有可检索文本，
 *       泄漏内部 media token 会污染 embedding。</li>
 * </ul>
 *
 * <h2>与 Go 的签名差异</h2>
 * <p>Go 的 {@code blocksToMarkdown} 返回 {@code ([]byte, []pendingAttachment, error)}，
 * 但实现里<b>所有分支返回的都 是 nil 错误</b>（表格读取失败在 {@link #inlineTable}
 * 内就降解成占位文案了）。Java 侧去掉那个永远为 nil 的 error，只保留两个真实返回值。</p>
 */
public final class DocxMarkdown {

    /**
     * 对照 Go 的 {@code sheetReader} 接口：{@code blocksToMarkdown} 只需要客户端这两件事，
     * 转换器才能用假实现（或 nil）单测。{@link FeishuClient} 实现了它。
     */
    public interface SheetReader {

        FeishuClient.SheetRange readSheetRange(String embedToken);

        FeishuClient.BitableTable readBitableRecords(String embedToken);
    }

    /** 对照 Go {@code pendingAttachment}：等待连接器决定是否下载的内嵌文件块。 */
    public record PendingAttachment(String fileToken, String name) {

        public PendingAttachment {
            fileToken = fileToken == null ? "" : fileToken;
            name = name == null ? "" : name;
        }
    }

    /** 对照 Go {@code blocksToMarkdown} 的 {@code (md, atts)}。 */
    public record MarkdownResult(byte[] markdown, List<PendingAttachment> attachments) {

        /** 便利：按 UTF-8 解码成字符串（Go 测试里的 {@code string(md)}）。 */
        public String text() {
            return new String(markdown, StandardCharsets.UTF_8);
        }
    }

    private DocxMarkdown() {
    }

    /**
     * 对照 Go {@code blocksToMarkdown}：把扁平的 docx 块数组渲染成 Markdown，
     * 内嵌 sheet/bitable 表格并收集可下载附件。
     *
     * @param client 可以为 {@code null}——那时块集合里若没有需要下钻的表格块，
     *               渲染照样完成（Go 的注释就是这么约定的）
     */
    public static MarkdownResult blocksToMarkdown(SheetReader client, List<DocxBlock> blocks) {
        List<DocxBlock> safeBlocks = blocks == null ? List.of() : blocks;
        Map<String, DocxBlock> byId = new LinkedHashMap<>();
        for (DocxBlock b : safeBlocks) {
            byId.put(b.getBlockId(), b);
        }
        // 原生表格（它的单元格与其中的内容块）由 renderNativeTable 负责。标记出来，
        // 免得下面的扁平循环把它们当成游离的顶层段落再输出一遍。
        Set<String> consumed = tableDescendants(safeBlocks, byId);

        StringBuilder sb = new StringBuilder();
        List<PendingAttachment> atts = new ArrayList<>();
        for (DocxBlock b : safeBlocks) {
            if (consumed.contains(b.getBlockId())) {
                continue;
            }
            switch (b.getBlockType()) {
                case DocxBlocks.BLOCK_TYPE_PAGE -> {
                    // 根容器，自身没有文本
                }
                case DocxBlocks.BLOCK_TYPE_TEXT -> writePara(sb, plainText(textBearingField(b)));
                case DocxBlocks.BLOCK_TYPE_BULLET -> writePara(sb, "- " + plainText(textBearingField(b)));
                case DocxBlocks.BLOCK_TYPE_ORDERED -> writePara(sb, "1. " + plainText(textBearingField(b)));
                case DocxBlocks.BLOCK_TYPE_CODE -> writePara(sb, "```\n" + plainText(textBearingField(b)) + "\n```");
                case DocxBlocks.BLOCK_TYPE_QUOTE -> writePara(sb, "> " + plainText(textBearingField(b)));
                case DocxBlocks.BLOCK_TYPE_DIVIDER -> writePara(sb, "---");
                case DocxBlocks.BLOCK_TYPE_TABLE -> writePara(sb, renderNativeTable(b, byId));
                case DocxBlocks.BLOCK_TYPE_TABLE_CELL -> {
                    // 由它的父表格渲染（`consumed` 也已覆盖）
                }
                case DocxBlocks.BLOCK_TYPE_SHEET -> {
                    if (b.getSheet() != null) {
                        writePara(sb, inlineTable(client, b.getSheet().token(), "sheet"));
                    }
                }
                case DocxBlocks.BLOCK_TYPE_BITABLE -> {
                    if (b.getBitable() != null) {
                        writePara(sb, inlineTable(client, b.getBitable().token(), "bitable"));
                    }
                }
                case DocxBlocks.BLOCK_TYPE_IMAGE ->
                    // 输出不含 token 的占位：图片没有可检索文本，泄漏内部 media token
                    // 会污染 embedding。中性的标记还能保住上下文（如"如下图所示"）。
                    writePara(sb, "![图片]()");
                case DocxBlocks.BLOCK_TYPE_TODO -> {
                    String t = plainText(textBearingField(b));
                    if (!t.isEmpty()) {
                        writePara(sb, "- [ ] " + t);
                    }
                }
                case DocxBlocks.BLOCK_TYPE_CALLOUT -> {
                    // Callout 是容器；它的正文通常在子块里（另行走渲染）。只有它自带
                    // 直接内联文本时才输出，于是"纯容器"这种情况天然是安全的 no-op。
                    String t = plainText(textBearingField(b));
                    if (!t.isEmpty()) {
                        writePara(sb, "> " + t);
                    }
                }
                case DocxBlocks.BLOCK_TYPE_FILE -> {
                    if (b.getFile() != null) {
                        String name = b.getFile().name() == null ? "" : b.getFile().name();
                        if (name.isEmpty()) {
                            name = b.getFile().token() == null ? "" : b.getFile().token();
                        }
                        writePara(sb, "📎 附件：" + name);
                        atts.add(new PendingAttachment(b.getFile().token(), b.getFile().name()));
                    }
                }
                default -> {
                    if (b.getBlockType() >= DocxBlocks.BLOCK_TYPE_HEADING1
                            && b.getBlockType() <= DocxBlocks.BLOCK_TYPE_HEADING9) {
                        int level = b.getBlockType() - DocxBlocks.BLOCK_TYPE_HEADING1 + 1;
                        writePara(sb, "#".repeat(level) + " " + plainText(textBearingField(b)));
                    }
                }
            }
        }

        String out = stripTrailingNewlines(sb.toString());
        if (!out.isEmpty()) {
            out += "\n";
        }
        return new MarkdownResult(out.getBytes(StandardCharsets.UTF_8), atts);
    }

    /** 对照 Go 的 {@code strings.TrimRight(s, "\n")}。 */
    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\n') {
            end--;
        }
        return s.substring(0, end);
    }

    /** 对照 Go {@code writePara}：追加一段文本 + 一个空行；空文本跳过。 */
    private static void writePara(StringBuilder sb, String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        sb.append(s).append("\n\n");
    }

    /** 对照 Go {@code plainText}：拼接带文本块的各个 run。 */
    public static String plainText(BlockText bt) {
        return bt == null ? "" : bt.plainText();
    }

    /** 对照 Go {@code headingText}：取该级别的 heading 字段，取不到回落到 {@code Text}。 */
    static BlockText headingText(DocxBlock b) {
        // ⚠️ 不能写成 List.of(...)：那些字段大多为 null，而 List.of 拒绝 null（Go 的切片没这问题）
        BlockText[] fields = {
                b.getHeading1(), b.getHeading2(), b.getHeading3(), b.getHeading4(), b.getHeading5(),
                b.getHeading6(), b.getHeading7(), b.getHeading8(), b.getHeading9()};
        int idx = b.getBlockType() - DocxBlocks.BLOCK_TYPE_HEADING1;
        if (idx >= 0 && idx < fields.length && fields[idx] != null) {
            return fields[idx];
        }
        return b.getText();
    }

    /**
     * 对照 Go {@code tableDescendants}：返回属于某个原生表格的块 id 集合——
     * 表格块列出的每个单元格，以及经这些单元格 {@code Children} 能走到的一切。
     * {@code blocksToMarkdown} 跳过它们，于是表格内容只由表格渲染器输出一次。
     */
    static Set<String> tableDescendants(List<DocxBlock> blocks, Map<String, DocxBlock> byId) {
        Set<String> consumed = new HashSet<>();
        for (DocxBlock b : blocks) {
            // 只 consume "我们真的会渲染"的那些表格的单元格。renderNativeTable 会放弃的
            // 表格（缺 property / 零列）绝不能把单元格 consume 掉，否则它们的文本
            // 会被整个丢掉——那种情况留给扁平循环当散段落输出。
            if (b.getBlockType() == DocxBlocks.BLOCK_TYPE_TABLE && tableRenderable(b)) {
                List<String> cells = b.getTable().cells() == null ? List.of() : b.getTable().cells();
                for (String cid : cells) {
                    mark(cid, byId, consumed);
                }
            }
        }
        return consumed;
    }

    private static void mark(String id, Map<String, DocxBlock> byId, Set<String> consumed) {
        DocxBlock b = byId.get(id);
        if (b == null || consumed.contains(id)) {
            return;
        }
        // 单元格里嵌套的附件/媒体块仍然要被主循环收集（并输出引用）——表格渲染器
        // 只抽文本——所以不 consume 它们，只 consume 它们的文本结构。
        if (b.getBlockType() == DocxBlocks.BLOCK_TYPE_FILE
                || b.getBlockType() == DocxBlocks.BLOCK_TYPE_IMAGE) {
            return;
        }
        consumed.add(id);
        for (String c : b.getChildren()) {
            mark(c, byId, consumed);
        }
    }

    /**
     * 对照 Go {@code tableRenderable}：原生表格块是否带够了结构（列数）让
     * {@link #renderNativeTable} 能产出 Markdown 表格。
     *
     * <p>它是 consume 扫描与渲染共用的<b>唯一</b>判据，确保两者对"哪些表格被
     * 表格渲染器接管"永远不会有分歧。</p>
     */
    static boolean tableRenderable(DocxBlock b) {
        return b.getTable() != null && b.getTable().property() != null
                && b.getTable().property().columnSize() > 0;
    }

    /**
     * 对照 Go {@code textBearingField}：按块的类型名取它的内联文本载荷
     * （docx 把块的文本存在与类型同名的字段里），于是任意文本类单元格的内容
     * 都能被统一抽取。
     */
    static BlockText textBearingField(DocxBlock b) {
        switch (b.getBlockType()) {
            case DocxBlocks.BLOCK_TYPE_TEXT:
                return b.getText();
            case DocxBlocks.BLOCK_TYPE_BULLET:
                return b.getBullet();
            case DocxBlocks.BLOCK_TYPE_ORDERED:
                return b.getOrdered();
            case DocxBlocks.BLOCK_TYPE_CODE:
                return b.getCode();
            case DocxBlocks.BLOCK_TYPE_QUOTE:
                return b.getQuote();
            case DocxBlocks.BLOCK_TYPE_TODO:
                return b.getTodo();
            case DocxBlocks.BLOCK_TYPE_CALLOUT:
                return b.getCallout();
            default:
                break;
        }
        if (b.getBlockType() >= DocxBlocks.BLOCK_TYPE_HEADING1
                && b.getBlockType() <= DocxBlocks.BLOCK_TYPE_HEADING9) {
            return headingText(b);
        }
        return b.getText();
    }

    /**
     * 对照 Go {@code cellText}：把一个原生表格单元格渲染成一个字符串。
     *
     * <p>飞书的 table_cell（block_type 32）是<b>容器</b>：文本在子块里、不在单元格上，
     * 所以这里要拼接各子块的文本。</p>
     */
    static String cellText(DocxBlock cell, Map<String, DocxBlock> byId) {
        List<String> parts = new ArrayList<>();
        for (String childId : cell.getChildren()) {
            DocxBlock child = byId.get(childId);
            // Go 里取不到时拿到的是零值 DocxBlock（Text 为 nil）→ 同样得到 ""
            String t = child == null ? "" : plainText(textBearingField(child));
            if (!t.isEmpty()) {
                parts.add(t);
            }
        }
        return String.join(" ", parts);
    }

    /** 对照 Go {@code renderNativeTable}：把原生 table 块渲染成 Markdown 表格。 */
    static String renderNativeTable(DocxBlock b, Map<String, DocxBlock> byId) {
        if (!tableRenderable(b)) {
            return "";
        }
        int cols = b.getTable().property().columnSize();
        List<String> cells = new ArrayList<>();
        for (String cid : b.getTable().cells() == null ? List.<String>of() : b.getTable().cells()) {
            DocxBlock cell = byId.get(cid);
            cells.add(cell == null ? "" : cellText(cell, byId));
        }
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < cells.size(); i += cols) {
            int end = Math.min(i + cols, cells.size());
            rows.add(new ArrayList<>(cells.subList(i, end)));
        }
        return markdownTable(rows);
    }

    /**
     * 对照 Go {@code markdownTable}：把 {@code [][]string}（首行是表头）渲染成 GFM 表格。
     *
     * <p>零列表头（没有列的内嵌 sheet/bitable）会输出 {@code "|  |"} 表头 + 只有 {@code "|"}
     * 的分隔行——那是<b>畸形 GFM</b>。这种情况什么都不渲染。</p>
     */
    public static String markdownTable(List<List<String>> rows) {
        if (rows == null || rows.isEmpty() || rows.get(0) == null || rows.get(0).isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int cols = rows.get(0).size();
        sb.append("| ").append(String.join(" | ", escapePipes(rows.get(0)))).append(" |\n");
        sb.append("|").append(" --- |".repeat(cols)).append("\n");
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = new ArrayList<>(rows.get(i) == null ? List.<String>of() : rows.get(i));
            // 参差的行：比表头宽的行会输出多于表头/分隔行的单元格，产出畸形 GFM。
            // 夹到表头宽度（溢出截掉、不足补空）。
            if (r.size() > cols) {
                r = new ArrayList<>(r.subList(0, cols));
            }
            while (r.size() < cols) {
                r.add("");
            }
            sb.append("| ").append(String.join(" | ", escapePipes(r))).append(" |\n");
        }
        return stripTrailingNewlines(sb.toString());
    }

    /** 对照 Go {@code escapePipes}：让单元格值在一行 Markdown 表格里安全。 */
    static List<String> escapePipes(List<String> row) {
        List<String> out = new ArrayList<>(row.size());
        for (String c : row) {
            out.add((c == null ? "" : c).replace("\n", " ").replace("|", "\\|"));
        }
        return out;
    }

    /**
     * 对照 Go {@code inlineTable}：读一个内嵌 sheet/bitable 并渲染成 Markdown 表格。
     *
     * <p>读/权限错误降解成一句内联说明，而不是让整篇文档失败（部分内容胜过没有内容）。
     * 读取方报告的截断会追加一句说明。</p>
     */
    static String inlineTable(SheetReader client, String token, String kind) {
        if (client == null) {
            return "";
        }
        List<List<String>> rows;
        boolean truncated;
        String noun = "sheet".equals(kind) ? "内嵌电子表格" : "内嵌多维表格";
        try {
            if ("sheet".equals(kind)) {
                FeishuClient.SheetRange r = client.readSheetRange(token);
                rows = r.rows();
                truncated = r.truncated();
            } else {
                FeishuClient.BitableTable r = client.readBitableRecords(token);
                rows = r.rows();
                truncated = r.truncated();
            }
        } catch (RuntimeException e) {
            return "> [无法读取" + noun + "]";
        }
        // markdownTable 在"没东西可渲染"（没有行、或表头没有列）时返回 ""。
        // 那种情况下截断说明也要一起跳过，否则它会孤零零地悬在空处。
        String table = markdownTable(rows);
        if (table.isEmpty()) {
            return "";
        }
        if (truncated) {
            table += "\n\n> 表格已截断（仅显示前 " + DocxBlocks.MAX_TABLE_ROWS + " 行）";
        }
        return table;
    }
}
