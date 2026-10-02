package com.ragagent.config;

import com.ragagent.common.crypto.CryptoEnvProperties;
import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.common.security.SsrfWhitelistProperties;
import com.ragagent.common.storage.UploadLimitProperties;
import com.ragagent.common.storage.UploadLimits;
import com.ragagent.common.wiki.LanguageProperties;
import com.ragagent.common.wiki.WikiLanguageSupport;
import org.springframework.context.annotation.Configuration;

/**
 * 启动期快照装配（B6 批 6）。
 *
 * <p>本仓配置读取的优先顺序：能在 bean 里构造注入的就注入；只有**纯静态/手工 new 的工具类**
 * 才走「启动期快照」——值在这里写入一次，之后整进程只读。都是部署期确定的值
 * （语言默认值、上传限额、AES 密钥、SSRF 白名单），没有运行期变更需求。</p>
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
                                 SsrfWhitelistProperties ssrfWhitelistProperties) {
        WikiLanguageSupport.installLanguage(languageProperties.language());
        UploadLimits.installFileSizeMb(uploadLimitProperties.fileSizeMb());
        CryptoService.installAesKey(cryptoEnvProperties.aesKey());
        SsrfGuard.installWhitelist(ssrfWhitelistProperties.whitelist(),
                ssrfWhitelistProperties.whitelistExtra());
    }
}
