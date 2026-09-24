package com.ragagent.config;

import org.springframework.context.annotation.Configuration;

import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.service.ImService;
import com.ragagent.im.slack.SlackAdapterFactory;
import com.ragagent.im.telegram.TelegramAdapterFactory;

/**
 * IM 适配器工厂的装配（对照 Go container 里对各平台 {@code RegisterAdapterFactory} 的调用）。
 *
 * <p>W5γ3：九个渠道的出站客户端逐个落地，每落地一支在这里注册一行。当前已注册：
 * Telegram（webhook + 长轮询）。未注册的平台 {@code ImService.startChannel} 会打
 * WARN（"no adapter factory for platform"）并保持渠道未启动——与 Go 在工厂缺失时的
 * 行为同形，绝不静默假装成功。</p>
 */
@Configuration
public class ImAdapterWiringConfig {

    /**
     * 构造器里注册（工厂是 {@code @Component}，不由本类定义——否则
     * "配置类的构造器依赖自己 @Bean 方法定义的 bean"会触发 BeanCurrentlyInCreation）。
     */
    public ImAdapterWiringConfig(ImService imService,
                                 TelegramAdapterFactory telegramAdapterFactory,
                                 SlackAdapterFactory slackAdapterFactory,
                                 com.ragagent.im.qqbot.QqBotAdapterFactory qqBotAdapterFactory) {
        imService.registerAdapterFactory(ImTypes.PLATFORM_TELEGRAM, telegramAdapterFactory);
        imService.registerAdapterFactory(ImTypes.PLATFORM_SLACK, slackAdapterFactory);
        imService.registerAdapterFactory(ImTypes.PLATFORM_QQBOT, qqBotAdapterFactory);
    }
}
