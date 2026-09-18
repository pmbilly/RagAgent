package com.ragagent.datasource.connector.feishu.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 飞书 docx 块结构（对照 Go {@code core/blocks.go} 的类型定义与纯函数段）。
 *
 * <h2>⚠️ 内部 API 形状，不是契约</h2>
 * <p>这些类型只在"解析 blocks API 的响应并渲染 Markdown"这条链路上存在：
 * 从不落 jsonb、从不进 HTTP 响应。所以不做本项目的契约治理
 * （约定 §7.5 第 3 条：Map 序列化器 / {@code @JsonIgnore} / 往返测试条目都不需要）。
 * 键名仍照抄飞书的线上协议。</p>
 *
 * <h2>为什么 {@link DocxBlock} 是可变类而不是 record</h2>
 * <p>它有 20 个字段、且文本按类型分散在 {@code text}/{@code heading1..9}/{@code bullet}
 * … 上——与 Go 的结构体逐字段对应。record 的 20 参构造器会让测试完全无法阅读，
 * 故照抄 Go 的"可变结构体 + 按需赋值"。其余小结构用 record。</p>
 */
public final class DocxBlocks {

    // ── block_type 整数枚举（本连接器处理的那一部分） ──────────────────────

    /** 页面根块。 */
    public static final int BLOCK_TYPE_PAGE = 1;

    public static final int BLOCK_TYPE_TEXT = 2;
    public static final int BLOCK_TYPE_HEADING1 = 3;

    /** 标题 9 是连续标题块的最后一个（3..11）。 */
    public static final int BLOCK_TYPE_HEADING9 = 11;

    public static final int BLOCK_TYPE_BULLET = 12;
    public static final int BLOCK_TYPE_ORDERED = 13;
    public static final int BLOCK_TYPE_CODE = 14;
    public static final int BLOCK_TYPE_QUOTE = 15;
    public static final int BLOCK_TYPE_TODO = 17;
    public static final int BLOCK_TYPE_BITABLE = 18;
    public static final int BLOCK_TYPE_CALLOUT = 19;
    public static final int BLOCK_TYPE_DIVIDER = 22;
    public static final int BLOCK_TYPE_FILE = 23;
    public static final int BLOCK_TYPE_IMAGE = 27;
    public static final int BLOCK_TYPE_SHEET = 30;
    public static final int BLOCK_TYPE_TABLE = 31;
    public static final int BLOCK_TYPE_TABLE_CELL = 32;

    /**
     * 对照 Go {@code maxDocumentBlocks}：单文档贡献的 block 数上限，
     * 防病态/对抗性文档。远高于任何真实飞书文档。
     */
    public static final int MAX_DOCUMENT_BLOCKS = 50000;

    /**
     * 对照 Go {@code maxTableRows}：内嵌表格最多渲染多少行，保护分块器不被
     * 超大表格拖垮。超过则截断，并由调用方补一句说明。
     */
    public static final int MAX_TABLE_ROWS = 500;

    /**
     * 对照 Go {@code bitableFieldTypeDateTime}：多维表格里"日期/日期时间"列的字段类型。
     *
     * <p>它的单元格值是裸的 Unix <b>毫秒数</b>，不做类型感知格式化就会渲染成
     * 13 位整数而不是可读日期。</p>
     */
    public static final int BITABLE_FIELD_TYPE_DATE_TIME = 5;

    /** 对照 Go {@code maxBitableFieldPageSize}：列字段接口的每页上限（100）。 */
    public static final int MAX_BITABLE_FIELD_PAGE_SIZE = 100;

    private DocxBlocks() {
    }

