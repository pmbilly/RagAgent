package com.ragagent.memory;

import com.ragagent.common.web.JsonMappers;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.JsonRoundTrip;
import com.ragagent.memory.domain.MemoryDocAffinity;
import com.ragagent.memory.domain.MemoryExtractionSession;
import com.ragagent.memory.domain.MemoryExtractionState;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryItemEmbedding;
import com.ragagent.memory.domain.MemoryMessageCursor;
import com.ragagent.memory.domain.MemorySubject;
import com.ragagent.memory.domain.MemoryTombstone;
import com.ragagent.memory.domain.MemoryTopicStat;
import org.junit.jupiter.api.Test;

/**
 * memory 实体 / jsonb 值的**逐字节 JSON 契约**测试（波 0）。
 *
 * <h2>期望值的来源</h2>
 * <p>Go 实录——把 {@code internal/types/memory.go} 与 {@code memory_extraction.go}
 * 里的这几个类型**原样抄进**一个独立 Go 程序（连 json/gorm tag 一起），
 * 喂同样的输入跑 {@code json.Marshal}，输出抄进下面的断言。
 * 程序里时间用的是 {@code time.FixedZone("CST", 8*3600)}，因为 JVM 默认时区是
 * {@code Asia/Shanghai}，{@code GoTimeSerializer} 会把时间归一化到那里再输出——
 * 用 UTC 录的话两边字面量会差一个偏移、断言无意义。</p>
 *
 * <h2>这份语料刻意盯住的五个坑</h2>
 * <ol>
 *   <li>（换锚后已统一）{@code replacesId} 与 {@code supersededBy} 都**恒输出**空串——
 *       旧 Go 的 {@code omitempty} 随 §1.6「禁止条件键」退役，两种处置不再并存。</li>
 *   <li>{@code inference}（Go {@code Inferred}）是 {@code json:"-" gorm:"-"}：
 *       **既不出响应也不落库**——Java 侧 {@code @JsonIgnore} + {@code @TableField(exist=false)} 缺一不可。</li>
 *   <li>{@code MemorySubject.pendingSessions} 与 {@code MemoryTopicStat.aliases}
 *       的**响应是 {@code null}、落库是 {@code []}**——Go 的 {@code Value()} 与
 *       {@code json.Marshal} 是两条路，别混。（落库侧由 {@code MemoryRepositoryTest} 用真库钉。）</li>
 *   <li>{@code MemoryExtractionState} 的 {@code leaseUntil} **永远输出**（Go 判 omitempty 时
 *       struct 值不算"空"），零值是 year-1 字面量；{@code leaseId} 换锚后也恒输出。</li>
 *   <li>{@code MemoryExtractionSession} 的游标在 JSON 里是**嵌套对象** {@code cursor}，
 *       在库里是**两个平列** {@code cursor_at}/{@code cursor_id}。这里只钉 JSON 那一半。</li>
 * </ol>
 *
 * <p>§14.9k M2 换锚后，本文件里**所有**类型的 JSON 键名都＝Java 字段名（camelCase）。</p>
 */
class MemoryEntityJsonTest {

