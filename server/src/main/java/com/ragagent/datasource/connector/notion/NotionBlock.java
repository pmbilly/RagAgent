package com.ragagent.datasource.connector.notion;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Notion 的一个内容块（对照 Go {@code notionBlock}，types.go L112-153）。
 *
 * <p><b>内部 API 形状，不是契约</b>：只进出于 Notion API 的 JSON，从不落 jsonb、
 * 从不作 HTTP 响应体。</p>
 *
 * <h2>自定义反序列化（Go 的 {@code UnmarshalJSON}）</h2>
 * <p>Notion 的块是"以类型名做键"的多态形状：</p>
 * <pre>
 *   {"id":"…","type":"paragraph","has_children":false,"paragraph":{"rich_text":[…]}}
 * </pre>
 * <p>Go 的做法是：先把 {@code id}/{@code type}/{@code has_children} 读进一个
 * 辅助结构（避免递归），再<b>整份重新解一遍</b>成 {@code map[string]RawMessage}，
 * 取出以 {@code type} 命名的那个键的<b>原始 JSON</b> 塞进 {@code RawContent}。
 * Java 侧等价地读成 {@link JsonNode}，取出 {@code node.get(type)} 存进
 * {@link #rawContent}——语义一致，且下游的 {@code extractRichText(raw)} 等
 * 抽取函数正好都吃节点。</p>
 *
 * <h2>两处必须照抄的细节</h2>
 * <ol>
 *   <li><b>type 为空串时不抽 RawContent</b>（Go 的 {@code if alias.Type != ""}），
 *       于是未知块类型的 {@code RawContent} 是 {@code null}。</li>
 *   <li><b>{@code RawContent} 为 {@code null} 与"是个 JSON null"是两回事</b>：
 *       Go 里 {@code {"type":"x","x":null}} 会留下 {@code RawMessage("null")}（**非 nil**），
 *       下游的 {@code if raw == nil} 判断为假、但解出来是零值对象。Java 侧用
 *       {@code NullNode} 表达同一种情况——所以下游一律写成
 *       "节点为 null → 空结果；否则从节点里取字段（取不到即空）"。</li>
 * </ol>
 */
@JsonDeserialize(using = NotionBlock.Deserializer.class)
public final class NotionBlock {

    @JsonProperty("id")
    public String id;

    @JsonProperty("type")
    public String type;

    @JsonProperty("has_children")
    public boolean hasChildren;

    /** 从"以 type 命名的字段"抽出来的原始内容；对照 Go 的 {@code json:"-"}。 */
    @JsonIgnore
    public JsonNode rawContent;

    /**
     * 由 {@code NotionClient.getBlockChildrenAll} 递归填充（**不是** API 直接给的）；
     * 对照 Go 的 {@code json:"-"}。
     */
    @JsonIgnore
    public List<NotionBlock> children;

    public String id() {
        return id == null ? "" : id;
    }

    /** 对照 Go {@code Type} 的零值语义：缺字段即 {@code ""}。 */
    public String type() {
        return type == null ? "" : type;
    }

    /** 对照 Go {@code (*notionBlock).UnmarshalJSON}（见类注释）。 */
    public static final class Deserializer extends JsonDeserializer<NotionBlock> {

        @Override
        public NotionBlock deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            JsonNode node = parser.readValueAsTree();
            NotionBlock block = new NotionBlock();
            if (node == null || !node.isObject()) {
                return block;
            }
            JsonNode idNode = node.get("id");
            block.id = idNode != null && idNode.isTextual() ? idNode.textValue() : null;
            JsonNode typeNode = node.get("type");
            block.type = typeNode != null && typeNode.isTextual() ? typeNode.textValue() : null;
            JsonNode hasChildrenNode = node.get("has_children");
            block.hasChildren = hasChildrenNode != null && hasChildrenNode.asBoolean(false);

            // 对照 Go：只有 type 非空时才去抽以 type 命名的那个字段。
            if (block.type != null && !block.type.isEmpty()) {
                JsonNode content = node.get(block.type);
                if (content != null) {
                    block.rawContent = content;
                }
            }
            return block;
        }
    }
}
