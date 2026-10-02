package com.ragagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.ragagent.**.mapper")
// 配置属性类分布在两处：装配层 config/ 与跨域共享的 common/tenant/（TenantProperties 2026-09-30 下沉至此，
// 使 common/auth 不再反向依赖 config）。新增共享配置类时把包加进这个列表——别写 com.ragagent 根包扫描，
// 那会把 session 等处"未注册"的配置类一并绑定，属行为变化。
@ConfigurationPropertiesScan({"com.ragagent.config", "com.ragagent.common.tenant", "com.ragagent.common.settings",
        "com.ragagent.storage.config", "com.ragagent.tracing.langfuse", "com.ragagent.common.retrieval",
        "com.ragagent.common.deployment", "com.ragagent.knowledge.config",
        "com.ragagent.common.crypto", "com.ragagent.common.security", "com.ragagent.common.storage",
        "com.ragagent.common.wiki"})
public class RagAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagAgentApplication.class, args);
    }
}
