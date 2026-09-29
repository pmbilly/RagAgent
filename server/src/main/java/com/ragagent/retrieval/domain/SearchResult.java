package com.ragagent.retrieval.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoDoubleSerializer;
import com.ragagent.common.web.GoMapSerializer;

/**
 * 检索结果条目（对照 Go {@code types.SearchResult}，internal/types/search.go:151-206）。
 *
 * <p>检索模块本体属阶段 7，本类**现在**落地是因为它已经是共享契约：
 * {@link com.ragagent.llm.domain.StreamResponse#getKnowledgeReferences()} 与
 * {@code Message.knowledge_references} 都是 {@code References = []*SearchResult}，
 * SSE 的 {@code references} 事件要按它的字段序逐字节输出。</p>
 *
 * <h2>字段序 = Go struct 声明序</h2>
 * <p>struct 响应按声明序输出（约定 §9），{@link JsonPropertyOrder} 必须与下方字段声明、
 * 以及 Go 的结构体顺序三者一致。</p>
 *
 * <h2>零值语义（逐字段对照 json tag）</h2>
 * <ul>
 *   <li><b>无 omitempty → 恒输出，nil 输出 {@code null}</b>：
 *       {@code sub_chunk_id}（nil slice → {@code null}）、{@code metadata}（nil map → {@code null}）。</li>
 *   <li><b>无 omitempty 的数值/字符串 → 恒输出零值</b>：{@code match_type} 输出 {@code 0}、
 *       {@code score} 输出 {@code 0}、{@code chunk_type} 输出 {@code ""}。</li>
 *   <li><b>有 omitempty → 空时省略整个键</b>：{@code chunk_metadata} /
 *       {@code matched_content} / {@code knowledge_description} /
 *       {@code knowledge_custom_metadata} / {@code knowledge_base_id}。</li>
 * </ul>
 *
 * <h2>两个 {@code json:"-"} 字段</h2>
 * <p>{@code ContentRevision} / {@code ContentRewritten} 是合并管线内部字段：
 * <b>不出响应</b>（{@code @JsonIgnore}），但 {@code content_revision} 有 gorm 列，
 * 直查行时要能落进对象。故用 {@code @JsonIgnore} 而非删字段。</p>
 *
 * <h2>{@code score} 的浮点输出</h2>
 * <p>Go 的 {@code float64} 走 encoding/json 的专用格式化（'f' 最短表示，
 * 绝对值 &lt; 1e-6 或 ≥ 1e21 时转 'e'）——与 Jackson 默认的
 * {@code Double.toString} 不同（{@code 1} vs {@code 1.0}，{@code 1e+21} vs {@code 1.0E21}）。
 * 由 {@link GoDoubleSerializer} 复刻，见该类注释。</p>
 */
@JsonPropertyOrder({
        "id", "content", "knowledge_id", "chunk_index", "knowledge_title",
        "start_at", "end_at", "seq", "score", "match_type", "sub_chunk_id", "metadata",
        "chunk_type", "parent_chunk_id", "image_info", "knowledge_filename",
        "knowledge_source", "knowledge_channel", "chunk_metadata", "matched_content",
        "knowledge_description", "knowledge_custom_metadata", "knowledge_base_id"
})
public class SearchResult {

    private String id = "";

    private String content = "";

    @JsonAlias("knowledge_id")
    private String knowledgeId = "";

    @JsonAlias("chunk_index")
    private int chunkIndex;

    @JsonAlias("knowledge_title")
    private String knowledgeTitle = "";

    @JsonAlias("start_at")
    private int startAt;

    @JsonAlias("end_at")
    private int endAt;

    private int seq;

    /** 相似度/融合分。**无 omitempty**：{@code 0} 恒输出（不是 {@code 0.0}，见类注释）。 */
    @JsonSerialize(using = GoDoubleSerializer.class)
    private double score;

    /**
     * 匹配算法（Go {@code MatchType} 是 int 枚举，零值 {@code MatchTypeEmbedding}）。
     * 无 omitempty，恒输出数字。
     */
    @JsonAlias("match_type")
    private int matchType;

    /** 子 chunk ID。**无 omitempty**：nil 输出 {@code null}（不是 {@code []}）。 */
    @JsonAlias("sub_chunk_id")
    private List<String> subChunkId;

    /**
     * 元数据。**无 omitempty**：nil 输出 {@code null}。
     * Go 的 {@code map[string]string} 经 {@code json.Marshal} **恒按 key 字母序**输出，
     * 故 setter 归一化为 {@link TreeMap}——无论产出方给的是什么 Map 实现，输出字节都一致。
     */
    private Map<String, String> metadata;

    @JsonAlias("chunk_type")
    private String chunkType = "";

    @JsonAlias("parent_chunk_id")
    private String parentChunkId = "";

    @JsonAlias("image_info")
    private String imageInfo = "";

    @JsonAlias("knowledge_filename")
    private String knowledgeFilename = "";

    @JsonAlias("knowledge_source")
    private String knowledgeSource = "";

    @JsonAlias("knowledge_channel")
    private String knowledgeChannel = "";

