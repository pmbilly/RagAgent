package com.ragagent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.ragagent.TestSchema;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageArtifact;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.MessageImage;
import com.ragagent.session.domain.MessageNotFoundException;
import com.ragagent.session.domain.Session;
import com.ragagent.session.mapper.MessageRepository;
import com.ragagent.session.mapper.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 消息仓储语义（H2）——对照 Go internal/application/repository/message.go。
 *
 * <p>重点是那些 mock 测不出来的 SQL 行为：<b>结构体 Updates 的零值跳过</b>、软删、
 * 倒序取 + 正序重排的比较器、jsonb 列的落库形态。</p>
 *
 * <p>⚠️ {@code @AutoConfigureMockMvc} 是为了与其余契约测试共用同一个 Spring 上下文
 * 缓存键（理由见 {@code TenantAPIKeyRepositoryTest} 的类注释）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageRepositoryTest {

    private static final long TENANT = 10002L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MessageRepository repo;
    @Autowired
    private SessionRepository sessionRepo;

    private String sessionId;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        Session s = new Session();
        s.setTenantId(TENANT);
        s.setTitle("t");
        s.setDescription("");
        s.setUserId("u1");
        sessionId = sessionRepo.create(s).getId();
    }

    private Message message(String role, String content, String requestId) {
        Message m = new Message();
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setRequestId(requestId);
        return repo.create(m);
    }

    // ── 创建 ────────────────────────────────────────────────────────────────

    @Test
    void createAssignsAFreshIdAndWritesEmptyJsonbArrays() {
        Message m = message(Message.ROLE_USER, "hi", "r1");

        assertThat(m.getId()).isNotBlank();
        // 钩子把 nil 切片置空，各 Value() 也把 nil 写成 []——所以库里是 [] 不是 NULL
        assertThat(jdbc.queryForObject(
                "SELECT knowledge_references FROM messages WHERE id = ?", String.class, m.getId()))
                .isEqualTo("[]");
        assertThat(jdbc.queryForObject(
                "SELECT images FROM messages WHERE id = ?", String.class, m.getId()))
                .isEqualTo("[]");
    }

    @Test
    void createOverwritesAnyCallerSuppliedId() {
        Message m = new Message();
        m.setSessionId(sessionId);
        m.setRole(Message.ROLE_USER);
        m.setContent("x");
        m.setId("caller-id");
        assertThat(repo.create(m).getId()).isNotEqualTo("caller-id");
    }

    // ── 更新：GORM 结构体 Updates 的零值跳过 ─────────────────────────────────

    @Test
    void updateSkipsZeroValuedFields() {
        // 这是本模块最容易"顺手修好"的地方：GORM 对结构体的 Updates 只写非零字段，
        // 所以把 content 改成空串、把 is_completed 改成 false 都**不会生效**。
        Message m = message(Message.ROLE_ASSISTANT, "original", "r1");
        m.setCompleted(true);
        repo.update(m);

        Message blanking = new Message();
        blanking.setId(m.getId());
        blanking.setSessionId(sessionId);
        blanking.setContent("");         // 零值 → 跳过
        blanking.setCompleted(false);    // 零值 → 跳过
        blanking.setAgentDurationMs(0);  // 零值 → 跳过
        repo.update(blanking);

        Message after = repo.getMessage(sessionId, m.getId());
        assertThat(after.getContent()).isEqualTo("original");
        assertThat(after.isCompleted()).isTrue();
    }

    @Test
    void updateWritesNonZeroFields() {
        Message m = message(Message.ROLE_ASSISTANT, "original", "r1");

        Message patch = new Message();
        patch.setId(m.getId());
        patch.setSessionId(sessionId);
        patch.setContent("updated");
        patch.setCompleted(true);
        patch.setAgentDurationMs(123);
        patch.setModelId("md1");
        repo.update(patch);

        Message after = repo.getMessage(sessionId, m.getId());
        assertThat(after.getContent()).isEqualTo("updated");
        assertThat(after.isCompleted()).isTrue();
        assertThat(after.getAgentDurationMs()).isEqualTo(123);
        assertThat(after.getModelId()).isEqualTo("md1");
    }

    @Test
    void updateWithEveryFieldZeroIsANoOp() {
        // GORM 全部为零时会生成 `UPDATE messages SET` 这种非法语句；Java 侧直接跳过
        Message m = message(Message.ROLE_ASSISTANT, "original", "r1");
        Message empty = new Message();
        empty.setId(m.getId());
        empty.setSessionId(sessionId);
        repo.update(empty);

        assertThat(repo.getMessage(sessionId, m.getId()).getContent()).isEqualTo("original");
    }

    // ── 读 ──────────────────────────────────────────────────────────────────

    @Test
    void getMessageIsScopedToItsSession() {
        Message m = message(Message.ROLE_USER, "hi", "r1");
        assertThatThrownBy(() -> repo.getMessage("other-session", m.getId()))
                .isInstanceOf(MessageNotFoundException.class);
    }

    @Test
    void getMessageByRequestIdReturnsNullInsteadOfThrowing() {
        // 与同文件其它读方法不同：Go 在这里把 ErrRecordNotFound 显式翻成 nil, nil
        assertThat(repo.getMessageByRequestId(sessionId, "nope")).isNull();

        message(Message.ROLE_USER, "hi", "r1");
        assertThat(repo.getMessageByRequestId(sessionId, "r1")).isNotNull();
    }

    @Test
    void getFirstMessageOfUserIgnoresAssistantMessages() {
        message(Message.ROLE_ASSISTANT, "a0", "r0");
        Message first = message(Message.ROLE_USER, "q1", "r1");
        message(Message.ROLE_USER, "q2", "r2");

        assertThat(repo.getFirstMessageOfUser(sessionId).getId()).isEqualTo(first.getId());
    }

    @Test
    void softDeleteHidesTheRowFromReads() {
        Message m = message(Message.ROLE_USER, "hi", "r1");
        repo.delete(sessionId, m.getId());

        assertThatThrownBy(() -> repo.getMessage(sessionId, m.getId()))
                .isInstanceOf(MessageNotFoundException.class);
        // 行还在，只是打了删除标记
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM messages WHERE id = ?", Object.class, m.getId())).isNotNull();
    }

    // ── 排序 ────────────────────────────────────────────────────────────────

    @Test
    void recentMessagesComeBackAscendingRegardlessOfTheQueryOrder() {
        message(Message.ROLE_USER, "q1", "r1");
        message(Message.ROLE_ASSISTANT, "a1", "r1");
        message(Message.ROLE_USER, "q2", "r2");

        List<Message> recent = repo.getRecentMessagesBySession(sessionId, 10);
        // SQL 是 created_at DESC，收上来之后再正序重排
        assertThat(recent).extracting(Message::getContent).containsExactly("q1", "a1", "q2");
    }

    @Test
    void paginationUsesOffsetAndLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            message(Message.ROLE_USER, "q" + i, "r" + i);
            Thread.sleep(5);   // 让 created_at 严格递增，否则排序不稳定
        }

        assertThat(repo.getMessagesBySession(sessionId, 1, 2))
                .extracting(Message::getContent).containsExactly("q0", "q1");
        assertThat(repo.getMessagesBySession(sessionId, 2, 2))
                .extracting(Message::getContent).containsExactly("q2");
    }

    @Test
    void listAfterTimeOnlyReturnsNewerMessages() throws Exception {
        message(Message.ROLE_USER, "old", "r1");
        Thread.sleep(10);
        var watermark = java.time.OffsetDateTime.now();
        Thread.sleep(10);
        message(Message.ROLE_USER, "new", "r2");

        assertThat(repo.listMessagesBySessionAfterTime(sessionId, watermark, 10))
                .extracting(Message::getContent).containsExactly("new");
    }

    @Test
    void ownedSessionIdsNarrowsToTheOwnersSessions() {
        Session mine = new Session();
        mine.setTenantId(TENANT);
        mine.setTitle("mine");
        mine.setDescription("");
        mine.setUserId("u1");
        String mineId = sessionRepo.create(mine).getId();

        Map<String, Boolean> owned = repo.ownedSessionIds(TENANT, "u1", List.of(mineId, sessionId));
        assertThat(owned).containsOnlyKeys(mineId, sessionId);

        // 换一个 owner：只认自己那份（以及历史空 owner 行）
        Session theirs = new Session();
        theirs.setTenantId(TENANT);
        theirs.setTitle("theirs");
        theirs.setDescription("");
        theirs.setUserId("u2");
        String theirsId = sessionRepo.create(theirs).getId();

        assertThat(repo.ownedSessionIds(TENANT, "u2", List.of(mineId, theirsId)))
                .containsOnlyKeys(theirsId);
    }

    // ── 投影 ────────────────────────────────────────────────────────────────

    @Test
    void sessionArtifactsAreFlattenedInCreationOrder() throws Exception {
        MessageArtifact a1 = new MessageArtifact();
        a1.setUrl("p://1");
        a1.setFileName("one");

        Message m1 = message(Message.ROLE_ASSISTANT, "a", "r1");
        m1.setArtifacts(List.of(a1));
        repo.update(m1);
        Thread.sleep(5);

        MessageArtifact a2 = new MessageArtifact();
        a2.setUrl("p://2");
        a2.setFileName("two");
        Message m2 = message(Message.ROLE_ASSISTANT, "b", "r2");
        m2.setArtifacts(List.of(a2));
        repo.update(m2);

        assertThat(repo.getSessionArtifacts(sessionId))
                .extracting(MessageArtifact::getFileName).containsExactly("one", "two");
    }

    @Test
    void sessionAttachmentsAreFlattenedAndIncludeEmptyRows() {
        MessageAttachment att = new MessageAttachment();
        att.setId("t1");
        att.setFileName("f.pdf");

        Message m = message(Message.ROLE_USER, "q", "r1");
        m.setAttachments(List.of(att));
        repo.update(m);

        assertThat(repo.getSessionAttachments(sessionId))
                .extracting(MessageAttachment::getFileName).containsExactly("f.pdf");
    }

    @Test
    void updateImagesWritesOnlyThatColumn() {
        Message m = message(Message.ROLE_USER, "q", "r1");
        MessageImage img = new MessageImage();
        img.setUrl("https://img/1");
        img.setCaption("cap");

        repo.updateImages(sessionId, m.getId(), List.of(img));

        Message after = repo.getMessage(sessionId, m.getId());
        assertThat(after.getImages()).hasSize(1);
        assertThat(after.getImages().get(0).getCaption()).isEqualTo("cap");
        // 其他列没被动过
        assertThat(after.getContent()).isEqualTo("q");
    }
}
