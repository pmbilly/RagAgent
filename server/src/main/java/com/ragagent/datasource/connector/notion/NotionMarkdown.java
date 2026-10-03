package com.ragagent.datasource.connector.notion;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.datasource.ConnectorException;

/**
 * 块树 → Markdown。
 *
 * <p>这一块的输出是**逐字符契约**：它直接变成知识条目的正文（{@code Content}），
 * 所以 {@code # }、{@code \n\n}、{@code | }、{@code ---|}、{@code <br>}、
 * {@code ## X 内容}、{@code - **name**: val} 这类拼法必须与既有数据一字不差。
 * 期望值由 {@code NotionMarkdownTest} 钉住。</p>
 *
 * <h2>三处刻意的"怪"行为</h2>
 * <ol>
 *   <li><b>空输出的页面拿到的是 {@code "\n"}</b>：转换恒在
 *       末尾补一个 {@code "\n"}，即使正文为空。上游用
 *       "去空白后非空"判"页面是否为空"，
 *       所以这个 {@code "\n"} 不会造成空条目。</li>
 *   <li><b>连续换行折叠是"循环替换到没有为止"</b>：{@code 5} 个换行会先被折成
 *       {@code 3} 个、再折成 {@code 2} 个（{@code replace} 一次替换全部
 *       不重叠出现，循环条件保留）。</li>
 *   <li><b>列表间距靠 {@code inList} 状态机</b>：列表项之间不空行，列表结束
 *       （下一个块不是列表）或列表收尾时补一个 {@code "\n"}——于是列表块
 *       与后续段落之间是**一个**空行。</li>
 * </ol>
 *
 * <h2>与 Java 默认 API 的两处不同（必须显式处理）</h2>
 * <ul>
 *   <li>去空白用 {@link NotionValues#trimSpace}（含 U+00A0 等）；</li>
 *   <li>按 {@code \n} 切分要用 {@code text.split("\n", -1)}
 *       （默认会**丢掉尾随空串**）。</li>
 * </ul>
 */
final class NotionMarkdown {

    private NotionMarkdown() {
    }

    /** 转换结果：Markdown 文本 + 收集到的附件。 */
    static final class Result {
        final String markdown;
        final List<NotionAttachment> attachments;

        Result(String markdown, List<NotionAttachment> attachments) {
            this.markdown = markdown;
            this.attachments = attachments;
        }
    }

    /**
     * 把块树渲染成 Markdown。
     *
     * <p>末尾三步的顺序是契约：先折叠连续换行 → 再去空白 →
     * 最后补 {@code "\n"}。</p>
     */
    static Result blocksToMarkdown(List<NotionBlock> blocks) {
        StringBuilder b = new StringBuilder();
        List<NotionAttachment> attachments = new ArrayList<>();
        renderBlocks(b, blocks, 0, attachments);

        String result = b.toString();
        while (result.contains("\n\n\n")) {
            result = result.replace("\n\n\n", "\n\n");
        }
        return new Result(NotionValues.trimSpace(result) + "\n", attachments);
    }

    /** 渲染块列表（维护列表间距状态机）。 */
    static void renderBlocks(StringBuilder b, List<NotionBlock> blocks, int depth,
                             List<NotionAttachment> attachments) {
        if (blocks == null) {
            return;
        }
        boolean inList = false;

        for (int i = 0; i < blocks.size(); i++) {
            NotionBlock block = blocks.get(i);
            String type = block.type();
            boolean isList = "bulleted_list_item".equals(type)
                    || "numbered_list_item".equals(type)
                    || "to_do".equals(type);

            if (inList && !isList) {
                b.append('\n');
                inList = false;
            }

            renderBlock(b, block, depth, attachments, i, blocks);

            if (isList) {
                inList = true;
            }
        }

        if (inList) {
            b.append('\n');
        }
    }

