package com.ragagent.common.web;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonFactory;

/**
 * 让 HTTP 响应的 JSON 里「补充字符」（emoji 等 UTF-16 代理对，码点 > 0xFFFF）
 * 以 Go/encoding/json 的方式输出 <b>raw UTF-8</b>（4 字节原样），而不是
 * {@code \uD83D\uDCDA} 代理对转义。
 *
 * <p><b>根因</b>（2.17.2 源码实测）：Jackson 的 UTF-8 字节生成器
 * {@code UTF8JsonGenerator._outputMultiByteChar} 对落在代理区
 * （0xD800–0xDFFF）的 char <b>硬编码</b>按反斜杠 u 十六进制逐个转义
 * （{@code ESCAPE_UTF8_SURROGATES} 特性在该版本里是被注释掉的死代码，
 * 2.17/2.18/2.19/2.20 四个版本实测行为一致，升级不可解）。
 * 而 {@code encoding/json} 对合法代理对输出原样 UTF-8——
 * 内建 agent 的 avatar（📚/📊）来自 builtin_agents.yaml，任何 agent 列表响应都会踩到。</p>
 *
 * <p><b>修法</b>：把 {@code createGenerator(OutputStream, JsonEncoding)} 改道到
 * {@code WriterBasedJsonGenerator}（经 {@code OutputStreamWriter} 编码）。
 * Writer 路径的转义只查 {@code GoJsonEscapes} 那张 ASCII 表（{@code < > &}、控制字符、
 * {@code \" \\}），其余 char——含代理对——原样交给 Writer 的 UTF-8 编码器，
 * 代理对被编成正确的 4 字节。两个生成器对这些字节的输出逐字节一致：</p>
 * <ul>
 *   <li>中文等 BMP 非 ASCII：两边都是原样 3 字节 UTF-8；</li>
 *   <li>{@code \n \t \b \f \r \" \\}：两边都是短转义；</li>
 *   <li>其余控制字符与 {@code < > &}：两边都是小写十六进制反斜杠 u 转义；</li>
 *   <li>唯一行为变化：补充字符从 12 字节代理对转义变成 4 字节 raw UTF-8 —— 即对齐 Go。</li>
 * </ul>
 *
 * <p><b>AUTO_CLOSE_TARGET 关闭</b>（JsonGenerator 默认值）：Spring 的消息转换器用
 * try-with-resources 关 generator，close() 只 flush 不关 writer，servlet 输出流
 * 仍由容器收口，与原字节路径语义一致。</p>
 */
public class GoWriterJsonFactory extends JsonFactory {

    private static final long serialVersionUID = 1L;

    @Override
    public JsonGenerator createGenerator(OutputStream out, JsonEncoding enc) throws IOException {
        // 统一按 UTF-8 走 Writer 路径（服务输出只有 UTF-8；enc 非 UTF-8 的调用方
        // 在本应用里不存在——HTTP JSON 恒 UTF-8）。
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8) {
            // 覆盖 close：generator.close()（AUTO_CLOSE_TARGET=false）不会走到这里，
            // 但防御性地保证即使被关也只是 flush，不关 servlet 流。
            @Override
            public void close() throws IOException {
                flush();
            }
        };
        return createGenerator(writer);
    }

    @Override
    public JsonFactory copy() {
        GoWriterJsonFactory copy = new GoWriterJsonFactory();
        copy.setCharacterEscapes(getCharacterEscapes());
        return copy;
    }
}
