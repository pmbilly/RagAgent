package com.ragagent.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件（对照 Go GORM 分页语义：Page 物理分页 + total 统计）。
 * 阶段 3 ListKnowledge 真分页依赖此拦截器。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor();
        pagination.setMaxLimit(1000L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