    /** 渲染单个块（每一种 case 的输出都是逐字符契约）。 */
    static void renderBlock(StringBuilder b, NotionBlock block, int depth,
                            List<NotionAttachment> attachments, int index,
                            List<NotionBlock> siblings) {
        String indent = indent(depth);
        String type = block.type();
        List<NotionBlock> children = block.children;

        switch (type) {
            case "paragraph": {
                String text = renderRichText(extractRichText(block.rawContent));
                b.append(indent).append(text).append("\n\n");
                break;
            }
            case "heading_1":
            case "heading_2":
            case "heading_3":
            case "heading_4": {
                int level = type.charAt(type.length() - 1) - '0';
                List<NotionRichText> rt = extractRichText(block.rawContent);
                b.append("#".repeat(level)).append(' ')
                        .append(renderRichText(rt)).append("\n\n");
                if (hasChildren(children)) {
                    renderBlocks(b, children, depth, attachments);
                }
                break;
            }
            case "bulleted_list_item": {
                List<NotionRichText> rt = extractRichText(block.rawContent);
                b.append(indent).append("- ").append(renderRichText(rt)).append('\n');
                if (hasChildren(children)) {
                    renderBlocks(b, children, depth + 1, attachments);
                }
                break;
            }
            case "numbered_list_item": {
                List<NotionRichText> rt = extractRichText(block.rawContent);
                // 连续的 numbered_list_item 里，序号是"往前数同类兄弟"。
                int num = 1;
                for (int j = index - 1; j >= 0 && siblings.get(j).type()
                        .equals("numbered_list_item"); j--) {
                    num++;
                }
                b.append(indent).append(num).append(". ")
                        .append(renderRichText(rt)).append('\n');
                if (hasChildren(children)) {
                    renderBlocks(b, children, depth + 1, attachments);
                }
                break;
            }
            case "to_do": {
                List<NotionRichText> rt = extractRichText(block.rawContent);
                boolean checked = extractBool(block.rawContent, "checked");
                String checkbox = checked ? "[x]" : "[ ]";
                b.append(indent).append("- ").append(checkbox).append(' ')
                        .append(renderRichText(rt)).append('\n');
                break;
            }
            case "toggle": {
                List<NotionRichText> rt = extractRichText(block.rawContent);
                b.append("<details><summary>").append(renderRichText(rt))
                        .append("</summary>\n\n");
                if (hasChildren(children)) {
                    renderBlocks(b, children, depth, attachments);
                }
                b.append("</details>\n\n");
                break;
            }
            case "code": {
                List<NotionRichText> rt = extractRichText(block.rawContent);
                String lang = extractString(block.rawContent, "language");
                b.append("```").append(lang).append('\n')
                        .append(renderRichText(rt)).append("\n```\n\n");
                break;
            }
            case "quote":
            case "meeting_notes": {
                List<NotionRichText> rt = extractRichText(block.rawContent);
                String text = renderRichText(rt);
                for (String line : text.split("\n", -1)) {
                    b.append("> ").append(line).append('\n');
                }
                b.append('\n');
                if (hasChildren(children)) {
                    renderBlocks(b, children, depth, attachments);
                }
                break;
            }
            case "callout": {
                List<NotionRichText> rt = extractRichText(block.rawContent);
                String icon = extractIcon(block.rawContent);
                String prefix = icon.isEmpty() ? "" : icon + " ";
                b.append("> ").append(prefix).append(renderRichText(rt)).append("\n\n");
                if (hasChildren(children)) {
                    renderBlocks(b, children, depth, attachments);
                }
                break;
            }
            case "divider":
                b.append("---\n\n");
                break;
            case "equation": {
                String expr = extractString(block.rawContent, "expression");
                b.append("$$").append(expr).append("$$\n\n");
                break;
            }
            case "table":
                renderTable(b, block);
                break;
            case "image": {
                FileAndCaption fc = extractFileAndCaption(block.rawContent);
                String url = fc.file.url();
                b.append("![").append(fc.caption).append("](").append(url).append(")\n\n");
                if (!url.isEmpty()) {
                    attachments.add(new NotionAttachment(
                            url, fileNameFromURL(url, "image"), "image"));
                }
                break;
            }
            case "file":
            case "pdf":
            case "video":
            case "audio":
                renderMediaBlock(b, block, attachments);
                break;
            case "bookmark":
            case "link_preview": {
                String url = extractString(block.rawContent, "url");
                String caption = extractCaptionText(block.rawContent);
                if (caption.isEmpty()) {
                    caption = url;
                }
                b.append('[').append(caption).append("](").append(url).append(")\n\n");
                break;
            }
            case "embed": {
                String url = extractString(block.rawContent, "url");
                b.append('[').append(url).append("](").append(url).append(")\n\n");
                break;
            }
            case "link_to_page": {
                // 页面标题在块数据里拿不到，只能渲染成链接
                String pageId = extractLinkToPageID(block.rawContent);
                b.append("[Page ").append(pageId).append("](https://notion.so/")
                        .append(pageId.replace("-", "")).append(")\n\n");
                break;
            }
            case "synced_block":
                if (hasChildren(children)) {
                    renderBlocks(b, children, depth, attachments);
                }
                break;
            case "column_list":
                if (hasChildren(children)) {
                    for (NotionBlock col : children) {
                        if (hasChildren(col.children)) {
                            renderBlocks(b, col.children, depth, attachments);
                        }
                    }
                }
                break;
            case "column":
                if (hasChildren(children)) {
                    renderBlocks(b, children, depth, attachments);
                }
                break;
            case "tab_list":
                if (hasChildren(children)) {
                    for (NotionBlock tab : children) {
                        if (hasChildren(tab.children)) {
                            renderBlocks(b, tab.children, depth, attachments);
                        }
                    }
                }
                break;
            case "tab":
                if (hasChildren(children)) {
                    renderBlocks(b, children, depth, attachments);
                }
                break;
            case "child_page": {
                // 父页面里渲染成链接；子页面本身由 connector 层抓成独立条目
                String title = extractString(block.rawContent, "title");
                if (title.isEmpty()) {
                    title = "Untitled";
                }
                b.append("- [").append(title).append("](https://notion.so/")
                        .append(block.id().replace("-", "")).append(")\n");
                break;
            }
            case "child_database": {
                String title = extractString(block.rawContent, "title");
                if (title.isEmpty()) {
                    title = "Database";
                }
                b.append("- [").append(title).append("](https://notion.so/")
                        .append(block.id().replace("-", "")).append(")\n");
                break;
            }
            case "table_of_contents":
            case "breadcrumb":
            case "template":
            case "table_row":
            case "unsupported":
                // 跳过——没有有意义的内容
                break;
            default:
                // 未知块类型静默跳过（向前兼容）
                break;
        }
    }

