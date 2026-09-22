package com.ragagent.common.web;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * {@code float[]} 的 Go 字节形态序列化（逐元素走 {@link GoFloatSerializer}）。
 *
 * <p>在 {@code JacksonConfig} 里按类型注册（float[] 在响应面极少出现，
 * 不影响既有路径；首个消费点是 models/{id}/debug 的 embedding 向量）。</p>
 */
public class GoFloatArraySerializer extends JsonSerializer<float[]> {

    @Override
    public void serialize(float[] value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeStartArray();
        for (float f : value) {
            gen.writeNumber(GoFloatSerializer.format(f));
        }
        gen.writeEndArray();
    }
}
