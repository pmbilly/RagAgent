package com.ragagent.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.mapper.AuditLogRepository;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.audit.service.WikiActivityAuditRecorder;
import com.ragagent.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Wiki 活动埋点的落库实现测试——对照 Go
 * {@code internal/application/service/kb_activity.go} 的
 * {@code RecordWikiContentActivity}（L162-181）/ {@code recordKBActivity}（L85-151）。
 *
 * <p>这是本轮的"接上既有桩"验证：{@code WikiActivityAudit} 此前只有接口没有实现 bean，
 * 6 处埋点全部退化成 debug 日志；{@link WikiActivityAuditRecorder} 补上之后
 * 这些埋点才会真正产出 {@code wiki.content_changed} 审计行。</p>
 */
class WikiActivityAuditRecorderTest {

    private final AuditLogRepository repo = mock(AuditLogRepository.class);
    private final List<AuditLog> written = new ArrayList<>();
    private final WikiActivityAuditRecorder recorder =
            new WikiActivityAuditRecorder(new AuditLogService(repo));

    WikiActivityAuditRecorderTest() {
        try {
            org.mockito.Mockito.doAnswer(inv -> {
                written.add(inv.getArgument(0));
                return null;
            }).when(repo).create(any(AuditLog.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static Map<String, Integer> actions(String k, int v) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    @Test
    void writesWikiContentChangedRow() {
        TenantContext.set(9L, TenantContext.webUserPrincipal("u-editor"), "contributor",
                false, "u-editor", false);

        recorder.wikiContentChanged(7L, "kb-1", actions("manual_edit", 1));

        assertThat(written).hasSize(1);
        AuditLog row = written.get(0);
        assertThat(row.getAction()).isEqualTo(AuditAction.WIKI_CONTENT_CHANGED);
        assertThat(row.getScopeType()).isEqualTo("knowledge_base");
        assertThat(row.getScopeId()).isEqualTo("kb-1");
        assertThat(row.getTargetType()).isEqualTo("wiki");
        assertThat(row.getTargetId()).isEqualTo("kb-1");
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(row.getTenantId()).isEqualTo(7L);
        assertThat(row.getActorUserId()).isEqualTo("u-editor");
        assertThat(row.getActorRole()).isEqualTo("contributor");
        // Details 对照 Go 的 map[string]any{"count": N, "actions": {...}}；字母序 actions < count
        assertThat(row.getDetails().get("count").asInt()).isEqualTo(1);
        assertThat(row.getDetails().get("actions").get("manual_edit").asInt()).isEqualTo(1);
        List<String> names = new ArrayList<>();
        row.getDetails().fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly("actions", "count");
    }

    /** 对照 Go {@code count := sum(actions); if count == 0 { return }}：全零不写。 */
    @Test
    void skipsWhenCountIsZero() {
        TenantContext.set(9L, TenantContext.webUserPrincipal("u"), "owner", false, "u", false);

        recorder.wikiContentChanged(7L, "kb-1", actions("manual_create", 0));
        recorder.wikiContentChanged(7L, "kb-1", Map.of());

        assertThat(written).isEmpty();
    }

    /** 对照 Go {@code recordKBActivity} 的入参守卫：kbID 为空 → 不写。 */
    @Test
    void skipsWhenKnowledgeBaseMissing() {
        TenantContext.set(9L, TenantContext.webUserPrincipal("u"), "owner", false, "u", false);

        recorder.wikiContentChanged(7L, "", actions("manual_create", 1));
        recorder.wikiContentChanged(7L, null, actions("manual_create", 1));

        assertThat(written).isEmpty();
    }

    /** tenantId 传 0 时回落到上下文租户；仍为 0 则不写（对照 Go 的两段判定）。 */
    @Test
    void fallsBackToContextTenantAndSkipsWhenAbsent() {
        TenantContext.set(9L, TenantContext.webUserPrincipal("u"), "owner", false, "u", false);
        recorder.wikiContentChanged(0L, "kb-1", actions("ingest", 2));
        assertThat(written).hasSize(1);
        assertThat(written.get(0).getTenantId()).isEqualTo(9L);

        TenantContext.clear();
        recorder.wikiContentChanged(0L, "kb-1", actions("ingest", 2));
        assertThat(written).hasSize(1);   // 没新增
    }

    /** 无 actor 时 actor_role 留空（对照 Go {@code if actorID != "" { actorRole = ... }}）。 */
    @Test
    void omitsActorRoleWhenActorMissing() {
        TenantContext.set(7L, null, null, false, null, false);

        recorder.wikiContentChanged(7L, "kb-1", actions("retract", 3));

        assertThat(written).hasSize(1);
        assertThat(written.get(0).getActorUserId()).isEmpty();
        assertThat(written.get(0).getActorRole()).isEmpty();
        assertThat(written.get(0).getDetails().get("count").asInt()).isEqualTo(3);
    }

    /** 审计失败绝不影响调用方（Wiki 编辑不能被埋点拖垮）。 */
    @Test
    void auditFailureIsSwallowed() {
        doThrow(new IllegalStateException("db down")).when(repo).create(any(AuditLog.class));
        TenantContext.set(7L, TenantContext.webUserPrincipal("u"), "owner", false, "u", false);

        recorder.wikiContentChanged(7L, "kb-1", actions("manual_delete", 1));
        // 不抛 = 通过
    }
}
