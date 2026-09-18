package com.ragagent.datasource.connector.ima;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * IMA OpenAPI 的请求/响应形状（对照 Go {@code ima/types.go} 的
 * {@code apiEnvelope} / {@code knowledgeBaseInfo} / {@code folderInfo} /
 * {@code getKnowledgeListResp} 等一整组类型）。
 *
 * <h2>⚠️ 内部 API 形状，不是契约</h2>
 * <p>这些类型只用于**解码 IMA 的响应**与在连接器内部传递数据：它们既不作
 * HTTP 响应体、也不落 jsonb。所以这里<b>不挂</b>
 * {@code GoMapSerializer} / {@code GoDoubleSerializer}，也<b>不是</b>
 * 「键序即契约」的那一类。真正的契约类型是
 * {@link com.ragagent.datasource.domain.Resource} /
 * {@link com.ragagent.datasource.domain.FetchedItem} /
 * {@link com.ragagent.datasource.domain.SyncCursor}。</p>
 *
 * <h2>{@code knowledge_list} 是混合数组</h2>
 * <p>IMA 把文件夹与知识条目塞进同一个数组（见 {@code getKnowledgeListResp}），
 * Go 用 {@code []json.RawMessage} 承接再逐项探测 {@code folder_id}/{@code media_id}。
 * Java 侧用 {@code List<JsonNode>}，探测逻辑一字不差地照抄（约定 §9「内部 API DTO」）。</p>
 */
public final class ImaApiTypes {

    private ImaApiTypes() {
    }

