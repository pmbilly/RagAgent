package com.ragagent.storage.fileserve;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ragagent.storage.support.FileService;

/**
 * 把 {@code storageurl} 包的两个端口接到生产实现（A3-3 接线）：
 *
 * <ul>
 *   <li>{@link com.ragagent.storage.support.StorageBackendResolver} → 见
 *       {@link FileserveStorageBackendResolver}（{@code @Component}）；</li>
 *   <li>{@link com.ragagent.storage.support.FileService}（进程级默认）→ 本类的 bean，
 *       对照 Go container 装配的 {@code globalFileService}——恒 local 基座 + resource
 *       catalog 装饰（于是 {@code resource://} 手柄在 {@code APP_EXTERNAL_URL} 在位时
 *       能派生出 {@code /r/<token>} 能力链接，此前该分支恒不可达）。</li>
 * </ul>
 *
 * <p>此前这两个端口都没有生产实现，三处 HTTP 调用点按 {@code ObjectProvider} 取到空、
 * 于是引用一律保留成 handle（Go 在 nil 分支的同形降级）。</p>
 */
@Configuration
public class StorageUrlWiringConfig {

    /**
     * 进程级默认文件服务（只暴露 {@code GetFileURL} 的窄口）。
     *
     * <p>本地根目录与 {@link FileProxyService} 用同一个属性链
     * （{@code weknora.storage.local-base-dir} → {@code LOCAL_STORAGE_BASE_DIR} →
     * {@code /data/files}），避免两处对"根在哪"有分歧。</p>
     */
    @Bean
    public FileService storageUrlDefaultFileService(
            StorageFileResolver resolver,
            @Value("${weknora.storage.local-base-dir:${LOCAL_STORAGE_BASE_DIR:/data/files}}")
                    String localBaseDir) {
        return new UrlPort(resolver.globalFileService(localBaseDir));
    }

    /** 窄口（只有 GetFileURL）→ fileserve 读取面的适配；失败按契约转 RuntimeException。 */
    private record UrlPort(FileContentService inner) implements FileService {

        @Override
        public String getFileURL(String filePath) {
            try {
                return inner.getFileURL(filePath);
            } catch (IOException e) {
                throw new IllegalStateException(
                        e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
        }
    }
}
