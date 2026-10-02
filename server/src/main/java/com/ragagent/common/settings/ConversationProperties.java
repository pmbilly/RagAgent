package com.ragagent.common.settings;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * config.yaml 的 conversation 段 + prompt_templates 回填（对照 Go
 * internal/config/config.go 的 ConversationConfig/SummaryConfig 与
 * backfillConversationDefaults；值取自 config/config.yaml dev 缺省）。
 *
 * <p>Go 从 yaml 起服；Java 侧经 {@code @ConfigurationProperties(prefix="conversation")}
 * 绑定 application.yml 的 {@code conversation.*}（2026-09-28 评审补注解——此前注解
 * 缺失、bean 由 QaWiring 手工 new，yml 的 9 个键全是死键），vendor 模板从
 * classpath agent/management/prompt_templates/ 装载（文件与 Go 仓同源同字节）。
 * FindTemplateByID 语义：按 11 个模板文件的注册顺序首个 id 命中即返回 content。</p>
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "conversation")
public class ConversationProperties {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对照 Go PromptTemplates 的 yaml 文件清单（FindTemplateByID 的查找序）。 */
    private static final String[] TEMPLATE_FILES = {
            "agent_system_prompt.yaml", "system_prompt.yaml", "context_template.yaml",
            "fallback.yaml", "generate_summary.yaml", "generate_session_title.yaml",
            "rewrite.yaml", "graph_extraction.yaml", "generate_questions.yaml",
            "keywords_extraction.yaml", "intent_prompts.yaml"};

    // ---- config.yaml conversation 段（dev 缺省值逐项对照） ----
    private int maxRounds = 5;
    private double keywordThreshold = 0.3;
    private int embeddingTopK = 30;
    private double vectorThreshold = 0.2;
    private int rerankTopK = 30;
    private double rerankThreshold = 0.3;
    /** fallback_strategy："model"（dev）/"fixed" */
    private String fallbackStrategy = "model";
    private String fallbackResponse = "Sorry, I am unable to answer this question.";
    private boolean enableRewrite = true;
    private boolean enableQueryExpansion = true;
    // 模板 ID（config.yaml）→ 回填后的文本
    private String fallbackPromptId = "default_fallback_prompt";
    private String rewritePromptId = "default_rewrite";
    private String summaryPromptId = "default_kb";
    private String contextTemplateId = "default_context";
    private String generateSummaryPromptId = "default_summary";
    private String generateSessionTitlePromptId = "default_session_title";
    private String extractEntitiesPromptId = "default_extract_entities";
    private String extractRelationshipsPromptId = "default_extract_relationships";
    private String generateQuestionsPromptId = "default_generate_questions";

    // ---- summary 子段 ----
    private int summaryMaxInputChars = 16384;
    private double summaryRepeatPenalty = 1.0;
    private double summaryTemperature = 0.3;
    private int summaryMaxCompletionTokens = 2048;
    private String summaryNoMatchPrefix = """
            <think>
            </think>
            NO_MATCH""";

    // ---- 回填产物（Go yaml:"-" 的运行时字段） ----
    private String fallbackPrompt = "";
    private String rewritePromptSystem = "";
    private String rewritePromptUser = "";
    private String generateSummaryPrompt = "";
    private String generateSessionTitlePrompt = "";
    private String extractEntitiesPrompt = "";
    private String extractRelationshipsPrompt = "";
    private String generateQuestionsPrompt = "";
    private String summaryPrompt = "";
    private String summaryContextTemplate = "";

    /** 启动回填（对照 backfillConversationDefaults）。 */
    @jakarta.annotation.PostConstruct
    public void backfillFromTemplates() {
        Map<String, String[]> byId = loadTemplates();
        fallbackPrompt = content(byId, fallbackPromptId, fallbackPrompt);
        String[] rw = byId.get(rewritePromptId);
        if (rw != null) {
            if (rewritePromptSystem.isEmpty()) {
                rewritePromptSystem = rw[0];
            }
            if (rewritePromptUser.isEmpty()) {
                rewritePromptUser = rw[1];
            }
        }
        generateSummaryPrompt = content(byId, generateSummaryPromptId, generateSummaryPrompt);
        generateSessionTitlePrompt = content(byId, generateSessionTitlePromptId, generateSessionTitlePrompt);
        extractEntitiesPrompt = content(byId, extractEntitiesPromptId, extractEntitiesPrompt);
        extractRelationshipsPrompt = content(byId, extractRelationshipsPromptId, extractRelationshipsPrompt);
        generateQuestionsPrompt = content(byId, generateQuestionsPromptId, generateQuestionsPrompt);
        String[] sp = byId.get(summaryPromptId);
        if (sp != null && summaryPrompt.isEmpty()) {
            summaryPrompt = sp[0];
        }
        String[] ct = byId.get(contextTemplateId);
        if (ct != null && summaryContextTemplate.isEmpty()) {
            summaryContextTemplate = ct[0];
        }
    }

