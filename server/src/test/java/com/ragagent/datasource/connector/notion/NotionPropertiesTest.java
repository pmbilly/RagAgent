package com.ragagent.datasource.connector.notion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 属性抽取四个纯函数（{@code propertyToString} / {@code extractValue} /
 * {@code extractLeafValue} / {@code extractPropertySchema} / {@code extractTitle}）
 * 的对等测试。
 *
 * <p>期望值全部来自 Go 实录（{@code /tmp/weknora-copy} 的 {@code probe_test.go}）。
 * 语料刻意覆盖了 22 种属性类型、以及一批"边界输入"（{@code type} 不存在、
 * 内层是 {@code null}、数字的三种形态、数组里混进非字符串……）。</p>
 */
class NotionPropertiesTest {

    private static String toStr(String json) {
        return NotionProperties.propertyToString(NotionTestSupport.json(json));
    }

    // ── propertyToString ─────────────────────────────────────────────────

    @Test
    void selectAndMultiSelect() {
        assertThat(toStr("{\"type\":\"select\",\"select\":{\"name\":\"Done\"}}")).isEqualTo("Done");
        assertThat(toStr("{\"type\":\"select\",\"select\":null}")).isEmpty();
        assertThat(toStr("{\"type\":\"multi_select\",\"multi_select\":"
                + "[{\"name\":\"Tag1\"},{\"name\":\"Tag2\"}]}")).isEqualTo("Tag1, Tag2");
        assertThat(toStr("{\"type\":\"multi_select\",\"multi_select\":[]}")).isEmpty();
    }

    @Test
    void richText() {
        assertThat(toStr("{\"type\":\"rich_text\",\"rich_text\":"
                + "[{\"plain_text\":\"Hello\"},{\"plain_text\":\" World\"}]}"))
                .isEqualTo("Hello,  World");
        // 片段里没有 plain_text → 空片段被丢掉
        assertThat(toStr("{\"type\":\"rich_text\",\"rich_text\":[{\"type\":\"text\",\"text\":{}}]}"))
                .isEqualTo("");
        // 叶子对象里没有 content 键 → 取 plain_text（content 在更深的 text 里）
        assertThat(toStr("{\"type\":\"rich_text\",\"rich_text\":"
                + "[{\"type\":\"text\",\"plain_text\":\"pt\",\"text\":{\"content\":\"ct\"}}]}"))
                .isEqualTo("pt");
    }

    /**
     * 数字的三种形态：Go 的 {@code %d} 分支、{@code %g} 的 'f' 形态、
     * 以及 {@code %g} 的 'e' 形态（指数至少两位）。这一组是把 Go 的
     * {@code extractValue} 与 {@code GoDoubleSerializer} 的差异钉死的地方。
     */
    @Test
    void numbersFollowGoFormatting() {
        assertThat(toStr("{\"type\":\"number\",\"number\":42}")).isEqualTo("42");
        assertThat(toStr("{\"type\":\"number\",\"number\":42.5}")).isEqualTo("42.5");
        assertThat(toStr("{\"type\":\"number\",\"number\":-3}")).isEqualTo("-3");
        assertThat(toStr("{\"type\":\"number\",\"number\":-0.5}")).isEqualTo("-0.5");
        assertThat(toStr("{\"type\":\"number\",\"number\":0}")).isEqualTo("0");
        assertThat(toStr("{\"type\":\"number\",\"number\":null}")).isEmpty();
        // %d 分支：整数值
        assertThat(toStr("{\"type\":\"number\",\"number\":1000000}")).isEqualTo("1000000");
        assertThat(toStr("{\"type\":\"number\",\"number\":1000001}")).isEqualTo("1000001");
        // %g 的 'e' 分界是 exp >= 6（不是 GoDoubleSerializer 的 1e21）
        assertThat(toStr("{\"type\":\"number\",\"number\":1234567.5}")).isEqualTo("1.2345675e+06");
        // %g 的 'f'/'e' 下界是 exp < -4
        assertThat(toStr("{\"type\":\"number\",\"number\":0.0001}")).isEqualTo("0.0001");
        assertThat(toStr("{\"type\":\"number\",\"number\":0.00001}")).isEqualTo("1e-05");
        // 超出 int64 的整数值 → 饱和后比较失败 → 走 %g
        assertThat(toStr("{\"type\":\"number\",\"number\":1e20}")).isEqualTo("1e+20");
        assertThat(toStr("{\"type\":\"number\",\"number\":1e21}")).isEqualTo("1e+21");
    }

