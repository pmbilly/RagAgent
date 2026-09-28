package com.ragagent.agent;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * agent 运行时配置（对照 Go {@code types.AgentConfig}，internal/types/agent.go:104-320）。
 *
 * <h2>⚠️ 与 {@code agentm.service.AgentConfigJson} 不是同一个类型</h2>
 * <p>{@code AgentConfigJson} 是 <b>配置树校验件</b>（custom_agents.config jsonb 的
 * EnsureDefaults/Validate），本类是 <b>运行时消费面</b>：引擎（engine/observe/act/think/
 * finalize）从这里读本轮执行的参数。Go 里同一个 struct 兼任两职（Value/Scan 落 jsonb +
 * 引擎消费）；Java 侧 3.x 已把存储面译成 AgentConfigJson，本类只补引擎用到的字段。
 * 别把两者混为一谈，也别互相顶替。</p>
 *
 * <h2>本波只收引擎消费的字段</h2>
 * <p>Go 全量字段的其余部分（KnowledgeBases/KnowledgeIDs/MCPSelectionMode/SkillsEnabled/
 * AllowedSkills/... 由调用方 agent_service 消费，装配随 4.6d）按需再补；补的时候
 * 逐字段对照 Go json tag。</p>
 *
 * <h2>方法 vs 字段（§7.5 第 2 条）</h2>
 * <ul>
 *   <li>{@code UnlimitedIterations()}/{@code CitationsEnabled()} 在 Go 里是<b>方法</b>
 *       → Java 侧 {@code @JsonIgnore}；</li>
 *   <li>技能安装模式族（skillInstallMode/SkillInstallDir/BuiltinSkillInstallerID）与
 *       sandboxConfigId 随沙箱裁剪退役。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class AgentConfig {

    /**
     * ReAct 轮次上限。0 = 未设置（用默认）；负数 = 无上限（见
     * {@link #unlimitedIterations()}）。对照 UnlimitedMaxIterations = -1。
     */
    public static final int UNLIMITED_MAX_ITERATIONS = -1;

    // ---- 引擎消费的持久化字段（json tag 对照 Go）----
    @JsonProperty("max_iterations")
    private int maxIterations;
    @JsonProperty("allowed_tools")
    private List<String> allowedTools;
    @JsonProperty("temperature")
    private double temperature;
    @JsonProperty("web_search_enabled")
    private boolean webSearchEnabled;
    @JsonProperty("multi_turn_enabled")
    private boolean multiTurnEnabled;
    @JsonProperty("thinking")
    private Boolean thinking;
    @JsonProperty("citation_enabled")
    private Boolean citationEnabled;
    @JsonProperty("retain_retrieval_history")
    private boolean retainRetrievalHistory;
    @JsonProperty("llm_call_timeout")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int llmCallTimeout;
    @JsonProperty("max_completion_tokens")
    private int maxCompletionTokens;
    @JsonProperty("max_tool_output_chars")
    private int maxToolOutputChars;
    @JsonProperty("max_context_tokens")
    private int maxContextTokens;
    @JsonProperty("compaction_keep_recent_tokens")
    private int compactionKeepRecentTokens;
    @JsonProperty("parallel_tool_calls")
    private boolean parallelToolCalls;

    // ---- 运行时字段（json:"-"）----
    /** 已解析的模型能力，客户端给不了。 */
    @JsonIgnore
    private boolean chatModelSupportsVision;
    /** 工具图片描述用的 VLM 模型 ID。 */
    @JsonIgnore
    private String vlmModelId = "";

    /** ReAct 循环是否无轮次上限（对照 UnlimitedIterations()，Go 方法）。 */
    @JsonIgnore
    public boolean unlimitedIterations() {
        return maxIterations < 0;
    }

    /**
     * 引用输出开关；旧运行时配置没有该字段（nil）时<b>默认开</b>
     * （对照 CitationsEnabled()，Go 方法）。
     */
    @JsonIgnore
    public boolean citationsEnabled() {
        return citationEnabled == null || citationEnabled;
    }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int v) { maxIterations = v; }
    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> v) { allowedTools = v; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double v) { temperature = v; }
    public boolean isWebSearchEnabled() { return webSearchEnabled; }
    public void setWebSearchEnabled(boolean v) { webSearchEnabled = v; }
    public boolean isMultiTurnEnabled() { return multiTurnEnabled; }
    public void setMultiTurnEnabled(boolean v) { multiTurnEnabled = v; }
    public Boolean getThinking() { return thinking; }
    public void setThinking(Boolean v) { thinking = v; }
    public Boolean getCitationEnabled() { return citationEnabled; }
    public void setCitationEnabled(Boolean v) { citationEnabled = v; }
    public boolean isRetainRetrievalHistory() { return retainRetrievalHistory; }
    public void setRetainRetrievalHistory(boolean v) { retainRetrievalHistory = v; }
    public int getLlmCallTimeout() { return llmCallTimeout; }
    public void setLlmCallTimeout(int v) { llmCallTimeout = v; }
    public int getMaxCompletionTokens() { return maxCompletionTokens; }
    public void setMaxCompletionTokens(int v) { maxCompletionTokens = v; }
    public int getMaxToolOutputChars() { return maxToolOutputChars; }
    public void setMaxToolOutputChars(int v) { maxToolOutputChars = v; }
    public int getMaxContextTokens() { return maxContextTokens; }
    public void setMaxContextTokens(int v) { maxContextTokens = v; }
    public int getCompactionKeepRecentTokens() { return compactionKeepRecentTokens; }
    public void setCompactionKeepRecentTokens(int v) { compactionKeepRecentTokens = v; }
    public boolean isParallelToolCalls() { return parallelToolCalls; }
    public void setParallelToolCalls(boolean v) { parallelToolCalls = v; }
    public boolean isChatModelSupportsVision() { return chatModelSupportsVision; }
    public void setChatModelSupportsVision(boolean v) { chatModelSupportsVision = v; }
    public String getVlmModelId() { return vlmModelId; }
    public void setVlmModelId(String v) { vlmModelId = v == null ? "" : v; }
}
