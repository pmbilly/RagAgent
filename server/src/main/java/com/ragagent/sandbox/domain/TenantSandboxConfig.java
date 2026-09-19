package com.ragagent.sandbox.domain;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoMapSerializer;

/**
 * 对照 Go {@code types.TenantSandboxConfig}（internal/types/tenant.go L622-684）。
 *
 * <p>一个工作区维护的一个具名沙箱后端配置。<b>自包含</b>：provider 字段不从进程环境继承，
 * 必填 provider 字段留空在保存时被拒。jsonb 落库 + 响应体双用（加密由
 * {@link TenantSandboxConfigTypeHandler} 在 Value/Scan 语义的位置做，打码/合并由
 * {@link SandboxConfigRedaction} 做）。</p>
 *
 * <p>全部字段带 omitempty（Go tag），逐字段 {@code NON_DEFAULT}（原始类型 0/false）
 * 与 {@code NON_EMPTY}（字符串空/容器空/指针 null）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TenantSandboxConfig {

    /** cube、e2b 或 docker；disabled 是隐藏的策略行 */
    @JsonProperty("sandbox_type")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String sandboxType = "";

    /** 单次执行超时（秒）；0 用程序内建默认 */
    @JsonProperty("default_timeout_sec")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int defaultTimeoutSec;

    /** 交互式终端无键入/PTY 输出多久后断开；0 用内建默认（15 分钟）。非身份字段。 */
    @JsonProperty("terminal_idle_disconnect_sec")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int terminalIdleDisconnectSec;

    /** 允许本工作区配置触达 RFC1918/loopback 集群端点；link-local/云元数据始终拒绝 */
    @JsonProperty("allow_private_endpoints")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean allowPrivateEndpoints;

    /**
     * 注入到该租户每个沙箱的额外环境变量。🔒 值落库加密（对租户内所有脚本可见）。
     * 挂 GoMapSerializer 对齐 Go 的 map 键字母序（存储与响应字节）。
     */
    @JsonProperty("env_vars")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonSerialize(using = GoMapSerializer.class)
    private Map<String, String> envVars;

    /** 可选共享卷挂载（当前用于租户 skills，但配置本身与 skill 无关） */
    @JsonProperty("volume_mount")
    private VolumeMountConfig volumeMount;

    /**
     * 指向携带本配置已安装 skills 的快照。空 = 用基础模板。只由 skill install/remove
     * 路径写入：MergeSandboxConfigForUpdate 忽略客户端值，设置表单存盘既不能抹掉也不能
     * 栽上这个指针。
     */
    @JsonProperty("skill_image")
    private SkillImageConfig skillImage;

    /** 空 / next_turn 下一聊天轮重建；new_session 让活沙箱留在旧镜像 */
    @JsonProperty("skill_rollout")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String skillRollout = "";

    /**
     * 应用于本配置创建的每个沙箱的出/入站网络策略。nil 与零值同义：出网放开、入站关闭。
     */
    @JsonProperty("network")
    private SandboxNetworkPolicy network;

    // ── 后端专属配置（同一时刻只有一个生效，由 sandbox_type 决定） ─────────

    @JsonProperty("cube")
    private CubeSandboxConfig cube;

    @JsonProperty("e2b")
    private E2BSandboxConfig e2b;

    @JsonProperty("docker")
    private DockerSandboxConfig docker;

    public String getSandboxType() { return sandboxType; }
    public void setSandboxType(String v) { sandboxType = v == null ? "" : v; }
    public int getDefaultTimeoutSec() { return defaultTimeoutSec; }
    public void setDefaultTimeoutSec(int v) { defaultTimeoutSec = v; }
    public int getTerminalIdleDisconnectSec() { return terminalIdleDisconnectSec; }
    public void setTerminalIdleDisconnectSec(int v) { terminalIdleDisconnectSec = v; }
    public boolean isAllowPrivateEndpoints() { return allowPrivateEndpoints; }
    public void setAllowPrivateEndpoints(boolean v) { allowPrivateEndpoints = v; }
    public Map<String, String> getEnvVars() { return envVars; }
    public void setEnvVars(Map<String, String> v) { envVars = v; }
    public VolumeMountConfig getVolumeMount() { return volumeMount; }
    public void setVolumeMount(VolumeMountConfig v) { volumeMount = v; }
    public SkillImageConfig getSkillImage() { return skillImage; }
    public void setSkillImage(SkillImageConfig v) { skillImage = v; }
    public String getSkillRollout() { return skillRollout; }
    public void setSkillRollout(String v) { skillRollout = v == null ? "" : v; }
    public SandboxNetworkPolicy getNetwork() { return network; }
    public void setNetwork(SandboxNetworkPolicy v) { network = v; }
    public CubeSandboxConfig getCube() { return cube; }
    public void setCube(CubeSandboxConfig v) { cube = v; }
    public E2BSandboxConfig getE2b() { return e2b; }
    public void setE2b(E2BSandboxConfig v) { e2b = v; }
    public DockerSandboxConfig getDocker() { return docker; }
    public void setDocker(DockerSandboxConfig v) { docker = v; }

    /**
     * 对照 {@code RebuildsExistingOnSkillChange}（Go 方法，非字段 → 必须 @JsonIgnore，
     * 否则写进 jsonb 回读即炸——§7.5 第 2 条复发率最高的坑）。未知值向"重建"失败：
     * 损坏的行不能把所有会话钉死在已退役的镜像上。
     */
    @JsonIgnore
    public boolean rebuildsExistingOnSkillChange() {
        return !SandboxConstants.SKILL_ROLLOUT_NEW_SESSION.equals(
                skillRollout == null ? "" : skillRollout.trim());
    }

    /** 浅拷贝（对照 Go 的 {@code cp := *c}；嵌套对象共享引用，密钥处理各自深拷） */
    public TenantSandboxConfig shallowCopy() {
        TenantSandboxConfig c = new TenantSandboxConfig();
        c.sandboxType = sandboxType;
        c.defaultTimeoutSec = defaultTimeoutSec;
        c.terminalIdleDisconnectSec = terminalIdleDisconnectSec;
        c.allowPrivateEndpoints = allowPrivateEndpoints;
        c.envVars = envVars;
        c.volumeMount = volumeMount;
        c.skillImage = skillImage;
        c.skillRollout = skillRollout;
        c.network = network;
        c.cube = cube;
        c.e2b = e2b;
        c.docker = docker;
        return c;
    }

    /** 深拷贝嵌套结构（VolumeMount/SkillImage 的复制语义，对照 Go 里各处的显式拷贝） */
    public static VolumeMountConfig copyVolumeMount(VolumeMountConfig v) {
        return v == null ? null : v.copy();
    }

    public static SkillImageConfig copySkillImage(SkillImageConfig v) {
        return v == null ? null : v.copy();
    }

    /** 新建可变 LinkedHashMap（jsonb 回读路径要求可变——业务代码会就地 put） */
    public static <K, V> Map<K, V> newMutableMap() {
        return new LinkedHashMap<>();
    }
}