    @Test
    void booleansAndDates() {
        assertThat(toStr("{\"type\":\"checkbox\",\"checkbox\":true}")).isEqualTo("true");
        assertThat(toStr("{\"type\":\"checkbox\",\"checkbox\":false}")).isEqualTo("false");
        assertThat(toStr("{\"type\":\"date\",\"date\":{\"start\":\"2026-01-15\","
                + "\"end\":\"2026-01-20\"}}")).isEqualTo("2026-01-15 ~ 2026-01-20");
        assertThat(toStr("{\"type\":\"date\",\"date\":{\"start\":\"2026-01-15\"}}"))
                .isEqualTo("2026-01-15");
        // end 为空串等于没有 end
        assertThat(toStr("{\"type\":\"date\",\"date\":{\"start\":\"2026-01-15\",\"end\":\"\"}}"))
                .isEqualTo("2026-01-15");
        assertThat(toStr("{\"type\":\"date\",\"date\":null}")).isEmpty();
    }

    @Test
    void allTwentyTwoPropertyTypes() {
        assertThat(toStr("{\"type\":\"title\",\"title\":[{\"plain_text\":\"My Title\"}]}"))
                .isEqualTo("My Title");
        assertThat(toStr("{\"type\":\"url\",\"url\":\"https://x.com\"}")).isEqualTo("https://x.com");
        assertThat(toStr("{\"type\":\"email\",\"email\":\"a@b.c\"}")).isEqualTo("a@b.c");
        assertThat(toStr("{\"type\":\"phone_number\",\"phone_number\":\"+1\"}")).isEqualTo("+1");
        assertThat(toStr("{\"type\":\"formula\",\"formula\":{\"type\":\"string\",\"string\":\"S\"}}"))
                .isEqualTo("S");
        assertThat(toStr("{\"type\":\"formula\",\"formula\":{\"type\":\"number\",\"number\":7}}"))
                .isEqualTo("7");
        assertThat(toStr("{\"type\":\"rollup\",\"rollup\":{\"type\":\"number\",\"number\":9}}"))
                .isEqualTo("9");
        assertThat(toStr("{\"type\":\"rollup\",\"rollup\":{\"type\":\"array\",\"array\":"
                + "[{\"type\":\"number\",\"number\":1},{\"type\":\"number\",\"number\":2}]}}"))
                .isEqualTo("1, 2");
        // relation 只有 id → 没有可抽的叶子
        assertThat(toStr("{\"type\":\"relation\",\"relation\":[{\"id\":\"a\"},{\"id\":\"b\"}]}"))
                .isEmpty();
        assertThat(toStr("{\"type\":\"people\",\"people\":[{\"name\":\"Ann\",\"object\":\"user\"},"
                + "{\"name\":\"Bob\"}]}")).isEqualTo("Ann, Bob");
        assertThat(toStr("{\"type\":\"files\",\"files\":[{\"name\":\"f1.pdf\",\"type\":\"file\"},"
                + "{\"name\":\"f2.pdf\"}]}")).isEqualTo("f1.pdf, f2.pdf");
        assertThat(toStr("{\"type\":\"status\",\"status\":{\"name\":\"Active\"}}")).isEqualTo("Active");
        assertThat(toStr("{\"type\":\"created_by\",\"created_by\":{\"id\":\"u1\",\"object\":\"user\"}}"))
                .isEmpty();
        assertThat(toStr("{\"type\":\"last_edited_time\","
                + "\"last_edited_time\":\"2026-01-15T10:00:00.000Z\"}"))
                .isEqualTo("2026-01-15T10:00:00.000Z");
        assertThat(toStr("{\"type\":\"created_time\","
                + "\"created_time\":\"2026-01-15T10:00:00.000Z\"}"))
                .isEqualTo("2026-01-15T10:00:00.000Z");
        assertThat(toStr("{\"type\":\"unique_id\",\"unique_id\":{\"prefix\":\"T\",\"number\":12}}"))
                .isEmpty();
        assertThat(toStr("{\"type\":\"button\",\"button\":{}}")).isEmpty();
    }

