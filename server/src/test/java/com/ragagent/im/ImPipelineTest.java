package com.ragagent.im;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.ragagent.TestSchema;
import com.ragagent.im.runtime.AdapterInterfaces.Adapter;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.ragagent.im.service.ImService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * W5γ2：IM 管线端到端（回调 → ACK → 去重/命令/会话解析 → QA → 回复送达）。
 * 平台侧用内存 fake 适配器（γ3 之前即可全链路验证 γ2 的编排）；QA 管线在测试
 * 环境无模型 → 回复落在失败兜底文案——这本身就是 Go imErrorFallback 路径的
 * 行为等价锚。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImPipelineTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final String EMU = "b0000000-0000-0000-0000-000000000701";
    private static final String AG = "b1000000-0000-0000-0000-000000000701";
    private static final String CH = "c1000000-0000-0000-0000-000000000701";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ImService imService;

    /** fake 适配器收到的回复（阻塞队列供测试等待）。 */
    static final BlockingQueue<ReplyMessage> REPLIES = new LinkedBlockingQueue<>();
    static final AtomicReference<IncomingMessage> LAST_MSG = new AtomicReference<>();

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.execute("INSERT INTO tenants (id, name, description, business, status) VALUES "
                + "(10008, 'im-pipeline-tenant', '', '', 'active')");
        jdbc.update("INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES "
                + "(?, 'impipeline', 'im-pipeline@weknora.test', ?, 10008, true)", EMU, BCRYPT);
        jdbc.update("INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES "
                + "(?, 10008, 'owner', 'active')", EMU);
        jdbc.update("INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, "
                + "created_by, config) VALUES (?, 'im-agent', 'im pipeline agent', '', FALSE, 10008, ?, '{}')",
                AG, EMU);
        jdbc.update("INSERT INTO im_channels (id, tenant_id, agent_id, platform, name, enabled, mode, "
                + "output_mode, knowledge_base_id, bot_identity, session_mode, credentials) VALUES "
                + "(?, 10008, ?, 'testplat', 'pipeline-bot', TRUE, 'webhook', 'full', '', '', 'user', '{}')",
                CH, AG);
        REPLIES.clear();
        registerFakeFactory();
    }

    private void registerFakeFactory() {
        imService.registerAdapterFactory("testplat", (channel, handler) -> {
            Adapter fake = new Adapter() {
                @Override
                public String platform() {
                    return "testplat";
                }

                @Override
                public Exception verifyCallback(com.ragagent.im.runtime.CallbackExchange exchange) {
                    return null;
                }

                @Override
                public IncomingMessage parseCallback(com.ragagent.im.runtime.CallbackExchange exchange) {
                    String body = new String(exchange.body(), StandardCharsets.UTF_8);
                    String text = body.replaceAll(".*\"text\":\"", "")
                            .replaceAll("\".*", "");
                    if (text.isEmpty()) {
                        return null; // 非消息事件
                    }
                    IncomingMessage m = IncomingMessage.of("testplat", "im-user-1", text);
                    m.messageId = exchange.query("msg_id");
                    return m;
                }

                @Override
                public void sendReply(IncomingMessage incoming, ReplyMessage reply) {
                    LAST_MSG.set(incoming);
                    REPLIES.add(reply);
                }

                @Override
                public boolean handleURLVerification(com.ragagent.im.runtime.CallbackExchange exchange) {
                    return false;
                }
            };
            return new com.ragagent.im.service.ImService.AdapterRegistration(fake, null);
        });
    }

    @Test
    void callbackAckAndReplyPipeline() throws Exception {
        // URL verification 由适配器直写——fake 恒 false，走完整管线。
        MvcResult ack = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/im/callback/" + CH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"message\",\"text\":\"你好\"}"))
                .andReturn();
        assertEquals(200, ack.getResponse().getStatus());
        assertEquals("{\"success\":true}", ack.getResponse().getContentAsString(StandardCharsets.UTF_8));

        // QA 失败兜底（测试环境无模型）：消息经会话解析/排队/执行后送回兜底文案。
        ReplyMessage reply = REPLIES.poll(30, TimeUnit.SECONDS);
        assertNotNull(reply, "管线应在超时前送达一条回复");
        assertTrue(reply.isFinal, "非流式回复是最后一片");
        assertEquals("抱歉，处理您的问题时出现了异常，请稍后再试。", reply.content,
                "QA 失败 → imErrorFallback 兜底（service.go L42）");
        assertNotNull(LAST_MSG.get());
        assertEquals("testplat", LAST_MSG.get().platform);

        // 会话解析落库：一条 channel_session 映射 + 一个新会话。
        Integer mappings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM im_channel_sessions WHERE platform='testplat' AND tenant_id=10008",
                Integer.class);
        assertEquals(1, mappings);
        String sessionId = jdbc.queryForObject(
                "SELECT session_id FROM im_channel_sessions WHERE platform='testplat' AND tenant_id=10008",
                String.class);
        String title = jdbc.queryForObject("SELECT title FROM sessions WHERE id=?", String.class,
                sessionId);
        // 有文本 → 起始标题为空（等首条消息后按内容起标题，Go imInitialSessionTitle）。
        assertEquals("", title == null ? "" : title);
        String description = jdbc.queryForObject(
                "SELECT description FROM sessions WHERE id=?", String.class, sessionId);
        assertEquals("Auto-created from testplat IM integration", description);
    }

    @Test
    void duplicateMessageIsSkipped() throws Exception {
        String body = "{\"type\":\"message\",\"text\":\"dup\"}";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/im/callback/" + CH + "?msg_id=fixed-dedup-id")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        ReplyMessage first = REPLIES.poll(30, TimeUnit.SECONDS);
        assertNotNull(first, "第一条应完整走管线");
        int afterFirst = REPLIES.size();
        // 第二条带同一 message_id → 去重（fake 从 query 解析 msg_id 作 MessageID）。
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/im/callback/" + CH + "?msg_id=fixed-dedup-id")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        Thread.sleep(500);
        assertEquals(afterFirst, REPLIES.size(), "重复消息不应产生第二条回复");
    }

    @Test
    void missingChannelIs404AndDisabledIs503() throws Exception {
        MvcResult missing = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/im/callback/does-not-exist")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals("{\"error\":\"channel not found\"}",
                missing.getResponse().getContentAsString(StandardCharsets.UTF_8));

        jdbc.update("UPDATE im_channels SET enabled = FALSE WHERE id = ?", CH);
        MvcResult disabled = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/im/callback/" + CH)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertEquals(503, disabled.getResponse().getStatus());
        assertEquals("{\"error\":\"channel is disabled\"}",
                disabled.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
