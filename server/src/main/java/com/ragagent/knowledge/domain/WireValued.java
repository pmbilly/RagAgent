package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 带 wire 值的知识状态枚举：JSON 值与库内存储值一致（小写单词），{@code @JsonValue} 返回原值，
 * 前端既有取值判断不受影响。
 */
public interface WireValued {

    /** 该枚举在 wire/库内使用的取值。 */
    String value();

    /** 按 wire 值查枚举；空值/未知值 → {@code null}。 */
    static <E extends Enum<E> & WireValued> E parse(E[] candidates, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        for (E candidate : candidates) {
            if (candidate.value().equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}
