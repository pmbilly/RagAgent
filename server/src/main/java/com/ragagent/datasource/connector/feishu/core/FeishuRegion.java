package com.ragagent.datasource.connector.feishu.core;

/**
 * 飞书部署区域。
 *
 * <p>飞书与 Lark 是同一产品部署在两片隔离的云上：wiki/docx/drive 三套 API
 * <b>完全一致，只有 host 不同</b>。应用、租户、token、文档 token 都绑定在单一一朵云上，
 * 所以在另一朵云上永远无效——因此 Region 在注册连接器时<b>一次性固定</b>。</p>
 *
 * <p>record 提供值语义：四个常量各自单例，{@code ==} 比较即可判等。</p>
 *
 * <h2>两种 host，别混</h2>
 * <ul>
 *   <li>{@code openBaseUrl} 是 <b>Open Platform API</b> 的 origin（连接器真正发请求的地方）；</li>
 *   <li>{@code webBaseUrl} 是<b>终端用户应用</b>的 origin（拼给用户看的链接，如 wiki 节点 URL）。</li>
 * </ul>
 * <p>{@code webBaseUrl} 这一组是<b>终端用户应用</b>的 host，别与 API host 混用——
 * {@code LARK.WikiURL} 曾经错误地漏出飞书 host，这是一个已修复的真实缺陷。</p>
 *
 * <p><b>这是内部类型</b>：只进出飞书 API 与连接器内部，从不落 jsonb、从不进 HTTP 响应，
 * 所以不需要 JSON 契约注解。</p>
 */
public record FeishuRegion(String connectorType, String openBaseUrl, String webBaseUrl, String label) {

    /** Open Platform API origin（飞书）。 */
    private static final String FEISHU_OPEN_BASE_URL = "https://open.feishu.cn";

    /** Open Platform API origin（Lark 国际版）。 */
    private static final String LARK_OPEN_BASE_URL = "https://open.larksuite.com";

    /** 用户态应用 origin（飞书）。 */
    private static final String FEISHU_WEB_BASE_URL = "https://feishu.cn";

    /** 用户态应用 origin（Lark 国际版）。 */
    private static final String LARK_WEB_BASE_URL = "https://larksuite.com";

    /** 中国大陆云（飞书）。 */
    public static final FeishuRegion FEISHU = new FeishuRegion(
            "feishu", FEISHU_OPEN_BASE_URL, FEISHU_WEB_BASE_URL, "Feishu");

    /** 国际云（Lark）。 */
    public static final FeishuRegion LARK = new FeishuRegion(
            "lark", LARK_OPEN_BASE_URL, LARK_WEB_BASE_URL, "Lark");

    /**
     * 中国大陆云，云盘（Drive）模式：与 {@link #FEISHU} 共用同一个连接器包，
     * 只有 connector type 不同，注册表据此派发到 Drive 连接器。
     */
    public static final FeishuRegion FEISHU_DRIVE = new FeishuRegion(
            "feishu_drive", FEISHU_OPEN_BASE_URL, FEISHU_WEB_BASE_URL, "FeishuDrive");

    /** 国际云，云盘模式。 */
    public static final FeishuRegion LARK_DRIVE = new FeishuRegion(
            "lark_drive", LARK_OPEN_BASE_URL, LARK_WEB_BASE_URL, "LarkDrive");

    /**
     * 给用户看的 wiki 空间/节点链接。
     * token 既可能是 space_id 也可能是 node_token，两者都挂在 {@code /wiki/} 下。
     */
    public String wikiUrl(String token) {
        return webBaseUrl + "/wiki/" + token;
    }

    /** 云盘文件夹链接。 */
    public String driveFolderUrl(String folderToken) {
        return webBaseUrl + "/drive/folder/" + folderToken;
    }
}
