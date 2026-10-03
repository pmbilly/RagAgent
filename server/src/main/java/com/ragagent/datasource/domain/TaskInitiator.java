package com.ragagent.datasource.domain;


/**
 * 提交异步任务的已认证调用方。
 *
 * <p>worker 把它还原进自己的上下文，好让审计条目描述"是谁发起的"；
 * 调度器创建的任务则仍归于系统。</p>
 *
 * <h2>JSON 形状</h2>
 * <pre>
 *   TaskInitiator.empty()                       → {"userId":"","role":""}
 *   TaskInitiator{"user-1","admin"}             → {"userId":"user-1","role":"admin"}
 * </pre>
 * <p>键名＝组件名；两键**恒输出**。</p>
 *
 * <h2>⚠️ 放这个包是权宜，后续应提升</h2>
 * <p>它是"跨模块公用类型"（与 {@code com.ragagent.common.tenant.TenantRole} 同类），
 * 目前只有 datasource 用它，所以先落在本模块的 {@code domain} 下。
 * 等第二个模块需要它时，应提升到 {@code com.ragagent.common.context}
 * （与 {@code TenantContext} 同级），而不是各自复制一份。</p>
 *
 * <h2>为什么 {@code role} 是 {@code String} 而不是 {@code TenantRole} 枚举</h2>
 * <p>{@code role} 的取值就是字符串（{@code TenantRole} 的字面量）。
 * 用枚举会把"未知/未来角色"逼进 {@code UNKNOWN("")} 分支并**悄悄改写**载荷字节
 * （非 {@code owner}/{@code admin}/… 的取值会变成空串），与"原样透传"的目标相反。
 * 取用方需要等级判定时自行 {@code TenantRole.fromString(...)}。</p>
 *
 * <h2>⚠️ 不提供 {@code isEmpty()} 形态的便捷方法</h2>
 * <p>Jackson 会把 {@code isEmpty()} 当**布尔属性 {@code empty}** 写进 JSON
 * （{@code isXxx()} 是标准 bean 访问器前缀）。需要"是否是空发起人"时用
 * {@link #blank()}，或者直接判 {@link #userId()}。</p>
 */
public record TaskInitiator( String userId, String role) {

    /** 紧凑构造器把 null 归一成空串（消费侧不必再判 null）。 */
    public TaskInitiator {
        userId = userId == null ? "" : userId;
        role = role == null ? "" : role;
    }

    /**
     * 全零值。
     *
     * <p>上下文里"无用户"或主体是"合成 API-Key 用户"时用它
     * ——合成 Key 用户是服务身份、不是人，所以刻意留空，让活动流把它呈现为系统作业。</p>
     */
    public static TaskInitiator empty() {
        return new TaskInitiator("", "");
    }

    /**
     * 发起人是否为空（{@code userId} 为空串即"系统任务"兜底：
     * 还原进上下文时是 no-op）。
     *
     * <p>刻意**不叫** {@code isEmpty()}（那会变成 JSON 属性，见类注释）。</p>
     */
    public boolean blank() {
        return userId.isEmpty();
    }
}
