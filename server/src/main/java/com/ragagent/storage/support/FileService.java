package com.ragagent.storage.support;

/**
 * 本包对"文件服务"的最小需求：<b>只有 {@code GetFileURL} 一个方法</b>。
 *
 * <p>刻意收窄成单方法端口：实现方（未来的存储后端模块）不必为了被重写器使用而实现全部。</p>
 *
 * <h2>实现契约</h2>
 * <ul>
 *   <li>返回 **可被外部客户端直接加载的 http(s) URL**；返回非 http(s) 的值
 *       （例如 {@code local://…}）会被 {@link Rewriter} 当成"没解析出来"，
 *       引用保持为 handle——这是刻意的降级，不是错误。</li>
 *   <li>失败时抛 {@link RuntimeException}；{@link Rewriter} 会捕获、WARN、并把引用原样留下。</li>
 * </ul>
 *
 * <p><b>接线状态</b>：生产实现是
 * {@code StorageUrlWiringConfig.storageUrlDefaultFileService}（进程级默认服务：
 * local 基座 + resource catalog 装饰，
 * 于是 {@code resource://} 手柄能派生 {@code /r/<token>} 能力链接）；
 * provider 级服务由 {@code FileServiceResolver} 经工厂按租户配置取用。
 * 端口仍可为空（缺 bean / 解析失败）——那时引用按 handle 保留。</p>
 */
public interface FileService {

    /** 解析为可被外部客户端直接加载的 http(s) URL。 */
    String getFileURL(String filePath);
}