    /** 渲染媒体块："[名字](链接)" + 收集附件。 */
    static void renderMediaBlock(StringBuilder b, NotionBlock block,
                                 List<NotionAttachment> attachments) {
        FileAndCaption fc = extractFileAndCaption(block.rawContent);
        String url = fc.file.url();
        String name = fc.file.name == null ? "" : fc.file.name;
        String type = block.type();
        if (name.isEmpty()) {
            name = fileNameFromURL(url, type);
        }
        b.append('[').append(name).append("](").append(url).append(")\n\n");
        if (!url.isEmpty()) {
            attachments.add(new NotionAttachment(url, name, type));
        }
    }

    /**
     * 表格渲染：第一行后面补分隔行，单元格里的 {@code |}
     * 转义成 {@code \|}。没有子块时**什么都不输出**（连空行都不补）。
     */
    static void renderTable(StringBuilder b, NotionBlock block) {
        List<NotionBlock> children = block.children;
        if (children == null || children.isEmpty()) {
            return;
        }
        for (int i = 0; i < children.size(); i++) {
            List<List<NotionRichText>> cells = extractTableCells(children.get(i).rawContent);
            List<String> parts = new ArrayList<>();
            for (List<NotionRichText> cell : cells) {
                parts.add(renderRichText(cell).replace("|", "\\|"));
            }
            b.append("| ").append(String.join(" | ", parts)).append(" |\n");

            if (i == 0) {
                List<String> sep = new ArrayList<>();
                for (int k = 0; k < parts.size(); k++) {
                    sep.add("---");
                }
                b.append("| ").append(String.join(" | ", sep)).append(" |\n");
            }
        }
        b.append('\n');
    }

    // ──────────────────────────────────────────────────────────────────────
    // 富文本渲染
    // ──────────────────────────────────────────────────────────────────────