    /** 与运行时一致的映射器（JacksonConfig 会装 GoJsonEscapes；这里没有需要转义的字符）。 */
    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    /** jsonb 读路径用的**裸**映射器——必须容忍未知属性（§9）。 */
    private static final ObjectMapper JSONB = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static String write(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    /** 与 Go 的 {@code time.FixedZone("CST", 8*3600)} 同墙钟：JVM 默认时区的 10:00。 */
    private static OffsetDateTime ten() {
        return ZonedDateTime.of(2026, 9, 18, 10, 0, 0, 0, ZoneId.systemDefault()).toOffsetDateTime();
    }

    // ── 零值（最容易被"顺手补 omitempty"改坏） ─────────────────────────────

    @Test
    void memorySubjectZeroMatchesGo() throws Exception {
        assertThat(write(new MemorySubject())).isEqualTo(
                "{\"id\":\"\",\"tenantId\":0,\"subjectId\":\"\",\"enabled\":false,"
                        + "\"blockText\":\"\",\"blockUpdatedAt\":null,\"itemCount\":0,"
                        + "\"lastExtractedAt\":null,\"extractCursor\":null,\"pendingSessions\":null,"
                        + "\"extractScheduledAt\":null,\"consolidatedAt\":null,"
                        + "\"forcedConsolidatedAt\":null,"
                        + "\"createdAt\":\"0001-01-01T00:00:00Z\",\"updatedAt\":\"0001-01-01T00:00:00Z\"}");
    }

    @Test
    void memorySubjectFullMatchesGo() throws Exception {
        MemorySubject s = new MemorySubject();
        s.setId("s1");
        s.setTenantId(7L);
        s.setSubjectId("web_user:u1");
        s.setEnabled(true);
        s.setBlockText("b");
        s.setItemCount(2);
        s.setPendingSessions(new ArrayList<>(List.of("a")));
        s.setExtractionState(new MemoryExtractionState());
        s.setCreatedAt(ten());
        s.setUpdatedAt(ten());

        assertThat(write(s)).isEqualTo(
                "{\"id\":\"s1\",\"tenantId\":7,\"subjectId\":\"web_user:u1\",\"enabled\":true,"
                        + "\"blockText\":\"b\",\"blockUpdatedAt\":null,\"itemCount\":2,"
                        + "\"lastExtractedAt\":null,\"extractCursor\":null,\"pendingSessions\":[\"a\"],"
                        + "\"extractScheduledAt\":null,\"consolidatedAt\":null,"
                        + "\"forcedConsolidatedAt\":null,"
                        + "\"createdAt\":\"2026-09-18T10:00:00+08:00\","
                        + "\"updatedAt\":\"2026-09-18T10:00:00+08:00\"}");
    }

    /** {@code extractionState} 的 {@code json:"-"}：**一个键都不出**（不是 null、不是 {}）。 */
    @Test
    void memorySubjectNeverExposesExtractionState() throws Exception {
        MemorySubject s = new MemorySubject();
        s.setExtractionState(new MemoryExtractionState());
        assertThat(write(s)).doesNotContain("extractionState").doesNotContain("leaseId");
    }

    @Test
    void memoryItemZeroMatchesGo() throws Exception {
        assertThat(write(new MemoryItem())).isEqualTo(
                "{\"id\":\"\",\"tenantId\":0,\"subjectId\":\"\",\"kind\":\"\",\"content\":\"\","
                        + "\"topic\":\"\",\"normalizedKey\":\"\",\"importance\":0,\"origin\":\"\","
                        + "\"status\":\"\",\"sourceSessionId\":\"\",\"sourceMessageId\":\"\","
                        + "\"validFrom\":\"0001-01-01T00:00:00Z\",\"invalidAt\":null,"
                        + "\"expiresAt\":null,\"replacesId\":\"\",\"supersededBy\":\"\","
                        + "\"lastUsedAt\":null,\"useCount\":0,"
                        + "\"createdAt\":\"0001-01-01T00:00:00Z\","
                        + "\"updatedAt\":\"0001-01-01T00:00:00Z\"}");
    }

    @Test
    void memoryItemFullMatchesGo() throws Exception {
        MemoryItem i = new MemoryItem();
        i.setId("i1");
        i.setTenantId(7L);
        i.setSubjectId("s");
        i.setKind("fact");
        i.setContent("c");
        i.setTopic("t");
        i.setNormalizedKey("nk");
        i.setImportance(3);
        i.setOrigin("extracted");
        i.setStatus("active");
        i.setSourceSessionId("ss");
        i.setSourceMessageId("sm");
        i.setValidFrom(ten());
        i.setReplacesId("r1");
        i.setSupersededBy("sb");
        i.setUseCount(4);
        i.setInferred(true);
        i.setCreatedAt(ten());
        i.setUpdatedAt(ten());

        assertThat(write(i)).isEqualTo(
                "{\"id\":\"i1\",\"tenantId\":7,\"subjectId\":\"s\",\"kind\":\"fact\",\"content\":\"c\","
                        + "\"topic\":\"t\",\"normalizedKey\":\"nk\",\"importance\":3,\"origin\":\"extracted\","
                        + "\"status\":\"active\",\"sourceSessionId\":\"ss\",\"sourceMessageId\":\"sm\","
                        + "\"validFrom\":\"2026-09-18T10:00:00+08:00\",\"invalidAt\":null,"
                        + "\"expiresAt\":null,\"replacesId\":\"r1\",\"supersededBy\":\"sb\","
                        + "\"lastUsedAt\":null,\"useCount\":4,"
                        + "\"createdAt\":\"2026-09-18T10:00:00+08:00\","
                        + "\"updatedAt\":\"2026-09-18T10:00:00+08:00\"}");
    }

    /**
     * 换锚后 {@code replacesId} 与 {@code supersededBy} **一视同仁**：未取代时都是空串但键都在。
     *
     * <p>旧 Go 里前者带 {@code omitempty}（空串时整个键消失）、后者没有——那种"条件键"
     * 随契约 §1.6「禁止条件键」退役，本类型不再有两种处置。</p>
     */
    @Test
    void memoryItemAlwaysEmitsReplacesIdAndSupersededBy() throws Exception {
        String json = write(new MemoryItem());
        assertThat(json).contains("\"replacesId\":\"\"");
        assertThat(json).contains("\"supersededBy\":\"\"");
    }

    /** {@code inferred} 在 Go 里是 {@code json:"-"}：绝不能出现在响应里。 */
    @Test
    void memoryItemNeverExposesInferred() throws Exception {
        MemoryItem i = new MemoryItem();
        i.setInferred(true);
        assertThat(write(i)).doesNotContain("inferred");
    }

    @Test
    void memoryTopicStatZeroMatchesGo() throws Exception {
        assertThat(write(new MemoryTopicStat())).isEqualTo(
                "{\"id\":\"\",\"tenantId\":0,\"subjectId\":\"\",\"normalizedKey\":\"\",\"topic\":\"\","
                        + "\"aliases\":null,\"hits\":0,\"lastSeenAt\":\"0001-01-01T00:00:00Z\","
                        + "\"promotedAt\":null,\"createdAt\":\"0001-01-01T00:00:00Z\","
                        + "\"updatedAt\":\"0001-01-01T00:00:00Z\"}");
    }

    @Test
    void memoryDocAffinityZeroMatchesGo() throws Exception {
        assertThat(write(new MemoryDocAffinity())).isEqualTo(
                "{\"id\":\"\",\"tenantId\":0,\"subjectId\":\"\",\"knowledgeId\":\"\","
                        + "\"knowledgeBaseId\":\"\",\"title\":\"\",\"hits\":0,"
                        + "\"lastUsedAt\":\"0001-01-01T00:00:00Z\","
                        + "\"createdAt\":\"0001-01-01T00:00:00Z\","
                        + "\"updatedAt\":\"0001-01-01T00:00:00Z\"}");
    }

    @Test
    void memoryTombstoneZeroMatchesGo() throws Exception {
        assertThat(write(new MemoryTombstone())).isEqualTo(
                "{\"id\":\"\",\"tenantId\":0,\"subjectId\":\"\",\"topic\":\"\",\"fingerprint\":\"\","
                        + "\"sourceMessageId\":\"\",\"createdAt\":\"0001-01-01T00:00:00Z\"}");
    }

    @Test
    void memoryItemEmbeddingZeroMatchesGo() throws Exception {
        assertThat(write(new MemoryItemEmbedding())).isEqualTo(
                "{\"itemId\":\"\",\"tenantId\":0,\"subjectId\":\"\",\"modelId\":\"\",\"dims\":0,"
                        + "\"createdAt\":\"0001-01-01T00:00:00Z\","
                        + "\"updatedAt\":\"0001-01-01T00:00:00Z\"}");
    }

    /** 三个 {@code json:"-"} 的含义不同：{@code vector} 仍落库、两个 source 连库都不落。 */
    @Test
    void memoryItemEmbeddingHidesSnapshotsAndVectorButOnlyFromJson() throws Exception {
        MemoryItemEmbedding e = new MemoryItemEmbedding();
        e.setSourceContent("c");
        e.setSourceTopic("t");
        e.setVector(new byte[]{1, 2, 3});

        String json = write(e);
        assertThat(json).doesNotContain("sourceContent").doesNotContain("sourceTopic")
                .doesNotContain("vector");
        // 但 Java 侧的取值口仍在——落库与业务判断都要用
        assertThat(e.getSourceContent()).isEqualTo("c");
        assertThat(e.getVector()).containsExactly(1, 2, 3);
    }

    // ── 抽取进度 ───────────────────────────────────────────────────────────

    @Test
    void memoryExtractionSessionZeroMatchesGo() throws Exception {
        assertThat(write(new MemoryExtractionSession()))
                .isEqualTo("{\"revision\":0,\"cursor\":{\"at\":\"0001-01-01T00:00:00Z\",\"id\":\"\"}}");
    }

    @Test
    void memoryExtractionSessionFullMatchesGo() throws Exception {
        MemoryExtractionSession s = new MemoryExtractionSession();
        s.setTenantId(7L);
        s.setSubjectId("s");
        s.setSessionId("sess");
        s.setRevision(3);
        s.setCursor(new MemoryMessageCursor(ten(), "m1"));
        s.setPending(true);
        s.setFailureCount(2);
        s.setFailureCode("bad_output");

        assertThat(write(s)).isEqualTo(
                "{\"revision\":3,\"cursor\":{\"at\":\"2026-09-18T10:00:00+08:00\",\"id\":\"m1\"}}");
    }

    /**
     * {@code MemoryExtractionState} 的零值——**两个键都在**：{@code leaseId} 是空串、
     * {@code leaseUntil} 是 year-1 字面量（旧 Go 的 {@code omitempty} 已随 M2 退役）。
     *
     * <p>这正是落库 jsonb 的字节（Go 的 {@code Value()} 就是 {@code json.Marshal(s)}），
     * 所以断言同时钉住了"库里那一列长什么样"。</p>
     */
    @Test
    void memoryExtractionStateZeroWritesBothKeys() throws Exception {
        assertThat(com.ragagent.support.ContractJson.deep(JSONB.writeValueAsString(new MemoryExtractionState())))
                .isEqualTo("{\"leaseId\":\"\",\"leaseUntil\":\"0001-01-01T00:00:00Z\"}");
    }

    @Test
    void memoryExtractionStateFullWritesBothKeys() throws Exception {
        MemoryExtractionState s = new MemoryExtractionState();
        s.setLeaseId("L");
        s.setLeaseUntil(ten());

        assertThat(com.ragagent.support.ContractJson.deep(JSONB.writeValueAsString(s)))
                .isEqualTo("{\"leaseId\":\"L\",\"leaseUntil\":\"2026-09-18T02:00:00Z\"}");
        // 读回来（走的是同一个裸映射器，没有 JavaTimeModule）必须自足
        MemoryExtractionState back = JSONB.readValue(
                "{\"leaseId\":\"L\",\"leaseUntil\":\"2026-09-18T02:00:00Z\"}",
                MemoryExtractionState.class);
        assertThat(back.getLeaseId()).isEqualTo("L");
        assertThat(back.getLeaseUntil().toInstant()).isEqualTo(ten().toInstant());
    }

    /** 历史行里可能有未知键（Go 的 {@code json.Unmarshal} 默认忽略）——读路径必须宽容。 */
    @Test
    void memoryExtractionStateToleratesUnknownKeysOnRead() throws Exception {
        MemoryExtractionState back = JSONB.readValue(
                "{\"leaseId\":\"L\",\"unknownKey\":1}", MemoryExtractionState.class);
        assertThat(back.getLeaseId()).isEqualTo("L");
    }

    /** 空列/缺失的 {@code leaseUntil} 读回**零值时间**而不是 null（往返才幂等）。 */
    @Test
    void memoryExtractionStateReadsMissingLeaseUntilAsGoZero() throws Exception {
        MemoryExtractionState back = JSONB.readValue("{}", MemoryExtractionState.class);
        assertThat(back.getLeaseId()).isEmpty();
        assertThat(back.getLeaseUntil().toInstant())
                .isEqualTo(com.ragagent.common.web.GoTimeSerializer.GO_ZERO_TIME);
    }

    // ── 键序 + 键数（§9：带 is 前缀字段/派生访问器的响应体必须额外钉一条） ──

    /**
     * Go struct 按**声明序**输出；map 才按字母序（§9）。这里逐类型核对键序与键数，
     * 抓的是两类"往返测试抓不到"的问题：
     * <ul>
     *   <li>派生访问器多吐了一个键（例如把 {@code hasAlias} 起名成 {@code isAlias}）；</li>
     *   <li>字段漏进/多出响应面（换锚后键名＝Java 字段名，写错就当场显形）——★ 正则必须
     *       大小写感知，否则驼峰键会被静默过滤掉（§9 明确记过这个教训）。</li>
     * </ul>
     *
     * <p>§14.9k M2 换锚后全部类型都用驼峰键名（＝Java 字段名），与声明序一致。</p>
     */
    @Test
    void entityKeyOrderAndCountMatchGoDeclarationOrder() throws Exception {
        assertKeyOrder(new MemorySubject(), "id", "tenantId", "subjectId", "enabled", "blockText",
                "blockUpdatedAt", "itemCount", "lastExtractedAt", "extractCursor",
                "pendingSessions", "extractScheduledAt", "consolidatedAt", "forcedConsolidatedAt",
                "createdAt", "updatedAt");

        assertKeyOrder(new MemoryItem(), "id", "tenantId", "subjectId", "kind", "content", "topic",
                "normalizedKey", "importance", "origin", "status", "sourceSessionId",
                "sourceMessageId", "validFrom", "invalidAt", "expiresAt", "replacesId",
                "supersededBy", "lastUsedAt", "useCount", "createdAt", "updatedAt");

        assertKeyOrder(new MemoryTopicStat(), "id", "tenantId", "subjectId", "normalizedKey",
                "topic", "aliases", "hits", "lastSeenAt", "promotedAt", "createdAt", "updatedAt");

        assertKeyOrder(new MemoryDocAffinity(), "id", "tenantId", "subjectId", "knowledgeId",
                "knowledgeBaseId", "title", "hits", "lastUsedAt", "createdAt", "updatedAt");

        assertKeyOrder(new MemoryTombstone(), "id", "tenantId", "subjectId", "topic", "fingerprint",
                "sourceMessageId", "createdAt");

        assertKeyOrder(new MemoryItemEmbedding(), "itemId", "tenantId", "subjectId", "modelId",
                "dims", "createdAt", "updatedAt");

        // ⚠️ MemoryExtractionSession **不在这里**：它的 cursor 是嵌套对象，
        // 而下面那个键名正则会把嵌套的 at/id 一并抓出来（这是刻意写的驼峰感知正则的反面）。
        // 它的键序由上面那条逐字节的断言钉住（外层的 revision → cursor 已经定死）。
    }

    /** 驼峰感知的键名正则——§9 明确要求，`"([a-z_]+)"` 会把驼峰键静默过滤掉。 */
    private static final Pattern KEY = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\":");

