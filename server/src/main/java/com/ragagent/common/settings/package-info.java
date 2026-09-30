/**
 * 跨域共享的设置类：对话相关配置 {@link com.ragagent.common.settings.ConversationProperties}
 * （{@code conversation.*} 前缀，含 prompt 模板装载）。
 *
 * <p>2026-09-30 由 {@code config} 下沉至此：{@code knowledge}/{@code session}/{@code initialization} 都要读它，
 * 留在装配层会形成"域 → config"的反向依赖（config 是组合根，应只出不进）。</p>
 */
package com.ragagent.common.settings;
