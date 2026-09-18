package com.ragagent.stream;

import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;

/**
 * 把 Jackson 的字符串转义改成与 Go {@code encoding/json} <b>逐字节一致</b>。
 *
 * <h2>实测的两侧差异</h2>
 *
 * <p>实测 Go 的 {@code json.Marshal}（默认开启 HTML 转义）对
 * {@code hi <b>&</b>} 输出 {@code "hi <b>&</b>"} 的转义形态。与
 * Jackson 默认行为相比，只有三类不同：</p>
 * <ol>
 *   <li>{@code <}、{@code >}、{@code &}：Go 转成 {@code <} 一类的形式，
 *       Jackson <b>原样输出</b>；</li>
 *   <li>其余控制字符（{@code 0x00-0x1F} 中除 {@code \b \t \n \f \r} 之外）：
 *       Go 用小写十六进制，Jackson 用大写；</li>
 *   <li>U+2028 / U+2029：Go 会转义（<b>本类未复刻</b>，见下）。</li>
 * </ol>
 *
 * <p><b>注意</b>：Jackson 的 {@code CharacterEscapes} 是**整表替换**而不是叠加——
 * 返回的数组里没标 escape 的字符会**原样输出**。所以短转义 {@code \b \t \n \f \r \" \\}
 * 虽然两侧行为本来就一致，也必须在这里显式声明（实现时漏掉，`\n`/`\t`/`\b` 直接漏成了原文，
 * 由 {@code StreamJsonTest} 抓到）。</p>
 *
 * <h2>不复刻会怎样</h2>
 *
 * <p>事件落的是 Go 与 Java <b>共用</b>的 Redis 键。JSON 本身两边都读得懂，功能上不炸；
 * 但 {@code ClearLiveRun} 的 CAS 是在原始 JSON 上做子串匹配、
 * {@code UpdateSteerEventData} 的 CAS 是拿读到的原文比对槽位——两个实现写出的字节
 * 不同时，跨语言的这两条 CAS 会一路重试到放弃。复刻转义规则把这类"偶发跨语言失败"
 * 整体消掉。</p>
 *
 * <h2>为什么 U+2028 / U+2029 不管</h2>
 *
 * <p>Jackson 的 {@code CharacterEscapes} 只管 7-bit 那段，非 ASCII 走另一条路径
 * （要复刻得开 {@code ESCAPE_NON_ASCII}，而那会把中文也一起转义，反而偏离 Go）。
 * 这两个字符出现在流事件正文里的概率可以忽略——真正参与 CAS 比对的是 {@code id} 与
 * {@code assistant_message_id}，都是 UUID 形态。</p>
 */
final class GoJsonEscapes extends CharacterEscapes {

    /** Go 的十六进制字母表是小写的（{@code const hex = "0123456789abcdef"}）。 */
    private static final char[] LOWER_HEX = "0123456789abcdef".toCharArray();

    private final int[] ascii;

    GoJsonEscapes() {
        int[] table = new int[128];
        // 全部控制字符都要显式声明——这张表是替换而非叠加
        for (int i = 0; i < 0x20; i++) {
            table[i] = ESCAPE_CUSTOM;
        }
        // 引号与反斜杠同理
        table['"'] = ESCAPE_CUSTOM;
        table['\\'] = ESCAPE_CUSTOM;
        // Go 默认（未调 SetEscapeHTML(false)）就会转义这三个
        table['<'] = ESCAPE_CUSTOM;
        table['>'] = ESCAPE_CUSTOM;
        table['&'] = ESCAPE_CUSTOM;
        this.ascii = table;
    }

    @Override
    public int[] getEscapeCodesForAscii() {
        return ascii;
    }

    @Override
    public SerializableString getEscapeSequence(int ch) {
        switch (ch) {
            case '\b':
                return new SerializedString("\\b");
            case '\t':
                return new SerializedString("\\t");
            case '\n':
                return new SerializedString("\\n");
            case '\f':
                return new SerializedString("\\f");
            case '\r':
                return new SerializedString("\\r");
            case '"':
                return new SerializedString("\\\"");
            case '\\':
                return new SerializedString("\\\\");
            default:
                // 其余控制字符，以及 < > & —— Go 都走通用的十六进制分支
                if (ch < 0x20 || ch == '<' || ch == '>' || ch == '&') {
                    return new SerializedString("\\u00" + hexPair(ch));
                }
                return null;
        }
    }

    private static String hexPair(int ch) {
        return new String(new char[] {LOWER_HEX[(ch >> 4) & 0xF], LOWER_HEX[ch & 0xF]});
    }
}
