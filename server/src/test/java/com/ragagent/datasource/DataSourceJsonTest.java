package com.ragagent.datasource;

import com.ragagent.common.web.JsonMappers;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.JsonRoundTrip;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceSyncPayload;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SubtreeChildIds;
import com.ragagent.datasource.domain.SyncCursor;
import com.ragagent.datasource.domain.SyncItemError;
import com.ragagent.datasource.domain.SyncLog;
import com.ragagent.datasource.domain.SyncResult;
import com.ragagent.datasource.domain.TaskInitiator;
import org.junit.jupiter.api.Test;

/**
 * datasource 领域类型的**逐字节 JSON 契约**测试（波 0 第 2 步）。
 *
 * <h2>期望值的来源（本项目的验收标准）</h2>
 * <p>全部是 <b>Go 实录</b>：把 {@code internal/types/datasource.go}、
 * {@code context_helpers.go}（{@code TaskInitiator}）、{@code tracing.go}
 * （{@code TracingContext}）与 {@code json.go}（{@code types.JSON}）里的类型
 * <b>连 json tag 一起原样抄进</b>一个独立 Go 程序，喂同样的输入跑
 * {@code json.Marshal}，输出抄进下面的断言。</p>
 * <p>时间用的是 {@code time.FixedZone("CST", 8*3600)}，因为 JVM 默认时区是
 * {@code Asia/Shanghai}、{@code GoTimeSerializer} 会把时间归一化到那里再输出——
 * 用 UTC 录的话两边字面量会差一个偏移、断言无意义（沿用 memory 模块的做法）。</p>
 *
 * <h2>这份语料刻意盯住的六个坑</h2>
 * <ol>
 *   <li><b>{@code DataSource} 一个 omitempty 都没有</b>——连 {@code error_message}
 *       空串、三个 JSON 列 null、{@code total_items_synced} 与 {@code latest_sync_log}
 *       （两个 {@code gorm:"-"}）都恒输出。最容易"顺手加个 omitempty"。</li>
 *   <li><b>{@code SyncItemError} 四个键全 omitempty</b> → 零值对象是 {@code {}}。</li>
 *   <li><b>{@code FetchedItem.content} 是 {@code []byte}</b> → JSON 里是 base64
 *       字符串，且无 omitempty（nil → {@code null}，空数组 → {@code ""}）。</li>
 *   <li><b>{@code FetchedItem.metadata} 无 omitempty</b>（nil → {@code null}），
 *       而 {@code Resource.metadata} **有** omitempty（nil → 键消失）——
 *       两个相邻类型两种处置。</li>
 *   <li><b>{@code DataSourceSyncPayload.initiator} 的 omitempty 是无效的</b>
 *       （Go 的 omitempty 对 struct 一律不生效）→ 空发起人输出 {@code "initiator":{}}。</li>
 *   <li><b>map 里的数字按 Go 的浮点编码器</b>（{@code 1.0 → 1}、{@code 1e21 → 1e+21}）
 *       ——{@link com.ragagent.datasource.domain.DataSourceMapSerializer} 的存在理由。</li>
 * </ol>
 *
 * <h2>为什么往返断言写在这里而不是 {@code JsonContractRoundTripTest}</h2>
 * <p>本轮任务书只授权改 {@code TestSchema}，其余共享文件（含
 * {@code JsonContractRoundTripTest}）不动。memory 模块把实体往返放在自己的
 * {@code MemoryEntityJsonTest} 里，此处沿用同一处置。</p>
 */
