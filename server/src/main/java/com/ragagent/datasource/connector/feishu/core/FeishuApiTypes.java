package com.ragagent.datasource.connector.feishu.core;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 飞书 Open Platform 的响应/请求形状（对照 Go {@code core/types.go} 的 API 结构体
 * 与 {@code core/client.go} 的两个 Partial 错误载体）。
 *
 * <h2>⚠️ 全部是内部 API 形状，不是契约</h2>
 * <p>这些类型<b>只在"解析飞书返回的 JSON"这一件事上存在</b>：它们从不落 jsonb、
 * 从不进 HTTP 响应、前端永远看不到。所以它们<b>刻意不做</b>本项目的契约治理：</p>
 * <ul>
 *   <li>不需要 {@code @JsonIgnore} 的派生访问器治理（§7.5 第 2 条那类坑）；</li>
 *   <li>不需要逐字段蛇形 {@code @JsonProperty} 对齐（§7.5 第 4 条）——但这里仍按 Go 的
 *       json tag 写，因为<b>入参侧</b>是飞书的线上协议，改一个字母就读不出响应；</li>
 *   <li>不需要 {@code JsonContractRoundTripTest} 的往返条目。</li>
 * </ul>
 * <p><b>后人若要对齐契约，别拿这个文件当模板</b>——真正的契约在
 * {@code com.ragagent.datasource.domain}（那里才需要那套治理）。</p>
 *
 * <h2>为什么是 record</h2>
 * <p>Go 侧这些结构体是纯数据载体，解析完只读。record + {@code @JsonProperty}
 * 让 Jackson 走构造器属性，且天然不可变——比 Go 的公开字段更严格，不会出现
 * "解析完被下游改掉"的意外。{@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * 对齐 Go {@code json.Unmarshal} 默认忽略未知字段的行为。</p>
 *
 * <p>唯一两个可变的是 {@link WikiNode} 与 {@link DriveFile}：它们是<b>业务节点</b>而非
 * 纯响应体，连接器会在遍历中就地补字段（如 {@code parent_node_token}），照抄 Go 的可变结构体。</p>
 */
public final class FeishuApiTypes {

    private FeishuApiTypes() {
    }

    // ──────────────────────────────────────────────────────────────────
    // 通用信封
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code ApiResponse}：飞书所有响应的公共信封。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiResponse(@JsonProperty("code") int code, @JsonProperty("msg") String msg) {
    }

    /** 对照 Go {@code TokenResponse}：{@code tenant_access_token} 接口的响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenResponse(
            @JsonProperty("code") int code,
            @JsonProperty("msg") String msg,
            @JsonProperty("tenant_access_token") String tenantAccessToken,
            @JsonProperty("expire") int expire) {
    }

    // ──────────────────────────────────────────────────────────────────
    // wiki 空间 / 节点
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code WikiSpace}：一个飞书 wiki 空间。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WikiSpace(
            @JsonProperty("space_id") String spaceId,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("visibility") String visibility) {
    }

    /** 对照 Go {@code WikiSpaceListData}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WikiSpaceListData(
            @JsonProperty("items") List<WikiSpace> items,
            @JsonProperty("has_more") boolean hasMore,
            @JsonProperty("page_token") String pageToken) {
    }

    /** 对照 Go {@code WikiSpaceListResponse}：{@code GET /open-apis/wiki/v2/spaces} 的响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WikiSpaceListResponse(
            @JsonProperty("code") int code,
            @JsonProperty("msg") String msg,
            @JsonProperty("data") WikiSpaceListData data) {
    }

    /** 对照 Go {@code WikiNodeListData}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WikiNodeListData(
            @JsonProperty("items") List<WikiNode> items,
            @JsonProperty("has_more") boolean hasMore,
            @JsonProperty("page_token") String pageToken) {
    }

    /** 对照 Go {@code WikiNodeListResponse}：{@code GET .../spaces/:space_id/nodes} 的响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WikiNodeListResponse(
            @JsonProperty("code") int code,
            @JsonProperty("msg") String msg,
            @JsonProperty("data") WikiNodeListData data) {
    }

    /** 对照 Go {@code WikiNodeInfoData}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WikiNodeInfoData(@JsonProperty("node") WikiNode node) {
    }

    /** 对照 Go {@code WikiNodeInfoResponse}：{@code GET .../spaces/get_node} 的响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WikiNodeInfoResponse(
            @JsonProperty("code") int code,
            @JsonProperty("msg") String msg,
            @JsonProperty("data") WikiNodeInfoData data) {
    }

    /**
     * 对照 Go {@code WikiNode}：wiki 空间里的一个节点（文档或文件夹）。
     *
     * <p>可变（有 setter），因为 {@code ListWikiNodes} 会在遍历中就地补
     * {@code parent_node_token} 与 {@code space_id}——照抄 Go。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WikiNode {

        @JsonProperty("space_id")
        private String spaceId = "";

        @JsonProperty("node_token")
        private String nodeToken = "";

        /** 文档 token。 */
        @JsonProperty("obj_token")
        private String objToken = "";

        /** {@code doc}/{@code sheet}/{@code mindnote}/{@code bitable}/{@code file}/{@code docx}/{@code slides}。 */
        @JsonProperty("obj_type")
        private String objType = "";

        @JsonProperty("parent_node_token")
        private String parentNodeId = "";

        /** {@code origin} 或 {@code shortcut}。 */
        @JsonProperty("node_type")
        private String nodeType = "";

        @JsonProperty("origin_node_id")
        private String originNodeId = "";

        @JsonProperty("origin_space_id")
        private String originSpaceId = "";

        @JsonProperty("has_child")
        private boolean hasChild;

        @JsonProperty("title")
        private String title = "";

        @JsonProperty("creator")
        private String creator = "";

        @JsonProperty("owner")
        private String owner = "";

        /** 文档创建时间（unix 秒的字符串）。 */
        @JsonProperty("obj_create_time")
        private String objCreateTime = "";

        /** 文档最后编辑时间——跟踪<b>内容</b>变化，游标比较用的就是它。 */
        @JsonProperty("obj_edit_time")
        private String objEditTime = "";

        /** 节点创建时间。 */
        @JsonProperty("node_create_time")
        private String nodeCreateTime = "";

        /** 节点编辑时间——只跟踪节点属性（改名/移动）变化。 */
        @JsonProperty("node_edit_time")
        private String nodeEditTime = "";

        public WikiNode() {
        }

        /** 测试与短构造用：只给最常用的五个字段。 */
        public static WikiNode of(String nodeToken, String objToken, String objType, String title, String editTime) {
            WikiNode n = new WikiNode();
            n.nodeToken = nodeToken;
            n.objToken = objToken;
            n.objType = objType;
            n.title = title;
            n.objEditTime = editTime == null ? "" : editTime;
            return n;
        }

        public String getSpaceId() {
            return spaceId;
        }

        public void setSpaceId(String v) {
            spaceId = v == null ? "" : v;
        }

        public String getNodeToken() {
            return nodeToken;
        }

        public void setNodeToken(String v) {
            nodeToken = v == null ? "" : v;
        }

        public String getObjToken() {
            return objToken;
        }

        public void setObjToken(String v) {
            objToken = v == null ? "" : v;
        }

        public String getObjType() {
            return objType;
        }

        public void setObjType(String v) {
            objType = v == null ? "" : v;
        }

        public String getParentNodeId() {
            return parentNodeId;
        }

        public void setParentNodeId(String v) {
            parentNodeId = v == null ? "" : v;
        }

        public String getNodeType() {
            return nodeType;
        }

        public void setNodeType(String v) {
            nodeType = v == null ? "" : v;
        }

        public String getOriginNodeId() {
            return originNodeId;
        }

        public void setOriginNodeId(String v) {
            originNodeId = v == null ? "" : v;
        }

        public String getOriginSpaceId() {
            return originSpaceId;
        }

        public void setOriginSpaceId(String v) {
            originSpaceId = v == null ? "" : v;
        }

        public boolean isHasChild() {
            return hasChild;
        }

        public void setHasChild(boolean v) {
            hasChild = v;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String v) {
            title = v == null ? "" : v;
        }

        public String getCreator() {
            return creator;
        }

        public void setCreator(String v) {
            creator = v == null ? "" : v;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String v) {
            owner = v == null ? "" : v;
        }

        public String getObjCreateTime() {
            return objCreateTime;
        }

        public void setObjCreateTime(String v) {
            objCreateTime = v == null ? "" : v;
        }

        public String getObjEditTime() {
            return objEditTime;
        }

        public void setObjEditTime(String v) {
            objEditTime = v == null ? "" : v;
        }

        public String getNodeCreateTime() {
            return nodeCreateTime;
        }

        public void setNodeCreateTime(String v) {
            nodeCreateTime = v == null ? "" : v;
        }

        public String getNodeEditTime() {
            return nodeEditTime;
        }

        public void setNodeEditTime(String v) {
            nodeEditTime = v == null ? "" : v;
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 文档原始内容（已废弃路径）
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code docRawContentData}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocRawContentData(@JsonProperty("content") String content) {
    }

    /** 对照 Go {@code docRawContentResponse}（{@code .../documents/:id/raw_content}）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocRawContentResponse(
            @JsonProperty("code") int code,
            @JsonProperty("msg") String msg,
            @JsonProperty("data") DocRawContentData data) {
    }

    // ──────────────────────────────────────────────────────────────────
    // 导出任务 API
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code ExportTaskCreateData}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportTaskCreateData(@JsonProperty("ticket") String ticket) {
    }

    /** 对照 Go {@code ExportTaskCreateResponse}：{@code POST /drive/v1/export_tasks} 的响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportTaskCreateResponse(
            @JsonProperty("code") int code,
            @JsonProperty("msg") String msg,
            @JsonProperty("data") ExportTaskCreateData data) {
    }

    /**
     * 对照 Go {@code ExportTaskResult}。
     *
     * @param jobStatus {@code 0}=成功、{@code 1}=初始化中、{@code 2}=处理中
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportTaskResult(
            @JsonProperty("file_token") String fileToken,
            @JsonProperty("file_size") long fileSize,
            @JsonProperty("job_status") int jobStatus,
            @JsonProperty("job_error_msg") String jobErrorMsg,
            @JsonProperty("file_name") String fileName) {
    }

    /** 对照 Go {@code ExportTaskStatusData}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportTaskStatusData(@JsonProperty("result") ExportTaskResult result) {
    }

    /** 对照 Go {@code ExportTaskStatusResponse}：{@code GET /drive/v1/export_tasks/:ticket} 的响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportTaskStatusResponse(
            @JsonProperty("code") int code,
            @JsonProperty("msg") String msg,
            @JsonProperty("data") ExportTaskStatusData data) {
    }

    // ──────────────────────────────────────────────────────────────────
    // Drive（云盘）文件
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code driveFileMeta}：{@code drive/v1/metas} 里的一条。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriveFileMeta(
            @JsonProperty("doc_token") String docToken,
            @JsonProperty("doc_type") String docType,
            @JsonProperty("title") String title) {
    }

    /** 对照 Go {@code driveFileMetaData}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriveFileMetaData(@JsonProperty("metas") List<DriveFileMeta> metas) {
    }

    /** 对照 Go {@code driveFileMetaResponse}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriveFileMetaResponse(
            @JsonProperty("code") int code,
            @JsonProperty("msg") String msg,
            @JsonProperty("data") DriveFileMetaData data) {
    }

    /** 对照 Go {@code driveShortcutInfo}：云盘快捷方式指向的目标。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriveShortcutInfo(
            @JsonProperty("target_token") String targetToken,
            @JsonProperty("target_type") String targetType) {
    }

    /**
     * 对照 Go {@code DriveFile}：云盘里的一个文件/文件夹。
     *
     * <p>可变（照抄 Go 的结构体），因为快捷方式展开时会现场造一个新的（见
     * {@code listDriveFilesRecursiveFrom}）。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DriveFile {

        @JsonProperty("token")
        private String token = "";

        @JsonProperty("name")
        private String name = "";

        /** {@code doc}/{@code docx}/{@code sheet}/{@code bitable}/{@code file}/{@code folder}/{@code shortcut}/… */
        @JsonProperty("type")
        private String type = "";

        @JsonProperty("parent_token")
        private String parentToken = "";

        @JsonProperty("url")
        private String url = "";

        /** unix 秒的字符串。 */
        @JsonProperty("created_time")
        private String createdTime = "";

        /** unix 秒的字符串——等价知识库的 {@code obj_edit_time}，游标比较用它。 */
        @JsonProperty("modified_time")
        private String modifiedTime = "";

        @JsonProperty("owner_id")
        private String ownerId = "";

        /**
         * 只有 {@code type == "shortcut"} 时才有值。target_type 只可能是
         * doc/sheet/mindnote/bitable/file/docx（飞书不允许快捷方式指向文件夹，已实测）。
         */
        @JsonProperty("shortcut_info")
        private DriveShortcutInfo shortcutInfo;

        public DriveFile() {
        }

        public String getToken() {
            return token;
        }

        public void setToken(String v) {
            token = v == null ? "" : v;
        }

        public String getName() {
            return name;
        }

        public void setName(String v) {
            name = v == null ? "" : v;
        }

        public String getType() {
            return type;
        }

        public void setType(String v) {
            type = v == null ? "" : v;
        }

        public String getParentToken() {
            return parentToken;
        }

        public void setParentToken(String v) {
            parentToken = v == null ? "" : v;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String v) {
            url = v == null ? "" : v;
        }

        public String getCreatedTime() {
            return createdTime;
        }

        public void setCreatedTime(String v) {
            createdTime = v == null ? "" : v;
        }

        public String getModifiedTime() {
            return modifiedTime;
        }

        public void setModifiedTime(String v) {
            modifiedTime = v == null ? "" : v;
        }

        public String getOwnerId() {
            return ownerId;
        }

        public void setOwnerId(String v) {
            ownerId = v == null ? "" : v;
        }

        public DriveShortcutInfo getShortcutInfo() {
            return shortcutInfo;
        }

        public void setShortcutInfo(DriveShortcutInfo v) {
            shortcutInfo = v;
        }
    }

    /** 对照 Go {@code DriveFileListData}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriveFileListData(
            @JsonProperty("files") List<DriveFile> files,
            @JsonProperty("has_more") boolean hasMore,
            @JsonProperty("next_page_token") String nextPageToken) {
    }

    /** 对照 Go {@code DriveFileListResponse}：{@code GET /open-apis/drive/v1/files} 的响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriveFileListResponse(
            @JsonProperty("code") int code,
            @JsonProperty("msg") String msg,
            @JsonProperty("data") DriveFileListData data) {
    }

    /** 对照 Go {@code driveFolderMetaData}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriveFolderMetaData(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("token") String token,
            @JsonProperty("createUid") String createUid,
            @JsonProperty("editUid") String editUid,
            @JsonProperty("parentId") String parentId,
            @JsonProperty("ownUid") String ownUid) {
    }

    /**
     * 对照 Go {@code driveFolderMetaResponse}：{@code GET /open-apis/drive/explorer/v2/folder/:folderToken/meta}。
     *
     * <p>用来解析<b>根文件夹</b>的人类可读名字——列表 API 只返回文件夹的子项，不返回它自己。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriveFolderMetaResponse(
            @JsonProperty("code") int code,
            @JsonProperty("msg") String msg,
            @JsonProperty("data") DriveFolderMetaData data) {
    }

    // ──────────────────────────────────────────────────────────────────
    // 部分失败载体（对照 Go 的 Partial*Error + ListFailure）
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code WikiNodeListFailure}：某棵子树的列举失败。 */
    public record WikiNodeListFailure(WikiNode node, RuntimeException err) {
    }

    /**
     * 对照 Go {@code PartialWikiNodeListError}：逐子树的列举失败聚合。
     *
     * <p>Go 用它表达"<b>部分成功</b>"：{@code List} 的三个返回值里第二个非空、
     * 第三个为空——nodes 仍然可用，同步继续，只是失败的子树经
     * {@code ListFailureItems} 变成错误条目。Java 侧用异常继承表达同一条控制流，
     * 但<b>异常携带结果</b>（{@link #getNodes()}），调用方 catch 后照常使用。</p>
     *
     * <p>{@link #getMessage()} 与 Go 的 {@code Error()} 逐字一致：无失败时是
     * {@code "partial wiki node listing failed"}，否则是各失败 Error() 用 {@code "; "} 连接。</p>
     */
    public static class PartialWikiNodeListException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final List<WikiNode> nodes;
        private final List<WikiNodeListFailure> failures;

        public PartialWikiNodeListException(List<WikiNode> nodes, List<WikiNodeListFailure> failures) {
            super(buildMessage(failures));
            this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
            this.failures = failures == null ? List.of() : List.copyOf(failures);
        }

        /** 已经成功列举出来的节点（不含失败的子树）——部分结果照样可用。 */
        public List<WikiNode> getNodes() {
            return nodes;
        }

        public List<WikiNodeListFailure> getFailures() {
            return failures;
        }

        private static String buildMessage(List<WikiNodeListFailure> failures) {
            if (failures == null || failures.isEmpty()) {
                return "partial wiki node listing failed";
            }
            StringBuilder sb = new StringBuilder();
            for (WikiNodeListFailure f : failures) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(f.err() == null ? "" : f.err().getMessage());
            }
            return sb.toString();
        }
    }

    /** 对照 Go {@code DriveFileListFailure}：某个子文件夹的列举失败。 */
    public record DriveFileListFailure(String folderToken, RuntimeException err) {
    }

    /**
     * 对照 Go {@code PartialDriveFileListError}——{@link PartialWikiNodeListException} 的云盘版，
     * 语义逐条相同（部分结果可用、消息格式一致）。
     */
    public static class PartialDriveFileListException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final List<DriveFile> files;
        private final List<DriveFileListFailure> failures;

        public PartialDriveFileListException(List<DriveFile> files, List<DriveFileListFailure> failures) {
            super(buildMessage(failures));
            this.files = files == null ? List.of() : List.copyOf(files);
            this.failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public List<DriveFile> getFiles() {
            return files;
        }

        public List<DriveFileListFailure> getFailures() {
            return failures;
        }

        private static String buildMessage(List<DriveFileListFailure> failures) {
            if (failures == null || failures.isEmpty()) {
                return "partial drive file listing failed";
            }
            StringBuilder sb = new StringBuilder();
            for (DriveFileListFailure f : failures) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(f.err() == null ? "" : f.err().getMessage());
            }
            return sb.toString();
        }
    }
}
