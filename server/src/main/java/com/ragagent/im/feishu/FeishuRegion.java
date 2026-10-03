package com.ragagent.im.feishu;

import com.ragagent.im.runtime.ImTypes;

/**
 * 飞书 / Lark 的云区。
 *
 * <p>飞书与 Lark 是同一产品的两朵隔离云：API 面完全相同，只有域名与租户不同。
 * 应用、租户、token 与资源键（image_key / file_key / card_id）都绑定单朵云、
 * 跨云无效，因此 Region 在渠道创建时就固定下来。</p>
 */
public record FeishuRegion(String platform, String openBaseUrl, String label,
                           String thinkingText, String imageFallbackLabel) {

    public static final String FEISHU_OPEN_BASE_URL = "https://open.feishu.cn";
    public static final String LARK_OPEN_BASE_URL = "https://open.larksuite.com";

    /** 中国大陆云（飞书）：面向中文用户。 */
    public static final FeishuRegion FEISHU = new FeishuRegion(
            ImTypes.PLATFORM_FEISHU, FEISHU_OPEN_BASE_URL, "Feishu",
            "正在思考...", "图片");

    /** 国际云（Lark）：面向英文用户。 */
    public static final FeishuRegion LARK = new FeishuRegion(
            ImTypes.PLATFORM_LARK, LARK_OPEN_BASE_URL, "Lark",
            "Thinking...", "Image");

    /** 按平台名取 region（配置里的 {@code region} 键为 {@code lark} 时用国际云）。 */
    public static FeishuRegion of(String name) {
        return name != null && name.trim().equalsIgnoreCase("lark") ? LARK : FEISHU;
    }
}
