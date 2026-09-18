package com.ragagent.session.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.common.web.PgJsonTypeHandler;
import com.ragagent.llm.domain.TokenUsage;

/**
 * messages 表实体（对照 Go {@code types.Message}，internal/types/message.go L308-378）。
 *
 * <p><b>响应形态</b>：消息列表 / 单条消息接口把它塞进 {@code {"success":true,"data":...}}，
 * 所以下面是 **Go struct 声明序**，不是字母序。</p>
 *
 * <h2>GORM 隐式行为 → Java 的等效清单（约定 §3 要求显式列出）</h2>
 * <ol>
 *   <li><b>钩子 BeforeCreate</b>（Go L463-484）：无条件生成新 UUID，并把
 *       KnowledgeReferences / AgentSteps / MentionedItems / Images / Attachments / Artifacts
 *       这六个 nil 切片**就地置为空切片**——所以落库时写的是 {@code []} 而不是 SQL NULL
 *       （各类型的 {@code Value()} 也做同样的 nil→[] 兜底）。<br>
 *       等效 Java：这六个字段**默认值是空列表**，实体的 create 路径无条件覆盖 ID。</li>
 *   <li><b>软删除</b>：{@code gorm.DeletedAt}。按 §9 既定做法不用 {@code @TableLogic}，
 *       查询显式 {@code deleted_at IS NULL}，删除是 UPDATE。</li>
 *   <li><b>⚠️ UpdateMessage 用 {@code Updates(结构体)}</b>（Go L139-143）——GORM 对
 *       **结构体** 的 Updates 会**跳过零值字段**（string ""、数值 0、bool false、
 *       指针 nil、切片 nil）。所以"把 content 改成空串"在这条路径上**不会生效**。
 *       这不是缺陷而是 Go 的既有行为，Java 侧必须照抄（见 {@code MessageRepository.update}）。</li>
 *   <li><b>默认排序</b>：各查询自带 {@code created_at ASC/DESC}（Go L54/L68/L94/L121/L134）。</li>
 *   <li><b>{@code knowledge_references} 等 jsonb 列</b>：走 {@code PgJsonTypeHandler}。</li>
 * </ol>
 *
 * <h2>跨模块类型的处置</h2>
 * <p>三处依赖尚未翻译的模块，先按**不透明**类型透传（与 {@code StreamResponse.knowledgeReferences}
 * 的既有做法一致）：</p>
 * <ul>
 *   <li>{@code knowledge_references}：Go 是 {@code References = []*SearchResult}，
 *       属检索模块。仅需保证序列化时原样透传。</li>
 *   <li>{@code agent_steps}：Go 是 {@code AgentSteps = []AgentStep} → {@code ToolCall}，
 *       属 agent 引擎（阶段 7）。</li>
 *   <li>{@code execution_context}：字段本身是 {@code json:"-"}（不出响应），
 *       子结构见 {@link MessageExecutionContext}。</li>
 * </ul>
 */
@TableName(value = "messages", autoResultMap = true)
@JsonPropertyOrder({
        "id", "session_id", "request_id", "content", "role", "knowledge_references",
        "agent_steps", "mentioned_items", "images", "attachments", "artifacts",
        "is_completed", "is_fallback", "agent_duration_ms", "usage", "channel", "agent_id",
        "model_id", "knowledge_id", "used_memories", "created_at", "updated_at", "deleted_at"
})
public class Message {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";

    @TableId(value = "id", type = IdType.INPUT)
    @JsonProperty("id")
    private String id = "";

    @JsonProperty("session_id")
    private String sessionId = "";

    /** 追踪 API 请求用的请求 ID；同一次问答的用户/助手两条消息**共用一个**。 */
    @JsonProperty("request_id")
    private String requestId = "";

    @JsonProperty("content")
    private String content = "";

    /** {@code user} / {@code assistant} / {@code system}。 */
    @JsonProperty("role")
    private String role = "";

