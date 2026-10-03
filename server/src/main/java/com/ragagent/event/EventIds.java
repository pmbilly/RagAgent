package com.ragagent.event;

import java.util.UUID;

/**
 * 事件 ID 生成。
 *
 * <p>格式为 v4 UUID 的<b>前 8 位十六进制</b> + 连字符 + 类型后缀，例如
 * {@code 286fbbe5-thinking}。纯函数，引擎各 emit 点共用（见包注释的 emit 表）。</p>
 *
 * <p>注意与 {@link Event#newUuid()}（Emit 的空 ID 兜底，完整 36 位 UUID）是两回事，别合并。</p>
 */
public final class EventIds {

    /**
     * 生成带类型后缀的事件 ID。
     *
     * @param suffix 类型后缀，如 {@code "thinking"} / {@code "answer"} / {@code "complete"}
     * @return {@code <uuid前8位>-<suffix>}
     */
    public static String generateEventID(String suffix) {
        return UUID.randomUUID().toString().substring(0, 8) + "-" + suffix;
    }

    private EventIds() {
    }
}
