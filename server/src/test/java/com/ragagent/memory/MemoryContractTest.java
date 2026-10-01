package com.ragagent.memory;

import com.ragagent.common.web.JsonMappers;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.settings.MemoryConfig;
import com.ragagent.memory.domain.MemoryConsolidationResult;
import com.ragagent.memory.domain.MemoryDocView;
import com.ragagent.memory.domain.MemorySettings;
import com.ragagent.memory.domain.MemoryTopicView;
import org.junit.jupiter.api.Test;

/**
 * memory 模块**响应契约**的逐字节测试（波 0 第 1 步：settings 切片）。
 *
 * <h2>期望值来源</h2>
 * <p>Go 实录——把 {@code internal/types/memory.go} 的这几个类型原样抄进一个独立 Go 程序，
 * 喂同样的输入打印 {@code json.Marshal}，再抄进来。</p>
 *
 * <p>语料刻意覆盖三个最容易看走眼的地方：</p>
 * <ol>
 *   <li>{@link MemoryConfig} 的**三态** {@code *bool}——{@code null} 要写出去
 *       （"没配"≠"显式配成 false"），且全类型无 omitempty；</li>
 *   <li>{@link MemoryConsolidationResult#getSkipped()} 换锚后**恒输出**：
 *       没跳过时是 {@code null}，空串就是空串（§1.5 可空字段显式 null）；</li>
 *   <li>{@link MemoryTopicView} 的 {@code aliases} 无 omitempty，nil 输出 {@code null}
 *       （而投影函数补空列表，两种形态并存）。</li>
 * </ol>
 *
 * <p>响应体三个类型（{@link MemorySettings} / {@link MemoryConsolidationResult} /
 * {@link MemoryTopicView} / {@link MemoryDocView}）已随 §14.9k M1 换锚：JSON 字段名＝
 * Java 字段名（camelCase）。{@link MemoryConfig} 是 tenants 的 jsonb 载荷，属 M2 范围，
 * 这里仍是库内键名。</p>
 */
class MemoryContractTest {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private static OffsetDateTime localTime(int hour) {
        return java.time.ZonedDateTime.of(2026, 9, 18, hour, 0, 0, 0, ZoneId.systemDefault())
                .toOffsetDateTime();
    }

