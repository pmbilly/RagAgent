package com.ragagent.datasource.connector.feishu.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.StreamHandler;
import com.ragagent.datasource.connector.feishu.core.FeishuCursorCodec;
import com.ragagent.datasource.connector.feishu.core.FeishuRegion;
import com.ragagent.datasource.connector.feishu.core.FeishuSupport;
import com.ragagent.datasource.connector.feishu.core.FeishuTestServer;
import com.ragagent.datasource.connector.feishu.core.FeishuTestSupport;
import com.ragagent.datasource.connector.feishu.core.SyncEngine;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * 对照 Go {@code wiki/connector_stream_test.go} 与 {@code wiki/connector_convergence_test.go}。
 *
 * <p>核心不变式（Tencent/WeKnora#2136）：<b>抓取失败不推进游标</b>、
 * <b>续跑会收敛</b>、<b>Emit 失败立刻中止</b>、<b>检查点按数量与时间两条规则触发</b>。</p>
 *
 * <p>Go 的 {@code context} 取消在 Java 侧是"handler 抛异常"——测试里的
 * {@link CancelAfterHandler} 就扮演 service 侧"灌入时发现任务已被取消"的角色。</p>
 */
class WikiConnectorStreamTest {

    private static FeishuTestServer server;

    @BeforeAll
    static void beforeAll() {
        FeishuTestSupport.allowLoopback();
    }

    @AfterAll
    static void afterAll() {
        FeishuTestSupport.restoreSsrf();
    }

    @BeforeEach
    void start() throws IOException {
        server = new FeishuTestServer();
        WikiFixtures.tokenRoute(server);
        WikiFixtures.spacesRoute(server);
        WikiFixtures.exportTrio(server, "fake-docx-content");
    }

    @AfterEach
    void stop() {
        server.close();
        WikiFixtures.resetParseMode();
        SyncEngine.checkpointInterval = 50;
        SyncEngine.checkpointMaxInterval = Duration.ofSeconds(30);
    }

    private WikiConnector connector() {
        return new WikiConnector(FeishuRegion.FEISHU);
    }

    private DataSourceConfig config(List<String> resourceIds) {
        return FeishuTestSupport.wikiConfig(server.baseUrl(), resourceIds);
    }

    private void fakeFeishu(WikiFixtures.Node... nodes) {
        WikiFixtures.hierarchyRoute(server, List.of(nodes), Map.of());
    }