    @Test
    void typeChainsAndMissingType() {
        // type 缺席 → 直接 extractLeafValue
        assertThat(toStr("{\"name\":\"Direct\"}")).isEqualTo("Direct");
        assertThat(toStr("{\"content\":\"C\"}")).isEqualTo("C");
        assertThat(toStr("{\"plain_text\":\"P\"}")).isEqualTo("P");
        assertThat(toStr("{\"start\":\"2026-01-15\",\"end\":\"2026-01-20\"}"))
                .isEqualTo("2026-01-15 ~ 2026-01-20");
        assertThat(toStr("{\"start\":\"2026-01-15\"}")).isEqualTo("2026-01-15");
        assertThat(toStr("{\"expression\":\"E\"}")).isEqualTo("E");
        assertThat(toStr("{\"foo\":\"bar\"}")).isEmpty();
        assertThat(toStr("{}")).isEmpty();
        // type 指的内层键不存在
        assertThat(toStr("{\"type\":\"select\"}")).isEmpty();
        assertThat(toStr("{\"type\":\"select\",\"select\":{\"name\":null}}")).isEmpty();
        // 内层又是"type + 以 type 命名"的结构 → 递归
        assertThat(toStr("{\"type\":\"relation\",\"relation\":[{\"type\":\"mention\","
                + "\"mention\":{\"type\":\"page\",\"page\":{\"id\":\"p\"}},\"plain_text\":\"PT\"}]}"))
                .isEqualTo("PT");
        // 数组元素：数字没有 name → 被丢掉，只剩字符串那个
        assertThat(toStr("{\"type\":\"multi_select\",\"multi_select\":"
                + "[{\"name\":1},{\"name\":\"x\"}]}")).isEqualTo("x");
    }

    @Test
    void nullInputs() {
        assertThat(NotionProperties.propertyToString(null)).isEmpty();
        assertThat(NotionProperties.extractValue(null)).isEmpty();
        assertThat(NotionProperties.extractLeafValue(null)).isEmpty();
        assertThat(NotionProperties.extractLeafValue(NotionTestSupport.json("[]"))).isEmpty();
    }

    // ── extractPropertySchema ────────────────────────────────────────────

    private static List<String> schema(String propsJson) {
        NotionPage page = new NotionPage();
        page.rawProperties = NotionTestSupport.json(propsJson);
        return NotionProperties.extractPropertySchema(page);
    }

    @Test
    void propertySchemaSortsAndSkipsTitle() {
        assertThat(schema("{\"Name\":{\"type\":\"title\"},\"Status\":{\"type\":\"select\"},"
                + "\"Zed\":{\"type\":\"number\"},\"Alpha\":{\"type\":\"rich_text\"}}"))
                .containsExactly("Alpha", "Status", "Zed");
        // 只有 title → nil（Go 的 nil 切片）
        assertThat(schema("{\"Name\":{\"type\":\"title\"}}")).isNull();
        assertThat(schema("{}")).isNull();
        assertThat(schema("[1,2]")).isNull();
        // 值是 JSON null → Go 解进 struct 成功、Type 为空 → 算作非 title
        assertThat(schema("{\"A\":null,\"B\":{\"type\":\"title\"}}")).containsExactly("A");
        // 没有 type 字段 → 同样算非 title
        assertThat(schema("{\"A\":{},\"B\":{\"type\":\"title\"}}")).containsExactly("A");
        // 值是字符串 → Go 解 struct 失败但**忽略错误**、Type 留空 → 照样收进来
        // （probe 实录：{"A":"str","B":{"type":"title"}} → ["A"]）
        assertThat(schema("{\"A\":\"str\",\"B\":{\"type\":\"title\"}}")).containsExactly("A");
        assertThat(schema("{\"A\":[1,2],\"B\":{\"type\":\"title\"}}")).containsExactly("A");
        // ASCII 排序：'A' < 'Z' < 'e'
        assertThat(schema("{\"e\":{\"type\":\"number\"},\"Z\":{\"type\":\"number\"},"
                + "\"A\":{\"type\":\"number\"}}")).containsExactly("A", "Z", "e");
    }

    @Test
    void propertySchemaNullPage() {
        assertThat(NotionProperties.extractPropertySchema(null)).isNull();
        assertThat(NotionProperties.extractPropertySchema(new NotionPage())).isNull();
    }

    // ── extractTitle ─────────────────────────────────────────────────────

    private static String title(String propsJson, String rawTitleJson) {
        NotionPage page = new NotionPage();
        if (propsJson != null) {
            page.rawProperties = NotionTestSupport.json(propsJson);
        }
        if (rawTitleJson != null) {
            page.rawTitle = NotionTestSupport.json(rawTitleJson);
        }
        return NotionProperties.extractTitle(page);
    }

