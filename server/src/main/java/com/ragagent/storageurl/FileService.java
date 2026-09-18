package com.ragagent.storageurl;

/**
 * 本包对"文件服务"的最小需求（对照 Go {@code interfaces.FileService} 的
 * <b>{@code GetFileURL} 一个方法</b>）。
 *
 * <p>Go 的 {@code interfaces.FileService} 有 SaveFile / SaveBytes / GetFile / DeleteFile /
 * CopyFile / CheckConnectivity 等一整套方法，本包<b>只调 GetFileURL</b>。
 * 这里刻意收窄成单方法端口：实现方（未来的存储后端模块）不必为了被重写器使用而实现全部。</p>
 *
 * <h2>实现契约</h2>
 * <ul>
 *   <li>返回 **可被外部客户端直接加载的 http(s) URL**；返回非 http(s) 的值
 *       （例如 {@code local://…}）会被 {@link Rewriter} 当成"没解析出来"，
 *       引用保持为 handle——这是刻意的降级，不是错误。</li>
 *   <li>失败时抛 {@link RuntimeException}；{@link Rewriter} 会捕获、WARN、并把引用原样留下。</li>
 * </ul>
 *
 * <p><b>接线状态</b>：本端口目前<b>没有生产实现</b>——Go 侧的实现是
 * {@code internal/application/service/file/*}（local/minio/s3/cos/tos/oss/obs/ks3，
 * 20+ 文件 + 各家云 SDK），属未翻译模块。调用方传 {@code null} 时行为等价于
 * Go 未配置 {@code APP_EXTERNAL_URL} 的部署：引用全部按 handle 保留。</p>
 */
public interface FileService {

    /** 对照 Go {@code interfaces.FileService.GetFileURL(ctx, filePath)}。 */
    String getFileURL(String filePath);
}
