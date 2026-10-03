package com.ragagent.llm.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;


/**
 * 出站请求体键序/序列化协作者（自 {@link RemoteApiChat} 拆出，全静态）：
 * 请求体序列化器（{@code GO_MARSHAL}）与两条键序归一路线——
 * map 字节序（{@code goSorted}，prompt-cache 改写路径）与 openai-go 结构体声明序
 * （{@code structSorted}，SDK 直出/thinking 包装路径）。门面 {@code Outbound.bodyBytes()}
 * 与测试直调的 {@code RemoteApiChat.goSorted} 委托至此。
 *
 * <p><b>2026-10-03（B38）</b>：Go 版已下线——序列化器不再复刻 Go 的 HTML 转义；
 * 键序归一保留（prompt-cache 与结构体声明序是请求体自身的形态约束）。</p>
 */
final class RemoteApiBodyCodec {

    /** 出站请求体序列化器（标准 JSON 转义）。 */
    static final com.fasterxml.jackson.databind.json.JsonMapper GO_MARSHAL =
            com.fasterxml.jackson.databind.json.JsonMapper.builder().build();

    /**
     * 按键的 UTF-8 字节序重排请求体：<b>每一层对象</b>都按字母序输出。
     * 两侧线格式在此处分叉的键包括：顶层
     * {@code max_completion_tokens/messages/model/parallel_tool_calls/prompt_cache_key/
     * stream/stream_options/tools}、messages 元素 {@code content/role}、
     * 工具 schema {@code properties/required/type}——而 Java 侧的 ObjectNode 保持插入序，故需重排。
     *
     * <p>键序比较用 UTF-8 字节序，而非 Java 字符串的 UTF-16 码元序。</p>
     */
    static JsonNode goSorted(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort((a, b) -> java.util.Arrays.compare(
                    a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    b.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                sorted.set(name, goSorted(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node) {
                sorted.add(goSorted(item));
            }
            return sorted;
        }
        return node;
    }

    // ── openai-go 结构体字段序（v1.41.2；SDK 直出路径的键序）─────────────

    /** openai-go ChatCompletionRequest 的声明序（含尾部 Extensions）。 */
    private static final List<String> SDK_TOP_ORDER = List.of(
            "model", "messages", "max_tokens", "max_completion_tokens", "temperature",
            "top_p", "n", "stream", "stop", "presence_penalty", "response_format", "seed",
            "frequency_penalty", "logit_bias", "logprobs", "top_logprobs", "user",
            "functions", "function_call", "tools", "tool_choice", "stream_options",
            "parallel_tool_calls", "store", "reasoning_effort", "metadata", "prediction",
            "chat_template_kwargs", "service_tier", "verbosity", "safety_identifier",
            "guided_choice");

    /** 父键 → 子对象字段序（各嵌套结构体的声明序）。 */
    private static final Map<String, List<String>> SDK_NESTED_ORDER = Map.of(
            "messages", List.of("role", "content", "refusal", "name", "reasoning_content",
                    "function_call", "tool_calls", "tool_call_id"),
            "tools", List.of("type", "function"),
            "functions", List.of("name", "description", "strict", "parameters"),
            "function", List.of("name", "description", "strict", "parameters"),
            "message_function", List.of("name", "arguments"),
            "tool_calls", List.of("index", "id", "type", "function"),
            "stream_options", List.of("include_usage"),
            "response_format", List.of("type", "json_schema"),
            "json_schema", List.of("name", "description", "schema", "strict"));

    /**
     * 按 openai-go 结构体声明序重排（SDK 直出路径）。规则：
     * <ul>
     *   <li>已知键按表序；未知键（thinking 包装字段如 enable_thinking）按插入序
     *       尾随（扩展字段排在已知字段之后）；</li>
     *   <li>map 型字段（metadata/logit_bias/chat_template_kwargs）本应字母序，
     *       单键场景与插入序一致，从简不改；</li>
     *   <li>工具 parameters 子树不动（jsonschema 结构体序，schema 字面量本就按同序录入）。</li>
     * </ul>
     */
    static JsonNode structSorted(JsonNode node) {
        return structSorted(node, SDK_TOP_ORDER);
    }

    private static JsonNode structSorted(JsonNode node, List<String> order) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            List<String> known = new ArrayList<>(names);
            List<String> unknown = new ArrayList<>();
            for (String name : names) {
                if (order.contains(name)) {
                    continue;
                }
                known.remove(name);
                unknown.add(name);
            }
            known.sort(java.util.Comparator.comparingInt(order::indexOf));
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            for (String name : known) {
                sorted.set(name, structSortedChild(name, node.get(name)));
            }
            for (String name : unknown) {
                sorted.set(name, structSortedChild(name, node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node) {
                sorted.add(structSorted(item, order));
            }
            return sorted;
        }
        return node;
    }

    /** 子节点按父键选表；parameters 子树（jsonschema 结构体序）原样保留。 */
    private static JsonNode structSortedChild(String parentKey, JsonNode child) {
        if ("parameters".equals(parentKey)) {
            return child;
        }
        List<String> childOrder = SDK_NESTED_ORDER.get(parentKey);
        if (childOrder == null) {
            return structSorted(child, SDK_TOP_ORDER);
        }
        return structSorted(child, childOrder);
    }

}
