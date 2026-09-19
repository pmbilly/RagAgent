package com.ragagent.sandbox.domain;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeDeserializer;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 对照 Go {@code types.SkillImageConfig}（internal/types/tenant.go L839-854）。
 *
 * <p>指向携带本配置已安装 skills 的快照；快照 ID 兼作 template ID。不含密钥，
 * 因此不经 {@code TenantSandboxConfig.Value()} 加密。</p>
 *
 * <p>⚠️ {@code built_at} 是 Go 的 {@code time.Time}（<b>struct</b>）：
 * {@code omitempty} 对 struct 无效 → <b>恒输出</b>，零值输出
 * {@code "0001-01-01T00:00:00Z"} 字面量。故 Java 字段默认值直接持有 Go 零值时间
 * （§9 阶段 5.2 步 3：Jackson 对 null 不调自定义序列化器），并成对挂
 * 序列化/反序列化器使裸 mapper 双向自足。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillImageConfig {

    /** 当前生效的快照；空 = 基础模板 */
    @JsonProperty("snapshot_id")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String snapshotId = "";

    /** 每次成功的 install/remove 递增 */
    @JsonProperty("generation")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int generation;

    /** 本 generation 的产出时间（Go time.Time：恒输出，零值 = year-1 字面量） */
    @JsonProperty("built_at")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime builtAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    /** 本链最初构建自的模板；重建路径从它重来 */
    @JsonProperty("base_template_id")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String baseTemplateId = "";

    /** 快照所属 provider 账户指纹；不匹配 → 回落基础模板而非报错 */
    @JsonProperty("owner_fingerprint")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String ownerFingerprint = "";

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String v) { snapshotId = v == null ? "" : v; }
    public int getGeneration() { return generation; }
    public void setGeneration(int v) { generation = v; }
    public OffsetDateTime getBuiltAt() { return builtAt; }
    public void setBuiltAt(OffsetDateTime v) { builtAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v; }
    public String getBaseTemplateId() { return baseTemplateId; }
    public void setBaseTemplateId(String v) { baseTemplateId = v == null ? "" : v; }
    public String getOwnerFingerprint() { return ownerFingerprint; }
    public void setOwnerFingerprint(String v) { ownerFingerprint = v == null ? "" : v; }

    public SkillImageConfig copy() {
        SkillImageConfig c = new SkillImageConfig();
        c.snapshotId = snapshotId;
        c.generation = generation;
        c.builtAt = builtAt;
        c.baseTemplateId = baseTemplateId;
        c.ownerFingerprint = ownerFingerprint;
        return c;
    }
}
