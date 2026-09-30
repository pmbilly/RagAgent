package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.event.EventBusInterface;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.UsedMemory;
import com.ragagent.common.graph.GraphData;

/**
 * 一次 chat 管线执行的全部配置、状态与运行时句柄
 * （对照 Go {@code types.ChatManage} = PipelineRequest + PipelineState + PipelineContext 的三段嵌入，
 * internal/types/chat_manage.go:5-165）。
 *
 * <h2>与 Go 的字段对应</h2>
 * <ul>
 *   <li><b>请求段（不可变配置）</b>：query/sessionId/userId/maxRounds、检索参数、重排参数、
 *       模型参数、改写开关、FAQ 策略、多模态/附件、web 检索开关、summaryConfig。</li>
 *   <li><b>状态段（插件间流转）</b>：rewriteQuery/intent/history、searchResult/rerankResult/
 *       mergeResult/graphResult/entity 系列/userContent/renderedContexts/chatResponse、
 *       imageDescription/quotedContext/systemPromptOverride/memoryPrompt/usedMemories。</li>
 *   <li><b>运行时段</b>：eventBus/messageId/userMessageID。</li>
 * </ul>
 *
 * <h2>{@link #cloneChatManage()} 的复制面（Go Clone，chat_manage.go:169-265，实录钉住）</h2>
 * <p>请求段全量复制（SearchTarget 不可变，列表换新）；状态段<b>只复制</b> rewriteQuery/intent/
 * imageDescription/quotedContext/systemPromptOverride/memoryPrompt/usedMemories/renderedContexts/
 * entity/entityKBIDs/entityKnowledge——<b>history、searchResult、rerankResult、mergeResult、
 * userContent、chatResponse、renderedContexts 的运行中数据以及运行时段句柄都不复制</b>。
 * 实录组 chat_manage/clone 逐字段钉住。</p>
 *
 * <p>本类型不落 jsonb 也不作响应体（Go 侧 json tag 只用于评测注入），不进
 * JsonContractRoundTripTest；行为契约由实录回放钉住。</p>
 */
public final class ChatManage {

    // ===== 请求段（对照 PipelineRequest） =====

    private String query = "";
    private String sessionId = "";
    private String userId = "";
    private int maxRounds;

    private List<String> knowledgeBaseIds = new ArrayList<>();
    private List<String> knowledgeIds = new ArrayList<>();
    private List<SearchTarget> searchTargets = new ArrayList<>();
    private double vectorThreshold;
    private double keywordThreshold;
    private int embeddingTopK;
    private String vectorDatabase = "";

    private String rerankModelId = "";
    private int rerankTopK;
    private double rerankThreshold;

    private String chatModelId = "";
    private SummaryConfig summaryConfig = new SummaryConfig();
    private String fallbackStrategy = "";
    private String fallbackResponse = "";
    private String fallbackPrompt = "";
    /** 只控制最终引用标注；null = 请求未指定（默认 true）。 */
    private Boolean citationEnabled;

    private boolean enableRewrite;
    private boolean enableQueryExpansion;
    private String rewritePromptSystem = "";
    private String rewritePromptUser = "";
    private String queryUnderstandModelId = "";

    private boolean faqPriorityEnabled;
    private double faqDirectAnswerThreshold;
    private double faqScoreBoost;

    private boolean dataAnalysisEnabled;

    private List<String> images;
    private String vlmModelId = "";
    private boolean chatModelSupportsVision;

    private List<MessageAttachment> attachments;
    /** intent → 覆写系统提示词（agent 级；空白值回落全局默认）。 */
    private Map<String, String> intentPromptOverrides;

    private long tenantId;
    private boolean webSearchEnabled;
    private String webSearchProviderId = "";
    private int webSearchMaxResults;
    private boolean webFetchEnabled;
    private int webFetchTopN;
    private String language = "";

    // ===== 状态段（对照 PipelineState） =====

    private String rewriteQuery = "";
    private String intent = "";
    private List<History> history;

    private List<SearchResult> searchResult;
    private List<SearchResult> rerankResult;
    private List<SearchResult> mergeResult;
    private List<String> entity;
    private List<String> entityKbIds;
    private Map<String, String> entityKnowledge;
    private GraphData graphResult;
    private String userContent = "";
    private String renderedContexts = "";
    private ChatResponse chatResponse;
    private String imageDescription = "";
    private String quotedContext = "";
    private String systemPromptOverride = "";
    private String memoryPrompt = "";
    private List<UsedMemory> usedMemories;

