package com.ragagent.memory.domain;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 抽取游标：用消息主键**打破时间戳平局**
 * （对照 Go {@code types.MemoryMessageCursor}，internal/types/memory_extraction.go L15-22）。
 *
 * <p>纯值对象，不落表。它出现在
 * {@link MemoryExtractionSession#getCursor()} 的嵌套 JSON 里，
 * 也作为 {@code failed_from_} / {@code failed_to_} 的 {@code embeddedPrefix} 展开成平列
 * （见 {@link MemoryExtractionSession} 的说明）。</p>
 */
@JsonPropertyOrder({"at", "id"})
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryMessageCursor {

    @JsonProperty("at")
    private OffsetDateTime at = GoTimeSerializer.GO_ZERO_DATE_TIME;

    @JsonProperty("id")
    private String id = "";

    public MemoryMessageCursor() {
    }

    public MemoryMessageCursor(OffsetDateTime at, String id) {
        setAt(at);
        setId(id);
    }

    /**
     * 对照 Go {@code After}：{@code c.At.After(other.At) || (c.At.Equal(other.At) && c.ID > other.ID)}。
     *
     * <p>方法名刻意不带 {@code is}/{@code get} 前缀——Jackson 不会把它当属性（§7.5 第 2 条）。</p>
     */
    public boolean after(MemoryMessageCursor other) {
        if (other == null) {
            return true;
        }
        return at.isAfter(other.at) || (at.isEqual(other.at) && id.compareTo(other.id) > 0);
    }

    /**
     * 对照 Go 的 {@code progress.FailedFrom.At.Equal(progress.Cursor.At)}。
     *
     * <p>名字**刻意**不用 {@code isXxx} 形式：那正是 §7.5 第 2 条的坑
     * （Jackson 会把零参 {@code isXxx()} 当属性名 {@code xxx} 写出去）。
     * 带参数的方法 Jackson 本来也不认，但少一个可疑形状少一分复发率。</p>
     */
    public boolean atEquals(OffsetDateTime other) {
        return other != null && at.toInstant().equals(other.toInstant());
    }

    public OffsetDateTime getAt() { return at; }
    public void setAt(OffsetDateTime v) {
        at = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }
}