    /** 渲染富文本片段序列。 */
    static String renderRichText(List<NotionRichText> texts) {
        if (texts == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (NotionRichText rt : texts) {
            String text = richTextToString(rt);
            text = applyAnnotations(text, rt.annotations());
            // 注意：`code` 样式下**不**加链接包装（annotations.code 为真时）
            if (!rt.href().isEmpty() && !rt.annotations().code) {
                text = "[" + text + "](" + rt.href() + ")";
            }
            b.append(text);
        }
        return b.toString();
    }

    /** 拼接富文本为纯串（含 mention/equation 等分支）。 */
    static String richTextToString(NotionRichText rt) {
        switch (rt.type()) {
            case "text":
                if (rt.text != null) {
                    return rt.text.content();
                }
                return rt.plainText();

            case "mention":
                if (rt.mention == null) {
                    return rt.plainText();
                }
                switch (rt.mention.type()) {
                    case "date":
                        if (rt.mention.date != null) {
                            if (!rt.mention.date.end().isEmpty()) {
                                return rt.mention.date.start() + " → "
                                        + rt.mention.date.end();
                            }
                            return rt.mention.date.start();
                        }
                        break;
                    case "page":
                        if (rt.mention.page != null) {
                            // Notion 会把页面标题填进 PlainText
                            return rt.plainText();
                        }
                        break;
                    case "database":
                    case "data_source":
                        if (rt.mention.database != null) {
                            return rt.plainText();
                        }
                        break;
                    case "link_preview":
                        if (rt.mention.linkPreview != null) {
                            return rt.mention.linkPreview.url();
                        }
                        break;
                    default:
                        break;
                }
                return rt.plainText();

            case "equation":
                if (rt.equation != null) {
                    return "$" + rt.equation.expression() + "$";
                }
                return rt.plainText();

            default:
                return rt.plainText();
        }
    }

    /**
     * **空文本原样返回**（不套任何标记）——
     * 所以一个空的加粗片段渲染成 {@code ""} 而不是 {@code "****"}（probe 已钉住）。
     * 包裹顺序由内到外是 code → bold/italic → strikethrough → underline。
     */
    static String applyAnnotations(String text, NotionAnnotations ann) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        NotionAnnotations a = ann == null ? NotionAnnotations.EMPTY : ann;
        if (a.code) {
            text = "`" + text + "`";
        }
        if (a.bold && a.italic) {
            text = "***" + text + "***";
        } else if (a.bold) {
            text = "**" + text + "**";
        } else if (a.italic) {
            text = "*" + text + "*";
        }
        if (a.strikethrough) {
            text = "~~" + text + "~~";
        }
        if (a.underline) {
            text = "<u>" + text + "</u>";
        }
        return text;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 原始内容抽取
    // ──────────────────────────────────────────────────────────────────────

    /** 取 {@code rich_text} 数组；{@code raw} 为 null 或不是对象 → {@code null}。 */
    static List<NotionRichText> extractRichText(JsonNode raw) {
        if (raw == null) {
            return null;
        }
        if (!raw.isObject()) {
            return null;
        }
        JsonNode richText = raw.get("rich_text");
        if (richText == null || !richText.isArray()) {
            return null;
        }
        List<NotionRichText> out = new ArrayList<>();
        for (JsonNode node : richText) {
            out.add(NotionJson.MAPPER.convertValue(node, NotionRichText.class));
        }
        return out;
    }

    /**
     * 取字符串字段：raw 不是对象、键缺席、或值不是字符串时都回
     * {@code ""}。
     */
    static String extractString(JsonNode raw, String key) {
        if (raw == null || !raw.isObject()) {
            return "";
        }
        JsonNode val = raw.get(key);
        if (val == null || !val.isTextual()) {
            return "";
        }
        return val.textValue();
    }

    /** 取布尔字段；取不到回 {@code false}。 */
    static boolean extractBool(JsonNode raw, String key) {
        if (raw == null || !raw.isObject()) {
            return false;
        }
        JsonNode val = raw.get(key);
        if (val == null || !val.isBoolean()) {
            return false;
        }
        return val.booleanValue();
    }

    /**
     * 只有 {@code icon.type == "emoji"} 才回 emoji，
     * 其它类型（含 {@code "file"}）回 {@code ""}——file 型图标
     * 的 callout 渲染成 {@code "> img"} 而不是 {@code "> url img"}。
     */
    static String extractIcon(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return "";
        }
        JsonNode icon = raw.get("icon");
        if (icon == null || !icon.isObject()) {
            return "";
        }
        JsonNode typeNode = icon.get("type");
        if (typeNode == null || !typeNode.isTextual()
                || !"emoji".equals(typeNode.textValue())) {
            return "";
        }
        JsonNode emoji = icon.get("emoji");
        return emoji != null && emoji.isTextual() ? emoji.textValue() : "";
    }