    /**
     * chunk 级元数据（如生成的问题）。Go 是 {@code JSON = json.RawMessage} + omitempty：
     * 原样内联，nil/空 时省略。
     *
     * <p>已知边界：Go 的 omitempty 判据是 {@code len(bytes)==0}，所以一个字面量
     * {@code {}} 是**会**输出的；Jackson 的 NON_NULL 只看 null。产出方若真写入空对象，
     * 两侧会有差异——实际语义里该字段要么是结构化 JSON 要么不设，暂不复刻这个角落里。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonAlias("chunk_metadata")
    private JsonNode chunkMetadata;

    /** 向量检索实际命中的文本（FAQ 场景是命中的问题）。omitempty。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonAlias("matched_content")
    private String matchedContent;

    /** 知识条目描述。omitempty。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonAlias("knowledge_description")
    private String knowledgeDescription;

    /** 用户自撰、可安全下发给模型的上下文。omitempty。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonAlias("knowledge_custom_metadata")
    private String knowledgeCustomMetadata;

    /** 所属知识库 ID。omitempty。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonAlias("knowledge_base_id")
    private String knowledgeBaseId;

    /** 检索时 chunk 的编辑版本号。**仅内部**（Go 是 {@code json:"-"}），有 gorm 列。 */
    @JsonIgnore
    private int contentRevision;

    /** 合并管线是否改写过 {@code content}。**仅内部**（Go 是 {@code json:"-"}）。 */
    @JsonIgnore
    private boolean contentRewritten;

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }

    public String getContent() { return content; }
    public void setContent(String v) { content = v == null ? "" : v; }

    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { knowledgeId = v == null ? "" : v; }

    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int v) { chunkIndex = v; }

    public String getKnowledgeTitle() { return knowledgeTitle; }
    public void setKnowledgeTitle(String v) { knowledgeTitle = v == null ? "" : v; }

    public int getStartAt() { return startAt; }
    public void setStartAt(int v) { startAt = v; }

    public int getEndAt() { return endAt; }
    public void setEndAt(int v) { endAt = v; }

    public int getSeq() { return seq; }
    public void setSeq(int v) { seq = v; }

    public double getScore() { return score; }
    public void setScore(double v) { score = v; }

    public int getMatchType() { return matchType; }
    public void setMatchType(int v) { matchType = v; }

    public List<String> getSubChunkId() { return subChunkId; }
    public void setSubChunkId(List<String> v) { subChunkId = v; }

    public Map<String, String> getMetadata() { return metadata; }

    /** 归一化为按 Go 键序（UTF-8 字节序）排好的 {@link TreeMap}，见字段注释。 */
    public void setMetadata(Map<String, String> v) {
        if (v == null) {
            metadata = null;
            return;
        }
        TreeMap<String, String> sorted = new TreeMap<>(GoMapSerializer.GO_KEY_ORDER);
        sorted.putAll(v);
        metadata = sorted;
    }

    public String getChunkType() { return chunkType; }
    public void setChunkType(String v) { chunkType = v == null ? "" : v; }

    public String getParentChunkId() { return parentChunkId; }
    public void setParentChunkId(String v) { parentChunkId = v == null ? "" : v; }

    public String getImageInfo() { return imageInfo; }
    public void setImageInfo(String v) { imageInfo = v == null ? "" : v; }

    public String getKnowledgeFilename() { return knowledgeFilename; }
    public void setKnowledgeFilename(String v) { knowledgeFilename = v == null ? "" : v; }

    public String getKnowledgeSource() { return knowledgeSource; }
    public void setKnowledgeSource(String v) { knowledgeSource = v == null ? "" : v; }

    public String getKnowledgeChannel() { return knowledgeChannel; }
    public void setKnowledgeChannel(String v) { knowledgeChannel = v == null ? "" : v; }

    public JsonNode getChunkMetadata() { return chunkMetadata; }
    public void setChunkMetadata(JsonNode v) { chunkMetadata = v; }

    public String getMatchedContent() { return matchedContent; }
    public void setMatchedContent(String v) { matchedContent = v; }

    public String getKnowledgeDescription() { return knowledgeDescription; }
    public void setKnowledgeDescription(String v) { knowledgeDescription = v; }

    public String getKnowledgeCustomMetadata() { return knowledgeCustomMetadata; }
    public void setKnowledgeCustomMetadata(String v) { knowledgeCustomMetadata = v; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { knowledgeBaseId = v == null ? "" : v; }

    /**
     * 浅拷贝（对照 Go 的 {@code rewritten := *ref}）。
     *
     * <p>{@code Rewriter.CopyReferences} 用它来"复制后再就地改写"，因为 SSE 的 references
     * 载荷与流的重放缓冲、以及正在落库的助手消息**共享同一批 {@code *SearchResult} 指针**——
     * 就地改写会把那两处一起弄坏。</p>
     *
     * <p>名字不是 {@code getXxx}/{@code isXxx}，Jackson 不会把它当属性——这正是要的。</p>
     */
    public SearchResult copy() {
        SearchResult c = new SearchResult();
        c.id = id;
        c.content = content;
        c.knowledgeId = knowledgeId;
        c.chunkIndex = chunkIndex;
        c.knowledgeTitle = knowledgeTitle;
        c.startAt = startAt;
        c.endAt = endAt;
        c.seq = seq;
        c.score = score;
        c.matchType = matchType;
        c.subChunkId = subChunkId;
        c.metadata = metadata;
        c.chunkType = chunkType;
        c.parentChunkId = parentChunkId;
        c.imageInfo = imageInfo;
        c.knowledgeFilename = knowledgeFilename;
        c.knowledgeSource = knowledgeSource;
        c.knowledgeChannel = knowledgeChannel;
        c.chunkMetadata = chunkMetadata;
        c.matchedContent = matchedContent;
        c.knowledgeDescription = knowledgeDescription;
        c.knowledgeCustomMetadata = knowledgeCustomMetadata;
        c.knowledgeBaseId = knowledgeBaseId;
        c.contentRevision = contentRevision;
        c.contentRewritten = contentRewritten;
        return c;
    }

    public int getContentRevision() { return contentRevision; }
    public void setContentRevision(int v) { contentRevision = v; }

    public boolean isContentRewritten() { return contentRewritten; }
    public void setContentRewritten(boolean v) { contentRewritten = v; }
}
