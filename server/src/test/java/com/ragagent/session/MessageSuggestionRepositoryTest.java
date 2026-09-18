package com.ragagent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.TestSchema;
import com.ragagent.session.domain.MessageSuggestionEvent;
import com.ragagent.session.domain.MessageSuggestionSet;
import com.ragagent.session.domain.SuggestionItem;
import com.ragagent.session.mapper.MessageSuggestionRepository;
import com.ragagent.session.mapper.MessageSuggestionRepository.SuggestionSetNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 追问建议仓储语义（H2）——对照 Go
 * internal/application/repository/message_suggestion.go。
 *
 * <p>重点是 {@code AcquireGeneration} 的四条分支：唯一键抢占、复用 ready/suppressed 结果、
 * 别人租约未过期时让位、租约过期后抢过来重生成。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageSuggestionRepositoryTest {

    private static final long TENANT = 10002L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MessageSuggestionRepository repo;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    private MessageSuggestionSet candidate(String messageId) {
        MessageSuggestionSet s = new MessageSuggestionSet();
        s.setTenantId(TENANT);
        s.setSessionId("sess-1");
        s.setAssistantMessageId(messageId);
        s.setAgentId("agent-1");
        s.setPlacement(MessageSuggestionSet.PLACEMENT_AFTER_ANSWER);
        s.setConfigHash("hash-1");
        s.setLocale("zh-CN");
        return s;
    }

    // ── AcquireGeneration ───────────────────────────────────────────────────

    @Test
    void firstAcquireWinsAndPersistsQuestionsAsEmptyArray() {
        var result = repo.acquireGeneration(candidate("m1"), false);

        assertThat(result.acquired()).isTrue();
        assertThat(result.set().getId()).isNotBlank();
        assertThat(result.set().getStatus()).isEqualTo(MessageSuggestionSet.STATUS_GENERATING);
        // 空列表写成 []（不是 NULL）——Go 的 SuggestionItems.Value() 对 nil 就是这么做的
        assertThat(jdbc.queryForObject(
                "SELECT questions FROM message_suggestion_sets WHERE id = ?",
                String.class, result.set().getId())).isEqualTo("[]");
    }

    @Test
    void reusedWhenAnUnexpiredGenerationLeaseIsHeldBySomeoneElse() {
        repo.acquireGeneration(candidate("m1"), false);

        var second = repo.acquireGeneration(candidate("m1"), false);
        assertThat(second.acquired()).isFalse();
        assertThat(second.set().getStatus()).isEqualTo(MessageSuggestionSet.STATUS_GENERATING);
    }

    @Test
    void readyResultIsReusedUnlessRegenerateIsRequested() {
        var first = repo.acquireGeneration(candidate("m1"), false);
        MessageSuggestionSet ready = first.set();
        ready.setStatus(MessageSuggestionSet.STATUS_READY);
        ready.setLeaseUntil(null);
        ready.setQuestions(List.of(new SuggestionItem()));
        repo.save(ready);

        var reuse = repo.acquireGeneration(candidate("m1"), false);
        assertThat(reuse.acquired()).isFalse();
        assertThat(reuse.set().getStatus()).isEqualTo(MessageSuggestionSet.STATUS_READY);

        var again = repo.acquireGeneration(candidate("m1"), true);
        assertThat(again.acquired()).isTrue();
        assertThat(again.set().getStatus()).isEqualTo(MessageSuggestionSet.STATUS_GENERATING);
    }

    @Test
    void suppressedResultIsReusedUnlessRegenerateIsRequested() {
        var first = repo.acquireGeneration(candidate("m1"), false);
        MessageSuggestionSet suppressed = first.set();
        suppressed.setStatus(MessageSuggestionSet.STATUS_SUPPRESSED);
        suppressed.setLeaseUntil(null);
        repo.save(suppressed);

        assertThat(repo.acquireGeneration(candidate("m1"), false).acquired()).isFalse();
        assertThat(repo.acquireGeneration(candidate("m1"), false).set().getStatus())
                .isEqualTo(MessageSuggestionSet.STATUS_SUPPRESSED);
    }

    @Test
    void expiredLeaseCanBeTakenOver() {
        var first = repo.acquireGeneration(candidate("m1"), false);
        // 直接把租约改成过去，模拟上一轮生成卡死
        jdbc.update("UPDATE message_suggestion_sets SET lease_until = ? WHERE id = ?",
                OffsetDateTime.now().minusMinutes(1), first.set().getId());

        var second = repo.acquireGeneration(candidate("m1"), false);
        assertThat(second.acquired()).isTrue();
    }

    // ── 读 / 写 / 删 ────────────────────────────────────────────────────────

    @Test
    void getByCacheKeyThrowsWhenMissing() {
        assertThatThrownBy(() -> repo.getByCacheKey(TENANT, "nope", "after_answer", "h", "zh-CN"))
                .isInstanceOf(SuggestionSetNotFoundException.class);
    }

    @Test
    void getByIdIsScopedToTenantAndSession() {
        var created = repo.acquireGeneration(candidate("m1"), false).set();
        assertThat(repo.getById(TENANT, "sess-1", created.getId()).getId()).isEqualTo(created.getId());

        assertThatThrownBy(() -> repo.getById(TENANT, "other-session", created.getId()))
                .isInstanceOf(SuggestionSetNotFoundException.class);
        assertThatThrownBy(() -> repo.getById(99999L, "sess-1", created.getId()))
                .isInstanceOf(SuggestionSetNotFoundException.class);
    }

    @Test
    void saveClearsLeaseAndStoresGeneratedQuestions() {
        var created = repo.acquireGeneration(candidate("m1"), false).set();
        created.setStatus(MessageSuggestionSet.STATUS_READY);
        created.setLeaseUntil(null);
        created.setGeneratedAt(OffsetDateTime.now());
        SuggestionItem item = new SuggestionItem();
        item.setId("q1");
        item.setText("追问一");
        created.setQuestions(List.of(item));
        repo.save(created);

        MessageSuggestionSet stored = repo.getById(TENANT, "sess-1", created.getId());
        assertThat(stored.getStatus()).isEqualTo(MessageSuggestionSet.STATUS_READY);
        assertThat(stored.getLeaseUntil()).isNull();
        assertThat(stored.getQuestions()).hasSize(1);
        assertThat(stored.getQuestions().get(0).getText()).isEqualTo("追问一");
    }

    @Test
    void eventsAreAppended() {
        var created = repo.acquireGeneration(candidate("m1"), false).set();
        MessageSuggestionEvent e = new MessageSuggestionEvent();
        e.setTenantId(TENANT);
        e.setSessionId("sess-1");
        e.setSuggestionSetId(created.getId());
        e.setQuestionId("q1");
        e.setEventType(MessageSuggestionEvent.EVENT_CLICK);
        e.setActorId("u1");
        repo.createEvent(e);

        assertThat(jdbc.queryForObject(
                "SELECT event_type FROM message_suggestion_events WHERE suggestion_set_id = ?",
                String.class, created.getId())).isEqualTo("click");
    }

    @Test
    void deletesAreHardDeletes() {
        // 本表没有 deleted_at 列——与 sessions/messages 的软删不同
        var created = repo.acquireGeneration(candidate("m1"), false).set();
        repo.deleteByMessageId(TENANT, "sess-1", "m1");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_suggestion_sets WHERE id = ?",
                Integer.class, created.getId())).isZero();
    }

    @Test
    void deleteBySessionIdRemovesEverySetOfThatSession() {
        repo.acquireGeneration(candidate("m1"), false);
        repo.acquireGeneration(candidate("m2"), false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_suggestion_sets",
                Integer.class)).isEqualTo(2);

        repo.deleteBySessionId(TENANT, "sess-1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_suggestion_sets",
                Integer.class)).isZero();
    }
}
