package com.ragagent.sandbox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ragagent.session.domain.Message;
import com.ragagent.session.mapper.MessageRepository;
import com.ragagent.stream.LiveRun;
import com.ragagent.stream.MemoryStreamManager;
import com.ragagent.stream.StreamEvent;

/**
 * install steer sink 纯单测（对照 Go installSteerSink：PollSteer/PersistSteerMessage/
 * closeIfDrained）。Redis 面用 {@link MemoryStreamManager}（同一批 live-run/steer 键
 * 语义的内存替身），消息面用 Mockito mock 的裸仓储。
 */
class InstallSteerSinkTest {

    private static void seedSteer(MemoryStreamManager streams, String sessionId,
            String messageId, String id, String content, boolean consumed) {
        StreamEvent evt = new StreamEvent(id, null, content, false);
        if (consumed) {
            evt.setData(Map.of("consumed", true));
        }
        streams.appendSteerEvents(sessionId, messageId, List.of(evt));
    }

    @Test
    void uuidV5MatchesGoNewSHA1NameSpaceOID() {
        // Go 实录：uuid.NewSHA1(uuid.NameSpaceOID, []byte(name))（/tmp/weknora-probe）
        assertEquals("2c9f3cef-3a18-5419-af47-a8b9fd86bd2d",
                InstallSteerSink.uuidV5NameSpaceOid("sess-1:msg-a1:steer-1"));
        assertEquals("fd2f19d5-916f-5eec-895c-8ba5d471947b",
                InstallSteerSink.uuidV5NameSpaceOid("s:m:x"));
    }

    @Test
    void pollSteerRefreshesDedicatedLiveRunAndSkipsConsumed() {
        MemoryStreamManager streams = new MemoryStreamManager();
        seedSteer(streams, "sess-1", "msg-a1", "s1", "install flake8", false);
        seedSteer(streams, "sess-1", "msg-a1", "s2", "already done", true);
        InstallSteerSink sink = new InstallSteerSink(streams, mock(MessageRepository.class),
                new Object(), new SkillInstallTranscript(null, streams, null,
                        "sess-1", "msg-a1", null));

        List<Map<String, Object>> polled = sink.pollSteer("sess-1", "msg-a1", 0);

        assertEquals(1, polled.size());
        assertEquals("s1", polled.get(0).get("id"));
        assertEquals("install flake8", polled.get(0).get("content"));

        LiveRun live = streams.getLiveRun(TenantSkillService.installSteerSession("sess-1"));
        assertNotNull(live, "独立命名空间的 live-run 标记被刷新");
        assertEquals("msg-a1", live.assistantMessageId());

        LiveRun plain = streams.getLiveRun("sess-1");
        assertFalse(plain.isPresent(), "普通聊天命名空间不受影响（Go 注释原文：steering 隔离）");
        assertNull(sink.error());
    }

    @Test
    void persistSteerMessagePersistsRowAndMarksConsumed() {
        MemoryStreamManager streams = new MemoryStreamManager();
        seedSteer(streams, "sess-1", "msg-a1", "s1", "install flake8", false);
        MessageRepository messages = mock(MessageRepository.class);
        InstallSteerSink sink = new InstallSteerSink(streams, messages, new Object(),
                new SkillInstallTranscript(null, streams, null, "sess-1", "msg-a1", null));

        // Go 实录：uuid.NewSHA1(uuid.NameSpaceOID, []byte("sess-1:msg-a1:s1"))
        // （/tmp/weknora-probe 探针，两轮稳定）
        String id = sink.persistSteerMessage("sess-1", "msg-a1", "s1", "install flake8",
                null, "web");

        assertEquals("dfe7707d-1b85-5054-bca8-ab0a3c9c5b5f", id,
                "确定性 ID = UUIDv5(OID, session:message:steer)");

        ArgumentCaptor<Message> row = ArgumentCaptor.forClass(Message.class);
        org.mockito.Mockito.verify(messages).create(row.capture());
        assertEquals("user", row.getValue().getRole());
        assertEquals("install flake8", row.getValue().getContent());
        assertTrue(row.getValue().isCompleted());

        StreamEvent evt = streams.getSteerEvents("sess-1", "msg-a1", 0).events().get(0);
        assertEquals(Boolean.TRUE, evt.getData().get("consumed"));
        assertEquals(id, evt.getData().get("user_message_id"));
        assertEquals(List.of("install flake8"), sink.guidance());
    }

