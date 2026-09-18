package com.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.TestSchema;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.memory.domain.MemoryDocAffinity;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryKinds;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemorySubject;
import com.ragagent.memory.domain.MemorySubjectMissingException;
import com.ragagent.memory.domain.MemoryText;
import com.ragagent.memory.domain.MemoryTopicStat;
import com.ragagent.memory.mapper.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 记忆仓储在 H2 上的语义（对照 Go internal/application/repository/memory.go）。
 *
 * <p>重点是那些**只在真 SQL 上才暴露**的行为：</p>
 * <ul>
 *   <li>jsonb 列（{@code pending_sessions} / {@code aliases} / {@code extraction_state}）
 *       在 nil 时写的是 {@code []} / {@code {...}} 而**不是** SQL NULL；</li>
 *   <li>GORM 在 CREATE 时对带字面量 default tag 的零值字段做的替换
 *       （{@code importance 0→3}、{@code origin ""→extracted}、{@code status ""→active}）；</li>
 *   <li>{@code created_at} 零值才补 / {@code updated_at} 恒被覆盖；</li>
 *   <li>{@code withSubject} 的 {@code SELECT … FOR UPDATE} 在 H2 上真的能跑；</li>
 *   <li>所有删除都带 scope——传别人的 id 必须是**空操作**而不是删掉别人的行。</li>
 * </ul>
 *
 * <p>⚠️ {@code @AutoConfigureMockMvc} 是为了与其余契约测试共用同一个 Spring 上下文缓存键。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemoryRepositoryTest {

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
        // memory 的 7 张表不在 TestSchema.resetData 的清理列表里（那文件不归本模块改），
        // 所以自己清。顺序无所谓：这七张表之间**没有任何外键**。
        for (String table : List.of("memory_item_embeddings", "memory_extraction_sessions",
                "memory_items", "memory_tombstones", "memory_topic_stats", "memory_doc_affinity",
                "memory_subjects")) {
            jdbc.execute("DELETE FROM " + table);
        }
        scope = new MemoryScope(TENANT, "web_user:u1");
    }

    // ── 主体 ───────────────────────────────────────────────────────────────

    @Test
    void getSubjectReturnsNullWhenAbsent() {
        assertThat(repo.getSubject(scope)).isNull();
    }

    @Test
    void ensureSubjectCreatesThenReuses() {
        MemorySubject created = repo.ensureSubject(scope);

        assertThat(created.getId()).hasSize(36);
        assertThat(created.getTenantId()).isEqualTo(TENANT);
        assertThat(created.getSubjectId()).isEqualTo("web_user:u1");
        assertThat(created.isEnabled()).isTrue();
        // GORM 的自动时间戳：created_at 与 updated_at 都被显式写入
        assertThat(created.getCreatedAt()).isNotEqualTo(GoTimeSerializer.GO_ZERO_DATE_TIME);
        assertThat(created.getUpdatedAt()).isNotEqualTo(GoTimeSerializer.GO_ZERO_DATE_TIME);

        MemorySubject again = repo.ensureSubject(scope);
        assertThat(again.getId()).as("第二次必须是同一行").isEqualTo(created.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_subjects", Integer.class))
                .isEqualTo(1);
    }

    /**
     * 两个 jsonb 列在 Go 里**从不**为 NULL：{@code pending_sessions} 写 {@code []}、
     * {@code extraction_state} 写一段对象。落到 SQL NULL 会让读路径与 Go 分叉。
     */
    @Test
    void ensureSubjectWritesEmptyJsonbNotNull() {
        repo.ensureSubject(scope);

        assertThat(jdbc.queryForObject("SELECT pending_sessions FROM memory_subjects", String.class))
                .isEqualTo("[]");
        assertThat(jdbc.queryForObject("SELECT extraction_state FROM memory_subjects", String.class))
                .isEqualTo("{\"lease_until\":\"0001-01-01T00:00:00Z\"}");
    }

    @Test
    void ensureSubjectIsIdempotentUnderRepeatedCalls() {
        for (int i = 0; i < 3; i++) {
            repo.ensureSubject(scope);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_subjects", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void updateSubjectEnabledAndBlock() {
        repo.ensureSubject(scope);

        repo.updateSubjectEnabled(scope, false);
        assertThat(repo.getSubject(scope).isEnabled()).isFalse();

        repo.updateSubjectBlock(scope, "About the user:\n- 写 Go", 7);
        MemorySubject after = repo.getSubject(scope);
        assertThat(after.getBlockText()).isEqualTo("About the user:\n- 写 Go");
        assertThat(after.getItemCount()).isEqualTo(7);
        assertThat(after.getBlockUpdatedAt()).isNotNull();
    }

    /** {@code updateSubjectEnabled} 对还没有主体的 scope 会先建行（Go 的 {@code EnsureSubject} 前置调用）。 */
    @Test
    void updateSubjectEnabledEnsuresSubjectFirst() {
        repo.updateSubjectEnabled(scope, false);
        assertThat(repo.getSubject(scope)).isNotNull();
    }

    @Test
    void markConsolidatedUsesTwoSeparateClocks() {
        repo.ensureSubject(scope);

        repo.markConsolidated(scope);
        assertThat(repo.getSubject(scope).getConsolidatedAt()).isNotNull();
        assertThat(repo.getSubject(scope).getForcedConsolidatedAt()).isNull();

        repo.markForcedConsolidated(scope);
        assertThat(repo.getSubject(scope).getForcedConsolidatedAt()).isNotNull();
    }

    // ── 条目：创建与 GORM 默认值 ───────────────────────────────────────────

    private MemoryItem newItem(String content, String topic) {
        MemoryItem item = new MemoryItem();
        item.setTenantId(TENANT);
        item.setSubjectId(scope.subjectId());
        item.setKind(MemoryKinds.KIND_FACT);
        item.setContent(content);
        item.setTopic(topic);
        item.setNormalizedKey(MemoryText.fingerprint(content));
        return item;
    }

    @Test
    void createItemFillsIdValidFromAndStatus() {
        MemoryItem item = newItem("生产库是 PostgreSQL", "在用的数据库");
        repo.createItem(item);

        assertThat(item.getId()).hasSize(36);
        assertThat(item.getValidFrom()).isNotEqualTo(GoTimeSerializer.GO_ZERO_DATE_TIME);
        assertThat(item.getStatus()).isEqualTo(MemoryKinds.STATUS_ACTIVE);
        assertThat(repo.getItem(scope, item.getId()).getContent()).isEqualTo("生产库是 PostgreSQL");
    }

    /** {@code valid_from} 若调用方已经给了就不动（Go 的 {@code if item.ValidFrom.IsZero()}）。 */
    @Test
    void createItemKeepsCallerSuppliedValidFrom() {
        OffsetDateTime when = OffsetDateTime.now().minusDays(3);
        MemoryItem item = newItem("x", "t");
        item.setValidFrom(when);
        repo.createItem(item);

        assertThat(repo.getItem(scope, item.getId()).getValidFrom().toInstant())
                .isEqualTo(when.toInstant());
    }

    /**
     * GORM 的"零值 → 默认值"替换（callbacks/create.go L336-341）：三个带字面量
     * default tag 的字段即使是零值也会被**显式写入默认值**，而不是落 DDL 默认。
     *
     * <p>{@code importance=0}、{@code origin=""} 在 {@code CreateItem} 这条路径上
     * 都没有被上层补齐，全靠这一条兜住。</p>
     */
    @Test
    void createItemAppliesGormInsertDefaults() {
        MemoryItem item = newItem("x", "t");
        item.setImportance(0);
        item.setOrigin("");
        // Status 由 CreateItem 显式补成 active
        repo.createItem(item);

        MemoryItem stored = repo.getItem(scope, item.getId());
        assertThat(stored.getImportance()).as("importance 0 → 3").isEqualTo(3);
        assertThat(stored.getOrigin()).as("origin \"\" → extracted").isEqualTo("extracted");
        assertThat(stored.getStatus()).isEqualTo("active");
        // 字段被回写进内存对象（Go 的 field.Set）
        assertThat(item.getImportance()).isEqualTo(3);
        assertThat(item.getOrigin()).isEqualTo("extracted");
    }

    /** {@code replaces_id} 是 NOT NULL DEFAULT ''：落库必须写空串，哪怕响应会省略这个键。 */
    @Test
    void createItemWritesEmptyReplacesIdNotNull() {
        MemoryItem item = newItem("x", "t");
        repo.createItem(item);

        assertThat(jdbc.queryForObject(
                "SELECT replaces_id FROM memory_items WHERE id = ?", String.class, item.getId()))
                .isEmpty();
    }

    // ── 条目：读 ───────────────────────────────────────────────────────────

    @Test
    void listActiveByKindsReturnsNullForEmptyKinds() {
        assertThat(repo.listActiveByKinds(scope, List.of(), 10)).isNull();
        assertThat(repo.listActiveByKinds(scope, null, 10)).isNull();
    }

    @Test
    void listActiveByKindsOrdersByImportanceThenRecency() {
        MemoryItem low = newItem("低", "a");
        low.setImportance(1);
        repo.createItem(low);
        MemoryItem high = newItem("高", "b");
        high.setImportance(5);
        repo.createItem(high);

        List<MemoryItem> items = repo.listActiveByKinds(scope, List.of(MemoryKinds.KIND_FACT), 10);
        assertThat(items).extracting(MemoryItem::getContent).containsExactly("高", "低");
    }

    /** {@code notExpired} 的括号不能少：{@code expires_at IS NULL OR expires_at > now} 必须整体 AND 上去。 */
    @Test
    void listActiveByKindsExcludesExpiredAndOtherKinds() {
        MemoryItem expired = newItem("过期的", "a");
        expired.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        repo.createItem(expired);

        MemoryItem otherKind = newItem("别的 kind", "b");
        otherKind.setKind(MemoryKinds.KIND_PROFILE);
        repo.createItem(otherKind);

        MemoryItem live = newItem("活着的", "c");
        repo.createItem(live);

        assertThat(repo.listActiveByKinds(scope, List.of(MemoryKinds.KIND_FACT), 10))
                .extracting(MemoryItem::getContent)
                .containsExactly("活着的");
    }

    /**
     * 常驻块 = 稳定特质 ∪ 明确要求记住的。
     *
     * <p>后者**不问 kind**：用户说了"记住这个"，让这件事取决于他之后的问题
     * 恰好与它共享词汇，是让用户失去信任最快的方式。</p>
     */
    @Test
    void listActiveResidentIncludesExplicitOriginRegardlessOfKind() {
        MemoryItem profile = newItem("关于用户", "a");
        profile.setKind(MemoryKinds.KIND_PROFILE);
        repo.createItem(profile);

        MemoryItem explicitFact = newItem("用户要求记住的事实", "b");
        explicitFact.setKind(MemoryKinds.KIND_FACT);
        explicitFact.setOrigin(MemoryKinds.ORIGIN_EXPLICIT);
        repo.createItem(explicitFact);

        MemoryItem plainFact = newItem("普通事实", "c");
        repo.createItem(plainFact);

        assertThat(repo.listActiveResident(scope, 0))
                .extracting(MemoryItem::getContent)
                .containsExactlyInAnyOrder("关于用户", "用户要求记住的事实");
    }

    @Test
    void listItemsAppliesStatusFilterAndReturnsTotal() {
        repo.createItem(newItem("a", "a"));
        MemoryItem archived = newItem("b", "b");
        archived.setStatus(MemoryKinds.STATUS_ARCHIVED);
        repo.createItem(archived);

        MemoryRepository.Page<MemoryItem> all = repo.listItems(scope, "", 0, 0);
        assertThat(all.total()).isEqualTo(2);
        assertThat(all.items()).hasSize(2);

        MemoryRepository.Page<MemoryItem> active = repo.listItems(scope, MemoryKinds.STATUS_ACTIVE, 0, 0);
        assertThat(active.total()).isEqualTo(1);
        assertThat(active.items()).extracting(MemoryItem::getContent).containsExactly("a");
    }

    @Test
    void listLiveIncludesPendingButNotSuperseded() {
        MemoryItem active = newItem("在用", "a");
        repo.createItem(active);
        MemoryItem pending = newItem("待确认", "b");
        pending.setStatus(MemoryKinds.STATUS_PENDING);
        repo.createItem(pending);
        MemoryItem superseded = newItem("被取代", "c");
        superseded.setStatus(MemoryKinds.STATUS_SUPERSEDED);
        repo.createItem(superseded);

        assertThat(repo.listLive(scope, MemoryKinds.KIND_FACT, 0))
                .extracting(MemoryItem::getContent)
                .containsExactlyInAnyOrder("在用", "待确认");
    }

    @Test
    void findActiveByKeySkipsEmptyKeyAndTreatsPendingAsLive() {
        assertThat(repo.findActiveByKey(scope, "")).isNull();

        MemoryItem pending = newItem("待确认", "same-key");
        pending.setNormalizedKey("same-key");
        pending.setStatus(MemoryKinds.STATUS_PENDING);
        repo.createItem(pending);

        assertThat(repo.findActiveByKey(scope, "same-key").getContent()).isEqualTo("待确认");
        assertThat(repo.findActiveByKey(scope, "no-such-key")).isNull();
    }

    @Test
    void getItemIsScoped() {
        MemoryItem item = newItem("x", "t");
        repo.createItem(item);

        assertThat(repo.getItem(scope, item.getId())).isNotNull();
        assertThat(repo.getItem(scope, "no-such-id")).isNull();
        // 别的 subject 看不见
        assertThat(repo.getItem(new MemoryScope(TENANT, "web_user:other"), item.getId())).isNull();
        // 别的租户也看不见
        assertThat(repo.getItem(new MemoryScope(99999L, scope.subjectId()), item.getId())).isNull();
    }

    @Test
    void countActiveCountsOnlyActive() {
        repo.createItem(newItem("a", "a"));
        MemoryItem archived = newItem("b", "b");
        archived.setStatus(MemoryKinds.STATUS_ARCHIVED);
        repo.createItem(archived);

        assertThat(repo.countActive(scope)).isEqualTo(1);
    }

    // ── 条目：更新与删除 ───────────────────────────────────────────────────

    @Test
    void updateItemContentOverwritesFiveColumnsAndMarksManual() {
        // withSubject 要锁主体行（Go 的 First 未命中即报错），所以先建
        repo.ensureSubject(scope);
        MemoryItem item = newItem("旧内容", "t");
        repo.createItem(item);

        repo.updateItemContent(scope, item.getId(), "新内容", "new-key", 5);

        MemoryItem after = repo.getItem(scope, item.getId());
        assertThat(after.getContent()).isEqualTo("新内容");
        assertThat(after.getNormalizedKey()).isEqualTo("new-key");
        assertThat(after.getImportance()).isEqualTo(5);
        assertThat(after.getOrigin()).isEqualTo(MemoryKinds.ORIGIN_MANUAL);
    }

    /** 内容**没变**时不动向量、也不作废提议（Go 的 {@code if current.Content != content}）。 */
    @Test
    void updateItemContentSkipsSideEffectsWhenContentUnchanged() {
        // withSubject 要锁主体行（Go 的 First 未命中即报错），所以先建
        repo.ensureSubject(scope);
        MemoryItem item = newItem("同样的内容", "t");
        repo.createItem(item);
        MemoryItem proposal = newItem("提议", "p");
        proposal.setStatus(MemoryKinds.STATUS_PENDING);
        proposal.setReplacesId(item.getId());
        repo.createItem(proposal);

        repo.updateItemContent(scope, item.getId(), "同样的内容", item.getNormalizedKey(), 4);

        assertThat(repo.getItem(scope, proposal.getId()).getStatus())
                .isEqualTo(MemoryKinds.STATUS_PENDING);
    }

    /** 改了内容 → 基于旧措辞的提议作废。 */
    @Test
    void updateItemContentSupersedesProposalsBasedOnOldWording() {
        // withSubject 要锁主体行（Go 的 First 未命中即报错），所以先建
        repo.ensureSubject(scope);
        MemoryItem item = newItem("旧内容", "t");
        repo.createItem(item);
        MemoryItem proposal = newItem("提议", "p");
        proposal.setStatus(MemoryKinds.STATUS_PENDING);
        proposal.setReplacesId(item.getId());
        repo.createItem(proposal);

        repo.updateItemContent(scope, item.getId(), "改过的内容", "k", 4);

        MemoryItem after = repo.getItem(scope, proposal.getId());
        assertThat(after.getStatus()).isEqualTo(MemoryKinds.STATUS_SUPERSEDED);
        assertThat(after.getSupersededBy()).isEqualTo(item.getId());
    }

    /** 目标行不存在时原样上抛（对照 Go 的 {@code gorm.ErrRecordNotFound}）。 */
    @Test
    void updateItemContentThrowsWhenItemMissing() {
        repo.ensureSubject(scope);
        assertThatThrownBy(() -> repo.updateItemContent(scope, "missing", "x", "k", 3))
                .isInstanceOf(MemorySubjectMissingException.class)
                .hasMessage("record not found");
    }

    @Test
    void supersedeItemOnlyTouchesLiveRows() {
        // withSubject 要锁主体行（Go 的 First 未命中即报错），所以先建
        repo.ensureSubject(scope);
        MemoryItem active = newItem("在用", "a");
        repo.createItem(active);
        MemoryItem pendingReplacement = newItem("替换者", "a");
        pendingReplacement.setStatus(MemoryKinds.STATUS_PENDING);
        pendingReplacement.setReplacesId(active.getId());
        repo.createItem(pendingReplacement);

        repo.supersedeItem(scope, active.getId(), pendingReplacement.getId());

        MemoryItem after = repo.getItem(scope, active.getId());
        assertThat(after.getStatus()).isEqualTo(MemoryKinds.STATUS_SUPERSEDED);
        assertThat(after.getSupersededBy()).isEqualTo(pendingReplacement.getId());
        assertThat(after.getInvalidAt()).isNotNull();
    }

    /** {@code DeleteItem} 是**物理删**：忘记就是忘记，没有软删也没有墓碑。 */
    @Test
    void deleteItemPhysicallyRemovesAndSupersedesPendingReplacements() {
        // withSubject 要锁主体行（Go 的 First 未命中即报错），所以先建
        repo.ensureSubject(scope);
        MemoryItem item = newItem("要被忘掉的", "a");
        repo.createItem(item);
        MemoryItem replacement = newItem("指向它的待确认项", "a");
        replacement.setStatus(MemoryKinds.STATUS_PENDING);
        replacement.setReplacesId(item.getId());
        repo.createItem(replacement);

        repo.deleteItem(scope, item.getId());

        assertThat(repo.getItem(scope, item.getId())).isNull();
        assertThat(repo.getItem(scope, replacement.getId()).getStatus())
                .isEqualTo(MemoryKinds.STATUS_SUPERSEDED);
    }

    /**
     * 删除一律带 scope——而且这条路走的是 {@code withSubject}，
     * 所以**跨主体/跨租户的删除会在锁主体那一步就失败**（Go 的 {@code First} 未命中）。
     * 这比"静默不删"更强：调用方不会以为删成功了。
     */
    @Test
    void deleteItemCannotCrossSubjectOrTenantBoundary() {
        repo.ensureSubject(scope);
        MemoryItem item = newItem("我的", "a");
        repo.createItem(item);

        assertThatThrownBy(() -> repo.deleteItem(new MemoryScope(TENANT, "web_user:other"), item.getId()))
                .isInstanceOf(MemorySubjectMissingException.class);
        assertThatThrownBy(() -> repo.deleteItem(new MemoryScope(99999L, scope.subjectId()), item.getId()))
                .isInstanceOf(MemorySubjectMissingException.class);

        assertThat(repo.getItem(scope, item.getId())).as("别人的删除请求没动到这一行").isNotNull();
    }

    @Test
    void deleteAllRemovesEveryItemInScope() {
        repo.createItem(newItem("a", "a"));
        repo.createItem(newItem("b", "b"));

        assertThat(repo.deleteAll(scope)).isEqualTo(2);
        assertThat(repo.countActive(scope)).isZero();
    }

    @Test
    void touchUsedIncrementsUseCountInSql() {
        MemoryItem item = newItem("a", "a");
        repo.createItem(item);

        repo.touchUsed(scope, List.of(item.getId()));
        repo.touchUsed(scope, List.of(item.getId()));

        MemoryItem after = repo.getItem(scope, item.getId());
        assertThat(after.getUseCount()).isEqualTo(2);
        assertThat(after.getLastUsedAt()).isNotNull();

        // 空列表是空操作
        repo.touchUsed(scope, List.of());
        assertThat(repo.getItem(scope, item.getId()).getUseCount()).isEqualTo(2);
    }

    // ── 容量 / 过期 ────────────────────────────────────────────────────────

    @Test
    void archiveLowestRankedKeepsTheBestOnes() {
        for (int i = 1; i <= 4; i++) {
            MemoryItem item = newItem("条目" + i, "k" + i);
            item.setImportance(i <= 2 ? 5 : 1);
            repo.createItem(item);
        }

        assertThat(repo.archiveLowestRanked(scope, 2)).isEqualTo(2);
        assertThat(repo.countActive(scope)).isEqualTo(2);
    }

    /** {@code keep <= 0} 直接返回 0（Go 的短路），不能变成"全部归档"。 */
    @Test
    void archiveLowestRankedDoesNothingForNonPositiveKeep() {
        repo.createItem(newItem("a", "a"));
        assertThat(repo.archiveLowestRanked(scope, 0)).isZero();
        assertThat(repo.countActive(scope)).isEqualTo(1);
    }

    @Test
    void expireOverdueArchivesOnlyPassedExpiries() {
        MemoryItem overdue = newItem("过期", "a");
        overdue.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        repo.createItem(overdue);
        MemoryItem future = newItem("未过期", "b");
        future.setExpiresAt(OffsetDateTime.now().plusDays(1));
        repo.createItem(future);
        MemoryItem noExpiry = newItem("没有到期时间", "c");
        repo.createItem(noExpiry);

        assertThat(repo.expireOverdue(scope)).isEqualTo(1);
        assertThat(repo.getItem(scope, overdue.getId()).getStatus())
                .isEqualTo(MemoryKinds.STATUS_ARCHIVED);
        assertThat(repo.getItem(scope, future.getId()).getStatus())
                .isEqualTo(MemoryKinds.STATUS_ACTIVE);
        assertThat(repo.getItem(scope, noExpiry.getId()).getStatus())
                .isEqualTo(MemoryKinds.STATUS_ACTIVE);
    }

    // ── 墓碑 ───────────────────────────────────────────────────────────────

    @Test
    void addTombstoneIsIdempotentOnTheSameFingerprint() {
        repo.ensureSubject(scope);
        repo.addTombstone(scope, "在用的数据库", "fp-1", "msg-1");
        repo.addTombstone(scope, "在用的数据库", "fp-1", "msg-1");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_tombstones", Integer.class))
                .isEqualTo(1);
        assertThat(repo.hasTombstone(scope, "fp-1")).isTrue();
        assertThat(repo.hasTombstone(scope, "fp-2")).isFalse();
    }

    @Test
    void addTombstoneSkipsEmptyFingerprintAndScopesLookups() {
        repo.ensureSubject(scope);
        repo.addTombstone(scope, "t", "", "msg-1");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_tombstones", Integer.class))
                .isZero();
        // 空指纹的查询也短路
        assertThat(repo.hasTombstone(scope, "")).isFalse();
    }

    @Test
    void listTombstonesReturnsNewestFirst() {
        repo.ensureSubject(scope);
        repo.addTombstone(scope, "旧", "fp-old", "");
        repo.addTombstone(scope, "新", "fp-new", "");

        assertThat(repo.listTombstones(scope, 0))
                .extracting(com.ragagent.memory.domain.MemoryTombstone::getTopic)
                .containsExactly("新", "旧");
    }

    /** 时间窗只过滤更早的记录；{@code within <= 0} 时不加窗（Go 的 {@code if within > 0}）。 */
    @Test
    void hasTombstoneForMessageHonoursTheWindow() {
        repo.ensureSubject(scope);
        repo.addTombstone(scope, "t", "fp", "msg-1");

        assertThat(repo.hasTombstoneForMessage(scope, "msg-1", Duration.ZERO)).isTrue();
        assertThat(repo.hasTombstoneForMessage(scope, "msg-1", Duration.ofHours(1))).isTrue();
        assertThat(repo.hasTombstoneForMessage(scope, "msg-2", Duration.ofHours(1))).isFalse();
        assertThat(repo.hasTombstoneForMessage(scope, "", Duration.ofHours(1))).isFalse();
    }

    // ── 话题统计 ───────────────────────────────────────────────────────────

    /** {@code aliases} 是 {@code NOT NULL DEFAULT '[]'}：插入时必须写 {@code []} 而不是 NULL。 */
    @Test
    void bumpTopicCreatesThenIncrementsAndWritesEmptyAliasesNotNull() {
        MemoryTopicStat first = repo.bumpTopic(scope, "在用的数据库", "在用的数据库", "生产库");

        assertThat(first.getHits()).isEqualTo(1);
        assertThat(first.getAliases()).containsExactly("生产库");
        assertThat(jdbc.queryForObject("SELECT aliases FROM memory_topic_stats", String.class))
                .isEqualTo("[\"生产库\"]");

        MemoryTopicStat second = repo.bumpTopic(scope, "在用的数据库", "在用的数据库", "生产库");
        assertThat(second.getHits()).isEqualTo(2);
        // 同一个说法不重复记
        assertThat(second.getAliases()).containsExactly("生产库");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_topic_stats", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void bumpTopicSkipsEmptyKey() {
        assertThat(repo.bumpTopic(scope, "topic", "", "alias")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_topic_stats", Integer.class))
                .isZero();
    }

    /** 别名归一化后等于 key 本身时不记（否则规范标签会被列成它自己的别名）。 */
    @Test
    void bumpTopicDoesNotRecordTheKeyItselfAsAlias() {
        MemoryTopicStat stat = repo.bumpTopic(scope, "数据库", "数据库", "  数据库  ");

        // 库里那一列写的是 []（不是 NULL），所以读回来是**非 null 的空列表**
        assertThat(jdbc.queryForObject("SELECT aliases FROM memory_topic_stats", String.class))
                .isEqualTo("[]");
        assertThat(stat.getAliases()).isEmpty();
    }

    @Test
    void bumpTopicKeepsAtMostTwelveAliases() {
        for (int i = 0; i < 15; i++) {
            repo.bumpTopic(scope, "topic", "topic", "alias-" + i);
        }
        List<String> aliases = repo.topicByKey(scope, "topic").getAliases();
        assertThat(aliases).hasSize(12);
        assertThat(aliases).containsExactly("alias-3", "alias-4", "alias-5", "alias-6", "alias-7",
                "alias-8", "alias-9", "alias-10", "alias-11", "alias-12", "alias-13", "alias-14");
    }

    @Test
    void renameTopicMovesLabelToAliasesAndRejectsClashes() {
        repo.bumpTopic(scope, "旧标签", "old", null);
        repo.bumpTopic(scope, "别的", "other", null);

        assertThat(repo.renameTopic(scope, "old", "other", "撞了")).as("新 key 已被占用").isFalse();
        assertThat(repo.renameTopic(scope, "old", "new", "新标签")).isTrue();

        MemoryTopicStat renamed = repo.topicByKey(scope, "new");
        assertThat(renamed.getTopic()).isEqualTo("新标签");
        // 旧标签变成别名，不是被丢掉
        assertThat(renamed.getAliases()).contains("旧标签");
        assertThat(repo.topicByKey(scope, "old")).isNull();
    }

    @Test
    void renameTopicShortCircuitsOnBadInput() {
        assertThat(repo.renameTopic(scope, "", "new", "x")).isFalse();
        assertThat(repo.renameTopic(scope, "old", "", "x")).isFalse();
        assertThat(repo.renameTopic(scope, "same", "same", "x")).isFalse();
        assertThat(repo.renameTopic(scope, "missing", "new", "x")).isFalse();
    }

    @Test
    void markTopicPromotedStopsItFromBeingListedAsUnpromoted() {
        repo.bumpTopic(scope, "a", "a", null);
        repo.bumpTopic(scope, "b", "b", null);

        repo.markTopicPromoted(scope, "a");

        MemoryRepository.Page<MemoryTopicStat> page = repo.listUnpromotedTopics(scope, 0, 0);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(MemoryTopicStat::getNormalizedKey).containsExactly("b");
        assertThat(repo.topicByKey(scope, "a").getPromotedAt()).isNotNull();
    }

    @Test
    void topTopicsOrdersByHitsThenRecency() {
        repo.bumpTopic(scope, "少", "few", null);
        repo.bumpTopic(scope, "多", "many", null);
        repo.bumpTopic(scope, "多", "many", null);

        assertThat(repo.topTopics(scope, 0))
                .extracting(MemoryTopicStat::getNormalizedKey)
                .containsExactly("many", "few");
    }

    @Test
    void topicByIdAndDeleteAreScoped() {
        MemoryTopicStat stat = repo.bumpTopic(scope, "a", "a", null);

        assertThat(repo.topicById(scope, stat.getId()).getNormalizedKey()).isEqualTo("a");
        assertThat(repo.topicById(scope, "")).isNull();
        assertThat(repo.topicById(scope, "missing")).isNull();

        repo.deleteTopic(new MemoryScope(TENANT, "web_user:other"), stat.getId());
        assertThat(repo.topicById(scope, stat.getId())).as("跨主体删除必须无效").isNotNull();

        repo.deleteTopic(scope, stat.getId());
        assertThat(repo.topicById(scope, stat.getId())).isNull();
    }

    @Test
    void deleteAllTopicsClearsTheCounters() {
        repo.bumpTopic(scope, "a", "a", null);
        repo.bumpTopic(scope, "b", "b", null);

        repo.deleteAllTopics(scope);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_topic_stats", Integer.class))
                .isZero();
    }

    // ── 文档亲和 ───────────────────────────────────────────────────────────

    private MemoryDocAffinity doc(String knowledgeId, String title) {
        MemoryDocAffinity row = new MemoryDocAffinity();
        row.setKnowledgeId(knowledgeId);
        row.setTitle(title);
        row.setKnowledgeBaseId("kb-1");
        return row;
    }

    @Test
    void bumpDocAffinityCountsAndOnlyOverwritesNonEmptyMetadata() {
        repo.bumpDocAffinity(scope, List.of(doc("k1", "标题")));
        repo.bumpDocAffinity(scope, List.of(doc("k1", "标题")));

        assertThat(repo.docAffinity(scope, List.of("k1"))).containsEntry("k1", 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_doc_affinity", Integer.class))
                .isEqualTo(1);

        // 一次没带标题的引用不该把已有标题冲成空串
        MemoryDocAffinity withoutTitle = new MemoryDocAffinity();
        withoutTitle.setKnowledgeId("k1");
        repo.bumpDocAffinity(scope, List.of(withoutTitle));

        MemoryDocAffinity row = repo.topDocAffinity(scope, 0).get(0);
        assertThat(row.getTitle()).isEqualTo("标题");
        assertThat(row.getKnowledgeBaseId()).isEqualTo("kb-1");
        assertThat(row.getHits()).isEqualTo(3);
    }

    @Test
    void bumpDocAffinitySkipsEmptyKnowledgeId() {
        MemoryDocAffinity empty = new MemoryDocAffinity();
        repo.bumpDocAffinity(scope, List.of(empty));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_doc_affinity", Integer.class))
                .isZero();
        assertThat(repo.docAffinity(scope, List.of())).isNull();
        assertThat(repo.docAffinity(scope, null)).isNull();
    }

    /** {@code minHits < 1} 回落到 {@code MemoryDocAffinityMinHits}（= 2）。 */
    @Test
    void listFamiliarDocsUsesTheDefaultMinHits() {
        repo.bumpDocAffinity(scope, List.of(doc("k1", "只引用了一次")));
        repo.bumpDocAffinity(scope, List.of(doc("k2", "引用了两次")));
        repo.bumpDocAffinity(scope, List.of(doc("k2", "引用了两次")));

        MemoryRepository.Page<MemoryDocAffinity> page = repo.listFamiliarDocs(scope, 0, 0, 0);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(MemoryDocAffinity::getKnowledgeId).containsExactly("k2");

        // 显式给 1 时两条都算
        assertThat(repo.listFamiliarDocs(scope, 1, 0, 0).total()).isEqualTo(2);
    }

    @Test
    void docAffinityByIdAndDeleteAreScoped() {
        repo.bumpDocAffinity(scope, List.of(doc("k1", "t")));
        MemoryDocAffinity row = repo.topDocAffinity(scope, 0).get(0);

        assertThat(repo.docAffinityById(scope, row.getId())).isNotNull();
        assertThat(repo.docAffinityById(scope, "")).isNull();
        assertThat(repo.docAffinityById(scope, "missing")).isNull();

        repo.deleteDocAffinity(new MemoryScope(TENANT, "web_user:other"), row.getId());
        assertThat(repo.docAffinityById(scope, row.getId())).as("跨主体删除必须无效").isNotNull();

        repo.deleteAllDocAffinity(scope);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_doc_affinity", Integer.class))
                .isZero();
    }

    /**
     * {@code withSubject} 里的主体行不存在时把 not-found **原样上抛**
     * （Go 的 {@code gorm.ErrRecordNotFound}），不是静默当成"没有主体"继续。
     */
    @Test
    void transactionalWritesThrowWhenSubjectRowIsMissing() {
        assertThatThrownBy(() -> repo.supersedeItem(scope, "any-id", "any-other"))
                .isInstanceOf(MemorySubjectMissingException.class)
                .hasMessage("record not found");
    }

    // ── 抽取进度：快照的"更新前"语义 ───────────────────────────────────────

    /**
     * {@code EnqueuePendingSession} 返回的是**更新之前**的快照。
     * Go 里 {@code snapshot = *subject} 是结构体按值复制；Java 侧若忘了拷贝，
     * 返回的就会是改过之后的状态（{@code pending_sessions} 被清空、{@code extract_scheduled_at} 被写上）。
     */
    @Test
    void enqueuePendingSessionReturnsThePreUpdateSnapshot() {
        repo.ensureSubject(scope);
        // 直接把遗留队列写进库里（模拟升级前的历史行：pending_sessions 非空）
        jdbc.update("UPDATE memory_subjects SET pending_sessions = '[\"legacy-session\"]' "
                + "WHERE tenant_id = ? AND subject_id = ?", TENANT, scope.subjectId());

        MemoryRepository.EnqueueResult result =
                repo.enqueuePendingSession(scope, "sess-1", Duration.ofSeconds(90));

        assertThat(result.subject().getPendingSessions())
                .as("快照必须是更新**之前**的队列")
                .containsExactly("legacy-session");
        assertThat(result.subject().getExtractScheduledAt()).isNull();
        assertThat(result.shouldSend()).as("有遗留负载 + 本会话 → 该投递").isTrue();

        // 库里：遗留队列被导空、在途标记写上了
        assertThat(jdbc.queryForObject("SELECT pending_sessions FROM memory_subjects", String.class))
                .isEqualTo("[]");
        assertThat(repo.getSubject(scope).getExtractScheduledAt()).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM memory_extraction_sessions", Integer.class)).isEqualTo(2);
    }
}