    /** 文件对象 + 渲染后的 caption。 */
    static final class FileAndCaption {
        final NotionFile file;
        final String caption;

        FileAndCaption(NotionFile file, String caption) {
            this.file = file;
            this.caption = caption;
        }
    }

    /** 解出文件对象并渲染 caption。 */
    static FileAndCaption extractFileAndCaption(JsonNode raw) {
        NotionFile file = new NotionFile();
        if (raw != null) {
            try {
                NotionFile parsed = NotionJson.MAPPER.convertValue(raw, NotionFile.class);
                if (parsed != null) {
                    file = parsed;
                }
            } catch (RuntimeException e) {
                file = new NotionFile();
            }
        }
        String captionText = "";
        if (file.caption != null && !file.caption.isEmpty()) {
            captionText = renderRichText(file.caption);
        }
        return new FileAndCaption(file, captionText);
    }

    /** 渲染 {@code caption} 数组为纯串。 */
    static String extractCaptionText(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return "";
        }
        JsonNode caption = raw.get("caption");
        if (caption == null || !caption.isArray() || caption.isEmpty()) {
            return "";
        }
        List<NotionRichText> texts = new ArrayList<>();
        for (JsonNode node : caption) {
            texts.add(NotionJson.MAPPER.convertValue(node, NotionRichText.class));
        }
        return renderRichText(texts);
    }

    /** 解出表格单元格的富文本行列。 */
    static List<List<NotionRichText>> extractTableCells(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return new ArrayList<>();
        }
        JsonNode cells = raw.get("cells");
        if (cells == null || !cells.isArray()) {
            return new ArrayList<>();
        }
        List<List<NotionRichText>> out = new ArrayList<>();
        for (JsonNode row : cells) {
            List<NotionRichText> rowOut = new ArrayList<>();
            if (row != null && row.isArray()) {
                for (JsonNode cell : row) {
                    rowOut.add(NotionJson.MAPPER.convertValue(cell, NotionRichText.class));
                }
            }
            out.add(rowOut);
        }
        return out;
    }

    /** 取 {@code link_to_page.page_id}；取不到回空串。 */
    static String extractLinkToPageID(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return "";
        }
        JsonNode pageId = raw.get("page_id");
        return pageId != null && pageId.isTextual() ? pageId.textValue() : "";
    }

    /**
     * 从 URL 提取文件名：先砍查询串，再取最后一个 {@code /} 之后的部分；
     * 拿不到就回 {@code fallbackType}。
     */
    static String fileNameFromURL(String url, String fallbackType) {
        if (url == null || url.isEmpty()) {
            return fallbackType;
        }
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            String name = path.substring(slash + 1);
            if (!name.isEmpty()) {
                return name;
            }
        }
        return fallbackType;
    }

    /** 附件类型 → MIME。 */
    static String mimeTypeForAttachment(String attType) {
        switch (attType == null ? "" : attType) {
            case "image":
                return "image/png";
            case "pdf":
                return "application/pdf";
            case "video":
                return "video/mp4";
            case "audio":
                return "audio/mpeg";
            default:
                return "application/octet-stream";
        }
    }

    /** 只有这五种块才可能带 file_upload。 */
    static boolean isFileBlock(String blockType) {
        switch (blockType == null ? "" : blockType) {
            case "image":
            case "file":
            case "pdf":
            case "video":
            case "audio":
                return true;
            default:
                return false;
        }
    }

    /** {@code strings.Repeat("  ", depth)}。 */
    static String indent(int depth) {
        return "  ".repeat(Math.max(depth, 0));
    }

    private static boolean hasChildren(List<NotionBlock> children) {
        return children != null && !children.isEmpty();
    }

    /** 供 {@code resolveFileUploads} 使用：解析 block 的 RawContent 成 {@link NotionFile}。 */
    static NotionFile parseFile(JsonNode rawContent) {
        if (rawContent == null) {
            return new NotionFile();
        }
        try {
            NotionFile file = NotionJson.MAPPER.convertValue(rawContent, NotionFile.class);
            return file == null ? new NotionFile() : file;
        } catch (RuntimeException e) {
            throw new ConnectorException("unmarshal file: " + e.getMessage(), e);
        }
    }
}
