package com.ragagent.wiki.domain;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * 把空/ null 的字符串列表序列化成 JSON {@code null}——对齐 Go 的
 * {@code types.StringArray}（nil slice → {@code json.Marshal} 出字面量 {@code null}）。
 *
 * <p>为什么要单独一个序列化器：DB 里的这些列是 SQL NULL（Go 写 nil 的结果），
 * Java 的读路径把它们宽容地还原成**空列表**（避免业务代码 NPE），
 * 于是序列化时会输出 {@code []} 而不是 Go 的 {@code null}。
 * 契约要求逐字节一致，故在这一层把空列表映射回 {@code null}。</p>
 *
 * @see WikiStringListTypeHandler
 */
public class EmptyListAsNullSerializer extends JsonSerializer<List<String>> {

    @Override
    public void serialize(List<String> value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        if (value == null || value.isEmpty()) {
            gen.writeNull();
            return;
        }
        gen.writeStartArray();
        for (String v : value) {
            gen.writeString(v == null ? "" : v);
        }
        gen.writeEndArray();
    }
}
