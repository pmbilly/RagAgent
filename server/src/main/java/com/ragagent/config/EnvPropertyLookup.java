package com.ragagent.config;

import java.util.Locale;
import java.util.function.Function;

import org.springframework.core.env.Environment;

/**
 * {@code Environment} 支撑的查找函数（B6 批 8/9 共用）。
 *
 * <p>语义：<b>键名原样优先</b>（系统环境变量风格 {@code MINIO_ENDPOINT} / {@code MILVUS_COLLECTION}
 * 本就是 Environment 的一个 property source），**再回落属性风格**（{@code minio.endpoint} /
 * {@code milvus.collection}），从而同一份配置可被属性源、命令行参数、{@code SPRING_APPLICATION_JSON}
 * 覆盖。各域查找面（存储 / 检索）都从这里取函数，保证语义一致。</p>
 */
public final class EnvPropertyLookup {

    private EnvPropertyLookup() {
    }

    /** 造一个「原样键 → 属性风格键」的查找函数。 */
    public static Function<String, String> of(Environment environment) {
        return key -> {
            if (key == null) {
                return null;
            }
            String value = environment.getProperty(key);
            return value != null
                    ? value
                    : environment.getProperty(key.toLowerCase(Locale.ROOT).replace('_', '.'));
        };
    }
}
