package com.ragagent.agentm.service;

import java.io.InputStream;
import com.ragagent.common.wiki.WikiLanguageSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 内建 agent 注册表（config/builtin_agents.yaml + config/prompt_templates/*.yaml 的启动装载）。
 *
 * <p>三个要点：</p>
 * <ol>
 *   <li><b>YAML config 的未知键静默丢弃</b>：snakeyaml 解析后按 CustomAgentConfig
 *       已知键过滤（builtin_agents.yaml 里的 {@code reflection_enabled} 即被丢弃）。</li>
 *   <li><b>prompt 引用解析</b>：启动时把
 *       system_prompt_id/context_template_id 的模板 content 填进 entry config
 *       （仅当对应 content 键为空）。按 11 个模板列表固定顺序查找。</li>
 *   <li><b>i18n 解析</b>：精确匹配 → 语言前缀匹配 → default → 第一项。</li>
 * </ol>
 *
 * <p>locale 来源：WEKNORA_LANGUAGE env 优先，
 * 其次 Accept-Language 首个 tag，缺省 zh-CN（见 {@link #localeFromRequest}）。</p>
 */
@Component
public class BuiltinAgentRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 列表接口的固定展示序（wiki-fixer/skill-installer 刻意不在列）。 */
    private static final List<String> ORDERED_IDS = List.of(
            "builtin-quick-answer", "builtin-smart-reasoning", "builtin-wiki-researcher",
            "builtin-deep-researcher", "builtin-data-analyst", "builtin-knowledge-graph-expert",
            "builtin-document-assistant");

    /** CustomAgentConfig 已知键全集（未知键装载时静默丢弃）。 */
    private static final java.util.Set<String> CONFIG_KEYS = java.util.Set.of(
            "agentMode", "agentType", "systemPrompt", "systemPromptId", "contextTemplate",
            "contextTemplateId", "modelId", "rerankModelId", "temperature",
            "maxCompletionTokens", "thinking", "citationEnabled", "maxIterations",
            "llmCallTimeout", "allowedTools", "mcpSelectionMode", "mcpServices",
            "mcpAuthWaitTimeout", "skillsSelectionMode", "selectedSkills",
            "kbSelectionMode", "knowledgeBases", "retrieveKbOnlyWhenMentioned",
            "retainRetrievalHistory", "imageUploadEnabled", "vlmModelId",
            "audioUploadEnabled", "asrModelId", "imageStorageProvider", "supportedFileTypes",
            "chatParserEngineRules", "attachmentImageUnderstanding", "attachmentOcrMaxPages",
            "attachmentParseWaitTimeoutSec", "dataAnalysisEnabled", "faqPriorityEnabled",
            "faqDirectAnswerThreshold", "faqScoreBoost", "webSearchEnabled",
            "webSearchMaxResults", "webSearchProviderId", "webFetchEnabled", "webFetchTopN",
            "multiTurnEnabled", "historyTurns", "memoryEnabled", "embeddingTopK",
            "keywordThreshold", "vectorThreshold", "rerankTopK", "rerankThreshold",
            "enableQueryExpansion", "enableRewrite", "rewritePromptSystem", "rewritePromptUser",
            "queryUnderstandModelId", "fallbackStrategy", "fallbackResponse", "fallbackPrompt",
            "intentPrompts", "questionSuggestions");

    /** 模板文件固定装载序（模板查找顺序）。 */
    private static final List<String> TEMPLATE_FILES = List.of(
            "system_prompt.yaml", "context_template.yaml", "rewrite.yaml", "fallback.yaml",
            "generate_session_title.yaml", "generate_summary.yaml", "keywords_extraction.yaml",
            "agent_system_prompt.yaml", "graph_extraction.yaml", "generate_questions.yaml",
            "intent_prompts.yaml");

    /** entry 的 i18n（name/description）与（过滤后的）config 树。 */
    public record Entry(String id, String avatar, boolean isBuiltin,
            Map<String, String[]> i18n, ObjectNode config) {
        /** name/description 按 [default, zh-CN, zh-TW, ja-JP, ko-KR, …] 存放：i18n.get(locale)。 */
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public BuiltinAgentRegistry() {
        load();
    }

    public static boolean isBuiltinAgentID(String id) {
        return id != null && (id.equals("builtin-quick-answer") || id.equals("builtin-smart-reasoning")
                || id.equals("builtin-wiki-researcher") || id.equals("builtin-deep-researcher")
                || id.equals("builtin-data-analyst") || id.equals("builtin-knowledge-graph-expert")
                || id.equals("builtin-document-assistant") || id.equals("builtin-wiki-fixer"));
    }

    private void load() {
        ObjectNode presets;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("agentm/builtin_agents.yaml")) {
            if (in == null) {
                return;
            }
            Object raw = new org.yaml.snakeyaml.Yaml().load(in);
            presets = (ObjectNode) MAPPER.valueToTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load builtin_agents.yaml", e);
        }
        JsonNode agents = presets.get("builtin_agents");
        if (agents == null || !agents.isArray()) {
            return;
        }
        for (JsonNode a : agents) {
            String id = a.path("id").asText("");
            if (id.isEmpty()) {
                continue;
            }
            Map<String, String[]> i18n = new LinkedHashMap<>();
            JsonNode node = a.get("i18n");
            if (node != null && node.isObject()) {
                node.fields().forEachRemaining(e -> i18n.put(e.getKey(), new String[] {
                        e.getValue().path("name").asText(""),
                        e.getValue().path("description").asText("") }));
            }
            // 未知键丢弃 + 深拷贝
            ObjectNode cfg = MAPPER.createObjectNode();
            JsonNode rawCfg = a.get("config");
            if (rawCfg != null && rawCfg.isObject()) {
                rawCfg.fields().forEachRemaining(e -> {
                    if (CONFIG_KEYS.contains(e.getKey())) {
                        cfg.set(e.getKey(), e.getValue().deepCopy());
                    }
                });
            }
            entries.put(id, new Entry(id, a.path("avatar").asText(""),
                    a.path("is_builtin").asBoolean(false), i18n, cfg));
        }
        resolvePromptRefs();
    }

    /** id → content 解析（仅当 content 为空时填充）。 */
    private void resolvePromptRefs() {
        Map<String, String> templates = loadTemplates();
        for (Entry e : entries.values()) {
            ObjectNode cfg = e.config();
            String spid = cfg.path("systemPromptId").asText("");
            if (!spid.isEmpty() && cfg.path("systemPrompt").asText("").isEmpty()
                    && templates.containsKey(spid)) {
                cfg.put("systemPrompt", templates.get(spid));
            }
            String ctid = cfg.path("contextTemplateId").asText("");
            if (!ctid.isEmpty() && cfg.path("contextTemplate").asText("").isEmpty()
                    && templates.containsKey(ctid)) {
                cfg.put("contextTemplate", templates.get(ctid));
            }
        }
    }

    /** 按固定模板列表顺序查找，首个 id 命中即返回 content。 */
    private Map<String, String> loadTemplates() {
        Map<String, String> byId = new LinkedHashMap<>();
        for (String file : TEMPLATE_FILES) {
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream("agentm/prompt_templates/" + file)) {
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
                    if (!id.isEmpty() && !byId.containsKey(id)) {
                        byId.put(id, t.path("content").asText(""));
                    }
                }
            } catch (Exception ignored) {
                // Go：目录/文件缺失 → 跳过
            }
        }
        return byId;
    }

    /** 精确 → 语言前缀 → default → 第一项。 */
    public String[] resolveI18n(Entry entry, String locale) {
        Map<String, String[]> m = entry.i18n();
        if (m.isEmpty()) {
            return new String[] {"", ""};
        }
        String[] exact = m.get(locale);
        if (exact != null) {
            return exact;
        }
        int idx = indexOfAny(locale);
        if (idx > 0) {
            String lang = locale.substring(0, idx);
            String[] byLang = m.get(lang);
            if (byLang != null) {
                return byLang;
            }
            for (Map.Entry<String, String[]> e : m.entrySet()) {
                if (e.getKey().startsWith(lang)) {
                    return e.getValue();
                }
            }
        }
        String[] dflt = m.get("default");
        if (dflt != null) {
            return dflt;
        }
        return m.values().iterator().next();
    }

    private static int indexOfAny(String locale) {
        if (locale == null) {
            return -1;
        }
        for (int i = 0; i < locale.length(); i++) {
            char c = locale.charAt(i);
            if (c == '-' || c == '_') {
                return i;
            }
        }
        return -1;
    }

    /**
     * entry →（i18n 覆盖 name/description）→ 补默认。
     * 返回 config 树（已 defaults）；null = 非注册内建。
     */
    public ObjectNode builtinAgentConfig(String id, String locale) {
        Entry e = entries.get(id);
        if (e == null) {
            return null;
        }
        String[] i18n = resolveI18n(e, locale);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("name", i18n[0]);
        out.put("description", i18n[1]);
        out.put("avatar", e.avatar());
        out.set("config", AgentConfigJson.ensureDefaults(e.config().deepCopy()));
        return out;
    }

    public List<String> orderedIds() {
        return ORDERED_IDS;
    }

    public boolean registered(String id) {
        return entries.containsKey(id);
    }

    /** entry 的默认（default locale）形态——updateBuiltinAgent 用（GetBuiltinAgent 无 ctx）。 */
    public Entry entry(String id) {
        return entries.get(id);
    }

    /** env → Accept-Language 首个 tag → zh-CN。 */
    public static String localeFromRequest(String acceptLanguage) {
        String env = WikiLanguageSupport.envLanguage();
        if (!env.isEmpty()) {
            return env;
        }
        String lang = "";
        if (acceptLanguage != null && !acceptLanguage.isEmpty()) {
            String first = acceptLanguage.split(",", 2)[0].trim();
            lang = first.split(";", 2)[0].trim();
        }
        return lang.isEmpty() ? "zh-CN" : lang;
    }

    /** 供测试清理用（entries 是进程级单例，一次装载）。 */
    public List<String> entryIds() {
        return new ArrayList<>(entries.keySet());
    }
}
