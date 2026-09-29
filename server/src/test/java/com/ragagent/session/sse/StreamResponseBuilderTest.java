package com.ragagent.session.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.domain.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.stream.StreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link StreamResponseBuilder} 的逐字节契约测试。
 *
 * <h2>期望值的来源</h2>
 * <p>全部是 <b>Go 实录</b>：把 {@code internal/handler/session/helpers.go} 的
 * {@code buildStreamResponse} / {@code searchResultFromMap} / {@code getString} /
 * {@code getFloat64} 连同 {@code types.StreamResponse} / {@code types.SearchResult}
 * 的定义原样抄进一个独立 Go 程序，喂同样的输入，打印 {@code json.Marshal} 的结果，
 * 再原样抄进下面的字符串常量。</p>
 *
 * <p>这样钉住的不只是"字段有没有"，而是<b>键序</b>与<b>零值取舍</b>——
 * 正是本项目最容易漂移的两处：</p>
 * <ul>
 *   <li>{@code StreamResponse} / {@code SearchResult} 按 <b>struct 声明序</b>；</li>
 *   <li>{@code data} 与 {@code data.references[].*} 是 <b>map，按键字母序</b>；
 *       同一个引用在两处出现时键序<b>本来就不同</b>（{@code knowledge_references}
 *       是重建后的 struct，{@code data.references} 是原样的 map）。</li>
 * </ul>
 */
class StreamResponseBuilderTest {

    /** 与线上一致：默认 mapper（本类型不含时间字段，无需 JacksonConfig 的 OffsetDateTime 覆盖）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String write(StreamResponse response) throws Exception {
        return MAPPER.writeValueAsString(response);
    }

    // ── 场景 B：references 事件从 Redis 回放（data 里是退化后的 map） ──────────

    @Test
    void rebuildsReferencesFromRedisRoundTrippedMaps() throws Exception {
        StreamEvent evt = new StreamEvent("evt-1", ResponseType.REFERENCES, "", false);
        evt.setData(Map.of("references", List.of(redisRoundTrippedRef())));

        // 键序不属契约（不再字节序钉死）：knowledge_references 是 SearchResult 的序列化
        // （Java 字段名即键名）；data 是缓存映射直通，保持库内键名与未知键。
        String json = write(StreamResponseBuilder.build(evt, "req-1"));
        assertThat(json).contains("\"id\":\"req-1\",\"response_type\":\"references\"");
        assertThat(json).contains("\"knowledgeReferences\":[{\"id\":\"chunk-1\",\"content\":\"hello\"");
        assertThat(json).contains("\"knowledgeId\":\"kb-1\"");
        assertThat(json).contains("\"chunkIndex\":3");
        assertThat(json).contains("\"knowledgeTitle\":\"t\"");
        assertThat(json).contains("\"startAt\":10,\"endAt\":20");
        assertThat(json).contains("\"matchType\":0");
        assertThat(json).contains("\"subChunkId\":null");
        assertThat(json).contains("\"metadata\":{\"lang\":\"zh\"}");
        assertThat(json).contains("\"knowledgeFilename\":\"a.md\",\"knowledgeSource\":\"file\"");
        assertThat(json).contains("\"knowledgeBaseId\":\"kb-1\"");
        // data 直通：库内 snake 键 + 未知键原样带出
        assertThat(json).contains("\"data\":{\"references\":[{\"chunk_index\":3");
        assertThat(json).contains("\"extra_unknown_key\":\"ignored\"");
    }

    /**
     * 与 Go 的 {@code searchResultFromMap} 同形的输入——注意故意用<b>乱序</b>的
     * {@code LinkedHashMap}，并混入一个不认识的键，验证两件事：
     * 输出键序与插入序无关（Go 是 map，本来就没有插入序），未知键照旧回显在 {@code data} 里。
     */
    private static Map<String, Object> redisRoundTrippedRef() {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("knowledge_id", "kb-1");
        ref.put("id", "chunk-1");
        ref.put("chunk_index", 3);
        ref.put("content", "hello");
        ref.put("knowledge_title", "t");
        ref.put("start_at", 10);
        ref.put("end_at", 20);
        ref.put("seq", 2);
        ref.put("score", 0.75);
        ref.put("chunk_type", "text");
        ref.put("parent_chunk_id", "");
        ref.put("image_info", "");
        ref.put("knowledge_filename", "a.md");
        ref.put("knowledge_source", "file");
        ref.put("knowledge_description", "");
        ref.put("knowledge_base_id", "kb-1");
        ref.put("metadata", Map.of("lang", "zh"));
        ref.put("extra_unknown_key", "ignored");
        return ref;
    }

