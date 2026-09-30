package com.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.TestSchema;
import com.ragagent.memory.domain.MemoryConflictException;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.common.settings.MemoryKinds;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemorySubjectMissingException;
import com.ragagent.memory.mapper.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code SaveItem} 与 {@code ConfirmPendingItem} 的并发语义
 * （对照 Go internal/application/repository/memory_lifecycle.go 全 125 行）。
 *
 * <p>这两个方法是**提议（pending）与已确认事实（active）之间唯一的闸门**，
 * 四条分支都必须照抄，否则会出现"一条提议悄悄让一条已生效的事实退休"
 * 或者"重放把同一条写两遍"。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemoryLifecycleTest {

    private static final long TENANT = 10002L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MemoryRepository repo;

    private MemoryScope scope;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        for (String table : List.of("memory_item_embeddings", "memory_extraction_sessions",
                "memory_items", "memory_tombstones", "memory_topic_stats", "memory_doc_affinity",
                "memory_subjects")) {
            jdbc.execute("DELETE FROM " + table);
        }
        scope = new MemoryScope(TENANT, "web_user:u1");
        repo.ensureSubject(scope);
    }

    /**
     * ⚠️ id 必须由调用方给：{@code SaveItem} **不生成** id（Go 侧
     * {@code tx.Create(item)} 直接插，服务层在调用前就 {@code uuid.New()} 好了）。
     * 不给的话第二次插入会撞主键。
     */
    private MemoryItem item(String content, String key, String status) {
        MemoryItem item = new MemoryItem();
        item.setId(java.util.UUID.randomUUID().toString());
        item.setTenantId(TENANT);
        item.setSubjectId(scope.subjectId());
        item.setKind(MemoryKinds.KIND_FACT);
        item.setContent(content);
        item.setTopic("topic");
        item.setNormalizedKey(key);
        item.setStatus(status);
        item.setValidFrom(OffsetDateTime.now());
        return item;
    }

    private long rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memory_items", Long.class);
    }

    // ── SaveItem ───────────────────────────────────────────────────────────

    /** 全新的一条：插进去，同 key 的活跃条目**不**被 retired（没有 target 而言）。 */
    @Test
    void saveItemInsertsWhenNothingToReplace() {
        MemoryItem fresh = item("生产库是 PostgreSQL", "db", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, fresh, "");

        assertThat(repo.getItem(scope, fresh.getId())).isNotNull();
        assertThat(rowCount()).isEqualTo(1);
        assertThat(repo.getItem(scope, fresh.getId()).getContent()).isEqualTo("生产库是 PostgreSQL");
    }

    /**
     * 提议（pending）**不能**让一条已生效的事实退休——这是本方法存在的核心理由。
     *
     * <p>目标 active 时 {@code item.replaces_id} 指向它，但取代集合里**跳过**它
     * （Go 的 {@code if item.Status == pending && old.Status == active { continue }}）。</p>
     */
    @Test
    void saveItemPendingProposalDoesNotRetireTheActiveFact() {
        MemoryItem active = item("我用 MySQL", "db", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, active, "");

        MemoryItem proposal = item("我迁到 PostgreSQL", "db", MemoryKinds.STATUS_PENDING);
        repo.saveItem(scope, proposal, active.getId());

        assertThat(proposal.getReplacesId()).isEqualTo(active.getId());
        assertThat(repo.getItem(scope, active.getId()).getStatus())
                .as("提议还没确认，事实必须还在")
                .isEqualTo(MemoryKinds.STATUS_ACTIVE);
        assertThat(repo.getItem(scope, proposal.getId()).getStatus())
                .isEqualTo(MemoryKinds.STATUS_PENDING);
    }

    /** 确认过的直接替换（active → active）：同 key 的旧条目被取代。 */
    @Test
    void saveItemActiveReplacementSupersedesTheOldOne() {
        MemoryItem old = item("我用 MySQL", "db", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, old, "");

        MemoryItem next = item("我迁到 PostgreSQL", "db", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, next, "");

        MemoryItem after = repo.getItem(scope, old.getId());
        assertThat(after.getStatus()).isEqualTo(MemoryKinds.STATUS_SUPERSEDED);
        assertThat(after.getSupersededBy()).isEqualTo(next.getId());
        assertThat(after.getInvalidAt()).isNotNull();
        assertThat(repo.getItem(scope, next.getId()).getStatus()).isEqualTo(MemoryKinds.STATUS_ACTIVE);
    }

    /**
     * 内容与状态都已有同款时**复用**那一行，不再插一条
     * （Go 的 {@code if old.Content == item.Content && old.Status == item.Status { *item = *old }}）。
     */
    @Test
    void saveItemReusesAnIdenticalLiveRow() {
        MemoryItem first = item("一样的话", "k", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, first, "");

        MemoryItem duplicate = item("一样的话", "k", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, duplicate, "");

        assertThat(rowCount()).isEqualTo(1);
        assertThat(duplicate.getId()).as("item 被就地改写成已存的那一行").isEqualTo(first.getId());
    }

    /**
     * 重放分支：目标已经被取代，而它的 {@code superseded_by} 指向的那条与本次
     * 要写的 status+content 完全一致 → 说明上次其实已经成功了，只是 checkpoint 失败
     * 后被重放。这时**不能再写一遍**。
     */
    @Test
    void saveItemReplaysTheAlreadyAppliedDecisionInsteadOfWritingAgain() {
        MemoryItem old = item("旧事实", "db", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, old, "");

        MemoryItem applied = item("新事实", "db", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, applied, old.getId());
        long rowsAfterApply = rowCount();

        // 重放一模一样的那次调用
        MemoryItem replay = item("新事实", "db", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, replay, old.getId());

        assertThat(rowCount()).isEqualTo(rowsAfterApply);
        assertThat(replay.getId()).isEqualTo(applied.getId());
    }

    /** 目标不存在 → 冲突（不是"当成新增"）。 */
    @Test
    void saveItemConflictsWhenTheReplacementTargetIsMissing() {
        MemoryItem proposal = item("提议", "db", MemoryKinds.STATUS_PENDING);
        assertThatThrownBy(() -> repo.saveItem(scope, proposal, "no-such-id"))
                .isInstanceOf(MemoryConflictException.class)
                .hasMessage("memory changed; reload before applying this proposal");
    }

    /** 目标已经被取代、且找不到等价的重放结果 → 冲突。 */
    @Test
    void saveItemConflictsWhenTheTargetIsNoLongerReplaceable() {
        MemoryItem old = item("旧事实", "db", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, old, "");
        MemoryItem applied = item("新事实", "db", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, applied, old.getId());

        // 用一个内容不同的提议去替换那个已经被取代的目标
        MemoryItem late = item("另一条", "db", MemoryKinds.STATUS_PENDING);
        assertThatThrownBy(() -> repo.saveItem(scope, late, old.getId()))
                .isInstanceOf(MemoryConflictException.class);
    }

    /** 提议的目标本身就是另一条提议时，{@code replaces_id} 从目标继承（链式提议不叠加目标）。 */
    @Test
    void saveItemPendingOnPendingInheritsTheOriginalTarget() {
        MemoryItem active = item("在用", "db", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, active, "");

        MemoryItem firstProposal = item("提议一", "other-key", MemoryKinds.STATUS_PENDING);
        repo.saveItem(scope, firstProposal, active.getId());
        assertThat(firstProposal.getReplacesId()).isEqualTo(active.getId());

        MemoryItem secondProposal = item("提议二", "third-key", MemoryKinds.STATUS_PENDING);
        repo.saveItem(scope, secondProposal, firstProposal.getId());
        assertThat(secondProposal.getReplacesId()).isEqualTo(active.getId());
    }

    /** 主体行不存在时（未 Ensure）原样上抛，而不是悄悄建行。 */
    @Test
    void saveItemThrowsWhenSubjectRowIsMissing() {
        MemoryItem orphan = item("x", "k", MemoryKinds.STATUS_ACTIVE);
        assertThatThrownBy(() -> repo.saveItem(new MemoryScope(TENANT, "web_user:nobody"),
                orphan, ""))
                .isInstanceOf(MemorySubjectMissingException.class);
    }

    // ── ConfirmPendingItem ─────────────────────────────────────────────────

    @Test
    void confirmPendingItemActivatesAndRetiresTheTarget() {
        MemoryItem active = item("我用 MySQL", "db", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, active, "");
        MemoryItem proposal = item("我迁到 PostgreSQL", "db", MemoryKinds.STATUS_PENDING);
        repo.saveItem(scope, proposal, active.getId());

        repo.confirmPendingItem(scope, proposal.getId());

        assertThat(repo.getItem(scope, proposal.getId()).getStatus())
                .isEqualTo(MemoryKinds.STATUS_ACTIVE);
        MemoryItem retired = repo.getItem(scope, active.getId());
        assertThat(retired.getStatus()).isEqualTo(MemoryKinds.STATUS_SUPERSEDED);
        assertThat(retired.getSupersededBy()).isEqualTo(proposal.getId());
    }

    /** 已经是 active → **幂等成功**（不是冲突）。重复确认是正常重放，不该报错。 */
    @Test
    void confirmPendingItemIsIdempotentForAnAlreadyActiveItem() {
        MemoryItem active = item("已经在用", "db", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, active, "");

        repo.confirmPendingItem(scope, active.getId());

        assertThat(repo.getItem(scope, active.getId()).getStatus())
                .isEqualTo(MemoryKinds.STATUS_ACTIVE);
    }

    @Test
    void confirmPendingItemThrowsWhenTheItemIsMissing() {
        assertThatThrownBy(() -> repo.confirmPendingItem(scope, "no-such-id"))
                .isInstanceOf(MemorySubjectMissingException.class);
    }

    @Test
    void confirmPendingItemConflictsForNonPendingOrExpiredItems() {
        MemoryItem superseded = item("被取代的", "db", MemoryKinds.STATUS_SUPERSEDED);
        repo.createItem(superseded);
        assertThatThrownBy(() -> repo.confirmPendingItem(scope, superseded.getId()))
                .isInstanceOf(MemoryConflictException.class);

        MemoryItem expired = item("过期的提议", "k2", MemoryKinds.STATUS_PENDING);
        expired.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        repo.createItem(expired);
        assertThatThrownBy(() -> repo.confirmPendingItem(scope, expired.getId()))
                .isInstanceOf(MemoryConflictException.class).hasMessage(
                        "memory changed; reload before applying this proposal");
    }

    /** {@code replaces_id} 指向的目标不存在，或已经不是 active → 冲突。 */
    @Test
    void confirmPendingItemConflictsWhenTheTargetIsGoneOrRetired() {
        MemoryItem dangling = item("指向空气的提议", "k1", MemoryKinds.STATUS_PENDING);
        dangling.setReplacesId("no-such-id");
        repo.createItem(dangling);
        assertThatThrownBy(() -> repo.confirmPendingItem(scope, dangling.getId()))
                .isInstanceOf(MemoryConflictException.class);

        MemoryItem retired = item("已经退休的", "k2", MemoryKinds.STATUS_SUPERSEDED);
        repo.createItem(retired);
        MemoryItem proposal = item("指向退休目标的提议", "k3", MemoryKinds.STATUS_PENDING);
        proposal.setReplacesId(retired.getId());
        repo.createItem(proposal);
        assertThatThrownBy(() -> repo.confirmPendingItem(scope, proposal.getId()))
                .isInstanceOf(MemoryConflictException.class);
    }

    /**
     * 确认时会顺带清掉同 key / 同 {@code replaces_id} 的其它 pending，
     * 否则确认之后会留下重复。
     *
     * <p>注意 {@code replaces_id <> ''} 那个守卫：没有它，一条 {@code replaces_id}
     * 为空的提议会匹配上所有 {@code replaces_id = ''} 的行。</p>
     */
    @Test
    void confirmPendingItemSupersedesSiblingProposals() {
        MemoryItem active = item("在用", "db", MemoryKinds.STATUS_ACTIVE);
        repo.saveItem(scope, active, "");

        MemoryItem chosen = item("被选中的提议", "db", MemoryKinds.STATUS_PENDING);
        repo.saveItem(scope, chosen, active.getId());

        MemoryItem sibling = item("同 key 的另一个提议", "db", MemoryKinds.STATUS_PENDING);
        sibling.setStatus(MemoryKinds.STATUS_PENDING);
        repo.createItem(sibling);

        // 一条完全无关的 pending（replaces_id 为空），不能被误伤
        MemoryItem unrelated = item("无关的提议", "unrelated-key", MemoryKinds.STATUS_PENDING);
        repo.createItem(unrelated);

        repo.confirmPendingItem(scope, chosen.getId());

        assertThat(repo.getItem(scope, sibling.getId()).getStatus())
                .isEqualTo(MemoryKinds.STATUS_SUPERSEDED);
        assertThat(repo.getItem(scope, unrelated.getId()).getStatus())
                .as("replaces_id 为空的无关行不能被守卫漏掉")
                .isEqualTo(MemoryKinds.STATUS_PENDING);
    }
}
