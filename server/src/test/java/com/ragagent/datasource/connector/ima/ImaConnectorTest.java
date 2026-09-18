package com.ragagent.datasource.connector.ima;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.connector.ima.FakeIma.FakeFile;
import com.ragagent.datasource.connector.ima.FakeIma.FakeFolder;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * IMA 连接器的对等测试（逐条对照 Go {@code ima/connector_test.go}）。
 *
 * <h2>不许依赖真实网络</h2>
 * <p>全部打到 {@link FakeIma} 这个绑在 {@code 127.0.0.1} 的 stub 上，
 * 并照 Go 的 {@code TestMain} 放行 loopback（见
 * {@link FakeIma#allowLoopback()}）。</p>
 *
 * <h2>不许靠墙钟造时间</h2>
 * <p>重试退避一律注入 {@link ImaRetryPolicy#immediate()}（全零），
 * 所以 429 / 5xx 的用例是毫秒级的，而不是 N×2 秒。</p>
 */
class ImaConnectorTest {

    @BeforeAll
    static void allowLoopback() {
        FakeIma.allowLoopback();
    }

    @AfterAll
    static void restoreSsrf() {
        FakeIma.restoreSsrf();
    }

    private static ImaConnector connector() {
        return new ImaConnector(ImaRetryPolicy.immediate());
    }

    // ── 工具（对照 Go 的 findItem / mustFindItem / describeItems / decodeCursor） ──

    private static FetchedItem findItem(List<FetchedItem> items, String externalId) {
        for (FetchedItem it : items) {
            if (it.getExternalId().equals(externalId)) {
                return it;
            }
        }
        return null;
    }

    private static FetchedItem mustFindItem(List<FetchedItem> items, String externalId) {
        FetchedItem it = findItem(items, externalId);
        assertThat(it).as("item %s not found in %s", externalId, describeItems(items)).isNotNull();
        return it;
    }

    private static String describeItems(List<FetchedItem> items) {
        StringBuilder sb = new StringBuilder("[");
        for (FetchedItem it : items) {
            sb.append("{id=").append(it.getExternalId())
                    .append(" title=\"").append(it.getTitle())
                    .append("\" deleted=").append(it.isDeleted()).append("} ");
        }
        return sb.append(']').toString();
    }

    /** 对照 Go 的 {@code decodeCursor}：把 connector cursor 转回有类型的游标。 */
    private static ImaCursor decodeCursor(SyncCursor cursor) {
        assertThat(cursor).as("cursor is nil").isNotNull();
        ImaCursor decoded = ImaCursor.fromConnectorCursor(cursor.getConnectorCursor());
        assertThat(decoded).as("cursor must decode").isNotNull();
        return decoded;
    }

    private static Map<String, String> logicalOf(SyncCursor cursor, String kbId) {
        ImaCursor c = decodeCursor(cursor);
        Map<String, String> m = c.getKbLogical() == null ? null : c.getKbLogical().get(kbId);
        return m == null ? new LinkedHashMap<>() : m;
    }

    // ── 基础 ─────────────────────────────────────────────────────────────

    @Test
    void typeIsIma() {
        assertThat(connector().type()).isEqualTo("ima");
    }

    @Test
    void resolveResourceAncestorsIsEmpty() {
        assertThat(connector().resolveResourceAncestors(null, List.of("kb1"))).isEmpty();
    }

    // ── 回归：临时失败必须被重试（cursor bug） ───────────────────────────

    /**
     * 对照 Go {@code TestFetchIncremental_TransientFailureIsRetried}：
     * cursor 曾经是"抓内容之前用原始列表建的"，于是下载失败的条目被记成
     * "见过这个 media_id"，之后每次增量同步都把它当未变跳过——文档就永远丢了。
     */
    @Test
    void transientFailureIsRetried() throws Exception {
        try (FakeIma f = new FakeIma()) {
            FakeFile good = FakeFile.of("m-good", "Good", ImaFormats.MEDIA_TYPE_MARKDOWN).body("# ok");
            FakeFile flaky = FakeFile.of("m-flaky", "Flaky", ImaFormats.MEDIA_TYPE_MARKDOWN)
                    .body("# later").downloadFails();
            f.setKb("kb1", List.of(good, flaky));

            ImaConnector c = connector();
            DataSourceConfig cfg = f.config("kb1");
            String goodKey = ImaFormats.logicalKey("kb1", "", "Good");
            String flakyKey = ImaFormats.logicalKey("kb1", "", "Flaky");

            Connector.FetchIncrementalResult first = c.fetchIncremental(cfg, null);
            assertThat(findItem(first.items(), flakyKey))
                    .as("failed download must not be emitted, got %s", describeItems(first.items()))
                    .isNull();
            mustFindItem(first.items(), goodKey);

            assertThat(logicalOf(first.cursor(), "kb1"))
                    .as("a failed item must not be recorded in the cursor, otherwise it is never retried")
                    .doesNotContainKey(flakyKey);
            assertThat(logicalOf(first.cursor(), "kb1").get(goodKey)).isEqualTo("m-good");

            // 第二遍：下载现在成功，条目必须被重新尝试。
            flaky.downloadFails = false;
            f.setKb("kb1", List.of(good, flaky));

            Connector.FetchIncrementalResult second = c.fetchIncremental(cfg, first.cursor());
            FetchedItem retried = mustFindItem(second.items(), flakyKey);
            assertThat(new String(retried.getContent(), StandardCharsets.UTF_8)).isEqualTo("# later");
            assertThat(findItem(second.items(), goodKey)).isNull();
            assertThat(logicalOf(second.cursor(), "kb1")).containsEntry(flakyKey, "m-flaky");
        }
    }

    /**
     * 对照 Go {@code TestFetchIncremental_FailureIsNotADeletion}：
     * 把失败条目挡在 cursor 之外，也不能让下一轮把它误判成"从 IMA 消失了"。
     */
    @Test
    void failureIsNotADeletion() throws Exception {
        try (FakeIma f = new FakeIma()) {
            FakeFile good = FakeFile.of("m-good", "Good", ImaFormats.MEDIA_TYPE_MARKDOWN).body("# ok");
            FakeFile flaky = FakeFile.of("m-flaky", "Flaky", ImaFormats.MEDIA_TYPE_MARKDOWN).downloadFails();
            f.setKb("kb1", List.of(good, flaky));

            ImaConnector c = connector();
            DataSourceConfig cfg = f.config("kb1");
            String flakyKey = ImaFormats.logicalKey("kb1", "", "Flaky");

            Connector.FetchIncrementalResult first = c.fetchIncremental(cfg, null);
            Connector.FetchIncrementalResult second = c.fetchIncremental(cfg, first.cursor());

            for (FetchedItem it : second.items()) {
                assertThat(it.isDeleted() && it.getExternalId().equals(flakyKey))
                        .as("a still-listed item that merely failed to download must not be "
                                + "tombstoned: %s", describeItems(second.items()))
                        .isFalse();
            }
        }
    }

    @Test
    void removedItemIsTombstoned() throws Exception {
        try (FakeIma f = new FakeIma()) {
            FakeFile keep = FakeFile.of("m-keep", "Keep", ImaFormats.MEDIA_TYPE_MARKDOWN).body("# keep");
            FakeFile drop = FakeFile.of("m-drop", "Drop", ImaFormats.MEDIA_TYPE_MARKDOWN).body("# drop");
            f.setKb("kb1", List.of(keep, drop));

            ImaConnector c = connector();
            DataSourceConfig cfg = f.config("kb1");
            String dropKey = ImaFormats.logicalKey("kb1", "", "Drop");

            Connector.FetchIncrementalResult first = c.fetchIncremental(cfg, null);

            f.setKb("kb1", List.of(keep));
            Connector.FetchIncrementalResult second = c.fetchIncremental(cfg, first.cursor());

            FetchedItem tombstone = findItem(second.items(), dropKey);
            assertThat(tombstone).as("removed item must be tombstoned, got %s",
                    describeItems(second.items())).isNotNull();
            assertThat(tombstone.isDeleted()).isTrue();
            assertThat(tombstone.getSourceResourceId()).isEqualTo("kb1");
        }
    }

    /**
     * 对照 Go {@code TestFetchIncremental_SameNameReplacementKeepsExternalID}：
     * IMA 就地替换同名文件时会换 {@code media_id}，条目必须以**同一个 external_id**
     * 回来（表现为更新），而不是"删一条 + 加一条"。
     */
    @Test
    void sameNameReplacementKeepsExternalId() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(
                    FakeFile.of("m-v1", "Doc", ImaFormats.MEDIA_TYPE_MARKDOWN).body("v1")));

            ImaConnector c = connector();
            DataSourceConfig cfg = f.config("kb1");
            String key = ImaFormats.logicalKey("kb1", "", "Doc");

            Connector.FetchIncrementalResult first = c.fetchIncremental(cfg, null);

            f.setKb("kb1", List.of(
                    FakeFile.of("m-v2", "Doc", ImaFormats.MEDIA_TYPE_MARKDOWN).body("v2")));
            Connector.FetchIncrementalResult second = c.fetchIncremental(cfg, first.cursor());

            FetchedItem updated = mustFindItem(second.items(), key);
            assertThat(updated.isDeleted()).isFalse();
            assertThat(new String(updated.getContent(), StandardCharsets.UTF_8)).isEqualTo("v2");
            assertThat(updated.getMetadata().get("media_id")).isEqualTo("m-v2");
            for (FetchedItem it : second.items()) {
                assertThat(it.isDeleted()).as("no tombstone expected, got %s",
                        describeItems(second.items())).isFalse();
            }
            assertThat(logicalOf(second.cursor(), "kb1")).containsEntry(key, "m-v2");
        }
    }

    /**
     * 对照 Go {@code TestFetchIncremental_UnsupportedTypeIsProbedOnce}：
     * 确定性跳过要被记住，否则一个装满笔记的 KB 每次同步都要白付一次
     * {@code get_media_info}。
     */
    @Test
    void unsupportedTypeIsProbedOnce() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(
                    FakeFile.of("m-note", "Note", ImaFormats.MEDIA_TYPE_NOTE)));

            ImaConnector c = connector();
            DataSourceConfig cfg = f.config("kb1");

            Connector.FetchIncrementalResult first = c.fetchIncremental(cfg, null);
            assertThat(first.items()).as("note must be skipped, got %s",
                    describeItems(first.items())).isEmpty();
            assertThat(f.callCount("get_media_info:m-note")).isEqualTo(1);

            c.fetchIncremental(cfg, first.cursor());
            assertThat(f.callCount("get_media_info:m-note"))
                    .as("skip should be cached")
                    .isEqualTo(1);
        }
    }

    // ── 笔记走 note 命名空间 ─────────────────────────────────────────────

    /**
     * 对照 Go {@code TestFetchAll_NoteBodyIsReadFromNoteNamespace}：
     * 笔记完全没有 url_info，{@code get_media_info} 只给 notebook_id，
     * 正文必须去 {@code /openapi/note/v1/get_doc_content} 读。
     */
    @Test
    void noteBodyIsReadFromNoteNamespace() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(
                    FakeFile.of("note_1", "Meeting notes", ImaFormats.MEDIA_TYPE_NOTE)
                            .notebook("987654321", "# Standup\n- shipped the connector")));

            List<FetchedItem> items = connector().fetchAll(f.config("kb1"), List.of("kb1"));

            FetchedItem note = mustFindItem(items, ImaFormats.logicalKey("kb1", "", "Meeting notes"));
            assertThat(new String(note.getContent(), StandardCharsets.UTF_8))
                    .isEqualTo("# Standup\n- shipped the connector");
            assertThat(note.getFileName()).endsWith(".md");
            assertThat(note.getContentType()).isEqualTo("text/markdown");
            assertThat(note.getMetadata().get("notebook_id")).isEqualTo("987654321");
            assertThat(f.callCount("get_doc_content:987654321")).isEqualTo(1);
        }
    }

    @Test
    void noteReadFailureIsRetried() throws Exception {
        try (FakeIma f = new FakeIma()) {
            FakeFile note = FakeFile.of("note_1", "Broken", ImaFormats.MEDIA_TYPE_NOTE)
                    .notebook("111", "recovered").noteFails();
            f.setKb("kb1", List.of(note));

            ImaConnector c = connector();
            DataSourceConfig cfg = f.config("kb1");
            String key = ImaFormats.logicalKey("kb1", "", "Broken");

            Connector.FetchIncrementalResult first = c.fetchIncremental(cfg, null);
            assertThat(first.items()).as("a note that could not be read must not be emitted, got %s",
                    describeItems(first.items())).isEmpty();
            assertThat(logicalOf(first.cursor(), "kb1"))
                    .as("a failed note read must not be recorded in the cursor")
                    .doesNotContainKey(key);

            note.noteFails = false;
            f.setKb("kb1", List.of(note));
            Connector.FetchIncrementalResult second = c.fetchIncremental(cfg, first.cursor());
            assertThat(new String(mustFindItem(second.items(), key).getContent(), StandardCharsets.UTF_8))
                    .isEqualTo("recovered");
        }
    }

    @Test
    void emptyNoteIsSkipped() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(
                    FakeFile.of("note_1", "Blank", ImaFormats.MEDIA_TYPE_NOTE)
                            .notebook("222", "   \n  ")));

            List<FetchedItem> items = connector().fetchAll(f.config("kb1"), List.of("kb1"));
            assertThat(items).as("an empty note must be skipped, got %s",
                    describeItems(items)).isEmpty();
        }
    }

    /** 对照 Go {@code TestFetchAll_AISessionIsStillSkipped}。 */
    @Test
    void aiSessionAndVideoAreStillSkipped() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(
                    FakeFile.of("m-ai", "Chat", ImaFormats.MEDIA_TYPE_AI_SESSION),
                    FakeFile.of("m-video", "Clip", ImaFormats.MEDIA_TYPE_VIDEO)));

            List<FetchedItem> items = connector().fetchAll(f.config("kb1"), List.of("kb1"));
            assertThat(items).as("AI sessions and video parses must be skipped, got %s",
                    describeItems(items)).isEmpty();
        }
    }

    // ── fetchOneMedia 的三条分支 ─────────────────────────────────────────

    /**
     * 对照 Go {@code TestFetchAll_AuthenticatedURLIsDownloaded}：没有固定扩展名的
     * 媒体类型，当 IMA 附带鉴权头时 URL 指向 IMA 托管存储，WeKnora 自己抓不了，
     * 所以连接器必须在这里下载并把头带上。
     */
    @Test
    void authenticatedUrlIsDownloaded() throws Exception {
        try (FakeIma f = new FakeIma()) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Ima-Token", "secret");
            f.setKb("kb1", List.of(
                    FakeFile.of("m-web", "Article", ImaFormats.MEDIA_TYPE_WEB)
                            .body("<html>body</html>")
                            .contentType("text/html")
                            .urlHeaders(headers)));

            List<FetchedItem> items = connector().fetchAll(f.config("kb1"), List.of("kb1"));
            FetchedItem item = mustFindItem(items, ImaFormats.logicalKey("kb1", "", "Article"));
            assertThat(new String(item.getContent(), StandardCharsets.UTF_8))
                    .isEqualTo("<html>body</html>");
            assertThat(item.getFileName()).endsWith(".html");
            assertThat(f.downloadHeaders("m-web").get("x-ima-token")).isEqualTo("secret");
        }
    }

    /**
     * 对照 Go {@code TestFetchAll_PublicURLStaysURLOnly}：没有鉴权头说明链接公网可达，
     * 那就把 URL 交给 ingest 层，让 WeKnora 抓实时页面而不是快照。
     */
    @Test
    void publicUrlStaysUrlOnly() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(
                    FakeFile.of("m-web", "Article", ImaFormats.MEDIA_TYPE_WEB).body("ignored")));

            List<FetchedItem> items = connector().fetchAll(f.config("kb1"), List.of("kb1"));
            FetchedItem item = mustFindItem(items, ImaFormats.logicalKey("kb1", "", "Article"));
            assertThat(item.getContent()).as("public URL must not be downloaded").isNull();
            assertThat(item.getUrl()).isNotEmpty();
            assertThat(f.callCount("download:m-web")).isZero();
        }
    }

    /**
     * 对照 Go {@code TestFetchAll_ImageExtensionFollowsContentType}：IMA 把所有图片
     * 都报成 media_type=9，只有下载响应的 Content-Type 能揭穿真实格式，
     * 而扩展名驱动 ingest 层的文件类型判定。
     */
    @Test
    void imageExtensionFollowsContentType() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(
                    FakeFile.of("m-img", "Photo", ImaFormats.MEDIA_TYPE_IMAGE)
                            .body("jpeg-bytes").contentType("image/jpeg")));

            List<FetchedItem> items = connector().fetchAll(f.config("kb1"), List.of("kb1"));
            FetchedItem item = mustFindItem(items, ImaFormats.logicalKey("kb1", "", "Photo"));
            assertThat(item.getFileName()).endsWith(".jpg");
            assertThat(item.getContentType()).isEqualTo("image/jpeg");
        }
    }

    /** Content-Type 缺失时用扩展名反推 MIME（对照 Go 的 {@code mimeForExtension} 分支）。 */
    @Test
    void missingContentTypeFallsBackToExtensionMime() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(
                    FakeFile.of("m-pdf", "Doc", ImaFormats.MEDIA_TYPE_PDF).body("%PDF-1.4")));

            List<FetchedItem> items = connector().fetchAll(f.config("kb1"), List.of("kb1"));
            FetchedItem item = mustFindItem(items, ImaFormats.logicalKey("kb1", "", "Doc"));
            assertThat(item.getFileName()).isEqualTo("Doc.pdf");
            assertThat(item.getContentType()).isEqualTo("application/pdf");
        }
    }

    @Test
    void skipsItemWithoutUrl() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(
                    FakeFile.of("m-empty", "Empty", ImaFormats.MEDIA_TYPE_PDF).noUrl()));

            List<FetchedItem> items = connector().fetchAll(f.config("kb1"), List.of("kb1"));
            assertThat(items).as("item without a URL must be skipped, got %s",
                    describeItems(items)).isEmpty();
        }
    }

    // ── 混合数组的 BFS + 文件夹路径 ──────────────────────────────────────

    /** 对照 Go {@code TestFetchAll_ResolvesFolderPath}。 */
    @Test
    void resolvesFolderPath() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1",
                    List.of(
                            FakeFile.of("m-root", "Root", ImaFormats.MEDIA_TYPE_MARKDOWN).body("root"),
                            FakeFile.of("m-deep", "Deep", ImaFormats.MEDIA_TYPE_MARKDOWN)
                                    .body("deep").parentFolder("f2")),
                    FakeFolder.of("f1", "Outer", ""),
                    FakeFolder.of("f2", "Inner", "f1"));

            List<FetchedItem> items = connector().fetchAll(f.config("kb1"), List.of("kb1"));

            FetchedItem deep = mustFindItem(items, ImaFormats.logicalKey("kb1", "f2", "Deep"));
            assertThat(deep.getMetadata().get("folder_path")).isEqualTo("Outer/Inner");
            assertThat(deep.getMetadata().get("parent_folder_id")).isEqualTo("f2");

            FetchedItem root = mustFindItem(items, ImaFormats.logicalKey("kb1", "", "Root"));
            assertThat(root.getMetadata().get("folder_path")).isEmpty();
            assertThat(root.getMetadata()).doesNotContainKey("parent_folder_id");
        }
    }

    /** 对照 Go 的 {@code listAllKBFiles}：根级也走 {@code get_knowledge_list}（不传 folder_id）。 */
    @Test
    void rootListingOmitsFolderIdAndPassesKbId() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(
                    FakeFile.of("m-1", "One", ImaFormats.MEDIA_TYPE_MARKDOWN).body("x")));

            connector().fetchAll(f.config("kb1"), List.of("kb1"));

            var rootBody = f.lastRequestBody("get_knowledge_list");
            assertThat(rootBody).isNotNull();
            assertThat(rootBody.get("knowledge_base_id").asText()).isEqualTo("kb1");
            assertThat(rootBody.has("folder_id")).as("folder_id must be omitted for the root").isFalse();
            assertThat(rootBody.get("limit").asInt()).isEqualTo(ImaClient.DEFAULT_PAGE_SIZE);
        }
    }

    /** 非根文件夹必须带上 {@code folder_id}（对照 Go 的 {@code if folderID != ""} 分支）。 */
    @Test
    void nestedListingPassesFolderId() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1",
                    List.of(FakeFile.of("m-deep", "Deep", ImaFormats.MEDIA_TYPE_MARKDOWN)
                            .body("deep").parentFolder("f1")),
                    FakeFolder.of("f1", "Outer", ""));

            connector().fetchAll(f.config("kb1"), List.of("kb1"));

            // 最后一次 get_knowledge_list 是内层文件夹那次。
            var body = f.lastRequestBody("get_knowledge_list");
            assertThat(body.get("folder_id").asText()).isEqualTo("f1");
        }
    }

    // ── baseMetadata 的键集合 ────────────────────────────────────────────

    /**
     * 对照 Go 实录（{@code baseMetadata} 的真值）：
     * 恒有 {@code channel/media_id/ima_logical_key/knowledge_base_id/folder_path/media_type}
     * 六项；{@code parent_folder_id} 与 {@code notebook_id} 只在非空时出现。
     */
    @Test
    void baseMetadataKeySetMatchesGo() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(
                    FakeFile.of("m1", "T1", ImaFormats.MEDIA_TYPE_MARKDOWN).body("x")));

            List<FetchedItem> items = connector().fetchAll(f.config("kb1"), List.of("kb1"));
            FetchedItem item = mustFindItem(items, ImaFormats.logicalKey("kb1", "", "T1"));

            Map<String, String> meta = item.getMetadata();
            assertThat(meta).containsOnlyKeys(
                    "channel", "media_id", "ima_logical_key", "knowledge_base_id",
                    "folder_path", "media_type");
            assertThat(meta.get("channel")).isEqualTo("ima");
            assertThat(meta.get("media_id")).isEqualTo("m1");
            assertThat(meta.get("ima_logical_key"))
                    .isEqualTo(ImaFormats.logicalKey("kb1", "", "T1"));
            assertThat(meta.get("knowledge_base_id")).isEqualTo("kb1");
            assertThat(meta.get("folder_path")).isEmpty();
            assertThat(meta.get("media_type")).isEqualTo("7");
        }
    }

    /** 笔记的 metadata 会多出 {@code notebook_id}（第三组 Go 实录）。 */
    @Test
    void baseMetadataAddsNotebookIdForNotes() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(
                    FakeFile.of("note_1", "N", ImaFormats.MEDIA_TYPE_NOTE)
                            .notebook("n1", "body")));

            List<FetchedItem> items = connector().fetchAll(f.config("kb1"), List.of("kb1"));
            FetchedItem note = mustFindItem(items, ImaFormats.logicalKey("kb1", "", "N"));
            assertThat(note.getMetadata()).containsEntry("notebook_id", "n1");
            assertThat(note.getMetadata()).containsEntry("media_type", "11");
        }
    }

    // ── ListResources ───────────────────────────────────────────────────

    /** 对照 Go {@code TestListResources_ReturnsAddableBases}。 */
    @Test
    void listResourcesReturnsAddableBases() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb-b", List.of());
            f.setKb("kb-a", List.of());

            List<Resource> resources = connector().listResources(f.config(), "");

            assertThat(resources).hasSize(2);
            assertThat(resources.get(0).getExternalId()).isEqualTo("kb-a");
            assertThat(resources.get(1).getExternalId()).isEqualTo("kb-b");
            assertThat(resources.get(0).getDescription()).isEqualTo("desc kb-a");
            assertThat(resources.get(0).getType()).isEqualTo("knowledge_base");
            assertThat(resources.get(0).getMetadata()).containsEntry("cover_url", "");
            assertThat(resources.get(0).getUrl()).isEqualTo(f.baseUrl());
            // Go 的零值 time.Time → 恒输出的 year-1 字面量（见 Resource 的类注释）
            assertThat(resources.get(0).getModifiedAt())
                    .isEqualTo(com.ragagent.common.web.GoTimeSerializer.GO_ZERO_DATE_TIME);
        }
    }

    /** 对照 Go {@code TestListResources_FallsBackToSearch}。 */
    @Test
    void listResourcesFallsBackToSearch() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.clearBases();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", "kb-x");
            entry.put("name", "X");
            entry.put("cover_url", "https://cover");
            f.setSearchBases(List.of(entry));

            List<Resource> resources = connector().listResources(f.config(), "");

            assertThat(resources).hasSize(1);
            assertThat(resources.get(0).getExternalId()).isEqualTo("kb-x");
            assertThat(resources.get(0).getMetadata()).containsEntry("cover_url", "https://cover");
        }
    }

    @Test
    void listResourcesChildrenAreEmpty() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of());
            assertThat(connector().listResources(f.config(), "kb1")).isEmpty();
        }
    }

    // ── 校验与参数 ───────────────────────────────────────────────────────

    /** 对照 Go {@code TestValidate_InvalidCredentials}。 */
    @Test
    void validateRejectsMissingClientId() throws Exception {
        try (FakeIma f = new FakeIma()) {
            DataSourceConfig cfg = f.config();
            cfg.getCredentials().put("client_id", "");

            assertThatThrownBy(() -> connector().validate(cfg))
                    .isInstanceOf(ConnectorException.InvalidCredentials.class)
                    .hasMessageContaining("client_id");
        }
    }

    /** 对照 Go {@code TestParseIMAConfig_NilConfig}。 */
    @Test
    void parseRejectsNilConfig() {
        assertThatThrownBy(() -> ImaConfig.parse(null))
                .isInstanceOf(ConnectorException.InvalidConfig.class);
    }

    /** 对照 Go {@code TestParseIMAConfig} 的 SSRF 档：元数据地址必须被拒。 */
    @Test
    void parseRejectsSsrfBaseUrl() {
        DataSourceConfig cfg = new DataSourceConfig();
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("client_id", "cid");
        credentials.put("api_key", "key");
        credentials.put("base_url", "http://169.254.169.254");
        cfg.setCredentials(credentials);

        assertThatThrownBy(() -> ImaConfig.parse(cfg))
                .hasMessageContaining("SSRF");
    }

    /** 对照 Go {@code TestFetchIncremental_RequiresResourceIDs}。 */
    @Test
    void fetchIncrementalRequiresResourceIds() throws Exception {
        try (FakeIma f = new FakeIma()) {
            assertThatThrownBy(() -> connector().fetchIncremental(f.config(), null))
                    .hasMessageContaining("no resource IDs");
        }
    }

    /** 全量同步不返回 cursor（对照 Go 的 {@code if !incremental { return out, nil, nil }}）。 */
    @Test
    void fetchAllDoesNotProduceCursor() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(
                    FakeFile.of("m-1", "One", ImaFormats.MEDIA_TYPE_MARKDOWN).body("x")));

            List<FetchedItem> items = connector().fetchAll(f.config("kb1"), List.of("kb1"));
            assertThat(items).hasSize(1);
        }
    }

    /**
     * connector cursor 的键集合与值形态（Go 的 marshal/unmarshal 往返净效果）：
     * 恒有 {@code last_sync_time}（**字符串**——roundtrip 把 time.Time 变成 string）
     * 与 {@code kb_logical}；遗留的 {@code kb_media} 永不出现。
     */
    @Test
    void connectorCursorShapeMatchesGoRoundTrip() throws Exception {
        try (FakeIma f = new FakeIma()) {
            f.setKb("kb1", List.of(
                    FakeFile.of("m-1", "One", ImaFormats.MEDIA_TYPE_MARKDOWN).body("x")));

            SyncCursor cursor = connector().fetchIncremental(f.config("kb1"), null).cursor();

            assertThat(cursor.getConnectorCursor()).containsOnlyKeys("last_sync_time", "kb_logical");
            assertThat(cursor.getConnectorCursor().get("last_sync_time")).isInstanceOf(String.class);
            assertThat((String) cursor.getConnectorCursor().get("last_sync_time"))
                    .startsWith("20");  // RFC3339（JVM 默认时区）
            @SuppressWarnings("unchecked")
            Map<String, Object> kbLogical =
                    (Map<String, Object>) cursor.getConnectorCursor().get("kb_logical");
            assertThat(kbLogical).containsOnlyKeys("kb1");
            @SuppressWarnings("unchecked")
            Map<String, Object> entries = (Map<String, Object>) kbLogical.get("kb1");
            assertThat(entries).containsEntry(ImaFormats.logicalKey("kb1", "", "One"), "m-1");
        }
    }

    /**
     * 一个 KB 枚举失败必须**整体失败**（Go 的 {@code return nil, nil, fmt.Errorf("list KB %s: %w")}），
     * 而不是"跳过这个 KB 继续"。
     */
    @Test
    void kbListingFailureAbortsTheWholeWalk() throws Exception {
        try (FakeIma f = new FakeIma()) {
            DataSourceConfig cfg = f.config("kb1");
            cfg.getCredentials().put("base_url", "http://127.0.0.1:1"); // 端口上没人听

            assertThatThrownBy(() -> connector().fetchAll(cfg, List.of("kb1")))
                    .isInstanceOf(ConnectorException.class);
        }
    }
}
