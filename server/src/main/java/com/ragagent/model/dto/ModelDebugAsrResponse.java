package com.ragagent.model.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.common.web.GoDoubleSerializer;

/**
 * models/{id}/debug 的 ASR 分支 raw_response（对照 Go
 * {@code asr.TranscriptionResult} + {@code asr.Segment}，asr.go:9-20）。
 *
 * <p>{@code text} 恒输出；{@code segments} omitempty（空则整键省略）。
 * start/end 是 float64 恒输出，字节形态走 {@link GoDoubleSerializer}。</p>
 */
@JsonPropertyOrder({"text", "segments"})
public class ModelDebugAsrResponse {

    @JsonProperty("text")
    private String text = "";

    @JsonProperty("segments")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<Segment> segments;

    public ModelDebugAsrResponse(String text, List<Segment> segments) {
        this.text = text == null ? "" : text;
        this.segments = segments;
    }

    public String getText() { return text; }
    public List<Segment> getSegments() { return segments; }

    /** 对照 Go asr.Segment（start/end/text 声明序，恒输出）。 */
    @JsonPropertyOrder({"start", "end", "text"})
    public static final class Segment {
        private final double start;
        private final double end;
        private final String text;

        public Segment(double start, double end, String text) {
            this.start = start;
            this.end = end;
            this.text = text == null ? "" : text;
        }

        @JsonProperty("start")
        public double getStart() { return start; }

        @JsonProperty("end")
        public double getEnd() { return end; }

        @JsonProperty("text")
        public String getText() { return text; }
    }
}
