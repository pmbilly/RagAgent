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
 * Notion 的一个内容块。
 *
 * <p><b>内部 API 形状，不是契约</b>：只进出于 Notion API 的 JSON，从不落 jsonb、
 * 从不作 HTTP 响应体。</p>
 *
 * <h2>自定义反序列化</h2>
 * <p>Notion 的块是"以类型名做键"的多态形状：</p>
 * <pre>
 *   {"id":"…","type":"paragraph","has_children":false,"paragraph":{"rich_text":[…]}}
 * </pre>
 * <p>做法是：先读 {@code id}/{@code type}/{@code has_children}，再取出以
 * {@code type} 命名的那个键的<b>原始 JSON 节点</b>存进 {@link #rawContent}。
 * 下游的 {@code extractRichText(raw)} 等抽取函数正好都吃节点。</p>
 *
 * <h2>两处关键细节</h2>
 * <ol>
 *   <li><b>type 为空串时不抽 RawContent</b>，
 *       于是未知块类型的 {@code RawContent} 是 {@code null}。</li>
 *   <li><b>{@code RawContent} 为 {@code null} 与"是个 JSON null"是两回事</b>：
 *       {@code {"type":"x","x":null}} 会留下一个 {@code NullNode}（**非 null**），
 *       下游解出来是空对象而不是"没有内容"。所以下游一律写成
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

    /** 从"以 type 命名的字段"抽出来的原始内容（不参与 JSON）。 */
    @JsonIgnore
    public JsonNode rawContent;

    /**
     * 由 {@code NotionClient.getBlockChildrenAll} 递归填充（**不是** API 直接给的）；
     * 不参与 JSON。
     */
    @JsonIgnore
    public List<NotionBlock> children;

    public String id() {
        return id == null ? "" : id;
    }

    /** 缺字段即 {@code ""}。 */
    public String type() {
        return type == null ? "" : type;
    }

    /** 自定义反序列化器（见类注释）。 */
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

            // 只有 type 非空时才去抽以 type 命名的那个字段。
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
