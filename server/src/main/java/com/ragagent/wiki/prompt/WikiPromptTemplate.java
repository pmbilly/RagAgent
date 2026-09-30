package com.ragagent.wiki.prompt;

import java.util.Map;

/**
 * 极简 prompt 模板渲染器，语义对齐原实现的 {@code text/template}
 * （取值 {@code {{.X}}} + 条件块 {@code {{if .X}}...{{end}}} 的确定性替换）。
 *
 * <p><b>为什么自己写而不用现成引擎</b>：本包的 prompt 模板只用到两个构造——
 * 取值 {@code {{.X}}} 与条件块 {@code {{if .X}}...{{end}}}——没有任何
 * {@code range} / {@code else} / 管道 / 函数调用。引入 Freemarker / Handlebars 只会
 * 带来"模板语义与原实现不完全一致"的风险（例如缺失变量分别渲染成空串、null、
 * 还是报错），而本项目对 prompt 字节级的保真要求远高于模板功能的丰富度。
 * 二十行的确定性替换反而是最容易对齐的做法。</p>
 *
 * <h2>模板语义</h2>
 * <ul>
 *   <li><b>{@code {{.X}}}</b> → {@code data.get("X")}；<b>缺失键渲染成空串</b>
 *       （与原模板引擎对 map 缺键返回零值 {@code ""} 的行为一致，渲染不报错）。
 *       值为 null 时同样按空串处理，避免把
 *       JavaScript 意义上的 {@code null} 写进 prompt。</li>
 *   <li><b>{@code {{if .X}}body{{end}}}</b> → {@code X} 非空串时渲染 body，
 *       否则渲染空串。真值判定就是 {@code len(s) > 0}；
 *       缺失键 → 空串 → 假。这正是 {@code WikiPageModifyUserPrompt} 用来
 *       开关 {@code <shared_source_contexts>} / {@code <new_information>} /
 *       {@code <deleted_documents>} 三块的机制。</li>
 *   <li>{@code {{if}}} <b>可嵌套</b>：本实现用配平扫描找匹配的 {@code {{end}}}，
 *       嵌套时也能正确闭合（当前模板未用到嵌套，
 *       但把递归写对不花额外成本，也避免将来加模板时静默错配）。</li>
 *   <li><b>不做空白裁剪</b>：原模板引擎只裁剪紧贴 action 的
 *       换行，而本文件里的 {@code {{if}}} 与后续文本都在同一行或以刻意留白分隔，
 *       逐字符保留才是与原输出一致的做法。</li>
 * </ul>
 *
 * <p><b>不支持语法一律抛异常</b>（{@link IllegalArgumentException}）：静默保留
 * 未识别的 {@code {{...}}}（例如将来有人写了 {@code {{range}}}）会让变量原样进入
 * prompt 并最终被模型当成字面量，属于最坏的一类无声降级。原模板引擎
 * 对未知 action 也是报错，这里保持同样严格。</p>
 */
public final class WikiPromptTemplate {

    private WikiPromptTemplate() {}

    /**
     * 渲染模板。见类注释的语义说明。
     *
     * @param template 模板文本（通常是 {@link WikiPrompts} 里的常量）
     * @param data     变量名 → 值；键名与模板里的字段名逐一对齐
     * @return 渲染结果
     */
    public static String render(String template, Map<String, String> data) {
        if (template == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(template.length() + 64);
        renderInto(out, template, 0, template.length(), data);
        return out.toString();
    }

    /**
     * 渲染 {@code [from, to)} 区间。
     *
     * <p>扫描到 {@code {{if .X}}} 时，先配平找出对应的 {@code {{end}}}，
     * 再决定是否递归渲染块体；这样"跳过整块"与"渲染整块"共用同一段扫描逻辑。</p>
     */
    private static void renderInto(StringBuilder out, String template, int from, int to,
                                   Map<String, String> data) {
        int i = from;
        while (i < to) {
            int open = template.indexOf("{{", i);
            if (open < 0 || open >= to) {
                out.append(template, i, to);
                return;
            }
            out.append(template, i, open);

            int close = template.indexOf("}}", open + 2);
            if (close < 0 || close >= to) {
                throw new IllegalArgumentException(
                        "unterminated template action at offset " + open + ": "
                                + template.substring(open, Math.min(open + 40, template.length())));
            }
            String action = template.substring(open + 2, close).trim();

            if (action.startsWith("if ")) {
                String name = variableName(action.substring(3), template, open);
                int bodyStart = close + 2;
                int bodyEnd = findMatchingEnd(template, bodyStart, to);
                if (truthy(data, name)) {
                    renderInto(out, template, bodyStart, bodyEnd, data);
                }
                // 跳过 {{end}}
                i = template.indexOf("}}", bodyEnd) + 2;
                continue;
            }
            if (action.equals("end")) {
                // 由调用方（if 分支）消费；走到这里说明模板里的 {{end}} 多出来了
                throw new IllegalArgumentException(
                        "unexpected {{end}} at offset " + open + " (no matching {{if}})");
            }
            if (action.startsWith(".")) {
                String name = variableName(action, template, open);
                String value = data == null ? null : data.get(name);
                out.append(value == null ? "" : value);
                i = close + 2;
                continue;
            }

            // {{range}} / {{else}} / 管道 / 函数调用…… 一律拒绝，理由见类注释
            throw new IllegalArgumentException(
                    "unsupported template action {{" + action + "}} at offset " + open
                            + " — WikiPromptTemplate only supports {{.Var}} and {{if .Var}}...{{end}}");
        }
    }

    /**
     * 从 {@code from}（紧跟在 {@code {{if ...}}}} 之后）出发，返回匹配的
     * {@code {{end}}} 中 {@code {{ 的位置。嵌套 {@code {{if}}} 会被计数。
     */
    private static int findMatchingEnd(String template, int from, int limit) {
        int depth = 1;
        int i = from;
        while (i < limit) {
            int open = template.indexOf("{{", i);
            if (open < 0 || open >= limit) {
                break;
            }
            int close = template.indexOf("}}", open + 2);
            if (close < 0 || close >= limit) {
                break;
            }
            String action = template.substring(open + 2, close).trim();
            if (action.startsWith("if ")) {
                depth++;
            } else if (action.equals("end")) {
                depth--;
                if (depth == 0) {
                    return open;
                }
            }
            i = close + 2;
        }
        throw new IllegalArgumentException("missing {{end}} for {{if}} at offset " + from);
    }

    /**
     * 校验 {@code {{.X}}} / {@code {{if .X}}} 里的变量名形态，并取出 {@code X}。
     *
     * <p>模板字段名是标识符（原实现里首次大写）；这里只做"形如 {@code .Name}"的
     * 基本校验，把真正的拼写错误留给测试去发现（调用方传的 map 缺键只会渲染成空串，
     * 不会报错——所以必须靠测试而不是运行时来守）。</p>
     */
    private static String variableName(String action, String template, int offset) {
        String name = action.substring(1).trim();
        if (name.isEmpty() || !name.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_')) {
            throw new IllegalArgumentException(
                    "bad template variable " + action + " at offset " + offset);
        }
        return name;
    }

    /**
     * 模板真值判定：<b>非空串为真</b>。键缺失或值为 null 都为假。
     */
    private static boolean truthy(Map<String, String> data, String name) {
        if (data == null) {
            return false;
        }
        String value = data.get(name);
        return value != null && !value.isEmpty();
    }
}
