package com.ragagent.config;

import java.util.Locale;

import com.ragagent.common.crypto.CryptoEnvProperties;
import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.common.security.SsrfWhitelistProperties;
import com.ragagent.common.storage.UploadLimitProperties;
import com.ragagent.common.storage.UploadLimits;
import com.ragagent.common.wiki.LanguageProperties;
import com.ragagent.common.wiki.WikiLanguageSupport;
import com.ragagent.storage.config.LocalStorageEnvProperties;
import com.ragagent.storage.config.StorageProviderEnv;
import com.ragagent.storage.config.ResourceUrlModeProperties;
import com.ragagent.storage.config.StorageEnvLookup;
import com.ragagent.storage.config.StorageRuntimeEnv;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 启动期快照装配（B6 批 6 起）。
 *
 * <p>本仓配置读取的优先顺序：能在 bean 里构造注入的就注入；只有**纯静态/手工 new 的工具类**
 * 才走「启动期快照」——值在这里写入一次，之后整进程只读。都是部署期确定的值
 * （语言默认值、上传限额、AES 密钥、SSRF 白名单；批 7 起含存储域的本地存储根与全局存储类型）。</p>
 *
 * <p>注意两处例外通道：SSRF 白名单另有 {@code SsrfGuard.reloadWhitelist}（系统设置运行时
 * 调谐，既有能力，不受本装配影响）。若要在运行期改其它快照值，那不是配置而是状态，
 * 应另行设计——别在这里加运行时写入口。</p>
 */
@Configuration
public class RuntimeSnapshotWiring {

    public RuntimeSnapshotWiring(LanguageProperties languageProperties,
                                 UploadLimitProperties uploadLimitProperties,
                                 CryptoEnvProperties cryptoEnvProperties,
                                 SsrfWhitelistProperties ssrfWhitelistProperties,
                                 LocalStorageEnvProperties localStorageEnvProperties,
                                 StorageProviderEnv.StorageType storageTypeProperties,
                                 ResourceUrlModeProperties resourceUrlModeProperties,
                                 Environment environment) {
        WikiLanguageSupport.installLanguage(languageProperties.language());
        UploadLimits.installFileSizeMb(uploadLimitProperties.fileSizeMb());
        CryptoService.installAesKey(cryptoEnvProperties.aesKey());
        SsrfGuard.installWhitelist(ssrfWhitelistProperties.whitelist(),
                ssrfWhitelistProperties.whitelistExtra());
        // 存储域静态工具（StoragePaths/TenantFileServiceResolver）读同一份快照；
        // STORAGE_TYPE 装原始串，缺省/小写归一仍留在各读点
        StorageRuntimeEnv.install(localStorageEnvProperties.storageBaseDir(), storageTypeProperties.type(),
                resourceUrlModeProperties.urlMode());
        // provider 家族键按**运行期键名**读：环境变量风格（MINIO_ENDPOINT）优先，
        // 再回落属性风格（minio.endpoint，便于 --minio.endpoint=… / 属性源覆盖）
        StorageEnvLookup.install(key -> {
            String value = environment.getProperty(key);
            return value != null ? value
                    : environment.getProperty(key.toLowerCase(Locale.ROOT).replace('_', '.'));
        });
    }
}
