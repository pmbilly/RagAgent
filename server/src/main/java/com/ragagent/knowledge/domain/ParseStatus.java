package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 解析（摄取）状态机。 */
public enum ParseStatus implements WireValued {
        /** 手工知识的草稿态（正文已存、未走解析管线）。 */
        DRAFT("draft"),
        PENDING("pending"),
        PROCESSING("processing"),
        /** 正文完成、后置工序（图谱/收尾）仍在进行。 */
        FINALIZING("finalizing"),
        COMPLETED("completed"),
        FAILED("failed"),
        DELETING("deleting"),
        CANCELLED("cancelled");

    private final String value;

    ParseStatus(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ParseStatus from(String raw) {
        return WireValued.parse(values(), raw);
    }
}