    private static String write(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    // ── MemoryConfig（tenants 上的 jsonb） ──────────────────────────────────

    @Test
    void memoryConfigFullMatchesGo() throws Exception {
        MemoryConfig c = new MemoryConfig();
        c.setEnabled(true);
        c.setWriteMode(MemoryConfig.WRITE_MODE_AUTO);
        c.setExtractModelId("m1");
        c.setMaxItems(200);
        c.setExtractDelaySeconds(30);
        c.setExtractMinIntervalSeconds(60);
        c.setExtractInstructions("instr");
        c.setInterestThreshold(3);
        c.setEmbeddingModelId("e1");
        c.setVectorRecall(true);
        c.setRetrievalConditioning(false);

        assertThat(write(c)).isEqualTo(
                "{\"enabled\":true,\"write_mode\":\"auto\",\"extract_model_id\":\"m1\","
                        + "\"max_items\":200,\"extract_delay_seconds\":30,"
                        + "\"extract_min_interval_seconds\":60,\"extract_instructions\":\"instr\","
                        + "\"interest_threshold\":3,\"embedding_model_id\":\"e1\","
                        + "\"vector_recall\":true,\"retrieval_conditioning\":false}");
    }

    /**
     * 零值：**所有键都输出**（本类型没有 omitempty），两个 {@code *bool} 写 {@code null}。
     */
    @Test
    void memoryConfigZeroMatchesGo() throws Exception {
        assertThat(write(new MemoryConfig())).isEqualTo(
                "{\"enabled\":false,\"write_mode\":\"\",\"extract_model_id\":\"\",\"max_items\":0,"
                        + "\"extract_delay_seconds\":0,\"extract_min_interval_seconds\":0,"
                        + "\"extract_instructions\":\"\",\"interest_threshold\":0,"
                        + "\"embedding_model_id\":\"\",\"vector_recall\":null,"
                        + "\"retrieval_conditioning\":null}");
    }

    /** 三态：显式 false 与 null 是**不同**的输出——压成 boolean 就把这个区分弄丢了。 */
    @Test
    void memoryConfigVectorRecallIsTriState() throws Exception {
        MemoryConfig unset = new MemoryConfig();
        MemoryConfig explicitOff = new MemoryConfig();
        explicitOff.setVectorRecall(false);

        assertThat(write(unset)).contains("\"vector_recall\":null");
        assertThat(write(explicitOff)).contains("\"vector_recall\":false");
    }

    // ── 响应体 ─────────────────────────────────────────────────────────────

    @Test
    void memorySettingsMatchesGo() throws Exception {
        MemorySettings s = new MemorySettings();
        s.setWorkspaceEnabled(true);
        s.setUserEnabled(false);
        s.setEffective(false);
        s.setWriteMode(MemoryConfig.WRITE_MODE_EXPLICIT_ONLY);
        s.setItemCount(7);
        s.setMaxItems(200);

        assertThat(write(s)).isEqualTo(
                "{\"workspaceEnabled\":true,\"userEnabled\":false,\"effective\":false,"
                        + "\"writeMode\":\"explicit_only\",\"itemCount\":7,\"maxItems\":200}");
    }

    /** {@code skipped} 换锚后恒输出：没跳过时是 {@code null}（旧 Go 的 omitempty 退役）。 */
    @Test
    void consolidationResultAlwaysEmitsSkipped() throws Exception {
        assertThat(write(new MemoryConsolidationResult())).isEqualTo(
                "{\"merged\":0,\"demoted\":0,\"expired\":0,\"reviewed\":0,\"candidates\":0,"
                        + "\"skipped\":null}");

        MemoryConsolidationResult merged = new MemoryConsolidationResult();
        merged.setMerged(2);
        merged.setDemoted(1);
        merged.setExpired(1);
        merged.setReviewed(9);
        merged.setCandidates(3);
        merged.setSkipped("");
        assertThat(write(merged)).isEqualTo(
                "{\"merged\":2,\"demoted\":1,\"expired\":1,\"reviewed\":9,\"candidates\":3,"
                        + "\"skipped\":\"\"}");

        MemoryConsolidationResult skipped = new MemoryConsolidationResult();
        skipped.setSkipped(MemoryConsolidationResult.SKIP_TOO_SOON);
        assertThat(write(skipped)).contains("\"skipped\":\"too_soon\"");
    }

    /** {@code aliases} 无 omitempty：nil 输出 {@code null}，空列表输出 {@code []}。 */
    @Test
    void topicViewAliasesKeepTheNilEmptyDistinction() throws Exception {
        MemoryTopicView nilAliases = new MemoryTopicView();
        nilAliases.setId("t1");
        nilAliases.setTopic("db");
        nilAliases.setHits(2);
        nilAliases.setThreshold(3);
        nilAliases.setLastSeenAt(localTime(10));

        assertThat(write(nilAliases)).isEqualTo(
                "{\"id\":\"t1\",\"topic\":\"db\",\"aliases\":null,\"hits\":2,\"threshold\":3,"
                        + "\"lastSeenAt\":\"2026-09-18T10:00:00+08:00\"}");

        MemoryTopicView withAliases = new MemoryTopicView();
        withAliases.setId("t1");
        withAliases.setTopic("db");
        withAliases.setAliases(new ArrayList<>(List.of("a", "b")));
        withAliases.setHits(2);
        withAliases.setThreshold(3);
        withAliases.setLastSeenAt(localTime(10));

        assertThat(write(withAliases)).isEqualTo(
                "{\"id\":\"t1\",\"topic\":\"db\",\"aliases\":[\"a\",\"b\"],\"hits\":2,"
                        + "\"threshold\":3,\"lastSeenAt\":\"2026-09-18T10:00:00+08:00\"}");
    }

    @Test
    void docViewMatchesGo() throws Exception {
        MemoryDocView d = new MemoryDocView();
        d.setId("d1");
        d.setKnowledgeId("k1");
        d.setKnowledgeBaseId("kb1");
        d.setTitle("t");
        d.setHits(4);
        d.setLastUsedAt(localTime(10));

        assertThat(write(d)).isEqualTo(
                "{\"id\":\"d1\",\"knowledgeId\":\"k1\",\"knowledgeBaseId\":\"kb1\","
                        + "\"title\":\"t\",\"hits\":4,"
                        + "\"lastUsedAt\":\"2026-09-18T10:00:00+08:00\"}");
    }

    /** 零值时间必须输出 Go 的 year-1 字面量，而不是 {@code null}。 */
    @Test
    void viewsEmitGoZeroTimeRatherThanNull() throws Exception {
        assertThat(write(new MemoryTopicView())).contains("\"lastSeenAt\":\"0001-01-01T00:00:00Z\"");
        assertThat(write(new MemoryDocView())).contains("\"lastUsedAt\":\"0001-01-01T00:00:00Z\"");
    }
}
