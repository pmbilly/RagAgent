package com.ragagent.llm.provider;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对照 Go provider.ExtraFieldConfig（provider.go），逐字段对齐 json tag：
 * key / label / type / required / default / placeholder / options(omitempty)。
 *
 * Go 的 Options 是匿名结构体切片 []struct{Label,Value}，Java 用嵌套 record {@link Option} 承载。
 * Go 的 json tag 是 "default"（Java 关键字无法做字段名）→ 组件名 defaultValue + @JsonProperty("default")。
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

    /** 对照 Go ExtraFieldConfig.Options 的元素匿名结构体 */
    public record Option(
            @JsonProperty("label") String label,
            @JsonProperty("value") String value) {
    }

    public ExtraFieldConfig {
        // Go 零值归一：string 零值 ""、nil 切片 → 空
        key = key == null ? "" : key;
        label = label == null ? "" : label;
        type = type == null ? "" : type;
        defaultValue = defaultValue == null ? "" : defaultValue;
        placeholder = placeholder == null ? "" : placeholder;
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** 便捷构造：无 options（对照 Go 里 Options 为 nil 的写法） */
    public ExtraFieldConfig(String key, String label, String type, boolean required,
                            String defaultValue, String placeholder) {
        this(key, label, type, required, defaultValue, placeholder, List.of());
    }
}