    private static void assertKeyOrder(Object value, String... expected) throws Exception {
        String json = MAPPER.writeValueAsString(value);
        List<String> keys = new ArrayList<>();
        Matcher m = KEY.matcher(json);
        while (m.find()) {
            keys.add(m.group(1));
        }
        assertThat(keys)
                .as("%s 的键序/键数（Go struct 声明序）\n实际 JSON: %s",
                        value.getClass().getSimpleName(), json)
                .containsExactly(expected);
    }

    // ── 往返幂等（严格映射器：多出的键直接炸） ─────────────────────────────

    @Test
    void entitiesRoundTripUnderStrictMapper() {
        JsonRoundTrip.assertRoundTrips(new MemorySubject(), MemorySubject.class, "MemorySubject");
        JsonRoundTrip.assertRoundTrips(new MemoryItem(), MemoryItem.class, "MemoryItem");
        JsonRoundTrip.assertRoundTrips(new MemoryTopicStat(), MemoryTopicStat.class, "MemoryTopicStat");
        JsonRoundTrip.assertRoundTrips(new MemoryDocAffinity(), MemoryDocAffinity.class,
                "MemoryDocAffinity");
        JsonRoundTrip.assertRoundTrips(new MemoryTombstone(), MemoryTombstone.class, "MemoryTombstone");
        JsonRoundTrip.assertRoundTrips(new MemoryItemEmbedding(), MemoryItemEmbedding.class,
                "MemoryItemEmbedding");
        JsonRoundTrip.assertRoundTrips(new MemoryExtractionSession(), MemoryExtractionSession.class,
                "MemoryExtractionSession");

        MemorySubject full = new MemorySubject();
        full.setId("s1");
        full.setTenantId(7L);
        full.setSubjectId("u");
        full.setEnabled(true);
        full.setBlockText("b");
        full.setItemCount(2);
        full.setLastExtractedAt(ten());
        full.setConsolidatedAt(ten());
        full.setForcedConsolidatedAt(ten());
        full.setExtractScheduledAt(ten());
        full.setCreatedAt(ten());
        full.setUpdatedAt(ten());
        full.setPendingSessions(new ArrayList<>(List.of("a", "b")));
        JsonRoundTrip.assertRoundTrips(full, MemorySubject.class, "MemorySubject(full)");

        MemoryItem item = new MemoryItem();
        item.setId("i1");
        item.setTenantId(7L);
        item.setSubjectId("u");
        item.setKind("fact");
        item.setContent("c");
        item.setTopic("t");
        item.setNormalizedKey("nk");
        item.setImportance(3);
        item.setOrigin("manual");
        item.setStatus("pending");
        item.setSourceSessionId("ss");
        item.setSourceMessageId("sm");
        item.setValidFrom(ten());
        item.setInvalidAt(ten());
        item.setExpiresAt(ten());
        item.setReplacesId("r");
        item.setSupersededBy("sb");
        item.setLastUsedAt(ten());
        item.setUseCount(4);
        item.setInferred(true);
        item.setCreatedAt(ten());
        item.setUpdatedAt(ten());
        JsonRoundTrip.assertRoundTrips(item, MemoryItem.class, "MemoryItem(full)");

        MemoryTopicStat stat = new MemoryTopicStat();
        stat.setId("t1");
        stat.setTenantId(7L);
        stat.setSubjectId("u");
        stat.setNormalizedKey("k");
        stat.setTopic("topic");
        stat.setAliases(new ArrayList<>(List.of("a")));
        stat.setHits(2);
        stat.setLastSeenAt(ten());
        stat.setPromotedAt(ten());
        stat.setCreatedAt(ten());
        stat.setUpdatedAt(ten());
        JsonRoundTrip.assertRoundTrips(stat, MemoryTopicStat.class, "MemoryTopicStat(full)");

        MemoryExtractionSession session = new MemoryExtractionSession();
        session.setTenantId(7L);
        session.setSubjectId("u");
        session.setSessionId("sess");
        session.setRevision(3);
        session.setCursor(new MemoryMessageCursor(ten(), "m1"));
        session.setPending(true);
        session.setFailureCount(1);
        session.setFailureCode("bad");
        session.setFailedFromAt(ten());
        session.setFailedFromId("f1");
        session.setFailedToAt(ten());
        session.setFailedToId("f2");
        session.setFailedAt(ten());
        session.setUpdatedAt(ten());
        JsonRoundTrip.assertRoundTrips(session, MemoryExtractionSession.class,
                "MemoryExtractionSession(full)");
    }

