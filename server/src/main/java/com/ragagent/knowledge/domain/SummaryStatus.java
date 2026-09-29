package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 摘要（描述自动生成）状态。 */
public enum SummaryStatus implements WireValued {
        /** 未生成（如描述为空）。 */
        NONE("none"),
        /** 手工知识的草稿态（正文已存、未走解析管线）。 */
        DRAFT("draft"),
        PENDING("pending"),
        PROCESSING("processing"),
        COMPLETED("completed"),
        FAILED("failed");

    private final String value;

    SummaryStatus(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static SummaryStatus from(String raw) {
        return WireValued.parse(values(), raw);
    }
}