    /** 只返回 export create 失败的桩（对照 Go 的 {@code fakeFeishuFailingExport}）。 */
    private void failingExportStub() {
        server.handle(WikiFixtures.EXPORT_CREATE_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":1,\"msg\":\"export unavailable\"}"));
    }

    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#2136：抓取失败保留旧编辑时间（下次重试），并发出可见错误条目")
    void failedFetchRetainsPriorCursor() {
        fakeFeishu(WikiFixtures.Node.of("nt1", "obj1", "docx", "Doc", "100"));
        failingExportStub();

        SyncCursor cursor = FeishuTestSupport.RecordingHandler.wikiCursor(
                Map.of("space1", Map.of("nt1", "50")));

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        SyncCursor next = connector().fetchStream(config(List.of("space1")), cursor, h);

        assertThat(h.emitted).hasSize(1);
        assertThat(h.emitted.get(0).getMetadata().get("error")).isNotEmpty();

        Map<String, Map<String, String>> times =
                FeishuCursorCodec.decodeSpaceNodeTimes(next.getConnectorCursor());
        assertThat(times.get("space1").get("nt1"))
                .as("失败节点不得推进到当前编辑时间（否则会被永久跳过）")
                .isNotEqualTo("100")
                .isEqualTo("50");
    }

    @Test
    @DisplayName("#2136：没有旧条目时，失败节点干脆不写进游标（下次当成新节点重试）")
    void failedFetchNoPriorOmitsFromCursor() {
        fakeFeishu(WikiFixtures.Node.of("nt1", "obj1", "docx", "Doc", "100"));
        failingExportStub();

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        SyncCursor next = connector().fetchStream(config(List.of("space1")), null, h);

        Map<String, Map<String, String>> times =
                FeishuCursorCodec.decodeSpaceNodeTimes(next.getConnectorCursor());
        assertThat(times.get("space1")).doesNotContainKey("nt1");
    }

    @Test
    @DisplayName("全量流：支持的类型各发一条，不支持的类型跳过但写进游标")
    void emitsSupportedSkipsUnsupported() {
        fakeFeishu(
                WikiFixtures.Node.of("nt1", "obj1", "docx", "Doc", "100"),
                WikiFixtures.Node.of("nt2", "obj2", "mindnote", "Brain", "200"),
                WikiFixtures.Node.of("nt3", "obj3", "docx", "Doc3", "300"));

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        SyncCursor next = connector().fetchStream(config(List.of("space1")), null, h);

        assertThat(h.emitted).hasSize(2);
        assertThat(h.emittedIds()).containsExactly("nt1", "nt3");

        Map<String, Map<String, String>> times =
                FeishuCursorCodec.decodeSpaceNodeTimes(next.getConnectorCursor());
        assertThat(times.get("space1")).containsKeys("nt1", "nt2", "nt3");
    }

    @Test
    @DisplayName("续跑快路径：编辑时间未变的节点不重新抓取")
    void skipsUnchangedNodesFromCursor() {
        fakeFeishu(
                WikiFixtures.Node.of("nt1", "obj1", "docx", "Doc", "100"),
                WikiFixtures.Node.of("nt3", "obj3", "docx", "Doc3", "300"));

        SyncCursor cursor = FeishuTestSupport.RecordingHandler.wikiCursor(
                Map.of("space1", Map.of("nt1", "100")));

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        connector().fetchStream(config(List.of("space1")), cursor, h);

        assertThat(h.emitted).hasSize(1);
        assertThat(h.emitted.get(0).getExternalId()).isEqualTo("nt3");
    }

    @Test
    @DisplayName("检查点按节点数触发：interval=1 时第一个检查点就含首个节点")
    void checkpointsProgress() {
        SyncEngine.checkpointInterval = 1;
        fakeFeishu(
                WikiFixtures.Node.of("nt1", "obj1", "docx", "Doc", "100"),
                WikiFixtures.Node.of("nt3", "obj3", "docx", "Doc3", "300"));

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        connector().fetchStream(config(List.of("space1")), null, h);

        assertThat(h.spaceNodeTimes).isNotEmpty();
        assertThat(h.spaceNodeTimes.get(0).get("space1")).containsKey("nt1");
    }

    @Test
    @DisplayName("检查点也按墙钟时间触发（节点数永不命中时仍要有检查点，否则 #2136 复发）")
    void checkpointsOnElapsedTime() {
        SyncEngine.checkpointInterval = 1 << 30; // 永不按数量触发
        SyncEngine.checkpointMaxInterval = Duration.ZERO; // 每个节点都按时间触发
        fakeFeishu(
                WikiFixtures.Node.of("nt1", "obj1", "docx", "Doc", "100"),
                WikiFixtures.Node.of("nt3", "obj3", "docx", "Doc3", "300"));

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        connector().fetchStream(config(List.of("space1")), null, h);

        assertThat(h.spaceNodeTimes).isNotEmpty();
    }

    @Test
    @DisplayName("Emit 失败立刻中止整条流（不再烧 API 配额）")
    void emitErrorAborts() {
        fakeFeishu(
                WikiFixtures.Node.of("nt1", "obj1", "docx", "Doc", "100"),
                WikiFixtures.Node.of("nt3", "obj3", "docx", "Doc3", "300"));

        RuntimeException boom = new IllegalStateException("ingest failed");
        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        h.emitFailure = boom;

        assertThatThrownBy(() -> connector().fetchStream(config(List.of("space1")), null, h))
                .isSameAs(boom);
        assertThat(h.emitted).isEmpty();
    }

    @Test
    @DisplayName("Checkpoint 失败只记日志、不中止同步（对照 Go 的 logger.Warnf）")
    void checkpointFailureDoesNotAbort() {
        SyncEngine.checkpointInterval = 1;
        fakeFeishu(WikiFixtures.Node.of("nt1", "obj1", "docx", "Doc", "100"));

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        h.checkpointFailure = new IllegalStateException("db down");

        SyncCursor next = connector().fetchStream(config(List.of("space1")), null, h);
        assertThat(next).isNotNull();
        assertThat(h.emitted).hasSize(1);
    }

    @Test
    @DisplayName("docx 多条目经 FetchStream 扇出（主文档 + 附件）")
    void docxMultiItem() {
        WikiFixtures.useBlocksParseMode();
        String nodeToken = "nt-blocks";
        String objToken = "obj-blocks";
        String attToken = "ft-stream-att";
        byte[] attContent = WikiFixtures.repeat("a", FeishuSupport.MIN_ATTACHMENT_BYTES + 1);

        fakeFeishu(WikiFixtures.Node.of(nodeToken, objToken, "docx", "Stream Blocks Doc", "500"));
        WikiFixtures.blocksRoute(server, objToken, WikiFixtures.blocks(
                WikiFixtures.pageBlockJson("b1"),
                WikiFixtures.textBlockJson("b2", 2, "Hello blocks"),
                WikiFixtures.fileBlockJson("b3", attToken, "slides.pdf")));
        WikiFixtures.mediaRoute(server, Map.of(attToken, attContent));

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        connector().fetchStream(config(List.of("space1")), null, h);

        assertThat(h.emitted).hasSize(2);
        FetchedItem main = h.emitted.get(0);
        assertThat(main.getExternalId()).isEqualTo(nodeToken);
        assertThat(main.getContentType()).isEqualTo("text/markdown");
        assertThat(main.isReplacesSubtree()).isTrue();

        FetchedItem att = h.emitted.get(1);
        assertThat(att.getExternalId()).isEqualTo(nodeToken + "#file#" + attToken);
        assertThat(att.getMetadata()).containsEntry("attachment", "true");
    }

    @Test
    @DisplayName("blocks API 500 → 导出回落：单条目、不设 ReplacesSubtree")
    void docxBlocksFallback() {
        WikiFixtures.useBlocksParseMode();
        String nodeToken = "nt-fallback";
        String objToken = "obj-fallback";

        fakeFeishu(WikiFixtures.Node.of(nodeToken, objToken, "docx", "Fallback Doc", "600"));
        WikiFixtures.blocksFailureRoute(server, objToken);

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        connector().fetchStream(config(List.of("space1")), null, h);

        assertThat(h.emitted).hasSize(1);
        FetchedItem item = h.emitted.get(0);
        assertThat(item.getExternalId()).isEqualTo(nodeToken);
        assertThat(item.getContentType()).isEqualTo("application/octet-stream");
        assertThat(item.isReplacesSubtree()).isFalse();
        assertThat(item.getMetadata().get("error")).isNull();
    }

    // ──────────────────────────────────────────────────────────────────
    // 收敛（#2136 端到端证明）
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go 的 {@code convergenceHandler}：记录已灌入/失败，并在第 N 次 Emit 抛异常。 */
    static final class CancelAfterHandler implements StreamHandler {

        final List<String> ingested = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final List<Map<String, Object>> checkpoints = new ArrayList<>();
        int calls;
        int cancelAfterCall;

        @Override
        public void emit(FetchedItem item) {
            calls++;
            if (cancelAfterCall > 0 && calls >= cancelAfterCall) {
                throw new ConnectorException("context canceled");
            }
            if (item.getMetadata() != null && item.getMetadata().get("error") != null
                    && !item.getMetadata().get("error").isEmpty()) {
                failed.add(item.getExternalId());
                return;
            }
            ingested.add(item.getExternalId());
        }

        @Override
        public void checkpoint(SyncCursor cursor) {
            if (cursor != null && cursor.getConnectorCursor() != null) {
                checkpoints.add(FeishuTestSupport.deepCopy(cursor.getConnectorCursor()));
            }
        }

        SyncCursor lastCheckpointCursor() {
            if (checkpoints.isEmpty()) {
                return null;
            }
            SyncCursor c = new SyncCursor();
            c.setConnectorCursor(checkpoints.get(checkpoints.size() - 1));
            return c;
        }
    }

    @Test
    @DisplayName("端到端收敛：瞬时导出失败 + 遍历中途被取消 → 重试后每个文档恰好同步一次")
    void resumeConvergesAfterTimeoutAndTransientFailure() {
        // 每处理一个节点就打检查点，让持久化的游标足够精确
        SyncEngine.checkpointInterval = 1;

        Set<String> failTokens = new HashSet<>();
        List<WikiFixtures.Node> nodes = List.of(
                WikiFixtures.Node.of("nt1", "obj1", "docx", "Doc1", "100"),
                WikiFixtures.Node.of("nt2", "obj2", "docx", "Doc2", "200"),
                WikiFixtures.Node.of("nt3", "obj3", "docx", "Doc3", "300"),
                WikiFixtures.Node.of("nt4", "obj4", "docx", "Doc4", "400"),
                WikiFixtures.Node.of("nt5", "obj5", "docx", "Doc5", "500"));
        fakeFeishu(nodes.toArray(new WikiFixtures.Node[0]));

        // 建任务是 ticket == obj_token；轮询用 token 查询参数决定成功/失败
        server.handle(WikiFixtures.EXPORT_CREATE_PATH, (ex, body) -> {
            String token = FeishuTestServer.jsonField(body, "token");
            FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{\"ticket\":"
                    + FeishuTestServer.jsonString(token) + "}}");
        });
        server.handle("/open-apis/drive/v1/export_tasks/", (ex, body) -> {
            String path = ex.getRequestURI().getPath();
            if (path.contains("/file/") && path.endsWith("/download")) {
                FeishuTestServer.sendBytes(ex, "application/octet-stream",
                        "fake-docx-content".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String token = WikiFixtures.queryParam(ex, "token");
            int status = failTokens.contains(token) ? 3 : 0;
            FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{\"result\":{"
                    + "\"file_token\":" + FeishuTestServer.jsonString("file-" + token)
                    + ",\"fileSize\":100,\"job_status\":" + status
                    + ",\"job_error_msg\":\"rate limited\",\"fileName\":\"exported.docx\"}}}");
        });

        DataSourceConfig ds = config(List.of("space1"));

        // Pass 1：obj2 瞬时导出失败；第 3 次 Emit 时任务"超时"（handler 抛异常）
        failTokens.add("obj2");
        CancelAfterHandler h1 = new CancelAfterHandler();
        h1.cancelAfterCall = 3;
        assertThatThrownBy(() -> connector().fetchStream(ds, null, h1))
                .as("pass 1 应当以取消/超时结束")
                .isInstanceOf(ConnectorException.class);

        assertThat(h1.ingested).as("pass 1 只应灌入 nt1").containsExactly("nt1");

        SyncCursor persisted = h1.lastCheckpointCursor();
        assertThat(persisted).as("pass 1 必须写出检查点，否则续跑会从头再来").isNotNull();
        Map<String, Map<String, String>> p =
                FeishuCursorCodec.decodeSpaceNodeTimes(persisted.getConnectorCursor());
        assertThat(p.get("space1")).containsKey("nt1");
        assertThat(p.get("space1")).doesNotContainKey("nt2"); // 瞬时失败不得被记成已同步
        assertThat(p.get("space1")).doesNotContainKey("nt3"); // 被取消的也不得记

        // Pass 2：asynq 重试；obj2 已恢复；从持久化游标续跑
        failTokens.clear();
        FeishuTestSupport.RecordingHandler h2 = new FeishuTestSupport.RecordingHandler();
        SyncCursor next2 = connector().fetchStream(ds, persisted, h2);

        assertThat(h2.emittedIds()).as("pass 2 不得重复导出已完成的 nt1").doesNotContain("nt1");
        for (String id : List.of("nt2", "nt3", "nt4", "nt5")) {
            assertThat(h2.emittedIds()).as("pass 2 必须灌入未完成的 %s", id).contains(id);
        }

        // 收敛：两趟并集覆盖全部 5 个节点，且每个恰好一次
        Set<String> union = new HashSet<>(h1.ingested);
        union.addAll(h2.emittedIds());
        assertThat(union).containsExactlyInAnyOrder("nt1", "nt2", "nt3", "nt4", "nt5");

        // 最终游标是全部节点的完整快照（下一次增量同步从干净状态开始）
        Map<String, Map<String, String>> fin =
                FeishuCursorCodec.decodeSpaceNodeTimes(next2.getConnectorCursor());
        assertThat(fin.get("space1")).containsKeys("nt1", "nt2", "nt3", "nt4", "nt5");
    }

    @Test
    @DisplayName("ConvergenceHandler 的 checkpoint 快照隔离：后续变更不影响已存的快照")
    void checkpointSnapshotsAreIsolated() {
        SyncEngine.checkpointInterval = 1;
        fakeFeishu(
                WikiFixtures.Node.of("nt1", "obj1", "docx", "Doc", "100"),
                WikiFixtures.Node.of("nt2", "obj2", "docx", "Doc2", "200"));

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        connector().fetchStream(config(List.of("space1")), null, h);

        assertThat(h.spaceNodeTimes).hasSize(2);
        assertThat(h.spaceNodeTimes.get(0).get("space1")).containsOnlyKeys("nt1");
        assertThat(h.spaceNodeTimes.get(1).get("space1")).containsOnlyKeys("nt1", "nt2");
    }
}
