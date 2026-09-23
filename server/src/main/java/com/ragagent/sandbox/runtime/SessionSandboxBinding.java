package com.ragagent.sandbox.runtime;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 记录分配给会话的远程沙箱（对照 Go internal/sandbox/session_binding.go L38-77 全文）。
 *
 * <p>顶层类型：{@link RemoteSessionLifecycle} 与 {@link SessionBoundManager} 都按
 * 本类的简单名引用；此前它嵌在 {@link SessionSandboxBindingStore} 内，本文件把它
 * 原样提升出来（字段/注解逐字未动），Store 的方法签名随之指向本类。</p>
 *
 * <p>JSON 键名与 Go 的 json tag 逐字一致；omitempty 字段
 * （provider/config_id/stale_at/traffic_access_token）为 null 时省略，其余恒输出
 * ——由 {@link JsonInclude} 控制。tenant_id 是 JSON 数字（Go uint64 → long；
 * Lua 脚本按 cjson 文本判字段，绝不能变成 x.0 形态）。created_at/stale_at 为
 * RFC3339/ISO-8601 字符串（Go time.Time 形态）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SessionSandboxBinding {

    @JsonProperty("version")
    public int version;

    @JsonProperty("provider")
    public String provider;

    @JsonProperty("tenant_id")
    public long tenantId;

    @JsonProperty("session_id")
    public String sessionId;

    @JsonProperty("sandbox_id")
    public String sandboxId;

    @JsonProperty("template_id")
    public String templateId;

    @JsonProperty("created_at")
    public Instant createdAt;

    /**
     * 沙箱由哪个沙箱配置创建——让"该配置的每个沙箱"可从绑定存储应答，
     * 而不依赖 provider 可达。该字段出现前的旧绑定为空且仍有效（升级不断会话）。
     */
    @JsonProperty("config_id")
    public String configId;

    /** 该绑定的沙箱启动的镜像已被配置替换；下次 resolve 时销毁重建。 */
    @JsonProperty("stale_at")
    public Instant staleAt;

    /**
     * Cube/E2B 在 create 时签发的入站凭据（bearer）。Cube 可能在 pause/resume 后
     * 的 Connect 里重签，生命周期把新值写回此处；这是凭据在 WeKnora 重启后
     * 唯一的存活处。Docker 为空。绝不打日志。
     */
    @JsonProperty("traffic_access_token")
    public String trafficAccessToken;

    /** 对照 Validate：对照当前 schema 与权威 key 检查绑定。 */
    public void validate(SessionSandboxBindingStore.SessionSandboxKey key) {
        key.validate();
        if (version != RemoteSessionLifecycle.SESSION_SANDBOX_BINDING_VERSION) {
            throw new IllegalArgumentException(String.format(
                    "sandbox binding version must be %d, got %d",
                    RemoteSessionLifecycle.SESSION_SANDBOX_BINDING_VERSION, version));
        }
        if (!SandboxTypes.isNamedSandboxBackendType(provider)) {
            throw new IllegalArgumentException(
                    String.format("unsupported sandbox binding provider \"%s\"", provider));
        }
        if (tenantId != key.tenantId() || !java.util.Objects.equals(sessionId, key.sessionId())) {
            throw new IllegalArgumentException("sandbox binding identity does not match its key");
        }
        if (sandboxId == null || sandboxId.strip().isEmpty()) {
            throw new IllegalArgumentException("sandbox binding requires sandbox ID");
        }
        if (templateId == null || templateId.strip().isEmpty()) {
            throw new IllegalArgumentException("sandbox binding requires template ID");
        }
        if (createdAt == null || createdAt.toEpochMilli() == 0) {
            throw new IllegalArgumentException("sandbox binding requires creation time");
        }
    }

    public SessionSandboxBinding copy() {
        SessionSandboxBinding c = new SessionSandboxBinding();
        c.version = version;
        c.provider = provider;
        c.tenantId = tenantId;
        c.sessionId = sessionId;
        c.sandboxId = sandboxId;
        c.templateId = templateId;
        c.createdAt = createdAt;
        c.configId = configId;
        c.staleAt = staleAt;
        c.trafficAccessToken = trafficAccessToken;
        return c;
    }
}
