package com.ragagent.model.dto;

import java.util.List;

/**
 * models/{id}/debug 的 ASR 分支 rawResponse：转写文本 + 分段时间轴。
 *
 * <p>{@code text} 恒输出；{@code segments} 未产出时显式 null；
 * {@code start}/{@code end} 为秒（double），标准 Jackson 数字形态。</p>
 */
public class ModelDebugAsrResponse {

    private String text = "";

    private List<Segment> segments;

    public ModelDebugAsrResponse(String text, List<Segment> segments) {
        this.text = text == null ? "" : text;
        this.segments = segments;
    }

    public String getText() { return text; }
    public List<Segment> getSegments() { return segments; }

    /** 单个转写分段（秒）。 */
    public static final class Segment {
        private final double start;
        private final double end;
        private final String text;

        public Segment(double start, double end, String text) {
            this.start = start;
            this.end = end;
            this.text = text == null ? "" : text;
        }

        public double getStart() { return start; }

        public double getEnd() { return end; }

        public String getText() { return text; }
    }
}
