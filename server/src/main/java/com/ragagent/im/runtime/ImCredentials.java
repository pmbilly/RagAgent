package com.ragagent.im.runtime;

import java.util.Map;

/**
 * 渠道凭据/模式小助手。
 */
public final class ImCredentials {

    private ImCredentials() {
    }

    /** 解析 JSONB credentials 字段为 map；空输入 → 空 map。解析失败抛 IllegalArgumentException。 */
    public static Map<String, Object> parseCredentials(byte[] data) {
        if (data == null || data.length == 0) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(data, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** 从凭据 map 安全取字符串；缺失或非字符串返回 ""。 */
    public static String getString(Map<String, Object> creds, String key) {
        if (creds != null && creds.get(key) instanceof String s) {
            return s;
        }
        return "";
    }

    /**
     * 读布尔（JSON bool、字符串 "true"/"1"/"yes"、非零数字）。
     */
    public static boolean getBool(Map<String, Object> creds, String key) {
        if (creds == null || !creds.containsKey(key)) {
            return false;
        }
        Object v = creds.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            String norm = s.strip().toLowerCase();
            return norm.equals("true") || norm.equals("1") || norm.equals("yes");
        }
        if (v instanceof Double d) {
            return d != 0;
        }
        if (v instanceof Integer i) {
            return i != 0;
        }
        if (v instanceof Long l) {
            return l != 0;
        }
        return false;
    }

    /** channel.Mode 为空回落 def。 */
    public static String resolveMode(com.ragagent.im.domain.ImChannelEntity channel, String def) {
        if (channel.getMode() == null || channel.getMode().isEmpty()) {
            return def;
        }
        return channel.getMode();
    }
}
