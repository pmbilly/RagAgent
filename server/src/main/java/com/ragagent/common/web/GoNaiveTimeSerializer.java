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

    // 委托 UTC 变体：naive 值补 UTC 偏移后要输出 Z 结尾（本类 javadoc 契约）。
    // 此前委托系统默认时区的 GoTimeSerializer——atOffset(UTC) 的入参会被
    // atZoneSameInstant(系统默认) 转回本地偏移（如 +08:00），一旦被复用即输出
    // 错误字节（该类当前全仓零引用，属"留着会被当正确实现复用"的坑）。
    private final GoTimeSerializer delegate = new GoTimeSerializer.Utc();

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