class DataSourceJsonTest {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    /** jsonb 读路径用的**裸**映射器——必须容忍未知属性（约定 §9）。 */
    private static final ObjectMapper JSONB = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static String write(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    /** 与 Go 的 {@code time.FixedZone("CST", 8*3600)} 同墙钟：JVM 默认时区的 10:00。 */
    private static OffsetDateTime ten() {
        return ZonedDateTime.of(2026, 9, 18, 10, 0, 0, 0, ZoneId.systemDefault()).toOffsetDateTime();
    }

    /** 同上，但换一天（对照 Go 录的 {@code 2026-09-17T09:00:00+08:00}）。 */
    private static OffsetDateTime earlier() {
        return ZonedDateTime.of(2026, 9, 17, 9, 0, 0, 0, ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    // ── DataSource ─────────────────────────────────────────────────────────

    @Test
    void dataSourceZeroMatchesGo() throws Exception {
        assertThat(write(new DataSource())).isEqualTo(
                "{\"id\":\"\",\"tenantId\":0,\"knowledgeBaseId\":\"\",\"name\":\"\",\"type\":\"\","
                        + "\"config\":null,\"syncSchedule\":\"\",\"syncMode\":\"\",\"status\":\"\","
                        + "\"conflictStrategy\":\"\",\"syncDeletions\":false,\"lastSyncAt\":null,"
                        + "\"lastSyncCursor\":null,\"lastSyncResult\":null,\"errorMessage\":\"\","
                        + "\"syncLogRetentionDays\":0,"
                        + "\"createdAt\":\"0001-01-01T00:00:00Z\","
                        + "\"updatedAt\":\"0001-01-01T00:00:00Z\",\"deletedAt\":null,"
                        + "\"totalItemsSynced\":0,\"latestSyncLog\":null}");
    }

    @Test
    void dataSourceFullMatchesGo() throws Exception {
        DataSource ds = new DataSource();
        ds.setId("d1");
        ds.setTenantId(7L);
        ds.setKnowledgeBaseId("kb1");
        ds.setName("n");
        ds.setType("feishu");
        ds.setConfig(json("{\"type\":\"feishu\"}"));
        ds.setSyncSchedule("0 */6 * * *");
        ds.setSyncMode("full");
        ds.setStatus("paused");
        ds.setConflictStrategy("skip");
        ds.setSyncDeletions(true);
        ds.setLastSyncAt(ten());
        ds.setLastSyncCursor(json("{\"lastSchemaHash\":\"h\"}"));
        ds.setLastSyncResult(json("{\"total\":3}"));
        ds.setErrorMessage("boom");
        ds.setSyncLogRetentionDays(14);
        ds.setCreatedAt(ten());
        ds.setUpdatedAt(ten());
        ds.setTotalItemsSynced(42L);
        SyncLog latest = new SyncLog();
        latest.setId("l1");
        ds.setLatestSyncLog(latest);

        assertThat(write(ds)).isEqualTo(
                "{\"id\":\"d1\",\"tenantId\":7,\"knowledgeBaseId\":\"kb1\",\"name\":\"n\","
                        + "\"type\":\"feishu\",\"config\":{\"type\":\"feishu\"},"
                        + "\"syncSchedule\":\"0 */6 * * *\",\"syncMode\":\"full\",\"status\":\"paused\","
                        + "\"conflictStrategy\":\"skip\",\"syncDeletions\":true,"
                        + "\"lastSyncAt\":\"2026-09-18T10:00:00+08:00\","
                        + "\"lastSyncCursor\":{\"lastSchemaHash\":\"h\"},"
                        + "\"lastSyncResult\":{\"total\":3},\"errorMessage\":\"boom\","
                        + "\"syncLogRetentionDays\":14,"
                        + "\"createdAt\":\"2026-09-18T10:00:00+08:00\","
                        + "\"updatedAt\":\"2026-09-18T10:00:00+08:00\",\"deletedAt\":null,"
                        + "\"totalItemsSynced\":42,\"latestSyncLog\":"
                        + "{\"id\":\"l1\",\"dataSourceId\":\"\",\"tenantId\":0,\"status\":\"\","
                        + "\"startedAt\":\"0001-01-01T00:00:00Z\",\"finishedAt\":null,\"itemsTotal\":0,"
                        + "\"itemsCreated\":0,\"itemsUpdated\":0,\"itemsDeleted\":0,\"itemsSkipped\":0,"
                        + "\"itemsFailed\":0,\"errorMessage\":\"\",\"result\":null,"
                        + "\"createdAt\":\"0001-01-01T00:00:00Z\","
                        + "\"updatedAt\":\"0001-01-01T00:00:00Z\"}}");
    }

    /** 三个 JSON 列都是 Go 的 {@code types.JSON}：**空就输出 {@code null}**，不省略键。 */
    @Test
    void dataSourceKeepsNullJsonColumns() throws Exception {
        String out = write(new DataSource());
        assertThat(out).contains("\"config\":null")
                .contains("\"lastSyncCursor\":null")
                .contains("\"lastSyncResult\":null");
    }

    // ── SyncLog ────────────────────────────────────────────────────────────

    @Test
    void syncLogZeroMatchesGo() throws Exception {
        assertThat(write(new SyncLog())).isEqualTo(
                "{\"id\":\"\",\"dataSourceId\":\"\",\"tenantId\":0,\"status\":\"\","
                        + "\"startedAt\":\"0001-01-01T00:00:00Z\",\"finishedAt\":null,"
                        + "\"itemsTotal\":0,\"itemsCreated\":0,\"itemsUpdated\":0,\"itemsDeleted\":0,"
                        + "\"itemsSkipped\":0,\"itemsFailed\":0,\"errorMessage\":\"\",\"result\":null,"
                        + "\"createdAt\":\"0001-01-01T00:00:00Z\","
                        + "\"updatedAt\":\"0001-01-01T00:00:00Z\"}");
    }

    @Test
    void syncLogFullMatchesGo() throws Exception {
        SyncLog log = new SyncLog();
        log.setId("l1");
        log.setDataSourceId("d1");
        log.setTenantId(7L);
        log.setStatus("success");
        log.setStartedAt(ten());
        log.setFinishedAt(ten().plusSeconds(5));
        log.setItemsTotal(1);
        log.setItemsCreated(2);
        log.setItemsUpdated(3);
        log.setItemsDeleted(4);
        log.setItemsSkipped(5);
        log.setItemsFailed(6);
        log.setErrorMessage("e");
        log.setResult(json("{\"total\":1}"));
        log.setCreatedAt(ten());
        log.setUpdatedAt(ten());

        assertThat(write(log)).isEqualTo(
                "{\"id\":\"l1\",\"dataSourceId\":\"d1\",\"tenantId\":7,\"status\":\"success\","
                        + "\"startedAt\":\"2026-09-18T10:00:00+08:00\","
                        + "\"finishedAt\":\"2026-09-18T10:00:05+08:00\","
                        + "\"itemsTotal\":1,\"itemsCreated\":2,\"itemsUpdated\":3,"
                        + "\"itemsDeleted\":4,\"itemsSkipped\":5,\"itemsFailed\":6,"
                        + "\"errorMessage\":\"e\",\"result\":{\"total\":1},"
                        + "\"createdAt\":\"2026-09-18T10:00:00+08:00\","
                        + "\"updatedAt\":\"2026-09-18T10:00:00+08:00\"}");
    }

    // ── DataSourceConfig ───────────────────────────────────────────────────

    @Test
    void dataSourceConfigZeroMatchesGo() throws Exception {
        assertThat(write(new DataSourceConfig())).isEqualTo(
                "{\"type\":\"\",\"credentials\":null,\"resourceIds\":null,\"settings\":null}");
    }

    @Test
    void dataSourceConfigFullMatchesGo() throws Exception {
        DataSourceConfig c = new DataSourceConfig();
        c.setType("feishu");
        Map<String, Object> creds = new LinkedHashMap<>();
        creds.put("app_id", "x");
        creds.put("n", 1.0d);
        creds.put("b", true);
        c.setCredentials(creds);
        c.setResourceIds(new ArrayList<>(List.of("r1", "r2")));
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("folder_token", "ft");
        c.setSettings(settings);

        assertThat(write(c)).isEqualTo(
                "{\"type\":\"feishu\",\"credentials\":{\"app_id\":\"x\",\"b\":true,\"n\":1},"
                        + "\"resourceIds\":[\"r1\",\"r2\"],\"settings\":{\"folder_token\":\"ft\"}}");
    }

    /**
     * {@code multimodal_enabled} 是 {@code json:"-"}，而且它**连 jsonb 都不落**
     * ——JSON 与落库是同一个 {@code json.Marshal}。
     */
    @Test
    void dataSourceConfigNeverExposesMultimodalEnabled() throws Exception {
        DataSourceConfig c = new DataSourceConfig();
        c.setMultimodalEnabled(true);
        assertThat(write(c)).doesNotContain("multimodal").doesNotContain("Multimodal");
        // 取值口仍在（运行期要用）
        assertThat(c.isMultimodalEnabled()).isTrue();
    }

    /** 三个 {@code Has*} 方法不能变成 JSON 属性（§7.5 第 2 条那一类泄漏）。 */
    @Test
    void dataSourceConfigHasMethodsAreNotProperties() throws Exception {
        DataSourceConfig c = new DataSourceConfig();
        c.setCredentials(new LinkedHashMap<>(Map.of("app_id", "x")));
        String out = write(c);
        assertThat(c.hasCredentials()).isTrue();
        assertThat(c.hasConfiguredCredentials("feishu")).isTrue();
        assertThat(out).doesNotContain("hasCredentials").doesNotContain("has_configured_credentials");
    }

    // ── Resource / FetchedItem ─────────────────────────────────────────────

    @Test
    void resourceZeroMatchesGo() throws Exception {
        // §1.6：原先三个 omitempty 键（parentId / hasChildren / metadata）现在**恒输出**
        assertThat(write(new Resource())).isEqualTo(
                "{\"externalId\":\"\",\"name\":\"\",\"type\":\"\",\"description\":\"\",\"url\":\"\","
                        + "\"modifiedAt\":\"0001-01-01T00:00:00Z\",\"parentId\":\"\","
                        + "\"hasChildren\":false,\"metadata\":null}");
    }

    @Test
    void resourceFullMatchesGo() throws Exception {
        Resource r = new Resource();
        r.setExternalId("e1");
        r.setName("n");
        r.setType("document");
        r.setDescription("d");
        r.setUrl("u");
        r.setModifiedAt(ten());
        r.setParentId("p1");
        r.setHasChildren(true);
        r.setMetadata(new LinkedHashMap<>(Map.of("k", "v")));

        assertThat(write(r)).isEqualTo(
                "{\"externalId\":\"e1\",\"name\":\"n\",\"type\":\"document\",\"description\":\"d\","
                        + "\"url\":\"u\",\"modifiedAt\":\"2026-09-18T10:00:00+08:00\","
                        + "\"parentId\":\"p1\",\"hasChildren\":true,\"metadata\":{\"k\":\"v\"}}");
    }

    @Test
    void fetchedItemZeroMatchesGo() throws Exception {
        assertThat(write(new FetchedItem())).isEqualTo(
                "{\"externalId\":\"\",\"title\":\"\",\"content\":null,\"contentType\":\"\","
                        + "\"fileName\":\"\",\"url\":\"\",\"updatedAt\":\"0001-01-01T00:00:00Z\","
                        + "\"createdAt\":\"0001-01-01T00:00:00Z\",\"metadata\":null,"
                        + "\"deleted\":false,\"sourceResourceId\":\"\","
                        + "\"replacesSubtree\":false,\"subtreeKeep\":null}");
    }

    @Test
    void fetchedItemFullMatchesGo() throws Exception {
        FetchedItem f = new FetchedItem();
        f.setExternalId("e1");
        f.setTitle("t");
        f.setContent("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        f.setContentType("text/markdown");
        f.setFileName("f.md");
        f.setUrl("u");
        f.setUpdatedAt(ten());
        f.setCreatedAt(earlier());
        f.setMetadata(new LinkedHashMap<>(Map.of("a", "b")));
        f.setDeleted(true);
        f.setSourceResourceId("s1");
        f.setReplacesSubtree(true);
        f.setSubtreeKeep(new ArrayList<>(List.of("c1", "c2")));

        assertThat(write(f)).isEqualTo(
                "{\"externalId\":\"e1\",\"title\":\"t\",\"content\":\"aGVsbG8=\","
                        + "\"contentType\":\"text/markdown\",\"fileName\":\"f.md\",\"url\":\"u\","
                        + "\"updatedAt\":\"2026-09-18T10:00:00+08:00\","
                        + "\"createdAt\":\"2026-09-17T09:00:00+08:00\",\"metadata\":{\"a\":\"b\"},"
                        + "\"deleted\":true,\"sourceResourceId\":\"s1\","
                        + "\"replacesSubtree\":true,\"subtreeKeep\":[\"c1\",\"c2\"]}");
    }

    /**
     * {@code []byte} 的 base64 字母表必须是 **标准表**（含 {@code +} 与 {@code /}
     * 且带 {@code =} 填充）。Go 用 {@code base64.StdEncoding}；
     * Jackson 的默认变体 {@code MIME_NO_LINEFEEDS} 与它同字母表、同填充、同样不折行。
     */
    @Test
    void fetchedItemContentUsesGoBase64Alphabet() throws Exception {
        FetchedItem f = new FetchedItem();
        f.setContent(new byte[]{(byte) 0xfb, (byte) 0xff, 0x3e, 0x41});
        // Go 实录：json.Marshal([]byte{0xfb,0xff,0x3e,0x41}) → "+/8+QQ=="
        assertThat(write(f)).contains("\"content\":\"+/8+QQ==\"");

        // 空但非 nil 的切片在 Go 里是 ""（不是 null）
        f.setContent(new byte[0]);
        assertThat(write(f)).contains("\"content\":\"\"");
    }

    /**
     * §14.9q D2 后字段名即键名：只输出 {@code deleted}，**没有** {@code is_deleted} 这个并生属性。
     *
     * <p>（修正一条从未生效的断言：原文第二条子句写的是 {@code doesNotContain("\"deleted\":")}，
     * 与第一条自相矛盾，等于没断言。）</p>
     */
    @Test
    void fetchedItemDoesNotLeakIsDeletedProperty() throws Exception {
        String out = write(new FetchedItem());
        assertThat(out).contains("\"deleted\":false").doesNotContain("is_deleted");
    }

    // ── SyncCursor / SyncResult / SyncItemError ────────────────────────────

    @Test
    void syncCursorZeroMatchesGo() throws Exception {
        assertThat(write(new SyncCursor())).isEqualTo(
                "{\"lastSyncTime\":\"0001-01-01T00:00:00Z\",\"connectorCursor\":null,"
                        + "\"lastSchemaHash\":\"\"}");
    }

    @Test
    void syncCursorFullMatchesGo() throws Exception {
        SyncCursor c = new SyncCursor();
        c.setLastSyncTime(ten());
        Map<String, Object> cursor = new LinkedHashMap<>();
        cursor.put("page_token", "p");
        cursor.put("n", 2.0d);
        c.setConnectorCursor(cursor);
        c.setLastSchemaHash("h");

        assertThat(write(c)).isEqualTo(
                "{\"lastSyncTime\":\"2026-09-18T10:00:00+08:00\","
                        + "\"connectorCursor\":{\"n\":2,\"page_token\":\"p\"},"
                        + "\"lastSchemaHash\":\"h\"}");
    }

    @Test
    void syncResultZeroMatchesGo() throws Exception {
        assertThat(write(new SyncResult())).isEqualTo(
                "{\"total\":0,\"created\":0,\"updated\":0,\"deleted\":0,\"skipped\":0,\"failed\":0,"
                        + "\"deletionFailed\":0,\"errors\":null,\"nextCursor\":null}");
    }

    @Test
    void syncResultFullMatchesGo() throws Exception {
        SyncResult r = new SyncResult();
        r.setTotal(1);
        r.setCreated(2);
        r.setUpdated(3);
        r.setDeleted(4);
        r.setSkipped(5);
        r.setFailed(6);
        r.setDeletionFailed(7);

        SyncItemError e = new SyncItemError();
        e.setTitle("t");
        e.setCode("c");
        e.setParams(new LinkedHashMap<>(Map.of("code", "1663")));
        e.setMessage("m");
        r.setErrors(new ArrayList<>(List.of(e)));

        SyncCursor next = new SyncCursor();
        next.setLastSchemaHash("h");
        r.setNextCursor(next);

        assertThat(write(r)).isEqualTo(
                "{\"total\":1,\"created\":2,\"updated\":3,\"deleted\":4,\"skipped\":5,\"failed\":6,"
                        + "\"deletionFailed\":7,"
                        + "\"errors\":[{\"title\":\"t\",\"code\":\"c\","
                        + "\"params\":{\"code\":\"1663\"},\"message\":\"m\"}],"
                        + "\"nextCursor\":{\"lastSyncTime\":\"0001-01-01T00:00:00Z\","
                        + "\"connectorCursor\":null,\"lastSchemaHash\":\"h\"}}");
    }

    /** §1.6：空集合、零值与 null 一律**照写**（旧 Go 的三种 omitempty 都已退役）。 */
    @Test
    void syncResultKeepsEmptyCollectionsAndZeroDeletionFailed() throws Exception {
        SyncResult r = new SyncResult();
        r.setErrors(new ArrayList<>());
        r.setNextCursor(new SyncCursor());
        assertThat(write(r))
                .as("空 list 照写，非 null 的 nextCursor 也照写")
                .contains("\"errors\":[]")
                .contains("\"nextCursor\":{\"lastSyncTime\":\"0001-01-01T00:00:00Z\"");

        r.setErrors(null);
        r.setNextCursor(null);
        String out = write(r);
        assertThat(out).contains("\"errors\":null").contains("\"nextCursor\":null")
                .contains("\"deletionFailed\":0");
    }

    /** §1.6：四键恒输出（旧 Go 的 omitempty 会让零值对象成为 {@code {}}）。 */
    @Test
    void syncItemErrorZeroMatchesGoAsFullObject() throws Exception {
        assertThat(write(new SyncItemError())).isEqualTo(
                "{\"title\":\"\",\"code\":\"\",\"params\":null,\"message\":\"\"}");
    }

    @Test
    void syncItemErrorFullMatchesGo() throws Exception {
        SyncItemError e = new SyncItemError();
        e.setTitle("t");
        e.setCode("c");
        e.setParams(new LinkedHashMap<>(Map.of("code", "1663")));
        e.setMessage("m");
        assertThat(write(e)).isEqualTo(
                "{\"title\":\"t\",\"code\":\"c\",\"params\":{\"code\":\"1663\"},\"message\":\"m\"}");

        SyncItemError onlyMessage = new SyncItemError();
        onlyMessage.setMessage("m");
        assertThat(write(onlyMessage)).isEqualTo(
                "{\"title\":\"\",\"code\":\"\",\"params\":null,\"message\":\"m\"}");
    }

    /** 历史同步日志里每个 error 是个**裸 JSON 字符串**——必须解成 Message。 */
    @Test
    void syncItemErrorReadsLegacyBareString() throws Exception {
        SyncItemError legacy = JSONB.readValue("\"old failure\"", SyncItemError.class);
        assertThat(legacy.getMessage()).isEqualTo("old failure");
        assertThat(write(legacy)).isEqualTo(
                "{\"title\":\"\",\"code\":\"\",\"params\":null,\"message\":\"old failure\"}");

        SyncItemError obj = JSONB.readValue("{\"title\":\"t\",\"code\":\"c\"}", SyncItemError.class);
        assertThat(obj.getTitle()).isEqualTo("t");
        assertThat(obj.getMessage()).isEmpty();
    }

    /** 未知键必须被忽略（Go 的 {@code json.Unmarshal} 默认如此）。 */
    @Test
    void syncItemErrorToleratesUnknownKeys() throws Exception {
        SyncItemError e = JSONB.readValue(
                "{\"title\":\"t\",\"future_key\":1}", SyncItemError.class);
        assertThat(e.getTitle()).isEqualTo("t");
    }

    /** {@code Display()} 的四条分支（对照 Go 的 switch）。 */
    @Test
    void syncItemErrorDisplayMatchesGo() {
        SyncItemError both = new SyncItemError();
        both.setTitle("t");
        both.setMessage("m");
        assertThat(both.display()).isEqualTo("t: m");

        SyncItemError messageOnly = new SyncItemError();
        messageOnly.setMessage("m");
        assertThat(messageOnly.display()).isEqualTo("m");

        SyncItemError titleOnly = new SyncItemError();
        titleOnly.setTitle("t");
        assertThat(titleOnly.display()).isEqualTo("t");

        assertThat(new SyncItemError().display()).isEmpty();
    }

    // ── TaskInitiator / DataSourceSyncPayload ──────────────────────────────

    @Test
    void taskInitiatorMatchesGo() throws Exception {
        assertThat(write(TaskInitiator.empty())).isEqualTo("{}");
        assertThat(write(new TaskInitiator("user-1", "admin")))
                .isEqualTo("{\"user_id\":\"user-1\",\"role\":\"admin\"}");
        // ⚠️ isEmpty() 会变成 JSON 属性 "empty"——所以那个方法叫 blank()
        assertThat(write(TaskInitiator.empty())).doesNotContain("empty");
    }

    @Test
    void dataSourceSyncPayloadZeroMatchesGo() throws Exception {
        // initiator 的 omitempty 对 struct 无效 → 空发起人也输出 "initiator":{}
        assertThat(write(new DataSourceSyncPayload(
                null, null, "", 0L, "", false, 0))).isEqualTo(
                "{\"initiator\":{},\"data_source_id\":\"\",\"tenant_id\":0,"
                        + "\"sync_log_id\":\"\",\"force_full\":false}");
    }

    @Test
    void dataSourceSyncPayloadFullMatchesGo() throws Exception {
        DataSourceSyncPayload p = new DataSourceSyncPayload(
                new TaskInitiator("user-1", "admin"), "manual", "d1", 7L, "l1", true, 10);
        assertThat(write(p)).isEqualTo(
                "{\"initiator\":{\"user_id\":\"user-1\",\"role\":\"admin\"},"
                        + "\"trigger\":\"manual\",\"data_source_id\":\"d1\",\"tenant_id\":7,"
                        + "\"sync_log_id\":\"l1\",\"force_full\":true,\"max_items\":10}");
    }

    @Test
    void dataSourceSyncPayloadRoundTripsThroughJson() {
        DataSourceSyncPayload p = new DataSourceSyncPayload(
                new TaskInitiator("u", "viewer"), "schedule", "d", 1L, "l", false, 0);
        DataSourceSyncPayload back = DataSourceSyncPayload.fromJson(p.toJson());
        assertThat(back).isEqualTo(p);
    }

    // ── map 里的数字：Go 的浮点编码器 ──────────────────────────────────────

    /**
     * Go 对 {@code map[string]interface{}} 里的 {@code float64} 用**专用编码器**：
     * 整数值不补 {@code .0}、大数走指数且带 {@code +}。Jackson 走
     * {@code Double.toString}，两者系统性不同——这是
     * {@link com.ragagent.datasource.domain.DataSourceMapSerializer} 的存在理由。
     *
     * <p>语料是 Go 实录：
     * {@code {"a":1e21,"nested":{"a":[3,1e-7],"b":2},"s":"x","z":1}}。</p>
     */
    @Test
    void mapValuesUseGoFloatEncoding() throws Exception {
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("z", 1.0d);
        outer.put("a", 1e21d);
        outer.put("s", "x");
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("b", 2.0d);
        nested.put("a", new ArrayList<>(List.of(3.0d, 1e-7d)));
        outer.put("nested", nested);

        Resource r = new Resource();
        r.setMetadata(outer);

        assertThat(write(r)).contains(
                "\"metadata\":{\"a\":1e+21,\"nested\":{\"a\":[3,1e-7],\"b\":2},\"s\":\"x\",\"z\":1}");
    }

    /** 排序与数字归一是**递归**的：嵌套 map / 数组里的键序也要排。 */
    @Test
    void mapSerializerSortsRecursivelyAndKeepsGoKeyOrder() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", Map.of("z", 1, "a", 2));
        SyncCursor c = new SyncCursor();
        c.setConnectorCursor(m);
        assertThat(write(c)).contains("\"connectorCursor\":{\"b\":{\"a\":2,\"z\":1}}");
    }

    // ── 派生访问器 / 便捷方法 ──────────────────────────────────────────────

    /** Go 的三个 {@code DataSourceConfig} 方法与 {@code SubtreeChild*} 都是方法，不是字段。 */
    @Test
    void dataSourceConfigHelperSemanticsMatchGo() {
        DataSourceConfig c = new DataSourceConfig();
        assertThat(c.hasCredentials()).isFalse();
        assertThat(c.hasConfiguredCredentials("rss")).isFalse();

        c.setCredentials(new LinkedHashMap<>(Map.of("feed_urls", "x", "auth_headers", "y")));
        assertThat(c.hasCredentials()).isTrue();
        assertThat(c.hasConfiguredCredentials("rss")).isTrue();

        DataSourceConfig onlyFeedUrls = new DataSourceConfig();
        onlyFeedUrls.setCredentials(new LinkedHashMap<>(Map.of("feed_urls", "x")));
        assertThat(onlyFeedUrls.hasConfiguredCredentials("rss"))
                .as("RSS 只有 feed_urls 时不算配了凭据").isFalse();
        onlyFeedUrls.stripNonSecretCredentials("rss");
        assertThat(onlyFeedUrls.getCredentials()).as("清空后置为 null（=> 落库写 SQL NULL）").isNull();

        // 非 RSS 连接器不看 auth_headers
        DataSourceConfig feishu = new DataSourceConfig();
        feishu.setCredentials(new LinkedHashMap<>(Map.of("app_id", "x")));
        assertThat(feishu.hasConfiguredCredentials("feishu")).isTrue();

        // 只有空白也算没配
        DataSourceConfig blank = new DataSourceConfig();
        blank.setCredentials(new LinkedHashMap<>(Map.of("auth_headers", "   ")));
        assertThat(blank.hasConfiguredCredentials("rss")).isFalse();
    }

    /** {@code stripNonSecretCredentials} 对 nil 接收者是 no-op（Go 的 {@code d == nil} 判定）。 */
    @Test
    void stripNonSecretCredentialsIsNoOpOnEmpty() {
        DataSourceConfig c = new DataSourceConfig();
        c.stripNonSecretCredentials("rss");
        assertThat(c.getCredentials()).isNull();
    }

    @Test
    void subtreeChildHelpersMatchGo() {
        assertThat(SubtreeChildIds.subtreeChildId("docx1", "file", "tok"))
                .isEqualTo("docx1#file#tok");
        assertThat(SubtreeChildIds.subtreeChildPrefix("docx1")).isEqualTo("docx1#");
        assertThat(SubtreeChildIds.subtreeChildId("p", "image", "t"))
                .startsWith(SubtreeChildIds.subtreeChildPrefix("p"));
    }

    // ── jsonb 往返（裸映射器，就是落库/回读那两个方向） ────────────────────

    @Test
    void jsonbValuesRoundTripUnderNakedMapper() throws Exception {
        assertRoundTripsNaked(new DataSourceConfig(), DataSourceConfig.class);
        assertRoundTripsNaked(new SyncCursor(), SyncCursor.class);
        assertRoundTripsNaked(new SyncResult(), SyncResult.class);
        assertRoundTripsNaked(new Resource(), Resource.class);
        assertRoundTripsNaked(new FetchedItem(), FetchedItem.class);

        DataSourceConfig c = new DataSourceConfig();
        c.setType("rss");
        c.setCredentials(new LinkedHashMap<>(Map.of("auth_headers", "x", "n", 3.0d)));
        c.setResourceIds(new ArrayList<>(List.of("r")));
        c.setSettings(new LinkedHashMap<>(Map.of("feed_urls", "u")));
        assertRoundTripsNaked(c, DataSourceConfig.class);

        SyncCursor cursor = new SyncCursor();
        cursor.setLastSyncTime(ten());
        cursor.setConnectorCursor(new LinkedHashMap<>(Map.of("n", 2.0d)));
        cursor.setLastSchemaHash("h");
        assertRoundTripsNaked(cursor, SyncCursor.class);

        SyncResult result = new SyncResult();
        result.setTotal(1);
        result.setDeletionFailed(2);
        SyncItemError e = new SyncItemError();
        e.setCode("c");
        e.setMessage("m");
        result.setErrors(new ArrayList<>(List.of(e)));
        result.setNextCursor(cursor);
        assertRoundTripsNaked(result, SyncResult.class);

        // ⚠️ 含未知键的历史行也必须读得出来（Go 的 json.Unmarshal 默认忽略）
        DataSourceConfig tolerant = JSONB.readValue(
                "{\"type\":\"rss\",\"future\":1}", DataSourceConfig.class);
        assertThat(tolerant.getType()).isEqualTo("rss");
    }

    private static <T> void assertRoundTripsNaked(T value, Class<T> type) throws Exception {
        String first = JSONB.writeValueAsString(value);
        T back = JSONB.readValue(first, type);
        assertThat(com.ragagent.support.ContractJson.deep(JSONB.writeValueAsString(back)))
                .as("%s 经统一工厂往返必须幂等", type.getSimpleName())
                .isEqualTo(com.ragagent.support.ContractJson.deep(first));
    }

    // ── 键序 + 键数（§9：正则必须驼峰感知） ────────────────────────────────

    /**
     * 逐类型核对键序与键数，抓两类往返测试抓不到的问题：派生访问器多吐一个键、
     * 字段漏进 JSON（§14.9q D1 后键名＝Java 字段名，声明序＝输出序）。
     */
    @Test
    void entityKeyOrderAndCountMatchGoDeclarationOrder() throws Exception {
        assertKeyOrder(new DataSource(), "id", "tenantId", "knowledgeBaseId", "name", "type",
                "config", "syncSchedule", "syncMode", "status", "conflictStrategy",
                "syncDeletions", "lastSyncAt", "lastSyncCursor", "lastSyncResult",
                "errorMessage", "syncLogRetentionDays", "createdAt", "updatedAt",
                "deletedAt", "totalItemsSynced", "latestSyncLog");

        assertKeyOrder(new SyncLog(), "id", "dataSourceId", "tenantId", "status", "startedAt",
                "finishedAt", "itemsTotal", "itemsCreated", "itemsUpdated", "itemsDeleted",
                "itemsSkipped", "itemsFailed", "errorMessage", "result", "createdAt",
                "updatedAt");

        assertKeyOrder(new DataSourceConfig(), "type", "credentials", "resourceIds", "settings");

        assertKeyOrder(new Resource(), "externalId", "name", "type", "description", "url",
                "modifiedAt", "parentId", "hasChildren", "metadata");

        assertKeyOrder(new FetchedItem(), "externalId", "title", "content", "contentType",
                "fileName", "url", "updatedAt", "createdAt", "metadata", "deleted",
                "sourceResourceId", "replacesSubtree", "subtreeKeep");

        assertKeyOrder(new SyncCursor(), "lastSyncTime", "connectorCursor", "lastSchemaHash");

        assertKeyOrder(new SyncResult(), "total", "created", "updated", "deleted", "skipped",
                "failed", "deletionFailed", "errors", "nextCursor");

        assertKeyOrder(new SyncItemError(), "title", "code", "params", "message");

        assertKeyOrder(new TaskInitiator("u", "admin"), "user_id", "role");

        // DataSourceSyncPayload 属 D3（队列载荷），本批键名不动
        assertKeyOrder(new DataSourceSyncPayload(new TaskInitiator("u", "admin"), "t", "d", 1L,
                "l", true, 2), "initiator", "user_id", "role", "trigger", "data_source_id",
                "tenant_id", "sync_log_id", "force_full", "max_items");
    }

    /** 驼峰感知的键名正则——{@code "([a-z_]+)"} 会把驼峰键静默过滤掉（§9 明确要求）。 */
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

    // ── 严格往返（抓漏 @JsonIgnore 与键名不匹配） ──────────────────────────

    @Test
    void entitiesRoundTripUnderStrictMapper() {
        JsonRoundTrip.assertRoundTrips(new DataSourceConfig(), DataSourceConfig.class,
                "types.DataSourceConfig ← DataSourceConfig");
        JsonRoundTrip.assertRoundTrips(new SyncCursor(), SyncCursor.class,
                "types.SyncCursor ← SyncCursor");
        JsonRoundTrip.assertRoundTrips(new SyncResult(), SyncResult.class,
                "types.SyncResult ← SyncResult");
        JsonRoundTrip.assertRoundTrips(new SyncItemError(), SyncItemError.class,
                "types.SyncItemError ← SyncItemError");
        JsonRoundTrip.assertRoundTrips(new Resource(), Resource.class,
                "types.Resource ← Resource");
        JsonRoundTrip.assertRoundTrips(new FetchedItem(), FetchedItem.class,
                "types.FetchedItem ← FetchedItem");
        JsonRoundTrip.assertRoundTrips(new SyncLog(), SyncLog.class,
                "types.SyncLog ← SyncLog");
        JsonRoundTrip.assertRoundTrips(new DataSource(), DataSource.class,
                "types.DataSource ← DataSource");
        JsonRoundTrip.assertRoundTrips(TaskInitiator.empty(), TaskInitiator.class,
                "types.TaskInitiator ← TaskInitiator");

        DataSourceConfig full = new DataSourceConfig();
        full.setType("feishu");
        full.setCredentials(new LinkedHashMap<>(Map.of("app_id", "x")));
        full.setResourceIds(new ArrayList<>(List.of("r")));
        full.setSettings(new LinkedHashMap<>(Map.of("k", "v")));
        full.setMultimodalEnabled(true);
        JsonRoundTrip.assertRoundTrips(full, DataSourceConfig.class, "DataSourceConfig(full)");

        Resource resource = new Resource();
        resource.setExternalId("e");
        resource.setName("n");
        resource.setType("document");
        resource.setDescription("d");
        resource.setUrl("u");
        resource.setModifiedAt(ten());
        resource.setParentId("p");
        resource.setHasChildren(true);
        resource.setMetadata(new LinkedHashMap<>(Map.of("k", "v")));
        JsonRoundTrip.assertRoundTrips(resource, Resource.class, "Resource(full)");

        FetchedItem item = new FetchedItem();
        item.setExternalId("e");
        item.setTitle("t");
        item.setContent(new byte[]{1, 2, 3});
        item.setContentType("text/markdown");
        item.setFileName("f");
        item.setUrl("u");
        item.setUpdatedAt(ten());
        item.setCreatedAt(ten());
        item.setMetadata(new LinkedHashMap<>(Map.of("a", "b")));
        item.setDeleted(true);
        item.setSourceResourceId("s");
        item.setReplacesSubtree(true);
        item.setSubtreeKeep(new ArrayList<>(List.of("c")));
        JsonRoundTrip.assertRoundTrips(item, FetchedItem.class, "FetchedItem(full)");

        SyncLog log = new SyncLog();
        log.setId("l");
        log.setDataSourceId("d");
        log.setTenantId(1L);
        log.setStatus("success");
        log.setStartedAt(ten());
        log.setFinishedAt(ten());
        log.setItemsTotal(1);
        log.setItemsCreated(1);
        log.setItemsUpdated(1);
        log.setItemsDeleted(1);
        log.setItemsSkipped(1);
        log.setItemsFailed(1);
        log.setErrorMessage("e");
        log.setResult(jsonUnchecked("{\"a\":1}"));
        log.setCreatedAt(ten());
        log.setUpdatedAt(ten());
        JsonRoundTrip.assertRoundTrips(log, SyncLog.class, "SyncLog(full)");

        DataSource ds = new DataSource();
        ds.setId("d");
        ds.setTenantId(1L);
        ds.setKnowledgeBaseId("kb");
        ds.setName("n");
        ds.setType("rss");
        ds.setConfig(jsonUnchecked("{\"type\":\"rss\"}"));
        ds.setSyncSchedule("* * * * *");
        ds.setSyncMode("full");
        ds.setStatus("active");
        ds.setConflictStrategy("skip");
        ds.setSyncDeletions(true);
        ds.setLastSyncAt(ten());
        ds.setLastSyncCursor(jsonUnchecked("{\"a\":1}"));
        ds.setLastSyncResult(jsonUnchecked("{\"b\":2}"));
        ds.setErrorMessage("e");
        ds.setSyncLogRetentionDays(30);
        ds.setCreatedAt(ten());
        ds.setUpdatedAt(ten());
        ds.setDeletedAt(ten());
        ds.setTotalItemsSynced(5L);
        ds.setLatestSyncLog(log);
        JsonRoundTrip.assertRoundTrips(ds, DataSource.class, "DataSource(full)");

        DataSourceSyncPayload payload = new DataSourceSyncPayload(
                new TaskInitiator("u", "admin"), "manual", "d", 1L, "l", true, 3);
        JsonRoundTrip.assertRoundTrips(payload, DataSourceSyncPayload.class,
                "types.DataSourceSyncPayload ← DataSourceSyncPayload");
    }

    /** 往返测试里造 JSON 的小工具（这里刻意不抛受检异常）。 */
    private static JsonNode jsonUnchecked(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── JSON 编码器的键名/零值细节 ─────────────────────────────────────────

    /** {@code deleted_at} 有值时输出 RFC3339——与 {@code gorm.DeletedAt.MarshalJSON} 一致。 */
    @Test
    void dataSourceDeletedAtSerializesWhenSet() throws Exception {
        DataSource ds = new DataSource();
        ds.setDeletedAt(ten());
        assertThat(write(ds)).contains("\"deletedAt\":\"2026-09-18T10:00:00+08:00\"");
    }

    /** {@code DataSourceConfig.toJSON()} 与 Go 的 {@code json.Marshal} 同形。 */
    @Test
    void dataSourceConfigToJsonMatchesGoMarshal() throws Exception {
        DataSourceConfig empty = new DataSourceConfig();
        assertThat(MAPPER.writeValueAsString(empty.toJSON())).isEqualTo(
                "{\"type\":\"\",\"credentials\":null,\"resourceIds\":null,\"settings\":null}");

        DataSourceConfig c = new DataSourceConfig();
        c.setType("rss");
        c.setCredentials(new LinkedHashMap<>(Map.of("auth_headers", "h")));
        c.setSettings(new LinkedHashMap<>(Map.of("feed_urls", "u")));
        c.setMultimodalEnabled(true);

        // 没有 SYSTEM_AES_KEY 时凭据原样落库（与 Go 的 GetAESKey()==nil 分支一致）
        assertThat(MAPPER.writeValueAsString(c.toJSON())).isEqualTo(
                "{\"type\":\"rss\",\"credentials\":{\"auth_headers\":\"h\"},"
                        + "\"resourceIds\":null,\"settings\":{\"feed_urls\":\"u\"}}");
    }

    /** {@code toJSON()} 不得改动调用方的内存 map（Go 的浅拷贝理由）。 */
    @Test
    void dataSourceConfigToJsonDoesNotMutateCaller() throws Exception {
        DataSourceConfig c = new DataSourceConfig();
        Map<String, Object> creds = new LinkedHashMap<>();
        creds.put("app_id", "plain");
        c.setCredentials(creds);

        c.toJSON();
        assertThat(c.getCredentials()).containsEntry("app_id", "plain");
    }

    /** {@code SyncResult.toJSON()} / {@code SyncCursor.toJSON()} 的形状。 */
    @Test
    void syncResultAndCursorToJsonMatchGoMarshal() throws Exception {
        assertThat(MAPPER.writeValueAsString(new SyncResult().toJSON())).isEqualTo(
                "{\"total\":0,\"created\":0,\"updated\":0,\"deleted\":0,\"skipped\":0,\"failed\":0,"
                        + "\"deletionFailed\":0,\"errors\":null,\"nextCursor\":null}");
        assertThat(MAPPER.writeValueAsString(new SyncCursor().toJSON())).isEqualTo(
                "{\"lastSyncTime\":\"0001-01-01T00:00:00Z\",\"connectorCursor\":null,"
                        + "\"lastSchemaHash\":\"\"}");
    }

    /** 解析方法的两态：SQL NULL（Java null）→ Go 的 {@code len == 0} 短路。 */
    @Test
    void parseHelpersDistinguishSqlNullFromJsonNull() {
        DataSource ds = new DataSource();
        assertThat(ds.parseConfig()).isNull();
        assertThat(ds.parseSyncCursor()).isNull();
        assertThat(ds.parseSyncResult()).isNull();

        ds.setConfig(jsonUnchecked("null"));
        ds.setLastSyncCursor(jsonUnchecked("null"));
        ds.setLastSyncResult(jsonUnchecked("null"));
        // 字面量 null → json.Unmarshal 成功且留下零值（不是 nil）
        assertThat(ds.parseConfig()).isNotNull();
        assertThat(ds.parseSyncCursor()).isNotNull();
        assertThat(ds.parseSyncResult()).isNotNull();

        SyncLog log = new SyncLog();
        assertThat(log.parseResult()).isNull();
        log.setResult(jsonUnchecked("{\"total\":2}"));
        assertThat(log.parseResult().getTotal()).isEqualTo(2);
    }
}