    @Test
    void persistSteerMessageRetrySafeWhenRowAlreadyExists() {
        MemoryStreamManager streams = new MemoryStreamManager();
        seedSteer(streams, "sess-1", "msg-a1", "s1", "install flake8", false);
        MessageRepository messages = mock(MessageRepository.class);
        // create 失败（如首试 consumed 写炸后 HTTP 重试），GetMessage 命中同内容行
        doThrow(new IllegalStateException("duplicate key")).when(messages).create(any(Message.class));
        Message existing = new Message();
        existing.setSessionId("sess-1");
        existing.setContent("install flake8");
        when(messages.getMessage("sess-1", "dfe7707d-1b85-5054-bca8-ab0a3c9c5b5f"))
                .thenReturn(existing);
        InstallSteerSink sink = new InstallSteerSink(streams, messages, new Object(),
                new SkillInstallTranscript(null, streams, null, "sess-1", "msg-a1", null));

        String id = sink.persistSteerMessage("sess-1", "msg-a1", "s1", "install flake8",
                null, "web");

        assertEquals("dfe7707d-1b85-5054-bca8-ab0a3c9c5b5f", id);
        assertNull(sink.error());
    }

    @Test
    void persistSteerMessageFailsWhenExistingRowDoesNotMatch() {
        MemoryStreamManager streams = new MemoryStreamManager();
        seedSteer(streams, "sess-1", "msg-a1", "s1", "install flake8", false);
        MessageRepository messages = mock(MessageRepository.class);
        doThrow(new IllegalStateException("duplicate key")).when(messages).create(any(Message.class));
        Message other = new Message();
        other.setSessionId("sess-1");
        other.setContent("something else");
        when(messages.getMessage("sess-1", "dfe7707d-1b85-5054-bca8-ab0a3c9c5b5f"))
                .thenReturn(other);
        InstallSteerSink sink = new InstallSteerSink(streams, messages, new Object(),
                new SkillInstallTranscript(null, streams, null, "sess-1", "msg-a1", null));

        String id = sink.persistSteerMessage("sess-1", "msg-a1", "s1", "install flake8",
                null, "web");

        assertEquals("", id, "空 ID = 持久化失败（引擎不把文本追加进消息，下次 drain 重试）");
        assertNotNull(sink.error());
        assertTrue(sink.guidance().isEmpty());
    }

    @Test
    void closeIfDrainedWithNoEventsAlsoClearsTheMarker() {
        // 对照 Go：遍历事件列表无未消费项（含空列表）→ 走到 ClearLiveRun → closed=true
        MemoryStreamManager streams = new MemoryStreamManager();
        streams.setLiveRun(TenantSkillService.installSteerSession("sess-1"), "msg-a1", "");
        InstallSteerSink sink = new InstallSteerSink(streams, mock(MessageRepository.class),
                new Object(), new SkillInstallTranscript(null, streams, null,
                        "sess-1", "msg-a1", null));
        assertTrue(sink.closeIfDrained());
        assertFalse(streams.getLiveRun(TenantSkillService.installSteerSession("sess-1"))
                .isPresent());
    }

    @Test
    void closeIfDrainedWithAllConsumedClearsTheMarker() {
        MemoryStreamManager streams = new MemoryStreamManager();
        seedSteer(streams, "sess-1", "msg-a1", "s1", "install flake8", true);
        seedSteer(streams, "sess-1", "msg-a1", "s2", "and pytest", true);
        streams.setLiveRun(TenantSkillService.installSteerSession("sess-1"), "msg-a1", "");
        Object lock = new Object();
        InstallSteerSink sink = new InstallSteerSink(streams, mock(MessageRepository.class),
                lock, new SkillInstallTranscript(null, streams, null, "sess-1", "msg-a1", null));

        assertTrue(sink.closeIfDrained());
        assertFalse(streams.getLiveRun(TenantSkillService.installSteerSession("sess-1"))
                .isPresent(), "live-run 标记被清掉");
        assertNull(sink.error());
    }

    @Test
    void closeIfDrainedKeepsMarkerWhileGuidancePending() {
        MemoryStreamManager streams = new MemoryStreamManager();
        seedSteer(streams, "sess-1", "msg-a1", "s1", "install flake8", true);
        seedSteer(streams, "sess-1", "msg-a1", "s2", "and pytest", false);
        streams.setLiveRun(TenantSkillService.installSteerSession("sess-1"), "msg-a1", "");
        InstallSteerSink sink = new InstallSteerSink(streams, mock(MessageRepository.class),
                new Object(), new SkillInstallTranscript(null, streams, null,
                        "sess-1", "msg-a1", null));

        assertFalse(sink.closeIfDrained());
        assertTrue(streams.getLiveRun(TenantSkillService.installSteerSession("sess-1"))
                .isPresent(), "还有未消费的指引，标记保留");
    }
}
