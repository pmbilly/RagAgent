package com.ragagent.llm.extract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.graph.GraphNode;
import com.ragagent.common.graph.GraphRelation;
import com.ragagent.llm.extract.PipelineConfig.PromptTemplateStructured;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.common.web.JsonMappers;
import com.ragagent.common.web.ToolJson;

/**
 * 实体抽取的提示词生成与 LLM 输出解析。
 *
 * <h2>围栏恢复</h2>
 * 围栏正则 {@code ```(lang)?\n body ```} 非贪婪匹配语言标签 + body；多候选取首；
 * 无候选时回落 stripFencesAndExtract（截断回复的开围栏 / 裸 JSON 提取）。
 * Graph 重建：重名节点合并 attributes；自环关系丢弃；未知端点自动补节点。
 */
public final class EntityExtraction {

    private static final ObjectMapper JSON = JsonMappers.lenient();

    private EntityExtraction() {}

    // ------------------------------------------------------------------
    // Extractor
    // ------------------------------------------------------------------

    /** 抽取器（对照 Extractor）。 */
    public static final class Extractor {
        private final LlmChatClient chat;
        private final Formater formater;
        private final PromptTemplateStructured template;
        private final ChatOptions chatOpt;

        public Extractor(LlmChatClient chatModel, PromptTemplateStructured template) {
            this.chat = chatModel;
            this.formater = new Formater();
            this.template = template;
            this.chatOpt = new ChatOptions();
            this.chatOpt.setTemperature(0.3);
            this.chatOpt.setMaxTokens(4096);
            this.chatOpt.setThinking(Boolean.FALSE);
        }

        /** 对照 Extract：LLM 调用 + ParseGraph。失败抛异常。 */
        public EntityGraph extract(String content) {
            QAPromptGenerator generator = new QAPromptGenerator(this.formater, this.template);
            List<ChatMessage> messages = generator.render(content);
            com.ragagent.llm.domain.ChatResponse chatResponse = chat.chat(messages, chatOpt);
            return formater.parseGraph(chatResponse.getContent());
        }
    }

    /** 对照 types.GraphData 的抽取形态（nodes + relations，可变）。 */
    public static final class EntityGraph {
        public List<GraphNode> node = new ArrayList<>();
        public List<GraphRelation> relation = new ArrayList<>();
    }

    // ------------------------------------------------------------------
    // QAPromptGenerator
    // ------------------------------------------------------------------

    /** QA 提示词生成器（对照 QAPromptGenerator）。 */
    public static final class QAPromptGenerator {
        private final Formater formater;
        private final PromptTemplateStructured template;
        private final String examplesHeading = "# Examples";
        private final String questionHeading = "# Question";
        private final String questionPrefix = "Q: ";
        private final String answerPrefix = "A: ";

        public QAPromptGenerator(Formater formater, PromptTemplateStructured template) {
            this.formater = formater;
            this.template = template;
        }

        /** 对照 System：模板 description（带 %s 占位时以 tags JSON 填充）+ 示例。 */
        public String system() {
            List<String> promptLines = new ArrayList<>();

            if (template.getTags() == null || template.getTags().isEmpty()) {
                promptLines.add(template.getDescription());
            } else {
                String tags = toJsonArray(template.getTags());
                promptLines.add(String.format(template.getDescription(), tags));
            }
            if (template.getExamples() != null && !template.getExamples().isEmpty()) {
                promptLines.add(examplesHeading);
                for (PromptTemplateStructured.Example example : template.getExamples()) {
                    promptLines.add(String.format("%s%s", questionPrefix, example.getText().trim()));
                    String answer;
                    try {
                        answer = formater.formatExtraction(example.getNode(), example.getRelation());
                    } catch (RuntimeException e) {
                        return "";
                    }
                    promptLines.add(String.format("%s%s", answerPrefix, answer));
                    promptLines.add("");
                }
            }
            return String.join("\n", promptLines);
        }

        /** 对照 User。 */
        public String user(String question) {
            List<String> promptLines = new ArrayList<>();
            promptLines.add(questionHeading);
            promptLines.add(String.format("%s%s", questionPrefix, question));
            promptLines.add(answerPrefix);
            return String.join("\n", promptLines);
        }

