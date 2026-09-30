package com.ragagent.system.domain;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * system_settings 表实体（对照 Go types.SystemSetting，迁移 000053）。
 *
 * GORM 隐式行为清单（约定 §3）：
 * - 钩子：无 BeforeCreate/AfterFind；created_at/updated_at 由 DB DEFAULT 填充，
 *   Upser 路径在 service 层显式赋值（GORM 的 autoUpdateTime 语义）
 * - 关联：无 Preload；enrichSettingsModifiedBy 在 service 层批量 join
 * - 软删除：无 DeletedAt（Reset 是物理 DELETE）
 * - 默认排序：Go 的 List 在 service 层按 key 排序（registry 序 + 额外行序），查询无 Order
 * - 唯一索引：key UNIQUE（迁移 000053）
 * - 自动时间戳：Upsert 时显式写 created_at/updated_at
 *
 * JSON 契约（Go struct 字段声明序；无嵌套信封——handler 直接序列化行）：
 * id, key, value(内联 JSON), value_type, category, description, is_secret,
 * requires_restart, last_modified_by, created_at, updated_at,
 * enum(gorm:"-" omitempty), last_modified_by_name(gorm:"-" omitempty)。
 *
 * ⚠️ 虚拟行（registry 有、DB 无）的时间戳是 Go 零值 "0001-01-01T00:00:00Z"
 * ——字段默认值本身持有零值（阶段 5.2 的教训：null 序列化不走自定义序列化器）。
 */
@TableName(value = "system_settings", autoResultMap = true)
@JsonPropertyOrder({
        "id", "key", "value", "value_type", "category", "description",
        "is_secret", "requires_restart", "last_modified_by", "created_at", "updated_at",
        "enum", "last_modified_by_name"
})
public class SystemSetting {

    @TableId(type = IdType.AUTO)
    @JsonProperty("id")
    private Long id;

    /** H2 保留字 → 列名带引号（PG 侧不加引号等价；MP 生成 SQL 时原样携带） */
    @com.baomidou.mybatisplus.annotation.TableField("\"key\"")
    @JsonProperty("key")
    private String key;

    /**
     * jsonb 列；types.JSON 的 MarshalJSON 语义 → 响应里原样内联（JsonNode 直接输出）。
     * int → 42、string → "foo"、bool → true、string_list → ["a","b"]。
     * H2 保留字 → 列名带引号。
     */
    @JsonProperty("value")
    @TableField(value = "\"value\"", typeHandler = PgJsonTypeHandler.class)
    private JsonNode value;

    @JsonProperty("value_type")
    private String valueType;

    @JsonProperty("category")
    private String category;

    @JsonProperty("description")
    private String description;

    @JsonProperty("is_secret")
    private boolean isSecret;

    @JsonProperty("requires_restart")
    private boolean requiresRestart;

    /** Go 非指针 string：DB NULL → ""（getter 归一化，恒输出） */
    @JsonProperty("last_modified_by")
    private String lastModifiedBy;

    /** 虚拟行 = Go 零值时间；持久行 = DB 值（零值走 GoTimeSerializer 的字面量输出） */
    @JsonProperty("created_at")
    private OffsetDateTime createdAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    /** gorm:"-"：registry 元数据，落库前必须清空（响应 omitempty） */
    @TableField(exist = false)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("enum")
    private List<String> enumOptions;

    /** gorm:"-"：handler enrich（username → email 回落），响应 omitempty */
    @TableField(exist = false)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("last_modified_by_name")
    private String lastModifiedByName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public JsonNode getValue() { return value; }
    public void setValue(JsonNode value) { this.value = value; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    /** Go 非指针 string：NULL → ""（IsBootstrapDefaultRow 依赖 trim 后非空判定） */
    public String getDescription() { return description == null ? "" : description; }
    public void setDescription(String description) { this.description = description; }
    /** 字段名带 is 前缀但 getter isIsSecret() 的隐式属性名与字段一致 → 合并为一个属性（§9 通用坑） */
    public boolean isIsSecret() { return isSecret; }
    public void setIsSecret(boolean secret) { isSecret = secret; }
    public boolean isRequiresRestart() { return requiresRestart; }
    public void setRequiresRestart(boolean requiresRestart) { this.requiresRestart = requiresRestart; }
    public String getLastModifiedBy() { return lastModifiedBy == null ? "" : lastModifiedBy; }
    public void setLastModifiedBy(String lastModifiedBy) { this.lastModifiedBy = lastModifiedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<String> getEnumOptions() { return enumOptions; }
    public void setEnumOptions(List<String> enumOptions) { this.enumOptions = enumOptions; }
    public String getLastModifiedByName() { return lastModifiedByName; }
    public void setLastModifiedByName(String lastModifiedByName) { this.lastModifiedByName = lastModifiedByName; }
}
