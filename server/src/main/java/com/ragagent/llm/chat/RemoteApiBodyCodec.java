package com.ragagent.llm.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;


/**
 * 出站请求体键序/序列化协作者（自 {@link RemoteApiChat} 机械搬出，全静态）：
 * Go json.Marshal 等价序列化器（{@code GO_MARSHAL}）与两条键序归一路线——
 * map 字节序（{@code goSorted}，prompt-cache 改写路径）与 openai-go 结构体声明序
 * （{@code structSorted}，SDK 直出/thinking 包装路径）。门面 {@code Outbound.bodyBytes()}
 * 与测试直调的 {@code RemoteApiChat.goSorted} 委托至此。
 */
final class RemoteApiBodyCodec {

    /** 出站请求体序列化器：Go json.Marshal 等价（HTML 转义 < > &，见 §9 差分排查）。 */
    static final com.fasterxml.jackson.databind.json.JsonMapper GO_MARSHAL =
            goMarshal();

    private static com.fasterxml.jackson.databind.json.JsonMapper goMarshal() {
        com.fasterxml.jackson.databind.json.JsonMapper mapper =
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
        mapper.getFactory().setCharacterEscapes(new com.ragagent.common.web.GoJsonEscapes());
        return mapper;
    }

    /**
     * 按 Go 的 map 序列化顺序重排请求体：Go 的整个 chat 请求体是经 map 出去的，
     * {@code encoding/json} 对 map 一律按 key 的字节序输出，所以<b>每一层对象</b>
     * 都是字母序。2026-09-23 双端实录对拍坐实三处同源差异：顶层
     * {@code max_completion_tokens/messages/model/parallel_tool_calls/prompt_cache_key/
     * stream/stream_options/tools}、messages 元素 {@code content/role}、
     * 工具 schema {@code properties/required/type}——而 Java 侧的 ObjectNode 保持插入序。
     *
     * <p>键序比较用 UTF-8 字节（与 Go 一致，而非 Java 的 UTF-16 码元序）。</p>
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

    // ── openai-go 结构体字段序（v1.41.2 实录；SDK 直出路径的键序）─────────────

    /** 对照 ChatCompletionRequest 声明序（chat.go L263-328，含尾部 Extensions）。 */
    private static final List<String> SDK_TOP_ORDER = List.of(
            "model", "messages", "max_tokens", "max_completion_tokens", "temperature",
            "top_p", "n", "stream", "stop", "presence_penalty", "response_format", "seed",
            "frequency_penalty", "logit_bias", "logprobs", "top_logprobs", "user",
            "functions", "function_call", "tools", "tool_choice", "stream_options",
            "parallel_tool_calls", "store", "reasoning_effort", "metadata", "prediction",
            "chat_template_kwargs", "service_tier", "verbosity", "safety_identifier",
            "guided_choice");

    /** 父键 → 子对象字段序（对照各嵌套结构体声明序）。 */
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
     * 按 openai-go 结构体声明序重排（SDK 直出路径，对照 Go body=&req 的
     * json.Marshal）。规则：
     * <ul>
     *   <li>已知键按表序；未知键（thinking 包装字段如 enable_thinking）按插入序
     *       尾随——Go 的包装结构体把扩展字段声明在嵌入基座之后；</li>
     *   <li>Go map 类型字段（metadata/logit_bias/chat_template_kwargs）本应字母序，
     *       单键场景与插入序一致，从简不改；</li>
     *   <li>工具 parameters 子树不动——Go 里是 json.Marshaler（jsonschema 库结构体
     *       序），Java 的 schema 字面量本就按同序录入。</li>
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
