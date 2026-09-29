package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 启用状态。 */
public enum EnableStatus implements WireValued {
        ENABLED("enabled"),
        DISABLED("disabled");

    private final String value;

    EnableStatus(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static EnableStatus from(String raw) {
        return WireValued.parse(values(), raw);
    }
}
