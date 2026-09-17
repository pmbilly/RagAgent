package com.ragagent.llm.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 区分"真正的缓存未命中"与"该 provider 根本不上报缓存账目"
 * （对照 Go types.PromptCacheStatus，internal/types/chat.go:11-18）。
 *
 * 把两者都当成 0 会让全fleet 的命中率看板失真，故保留四态。
 */
public enum PromptCacheStatus {

    /** 该 provider/model 路径无法上报缓存用量 */
    UNSUPPORTED("unsupported"),
    /** 会上报但本次没有上报（如流式路径未携带） */
    UNREPORTED("unreported"),
    /** 上报了且未命中 */
    MISS("miss"),
    /** 上报了且命中 */
    HIT("hit");

    private final String value;

    PromptCacheStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static PromptCacheStatus fromValue(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        for (PromptCacheStatus s : values()) {
            if (s.value.equals(v)) {
                return s;
            }
        }
        return null;
    }
}
