package com.ragagent.agentm.service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * agent 类型预设（对照 Go internal/types/agent_type_preset.go + config/agent_type_presets.yaml）。
 *
 * <p>响应形状 = AgentTypePresetEntry struct 序（id → i18n → config → kb_filter），
 * 内层 i18n 是 Go map → JSON 键<b>字母序</b>（resolveAgentTypeI18n 只保留
 * default + 命中 locale 两项）；config/kb_filter 的 omitempty 键在零值时整键省略。</p>
 */
@Component
public class AgentTypePresets {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** YAML 声明序（LoadAgentTypePresetsConfig 的 agentTypePresetIDs）。 */
    private final List<ObjectNode> entries = new ArrayList<>();

    public AgentTypePresets() {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("agentm/agent_type_presets.yaml")) {
            if (in == null) {
                return;
            }
            Object raw = new org.yaml.snakeyaml.Yaml().load(in);
            JsonNode root = MAPPER.valueToTree(raw);
            JsonNode list = root.get("agent_type_presets");
            if (list == null || !list.isArray()) {
                return;
            }
            for (JsonNode e : list) {
                String id = e.path("id").asText("");
                if (id.isEmpty()) {
                    continue; // Go：空 id 跳过
                }
                entries.add(e.deepCopy());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("failed to load agent_type_presets.yaml", ex);
        }
    }

    /** 对照 ListAgentTypePresetsWithContext。 */
    public ArrayNode list(String locale) {
        ArrayNode out = MAPPER.createArrayNode();
        for (ObjectNode e : entries) {
            ObjectNode item = out.addObject();
            item.put("id", e.path("id").asText(""));
            item.set("i18n", resolveI18n(e.get("i18n"), locale));
            JsonNode config = e.get("config");
            if (config != null && config.isObject() && config.size() > 0) {
                item.set("config", presetConfig((ObjectNode) config));
            }
            JsonNode filter = e.get("kb_filter");
            if (filter != null && filter.isObject() && filter.size() > 0) {
                ObjectNode f = item.putObject("kb_filter");
                copyIfNonEmptyArray((ObjectNode) filter, "any_of", f);
                copyIfNonEmptyArray((ObjectNode) filter, "all_of", f);
                copyIfNonEmptyArray((ObjectNode) filter, "none_of", f);
            }
        }
        return out;
    }

    /** AgentTypePresetConfig struct 序 + omitempty。 */
    private static ObjectNode presetConfig(ObjectNode c) {
        ObjectNode out = MAPPER.createObjectNode();
        String spid = c.path("system_prompt_id").asText("");
        if (!spid.isEmpty()) {
            out.put("system_prompt_id", spid);
        }
        double temp = c.path("temperature").asDouble(0);
        if (temp != 0) {
            out.put("temperature", temp);
        }
        int iters = c.path("max_iterations").asInt(0);
        if (iters != 0) {
            out.put("max_iterations", iters);
        }
        copyIfNonEmptyArray(c, "allowed_tools", out);
        if (c.path("retain_retrieval_history").asBoolean(false)) {
            out.put("retain_retrieval_history", true);
        }
        if (c.path("faq_priority_enabled").asBoolean(false)) {
            out.put("faq_priority_enabled", true);
        }
        if (c.path("web_search_enabled").asBoolean(false)) {
            out.put("web_search_enabled", true);
        }
        copyIfNonEmptyArray(c, "supported_file_types", out);
        String mode = c.path("kb_selection_mode").asText("");
        if (!mode.isEmpty()) {
            out.put("kb_selection_mode", mode);
        }
        return out;
    }

    /** 对照 resolveAgentTypeI18n：default + locale 两项（字母序由外层序列化器保证）。 */
    private static ObjectNode resolveI18n(JsonNode m, String locale) {
        ObjectNode out = MAPPER.createObjectNode();
        if (m == null || !m.isObject()) {
            return out;
        }
        JsonNode dflt = m.get("default");
        if (dflt != null) {
            out.set("default", dflt.deepCopy());
        }
        if (locale != null && !locale.isEmpty()) {
            JsonNode hit = m.get(locale);
            if (hit != null) {
                out.set(locale, hit.deepCopy());
            }
        }
        if (out.isEmpty()) {
            m.fields().forEachRemaining(e -> out.set(e.getKey(), e.getValue().deepCopy()));
        }
        return out;
    }

    private static void copyIfNonEmptyArray(ObjectNode src, String field, ObjectNode dst) {
        JsonNode n = src.get(field);
        if (n != null && n.isArray() && !n.isEmpty()) {
            dst.set(field, n.deepCopy());
        }
    }
}