    private static String content(Map<String, String[]> byId, String id, String current) {
        if (!current.isEmpty() || id.isEmpty()) {
            return current;
        }
        String[] t = byId.get(id);
        return t == null ? "" : t[0];
    }

    /** FindTemplateByID：id → [content, user]。 */
    private Map<String, String[]> loadTemplates() {
        Map<String, String[]> byId = new LinkedHashMap<>();
        for (String file : TEMPLATE_FILES) {
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream("agent/management/prompt_templates/" + file)) {
                if (in == null) {
                    continue;
                }
                Object raw = new org.yaml.snakeyaml.Yaml().load(in);
                JsonNode root = MAPPER.valueToTree(raw);
                JsonNode list = root.get("templates");
                if (list == null || !list.isArray()) {
                    continue;
                }
                for (JsonNode t : list) {
                    String id = t.path("id").asText("");
                    if (id.isEmpty() || byId.containsKey(id)) {
                        continue;
                    }
                    byId.put(id, new String[] {t.path("content").asText(""), t.path("user").asText("")});
                }
            } catch (Exception ignored) {
                // Go：目录/文件缺失 → 跳过
            }
        }
        return byId;
    }

    // ---- getters/setters（Spring 绑定） ----
    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int v) { maxRounds = v; }
    public double getKeywordThreshold() { return keywordThreshold; }
    public void setKeywordThreshold(double v) { keywordThreshold = v; }
    public int getEmbeddingTopK() { return embeddingTopK; }
    public void setEmbeddingTopK(int v) { embeddingTopK = v; }
    public double getVectorThreshold() { return vectorThreshold; }
    public void setVectorThreshold(double v) { vectorThreshold = v; }
    public int getRerankTopK() { return rerankTopK; }
    public void setRerankTopK(int v) { rerankTopK = v; }
    public double getRerankThreshold() { return rerankThreshold; }
    public void setRerankThreshold(double v) { rerankThreshold = v; }
    public String getFallbackStrategy() { return fallbackStrategy; }
    public void setFallbackStrategy(String v) { fallbackStrategy = v; }
    public String getFallbackResponse() { return fallbackResponse; }
    public void setFallbackResponse(String v) { fallbackResponse = v; }
    public boolean isEnableRewrite() { return enableRewrite; }
    public void setEnableRewrite(boolean v) { enableRewrite = v; }
    public boolean isEnableQueryExpansion() { return enableQueryExpansion; }
    public void setEnableQueryExpansion(boolean v) { enableQueryExpansion = v; }
    public String getFallbackPrompt() { return fallbackPrompt; }
    public void setFallbackPrompt(String v) { fallbackPrompt = v; }
    public String getRewritePromptSystem() { return rewritePromptSystem; }
    public void setRewritePromptSystem(String v) { rewritePromptSystem = v; }
    public String getRewritePromptUser() { return rewritePromptUser; }
    public void setRewritePromptUser(String v) { rewritePromptUser = v; }
    public int getSummaryMaxCompletionTokens() { return summaryMaxCompletionTokens; }
    public void setSummaryMaxCompletionTokens(int v) { summaryMaxCompletionTokens = v; }
    public double getSummaryTemperature() { return summaryTemperature; }
    public void setSummaryTemperature(double v) { summaryTemperature = v; }
    public String getSummaryNoMatchPrefix() { return summaryNoMatchPrefix; }
    public void setSummaryNoMatchPrefix(String v) { summaryNoMatchPrefix = v; }
    public String getSummaryPrompt() { return summaryPrompt; }
    public void setSummaryPrompt(String v) { summaryPrompt = v; }
    public String getSummaryContextTemplate() { return summaryContextTemplate; }
    public void setSummaryContextTemplate(String v) { summaryContextTemplate = v; }
    public int getSummaryMaxInputChars() { return summaryMaxInputChars; }
    public void setSummaryMaxInputChars(int v) { summaryMaxInputChars = v; }
    public double getSummaryRepeatPenalty() { return summaryRepeatPenalty; }
    public void setSummaryRepeatPenalty(double v) { summaryRepeatPenalty = v; }
    public String getGenerateSessionTitlePrompt() { return generateSessionTitlePrompt; }
    public void setGenerateSessionTitlePrompt(String v) { generateSessionTitlePrompt = v; }
    public String getGenerateSummaryPrompt() { return generateSummaryPrompt; }
    public void setGenerateSummaryPrompt(String v) { generateSummaryPrompt = v; }
    public String getGenerateQuestionsPrompt() { return generateQuestionsPrompt; }
    public void setGenerateQuestionsPrompt(String v) { generateQuestionsPrompt = v; }
    public String getExtractEntitiesPrompt() { return extractEntitiesPrompt; }
    public void setExtractEntitiesPrompt(String v) { extractEntitiesPrompt = v; }
    public String getExtractRelationshipsPrompt() { return extractRelationshipsPrompt; }
    public void setExtractRelationshipsPrompt(String v) { extractRelationshipsPrompt = v; }
}
