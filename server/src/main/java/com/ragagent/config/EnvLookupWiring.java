package com.ragagent.config;

import com.ragagent.vectorstore.domain.EnvVectorStores;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 向量库 env 族的查找面装配（B6 批 3）。
 *
 * <p>原先调用方自传 {@code System::getenv}；改为由 Spring {@code Environment} 支撑——
 * 同一批键名（{@code OPENSEARCH_ADDR} / {@code QDRANT_HOST} / {@code MILVUS_ADDRESS} …）
 * 照旧解析（系统环境变量本就是 Environment 的一个 property source），但因此可被
 * 测试属性/命令行参数覆盖，且「读环境变量」这件事收敛到单一入口。</p>
 */
@Configuration
public class EnvLookupWiring {

    @Bean
    public EnvVectorStores.EnvLookup envVectorStoreLookup(Environment environment) {
        return environment::getProperty;
    }
}
