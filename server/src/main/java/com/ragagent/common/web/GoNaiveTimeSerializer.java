package com.ragagent.common.web;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * naive 时间列（TIMESTAMP WITHOUT TIME ZONE）的 Go 形态序列化器。
 *
 * Go 侧 GORM/pgx 把 naive 列扫成 UTC location 的 time.Time，json.Marshal 输出
 * Z 结尾的 RFC3339（与 timestamptz 列的本地偏移 +08:00 不同，见 GoTimeSerializer）。
 * Java 侧用 LocalDateTime 承载 naive 值，序列化时补 UTC 偏移后复用
 * GoTimeSerializer 的纳秒裁剪逻辑。
 */
public class GoNaiveTimeSerializer extends JsonSerializer<LocalDateTime> {

    private final GoTimeSerializer delegate = new GoTimeSerializer();

    @Override
    public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        delegate.serialize(value.atOffset(ZoneOffset.UTC), gen, provider);
    }
}
