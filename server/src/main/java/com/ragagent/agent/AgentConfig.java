package com.ragagent.agent;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * agent 运行时配置。
 *
 * <h2>⚠️ 与 {@code agent.management.service.AgentConfigJson} 不是同一个类型</h2>
 * <p>{@code AgentConfigJson} 是 <b>配置树校验件</b>（custom_agents.config jsonb 的
 * 默认值补全/校验），本类是 <b>运行时消费面</b>：引擎（engine/observe/act/think/
 * finalize）从这里读本轮执行的参数。存储面归 {@code AgentConfigJson}，
 * 本类只收引擎用到的字段。别把两者混为一谈，也别互相顶替。</p>
 *
 * <h2>只收引擎消费的字段</h2>
 * <p>其余字段（知识库/技能开关/MCP 选择模式等，由调用方服务消费）尚未收入，
 * 按需再补；新增字段时键名须与既有 jsonb 记录保持一致。</p>
 *
 * <h2>方法 vs 字段</h2>
 * <ul>
 *   <li>派生判定（{@link #unlimitedIterations()}/{@link #citationsEnabled()}）是
 *       <b>方法</b>而非持久化字段 → Java 侧 {@code @JsonIgnore}；</li>
 *   <li>技能安装模式族（skillInstallMode/SkillInstallDir/BuiltinSkillInstallerID）与
 *       sandboxConfigId 随沙箱裁剪退役。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class AgentConfig {

    /**
     * ReAct 轮次上限。0 = 未设置（用默认）；负数 = 无上限（见
     * {@link #unlimitedIterations()}）。
     */
    public static final int UNLIMITED_MAX_ITERATIONS = -1;

    // ---- 引擎消费的持久化字段 ----
    private int maxIterations;
    private List<String> allowedTools;
    private double temperature;
    private boolean webSearchEnabled;
    private boolean multiTurnEnabled;
    private Boolean thinking;
    private Boolean citationEnabled;
    private boolean retainRetrievalHistory;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int llmCallTimeout;
    private int maxCompletionTokens;
    private int maxToolOutputChars;
    private int maxContextTokens;
    private int compactionKeepRecentTokens;
    private boolean parallelToolCalls;

    // ---- 运行时字段（不持久化）----
    /** 已解析的模型能力，客户端给不了。 */
    @JsonIgnore
    private boolean chatModelSupportsVision;
    /** 工具图片描述用的 VLM 模型 ID。 */
    @JsonIgnore
    private String vlmModelId = "";

    /** ReAct 循环是否无轮次上限（派生判定，不持久化）。 */
    @JsonIgnore
    public boolean unlimitedIterations() {
        return maxIterations < 0;
    }

    /**
     * 引用输出开关；旧运行时配置没有该字段（null）时<b>默认开</b>。
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
