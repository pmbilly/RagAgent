package com.ragagent.retrieval.engine.milvus;

import java.math.BigDecimal;

/**
 * 数值字面量格式化：整数不带小数（{@code 1}）、极小/极大值走 {@code 1e-07}/{@code 1e+07}
 * 计数形态。仅用于过滤表达式里的浮点值（本驱动的过滤面以字符串/布尔为主）。
 */
final class MilvusNumbers {

    private MilvusNumbers() {
    }

    /** 整数不带小数（{@code 1}）、极小/极大走 {@code 1e-07} 形态。 */
    static String floatGo(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "+Inf" : "-Inf";
        }
        if (v == 0d) {
            return Double.doubleToRawLongBits(v) < 0 ? "-0" : "0";
        }
        String sign = v < 0 ? "-" : "";
        BigDecimal decimal = new BigDecimal(Double.toString(Math.abs(v)));
        String digits = decimal.unscaledValue().toString();
        int dp = digits.length() - decimal.scale();
        while (digits.length() > 1 && digits.endsWith("0")) {
            digits = digits.substring(0, digits.length() - 1);
        }
        int exp = dp - 1;
        if (exp < -4 || exp >= 21) {
            String mantissa = digits.length() == 1
                    ? digits
                    : digits.charAt(0) + "." + digits.substring(1);
            return sign + mantissa + "e" + (exp < 0 ? "-" : "+")
                    + String.format("%02d", Math.abs(exp));
        }
        if (dp <= 0) {
            return sign + "0." + "0".repeat(-dp) + digits;
        }
        if (dp >= digits.length()) {
            return sign + digits + "0".repeat(dp - digits.length());
        }
        return sign + digits.substring(0, dp) + "." + digits.substring(dp);
    }
}
