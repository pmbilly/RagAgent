package com.ragagent.datasource.service;

import org.springframework.stereotype.Component;

/**
 * {@link AutoTagProvider} 的占位实现：知识标签模块尚未翻译，因此恒回 {@code null}。
 *
 * <p>等价于 Go 的 {@code autoTag == nil} 分支（同步继续、条目没有自动标签），
 * <b>不是</b>错误路径——所以不抛异常、也不记警告，否则每次同步都会刷一行
 * 无意义的 warn。</p>
 *
 * <p>标签模块落地时，把本类换成真正的实现（注入标签服务）即接上；
 * 或者直接给真正实现加 {@code @Primary}。</p>
 */
@Component
public class NoAutoTagProvider implements AutoTagProvider {

    @Override
    public String findOrCreateTagId(String kbId, String name) {
        return null;
    }
}
