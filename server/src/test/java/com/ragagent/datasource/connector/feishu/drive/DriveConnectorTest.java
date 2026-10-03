package com.ragagent.datasource.connector.feishu.drive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.connector.feishu.core.DocxFetcher;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveFile;
import com.ragagent.datasource.connector.feishu.core.FeishuCursorCodec;
import com.ragagent.datasource.connector.feishu.core.FeishuRegion;
import com.ragagent.datasource.connector.feishu.core.FeishuSupport;
import com.ragagent.datasource.connector.feishu.core.FeishuTestServer;
import com.ragagent.datasource.connector.feishu.core.FeishuTestSupport;
import com.ragagent.datasource.connector.feishu.core.SyncEngine;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * 云盘连接器的对等测试（含 {@code listDriveFilesForResource} 的 {@code 1061002}
 * 回落语义、游标往返）。
 *
 * <p>云盘与 wiki 共用引擎，所以这里只测<b>云盘特有</b>的部分：资源 ID 编码、
 * 根文件夹解析、祖先 BFS、列举回落、快捷方式展开、渠道标签。</p>
 */
class DriveConnectorTest {

    private static FeishuTestServer server;

    private static final String FILES_PATH = "/open-apis/drive/v1/files";
    private static final String TOKEN_PATH = "/open-apis/auth/v3/tenant_access_token/internal";

    /** folderToken → 子项 JSON 列表（null 表示列举失败）。 */
    private Map<String, List<String>> folders;
    private final List<String> fileListCalls = new ArrayList<>();

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
        folders = new LinkedHashMap<>();
        fileListCalls.clear();
        WikiToken(server);

