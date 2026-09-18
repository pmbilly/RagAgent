package com.ragagent.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * HTTP 响应用的 mapper 是否按 Go 的规则转义字符串——**全局**契约。
 *
 * <h2>为什么需要这条测试</h2>
 * <p>Go 的 {@code encoding/json} 默认把 {@code < > &} 转义成 {@code <} / {@code >} /
 * {@code &}，Jackson 默认原样输出。这个差异**早就存在**，但 golden 契约文件里
 * {@code < > &} 的出现次数一直是 <b>0</b>，所以从没被测到——直到 SSE 的聊天正文
 * （散文，含 {@code &} 太正常）第一个踩上去。</p>
 *
 * <p>修法是在 {@code config.JacksonConfig} 里全局装上 {@link GoJsonEscapes}。
 * 本测试**注入容器里的那个 mapper**（不是自己 new 一个）：
 * 自己 new 的 mapper 测的是测试自己的配置，不是线上那条路。</p>
 *
 * <h2>期望值来源</h2>
 * <p>Go 实录——{@code json.Marshal(map[string]string{"v": …})} 的输出。</p>
 */
@SpringBootTest
class GoJsonEscapesContractTest {

    @Autowired
    private ObjectMapper mapper;

    private String jsonOf(String value) throws Exception {
        return mapper.writeValueAsString(Map.of("v", value));
    }

    /** 主用例：HTML 敏感字符。这正是 SSE 正文里天天出现的东西。 */
    @Test
    void htmlSensitiveCharactersMatchGo() throws Exception {
        assertThat(jsonOf("a < b & c > d")).isEqualTo("{\"v\":\"a \\u003c b \\u0026 c \\u003e d\"}");
        assertThat(jsonOf("slash</script>")).isEqualTo("{\"v\":\"slash\\u003c/script\\u003e\"}");
    }

    /**
     * ⚠️ {@code CharacterEscapes} 是**整表替换**而非叠加：装表时必须把
     * {@code \b \t \n \f \r \" \\} 这些"两边本来就一致"的短转义也显式声明，
     * 漏掉会直接漏成原文。阶段 5 在 Redis 那条路径上踩过这个坑，这条用例是它的永久防线。
     */
    @Test
    void shortEscapesAreStillEscaped() throws Exception {
        assertThat(jsonOf("quote\"back\\slash")).isEqualTo("{\"v\":\"quote\\\"back\\\\slash\"}");
        assertThat(jsonOf("tab\tnl\ncr\rbs\bff\f"))
                .isEqualTo("{\"v\":\"tab\\tnl\\ncr\\rbs\\bff\\f\"}");
    }

    /** 控制字符编码成 &#92;u00xx 形式，**小写十六进制**（Go 的写法）。 */
    @Test
    void controlCharactersUseLowercaseHex() throws Exception {
        assertThat(jsonOf("ctrl\u0001\u001f")).isEqualTo("{\"v\":\"ctrl\\u0001\\u001f\"}");
    }

    /** 非 ASCII 与增补平面字符**不转义**，按 UTF-8 原样输出。 */
    @Test
    void nonAsciiIsNotEscaped() throws Exception {
        assertThat(jsonOf("中文 & emoji 😀"))
                .isEqualTo("{\"v\":\"中文 \\u0026 emoji 😀\"}");
    }
}
