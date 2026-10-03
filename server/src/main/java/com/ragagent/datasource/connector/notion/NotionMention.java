package com.ragagent.datasource.connector.notion;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code "mention"} 型富文本的内容。
 *
 * <p><b>内部 API 形状，不是契约</b>：只进出于 Notion API 的 JSON。</p>
 *
 * <h2>一个刻意的非对称</h2>
 * <p>{@code database} 字段同时被 {@code "database"} 与 {@code "data_source"}
 * 两个 mention 类型使用（{@code richTextToString} 的 case 是 {@code "database", "data_source"}），
 * 而 {@code "user"} 类型既没有专属字段、也不在 switch 里——它直接落到最后的
 * "返回 plain_text" 分支。别"顺手补全"。</p>
 */
public final class NotionMention {

    /** {@code "page"} | {@code "database"} | {@code "data_source"} | {@code "date"} | {@code "user"} | {@code "link_preview"}。 */
    @JsonProperty("type")
    public String type;

    @JsonProperty("page")
    public IdRef page;

    @JsonProperty("database")
    public IdRef database;

    @JsonProperty("date")
    public NotionDateMention date;

    @JsonProperty("link_preview")
    public UrlRef linkPreview;

    public String type() {
        return type == null ? "" : type;
    }

    /** 只带 {@code id} 的引用。 */
    public static final class IdRef {
        @JsonProperty("id")
        public String id;
    }

    /** 只带 {@code url} 的引用。 */
    public static final class UrlRef {
        @JsonProperty("url")
        public String url;

        public String url() {
            return url == null ? "" : url;
        }
    }
}
