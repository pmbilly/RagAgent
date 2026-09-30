package com.ragagent.common.session;

/**
 * 用户消息里的一张图片（{@code url} + 可空的 {@code caption} 描述）。
 *
 * <p>图片描述是异步 VLM 生成后回写用户消息的，故提供 {@link #withCaption(String)}
 * 造新值（载荷是不可变记录）。</p>
 */
public record PipelineMessageImageView(String url, String caption) {

    /** 换掉图片描述（其余字段原样）。 */
    public PipelineMessageImageView withCaption(String newCaption) {
        return new PipelineMessageImageView(url, newCaption);
    }
}
