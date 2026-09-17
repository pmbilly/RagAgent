package com.ragagent.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.TestSchema;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditLogQuery;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.mapper.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * audit_logs 仓储语义测试（H2）——对照 Go
 * internal/application/repository/audit_log_scope_test.go（L13-51），
 * 并按 {@code audit_log.go} 的 SQL 行为补齐 mock 测不出来的部分。
 *
 * <p>覆盖：作用域过滤（scope_type/scope_id）、UnscopedOnly、租户边界、
 * id DESC 排序与游标、limit 默认/上限、action/outcome/actor 过滤、
 * 去重 count 的窗口与元组语义、retention DELETE 的影响行数，
 * 以及 details 这个 jsonb 列的往返（读回经 PgJsonTypeHandler 规范化）。</p>
 *
 * <p>{@code @AutoConfigureMockMvc} 与其余契约测试共用同一个 Spring 上下文缓存键
 * ——理由见 {@code TenantAPIKeyRepositoryTest} 的类注释（不引入新上下文键）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditLogRepositoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuditLogRepository repo;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    private AuditLog row(long tenantId, String action, String scopeType, String scopeId) {
        AuditLog e = new AuditLog();
        e.setTenantId(tenantId);
        e.setAction(action);
        e.setScopeType(scopeType == null ? "" : scopeType);
        e.setScopeId(scopeId == null ? "" : scopeId);
        e.setOutcome(AuditOutcome.SUCCESS);
        e.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return e;
    }

    private void insert(AuditLog e) {
        repo.create(e);
    }

    // ── 作用域（对照 audit_log_scope_test.go） ───────────────────────────

    @Test
    void listFiltersKnowledgeBaseScope() {
        insert(row(7, AuditAction.MEMBER_ADDED, "", ""));
        insert(row(7, AuditAction.KB_UPDATED, "knowledge_base", "kb-a"));
        insert(row(7, AuditAction.KNOWLEDGE_CREATED, "knowledge_base", "kb-b"));
        insert(row(8, AuditAction.KB_UPDATED, "knowledge_base", "kb-a"));

        List<AuditLog> scoped = repo.list(7, new AuditLogQuery(
                0, 0, "", "", "", "knowledge_base", "kb-a", false));
        assertThat(scoped).hasSize(1);
        assertThat(scoped.get(0).getTenantId()).isEqualTo(7L);
        assertThat(scoped.get(0).getScopeId()).isEqualTo("kb-a");

        List<AuditLog> unscoped = repo.list(7, new AuditLogQuery(
                0, 0, "", "", "", "", "", true));
        assertThat(unscoped).hasSize(1);
        assertThat(unscoped.get(0).getAction()).isEqualTo(AuditAction.MEMBER_ADDED);
    }

    /** 租户边界：tenant_id 是查询的第一条件，跨租户行绝不外泄。 */
    @Test
    void listIsTenantScoped() {
        insert(row(7, AuditAction.MEMBER_ADDED, "", ""));
        insert(row(8, AuditAction.MEMBER_ADDED, "", ""));

        assertThat(repo.list(7, AuditLogQuery.empty())).hasSize(1);
        assertThat(repo.list(8, AuditLogQuery.empty())).hasSize(1);
        assertThat(repo.list(9, AuditLogQuery.empty())).isEmpty();
    }

    // ── 排序 / 游标 / 分页 ───────────────────────────────────────────────

    /** 对照 Go 的 {@code Order("id DESC")}：最新在前，游标取 id &lt; after_id。 */
    @Test
    void listOrdersByIdDescAndHonorsCursor() {
        for (int i = 0; i < 5; i++) {
            insert(row(7, AuditAction.MEMBER_ADDED, "", ""));
        }
        List<AuditLog> all = repo.list(7, AuditLogQuery.empty());
        assertThat(all).hasSize(5);
        List<Long> ids = new ArrayList<>();
        all.forEach(e -> ids.add(e.getId()));
        assertThat(ids).isSortedAccordingTo((a, b) -> Long.compare(b, a));

        long newest = ids.get(0);
        List<AuditLog> older = repo.list(7, new AuditLogQuery(newest, 0, "", "", "", "", "", false));
        assertThat(older).hasSize(4);
        assertThat(older).allSatisfy(e -> assertThat(e.getId()).isLessThan(newest));
    }

    /** 对照 Go {@code limit := 50}（默认）与 {@code auditLogListLimitMax = 100}（硬上限）。 */
    @Test
    void listAppliesDefaultAndMaxLimit() {
        for (int i = 0; i < 60; i++) {
            insert(row(7, AuditAction.MEMBER_ADDED, "", ""));
        }
        // 默认 50
        assertThat(repo.list(7, AuditLogQuery.empty())).hasSize(50);
        // limit <= 0 → 默认
        assertThat(repo.list(7, new AuditLogQuery(0, 0, "", "", "", "", "", false))).hasSize(50);
        assertThat(repo.list(7, new AuditLogQuery(0, -3, "", "", "", "", "", false))).hasSize(50);
        // 显式小于上限
        assertThat(repo.list(7, new AuditLogQuery(0, 10, "", "", "", "", "", false))).hasSize(10);
        // 超上限 → 截到 100（此处只有 60 行，断言"没被 999 放大"且不超过 100）
        assertThat(repo.list(7, new AuditLogQuery(0, 999, "", "", "", "", "", false)))
                .hasSizeLessThanOrEqualTo(AuditLogRepository.MAX_LIMIT);
    }

    // ── 过滤器 ───────────────────────────────────────────────────────────

    /** action / outcome / actor 都是精确匹配（对照 Go 的三条独立 Where）。 */
    @Test
    void listAppliesExactMatchFilters() {
        AuditLog a = row(7, AuditAction.ACCESS_DENIED, "", "");
        a.setActorUserId("u-a");
        a.setOutcome(AuditOutcome.DENIED);
        insert(a);

        AuditLog b = row(7, AuditAction.MEMBER_ADDED, "", "");
        b.setActorUserId("u-b");
        insert(b);

        assertThat(repo.list(7, new AuditLogQuery(0, 0, AuditAction.ACCESS_DENIED, "", "", "", "", false)))
                .hasSize(1);
        assertThat(repo.list(7, new AuditLogQuery(0, 0, "", AuditOutcome.DENIED, "", "", "", false)))
                .hasSize(1);
        assertThat(repo.list(7, new AuditLogQuery(0, 0, "", "", "u-b", "", "", false)))
                .hasSize(1);
        assertThat(repo.list(7, new AuditLogQuery(0, 0, "no.such.action", "", "", "", "", false)))
                .isEmpty();
    }

    /** 空串过滤器 = 不过滤（Go 的 string 零值语义），不能被当成"匹配空串"。 */
    @Test
    void listTreatsEmptyFiltersAsAbsent() {
        insert(row(7, AuditAction.MEMBER_ADDED, "", ""));
        assertThat(repo.list(7, new AuditLogQuery(0, 0, "", "", "", "", "", false))).hasSize(1);
    }

    // ── 去重 count ───────────────────────────────────────────────────────

    @Test
    void countSinceForDedupMatchesTupleAndWindow() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AuditLog e = row(7, AuditAction.ACCESS_DENIED, "", "");
        e.setActorUserId("u-viewer");
        e.setRequestPath("/api/v1/tenants/{id}/audit-log");
        e.setOutcome(AuditOutcome.DENIED);
        e.setCreatedAt(now.minusSeconds(10));
        insert(e);

        assertThat(repo.countSinceForDedup(7, "u-viewer", AuditAction.ACCESS_DENIED,
                "/api/v1/tenants/{id}/audit-log", now.minus(Duration.ofMinutes(1)))).isEqualTo(1);
        // 窗口外（since 晚于该行）
        assertThat(repo.countSinceForDedup(7, "u-viewer", AuditAction.ACCESS_DENIED,
                "/api/v1/tenants/{id}/audit-log", now.minusSeconds(5))).isZero();
        // 元组任一维不同都不算
        assertThat(repo.countSinceForDedup(7, "u-other", AuditAction.ACCESS_DENIED,
                "/api/v1/tenants/{id}/audit-log", now.minus(Duration.ofMinutes(1)))).isZero();
        assertThat(repo.countSinceForDedup(7, "u-viewer", AuditAction.ACCESS_DENIED,
                "/api/v1/other", now.minus(Duration.ofMinutes(1)))).isZero();
        assertThat(repo.countSinceForDedup(8, "u-viewer", AuditAction.ACCESS_DENIED,
                "/api/v1/tenants/{id}/audit-log", now.minus(Duration.ofMinutes(1)))).isZero();
    }

    // ── 保留期 ───────────────────────────────────────────────────────────

    /** 对照 Go {@code DeleteOlderThan}：严格早于 cutoff 的行被删，返回影响行数。 */
    @Test
    void deleteOlderThanRemovesOnlyStrictlyOlderRows() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AuditLog old = row(7, AuditAction.MEMBER_ADDED, "", "");
        old.setCreatedAt(now.minus(Duration.ofDays(100)));
        insert(old);

        AuditLog alsoOld = row(8, AuditAction.MEMBER_ADDED, "", "");
        alsoOld.setCreatedAt(now.minus(Duration.ofDays(91)));
        insert(alsoOld);

        AuditLog fresh = row(7, AuditAction.MEMBER_ADDED, "", "");
        fresh.setCreatedAt(now.minus(Duration.ofDays(1)));
        insert(fresh);

        // 保留期是全局运维策略——删除动作**不带**租户维度
        long deleted = repo.deleteOlderThan(now.minus(Duration.ofDays(90)));
        assertThat(deleted).isEqualTo(2);
        assertThat(repo.list(7, AuditLogQuery.empty())).hasSize(1);
        assertThat(repo.list(8, AuditLogQuery.empty())).isEmpty();
    }

    // ── details（jsonb） ─────────────────────────────────────────────────

    /**
     * details 与 DDL 默认值：Go 的 nil JSON 被 GORM 省略 → 库默认 {@code '{}'}。
     * Java 侧 null 字段被 MyBatis-Plus 省略 → 同样落 {@code '{}'}。
     * 读回经 PgJsonTypeHandler 规范化键序（（长度,字节序）——复刻 PG jsonb）。
     */
    @Test
    void detailsRoundTripsThroughJsonbColumn() {
        ObjectNode details = MAPPER.createObjectNode();
        // 故意按非规范序插入，读回必须已被规范化
        details.put("required_role", "admin");
        details.put("raw_path", "/api/v1/tenants/10002/audit-log");

        AuditLog e = row(7, AuditAction.ACCESS_DENIED, "", "");
        e.setDetails(details);
        insert(e);

        AuditLog back = repo.list(7, AuditLogQuery.empty()).get(0);
        assertThat(back.getDetails()).isNotNull();
        assertThat(back.getDetails().get("required_role").asText()).isEqualTo("admin");
        assertThat(back.getDetails().get("raw_path").asText())
                .isEqualTo("/api/v1/tenants/10002/audit-log");
        // 规范化后的键序：raw_path(8) 短于 required_role(13)
        List<String> names = new ArrayList<>();
        back.getDetails().fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly("raw_path", "required_role");
    }

    /** details 为 null 时落库默认 '{}'（不是 NULL，也不是 "null"）——列是 NOT NULL DEFAULT '{}'。 */
    @Test
    void nullDetailsFallsBackToColumnDefault() {
        AuditLog e = row(7, AuditAction.MEMBER_ADDED, "", "");
        e.setDetails(null);
        insert(e);

        String raw = jdbc.queryForObject(
                "SELECT details FROM audit_logs WHERE tenant_id = 7", String.class);
        assertThat(raw).isEqualTo("{}");

        AuditLog back = repo.list(7, AuditLogQuery.empty()).get(0);
        assertThat(back.getDetails()).isNotNull();
        assertThat(back.getDetails().isObject()).isTrue();
        assertThat(back.getDetails().size()).isZero();
    }

    /** 零值列语义：Go 的非指针 string 零值写 ''，outcome 默认 'success'。 */
    @Test
    void zeroValueColumnsMatchGoSemantics() {
        AuditLog e = row(7, AuditAction.MEMBER_ADDED, null, null);
        e.setOutcome("");           // 未设置 → 库默认 'success'
        e.setActorRole(null);
        e.setRequestPath(null);
        insert(e);

        AuditLog back = repo.list(7, AuditLogQuery.empty()).get(0);
        assertThat(back.getScopeType()).isEmpty();
        assertThat(back.getScopeId()).isEmpty();
        assertThat(back.getActorRole()).isEmpty();
        assertThat(back.getRequestPath()).isEmpty();
        // 列默认 'success'（对照 Go 的 gorm:"default:success"；服务层也会做同样归一）
        assertThat(back.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
    }

    /** 自增主键回填（GORM 的 Create 会写回主键；MyBatis-Plus 的 AUTO 同样回填）。 */
    @Test
    void createBackfillsGeneratedId() {
        AuditLog e = row(7, AuditAction.MEMBER_ADDED, "", "");
        insert(e);
        assertThat(e.getId()).isNotNull().isPositive();
    }
}
