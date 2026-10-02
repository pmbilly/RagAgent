package com.ragagent.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Assertions;

/**
 * 契约金片对比的<b>共享基建</b>（B2，§15）——把各契约测试各自为政的
 * 「读夹具 + 掩码 + 断言 + {@code -Dcontract.refresh=true} 重录」四件套收拢为一份。
 *
 * <h2>对比语义</h2>
 * 两侧统一走 {@link ContractJson#deep}（键排序 + 数字归一 + 时间归 UTC + 字符串内嵌
 * JSON 递归归一）+ {@link String#strip()}（夹具文件的尾随换行是编辑器产物，不是内容）。
 * 非 JSON 文本（纯字符串错误体）deep 原样返回，等值断言照旧成立——
 * 因此<b>字节级与语义级不再需要区分</b>：迁移后的对比一律是语义断言。
 *
 * <h2>重录</h2>
 * {@code -Dcontract.refresh=true} 时把掩码后的实际响应写回夹具（换锚批重录用），
 * 与各测试既有的开关同名同语义；各测试的本地开关应改为引用
 * {@link #REFRESH}，避免出现第二套开关。
 *
 * <h2>掩码</h2>
 * uuid/时间戳等动态值的掩码正则因域而异，由调用方以 {@code mask} 函数传入
 * （golden 侧与 actual 侧走同一函数，本类不持有域知识）。
 */
public final class GoldenContract {

    /** 共享重录开关：{@code -Dcontract.refresh=true}。 */
    public static final boolean REFRESH = Boolean.getBoolean("contract.refresh");

    private GoldenContract() {
    }

    /**
     * 对比实际响应与金片：两侧 {@code deep} 归一 + strip 后等值断言；
     * {@link #REFRESH} 开启时改写夹具为掩码后的实际值（追加换行，便于 diff）。
     *
     * @param contractsDir 夹具目录（测试工作目录相对路径，如 {@code src/test/resources/contracts}）
     * @param name         金片文件名（含扩展名）
     * @param mask         掩码函数（uuid/时间戳 → 占位符；两侧同函数）
     * @param actual       实际响应体原文
     */
    public static void assertEquals(String contractsDir, String name,
            UnaryOperator<String> mask, String actual) throws Exception {
        Path file = Path.of(contractsDir, name);
        if (!Files.exists(file)) {
            file = Path.of("server", contractsDir, name);
        }
        String maskedActual = mask.apply(actual);
        if (REFRESH) {
            Files.writeString(file, maskedActual + "\n");
            return;
        }
        String expected = mask.apply(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
        String expectedDeep = deep(expected).strip();
        String actualDeep = deep(maskedActual).strip();
        // deep 归一后相等（键序差异）→ 通过，不做文本断言
        if (expectedDeep.equals(actualDeep)) {
            return;
        }
        // 归一后仍不等 → 再做 strip 后的文本断言（覆盖 CSV/纯文本等非 JSON 内容；
        // 文本语义就是字节内容，strip 只吸收夹具文件的尾随换行）
        Assertions.assertEquals(expectedDeep, actualDeep,
                () -> "golden mismatch: " + name + "\nexpected: " + expected + "\nactual:   " + maskedActual);
    }

    private static String deep(String text) {
        return ContractJson.deep(text);
    }
}
