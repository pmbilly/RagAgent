package com.ragagent.storage.support;

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
 * <p><b>接线状态（2026-09-24 A3-3 起）</b>：生产实现是
 * {@code StorageUrlWiringConfig.storageUrlDefaultFileService}（进程级默认服务，
 * 照 Go container 的 {@code globalFileService}：local 基座 + resource catalog 装饰，
 * 于是 {@code resource://} 手柄能派生 {@code /r/<token>} 能力链接）；
 * provider 级服务由 {@code FileServiceResolver} 经 A3 的工厂按租户配置取用
 * （{@code internal/application/service/file/*} 那 20+ 文件的对应物）。
 * 端口仍可为空（缺 bean / 解析失败）——那时行为等价于 Go 未配置
 * {@code APP_EXTERNAL_URL} 的部署：引用按 handle 保留。</p>
 */
public interface FileService {

    /** 对照 Go {@code interfaces.FileService.GetFileURL(ctx, filePath)}。 */
    String getFileURL(String filePath);
}
