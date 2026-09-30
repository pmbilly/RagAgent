package com.ragagent.session.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletResponse;

import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageNotFoundException;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.service.MessageService;
import com.ragagent.session.sse.SseFrameWriter;
import com.ragagent.session.sse.StreamEventEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.web.GoJsonEscapes;
import com.ragagent.session.service.SessionService;
import com.ragagent.stream.StreamBatch;
import com.ragagent.stream.StreamEvent;
import com.ragagent.stream.StreamManager;

/**
 * {@code continue-stream} 端点的行为契约（对照 Go {@code stream.go} 的
 * {@code ContinueStream}）。
 *
 * <p>重点是<b>错误映射</b>与<b>帧序列</b>：Go 在这条路径上有四种互不相同的失败
 * （两种 404 文案不同、一种 400、一种 403），还有"已经完成就只回放不进轮询"这条分支。
 * 这些都不是能从直觉推出来的。</p>
 */
class SessionStreamControllerTest {

    private static final String SESSION_ID = "sess-1";
    private static final String MESSAGE_ID = "msg-1";
    private static final String REQUEST_ID = "req-1";

    private SessionService sessionService;
    private MessageService messageService;
    private StreamManager streamManager;
    private StreamEventEmitter emitter;
    private SessionStreamController controller;