    /** {@code MemoryExtractionState} 是 jsonb 值：用**裸**映射器往返（就是落库/回读那两个方向）。 */
    @Test
    void extractionStateRoundTripsUnderNakedMapper() throws Exception {
        MemoryExtractionState state = new MemoryExtractionState();
        state.setLeaseId("L");
        state.setLeaseUntil(ten());
        String first = JSONB.writeValueAsString(state);
        MemoryExtractionState back = JSONB.readValue(first, MemoryExtractionState.class);
        assertThat(com.ragagent.support.ContractJson.deep(JSONB.writeValueAsString(back)))
                .isEqualTo(com.ragagent.support.ContractJson.deep(first));

        String zero = JSONB.writeValueAsString(new MemoryExtractionState());
        assertThat(com.ragagent.support.ContractJson.deep(JSONB.writeValueAsString(JSONB.readValue(zero, MemoryExtractionState.class))))
                .isEqualTo(zero);
    }

    /** {@code MemoryMessageCursor.after} 的判定（对照 Go {@code After}）。 */
    @Test
    void messageCursorOrderingMatchesGo() {
        MemoryMessageCursor earlier = new MemoryMessageCursor(ten().minusSeconds(1), "z");
        MemoryMessageCursor later = new MemoryMessageCursor(ten(), "a");
        MemoryMessageCursor sameTimeSmallerId = new MemoryMessageCursor(ten(), "a");
        MemoryMessageCursor sameTimeBiggerId = new MemoryMessageCursor(ten(), "b");

        assertThat(later.after(earlier)).isTrue();
        assertThat(earlier.after(later)).isFalse();
        // 同一时刻靠主键破平局
        assertThat(sameTimeBiggerId.after(sameTimeSmallerId)).isTrue();
        // 与自己比：既不 After，也相等
        assertThat(sameTimeSmallerId.after(sameTimeSmallerId)).isFalse();
    }
}
