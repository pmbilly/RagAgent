package com.ragagent.agent.management.service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * agent 类型预设。
 *
 * <p>响应键序固定：id → i18n → config → kb_filter，
 * 内层 i18n 键<b>字母序</b>（只保留
 * default + 命中 locale 两项）；config/kb_filter 的零值键整键省略。</p>
 */
@Component
public class AgentTypePresets {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** YAML 声明序。 */
    private final List<ObjectNode> entries = new ArrayList<>();

    public AgentTypePresets() {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("agent/management/agent_type_presets.yaml")) {
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
                    continue; // 空 id 跳过
                }
                entries.add(e.deepCopy());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("failed to load agent_type_presets.yaml", ex);
        }
    }

    /** 按 locale 列出全部预设。 */
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

    /** 固定键序 + 零值键省略。 */
    private static ObjectNode presetConfig(ObjectNode c) {
        ObjectNode out = MAPPER.createObjectNode();
        String spid = c.path("systemPromptId").asText("");
        if (!spid.isEmpty()) {
            out.put("systemPromptId", spid);
        }
        double temp = c.path("temperature").asDouble(0);
        if (temp != 0) {
            out.put("temperature", temp);
        }
        int iters = c.path("maxIterations").asInt(0);
        if (iters != 0) {
            out.put("maxIterations", iters);
        }
        copyIfNonEmptyArray(c, "allowedTools", out);
        if (c.path("retainRetrievalHistory").asBoolean(false)) {
            out.put("retainRetrievalHistory", true);
        }
        if (c.path("faqPriorityEnabled").asBoolean(false)) {
            out.put("faqPriorityEnabled", true);
        }
        if (c.path("webSearchEnabled").asBoolean(false)) {
            out.put("webSearchEnabled", true);
        }
        copyIfNonEmptyArray(c, "supportedFileTypes", out);
        String mode = c.path("kbSelectionMode").asText("");
        if (!mode.isEmpty()) {
            out.put("kbSelectionMode", mode);
        }
        return out;
    }

    /** i18n 只保留 default + locale 两项（字母序由外层序列化器保证）。 */
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
