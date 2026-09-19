package com.ragagent.browserskill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.browserskill.service.BrowserSkillManager;
import com.ragagent.browserskill.service.BrowserSkillStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对照 Go container.go L293-300 的装配：
 * {@code NewManager(NewStore(db))} + {@code ValidateConfiguration()}（must——
 * 配置无效直接拒启）+ 退出清理（cleaner.RegisterWithName("BrowserSkill", Close)）。
 * 全部配置读自 BROWSERSKILL_* 环境变量（与 Go 同名）；未设 → 集成禁用（dev 默认，
 * ValidateConfiguration 对禁用态恒通过）。
 */
@Configuration
public class BrowserSkillWiring {

    private static final Logger log = LoggerFactory.getLogger(BrowserSkillWiring.class);

    @Bean(destroyMethod = "close")
    public BrowserSkillManager browserSkillManager(ObjectMapper mapper, BrowserSkillStore store) {
        BrowserSkillManager manager = BrowserSkillManager.fromEnv(mapper, store);
        // 对照容器里的 must(Provide(...))：配置无效 Go 直接 panic 拒启
        manager.validateConfiguration();
        log.info("[browserskill] manager wired (enabled={})", manager.enabled());
        return manager;
    }
}
