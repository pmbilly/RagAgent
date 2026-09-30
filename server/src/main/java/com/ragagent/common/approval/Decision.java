package com.ragagent.common.approval;

/**
 * 一次待决审批的结果。
 *
 * <p>{@code modifiedArgs} 承载原始 JSON（替换工具入参用），空串归一为 {@code null}，
 * 即“未修改”。</p>
 */
public record Decision(
        boolean approved,
        String modifiedArgs,
        String reason,
        boolean timedOut,
        boolean contextCanceled) {

    public Decision {
        // null 与空白串都视为“未修改”
        if (modifiedArgs == null || modifiedArgs.isBlank()) {
            modifiedArgs = null;
        }
        if (reason == null) {
            reason = "";
        }
    }

    // 注意：静态工厂不能叫 approved()/reason() 等与 record 访问器同名的名字（Java 禁止），
    // 故批准用 allow / allowWith。

    /** 批准（无超时/取消）。 */
    public static Decision allow() {
        return new Decision(true, null, "", false, false);
    }

    /** 批准并携带替换用的参数。 */
    public static Decision allowWith(String modifiedArgs) {
        return new Decision(true, modifiedArgs, "", false, false);
    }

    /** 拒绝（用户拒绝 / 内部拒绝）。 */
    public static Decision deny(String reason) {
        return new Decision(false, null, reason, false, false);
    }

    /** 超时决策；reason 通常为 {@code "approval timeout"} 或 OAuth 的 {@code "authorization timeout"}。 */
    public static Decision timeout(String reason) {
        return new Decision(false, null, reason, true, false);
    }

    /** 取消决策；{@code contextCanceled} 置位，reason 通常为 {@code "request canceled"}。 */
    public static Decision cancel(String reason) {
        return new Decision(false, null, reason, false, true);
    }
}
