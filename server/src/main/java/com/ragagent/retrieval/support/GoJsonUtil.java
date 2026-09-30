package com.ragagent.retrieval.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ragagent.common.web.GoJsonEscapes;

/**
 * searchutil 包内的 Go {@code encoding/json} 等价编解码：marshal 走 HTML/控制字符
 * 转义表（{@link GoJsonEscapes}，对照 json.Marshal 的 escapeHTML）；parse 容忍未知
 * 字段（json.Unmarshal 语义）。
 */
public final class GoJsonUtil {

    private static final JsonMapper MARSHAL = JsonMapper.builder().build();
    static {
        MARSHAL.getFactory().setCharacterEscapes(new GoJsonEscapes());
    }

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