    // ── 场景 A：agent_query 提取会话/消息 ID ────────────────────────────────

    @Test
    void extractsSessionAndAssistantMessageIdForAgentQuery() throws Exception {
        StreamEvent evt = new StreamEvent("evt-2", ResponseType.AGENT_QUERY, "", true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assistant_message_id", "msg-1");
        data.put("session_id", "sess-1");
        evt.setData(data);

        assertThat(write(StreamResponseBuilder.build(evt, "req-2"))).isEqualTo(
                "{\"id\":\"req-2\",\"response_type\":\"agent_query\",\"content\":\"\",\"done\":true,"
                        + "\"sessionId\":\"sess-1\",\"assistantMessageId\":\"msg-1\","
                        + "\"data\":{\"assistantMessageId\":\"msg-1\",\"sessionId\":\"sess-1\"}}");
    }

    /** 非 agent_query 事件即便带了这两个键也<b>不</b>提取（Go 只在 agent_query 分支里取）。 */
    @Test
    void doesNotExtractIdsForOtherResponseTypes() throws Exception {
        StreamEvent evt = new StreamEvent("evt-6", ResponseType.ANSWER, "hi", false);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_id", "sess-9");
        data.put("assistant_message_id", "m9");
        data.put("event_id", "e1");
        evt.setData(data);

        assertThat(write(StreamResponseBuilder.build(evt, "req-6"))).isEqualTo(
                "{\"id\":\"req-6\",\"response_type\":\"answer\",\"content\":\"hi\",\"done\":false,"
                        + "\"data\":{\"assistantMessageId\":\"m9\",\"event_id\":\"e1\","
                        + "\"sessionId\":\"sess-9\"}}");
    }

    // ── 场景 C/D/E：references 数据不成立时的三种退路 ───────────────────────

    /** data 里没有 {@code references} 键 → 不设该字段（omitempty 让整键消失），data 原样带出。 */
    @Test
    void leavesReferencesUnsetWhenKeyAbsent() throws Exception {
        StreamEvent evt = new StreamEvent("evt-3", ResponseType.REFERENCES, "", false);
        evt.setData(Map.of("foo", "bar"));

        assertThat(write(StreamResponseBuilder.build(evt, "req-3"))).isEqualTo(
                "{\"id\":\"req-3\",\"response_type\":\"references\",\"content\":\"\",\"done\":false,"
                        + "\"data\":{\"foo\":\"bar\"}}");
    }

    /**
     * 元素不是 map → 逐个跳过。结果集是「空但非 nil」的 slice，
     * Go 的 omitempty 让整个键消失——所以输出与"没有引用"完全一样。
     */
    @Test
    void skipsNonMapReferenceElementsAndOmitsTheEmptyList() throws Exception {
        StreamEvent evt = new StreamEvent("evt-4", ResponseType.REFERENCES, "", false);
        evt.setData(Map.of("references", List.of("not-a-map", 42)));

        assertThat(write(StreamResponseBuilder.build(evt, "req-4"))).isEqualTo(
                "{\"id\":\"req-4\",\"response_type\":\"references\",\"content\":\"\",\"done\":false,"
                        + "\"data\":{\"references\":[\"not-a-map\",42]}}");
    }

    /** {@code references} 是字符串 → 一条类型分支都不命中 → 同样不设该字段。 */
    @Test
    void leavesReferencesUnsetForUnrecognisedShape() throws Exception {
        StreamEvent evt = new StreamEvent("evt-5", ResponseType.REFERENCES, "", false);
        evt.setData(Map.of("references", "oops"));

        assertThat(write(StreamResponseBuilder.build(evt, "req-5"))).isEqualTo(
                "{\"id\":\"req-5\",\"response_type\":\"references\",\"content\":\"\",\"done\":false,"
                        + "\"data\":{\"references\":\"oops\"}}");
    }

    // ── 活路径（不经 Redis）：直接就是 SearchResult 对象 ─────────────────────

    /**
     * 活路径下 {@code data["references"]} 已经是 {@code List<SearchResult>}
     * （Go 的 {@code []*SearchResult} 分支），<b>不做</b> map 重建——
     * 于是 {@code match_type} / {@code sub_chunk_id} / 各个 omitempty 字段都保留原值。
     */
    @Test
    void passesThroughLiveSearchResultsWithoutRebuilding() throws Exception {
        SearchResult live = new SearchResult();
        live.setId("chunk-2");
        live.setContent("world");
        live.setKnowledgeId("kb-2");
        live.setMatchType(3);
        live.setScore(1.0);
        live.setSubChunkId(List.of("sub-1"));
        live.setChunkMetadata(MAPPER.readTree("{\"questions\":[\"q\"]}"));

        StreamEvent evt = new StreamEvent("evt-7", ResponseType.REFERENCES, "", false);
        evt.setData(Map.of("references", new ArrayList<>(List.of(live))));

        // 活对象直通：两侧都按 SearchResult 序列化（camelCase）
        String json = write(StreamResponseBuilder.build(evt, "req-7"));
        assertThat(json).contains("\"knowledgeReferences\":[{\"id\":\"chunk-2\",\"content\":\"world\"");
        assertThat(json).contains("\"knowledgeId\":\"kb-2\"");
        assertThat(json).contains("\"matchType\":3");
        assertThat(json).contains("\"subChunkId\":[\"sub-1\"]");
        assertThat(json).contains("\"chunkMetadata\":{\"questions\":[\"q\"]}");
        // score 是 1.0，Go 输出 1（不是 1.0）——GoDoubleSerializer 的职责
        assertThat(json).contains("\"score\":1,");
        assertThat(json).contains("\"data\":{\"references\":[{\"id\":\"chunk-2\"");
    }

    /** 空 {@code data} → Go 的 omitempty 整键省略（不是输出 {@code "data":{}}）。 */
    @Test
    void omitsEmptyDataMap() throws Exception {
        StreamEvent evt = new StreamEvent("evt-8", ResponseType.ANSWER, "x", true);
        evt.setData(Map.of());

        assertThat(write(StreamResponseBuilder.build(evt, "req-8"))).isEqualTo(
                "{\"id\":\"req-8\",\"response_type\":\"answer\",\"content\":\"x\",\"done\":true}");
    }

    // ── 与 Go 一致的"不拷贝"语义 ────────────────────────────────────────────

    /** Go 是 {@code Data: evt.Data}（同一引用），Java 照抄——下游改写会同时反映到事件上。 */
    @Test
    void sharesTheDataReferenceWithTheEvent() {
        StreamEvent evt = new StreamEvent("evt-8", ResponseType.ANSWER, "x", false);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("k", "v");
        evt.setData(data);

        StreamResponse response = StreamResponseBuilder.build(evt, "req-8");
        assertThat(response.getData()).isSameAs(data);
    }

    // ── SSE 头 ─────────────────────────────────────────────────────────────

    @Test
    void setsTheFourSseHeaders() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        SseContract.setSSEHeaders(response);

        assertThat(response.getHeader("Content-Type")).isEqualTo("text/event-stream");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(response.getHeader("Connection")).isEqualTo("keep-alive");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
    }

    /**
     * {@code setSSEHeaders} 是<b>覆盖</b>语义（对照 Go 的 {@code c.Header}），
     * 调两次不应出现两个同名头。
     */
    @Test
    void sseHeadersAreOverwrittenNotAppended() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.addHeader("Cache-Control", "max-age=60");

        SseContract.setSSEHeaders(response);

        assertThat(response.getHeaders("Cache-Control")).containsExactly("no-cache");
    }

    /** {@code sendCompletionEvent} 是刻意的空实现——调用它不产生任何输出。 */
    @Test
    void sendCompletionEventIsDeliberatelyEmpty() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("text/event-stream");

        SseContract.sendCompletionEvent(response, "req-9");

        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