    // ── 信封 ──────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code apiEnvelope}：IMA 的统一响应包装 {@code { code, msg, data }}。
     * 非零 code 是业务错误，必须透给用户。
     *
     * <p>部分公开的 IMA 资料把同一信封写成 {@code { retcode, errmsg }}。
     * 两种拼法都接受不花什么代价，却能避免"拼错的那个让 Code 留在零值、
     * 于是每个 API 错误都被静默当成成功"这种故障。</p>
     *
     * <p>{@link #statusCode()} 与 {@link #message()} 是对照 Go 的**方法**，
     * 名字不带 {@code get}/{@code is} 前缀，Jackson 不会把它们当属性——
     * 而本类型也只用于解码，不写出。</p>
     */
    public static class ApiEnvelope {

        @JsonProperty("code")
        private int code;

        @JsonProperty("msg")
        private String msg = "";

        /** 指针语义：只有 JSON 里真的出现 {@code retcode} 才参与取值。 */
        @JsonProperty("retcode")
        private Integer retErr;

        @JsonProperty("errmsg")
        private String errMsg = "";

        @JsonProperty("data")
        private JsonNode data;

        public int getCode() {
            return code;
        }

        public void setCode(int v) {
            code = v;
        }

        public String getMsg() {
            return msg;
        }

        public void setMsg(String v) {
            msg = v == null ? "" : v;
        }

        public Integer getRetErr() {
            return retErr;
        }

        public void setRetErr(Integer v) {
            retErr = v;
        }

        public String getErrMsg() {
            return errMsg;
        }

        public void setErrMsg(String v) {
            errMsg = v == null ? "" : v;
        }

        public JsonNode getData() {
            return data;
        }

        public void setData(JsonNode v) {
            data = v;
        }

        /**
         * 对照 Go {@code apiEnvelope.statusCode}：两种拼法下的业务状态码。
         * {@code Code != 0} 优先；否则看指针形式的 {@code retcode} 是否存在。
         */
        public int statusCode() {
            if (code != 0) {
                return code;
            }
            if (retErr != null) {
                return retErr;
            }
            return 0;
        }

        /** 对照 Go {@code apiEnvelope.message}：{@code msg} 非空优先，否则 {@code errmsg}。 */
        public String message() {
            if (msg != null && !msg.isEmpty()) {
                return msg;
            }
            return errMsg == null ? "" : errMsg;
        }
    }

    // ── 知识库 ────────────────────────────────────────────────────────────

    /** 对照 Go {@code knowledgeBaseInfo}（{@code get_knowledge_base} 的条目）。 */
    public static class KnowledgeBaseInfo {

        @JsonProperty("id")
        private String id = "";

        @JsonProperty("name")
        private String name = "";

        @JsonProperty("cover_url")
        private String coverUrl = "";

        @JsonProperty("description")
        private String description = "";

        @JsonProperty("recommended_questions")
        private List<String> recommendedQuestions;

        public String getId() {
            return id;
        }

        public void setId(String v) {
            id = v == null ? "" : v;
        }

        public String getName() {
            return name;
        }

        public void setName(String v) {
            name = v == null ? "" : v;
        }

        public String getCoverUrl() {
            return coverUrl;
        }

        public void setCoverUrl(String v) {
            coverUrl = v == null ? "" : v;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String v) {
            description = v == null ? "" : v;
        }

        public List<String> getRecommendedQuestions() {
            return recommendedQuestions;
        }

        public void setRecommendedQuestions(List<String> v) {
            recommendedQuestions = v;
        }
    }

    /** 对照 Go {@code searchedKnowledgeBaseInfo}：{@code search_knowledge_base} 返回的字段更少。 */
    public static class SearchedKnowledgeBaseInfo {

        @JsonProperty("id")
        private String id = "";

        @JsonProperty("name")
        private String name = "";

        @JsonProperty("cover_url")
        private String coverUrl = "";

        public String getId() {
            return id;
        }

        public void setId(String v) {
            id = v == null ? "" : v;
        }

        public String getName() {
            return name;
        }

        public void setName(String v) {
            name = v == null ? "" : v;
        }

        public String getCoverUrl() {
            return coverUrl;
        }

        public void setCoverUrl(String v) {
            coverUrl = v == null ? "" : v;
        }
    }

    /** 对照 Go {@code searchKnowledgeBaseResp}。 */
    public static class SearchKnowledgeBaseResp {

        @JsonProperty("info_list")
        private List<SearchedKnowledgeBaseInfo> infoList;

        @JsonProperty("is_end")
        private boolean end;

        @JsonProperty("next_cursor")
        private String nextCursor = "";

        public List<SearchedKnowledgeBaseInfo> getInfoList() {
            return infoList;
        }

        public void setInfoList(List<SearchedKnowledgeBaseInfo> v) {
            infoList = v;
        }

        public boolean isEnd() {
            return end;
        }

        public void setEnd(boolean v) {
            end = v;
        }

        public String getNextCursor() {
            return nextCursor;
        }

        public void setNextCursor(String v) {
            nextCursor = v == null ? "" : v;
        }
    }

    /** 对照 Go {@code addableKnowledgeBaseInfo}：只有 id + name（没有 cover_url）。 */
    public static class AddableKnowledgeBaseInfo {

        @JsonProperty("id")
        private String id = "";

        @JsonProperty("name")
        private String name = "";

        public String getId() {
            return id;
        }

        public void setId(String v) {
            id = v == null ? "" : v;
        }

        public String getName() {
            return name;
        }

        public void setName(String v) {
            name = v == null ? "" : v;
        }
    }

    /**
     * 对照 Go {@code getAddableKnowledgeBaseListResp}。
     *
     * <p>JSON 键是 {@code addable_knowledge_base_list}，<b>不是</b>
     * {@code info_list}——两个端点的形状刻意不同，别顺手统一。</p>
     */
    public static class GetAddableKnowledgeBaseListResp {

        @JsonProperty("addable_knowledge_base_list")
        private List<AddableKnowledgeBaseInfo> addableKnowledgeBaseList;

        @JsonProperty("is_end")
        private boolean end;

        @JsonProperty("next_cursor")
        private String nextCursor = "";

        public List<AddableKnowledgeBaseInfo> getAddableKnowledgeBaseList() {
            return addableKnowledgeBaseList;
        }

        public void setAddableKnowledgeBaseList(List<AddableKnowledgeBaseInfo> v) {
            addableKnowledgeBaseList = v;
        }

        public boolean isEnd() {
            return end;
        }

        public void setEnd(boolean v) {
            end = v;
        }

        public String getNextCursor() {
            return nextCursor;
        }

        public void setNextCursor(String v) {
            nextCursor = v == null ? "" : v;
        }
    }

    /** 对照 Go {@code getKnowledgeBaseResp}：按 id 建索引的 map。 */
    public static class GetKnowledgeBaseResp {

        @JsonProperty("infos")
        private Map<String, KnowledgeBaseInfo> infos;

        public Map<String, KnowledgeBaseInfo> getInfos() {
            return infos;
        }

        public void setInfos(Map<String, KnowledgeBaseInfo> v) {
            infos = v;
        }
    }

    // ── 知识条目 / 文件夹 ────────────────────────────────────────────────

    /**
     * 对照 Go {@code knowledgeInfo}。
     *
     * <p><b>IMA 的列表响应不暴露 {@code media_type}</b>——只能靠
     * {@code get_media_info} 才知道；文件夹则靠"非空 {@code folder_id}"区分
     * （见 {@code folderInfo}）。{@code media_type} 的 tag 带 omitempty，
     * 所以 0 时省略（本类型只用于解码，这条只为形状对齐）。</p>
     */
    public static class KnowledgeInfo {

        @JsonProperty("media_id")
        private String mediaId = "";

        @JsonProperty("title")
        private String title = "";

        @JsonProperty("parent_folder_id")
        private String parentFolderId = "";

        @JsonProperty("media_type")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private int mediaType;

        public String getMediaId() {
            return mediaId;
        }

        public void setMediaId(String v) {
            mediaId = v == null ? "" : v;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String v) {
            title = v == null ? "" : v;
        }

        public String getParentFolderId() {
            return parentFolderId;
        }

        public void setParentFolderId(String v) {
            parentFolderId = v == null ? "" : v;
        }

        public int getMediaType() {
            return mediaType;
        }

        public void setMediaType(int v) {
            mediaType = v;
        }
    }

    /**
     * 对照 Go {@code folderInfo}。
     *
     * <p>{@code file_number} / {@code folder_number} 在 Go 里是
     * {@code json.Number}——能同时吃 JSON 数字与字符串，且 {@code Int64()}
     * 解析失败时回 0。Java 侧用 {@link String} 承接（Jackson 对数字→字符串的
     * 强制转换是默认开启的），转换语义在 {@link #fileCount()} /
     * {@link #folderCount()} 里照抄。</p>
     */
    public static class FolderInfo {

        @JsonProperty("folder_id")
        private String folderId = "";

        @JsonProperty("name")
        private String name = "";

        @JsonProperty("file_number")
        private String fileNumber = "";

        @JsonProperty("folder_number")
        private String folderNumber = "";

        @JsonProperty("parent_folder_id")
        private String parentFolderId = "";

        /**
         * 字段名去掉 {@code is} 前缀（对照 Go 的 {@code IsTop}）——否则
         * Jackson 会给字段与 getter 生成两个不同的隐式属性名（约定 §9
         * 「同族的第二种形态」）。本类型不写出，但保持一致以免后续被复用成契约。
         */
        @JsonProperty("is_top")
        private boolean top;

        public String getFolderId() {
            return folderId;
        }

        public void setFolderId(String v) {
            folderId = v == null ? "" : v;
        }

        public String getName() {
            return name;
        }

        public void setName(String v) {
            name = v == null ? "" : v;
        }

        public String getFileNumber() {
            return fileNumber;
        }

        public void setFileNumber(String v) {
            fileNumber = v == null ? "" : v;
        }

        public String getFolderNumber() {
            return folderNumber;
        }

        public void setFolderNumber(String v) {
            folderNumber = v == null ? "" : v;
        }

        public String getParentFolderId() {
            return parentFolderId;
        }

        public void setParentFolderId(String v) {
            parentFolderId = v == null ? "" : v;
        }

        public boolean isTop() {
            return top;
        }

        public void setTop(boolean v) {
            top = v;
        }

        /** 对照 Go {@code folderInfo.FileCount}：空串或非法一律回 0。 */
        public long fileCount() {
            return parseIntOrZero(fileNumber);
        }

        /** 对照 Go {@code folderInfo.FolderCount}：空串或非法一律回 0。 */
        public long folderCount() {
            return parseIntOrZero(folderNumber);
        }

        private static long parseIntOrZero(String raw) {
            if (raw == null || raw.isEmpty()) {
                return 0;
            }
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /**
     * 对照 Go {@code getKnowledgeListResp}：{@code knowledge_list} 是
     * <b>文件夹与知识条目混在一起</b>的松散数组，所以用 {@link JsonNode} 承接。
     */
    public static class GetKnowledgeListResp {

        @JsonProperty("knowledge_list")
        private List<JsonNode> knowledgeList;

        @JsonProperty("is_end")
        private boolean end;

        @JsonProperty("next_cursor")
        private String nextCursor = "";

        @JsonProperty("current_path")
        private List<FolderInfo> currentPath;

        public List<JsonNode> getKnowledgeList() {
            return knowledgeList;
        }

        public void setKnowledgeList(List<JsonNode> v) {
            knowledgeList = v;
        }

        public boolean isEnd() {
            return end;
        }

        public void setEnd(boolean v) {
            end = v;
        }

        public String getNextCursor() {
            return nextCursor;
        }

        public void setNextCursor(String v) {
            nextCursor = v == null ? "" : v;
        }

        public List<FolderInfo> getCurrentPath() {
            return currentPath;
        }

        public void setCurrentPath(List<FolderInfo> v) {
            currentPath = v;
        }
    }

    // ── 媒体信息 / 笔记正文 ──────────────────────────────────────────────

    /** 对照 Go {@code urlInfo}：URL 形式的媒体才带它。 */
    public static class UrlInfo {

        @JsonProperty("url")
        private String url = "";

        @JsonProperty("headers")
        private Map<String, String> headers;

        public String getUrl() {
            return url;
        }

        public void setUrl(String v) {
            url = v == null ? "" : v;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> v) {
            headers = v;
        }
    }

    /** 对照 Go {@code notebookExtInfo}：笔记（{@code media_type=11}）上才设置。 */
    public static class NotebookExtInfo {

        @JsonProperty("notebook_id")
        private String notebookId = "";

        public String getNotebookId() {
            return notebookId;
        }

        public void setNotebookId(String v) {
            notebookId = v == null ? "" : v;
        }
    }

    /**
     * 对照 Go {@code getMediaInfoResp}。
     *
     * <p>Go 的 {@code URLInfo} / {@code NotebookExtInfo} 是**值类型**——缺字段
     * 就是零值结构体，不是 null。Java 侧同理在字段上初始化，避免调用方到处判空。</p>
     */
    public static class GetMediaInfoResp {

        @JsonProperty("media_type")
        private int mediaType;

        @JsonProperty("url_info")
        private UrlInfo urlInfo = new UrlInfo();

        @JsonProperty("notebook_ext_info")
        private NotebookExtInfo notebookExtInfo = new NotebookExtInfo();

        public int getMediaType() {
            return mediaType;
        }

        public void setMediaType(int v) {
            mediaType = v;
        }

        public UrlInfo getUrlInfo() {
            return urlInfo;
        }

        public void setUrlInfo(UrlInfo v) {
            urlInfo = v == null ? new UrlInfo() : v;
        }

        public NotebookExtInfo getNotebookExtInfo() {
            return notebookExtInfo;
        }

        public void setNotebookExtInfo(NotebookExtInfo v) {
            notebookExtInfo = v == null ? new NotebookExtInfo() : v;
        }
    }

    /**
     * 对照 Go {@code getDocContentResp}：{@code get_doc_content} 的正文。
     * 带 {@code target_content_format=0} 时是纯文本。
     */
    public static class GetDocContentResp {

        @JsonProperty("content")
        private String content = "";

        public String getContent() {
            return content;
        }

        public void setContent(String v) {
            content = v == null ? "" : v;
        }
    }

    /**
     * 对照 Go {@code walkedFile}：解析完文件夹位置之后的内部条目表示
     * （Go 用**内嵌** {@code knowledgeInfo} 做字段提升，Java 用继承表达）。
     */
    public static class WalkedFile extends KnowledgeInfo {

        /**
         * 所在文件夹的 {@code "/A/B"} 风格路径（文件夹名，无前导斜杠）；
         * 根级条目为空串。
         */
        private String folderPath = "";

        public String getFolderPath() {
            return folderPath;
        }

        public void setFolderPath(String v) {
            folderPath = v == null ? "" : v;
        }
    }
}