    @Test
    void extractTitleFromProperties() {
        assertThat(title("{\"Name\":{\"type\":\"title\",\"title\":[{\"plain_text\":\"Test Page\"}]}}",
                null)).isEqualTo("Test Page");
        assertThat(title("{\"Name\":{\"type\":\"title\",\"title\":"
                + "[{\"plain_text\":\"A\"},{\"plain_text\":\"B\"}]}}", null)).isEqualTo("AB");
        // title 数组为空 → 不提前返回；没有别的 title → 落到顶层 title（这里是空）
        assertThat(title("{\"Name\":{\"type\":\"title\",\"title\":[]}}", null)).isEmpty();
        assertThat(title("{\"Name\":{\"type\":\"select\"}}", null)).isEmpty();
        assertThat(title("null", null)).isEmpty();
        assertThat(title("\"str\"", null)).isEmpty();
        assertThat(title("{\"Name\":{\"type\":\"title\",\"title\":{\"a\":1}}}", null)).isEmpty();
    }

    @Test
    void extractTitleFallsBackToTopLevelTitleArray() {
        assertThat(title(null, "[{\"plain_text\":\"Test Database\"}]")).isEqualTo("Test Database");
        assertThat(title(null, "[]")).isEmpty();
        assertThat(title(null, "\"x\"")).isEmpty();
        // properties 优先
        assertThat(title("{\"N\":{\"type\":\"title\",\"title\":[{\"plain_text\":\"FromProps\"}]}}",
                "[{\"plain_text\":\"FromRaw\"}]")).isEqualTo("FromProps");
    }

    // ── 数字格式化（NotionValues） ────────────────────────────────────────

    @Test
    void goFormatGMatchesStrconv() {
        assertThat(NotionValues.jsonNumberToString(42)).isEqualTo("42");
        assertThat(NotionValues.jsonNumberToString(42.5)).isEqualTo("42.5");
        assertThat(NotionValues.jsonNumberToString(0.0001)).isEqualTo("0.0001");
        assertThat(NotionValues.jsonNumberToString(0.00001)).isEqualTo("1e-05");
        assertThat(NotionValues.jsonNumberToString(1234567.5)).isEqualTo("1.2345675e+06");
        assertThat(NotionValues.jsonNumberToString(1e20)).isEqualTo("1e+20");
        assertThat(NotionValues.jsonNumberToString(1e21)).isEqualTo("1e+21");
        assertThat(NotionValues.jsonNumberToString(-0.5)).isEqualTo("-0.5");
        assertThat(NotionValues.jsonNumberToString(0)).isEqualTo("0");
        assertThat(NotionValues.jsonNumberToString(-0.0)).isEqualTo("0");
        assertThat(NotionValues.goFormatG(1e-7)).isEqualTo("1e-07");
        assertThat(NotionValues.goFormatG(1e100)).isEqualTo("1e+100");
        assertThat(NotionValues.goFormatG(-1234567.5)).isEqualTo("-1.2345675e+06");
    }

    @Test
    void trimSpaceMatchesGoUnicodeSpace() {
        assertThat(NotionValues.trimSpace("  x  ")).isEqualTo("x");
        assertThat(NotionValues.trimSpace("\t\n\r x \f\u000b")).isEqualTo("x");
        // U+00A0（NBSP）：Go 算空白、Java 的 Character.isWhitespace 不算
        assertThat(NotionValues.trimSpace("\u00a0x\u00a0")).isEqualTo("x");
        assertThat(Character.isWhitespace('\u00a0')).isFalse();
        // U+2007 / U+202F：同上（Go 的 White_Space 含、Java 不含）
        assertThat(NotionValues.trimSpace("\u2007x\u202f")).isEqualTo("x");
        // U+2028 / U+2029 / U+3000：两边都算空白
        assertThat(NotionValues.trimSpace("\u2028x\u2029")).isEqualTo("x");
        assertThat(NotionValues.trimSpace("\u2003x\u3000")).isEqualTo("x");
        // U+001C–U+001F：Java 的 isWhitespace **会**当成空白、Go 不算（双向差异）
        assertThat(NotionValues.trimSpace("\u001cx\u001c")).isEqualTo("\u001cx\u001c");
        assertThat(Character.isWhitespace('\u001c')).isTrue();
        assertThat(NotionValues.trimSpace(null)).isEmpty();
        assertThat(NotionValues.trimSpace("")).isEmpty();
    }
}