        /** 对照 Render。 */
        public List<ChatMessage> render(String question) {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatMessage("system", system()));
            messages.add(new ChatMessage("user", user(question)));
            return messages;
        }
    }

    private static String toJsonArray(List<String> tags) {
        return ToolJson.compactJson(tags);
    }

    /** 值字符串化（Java 原生）：标量走 asText、容器走 JSON 形态、null → "null"。 */
    private static String valueStr(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        return node.isContainerNode() ? node.toString() : node.asText();
    }

    // ------------------------------------------------------------------
    // Formater
    // ------------------------------------------------------------------

    /** 格式化/解析器（对照 Formater，json + 围栏形态）。 */
    public static final class Formater {
        private final String attributeSuffix = "_attributes";
        private final String nodePrefix = "entity";
        private final String relationSource = "entity1";
        private final String relationTarget = "entity2";
        private final String relationPrefix = "relation";

        /** 对照 formatExtraction：nodes+relations → 带围栏的缩进 JSON。 */
        public String formatExtraction(List<GraphNode> nodes, List<GraphRelation> relations) {
            List<Map<String, Object>> items = new ArrayList<>();
            if (nodes != null) {
                for (GraphNode node : nodes) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put(nodePrefix, node.getName());
                    if (node.getAttributes() != null && !node.getAttributes().isEmpty()) {
                        item.put(nodePrefix + attributeSuffix, node.getAttributes());
                    }
                    items.add(item);
                }
            }
            if (relations != null) {
                for (GraphRelation relation : relations) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put(relationSource, relation.node1());
                    item.put(relationTarget, relation.node2());
                    item.put(relationPrefix, relation.type());
                    items.add(item);
                }
            }
            String formatted = ToolJson.prettyJson(items);
            formatted = addFences(formatted);
            return formatted;
        }

        /**
         * 对照 parseOutput：抽体 + JSON 解析 + 形状校验。失败抛 IllegalArgumentException。
         * 值保持 JsonNode（null 值键视为不存在）。
         */
        List<Map<String, JsonNode>> parseOutput(String text) {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("empty or invalid input string");
            }
            String content = extractContent(text);
            if (content.isEmpty()) {
                throw new IllegalArgumentException("empty or invalid input string");
            }

            JsonNode parsed;
            try {
                parsed = JSON.readTree(content);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        String.format("failed to parse %s content: %s", "JSON", e.getMessage()));
            }
            if (parsed == null || parsed.isNull()) {
                throw new IllegalArgumentException("content must be a list of extractions or a dict");
            }

            JsonNode itemsNode;
            if (parsed.isObject()) {
                itemsNode = JSON.createArrayNode().add(parsed);
            } else if (parsed.isArray()) {
                itemsNode = parsed;
            } else {
                throw new IllegalArgumentException("expected list or dict, got " + jsonKind(parsed));
            }

            List<Map<String, JsonNode>> itemsList = new ArrayList<>();
            for (JsonNode item : itemsNode) {
                if (item.isObject()) {
                    Map<String, JsonNode> row = new LinkedHashMap<>();
                    var fields = item.fields();
                    while (fields.hasNext()) {
                        var e = fields.next();
                        // null 值键等价"不存在"，跳过
                        if (e.getValue() == null || e.getValue().isNull()) {
                            continue;
                        }
                        row.put(e.getKey(), e.getValue());
                    }
                    itemsList.add(row);
                } else {
                    throw new IllegalArgumentException("each item in the sequence must be a mapping.");
                }
            }
            return itemsList;
        }

        private static String jsonKind(JsonNode n) {
            if (n.isTextual()) {
                return "string";
            }
            if (n.isNumber()) {
                return "float64";
            }
            if (n.isBoolean()) {
                return "bool";
            }
            return "interface {}";
        }

        /** 对照 ParseGraph。 */
        public EntityGraph parseGraph(String text) {
            List<Map<String, JsonNode>> matchData = parseOutput(text);
            if (matchData.isEmpty()) {
                return new EntityGraph();
            }

            List<GraphNode> nodes = new ArrayList<>();
            List<GraphRelation> relations = new ArrayList<>();

            for (Map<String, JsonNode> group : matchData) {
                JsonNode nodeVal = group.get(nodePrefix);
                JsonNode srcVal = group.get(relationSource);
                JsonNode tgtVal = group.get(relationTarget);
                if (nodeVal != null) {
                    // 对照 group[attributesKey].([]interface{})：必须是数组才收
                    List<String> attributes = new ArrayList<>();
                    String attributesKey = nodePrefix + attributeSuffix;
                    JsonNode attrs = group.get(attributesKey);
                    if (attrs != null && attrs.isArray()) {
                        for (JsonNode v : attrs) {
                            attributes.add(valueStr(v));
                        }
                    }
                    nodes.add(new GraphNode(valueStr(nodeVal), null, attributes));
                } else if (srcVal != null && tgtVal != null) {
                    // 键缺失 → "null"（Java 原生形态；B43 前为 Go 的 "<nil>"）
                    JsonNode relType = group.get(relationPrefix);
                    relations.add(new GraphRelation(valueStr(srcVal),
                            valueStr(tgtVal),
                            relType == null ? "null" : valueStr(relType)));
                }
                // 其余组告警跳过（unsupported group）
            }
            EntityGraph graph = new EntityGraph();
            graph.node = nodes;
            graph.relation = relations;
            rebuildGraph(graph);
            return graph;
        }

        /** 对照 rebuildGraph：重名合并/自环丢弃/未知端点补节点。 */
        private void rebuildGraph(EntityGraph graph) {
            Map<String, GraphNode> nodeMap = new LinkedHashMap<>();
            List<GraphNode> nodes = new ArrayList<>(graph.node.size());
            for (GraphNode node : graph.node) {
                GraphNode prenode = nodeMap.get(node.getName());
                if (prenode != null) {
                    if (node.getAttributes() == null) {
                        node.setAttributes(new ArrayList<>());
                    }
                    if (prenode.getAttributes() != null) {
                        node.getAttributes().addAll(prenode.getAttributes());
                    }
                    continue;
                }
                nodeMap.put(node.getName(), node);
                nodes.add(node);
            }

            List<GraphRelation> relations = new ArrayList<>(graph.relation.size());
            for (GraphRelation relation : graph.relation) {
                if (relation.node1().equals(relation.node2())) {
                    continue;
                }
                if (!nodeMap.containsKey(relation.node1())) {
                    GraphNode node = new GraphNode(relation.node1(), null, null);
                    nodes.add(node);
                    nodeMap.put(relation.node1(), node);
                }
                if (!nodeMap.containsKey(relation.node2())) {
                    GraphNode node = new GraphNode(relation.node2(), null, null);
                    nodes.add(node);
                    nodeMap.put(relation.node2(), node);
                }
                relations.add(relation);
            }
            graph.node = nodes;
            graph.relation = relations;
        }

        /** 对照 extractContent：围栏候选筛选 + 回退提取。 */
        String extractContent(String text) {
            List<String> matches = findAllFences(text);
            List<String> candidates = new ArrayList<>();
            List<String> matchLangs = new ArrayList<>();
            List<String> matchBodies = new ArrayList<>();
            collectFences(text, matches, matchLangs, matchBodies);
            matches.clear();

            for (int i = 0; i < matchLangs.size(); i++) {
                String lang = matchLangs.get(i);
                String body = matchBodies.get(i);
                if (isValidLanguageTag(lang)) {
                    candidates.add(body);
                }
            }
            if (candidates.size() == 1) {
                return candidates.get(0).trim();
            }
            if (candidates.size() > 1) {
                return candidates.get(0).trim();
            }
            if (matchBodies.size() == 1) {
                return matchBodies.get(0).trim();
            }
            if (matchBodies.size() > 1) {
                return matchBodies.get(0).trim();
            }
            // 围栏正则失配的回退：截断回复 / 裸 JSON
            String extracted = stripFencesAndExtract(text);
            if (!extracted.isEmpty()) {
                return extracted;
            }
            return text.trim();
        }

        /** 对照 stripFencesAndExtract：三种失配形态的恢复。 */
        static String stripFencesAndExtract(String text) {
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.isEmpty()) {
                return "";
            }

            // Case 1: 有开围栏无闭围栏（截断）
            int idx = trimmed.indexOf("```");
            if (idx >= 0) {
                String rest = trimmed.substring(idx + 3);
                int nl = rest.indexOf('\n');
                if (nl >= 0) {
                    String firstLine = rest.substring(0, nl).trim();
                    if (firstLine.isEmpty() || isLikelyLanguageTag(firstLine)) {
                        rest = rest.substring(nl + 1);
                    }
                }
                int end = rest.indexOf("```");
                if (end >= 0) {
                    rest = rest.substring(0, end);
                }
                rest = rest.trim();
                rest = trimRunes(rest, '`');
                rest = rest.trim();
                if (!rest.isEmpty()) {
                    return rest;
                }
            }

            // Case 2: 无围栏，抓最外层 JSON
            return extractJSONLike(trimmed);
        }

        static String trimRunes(String s, char c) {
            int start = 0;
            int end = s.length();
            while (start < end && s.charAt(start) == c) {
                start++;
            }
            while (end > start && s.charAt(end - 1) == c) {
                end--;
            }
            return s.substring(start, end);
        }

        /** 对照 isLikelyLanguageTag。 */
        static boolean isLikelyLanguageTag(String s) {
            if (s == null || s.isEmpty() || s.length() > 16) {
                return false;
            }
            for (int i = 0; i < s.length(); i++) {
                char r = s.charAt(i);
                boolean ok = (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z')
                        || (r >= '0' && r <= '9') || r == '_' || r == '-' || r == '+';
                if (!ok) {
                    return false;
                }
            }
            return true;
        }

        /** 对照 extractJSONLike：最外层 {...} / [...]（字符串感知）。 */
        static String extractJSONLike(String s) {
            int objStart = s.indexOf('{');
            int arrStart = s.indexOf('[');
            char open;
            char closeCh;
            int start;
            if (objStart < 0 && arrStart < 0) {
                return "";
            }
            if (objStart < 0) {
                open = '[';
                closeCh = ']';
                start = arrStart;
            } else if (arrStart < 0) {
                open = '{';
                closeCh = '}';
                start = objStart;
            } else if (objStart < arrStart) {
                open = '{';
                closeCh = '}';
                start = objStart;
            } else {
                open = '[';
                closeCh = ']';
                start = arrStart;
            }
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                        continue;
                    }
                    if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == open) {
                    depth++;
                } else if (c == closeCh) {
                    depth--;
                    if (depth == 0) {
                        return s.substring(start, i + 1).trim();
                    }
                }
            }
            return "";
        }

        private String addFences(String content) {
            String c = content.trim();
            return "```json\n" + c + "\n```";
        }

        private boolean isValidLanguageTag(String lang) {
            if (lang == null || lang.isEmpty()) {
                return true;
            }
            String tag = lang.trim().toLowerCase(java.util.Locale.ROOT);
            return "json".equals(tag);
        }
    }

    // 围栏正则（对照 _FENCE_RE：```(lang)?(\s*\n)?(body)```，DOTALL 由 (?s) 等价承担）
    private static final Pattern FENCE_RE = Pattern.compile(
            "```(?<lang>[A-Za-z0-9_+-]+)?(?:\\s*\\n)?(?<body>[\\s\\S]*?)```");

    /** 围栏全文扫描（对照 FindAllStringSubmatch 的调用点形态）。 */
    private static List<String> findAllFences(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = FENCE_RE.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static void collectFences(String text, List<String> ignored, List<String> langs,
                                      List<String> bodies) {
        Matcher m = FENCE_RE.matcher(text);
        while (m.find()) {
            String lang = m.group("lang");
            langs.add(lang == null ? "" : lang);
            bodies.add(m.group("body"));
        }
    }
}
