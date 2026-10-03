package com.ragagent.llm.provider;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 服务商额外字段元数据，JSON 键逐字段固定：
 * key / label / type / required / default / placeholder / options（为空省略）。
 *
 * options 用嵌套 record {@link Option} 承载；
 * JSON 键 "default" 与 Java 关键字冲突 → 组件名 defaultValue + @JsonProperty("default")。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtraFieldConfig(
        @JsonProperty("key") String key,
        @JsonProperty("label") String label,
        @JsonProperty("type") String type,
        @JsonProperty("required") boolean required,
        @JsonProperty("default") String defaultValue,
        @JsonProperty("placeholder") String placeholder,
        @JsonProperty("options") List<Option> options) {

    /** options 数组元素 */
    public record Option(
            @JsonProperty("label") String label,
            @JsonProperty("value") String value) {
    }

    public ExtraFieldConfig {
        // 缺省归一：null 字符串 → ""，null 列表 → 空列表
        key = key == null ? "" : key;
        label = label == null ? "" : label;
        type = type == null ? "" : type;
        defaultValue = defaultValue == null ? "" : defaultValue;
        placeholder = placeholder == null ? "" : placeholder;
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** 便捷构造：无 options */
    public ExtraFieldConfig(String key, String label, String type, boolean required,
                            String defaultValue, String placeholder) {
        this(key, label, type, required, defaultValue, placeholder, List.of());
    }
}
