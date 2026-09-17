package com.ragagent.llm.domain;

/**
 * provider prompt 缓存 TTL 控制（对照 Go chat.CacheRetention）。
 * 空值语义等同于 SHORT（默认 5 分钟缓存）。
 */
public enum CacheRetention {

    /** 关闭缓存标记 */
    NONE("none"),
    /** 默认 5 分钟缓存 */
    SHORT("short"),
    /** 请求 1h/24h（在 provider 接受的前提下） */
    LONG("long");

    private final String value;

    CacheRetention(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static CacheRetention fromValue(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        for (CacheRetention r : values()) {
            if (r.value.equals(v)) {
                return r;
            }
        }
        return null;
    }
}