        server.handle(FILES_PATH, (ex, body) -> {
            String folder = queryParam(ex, "folder_token");
            fileListCalls.add(folder);
            List<String> files = folders.get(folder);
            if (files == null) {
                // 对照飞书对"非文件夹 token"的响应：1061002 params error
                FeishuTestServer.sendJson(ex, "{\"code\":1061002,\"msg\":\"params error\"}");
                return;
            }
            FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{\"files\":["
                    + String.join(",", files) + "],\"has_more\":false,\"next_page_token\":\"\"}}");
        });
    }

    @AfterEach
    void stop() {
        server.close();
        DocxFetcher.parseMode = () -> System.getenv("FEISHU_DOCX_PARSE_MODE");
    }

    private static void WikiToken(FeishuTestServer s) {
        s.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"tenant_access_token\":\"fake-token\",\"expire\":7200}"));
    }

    private DriveConnector connector() {
        return new DriveConnector(FeishuRegion.FEISHU_DRIVE);
    }

    private DataSourceConfig config(List<String> resourceIds) {
        return FeishuTestSupport.driveConfig(server.baseUrl(), resourceIds, false);
    }

    private static String fileJson(String token, String name, String type, String parent,
                                   String modifiedTime) {
        return fileJson(token, name, type, parent, modifiedTime, null, null);
    }

    private static String fileJson(String token, String name, String type, String parent,
                                   String modifiedTime, String targetToken, String targetType) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"token\":").append(FeishuTestServer.jsonString(token)).append(',');
        sb.append("\"name\":").append(FeishuTestServer.jsonString(name)).append(',');
        sb.append("\"type\":").append(FeishuTestServer.jsonString(type)).append(',');
        sb.append("\"parent_token\":").append(FeishuTestServer.jsonString(parent)).append(',');
        sb.append("\"url\":").append(FeishuTestServer.jsonString(
                "https://example.feishu.cn/file/" + token)).append(',');
        sb.append("\"created_time\":\"10\",");
        sb.append("\"modified_time\":").append(FeishuTestServer.jsonString(modifiedTime)).append(',');
        sb.append("\"owner_id\":\"ou_1\"");
        if (targetToken != null) {
            sb.append(",\"shortcut_info\":{\"target_token\":")
                    .append(FeishuTestServer.jsonString(targetToken))
                    .append(",\"target_type\":").append(FeishuTestServer.jsonString(targetType))
                    .append('}');
        }
        return sb.append('}').toString();
    }

    private void folderMetaRoute(String folderToken, String name) {
        server.handle("/open-apis/drive/explorer/v2/folder/", (ex, body) -> {
            String path = ex.getRequestURI().getPath();
            String token = path.substring("/open-apis/drive/explorer/v2/folder/".length(),
                    path.length() - "/meta".length());
            if (!token.equals(folderToken)) {
                FeishuTestServer.sendJson(ex, "{\"code\":1061002,\"msg\":\"not found\"}");
                return;
            }
            FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{"
                    + "\"id\":\"id1\",\"name\":" + FeishuTestServer.jsonString(name)
                    + ",\"token\":" + FeishuTestServer.jsonString(folderToken)
                    + ",\"createUid\":\"u1\",\"editUid\":\"u1\",\"parentId\":\"\",\"ownUid\":\"u1\"}}");
        });
    }

    // ──────────────────────────────────────────────────────────────────
    // 基础
    // ──────────────────────────────────────────────────────────────────

    @Test
    void connectorType() {
        assertThat(connector().type()).isEqualTo("feishu_drive");
        assertThat(new DriveConnector(FeishuRegion.LARK_DRIVE).type()).isEqualTo("lark_drive");
    }

    @Test
    @DisplayName("validate 的错误前缀是 region.Label（FeishuDrive / LarkDrive）")
    void validateUsesRegionLabel() {
        server.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":99991663,\"msg\":\"invalid access token\"}"));

        assertThatThrownBy(() -> connector().validate(config(List.of("folder1"))))
                .isInstanceOf(ConnectorException.class)
                .hasMessage("FeishuDrive connection failed: feishu auth error: "
                        + "code=99991663 msg=invalid access token");

        assertThatThrownBy(() -> new DriveConnector(FeishuRegion.LARK_DRIVE)
                .validate(config(List.of("folder1"))))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("LarkDrive connection failed: ");
    }

    @Test
    @DisplayName("ResourceID 编解码：根是裸 token，子是 folderToken:fileToken")
    void resourceIdHelpers() {
        assertThat(DriveConnector.makeDriveResourceId("folder1", "")).isEqualTo("folder1");
        assertThat(DriveConnector.makeDriveResourceId("folder1", "fdoc1")).isEqualTo("folder1:fdoc1");
        assertThat(DriveConnector.parseDriveResourceId("folder1:fdoc1"))
                .containsExactly("folder1", "fdoc1");
        assertThat(DriveConnector.parseDriveResourceId("folder1")).containsExactly("folder1", "");
    }

    @Test
    @DisplayName("driveRootFolderToken 取 resourceIds[0] 的根部分")
    void rootFolderToken() {
        assertThat(DriveConnector.driveRootFolderToken(config(List.of("folder1:fdoc1"))))
                .isEqualTo("folder1");
        assertThat(DriveConnector.driveRootFolderToken(config(List.of("folder1"))))
                .isEqualTo("folder1");
        assertThat(DriveConnector.driveRootFolderToken(config(List.of()))).isEmpty();
        assertThat(DriveConnector.driveRootFolderToken(null)).isEmpty();
    }

    @Test
    @DisplayName("游标往返（对照 cursor_test.go）：file_times 经 JSON 快照后仍可读")
    void driveCursorRoundTrip() {
        Map<String, Map<String, String>> times = new LinkedHashMap<>();
        times.put("folder1", Map.of("fdoc1", "100", "fdoc2", "200"));
        times.put("folder1:fdoc1", Map.of("fdoc1", "100"));
        times.put("folder2:subA", Map.of("fdoc3", "300"));

        SyncCursor cursor = FeishuCursorCodec.encodeFileTimes(times, OffsetDateTime.now());
        assertThat(cursor.getConnectorCursor()).containsKey("file_times");
        assertThat(cursor.getConnectorCursor()).doesNotContainKey("space_node_times");

        Map<String, Map<String, String>> restored = FeishuCursorCodec.decodeFileTimes(
                FeishuTestSupport.deepCopy(cursor.getConnectorCursor()));
        assertThat(restored).hasSameSizeAs(times);
        assertThat(restored.get("folder1")).containsEntry("fdoc1", "100").containsEntry("fdoc2", "200");
        assertThat(restored.get("folder2:subA")).containsEntry("fdoc3", "300");
    }

    // ──────────────────────────────────────────────────────────────────
    // ListResources / ResolveResourceAncestors
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("根加载：用户给的 folder_token + meta 解析出来的名字")
    void listResourcesRoot() {
        folders.put("folder1", List.of(fileJson("fdoc1", "Doc", "docx", "folder1", "100")));
        folderMetaRoute("folder1", "我的云盘");

        List<Resource> resources = connector().listResources(config(List.of("folder1")), "");
        assertThat(resources).hasSize(1);
        Resource r = resources.get(0);
        assertThat(r.getExternalId()).isEqualTo("folder1");
        assertThat(r.getName()).isEqualTo("我的云盘");
        assertThat(r.getType()).isEqualTo("drive_folder");
        assertThat(r.getUrl()).isEqualTo("https://feishu.cn/drive/folder/folder1");
        assertThat(r.isHasChildren()).isTrue();
        assertThat(r.getMetadata()).containsEntry("folder_token", "folder1");
    }

    @Test
    @DisplayName("根名字解析失败时回落到 folder_token（尽力而为）")
    void listResourcesRootFallsBackToToken() {
        folders.put("folder1", List.of());
        // 不注册 meta 路由 → 404 → 客户端抛错 → 回落

        List<Resource> resources = connector().listResources(config(List.of("folder1")), "");
        assertThat(resources.get(0).getName()).isEqualTo("folder1");
    }

    @Test
    @DisplayName("没有 folder_token → 直接拒绝（ADR-0004）")
    void listResourcesRejectsMissingFolderToken() {
        assertThatThrownBy(() -> connector().listResources(config(List.of()), ""))
                .isInstanceOf(ConnectorException.class)
                .hasMessage("folder_token is required; specify a Drive folder token");
    }

    @Test
    @DisplayName("子级加载：根的直接子项 ParentID 是裸根 token，更深层用编码形式")
    void listResourcesChildren() {
        folders.put("folder1", List.of(
                fileJson("fdoc1", "Doc", "docx", "folder1", "100"),
                fileJson("fsub", "Sub", "folder", "folder1", "200")));
        folders.put("fsub", List.of(
                fileJson("fdoc2", "Inner", "sheet", "fsub", "300")));

        List<Resource> root = connector().listResources(config(List.of("folder1")), "folder1");
        assertThat(root).hasSize(2);
        Map<String, Resource> byId = new LinkedHashMap<>();
        for (Resource r : root) {
            byId.put(r.getExternalId(), r);
        }
        Resource doc = byId.get("folder1:fdoc1");
        assertThat(doc.getParentId()).isEqualTo("folder1");
        assertThat(doc.getType()).isEqualTo("docx");
        assertThat(doc.isHasChildren()).isFalse();
        assertThat(doc.getMetadata())
                .containsEntry("file_token", "fdoc1")
                .containsEntry("obj_type", "docx")
                .containsEntry("folder_token", "folder1");
        Resource sub = byId.get("folder1:fsub");
        assertThat(sub.isHasChildren()).isTrue();

        // 展开子文件夹："folder1:fsub" → 列出 fsub 的子项，父 ID 是编码形式
        List<Resource> kids = connector().listResources(config(List.of("folder1")), "folder1:fsub");
        assertThat(kids).hasSize(1);
        assertThat(kids.get(0).getExternalId()).isEqualTo("folder1:fdoc2");
        assertThat(kids.get(0).getParentId()).isEqualTo("folder1:fsub");
    }

    @Test
    @DisplayName("祖先解析：从根 BFS，共享遍历，返回自根向下的父链")
    void resolveResourceAncestors() {
        folders.put("folder1", List.of(
                fileJson("fsubA", "A", "folder", "folder1", "1"),
                fileJson("fsubB", "B", "folder", "folder1", "2")));
        folders.put("fsubA", List.of(fileJson("fdoc1", "Doc", "docx", "fsubA", "3")));
        folders.put("fsubB", List.of(fileJson("fdoc2", "Doc2", "docx", "fsubB", "4")));

        // ⚠️ 刻意钉住的怪癖（无害，不做"顺手修好"）：
        // 根文件夹自己的 resourceID 被编成 "root:root"，于是根的直接子文件夹的
        // parentChain 值是 "folder1:folder1" 而不是 "folder1"
        // ——祖先列表里会多一个无人认领的 ID。
        List<String> ancestors = connector().resolveResourceAncestors(
                config(List.of("folder1")), List.of("folder1:fdoc1"));
        assertThat(ancestors).containsExactly("folder1", "folder1:folder1");

        // 两个选中项共享同一个根：根只加一次，遍历也只跑一遍
        List<String> multi = connector().resolveResourceAncestors(
                config(List.of("folder1")), List.of("folder1:fdoc1", "folder1:fdoc2"));
        assertThat(multi).containsExactly("folder1", "folder1:folder1");

        // 根级选中项没有可展开的祖先
        assertThat(connector().resolveResourceAncestors(
                config(List.of("folder1")), List.of("folder1"))).isEmpty();
    }

    @Test
    @DisplayName("祖先解析尽力而为：列举报错时停止该根的遍历，不抛异常")
    void resolveResourceAncestorsBestEffort() {
        // 根列举直接失败（folders 里没有 folder1）
        assertThat(connector().resolveResourceAncestors(
                config(List.of("folder1")), List.of("folder1:fdoc1"))).containsExactly("folder1");
    }

    @Test
    @DisplayName("祖先链构造（buildDriveAncestorChain）")
    void buildAncestorChain() {
        Map<String, String> parentChain = new LinkedHashMap<>();
        parentChain.put("fsubA", "folder1");
        parentChain.put("fsubB", "folder1:fsubA");
        assertThat(DriveConnector.buildDriveAncestorChain("folder1", "fsubB", parentChain))
                .containsExactly("folder1", "folder1:fsubA");
        assertThat(DriveConnector.buildDriveAncestorChain("folder1", "folder1", parentChain))
                .isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // listDriveFilesForResource
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("裸根 token → 递归整棵子树（folder 递归、shortcut 展开成目标、其余收下）")
    void listFilesForRootResourceWalksSubtree() {
        folders.put("folder1", List.of(
                fileJson("fsub", "Sub", "folder", "folder1", "1"),
                fileJson("fshort", "Shortcut", "shortcut", "folder1", "2", "ftarget", "sheet"),
                fileJson("fdoc1", "Doc", "docx", "folder1", "3"),
                fileJson("fboard", "Board", "board", "folder1", "4")));
        folders.put("fsub", List.of(fileJson("fdoc2", "Inner", "sheet", "fsub", "5")));

        List<DriveFile> files = DriveConnector.listDriveFilesForResource(clientForTest(), "folder1");
        Map<String, DriveFile> byToken = new LinkedHashMap<>();
        for (DriveFile f : files) {
            byToken.put(f.getToken(), f);
        }
        // folder / board 不会被收（folder 递归、board 是未知类型由上层跳过）
        assertThat(byToken).containsKeys("ftarget", "fdoc1", "fboard", "fdoc2");
        assertThat(byToken).doesNotContainKey("fsub");
        // shortcut 展开成 target：token/type 取自 shortcut_info，其余字段沿用快捷方式
        DriveFile expanded = byToken.get("ftarget");
        assertThat(expanded.getType()).isEqualTo("sheet");
        assertThat(expanded.getName()).isEqualTo("Shortcut");
        assertThat(expanded.getParentToken()).isEqualTo("folder1");
        assertThat(expanded.getModifiedTime()).isEqualTo("2");
    }

    @Test
    @DisplayName("单文件选中：file token 不是文件夹 → 1061002 回落成「走根子树 + 过滤」")
    void listFilesForSingleFileResourceFallsBackToFiltering() {
        folders.put("folder1", List.of(
                fileJson("fdoc1", "Doc", "docx", "folder1", "100"),
                fileJson("fdoc2", "Doc2", "sheet", "folder1", "200")));
        // fdoc1 本身不是文件夹 → 列举返回 1061002

        DriveConnector c = connector();
        com.ragagent.datasource.connector.feishu.core.FeishuClient client =
                clientForTest();
        List<DriveFile> files = DriveConnector.listDriveFilesForResource(client, "folder1:fdoc1");
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getToken()).isEqualTo("fdoc1");
        // 第一次尝试（把 fdoc1 当文件夹）+ 回落后走根
        assertThat(fileListCalls).containsExactly("fdoc1", "folder1");
    }

    @Test
    @DisplayName("选中子文件夹：直接走该子文件夹的子树，不需要过滤")
    void listFilesForSubFolderResourceWalksThatFolder() {
        folders.put("folder1", List.of(fileJson("fsub", "Sub", "folder", "folder1", "1")));
        folders.put("fsub", List.of(fileJson("fdoc2", "Inner", "sheet", "fsub", "5")));

        List<DriveFile> files = DriveConnector.listDriveFilesForResource(clientForTest(),
                "folder1:fsub");
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getToken()).isEqualTo("fdoc2");
        assertThat(fileListCalls).containsExactly("fsub");
    }

    @Test
    @DisplayName("1061002 判定是子串匹配（对照 Go 的 strings.Contains）")
    void isDriveNotFolderError() {
        assertThat(DriveConnector.isDriveNotFolderError(
                new ConnectorException("list drive files error: code=1061002 msg=params error")))
                .isTrue();
        assertThat(DriveConnector.isDriveNotFolderError(
                new ConnectorException("params error"))).isTrue();
        assertThat(DriveConnector.isDriveNotFolderError(
                new ConnectorException("list drive files error: code=1050 msg=forbidden")))
                .isFalse();
    }

    private com.ragagent.datasource.connector.feishu.core.FeishuClient clientForTest() {
        return new com.ragagent.datasource.connector.feishu.core.FeishuClient(
                server.baseUrl(), "a", "s", null,
                com.ragagent.datasource.ConnectorHttp.newConnectorHttpClient(
                        java.time.Duration.ofSeconds(10)));
    }

    // ──────────────────────────────────────────────────────────────────
    // 抓取
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("云盘 docx（blocks 模式）：主 Markdown + 附件，channel=feishu_drive")
    void fetchStreamDocxBlocksMultiItem() {
        DocxFetcher.parseMode = () -> "blocks";
        String attToken = "ft-drv-att";
        byte[] attContent = repeat("x", FeishuSupport.MIN_ATTACHMENT_BYTES + 1);

        folders.put("folder1", List.of(fileJson("fdoc1", "Drive Doc", "docx", "folder1", "500")));
        server.handle("/open-apis/docx/v1/documents/fdoc1/blocks", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{\"items\":["
                        + "{\"block_id\":\"b1\",\"block_type\":1},"
                        + "{\"block_id\":\"b2\",\"block_type\":2,\"text\":{\"elements\":"
                        + "[{\"text_run\":{\"content\":\"Hello drive\"}}]}},"
                        + "{\"block_id\":\"b3\",\"block_type\":23,\"file\":{\"token\":\""
                        + attToken + "\",\"name\":\"report.pdf\"}}],"
                        + "\"has_more\":false,\"page_token\":\"\"}}"));
        server.handle("/open-apis/drive/v1/medias/", (ex, body) ->
                FeishuTestServer.sendBytes(ex, "application/octet-stream", attContent));

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        connector().fetchStream(config(List.of("folder1")), null, h);

        assertThat(h.emitted).hasSize(2);
        FetchedItem main = h.emitted.get(0);
        assertThat(main.getExternalId()).isEqualTo("fdoc1");
        assertThat(main.getContentType()).isEqualTo("text/markdown");
        assertThat(main.isReplacesSubtree()).isTrue();
        assertThat(main.getMetadata()).containsEntry("channel", "feishu_drive");
        assertThat(main.getUrl()).isEqualTo("https://example.feishu.cn/file/fdoc1");
        assertThat(new String(main.getContent(), StandardCharsets.UTF_8)).contains("Hello drive");

        assertThat(h.emitted.get(1).getExternalId()).isEqualTo("fdoc1#file#" + attToken);
        assertThat(h.emitted.get(1).getMetadata()).containsEntry("attachment", "true");
    }

    @Test
    @DisplayName("blocks 失败 → 导出回落（单条目、octet-stream、不设 ReplacesSubtree）")
    void fetchStreamDocxBlocksFailFallsBackToExport() {
        DocxFetcher.parseMode = () -> "blocks";
        folders.put("folder1", List.of(fileJson("fdoc1", "Drive Doc", "docx", "folder1", "500")));
        server.handle("/open-apis/docx/v1/documents/fdoc1/blocks", (ex, body) ->
                FeishuTestServer.sendStatus(ex, 500,
                        "{\"code\":99991400,\"msg\":\"insufficient scope\"}"));
        exportTrio("fake-drive-export-binary");

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        connector().fetchStream(config(List.of("folder1")), null, h);

        assertThat(h.emitted).hasSize(1);
        FetchedItem item = h.emitted.get(0);
        assertThat(item.getExternalId()).isEqualTo("fdoc1");
        assertThat(item.getContentType()).isEqualTo("application/octet-stream");
        assertThat(item.getFileName()).endsWith(".docx");
        assertThat(item.isReplacesSubtree()).isFalse();
    }

    @Test
    @DisplayName("blocks 渲染为空 → 也回落导出")
    void fetchStreamDocxBlocksEmptyFallsBackToExport() {
        DocxFetcher.parseMode = () -> "blocks";
        folders.put("folder1", List.of(fileJson("fdoc1", "Drive Doc", "docx", "folder1", "500")));
        server.handle("/open-apis/docx/v1/documents/fdoc1/blocks", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{\"items\":"
                        + "[{\"block_id\":\"b1\",\"block_type\":1}],"
                        + "\"has_more\":false,\"page_token\":\"\"}}"));
        exportTrio("fake-drive-export-binary");

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        connector().fetchStream(config(List.of("folder1")), null, h);

        assertThat(h.emitted).hasSize(1);
        assertThat(h.emitted.get(0).getContentType()).isEqualTo("application/octet-stream");
        assertThat(h.emitted.get(0).isReplacesSubtree()).isFalse();
    }

    @Test
    @DisplayName("多模态关闭时内嵌图片不下载也不产出，但 external_id 仍在 SubtreeKeep 里")
    void fetchStreamDocxImageMultimodalOff() {
        DocxFetcher.parseMode = () -> "blocks";
        String imgToken = "img-drv-1";
        folders.put("folder1", List.of(fileJson("fdoc1", "Drive Doc", "docx", "folder1", "500")));
        server.handle("/open-apis/docx/v1/documents/fdoc1/blocks", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{\"items\":["
                        + "{\"block_id\":\"b1\",\"block_type\":1},"
                        + "{\"block_id\":\"b2\",\"block_type\":2,\"text\":{\"elements\":"
                        + "[{\"text_run\":{\"content\":\"has image\"}}]}},"
                        + "{\"block_id\":\"b3\",\"block_type\":27,\"image\":{\"token\":\""
                        + imgToken + "\"}}],\"has_more\":false,\"page_token\":\"\"}}"));

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        connector().fetchStream(config(List.of("folder1")), null, h);

        assertThat(h.emitted).hasSize(1);
        assertThat(h.emitted.get(0).getSubtreeKeep()).contains("fdoc1#image#" + imgToken);
    }

    @Test
    @DisplayName("file 类型 → 云盘下载原文件；sheet/doc/bitable → 导出")
    void fetchDriveFileTypes() {
        folders.put("folder1", List.of(
                fileJson("fpdf", "manual.pdf", "file", "folder1", "100"),
                fileJson("fsheet", "Sheet", "sheet", "folder1", "200"),
                fileJson("fmn", "Mind", "mindnote", "folder1", "300")));
        server.handle("/open-apis/drive/v1/files/", (ex, body) -> {
            if (ex.getRequestURI().getPath().endsWith("/download")) {
                FeishuTestServer.sendBytes(ex, "application/octet-stream",
                        "pdf-bytes".getBytes(StandardCharsets.UTF_8));
                return;
            }
            FeishuTestServer.sendStatus(ex, 404, "");
        });
        exportTrio("exported-xlsx");

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        connector().fetchStream(config(List.of("folder1")), null, h);

        assertThat(h.emitted).hasSize(2);
        assertThat(h.emittedIds()).containsExactly("fpdf", "fsheet");
        FetchedItem pdf = h.emitted.get(0);
        assertThat(pdf.getFileName()).isEqualTo("manual.pdf");
        assertThat(new String(pdf.getContent(), StandardCharsets.UTF_8)).isEqualTo("pdf-bytes");
        assertThat(pdf.getCreatedAt().toEpochSecond()).isEqualTo(10L);
    }

    @Test
    @DisplayName("增量：modified_time 未变的文件跳过")
    void incrementalSkipsUnchanged() {
        folders.put("folder1", List.of(
                fileJson("fpdf", "manual.pdf", "file", "folder1", "100"),
                fileJson("fpdf2", "other.pdf", "file", "folder1", "200")));
        server.handle("/open-apis/drive/v1/files/", (ex, body) ->
                FeishuTestServer.sendBytes(ex, "application/octet-stream",
                        "pdf-bytes".getBytes(StandardCharsets.UTF_8)));

        SyncCursor cursor = FeishuTestSupport.RecordingHandler.driveCursor(
                Map.of("folder1", Map.of("fpdf", "100")));
        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        connector().fetchStream(config(List.of("folder1")), cursor, h);

        assertThat(h.emittedIds()).containsExactly("fpdf2");
    }

    @Test
    @DisplayName("删除检测：完整列举时把消失的文件标成删除")
    void detectsDeleted() {
        folders.put("folder1", List.of(
                fileJson("a.pdf", "a.pdf", "file", "folder1", "100"),
                fileJson("b.pdf", "b.pdf", "file", "folder1", "200")));
        server.handle("/open-apis/drive/v1/files/", (ex, body) ->
                FeishuTestServer.sendBytes(ex, "application/octet-stream",
                        "x".getBytes(StandardCharsets.UTF_8)));
        SyncCursor cursor = FeishuTestSupport.RecordingHandler.driveCursor(
                Map.of("folder1", Map.of("a.pdf", "100", "gone.pdf", "50")));

        FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
        connector().fetchStream(config(List.of("folder1")), cursor, h);

        boolean deleted = h.emitted.stream().anyMatch(i -> i.isDeleted()
                && "gone.pdf".equals(i.getExternalId()));
        // a.pdf 未变 → 跳过；b.pdf 新增 → 抓取；gone.pdf → 删除
        assertThat(deleted).isTrue();
        assertThat(h.emittedIds()).contains("b.pdf");
    }

    @Test
    @DisplayName("没有资源 ID → 拒绝（文案与 wiki 不同，逐字保留）")
    void emptyResourceIds() {
        assertThatThrownBy(() -> connector().fetchStream(config(List.of()), null,
                new FeishuTestSupport.RecordingHandler()))
                .isInstanceOf(ConnectorException.class)
                .hasMessage("no resource IDs (Drive folder tokens) configured");
        assertThatThrownBy(() -> connector().fetchIncremental(config(List.of()), null))
                .isInstanceOf(ConnectorException.class)
                .hasMessage("no resource IDs (Drive folder tokens) configured");
    }

    @Test
    @DisplayName("部分列举失败：不中止，失败文件夹变成错误条目（channel=feishu_drive）")
    void partialListingFailureItems() {
        // folder1 的列举在 BFS 中失败 → 直接返回 partial
        DriveConnector c = connector();
        var client = clientForTest();
        assertThatThrownBy(() -> DriveConnector.listDriveFilesForResource(client, "folder1"))
                .isInstanceOf(com.ragagent.datasource.connector.feishu.core.FeishuApiTypes
                        .PartialDriveFileListException.class);
        assertThat(c.type()).isEqualTo("feishu_drive");
    }

    @Test
    @DisplayName("listDriveFiles 拒绝空 folder token（根不可分页、不返回快捷方式）")
    void listDriveFilesRejectsEmptyFolder() {
        assertThatThrownBy(() -> clientForTest().listDriveFiles("", ""))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("root folder not supported");
        assertThatThrownBy(() -> clientForTest().getDriveFolderMeta(""))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("root folder not supported");
    }

    @Test
    @DisplayName("getDriveFolderMeta 的路径与解析")
    void getDriveFolderMeta() {
        folderMetaRoute("folder1", "我的云盘");
        var meta = clientForTest().getDriveFolderMeta("folder1");
        assertThat(meta.data().name()).isEqualTo("我的云盘");
        assertThat(meta.data().token()).isEqualTo("folder1");
    }

    @Test
    @DisplayName("引擎的 checkpoint 只写 file_times，不写 space_node_times")
    void checkpointUsesFileTimesKey() {
        SyncEngine.checkpointInterval = 1;
        try {
            folders.put("folder1", List.of(fileJson("fpdf", "a.pdf", "file", "folder1", "100")));
            server.handle("/open-apis/drive/v1/files/", (ex, body) ->
                    FeishuTestServer.sendBytes(ex, "application/octet-stream", new byte[]{1}));

            FeishuTestSupport.RecordingHandler h = new FeishuTestSupport.RecordingHandler();
            connector().fetchStream(config(List.of("folder1")), null, h);

            assertThat(h.cursorSnapshots).isNotEmpty();
            assertThat(h.cursorSnapshots.get(0)).containsKey("file_times");
            assertThat(h.cursorSnapshots.get(0)).doesNotContainKey("space_node_times");
            assertThat(h.fileTimes.get(0).get("folder1")).containsKey("fpdf");
        } finally {
            SyncEngine.checkpointInterval = 50;
        }
    }

    // ── 助手 ────────────────────────────────────────────────────────────

    private void exportTrio(String downloadContent) {
        server.handle("/open-apis/drive/v1/export_tasks", (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"msg\":\"\",\"data\":{\"ticket\":\"ticket-drv\"}}"));
        server.handle("/open-apis/drive/v1/export_tasks/ticket-drv", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{\"result\":{"
                        + "\"file_token\":\"ft-export-drv\",\"file_size\":512,\"job_status\":0,"
                        + "\"job_error_msg\":\"\",\"file_name\":\"drive-fallback.docx\"}}}"));
        server.handle("/open-apis/drive/v1/export_tasks/file/ft-export-drv/download",
                (ex, body) -> FeishuTestServer.sendBytes(ex, "application/octet-stream",
                        downloadContent.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] repeat(String s, int times) {
        return s.repeat(times).getBytes(StandardCharsets.UTF_8);
    }

    private static String queryParam(com.sun.net.httpserver.HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.isEmpty()) {
            return "";
        }
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            if (k.equals(name)) {
                return i < 0 ? "" : java.net.URLDecoder.decode(kv.substring(i + 1),
                        StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
