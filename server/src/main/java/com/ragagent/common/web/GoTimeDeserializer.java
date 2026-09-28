package com.ragagent.common.web;

import java.io.IOException;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@link GoTimeSerializer} 的读回一半。
 *
 * <h2>为什么必须成对提供</h2>
 * <p>jsonb 列的读路径用的是**裸** {@code ObjectMapper}（见
 * {@code AbstractJsonListTypeHandler}）——它没注册 {@code JavaTimeModule}，
 * 遇到 {@code java.time} 类型会直接抛 {@code InvalidDefinitionException}。
 * 这也是本项目此前没人往 jsonb 类型里放时间字段的原因：
 * 序列化侧有 {@code JacksonConfig} 兜着，读回侧没有。</p>
 *
 * <p>挂上 {@code @JsonSerialize}/{@code @JsonDeserialize} 之后两个方向都自足，
 * 不依赖任何全局 mapper 配置，也不受 MyBatis-Plus 那个内置 mapper 的影响。</p>
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>JSON {@code null} / 空串 → Go 零值时间（不是 null——Go 的零值时间没有
 *       "缺省"这回事，回落 null 会让再序列化输出 null，往返不幂等）；</li>
 *   <li>Go 零值字面量 {@code "0001-01-01T00:00:00Z"} → Go 零值时间
 *       （与 {@link GoTimeSerializer} 的零值输出互逆）；</li>
 *   <li>其余按 {@link OffsetDateTime#parse(CharSequence)} 解析，**保留串里带的偏移**。</li>
 * </ul>
 */
public class GoTimeDeserializer extends JsonDeserializer<OffsetDateTime> {

    @Override
    public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.readValueAsTree();
        if (node == null || node.isNull() || node.asText().isEmpty()) {
            // Go 的零值时间**没有"缺省"这回事**：字段缺失就是零值，不是 null。
            // 所以这里回落到零值时间而不是 null——否则再写出去就成了 null，往返不幂等。
            return GoTimeSerializer.GO_ZERO_DATE_TIME;
        }
        String raw = node.asText();
        if (GoTimeSerializer.GO_ZERO_TIME_LITERAL.equals(raw)) {
            return GoTimeSerializer.GO_ZERO_DATE_TIME;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (RuntimeException e) {
            throw new IOException("failed to parse time \"" + raw + "\": " + e.getMessage(), e);
        }
    }
}
