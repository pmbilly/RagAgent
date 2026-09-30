package com.ragagent.datasource.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 提交异步任务的已认证调用方（对照 Go {@code types.TaskInitiator}，
 * internal/types/context_helpers.go L72-79）。
 *
 * <p>worker 把它还原进自己的上下文，好让审计条目描述"是谁发起的"；
 * 调度器创建的任务则仍归于系统。</p>
 *
 * <h2>Go 实录（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   TaskInitiator{}                             → {}
 *   TaskInitiator{UserID:"user-1",Role:"admin"} → {"user_id":"user-1","role":"admin"}
 * </pre>
 * <p>两个字段都带 omitempty：<b>零值对象序列化成 {@code {}}</b>。</p>
 *
 * <h2>⚠️ 放这个包是权宜，后续应提升</h2>
 * <p>Go 的 {@code TaskInitiator} 住在 {@code internal/types}，被
 * knowledge / tag / knowledge_faq_import / datasource 等多处使用。Java 侧目前只有
 * datasource 用它（其它模块尚未翻译），所以先落在本模块的 {@code domain} 下——
 * 它与 {@code com.ragagent.common.tenant.TenantRole} 一样是"跨模块公用类型"。
 * 等第二个模块需要它时，应提升到 {@code com.ragagent.common.context}
 * （与 {@code TenantContext} 同级），而不是各自复制一份。</p>
 *
 * <h2>为什么 {@code role} 是 {@code String} 而不是 {@code TenantRole} 枚举</h2>
 * <p>Go 的 {@code Role} 是具名类型 {@code TenantRole}（底层 string），JSON 上就是字符串。
 * 用枚举会把"未知/未来角色"逼进 {@code UNKNOWN("")} 分支并**悄悄改写**载荷字节
 * （非 {@code owner}/{@code admin}/… 的取值会变成空串），与"原样透传"的目标相反。
 * 取用方需要等级判定时自行 {@code TenantRole.fromString(...)}。</p>
 *
 * <h2>⚠️ 不提供 {@code isEmpty()} 形态的便捷方法</h2>
 * <p>Jackson 会把 {@code isEmpty()} 当**布尔属性 {@code empty}** 写进 JSON
 * （{@code isXxx()} 是标准 bean 访问器前缀）。需要"是否是空发起人"时用
 * {@link #blank()}，或者直接判 {@link #userId()}。</p>
 */
@JsonPropertyOrder({"user_id", "role"})
public record TaskInitiator(
        @JsonProperty("user_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String userId,
        @JsonProperty("role") @JsonInclude(JsonInclude.Include.NON_EMPTY) String role) {

    /** 紧凑构造器把 null 归一成空串——{@code NON_EMPTY} 才能在 null/空串两种输入下都省略键。 */
    public TaskInitiator {
        userId = userId == null ? "" : userId;
        role = role == null ? "" : role;
    }

    /**
     * 对照 Go 的 {@code TaskInitiator{}}：全零值。
     *
     * <p>Go 的 {@code TaskInitiatorFromContext} 在"无用户"或"合成 API-Key 用户"时返回它
     * ——合成的 Key 用户是服务身份、不是人，所以刻意留空，让活动流把它呈现为系统作业。</p>
     */
    public static TaskInitiator empty() {
        return new TaskInitiator("", "");
    }

    /**
     * 对照 Go {@code Apply} 的短路条件 {@code i.UserID == ""}：
     * 空或历史载荷是 no-op，因此保留"系统任务"的兜底。
     *
     * <p>刻意**不叫** {@code isEmpty()}（那会变成 JSON 属性，见类注释）。</p>
     */
    public boolean blank() {
        return userId.isEmpty();
    }
}