    // ===== 运行时段（对照 PipelineContext） =====

    private EventBusInterface eventBus;
    private String messageId = "";
    private String userMessageId = "";

    /** 对照 types.GraphData（管线只消费 Node/Relation 两个切片）。 */
    /** 对照 types.GraphNode（name/chunks/attributes 三个被管线消费的字段）。 */
    /** 对照 types.GraphRelation。 */
    /** 图检索的命名空间（对照 types.NameSpace，internal/types/extract_graph.go:38-41）。 */
    // ----- 请求段访问器 -----

    public String getQuery() { return query; }
    public void setQuery(String v) { query = v == null ? "" : v; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { sessionId = v == null ? "" : v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { userId = v == null ? "" : v; }
    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int v) { maxRounds = v; }

    public List<String> getKnowledgeBaseIds() { return knowledgeBaseIds; }
    public void setKnowledgeBaseIds(List<String> v) { knowledgeBaseIds = v == null ? new ArrayList<>() : v; }
    public List<String> getKnowledgeIds() { return knowledgeIds; }
    public void setKnowledgeIds(List<String> v) { knowledgeIds = v == null ? new ArrayList<>() : v; }
    public List<SearchTarget> getSearchTargets() { return searchTargets; }
    public void setSearchTargets(List<SearchTarget> v) { searchTargets = v == null ? new ArrayList<>() : v; }
    public double getVectorThreshold() { return vectorThreshold; }
    public void setVectorThreshold(double v) { vectorThreshold = v; }
    public double getKeywordThreshold() { return keywordThreshold; }
    public void setKeywordThreshold(double v) { keywordThreshold = v; }
    public int getEmbeddingTopK() { return embeddingTopK; }
    public void setEmbeddingTopK(int v) { embeddingTopK = v; }
    public String getVectorDatabase() { return vectorDatabase; }
    public void setVectorDatabase(String v) { vectorDatabase = v == null ? "" : v; }

    public String getRerankModelId() { return rerankModelId; }
    public void setRerankModelId(String v) { rerankModelId = v == null ? "" : v; }
    public int getRerankTopK() { return rerankTopK; }
    public void setRerankTopK(int v) { rerankTopK = v; }
    public double getRerankThreshold() { return rerankThreshold; }
    public void setRerankThreshold(double v) { rerankThreshold = v; }

    public String getChatModelId() { return chatModelId; }
    public void setChatModelId(String v) { chatModelId = v == null ? "" : v; }
    public SummaryConfig getSummaryConfig() { return summaryConfig; }
    public void setSummaryConfig(SummaryConfig v) { summaryConfig = v == null ? new SummaryConfig() : v; }
    public String getFallbackStrategy() { return fallbackStrategy; }
    public void setFallbackStrategy(String v) { fallbackStrategy = v == null ? "" : v; }
    public String getFallbackResponse() { return fallbackResponse; }
    public void setFallbackResponse(String v) { fallbackResponse = v == null ? "" : v; }
    public String getFallbackPrompt() { return fallbackPrompt; }
    public void setFallbackPrompt(String v) { fallbackPrompt = v == null ? "" : v; }
    public Boolean getCitationEnabled() { return citationEnabled; }
    public void setCitationEnabled(Boolean v) { citationEnabled = v; }

    public boolean isEnableRewrite() { return enableRewrite; }
    public void setEnableRewrite(boolean v) { enableRewrite = v; }
    public boolean isEnableQueryExpansion() { return enableQueryExpansion; }
    public void setEnableQueryExpansion(boolean v) { enableQueryExpansion = v; }
    public String getRewritePromptSystem() { return rewritePromptSystem; }
    public void setRewritePromptSystem(String v) { rewritePromptSystem = v == null ? "" : v; }
    public String getRewritePromptUser() { return rewritePromptUser; }
    public void setRewritePromptUser(String v) { rewritePromptUser = v == null ? "" : v; }
    public String getQueryUnderstandModelId() { return queryUnderstandModelId; }
    public void setQueryUnderstandModelId(String v) { queryUnderstandModelId = v == null ? "" : v; }

    public boolean isFaqPriorityEnabled() { return faqPriorityEnabled; }
    public void setFaqPriorityEnabled(boolean v) { faqPriorityEnabled = v; }
    public double getFaqDirectAnswerThreshold() { return faqDirectAnswerThreshold; }
    public void setFaqDirectAnswerThreshold(double v) { faqDirectAnswerThreshold = v; }
    public double getFaqScoreBoost() { return faqScoreBoost; }
    public void setFaqScoreBoost(double v) { faqScoreBoost = v; }

    public boolean isDataAnalysisEnabled() { return dataAnalysisEnabled; }
    public void setDataAnalysisEnabled(boolean v) { dataAnalysisEnabled = v; }

    public List<String> getImages() { return images; }
    public void setImages(List<String> v) { images = v; }
    public String getVlmModelId() { return vlmModelId; }
    public void setVlmModelId(String v) { vlmModelId = v == null ? "" : v; }
    public boolean isChatModelSupportsVision() { return chatModelSupportsVision; }
    public void setChatModelSupportsVision(boolean v) { chatModelSupportsVision = v; }

    public List<MessageAttachment> getAttachments() { return attachments; }
    public void setAttachments(List<MessageAttachment> v) { attachments = v; }
    public Map<String, String> getIntentPromptOverrides() { return intentPromptOverrides; }
    public void setIntentPromptOverrides(Map<String, String> v) { intentPromptOverrides = v; }

    public long getTenantId() { return tenantId; }
    public void setTenantId(long v) { tenantId = v; }
    public boolean isWebSearchEnabled() { return webSearchEnabled; }
    public void setWebSearchEnabled(boolean v) { webSearchEnabled = v; }
    public String getWebSearchProviderId() { return webSearchProviderId; }
    public void setWebSearchProviderId(String v) { webSearchProviderId = v == null ? "" : v; }
    public int getWebSearchMaxResults() { return webSearchMaxResults; }
    public void setWebSearchMaxResults(int v) { webSearchMaxResults = v; }
    public boolean isWebFetchEnabled() { return webFetchEnabled; }
    public void setWebFetchEnabled(boolean v) { webFetchEnabled = v; }
    public int getWebFetchTopN() { return webFetchTopN; }
    public void setWebFetchTopN(int v) { webFetchTopN = v; }
    public String getLanguage() { return language; }
    public void setLanguage(String v) { language = v == null ? "" : v; }

    // ----- 状态段访问器 -----

    public String getRewriteQuery() { return rewriteQuery; }
    public void setRewriteQuery(String v) { rewriteQuery = v == null ? "" : v; }
    public String getIntent() { return intent; }
    public void setIntent(String v) { intent = v == null ? "" : v; }
    public List<History> getHistory() { return history; }
    public void setHistory(List<History> v) { history = v; }

    public List<SearchResult> getSearchResult() { return searchResult; }
    public void setSearchResult(List<SearchResult> v) { searchResult = v; }
    public List<SearchResult> getRerankResult() { return rerankResult; }
    public void setRerankResult(List<SearchResult> v) { rerankResult = v; }
    public List<SearchResult> getMergeResult() { return mergeResult; }
    public void setMergeResult(List<SearchResult> v) { mergeResult = v; }
    public List<String> getEntity() { return entity; }
    public void setEntity(List<String> v) { entity = v; }
    public List<String> getEntityKbIds() { return entityKbIds; }
    public void setEntityKbIds(List<String> v) { entityKbIds = v; }
    public Map<String, String> getEntityKnowledge() { return entityKnowledge; }
    public void setEntityKnowledge(Map<String, String> v) { entityKnowledge = v; }
    public GraphData getGraphResult() { return graphResult; }
    public void setGraphResult(GraphData v) { graphResult = v; }
    public String getUserContent() { return userContent; }
    public void setUserContent(String v) { userContent = v == null ? "" : v; }
    public String getRenderedContexts() { return renderedContexts; }
    public void setRenderedContexts(String v) { renderedContexts = v == null ? "" : v; }
    public ChatResponse getChatResponse() { return chatResponse; }
    public void setChatResponse(ChatResponse v) { chatResponse = v; }
    public String getImageDescription() { return imageDescription; }
    public void setImageDescription(String v) { imageDescription = v == null ? "" : v; }
    public String getQuotedContext() { return quotedContext; }
    public void setQuotedContext(String v) { quotedContext = v == null ? "" : v; }
    public String getSystemPromptOverride() { return systemPromptOverride; }
    public void setSystemPromptOverride(String v) { systemPromptOverride = v == null ? "" : v; }
    public String getMemoryPrompt() { return memoryPrompt; }
    public void setMemoryPrompt(String v) { memoryPrompt = v == null ? "" : v; }
    public List<UsedMemory> getUsedMemories() { return usedMemories; }
    public void setUsedMemories(List<UsedMemory> v) { usedMemories = v; }

    // ----- 运行时段访问器 -----

    public EventBusInterface getEventBus() { return eventBus; }
    public void setEventBus(EventBusInterface v) { eventBus = v; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String v) { messageId = v == null ? "" : v; }
    public String getUserMessageId() { return userMessageId; }
    public void setUserMessageId(String v) { userMessageId = v == null ? "" : v; }

    // ----- 行为 -----

    /**
     * 对照 {@code ChatManage.NeedsRetrieval}：当前执行是否要跑检索阶段。
     * web_search 意图只有开 web 检索才需要；其余委托给 intent 判定。
     */
    public boolean needsRetrieval() {
        if (QueryIntent.WEB_SEARCH.equals(intent)) {
            return webSearchEnabled;
        }
        return QueryIntent.needsKbRetrieval(intent);
    }

    /** 对照 {@code PipelineRequest.CitationsEnabled}：nil 默认 true。 */
    public boolean citationsEnabled() {
        return citationEnabled == null || citationEnabled;
    }

    /**
     * 对照 {@code ChatManage.Clone}：复制面见类注释。PipelineContext 字段
     * （eventBus 等）是逐执行句柄，<b>不复制</b>。
     */
    public ChatManage cloneChatManage() {
        ChatManage c = new ChatManage();
        // PipelineRequest
        c.query = query;
        c.sessionId = sessionId;
        c.userId = userId;
        c.maxRounds = maxRounds;
        c.knowledgeBaseIds = new ArrayList<>(knowledgeBaseIds);
        c.knowledgeIds = new ArrayList<>(knowledgeIds);
        c.searchTargets = new ArrayList<>(searchTargets); // SearchTarget 不可变
        c.vectorThreshold = vectorThreshold;
        c.keywordThreshold = keywordThreshold;
        c.embeddingTopK = embeddingTopK;
        c.vectorDatabase = vectorDatabase;
        c.rerankModelId = rerankModelId;
        c.rerankTopK = rerankTopK;
        c.rerankThreshold = rerankThreshold;
        c.chatModelId = chatModelId;
        c.summaryConfig = summaryConfig.copy();
        c.fallbackStrategy = fallbackStrategy;
        c.fallbackResponse = fallbackResponse;
        c.fallbackPrompt = fallbackPrompt;
        c.citationEnabled = citationEnabled;
        c.enableRewrite = enableRewrite;
        c.enableQueryExpansion = enableQueryExpansion;
        c.rewritePromptSystem = rewritePromptSystem;
        c.rewritePromptUser = rewritePromptUser;
        c.queryUnderstandModelId = queryUnderstandModelId;
        c.faqPriorityEnabled = faqPriorityEnabled;
        c.faqDirectAnswerThreshold = faqDirectAnswerThreshold;
        c.faqScoreBoost = faqScoreBoost;
        c.dataAnalysisEnabled = dataAnalysisEnabled;
        c.images = images == null ? null : new ArrayList<>(images);
        c.vlmModelId = vlmModelId;
        c.chatModelSupportsVision = chatModelSupportsVision;
        c.attachments = attachments == null ? null : new ArrayList<>(attachments); // MessageAttachment 视为不可变行
        c.intentPromptOverrides = intentPromptOverrides == null ? null : new LinkedHashMap<>(intentPromptOverrides);
        c.tenantId = tenantId;
        c.webSearchEnabled = webSearchEnabled;
        c.webSearchProviderId = webSearchProviderId;
        c.webSearchMaxResults = webSearchMaxResults;
        c.webFetchEnabled = webFetchEnabled;
        c.webFetchTopN = webFetchTopN;
        c.language = language;
        // PipelineState（只复制 Clone 列出的字段；history/结果集/userContent 等不复制）
        c.rewriteQuery = rewriteQuery;
        c.intent = intent;
        c.imageDescription = imageDescription;
        c.quotedContext = quotedContext;
        c.systemPromptOverride = systemPromptOverride;
        c.memoryPrompt = memoryPrompt;
        c.usedMemories = usedMemories == null ? null : new ArrayList<>(usedMemories);
        c.renderedContexts = renderedContexts;
        c.entity = entity == null ? new ArrayList<>() : new ArrayList<>(entity);
        c.entityKbIds = entityKbIds == null ? new ArrayList<>() : new ArrayList<>(entityKbIds);
        c.entityKnowledge = entityKnowledge == null ? new HashMap<>() : new LinkedHashMap<>(entityKnowledge);
        return c;
    }
}
