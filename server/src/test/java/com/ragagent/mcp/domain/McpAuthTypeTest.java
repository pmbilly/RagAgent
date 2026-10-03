package com.ragagent.mcp.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 鉴权策略枚举的线上取值契约。
 *
 * <p>取值面是"枚举值（数据值）"而非字段名：按契约文档 §1.7 一律小写
 * （{@code api_key} / {@code bearer} / {@code oauth} / 空串），<b>不是 camel</b>。</p>
 *
 * <p>为什么要钉：2026-10-03 点检实锤——前端发 {@code "apiKey"}（camel），
 * {@link McpAuthType#fromValue} 不匹配返回 null，策略被静默写成空，
 * 用户"配了 API Key 却不生效"。本测试同时钉住三件事：
 * ① 线上取值不许改名（改名要连同前端与存量行一起迁移）；
 * ② 读侧保持宽松（未知 → null，容忍历史行，勿改成抛错）；
 * ③ 写侧严格（未知 → 抛错，由控制器转 400）。</p>
 */
class McpAuthTypeTest {

    @Test
    void wireValuesAreLowercaseAndPinned() {
        List<String> values = Arrays.stream(McpAuthType.values()).map(McpAuthType::value).toList();
        assertEquals(List.of("", "api_key", "bearer", "oauth"), values);
    }

    @Test
    void fromValueIsLenientForReads() {
        assertEquals(McpAuthType.NONE, McpAuthType.fromValue(null));
        assertEquals(McpAuthType.NONE, McpAuthType.fromValue(""));
        assertEquals(McpAuthType.API_KEY, McpAuthType.fromValue("api_key"));
        assertNull(McpAuthType.fromValue("apiKey"),
                "camel 取值不属于本枚举（前端曾发它 → 策略静默丢失）");
    }

    @Test
    void parseStrictRejectsUnknownWrites() {
        assertEquals(McpAuthType.NONE, McpAuthType.parseStrict(null));
        assertEquals(McpAuthType.NONE, McpAuthType.parseStrict(""));
        assertEquals(McpAuthType.API_KEY, McpAuthType.parseStrict("api_key"));
        assertEquals(McpAuthType.OAUTH, McpAuthType.parseStrict("oauth"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> McpAuthType.parseStrict("apiKey"));
        assertEquals(true, e.getMessage().contains("unknown authType"));
    }
}
