package com.ragagent.chatpipeline;

/**
 * 会话的模型调用配置（对照 Go {@code types.SummaryConfig}，internal/types/session.go:22-52）。
 *
 * <p>仅作管线内部的配置载体（Java 的 session 域未翻这个类型）；字段与 Go 声明序一致。
 * {@code thinking} 是三态 Boolean（Go 的 *bool，null=由模型默认决定）。</p>
 */
public final class SummaryConfig {

    private int maxTokens;
    private double repeatPenalty;
    private int topK;
    private double topP;
    private double frequencyPenalty;
    private double presencePenalty;
    private String prompt = "";
    private String contextTemplate = "";
    private String noMatchPrefix = "";
    private double temperature;
    private int seed;
    private int maxCompletionTokens;
    private Boolean thinking;

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int v) { maxTokens = v; }

    public double getRepeatPenalty() { return repeatPenalty; }
    public void setRepeatPenalty(double v) { repeatPenalty = v; }

    public int getTopK() { return topK; }
    public void setTopK(int v) { topK = v; }

    public double getTopP() { return topP; }
    public void setTopP(double v) { topP = v; }

    public double getFrequencyPenalty() { return frequencyPenalty; }
    public void setFrequencyPenalty(double v) { frequencyPenalty = v; }

    public double getPresencePenalty() { return presencePenalty; }
    public void setPresencePenalty(double v) { presencePenalty = v; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String v) { prompt = v == null ? "" : v; }

    public String getContextTemplate() { return contextTemplate; }
    public void setContextTemplate(String v) { contextTemplate = v == null ? "" : v; }

    public String getNoMatchPrefix() { return noMatchPrefix; }
    public void setNoMatchPrefix(String v) { noMatchPrefix = v == null ? "" : v; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double v) { temperature = v; }

    public int getSeed() { return seed; }
    public void setSeed(int v) { seed = v; }

    public int getMaxCompletionTokens() { return maxCompletionTokens; }
    public void setMaxCompletionTokens(int v) { maxCompletionTokens = v; }

    public Boolean getThinking() { return thinking; }
    public void setThinking(Boolean v) { thinking = v; }

    /** 值拷贝（对照 Go 的结构体值语义：Clone 时 SummaryConfig 按值复制）。 */
    public SummaryConfig copy() {
        SummaryConfig c = new SummaryConfig();
        c.maxTokens = maxTokens;
        c.repeatPenalty = repeatPenalty;
        c.topK = topK;
        c.topP = topP;
        c.frequencyPenalty = frequencyPenalty;
        c.presencePenalty = presencePenalty;
        c.prompt = prompt;
        c.contextTemplate = contextTemplate;
        c.noMatchPrefix = noMatchPrefix;
        c.temperature = temperature;
        c.seed = seed;
        c.maxCompletionTokens = maxCompletionTokens;
        c.thinking = thinking;
        return c;
    }
}
