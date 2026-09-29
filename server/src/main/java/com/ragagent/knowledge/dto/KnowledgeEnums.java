package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 文档（Knowledge）状态类字段的枚举化定义。
 *
 * <p>这些字段在库里是自由文本，取值集合由各处常量约定（{@code Knowledge.PARSE_*}、
 * {@code KnowledgeSummaryService.SUMMARY_*}）。枚举化把"约定"变成"类型"：写错的取值在编译期
 * 就能发现，读代码时也不必再猜某个字符串代表什么状态。</p>
 *
 * <p>只收录<b>取值集合由代码收敛</b>的状态字段；像文档 {@code type}（来源类型，随接入方式扩展）
 * 这类数据驱动的字段保持字符串——强枚举会静默丢值。</p>
 *
 * <p>约定：JSON 值与数据库存储值保持一致（小写单词），{@code @JsonValue} 返回原值，
 * 前端既有的取值判断不受影响。</p>
 */
public final class KnowledgeEnums {

    private KnowledgeEnums() {
    }

    /** 带 wire 值的枚举：统一解析入口依赖它。 */
    public interface WireValued {
        String value();
    }

    /** 解析（摄取）状态机。 */
    public enum ParseStatus implements WireValued {
        /** 手工知识的草稿态（正文已存、未走解析管线）。 */
        DRAFT("draft"),
        PENDING("pending"),
        PROCESSING("processing"),
        /** 正文完成、后置工序（图谱/收尾）仍在进行。 */
        FINALIZING("finalizing"),
        COMPLETED("completed"),
        FAILED("failed"),
        DELETING("deleting"),
        CANCELLED("cancelled");

        private final String value;

        ParseStatus(String value) {
            this.value = value;
        }

        @Override
        @JsonValue
        public String value() {
            return value;
        }

        @JsonCreator
        public static ParseStatus from(String raw) {
            return parse(values(), raw);
        }
    }

    /** 启用状态。 */
    public enum EnableStatus implements WireValued {
        ENABLED("enabled"),
        DISABLED("disabled");

        private final String value;

        EnableStatus(String value) {
            this.value = value;
        }

        @Override
        @JsonValue
        public String value() {
            return value;
        }

        @JsonCreator
        public static EnableStatus from(String raw) {
            return parse(values(), raw);
        }
    }

    /** 摘要（描述自动生成）状态。 */
    public enum SummaryStatus implements WireValued {
        /** 未生成（如描述为空）。 */
        NONE("none"),
        /** 手工知识的草稿态（正文已存、未走解析管线）。 */
        DRAFT("draft"),
        PENDING("pending"),
        PROCESSING("processing"),
        COMPLETED("completed"),
        FAILED("failed");

        private final String value;

        SummaryStatus(String value) {
            this.value = value;
        }

        @Override
        @JsonValue
        public String value() {
            return value;
        }

        @JsonCreator
        public static SummaryStatus from(String raw) {
            return parse(values(), raw);
        }
    }

    /** 按 wire 值查枚举；空值/未知值 → {@code null}。 */
    private static <E extends Enum<E> & WireValued> E parse(E[] candidates, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        for (E candidate : candidates) {
            if (candidate.value().equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}
