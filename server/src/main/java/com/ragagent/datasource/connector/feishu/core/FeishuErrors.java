package com.ragagent.datasource.connector.feishu.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 飞书错误分类。
 *
 * <h2>为什么要有这一层</h2>
 * <p>飞书原始错误里带着 status / JSON body / log_id。把它们直接丢给用户在
 * Airbyte/Fivetran/Onyx 那类产品里是被明确警告的反模式（泄漏内部细节、不可本地化）。
 * 这里把原始错误分类成一个<b>稳定的 i18n code</b>（前端据此取本地化文案）
 * + 一个<b>可选的飞书数字错误码</b>（用于插值）+ 一句<b>英文兜底文案</b>
 * （没有 i18n key 的客户端用）。原始 body 只留在服务端日志里。</p>
 *
 * <p>分类对"重试语义"有直接影响：瞬时错误（限流/超时/5xx）下次同步会重试
 * （游标保留），而鉴权/权限错误要用户去改配置——所以文案里绝不能写
 * "会自动重试"。</p>
 *
 * <h2>两点实现注记</h2>
 * <ol>
 *   <li>{@code \s} 的集合：这里的 {@code \s} <b>含</b> {@code \x0B}（垂直制表符）；
 *       错误串里不会出现它，故无影响。</li>
 *   <li>正则只用到最基础的分支，无引擎差异带来的语义差别。</li>
 * </ol>
 */
public final class FeishuErrors {

    /**
     * 飞书数字错误码的匹配模式：{@code code["\s]*[:=]\s*(\d+)}。
     *
     * <p>刻意<b>不</b>匹配裸的 "code" 子串：{@code decode}/{@code encode}/{@code unicode}
     * 里都含 "code"，但后面没有 {@code "} / 空白 + {@code :} / {@code =}，
     * 所以不会被误判成飞书 API 错误。</p>
     */
    private static final Pattern FEISHU_ERROR_CODE = Pattern.compile("code[\"\\s]*[:=]\\s*(\\d+)");

    private FeishuErrors() {
    }

    /**
     * 从原始错误串里尽力提取飞书数字错误码。
     * 提不到就回空串。
     */
    public static String feishuErrorCode(String raw) {
        if (raw == null) {
            return "";
        }
        Matcher m = FEISHU_ERROR_CODE.matcher(raw);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    /**
     * 分类结果的三元组。
     *
     * @param code     稳定的 i18n code
     * @param codeValue 飞书数字错误码（无则空串）
     * @param fallback 英文兜底文案
     */
    public record Failure(String code, String codeValue, String fallback) {
    }

    /**
     * 把原始连接器/API 错误分类成
     * {@code (i18n code, 飞书错误码, 英文兜底)}。
     *
     * <p><b>分支判定顺序有语义</b>（鉴权/权限先于限流，限流先于兜底）：</p>
     * <ol>
     *   <li>鉴权/权限（auth error / invalid access token / permission / forbidden / status=403）</li>
     *   <li>限流（rate limited / status=429）</li>
     *   <li>超时（timed out / timeout / deadline exceeded）</li>
     *   <li>5xx（server error）</li>
     *   <li>API 错误（api error / export task failed / download failed）——带得出数字码就用
     *       {@code feishu_api_error}，否则 {@code feishu_api_error_generic}</li>
     *   <li>兜底 {@code sync_failed}</li>
     * </ol>
     * <p>匹配一律在原文的小写形式上做。</p>
     */
    public static Failure feishuFailure(RuntimeException err) {
        if (err == null) {
            return new Failure("sync_failed", "", "Sync failed; will retry on the next sync");
        }
        String s = err.getMessage() == null ? "" : err.getMessage().toLowerCase();

        if (s.contains("auth error")
                || s.contains("invalid access token")
                || s.contains("permission")
                || s.contains("forbidden")
                || s.contains("status=403")) {
            return new Failure("feishu_auth_or_permission", "",
                    "Authentication or permission error; check credentials and app scopes");
        }
        if (s.contains("rate limited") || s.contains("status=429")) {
            return new Failure("feishu_rate_limited", "",
                    "Feishu API rate limited; will retry on the next sync");
        }
        if (s.contains("timed out") || s.contains("timeout") || s.contains("deadline exceeded")) {
            return new Failure("feishu_timeout", "",
                    "Export or request timed out; will retry on the next sync");
        }
        if (s.contains("server error")) {
            return new Failure("feishu_server_unavailable", "",
                    "Feishu service temporarily unavailable; will retry on the next sync");
        }
        if (s.contains("api error") || s.contains("export task failed") || s.contains("download failed")) {
            String v = feishuErrorCode(err.getMessage());
            if (!v.isEmpty()) {
                return new Failure("feishu_api_error", v,
                        "Feishu API error (code=" + v + "); will retry on the next sync");
            }
            return new Failure("feishu_api_error_generic", "",
                    "Feishu API error; will retry on the next sync");
        }
        return new Failure("sync_failed", "", "Sync failed; will retry on the next sync");
    }

    /**
     * 构造一个失败条目的 metadata——
     * 原始错误（给服务端日志）+ 分类结果（给前端本地化）+ 调用方补充的额外键。
     *
     * <p>键名：{@code error} / {@code error_reason_code} /
     * {@code error_reason} / 有值时的 {@code error_reason_code_value}。</p>
     *
     * <p><b>{@code extra} 覆盖同名的内置键</b>。
     * 键序无所谓：序列化时按键名字典序输出。</p>
     */
    public static Map<String, String> feishuErrorItemMeta(RuntimeException err, Map<String, String> extra) {
        if (err == null) {
            // 调用点约定 err 非 null；这里只是不让 NPE 替掉真正的语义。
            err = new RuntimeException("");
        }
        Failure f = feishuFailure(err);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("error", err.getMessage() == null ? "" : err.getMessage());
        m.put("error_reason_code", f.code());
        m.put("error_reason", f.fallback());
        if (!f.codeValue().isEmpty()) {
            m.put("error_reason_code_value", f.codeValue());
        }
        if (extra != null) {
            m.putAll(extra);
        }
        return m;
    }
}
