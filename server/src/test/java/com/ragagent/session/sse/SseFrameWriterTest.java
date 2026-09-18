package com.ragagent.session.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.ragagent.llm.domain.ResponseType;
import com.ragagent.llm.domain.StreamResponse;

/**
 * SSE 帧的**逐字节**契约测试——这是整套 A/B 的落点，字节错了前面全白做。
 *
 * <h2>期望值的来源</h2>
 * <p>把 {@code gin-contrib/sse} 的 {@code Encode}/{@code writeEvent}/{@code writeData}
 * 原样抄进一个独立 Go 程序，喂同样的 {@code StreamResponse}，打印帧字节，再抄进来。
 * 它一次钉住四件事：</p>
 * <ol>
 *   <li>帧骨架是 {@code event:message\ndata:<json>\n\n}——<b>冒号后没有空格</b>；</li>
 *   <li>JSON 走 Go 的转义（{@code < > &} → {@code < > &}，<b>小写十六进制</b>）；</li>
 *   <li>map 键按键排序（{@code data} 与嵌套 map）；</li>
 *   <li>字段序是 Go 的 struct 声明序，且 {@code id}/{@code response_type}/{@code content}/{@code done}
 *       恒输出。</li>
 * </ol>
 *
 * <p>第 2 条是最容易漏的：Spring 自带的 mapper **不**做 HTML 转义，
 * 直接用就会在含 {@code &} 的正文上分叉。</p>
 */
@SpringBootTest
class SseFrameWriterTest {

    /**
     * 用**容器里那个** {@link SseFrameWriter}——它注入的是应用统一的 mapper，
     * 转义规则由 {@code JacksonConfig} 全局装在上面。
     * <b>刻意不自己 new 一个 mapper</b>：那样测的是测试自己的配置，不是线上那条路。
     */
    @Autowired
    private SseFrameWriter writer;

    private MockHttpServletResponse write(StreamResponse payload) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        writer.write(response, payload);
        return response;
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        // 帧里全是 ASCII 的骨架 + UTF-8 的正文，按 UTF-8 解码才与 Go 的字节一致
        return new String(response.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void plainAnswerFrameMatchesGo() throws Exception {
        StreamResponse r = StreamResponse.of(ResponseType.ANSWER, "hi there", false);
        r.setId("req-1");

        assertThat(body(write(r))).isEqualTo(
                "event:message\n"
                        + "data:{\"id\":\"req-1\",\"response_type\":\"answer\","
                        + "\"content\":\"hi there\",\"done\":false}\n\n");
    }

    /** HTML 敏感字符必须按 Go 的规则转义——Spring 默认不转。 */
    @Test
    void htmlSensitiveCharactersAreEscapedLikeGo() throws Exception {
        StreamResponse r = StreamResponse.of(ResponseType.ANSWER, "a < b & c > d", false);
        r.setId("req-1");

        assertThat(body(write(r))).isEqualTo(
                "event:message\n"
                        + "data:{\"id\":\"req-1\",\"response_type\":\"answer\","
                        + "\"content\":\"a \\u003c b \\u0026 c \\u003e d\",\"done\":false}\n\n");
    }

    /** {@code data} 与其中的嵌套 map 都按键排序。 */
    @Test
    void dataMapsAreKeySortedLikeGo() throws Exception {
        StreamResponse r = StreamResponse.of(ResponseType.TOOL_CALL, "", false);
        r.setId("req-1");
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", 1);
        nested.put("a", 2);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool_name", "t");
        data.put("event_id", "e1");
        data.put("nested", nested);
        r.setData(data);

        assertThat(body(write(r))).isEqualTo(
                "event:message\n"
                        + "data:{\"id\":\"req-1\",\"response_type\":\"tool_call\",\"content\":\"\","
                        + "\"done\":false,\"data\":{\"event_id\":\"e1\","
                        + "\"nested\":{\"a\":2,\"z\":1},\"tool_name\":\"t\"}}\n\n");
    }

    /** 正文里的换行在 JSON 里是 {@code \n} 转义，**不能**真断行（否则帧就碎了）。 */
    @Test
    void newlinesStayEscapedInsideTheJson() throws Exception {
        StreamResponse r = StreamResponse.of(ResponseType.ANSWER, "line1\nline2", true);
        r.setId("req-1");

        assertThat(body(write(r))).isEqualTo(
                "event:message\n"
                        + "data:{\"id\":\"req-1\",\"response_type\":\"answer\","
                        + "\"content\":\"line1\\nline2\",\"done\":true}\n\n");
    }

    @Test
    void completeFrameMatchesGo() throws Exception {
        StreamResponse r = StreamResponse.of(ResponseType.COMPLETE, "", true);
        r.setId("req-1");

        assertThat(body(write(r))).isEqualTo(
                "event:message\n"
                        + "data:{\"id\":\"req-1\",\"response_type\":\"complete\","
                        + "\"content\":\"\",\"done\":true}\n\n");
    }

    /**
     * Content-Type 会被 SSE 渲染器**无条件覆盖**成带 charset 的那个值——
     * {@code setSSEHeaders} 设的 {@code text/event-stream} 不是线上的最终值。
     */
    @Test
    void renderedContentTypeOverridesThePlainOne() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        SseContract.setSSEHeaders(response);
        assertThat(response.getHeader("Content-Type")).isEqualTo("text/event-stream");

        SseFrameWriter.applyRenderedContentType(response);
        assertThat(response.getHeader("Content-Type")).isEqualTo("text/event-stream;charset=utf-8");
        // Cache-Control 是"没有才设"，所以 setSSEHeaders 的值保持不变
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
    }

    /** 非 ASCII 正文按 UTF-8 写出。 */
    @Test
    void nonAsciiContentIsUtf8() throws Exception {
        StreamResponse r = StreamResponse.of(ResponseType.ANSWER, "你好", true);
        r.setId("req-1");

        byte[] bytes = write(r).getContentAsByteArray();
        // 骨架里的 "content":"你好" 部分必须与 Go 写出的字节一致
        assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"content\":\"你好\"");
    }
}
