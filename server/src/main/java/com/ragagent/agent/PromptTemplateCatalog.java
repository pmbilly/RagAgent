package com.ragagent.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 提示词模板目录，供 {@code GET /api/v1/tenants/kv/prompt-templates} 消费。
 *
 * <p>模板文件 vendored 到 classpath {@code agentm/prompt_templates/}，
 * 启动时装载一次（进程内缓存）。</p>
 *
 * <p>JSON 保真点：</p>
 * <ul>
 *   <li>模板字段顺序固定：id/name/description/content，随后
 *       user/has_knowledge_base/has_web_search/default/mode（空串与 false 省略），
 *       i18n 从不出现在响应里；</li>
 *   <li>config 字段顺序固定；system_prompt/context_template/
 *       rewrite/fallback 恒输出（空 → null），其余空 → 键缺席；</li>
 *   <li>localized 副本只搬 9 个字段——graph_extraction /
 *       generate_questions 不落进 localized（键恒缺席）。</li>
 * </ul>
 */
public final class PromptTemplateCatalog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单个模板（i18n 只用于本地化，不进 JSON）。 */
    public record Template(String id, String name, String description, String content,
                           String user, boolean hasKnowledgeBase, boolean hasWebSearch,
                           boolean dflt, String mode, Map<String, I18n> i18n) {

        public Template {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            description = description == null ? "" : description;
            content = content == null ? "" : content;
            user = user == null ? "" : user;
            mode = mode == null ? "" : mode;
            i18n = i18n == null ? Map.of() : i18n;
        }
    }

    /** name/description 的本地化覆盖项。 */
    public record I18n(String name, String description) {}

    /**
     * 模板配置集。{@code null} 列表 = 文件缺失
     * （恒输出键 → null，可省键 → 键缺席）。
     */
    public record Config(List<Template> systemPrompt, List<Template> contextTemplate,
                         List<Template> rewrite, List<Template> fallback,
                         List<Template> generateSessionTitle, List<Template> generateSummary,
                         List<Template> keywordsExtraction, List<Template> agentSystemPrompt,
                         List<Template> intentPrompts) {}

    /** 模板文件目录（graph_extraction/generate_questions 不装载——handler 不消费）。 */
    private static final String DIR = "agentm/prompt_templates/";

    private static volatile Config cached;

    private PromptTemplateCatalog() {}

    /** 启动时装载一次（进程内缓存；文件缺失 → 对应字段 null）。 */
    public static Config load() {
        Config c = cached;
        if (c == null) {
            synchronized (PromptTemplateCatalog.class) {
                if (cached == null) {
                    cached = new Config(
                            loadFile("system_prompt.yaml"),
                            loadFile("context_template.yaml"),
                            loadFile("rewrite.yaml"),
                            loadFile("fallback.yaml"),
                            loadFile("generate_session_title.yaml"),
                            loadFile("generate_summary.yaml"),
                            loadFile("keywords_extraction.yaml"),
                            loadFile("agent_system_prompt.yaml"),
                            loadFile("intent_prompts.yaml"));
                }
                c = cached;
            }
        }
        return c;
    }

    /** 测试用：清缓存强制重载。 */
    public static void resetForTest() {
        cached = null;
    }

    private static List<Template> loadFile(String fileName) {
        try (var in = PromptTemplateCatalog.class.getClassLoader()
                .getResourceAsStream(DIR + fileName)) {
            if (in == null) {
                return null; // 文件不存在 → 跳过（对应字段保持 null）
            }
            JsonNode root = MAPPER.valueToTree(new org.yaml.snakeyaml.Yaml().load(in));
            JsonNode list = root == null ? null : root.get("templates");
            if (list == null || !list.isArray()) {
                return null;
            }
            List<Template> out = new ArrayList<>();
            for (JsonNode t : list) {
                Map<String, I18n> i18n = new LinkedHashMap<>();
                JsonNode i18nNode = t.get("i18n");
                if (i18nNode != null && i18nNode.isObject()) {
                    i18nNode.fields().forEachRemaining(e -> {
                        JsonNode v = e.getValue();
                        i18n.put(e.getKey(), new I18n(
                                v.path("name").asText(""), v.path("description").asText("")));
                    });
                }
                out.add(new Template(
                        t.path("id").asText(""),
                        t.path("name").asText(""),
                        t.path("description").asText(""),
                        t.path("content").asText(""),
                        t.path("user").asText(""),
                        t.path("has_knowledge_base").asBoolean(false),
                        t.path("has_web_search").asBoolean(false),
                        t.path("default").asBoolean(false),
                        t.path("mode").asText(""),
                        i18n));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse " + fileName + ": " + e.getMessage(), e);
        }
    }

    /**
     * 深拷贝列表，按 locale 替换 name/description。
     * 回退链：精确匹配（zh-CN）→ 主语言子标签（zh）→ 保留原文。
     */
    public static List<Template> localize(List<Template> templates, String locale) {
        if (templates == null || templates.isEmpty()) {
            return templates;
        }
        List<Template> out = new ArrayList<>(templates);
        for (int i = 0; i < out.size(); i++) {
            Template t = out.get(i);
            if (t.i18n().isEmpty()) {
                continue;
            }
            I18n l10n = t.i18n().get(locale);
            if (l10n == null) {
                int idx = locale.indexOf('-');
                if (idx > 0) {
                    l10n = t.i18n().get(locale.substring(0, idx));
                }
            }
            if (l10n == null) {
                continue;
            }
            out.set(i, new Template(t.id(),
                    l10n.name().isEmpty() ? t.name() : l10n.name(),
                    l10n.description().isEmpty() ? t.description() : l10n.description(),
                    t.content(), t.user(), t.hasKnowledgeBase(), t.hasWebSearch(),
                    t.dflt(), t.mode(), t.i18n()));
        }
        return out;
    }

    /**
     * localized 副本 + JSON 输出：
     * 只搬 9 个字段（graph_extraction/generate_questions 恒缺席），
     * 键序固定，空值省略规则逐字段复刻。
     */
    public static ObjectNode toJson(Config cfg, String locale) {
        ObjectNode data = MAPPER.createObjectNode();
        putAlways(data, "systemPrompt", localize(cfg.systemPrompt(), locale));
        putAlways(data, "contextTemplate", localize(cfg.contextTemplate(), locale));
        putAlways(data, "rewrite", localize(cfg.rewrite(), locale));
        putAlways(data, "fallback", localize(cfg.fallback(), locale));
        putOmitEmpty(data, "generateSessionTitle", cfg.generateSessionTitle());
        putOmitEmpty(data, "generateSummary", cfg.generateSummary());
        putOmitEmpty(data, "keywordsExtraction", cfg.keywordsExtraction());
        putOmitEmpty(data, "agentSystemPrompt", localize(cfg.agentSystemPrompt(), locale));
        putOmitEmpty(data, "intentPrompts", localize(cfg.intentPrompts(), locale));
        return data;
    }

    /** 恒输出：空（文件缺失）→ null。 */
    private static void putAlways(ObjectNode data, String key, List<Template> list) {
        if (list == null) {
            data.putNull(key);
            return;
        }
        data.set(key, templatesJson(list));
    }

    private static void putOmitEmpty(ObjectNode data, String key, List<Template> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        data.set(key, templatesJson(list));
    }

    private static ArrayNode templatesJson(List<Template> list) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Template t : list) {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("id", t.id());
            o.put("name", t.name());
            o.put("description", t.description());
            o.put("content", t.content());
            if (!t.user().isEmpty()) {
                o.put("user", t.user());
            }
            if (t.hasKnowledgeBase()) {
                o.put("has_knowledge_base", true);
            }
            if (t.hasWebSearch()) {
                o.put("has_web_search", true);
            }
            if (t.dflt()) {
                o.put("default", true);
            }
            if (!t.mode().isEmpty()) {
                o.put("mode", t.mode());
            }
            arr.add(o);
        }
        return arr;
    }
}