    /** 检索引用。**无 omitempty**（nil 会输出成 {@code null}）。跨模块类型见类注释。 */
    @TableField(value = "knowledge_references", typeHandler = PgJsonTypeHandler.class)
    @JsonProperty("knowledge_references")
    private List<Object> knowledgeReferences = new ArrayList<>();

    /** agent 执行步骤。跨模块类型见类注释。 */
    @TableField(value = "agent_steps", typeHandler = PgJsonTypeHandler.class)
    @JsonProperty("agent_steps")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<Object> agentSteps = new ArrayList<>();

    /** 用户消息里 @ 到的知识库/文件等。 */
    @TableField(value = "mentioned_items", typeHandler = MentionedItemListTypeHandler.class)
    @JsonProperty("mentioned_items")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<MentionedItem> mentionedItems = new ArrayList<>();

    @TableField(value = "images", typeHandler = MessageImageListTypeHandler.class)
    @JsonProperty("images")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<MessageImage> images = new ArrayList<>();

    @TableField(value = "attachments", typeHandler = MessageAttachmentListTypeHandler.class)
    @JsonProperty("attachments")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<MessageAttachment> attachments = new ArrayList<>();

    /** skill 产出、由 ArtifactCollector 在沙箱结束后回填（仅助手消息）。 */
    @TableField(value = "artifacts", typeHandler = MessageArtifactListTypeHandler.class)
    @JsonProperty("artifacts")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<MessageArtifact> artifacts = new ArrayList<>();

    /**
     * 是否生成完毕。**无 omitempty**：恒输出。
     *
     * <p>字段名不带 {@code is} 前缀——理由见 {@link Session} 上同名字段的注释。</p>
     */
    @TableField("is_completed")
    @JsonProperty("is_completed")
    private boolean completed;

    /** 是否兜底回答（没匹配到知识库）。omitempty → 省略 false。 */
    @TableField("is_fallback")
    @JsonProperty("is_fallback")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean fallback;

    /** 从发起查询到答案开始的耗时（毫秒）。omitempty + 列默认 0 → NON_DEFAULT。 */
    @JsonProperty("agent_duration_ms")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private long agentDurationMs;

    /**
     * 本轮所有 round 聚合的 token 用量。持久化是为了让历史读取在实时流消失后
     * 仍能归因成本；用户消息与旧数据行为 null。
     */
    @TableField(value = "usage", typeHandler = PgJsonTypeHandler.class)
    @JsonProperty("usage")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private TokenUsage usage;

    /** 发给 LLM 的完整 RAG 增强正文（带检索上下文）。**不进 JSON**，只落库。 */
    @TableField("rendered_content")
    @JsonIgnore
    private String renderedContent = "";

    /** 消息来源渠道：{@code web} / {@code api} / {@code im}。 */
    @JsonProperty("channel")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String channel;

