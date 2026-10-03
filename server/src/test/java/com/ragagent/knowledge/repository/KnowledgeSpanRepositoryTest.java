package com.ragagent.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.ragagent.TestSchema;
import com.ragagent.knowledge.domain.KnowledgeProcessingSpan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * span 仓储语义（H2）——重点钉住 mock 测不出的
 * SQL 行为：upsert 的**动态列**语义（EndSpan 只写 output 不得冲掉 Begin 的 input /
 * metadata）、attempt==0 → 1、attempt 分配、BFS 级联取消（终态行保持）、
 * cancelAllOpenSpans / cancelOpenSpansByName 的集合语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeSpanRepositoryTest {

    private static final String KID = "kg-span-test-1";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private KnowledgeSpanRepository repo;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    private KnowledgeProcessingSpan row(String spanId, String parent, String name, String kind,
                                        String status) {
        KnowledgeProcessingSpan r = new KnowledgeProcessingSpan();
        r.setKnowledgeId(KID);
        r.setAttempt(1);
        r.setSpanId(spanId);
        r.setParentSpanId(parent == null ? "" : parent);
        r.setName(name);
        r.setKind(kind);
        r.setStatus(status);
        r.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        r.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return r;
    }

    @Test
    void upsertInsertAndReadBack() {
        KnowledgeProcessingSpan root = row("sp-root", "", "knowledge_processing", "root", "running");
        root.setInput(Map.of("k", "v"));
        repo.upsert(root);

        List<KnowledgeProcessingSpan> rows = repo.listByAttempt(KID, 1);
        assertThat(rows).hasSize(1);
        KnowledgeProcessingSpan got = rows.get(0);
        assertThat(got.getSpanId()).isEqualTo("sp-root");
        assertThat(got.getName()).isEqualTo("knowledge_processing");
        assertThat(got.getStatus()).isEqualTo("running");
        assertThat(got.getInput()).containsEntry("k", "v");
        assertThat(got.getOutput()).isNull();
        assertThat(got.getCreatedAt()).isNotNull();
    }

    @Test
    void upsertZeroAttemptBecomesOne() {
        KnowledgeProcessingSpan r = row("sp-a", "", "docreader", "stage", "running");
        r.setAttempt(0);
        repo.upsert(r);
        assertThat(repo.latestAttempt(KID)).isEqualTo(1);
        assertThat(repo.listByAttempt(KID, 1)).hasSize(1);
    }

    /** 动态列语义：第二次 upsert 只带 output → input/metadata 必须保留。 */
    @Test
    void upsertUpdatesOnlyProvidedContentColumns() {
        KnowledgeProcessingSpan begin = row("sp-x", "", "chunking", "stage", "running");
        begin.setInput(Map.of("chunks", 3));
        begin.setMetadata(Map.of("m", 1));
        repo.upsert(begin);

        KnowledgeProcessingSpan end = row("sp-x", "", "chunking", "stage", "done");
        end.setOutput(Map.of("written", 3));
        end.setDurationMs(42);
        repo.upsert(end);

        KnowledgeProcessingSpan got = repo.getSpan(KID, 1, "sp-x");
        assertThat(got.getStatus()).isEqualTo("done");
        assertThat(got.getInput()).containsEntry("chunks", 3);
        assertThat(got.getMetadata()).containsEntry("m", 1);
        assertThat(got.getOutput()).containsEntry("written", 3);
        assertThat(got.getDurationMs()).isEqualTo(42);
    }

    @Test
    void attemptAllocation() {
        assertThat(repo.nextAttempt(KID)).isEqualTo(1);
        assertThat(repo.latestAttempt(KID)).isEqualTo(0);
        repo.upsert(row("sp-1", "", "docreader", "stage", "done"));
        assertThat(repo.nextAttempt(KID)).isEqualTo(2);
        KnowledgeProcessingSpan second = row("sp-1", "", "docreader", "stage", "done");
        second.setAttempt(2);
        repo.upsert(second);
        assertThat(repo.latestAttempt(KID)).isEqualTo(2);
        // 指定 attempt=0 时读全部
        assertThat(repo.listByAttempt(KID, 0)).hasSize(2);
    }

    @Test
    void getSpanMissingReturnsNull() {
        assertThat(repo.getSpan(KID, 1, "nope")).isNull();
    }

    /** BFS 级联：只翻 pending/running；终态行（done/failed）保持原状。 */
    @Test
    void cancelDescendantsFlipsOnlyOpenRows() {
        repo.upsert(row("sp-root", "", "knowledge_processing", "root", "running"));
        repo.upsert(row("sp-stage", "sp-root", "chunking", "stage", "failed"));
        repo.upsert(row("sp-sub-open", "sp-stage", "chunking.batch[0]", "subspan", "running"));
        repo.upsert(row("sp-sub-done", "sp-stage", "chunking.batch[1]", "subspan", "done"));

        long affected = repo.cancelDescendants(KID, 1, "sp-stage", "upstream chunking failed");
        assertThat(affected).isEqualTo(1);
        KnowledgeProcessingSpan open = repo.getSpan(KID, 1, "sp-sub-open");
        assertThat(open.getStatus()).isEqualTo("cancelled");
        assertThat(open.getErrorCode()).isEqualTo("UPSTREAM_FAILED");
        assertThat(open.getErrorMessage()).isEqualTo("upstream chunking failed");
        assertThat(repo.getSpan(KID, 1, "sp-sub-done").getStatus()).isEqualTo("done");
    }

    @Test
    void cancelAllOpenSpansFlipsPendingAndRunning() {
        repo.upsert(row("sp-1", "", "a", "stage", "running"));
        repo.upsert(row("sp-2", "", "b", "stage", "pending"));
        repo.upsert(row("sp-3", "", "c", "stage", "done"));
        long affected = repo.cancelAllOpenSpans(KID, 1, "USER_CANCELLED", "用户已取消解析");
        assertThat(affected).isEqualTo(2);
        assertThat(repo.getSpan(KID, 1, "sp-1").getStatus()).isEqualTo("cancelled");
        assertThat(repo.getSpan(KID, 1, "sp-1").getFinishedAt()).isNotNull();
        assertThat(repo.getSpan(KID, 1, "sp-3").getStatus()).isEqualTo("done");
    }

    @Test
    void cancelOpenSpansByNameScopesToName() {
        repo.upsert(row("sp-r1", "", "postprocess.summary", "subspan", "running"));
        repo.upsert(row("sp-r2", "", "postprocess.question", "subspan", "running"));
        long affected = repo.cancelOpenSpansByName(KID, 1, "postprocess.summary",
                "RETRY", "reopened after retry");
        assertThat(affected).isEqualTo(1);
        assertThat(repo.getSpan(KID, 1, "sp-r1").getStatus()).isEqualTo("cancelled");
        assertThat(repo.getSpan(KID, 1, "sp-r2").getStatus()).isEqualTo("running");
        // 守卫：空 name / attempt<=0 → 0
        assertThat(repo.cancelOpenSpansByName(KID, 1, "", "X", "y")).isZero();
        assertThat(repo.cancelOpenSpansByName(KID, 0, "postprocess.summary", "X", "y")).isZero();
    }
}
