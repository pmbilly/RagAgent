package com.ragagent.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具自带调用方可配输出预算时的硬顶接口（对照 Go registry 的
 * {@code outputLimitProvider}，registry.go:27-30）。
 *
 * <p>registry 的通用限额不能把工具显式选择的有界输出又砍回去：
 * 当工具声明的限额大于 registry 预算时以工具为准。</p>
 */
public interface OutputLimitProvider {

    /** 该次调用的输出上限（runes）；仅当大于 registry 预算时生效。 */
    int outputLimitChars(JsonNode args);
}