    // ──────────────────────────────────────────────────────────────────
    // 块载荷
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code TextRun}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TextRun(@JsonProperty("content") String content) {
    }

    /** 对照 Go {@code TextElement}：一个内联 run。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TextElement(@JsonProperty("text_run") TextRun textRun) {
    }

    /** 对照 Go {@code BlockText}：所有带文本的块的公共载荷。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BlockText(@JsonProperty("elements") List<TextElement> elements) {

        /** 测试与构造便利：单 run 的文本块。 */
        public static BlockText of(String content) {
            return new BlockText(List.of(new TextElement(new TextRun(content))));
        }

        /** 对照 Go {@code plainText}：把所有 run 的 content 拼起来。 */
        public String plainText() {
            if (elements == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (TextElement e : elements) {
                if (e != null && e.textRun() != null && e.textRun().content() != null) {
                    sb.append(e.textRun().content());
                }
            }
            return sb.toString();
        }
    }

    /** 对照 Go {@code BlockTokenRef}：sheet/bitable/image 三种块的共同形状。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BlockTokenRef(@JsonProperty("token") String token) {
    }

    /** 对照 Go {@code BlockFileRef}：文件块载荷（附件 token + 名字）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BlockFileRef(
            @JsonProperty("token") String token,
            @JsonProperty("name") String name) {
    }

    /** 对照 Go {@code BlockTableProperty}：表格网格形状。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BlockTableProperty(@JsonProperty("column_size") int columnSize) {
    }

    /** 对照 Go {@code BlockTable}：单元格 block id 列表 + 网格属性。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BlockTable(
            @JsonProperty("cells") List<String> cells,
            @JsonProperty("property") BlockTableProperty property) {
    }

    /**
     * 对照 Go {@code DocxBlock}：blocks API 返回的扁平数组里的一个节点。
     *
     * <p>扁平的、<b>先序</b>排列；层级只通过 {@code children} / {@code parent_id} 表达，
     * 表格单元格是容器（它自己的文本在子块里）。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DocxBlock {

        @JsonProperty("block_id")
        private String blockId = "";

        @JsonProperty("parent_id")
        private String parentId = "";

        @JsonProperty("block_type")
        private int blockType;

        @JsonProperty("children")
        private List<String> children = new ArrayList<>();

        @JsonProperty("text")
        private BlockText text;

        @JsonProperty("heading1")
        private BlockText heading1;

        @JsonProperty("heading2")
        private BlockText heading2;

        @JsonProperty("heading3")
        private BlockText heading3;

        @JsonProperty("heading4")
        private BlockText heading4;

        @JsonProperty("heading5")
        private BlockText heading5;

        @JsonProperty("heading6")
        private BlockText heading6;

        @JsonProperty("heading7")
        private BlockText heading7;

        @JsonProperty("heading8")
        private BlockText heading8;

        @JsonProperty("heading9")
        private BlockText heading9;

        @JsonProperty("bullet")
        private BlockText bullet;

        @JsonProperty("ordered")
        private BlockText ordered;

        @JsonProperty("code")
        private BlockText code;

        @JsonProperty("quote")
        private BlockText quote;

        @JsonProperty("todo")
        private BlockText todo;

        @JsonProperty("callout")
        private BlockText callout;

        @JsonProperty("sheet")
        private BlockTokenRef sheet;

        @JsonProperty("bitable")
        private BlockTokenRef bitable;

        @JsonProperty("file")
        private BlockFileRef file;

        @JsonProperty("image")
        private BlockTokenRef image;

        @JsonProperty("table")
        private BlockTable table;

        public DocxBlock() {
        }

        /** 便利构造：只有 id 与类型的块（测试最常用）。 */
        public static DocxBlock of(String blockId, int blockType) {
            DocxBlock b = new DocxBlock();
            b.blockId = blockId;
            b.blockType = blockType;
            return b;
        }

        /**
         * 便利构造：带文本的块。
         *
         * <p>docx 把文本存在<b>与类型同名</b>的字段里（bullet → {@code bullet}），
         * 所以这里按 block_type 放到正确的字段——写在 {@code text} 上会让
         * {@code textBearingField} 取不到（Go 的测试也是按字段名构造的）。</p>
         */
        public static DocxBlock text(String blockId, int blockType, String content) {
            DocxBlock b = of(blockId, blockType);
            BlockText t = BlockText.of(content);
            switch (blockType) {
                case BLOCK_TYPE_BULLET -> b.bullet = t;
                case BLOCK_TYPE_ORDERED -> b.ordered = t;
                case BLOCK_TYPE_CODE -> b.code = t;
                case BLOCK_TYPE_QUOTE -> b.quote = t;
                case BLOCK_TYPE_TODO -> b.todo = t;
                case BLOCK_TYPE_CALLOUT -> b.callout = t;
                case BLOCK_TYPE_HEADING1 -> b.heading1 = t;
                case BLOCK_TYPE_HEADING1 + 1 -> b.heading2 = t;
                case BLOCK_TYPE_HEADING1 + 2 -> b.heading3 = t;
                case BLOCK_TYPE_HEADING1 + 3 -> b.heading4 = t;
                case BLOCK_TYPE_HEADING1 + 4 -> b.heading5 = t;
                case BLOCK_TYPE_HEADING1 + 5 -> b.heading6 = t;
                case BLOCK_TYPE_HEADING1 + 6 -> b.heading7 = t;
                case BLOCK_TYPE_HEADING1 + 7 -> b.heading8 = t;
                case BLOCK_TYPE_HEADING9 -> b.heading9 = t;
                default -> b.text = t;
            }
            return b;
        }

        /** 便利构造：单元格容器块（子块挂到 {@code id + "_txt"} 上）。 */
        public static DocxBlock cell(String blockId) {
            DocxBlock b = of(blockId, BLOCK_TYPE_TABLE_CELL);
            b.children = new ArrayList<>(List.of(blockId + "_txt"));
            return b;
        }

        /** 便利构造：原生表格块。 */
        public static DocxBlock table(String blockId, int cols, String... cellIds) {
            DocxBlock b = of(blockId, BLOCK_TYPE_TABLE);
            b.table = new BlockTable(new ArrayList<>(List.of(cellIds)), new BlockTableProperty(cols));
            return b;
        }

        public String getBlockId() {
            return blockId;
        }

        public void setBlockId(String v) {
            blockId = v == null ? "" : v;
        }

        public String getParentId() {
            return parentId;
        }

        public void setParentId(String v) {
            parentId = v == null ? "" : v;
        }

        public int getBlockType() {
            return blockType;
        }

        public void setBlockType(int v) {
            blockType = v;
        }

        public List<String> getChildren() {
            return children == null ? List.of() : children;
        }

        public void setChildren(List<String> v) {
            children = v == null ? new ArrayList<>() : v;
        }

        public BlockText getText() {
            return text;
        }

        public void setText(BlockText v) {
            text = v;
        }

        public BlockText getHeading1() {
            return heading1;
        }

        public void setHeading1(BlockText v) {
            heading1 = v;
        }

        public BlockText getHeading2() {
            return heading2;
        }

        public void setHeading2(BlockText v) {
            heading2 = v;
        }

        public BlockText getHeading3() {
            return heading3;
        }

        public void setHeading3(BlockText v) {
            heading3 = v;
        }

        public BlockText getHeading4() {
            return heading4;
        }

        public void setHeading4(BlockText v) {
            heading4 = v;
        }

        public BlockText getHeading5() {
            return heading5;
        }

        public void setHeading5(BlockText v) {
            heading5 = v;
        }

        public BlockText getHeading6() {
            return heading6;
        }

        public void setHeading6(BlockText v) {
            heading6 = v;
        }

        public BlockText getHeading7() {
            return heading7;
        }

        public void setHeading7(BlockText v) {
            heading7 = v;
        }

        public BlockText getHeading8() {
            return heading8;
        }

        public void setHeading8(BlockText v) {
            heading8 = v;
        }

        public BlockText getHeading9() {
            return heading9;
        }

        public void setHeading9(BlockText v) {
            heading9 = v;
        }

        public BlockText getBullet() {
            return bullet;
        }

        public void setBullet(BlockText v) {
            bullet = v;
        }

        public BlockText getOrdered() {
            return ordered;
        }

        public void setOrdered(BlockText v) {
            ordered = v;
        }

        public BlockText getCode() {
            return code;
        }

        public void setCode(BlockText v) {
            code = v;
        }

        public BlockText getQuote() {
            return quote;
        }

        public void setQuote(BlockText v) {
            quote = v;
        }

        public BlockText getTodo() {
            return todo;
        }

        public void setTodo(BlockText v) {
            todo = v;
        }

        public BlockText getCallout() {
            return callout;
        }

        public void setCallout(BlockText v) {
            callout = v;
        }

        public BlockTokenRef getSheet() {
            return sheet;
        }

        public void setSheet(BlockTokenRef v) {
            sheet = v;
        }

        public BlockTokenRef getBitable() {
            return bitable;
        }

        public void setBitable(BlockTokenRef v) {
            bitable = v;
        }

        public BlockFileRef getFile() {
            return file;
        }

        public void setFile(BlockFileRef v) {
            file = v;
        }

        public BlockTokenRef getImage() {
            return image;
        }

        public void setImage(BlockTokenRef v) {
            image = v;
        }

        public BlockTable getTable() {
            return table;
        }

        public void setTable(BlockTable v) {
            table = v;
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // blocks API 响应
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code DocxBlocksData}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocxBlocksData(
            @JsonProperty("items") List<DocxBlock> items,
            @JsonProperty("has_more") boolean hasMore,
            @JsonProperty("page_token") String pageToken) {
    }

    /** 对照 Go {@code DocxBlocksResponse}：{@code GET .../documents/:id/blocks} 的响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocxBlocksResponse(
            @JsonProperty("code") int code,
            @JsonProperty("msg") String msg,
            @JsonProperty("data") DocxBlocksData data) {
    }

    // ──────────────────────────────────────────────────────────────────
    // sheets-v2 取值
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code sheetValueRange}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SheetValueRange(@JsonProperty("values") List<List<Object>> values) {
    }

    /** 对照 Go {@code sheetValuesData}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SheetValuesData(@JsonProperty("valueRange") SheetValueRange valueRange) {
    }

    /** 对照 Go {@code sheetValuesResponse}：sheets-v2 取值接口的响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SheetValuesResponse(
            @JsonProperty("code") int code,
            @JsonProperty("msg") String msg,
            @JsonProperty("data") SheetValuesData data) {
    }

    // ──────────────────────────────────────────────────────────────────
    // bitable-v1 字段与记录
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code bitableColumn}：一列的渲染所需信息。 */
    public record BitableColumn(String name, int fieldType, String dateFormatter) {
        public BitableColumn {
            name = name == null ? "" : name;
            dateFormatter = dateFormatter == null ? "" : dateFormatter;
        }
    }

    /** 对照 Go {@code bitableFieldProperty}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BitableFieldProperty(
            @JsonProperty("date_formatter") String dateFormatter) {
    }

    /** 对照 Go {@code bitableField}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BitableField(
            @JsonProperty("field_name") String fieldName,
            @JsonProperty("type") int type,
            @JsonProperty("property") BitableFieldProperty property) {
    }

    /** 对照 Go {@code bitableFieldsData}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BitableFieldsData(
            @JsonProperty("has_more") boolean hasMore,
            @JsonProperty("page_token") String pageToken,
            @JsonProperty("items") List<BitableField> items) {
    }

    /** 对照 Go {@code bitableFieldsResponse}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BitableFieldsResponse(
            @JsonProperty("code") int code,
            @JsonProperty("msg") String msg,
            @JsonProperty("data") BitableFieldsData data) {
    }

    /** 对照 Go {@code bitableRecord}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BitableRecord(@JsonProperty("fields") Map<String, Object> fields) {
    }

    /** 对照 Go {@code bitableRecordsData}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BitableRecordsData(
            @JsonProperty("has_more") boolean hasMore,
            @JsonProperty("page_token") String pageToken,
            @JsonProperty("items") List<BitableRecord> items) {
    }

    /** 对照 Go {@code bitableRecordsResponse}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BitableRecordsResponse(
            @JsonProperty("code") int code,
            @JsonProperty("msg") String msg,
            @JsonProperty("data") BitableRecordsData data) {
    }

    // ──────────────────────────────────────────────────────────────────
    // 纯函数（blocks.go L205-315）
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code capRows} 的双返回值。 */
    public record Capped<T>(List<T> rows, boolean truncated) {
    }

    /**
     * 对照 Go {@code capRows}：把行数限到 {@link #MAX_TABLE_ROWS}，并报告是否截断了。
     * 泛型化是为了让 sheet（{@code [][]any}）与 bitable（{@code [][]string}）
     * 共用同一条截断规则，而不是各写一份。
     */
    public static <T> Capped<T> capRows(List<T> rows) {
        if (rows == null) {
            return new Capped<>(new ArrayList<>(), false);
        }
        if (rows.size() > MAX_TABLE_ROWS) {
            return new Capped<>(new ArrayList<>(rows.subList(0, MAX_TABLE_ROWS)), true);
        }
        return new Capped<>(rows, false);
    }

    /** 对照 Go {@code stringifyMatrix}：把任意单元格值渲染成字符串；nil → {@code ""}。 */
    public static List<List<String>> stringifyMatrix(List<List<Object>> in) {
        List<List<String>> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        for (List<Object> row : in) {
            List<String> cells = new ArrayList<>();
            if (row != null) {
                for (Object v : row) {
                    cells.add(cellToString(v));
                }
            }
            out.add(cells);
        }
        return out;
    }

    /**
     * 对照 Go {@code cellToString}：把单个 JSON 单元格值渲染成字符串；nil → {@code ""}。
     *
     * <p>浮点走 Go 的 {@code strconv.FormatFloat(t, 'f', -1, 64)}：<b>'f' 格式 + 最短表示</b>，
     * 即"没有指数、整数值不补 .0"。Java 的 {@code Double.toString} 会给出 {@code 1.0E21}
     * 这种形态，所以这里用 {@code BigDecimal(Double.toString(d)).toPlainString()}——
     * {@code Double.toString} 与 Go 一样是最短可往返表示，{@code toPlainString} 消掉指数，
     * 再去掉多余的 {@code .0}。</p>
     */
    public static String cellToString(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Double || v instanceof Float) {
            return formatGoFloatF(((Number) v).doubleValue());
        }
        if (v instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (v instanceof Number n) {
            // Jackson 通常给 Integer/Long/BigInteger；Go 的 JSON 数字一律是 float64，
            // 但 Java 侧按整型解析时直接输出其十进制即可（值相同）。
            return n.toString();
        }
        return String.valueOf(v);
    }

    /** 对照 Go {@code strconv.FormatFloat(d, 'f', -1, 64)}。 */
    static String formatGoFloatF(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "+Inf" : "-Inf";
        }
        String plain = new BigDecimal(Double.toString(d)).toPlainString();
        if (plain.endsWith(".0")) {
            plain = plain.substring(0, plain.length() - 2);
        }
        return plain;
    }

    /**
     * 对照 Go {@code dateFormatterHasTime}：飞书的 date_formatter 是不是带时间部分。
     *
     * <p>飞书的 formatter 是 Java 风格的：{@code 'H'/'h'} 是小时、<b>小写</b> {@code 'm'}
     * 是分钟、<b>大写</b> {@code 'M'} 是月——所以"有时间"当且仅当串里含小时符号或
     * 小写 m。空 formatter（飞书默认 {@code "yyyy/MM/dd"}）按只有日期处理。</p>
     */
    public static boolean dateFormatterHasTime(String f) {
        if (f == null || f.isEmpty()) {
            return false;
        }
        return f.contains("H") || f.contains("h") || f.contains("m");
    }

    /**
     * 对照 Go {@code bitableFieldCell}：渲染一个多维表格单元格。
     *
     * <p>日期列带的是 Unix <b>毫秒</b>的 UTC 瞬时；用户看到的日历日期是"该瞬时在表格
     * 时区下"的日期，所以要用 {@code loc} 渲染（用 UTC 会把日期挪一天，例如 GMT+8 的
     * {@code "2024-04-01"} 会显示成 {@code "2024-03-31 16:00:00"}）。
     * 只有日期的 formatter 输出 {@code 2006-01-02}；带时间的 formatter 再加 {@code 15:04}。
     * 其它类型一律落到 {@link #bitableCellToString}。</p>
     */
    public static String bitableFieldCell(Object v, BitableColumn col, ZoneId loc) {
        if (col != null && col.fieldType() == BITABLE_FIELD_TYPE_DATE_TIME && v instanceof Number n) {
            double ms = n.doubleValue();
            // 空日期单元格是 nil（→ 落到空白）；ms==0（epoch）也不是真实值——
            // 渲染成空白，而不是 "1970-01-01" 或 "0"。
            if (ms == 0) {
                return "";
            }
            java.time.ZonedDateTime t = Instant.ofEpochMilli((long) ms).atZone(loc);
            if (dateFormatterHasTime(col.dateFormatter())) {
                return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
            return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        return bitableCellToString(v);
    }

    /**
     * 对照 Go {@code bitableCellToString}：把多维表格字段值（可能是文本段数组、
     * 人员/链接对象、附件/多选数组）渲染成可读字符串给 RAG 用。
     */
    public static String bitableCellToString(Object v) {
        if (v instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object e : list) {
                String s = bitableCellToString(e);
                if (!s.isEmpty()) {
                    parts.add(s);
                }
            }
            return String.join(", ", parts);
        }
        if (v instanceof Map<?, ?> map) {
            for (String k : new String[]{"text", "name", "en_name", "link"}) {
                Object o = map.get(k);
                if (o instanceof String s && !s.isEmpty()) {
                    return s;
                }
            }
            return "";
        }
        return cellToString(v);
    }
}
