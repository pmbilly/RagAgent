package com.ragagent.websearch.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 更新 provider 的请求契约钉（B17）。
 *
 * <p>该字段历史上是 Go 期的 snake 键（{@code is_default}），而**响应面早已是 camel**
 * （{@code isDefault}，真机实测确认为准）——同一个资源两个面两套写法，且请求侧还会静默忽略
 * camel 键（{@code @JsonIgnoreProperties(ignoreUnknown = true)}）。本批按 §2 第 4 条
 * 「JSON 字段名 = Java 字段名」收口到 camel，并**不留兼容别名**（§2 第 11 条）。</p>
 *
 * <p>钉住两点：camel 生效、snake 不再被接受（防旧键回流＝防"同一份数据又有第二条路径"）。</p>
 */
class WebSearchProviderRequestBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("请求体：camel isDefault 生效；snake is_default 不再绑定")
    void bindsCamelOnly() throws Exception {
        WebSearchProviderController.UpdateProviderRequest camel = MAPPER.readValue(
                "{\"name\":\"n\",\"isDefault\":true}",
                WebSearchProviderController.UpdateProviderRequest.class);
        assertThat(camel.isDefault()).as("camel isDefault 必须生效").isTrue();

        WebSearchProviderController.UpdateProviderRequest snake = MAPPER.readValue(
                "{\"name\":\"n\",\"is_default\":true}",
                WebSearchProviderController.UpdateProviderRequest.class);
        assertThat(snake.isDefault()).as("snake 旧键不再绑定（无兼容别名）").isNull();
    }
}
