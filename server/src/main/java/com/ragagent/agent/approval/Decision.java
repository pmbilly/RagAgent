package com.ragagent.agent.approval;

/**
 * 一次待决审批的结果（对照 Go approval.Decision，gate.go:76-83）。
 *
 * <p>{@code ModifiedArgs} 在 Go 里是 {@code json.RawMessage}（原始 JSON 对象字节），
 * Java 侧用 {@code String} 承载同一段原始 JSON，空串归一为 {@code null}
 * （对齐 Go 的 omitempty / len(ModifiedArgs)==0 语义）。</p>
 *
 * <p>字段顺序与 Go struct 声明序一致：Approved / ModifiedArgs / Reason / TimedOut / ContextCanceled。</p>
 */
public record Decision(
        boolean approved,
        String modifiedArgs,
        String reason,
        boolean timedOut,
        boolean contextCanceled) {

    public Decision {
        // Go: ModifiedArgs json.RawMessage —— 空字节切片与 nil 都视为“未修改”
        if (modifiedArgs == null || modifiedArgs.isBlank()) {
            modifiedArgs = null;
        }
        if (reason == null) {
            reason = "";
        }
    }

    // 注意：静态工厂不能叫 approved()/reason() 等与 record 访问器同名的名字（Java 禁止），
    // 故批准用 allow / allowWith，与 Go 的结构体字面量一一对应关系见各自注释。

    /** 对照 Go MCPTool 里的 {@code Decision{Approved: true}}（无超时/取消） */
    public static Decision allow() {
        return new Decision(true, null, "", false, false);
    }

    /** 批准并携带替换用的参数（对照 Go {@code Decision{Approved: true, ModifiedArgs: ...}}） */
    public static Decision allowWith(String modifiedArgs) {
        return new Decision(true, modifiedArgs, "", false, false);
    }

    /** 对照 Go {@code Decision{Approved: false, Reason: ...}}（用户拒绝 / 内部拒绝） */
    public static Decision deny(String reason) {
        return new Decision(false, null, reason, false, false);
    }

    /** 对照 Go RequestAndWait 超时分支：{@code "approval timeout"} / OAuth 的 {@code "authorization timeout"} */
    public static Decision timeout(String reason) {
        return new Decision(false, null, reason, true, false);
    }

    /** 对照 Go 取消分支：{@code Decision{Reason: "request canceled", ContextCanceled: true}} */
    public static Decision cancel(String reason) {
        return new Decision(false, null, reason, false, true);
    }
}