    /** 本轮用的 agent。与 session 的 last_request_state 不同，它不随用户切换 agent 而变。 */
    @JsonProperty("agent_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String agentId;

    /** 解析共享 agent 的模型/知识库所用的有效租户。**刻意不进 JSON**。 */
    @TableField("agent_tenant_id")
    @JsonIgnore
    private long agentTenantId;

    /** 本轮请求/生效的对话模型。 */
    @JsonProperty("model_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String modelId;

    /** 本轮的机密无关作用域快照，供流结束后派生追问建议。**不进 JSON**。 */
    @TableField(value = "execution_context", typeHandler = PgJsonTypeHandler.class)
    @JsonIgnore
    private MessageExecutionContext executionContext;

    /** 指向聊天历史知识库里的 Knowledge 条目（用于向量检索）。 */
    @JsonProperty("knowledge_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String knowledgeId;

    /** 注入到本回答的长期记忆，供 UI 展示与就地删除。 */
    @TableField(value = "used_memories", typeHandler = UsedMemoryListTypeHandler.class)
    @JsonProperty("used_memories")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<UsedMemory> usedMemories;

    @JsonProperty("created_at")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;

    @JsonProperty("deleted_at")
    private OffsetDateTime deletedAt;

    public Message() {
    }

    /**
     * 对照 Go {@code Message.BeforeCreate} 的切片初始化部分。
     *
     * <p>Go 的钩子在创建前把六个 nil 切片置空；Java 侧这些字段的**默认值本就是空列表**
     * （见字段声明），所以这里只需要保证调用方显式 setNull 之后不会退化成 SQL NULL。</p>
     */
    public void normalizeListsForInsert() {
        if (knowledgeReferences == null) {
            knowledgeReferences = new ArrayList<>();
        }
        if (agentSteps == null) {
            agentSteps = new ArrayList<>();
        }
        if (mentionedItems == null) {
            mentionedItems = new ArrayList<>();
        }
        if (images == null) {
            images = new ArrayList<>();
        }
        if (attachments == null) {
            attachments = new ArrayList<>();
        }
        if (artifacts == null) {
            artifacts = new ArrayList<>();
        }
    }

    // ── 访问器 ──────────────────────────────────────────────────────────────

    public String getId() {
        return id;
    }

    public void setId(String v) {
        this.id = v;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String v) {
        this.sessionId = v == null ? "" : v;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String v) {
        this.requestId = v == null ? "" : v;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String v) {
        this.content = v == null ? "" : v;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String v) {
        this.role = v == null ? "" : v;
    }

    public List<Object> getKnowledgeReferences() {
        return knowledgeReferences;
    }

    public void setKnowledgeReferences(List<Object> v) {
        this.knowledgeReferences = v;
    }

    public List<Object> getAgentSteps() {
        return agentSteps;
    }

    public void setAgentSteps(List<Object> v) {
        this.agentSteps = v;
    }

    public List<MentionedItem> getMentionedItems() {
        return mentionedItems;
    }

    public void setMentionedItems(List<MentionedItem> v) {
        this.mentionedItems = v;
    }

    public List<MessageImage> getImages() {
        return images;
    }

    public void setImages(List<MessageImage> v) {
        this.images = v;
    }

    public List<MessageAttachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<MessageAttachment> v) {
        this.attachments = v;
    }

    public List<MessageArtifact> getArtifacts() {
        return artifacts;
    }

    public void setArtifacts(List<MessageArtifact> v) {
        this.artifacts = v;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean v) {
        this.completed = v;
    }

    public boolean isFallback() {
        return fallback;
    }

    public void setFallback(boolean v) {
        this.fallback = v;
    }

    public long getAgentDurationMs() {
        return agentDurationMs;
    }

    public void setAgentDurationMs(long v) {
        this.agentDurationMs = v;
    }

    public TokenUsage getUsage() {
        return usage;
    }

    public void setUsage(TokenUsage v) {
        this.usage = v;
    }

    public String getRenderedContent() {
        return renderedContent;
    }

    public void setRenderedContent(String v) {
        this.renderedContent = v == null ? "" : v;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String v) {
        this.channel = v;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String v) {
        this.agentId = v;
    }

    public long getAgentTenantId() {
        return agentTenantId;
    }

    public void setAgentTenantId(long v) {
        this.agentTenantId = v;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String v) {
        this.modelId = v;
    }

    public MessageExecutionContext getExecutionContext() {
        return executionContext;
    }

    public void setExecutionContext(MessageExecutionContext v) {
        this.executionContext = v;
    }

    public String getKnowledgeId() {
        return knowledgeId;
    }

    public void setKnowledgeId(String v) {
        this.knowledgeId = v;
    }

    public List<UsedMemory> getUsedMemories() {
        return usedMemories;
    }

    public void setUsedMemories(List<UsedMemory> v) {
        this.usedMemories = v;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime v) {
        this.createdAt = v;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime v) {
        this.updatedAt = v;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime v) {
        this.deletedAt = v;
    }
}
