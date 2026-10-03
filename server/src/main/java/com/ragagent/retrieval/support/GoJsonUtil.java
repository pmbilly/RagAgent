package com.ragagent.retrieval.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * searchutil 包内的 JSON 编解码（marshal 标准转义；parse 容忍未知字段）。
 *
 * <p><b>2026-10-03（B39）</b>：Go 版已下线——不再复刻 Go 的 HTML 转义。</p>
 */
public final class GoJsonUtil {

    private static final JsonMapper MARSHAL = JsonMapper.builder().build();

    private static final JsonMapper UNMARSHAL = JsonMapper.builder().build();

    private GoJsonUtil() {
    }

    public static byte[] marshal(com.fasterxml.jackson.databind.node.ObjectNode node) {
        try {
            return MARSHAL.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode parse(String body) {
        try {
            return UNMARSHAL.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }
}