    /**
     * 与线上等价的 mapper：**必须**带上 Go 的转义表（线上由 {@code JacksonConfig} 全局装）。
     * {@code SseFrameWriterTest} 用容器里的真 bean 覆盖这条；这里只是为了让控制器测试
     * 不必启动整个 Spring 上下文。
     */
    private static ObjectMapper goEscapingMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().setCharacterEscapes(new GoJsonEscapes());
        return mapper;
    }

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        messageService = mock(MessageService.class);
        streamManager = mock(StreamManager.class);
        emitter = new StreamEventEmitter(new SseFrameWriter(goEscapingMapper()));
        controller = new SessionStreamController(
                sessionService, messageService, streamManager, emitter, absent(), absent(),
                // 租户服务桩：上下文里有 tenantId 但库里没有该租户 → 解析器按"无租户"降级
                // （与 A3-3 接线前传 null 的可见行为一致）
                new com.ragagent.auth.service.TenantService(null, null, null) {
                    @Override
                    public com.ragagent.auth.domain.Tenant getTenantById(long id) {
                        return null;
                    }
                });
        TenantContext.set(10002L, null, "viewer", false, "u-1", false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        APIKeyScopeContext.clear();
    }

    /** 端口目前没有生产实现——测试里就照实传"没有这个 bean"。 */
    private static <T> ObjectProvider<T> absent() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new NoSuchBeanDefinitionException("absent");
            }

            @Override
            public T getObject(Object... args) {
                throw new NoSuchBeanDefinitionException("absent");
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }
        };
    }

    private static StreamEvent event(String id, ResponseType type, String content, boolean done) {
        StreamEvent evt = new StreamEvent(id, type, content, done);
        evt.setTimestamp(java.time.OffsetDateTime.now());
        return evt;
    }

    private void givenSession() {
        when(sessionService.getSession(anyString())).thenReturn(new Session());
    }

    private void givenMessage(String requestId) {
        Message message = new Message();
        message.setId(MESSAGE_ID);
        message.setRequestId(requestId);
        when(messageService.getMessage(anyString(), anyString())).thenReturn(message);
    }

    private String invoke(String messageId, String resourceUrls, MockHttpServletResponse response)
            throws Exception {
        controller.continueStream(SESSION_ID, messageId, resourceUrls, response);
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    // ── 入参校验（都在写任何 SSE 头之前） ────────────────────────────────────

    @Test
    void missingMessageIdIs400() {
        assertThatThrownBy(() -> invoke(null, null, new MockHttpServletResponse()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Missing message ID");
    }

    /** 非法的 {@code resource_urls} 必须是普通 400——这正是"必须在 SSE 头之前解析"的理由。 */
    @Test
    void invalidResourceUrlsIs400() {
        assertThatThrownBy(() -> invoke(MESSAGE_ID, "yes-please", new MockHttpServletResponse()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("invalid resource_urls value");
    }

    /** 受知识库限制的 Key 求 public → **403**（不是 400）：请求合法，是授权范围不允许。 */
    @Test
    void publicForKbRestrictedKeyIs403() {
        APIKeyScopeContext.set(new TenantAPIKeyScope(1L, "tenant", false, List.of("kb-1"), null));

        assertThatThrownBy(() -> invoke(MESSAGE_ID, "public", new MockHttpServletResponse()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("not available for a knowledge-base-restricted API key");
    }

    // ── 两种 404 文案不同，别统一 ───────────────────────────────────────────

    @Test
    void sessionNotFoundIs404WithItsOwnMessage() {
        when(sessionService.getSession(anyString())).thenThrow(new SessionNotFoundException());

        assertThatThrownBy(() -> invoke(MESSAGE_ID, null, new MockHttpServletResponse()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("session not found");
    }

    /**
     * 消息不存在时用的是 {@code gorm.ErrRecordNotFound.Error()} 的原文
     * ——{@code "record not found"}，**不是** {@code "message not found"}。
     */
    @Test
    void messageNotFoundIs404WithGormWording() {
        givenSession();
        when(messageService.getMessage(anyString(), anyString()))
                .thenThrow(new MessageNotFoundException());

        assertThatThrownBy(() -> invoke(MESSAGE_ID, null, new MockHttpServletResponse()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("record not found");
    }

    /** {@code getMessage} 里那层会话可见性判定失败 → 404 用的是**会话**的文案。 */
    @Test
    void sessionNotVisibleFromMessageServiceIs404() {
        givenSession();
        when(messageService.getMessage(anyString(), anyString()))
                .thenThrow(new SessionNotFoundException());

        assertThatThrownBy(() -> invoke(MESSAGE_ID, null, new MockHttpServletResponse()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("session not found");
    }

    // ── 事件为空 → 手写信封（gin.H 是 map，键**按字母序**） ───────────────────

    @Test
    void emptyStreamIs404WithHandWrittenEnvelope() throws Exception {
        givenSession();
        givenMessage(REQUEST_ID);
        when(streamManager.getEvents(anyString(), anyString(), anyInt()))
                .thenReturn(StreamBatch.empty(0));

        MockHttpServletResponse response = new MockHttpServletResponse();
        String body = invoke(MESSAGE_ID, null, response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(body).isEqualTo("{\"error\":\"No stream events found\",\"success\":false}");
    }

    // ── 回放 ────────────────────────────────────────────────────────────────

    /** 已经完成的流：回放全部事件后**直接返回**，不进轮询。 */
    @Test
    void completedStreamReplaysAndReturnsWithoutPolling() throws Exception {
        givenSession();
        givenMessage(REQUEST_ID);
        List<StreamEvent> events = List.of(
                event("e1", ResponseType.AGENT_QUERY, "", true),
                event("e2", ResponseType.ANSWER, "hi", false),
                event("e3", ResponseType.COMPLETE, "", true));
        when(streamManager.getEvents(anyString(), anyString(), eq(0)))
                .thenReturn(new StreamBatch(events, 3));

        MockHttpServletResponse response = new MockHttpServletResponse();
        String body = invoke(MESSAGE_ID, null, response);

        assertThat(response.getHeader("Content-Type")).isEqualTo("text/event-stream;charset=utf-8");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(body).contains("\"response_type\":\"agent_query\"");
        assertThat(body).contains("\"response_type\":\"answer\"");
        assertThat(body).contains("\"response_type\":\"complete\"");
        // 回放顺序 = 存储顺序
        assertThat(body.indexOf("agent_query")).isLessThan(body.indexOf("\"answer\""));
        assertThat(body.indexOf("\"answer\"")).isLessThan(body.indexOf("\"complete\""));
    }

    /** 未完成的流：轮询到 {@code complete} 就收尾，帧按到达顺序追加。 */
    @Test
    void liveStreamPollsUntilComplete() throws Exception {
        givenSession();
        givenMessage(REQUEST_ID);
        when(streamManager.getEvents(anyString(), anyString(), eq(0)))
                .thenReturn(new StreamBatch(new ArrayList<>(
                        List.of(event("e1", ResponseType.ANSWER, "part1", false))), 1));
        when(streamManager.getEvents(anyString(), anyString(), eq(1)))
                .thenReturn(new StreamBatch(new ArrayList<>(List.of(
                        event("e2", ResponseType.ANSWER, "part2", false),
                        event("e3", ResponseType.COMPLETE, "", true))), 3));

        MockHttpServletResponse response = new MockHttpServletResponse();
        String body = invoke(MESSAGE_ID, null, response);

        assertThat(body).contains("part1").contains("part2").contains("\"complete\"");
        assertThat(body.indexOf("part1")).isLessThan(body.indexOf("part2"));
    }

    // ── 辅助函数 ────────────────────────────────────────────────────────────

    /** 对照 Go {@code secutils.SanitizeForLog}：换行/制表归一成空格，其余控制字符丢弃。 */
    @Test
    void sanitizeForLogMatchesGo() {
        assertThat(SessionStreamController.sanitizeForLog("a\nb")).isEqualTo("a b");
        assertThat(SessionStreamController.sanitizeForLog("a\r\nb")).isEqualTo("a  b");
        assertThat(SessionStreamController.sanitizeForLog("a\tb")).isEqualTo("a b");
        assertThat(SessionStreamController.sanitizeForLog("ab")).isEqualTo("ab");
        assertThat(SessionStreamController.sanitizeForLog("正常 id")).isEqualTo("正常 id");
        assertThat(SessionStreamController.sanitizeForLog(null)).isEmpty();
        assertThat(SessionStreamController.sanitizeForLog("")).isEmpty();
    }

    /** 手写信封的字节：gin.H 是 map → 键按字母序，所以 {@code error} 在前。 */
    @Test
    void handWrittenEnvelopeIsKeySorted() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        SessionStreamController.writeJsonError(response, 404, "Incomplete message not found");

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).isEqualTo("application/json; charset=utf-8");
        assertThat(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8))
                .isEqualTo("{\"error\":\"Incomplete message not found\",\"success\":false}");
    }
}
