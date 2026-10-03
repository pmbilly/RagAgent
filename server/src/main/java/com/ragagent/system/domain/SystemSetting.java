package com.ragagent.system.domain;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.ZeroTimeSerializer;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * system_settings 表实体（迁移 000053）。
 *
 * <p>JSON 响应形态（键名 = Java 字段名；可空字段显式 null）：
 * id, key, value（jsonb 原样内联）, valueType, category, description, isSecret,
 * requiresRestart, lastModifiedBy, createdAt, updatedAt, enumOptions, lastModifiedByName。</p>
 *
 * <p>⚠️ 虚拟行（registry 有、DB 无）的时间戳是 Go 零值 "0001-01-01T00:00:00Z"
 * ——字段默认值本身持有零值（null 序列化不走自定义序列化器的教训）。</p>
 */
@TableName(value = "system_settings", autoResultMap = true)
public class SystemSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** H2 保留字 → 列名带引号（PG 侧不加引号等价；MP 生成 SQL 时原样携带） */
    @com.baomidou.mybatisplus.annotation.TableField("\"key\"")
    private String key;

    /**
     * jsonb 列，响应里原样内联（JsonNode 直接输出）：
     * int → 42、string → "foo"、bool → true、string_list → ["a","b"]。
     * H2 保留字 → 列名带引号。
     */
    @TableField(value = "\"value\"", typeHandler = PgJsonTypeHandler.class)
    private JsonNode value;

    private String valueType;

    private String category;

    private String description;

    private boolean isSecret;

    private boolean requiresRestart;

    /** Go 非指针 string：DB NULL → ""（getter 归一化，恒输出） */
    private String lastModifiedBy;

    /** 虚拟行 = Go 零值时间；持久行 = DB 值 */
    private OffsetDateTime createdAt = ZeroTimeSerializer.ZERO_DATE_TIME;

    private OffsetDateTime updatedAt = ZeroTimeSerializer.ZERO_DATE_TIME;

    /** registry 元数据，落库前必须清空（未产出时显式 null） */
    @TableField(exist = false)
    private List<String> enumOptions;

    /** handler enrich（username → email 回落），未产出时显式 null */
    @TableField(exist = false)
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
