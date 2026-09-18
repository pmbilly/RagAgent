package com.ragagent.datasource;

import org.springframework.stereotype.Component;

import com.ragagent.common.security.SsrfGuard;

/**
 * 把 Spring 管理的 {@link SsrfGuard} 单例交给 {@link ConnectorHttp}（对照 Go 侧
 * 连接器直接调 {@code utils.ValidateURLForSSRF} 这个<b>包级</b>函数——它读的正是
 * 全进程同一份白名单）。
 *
 * <p>必要性：{@code SsrfGuard} 的白名单可被系统设置模块在运行时调谐
 * （{@link SsrfGuard#reloadWhitelist(String)} 对应 Go 的
 * {@code SetSSRFWhitelistFromRaw}）；若连接器自己 {@code new} 一个实例，
 * 运行时调谐就落不到出站校验上——表现为"运维在设置页加了白名单，连接器照样被拒"。
 * 这与 {@code LlmTransportWiring} / {@code McpServiceUrls} 是同一处置。</p>
 */
@Component
public class ConnectorHttpWiring {

    public ConnectorHttpWiring(SsrfGuard ssrfGuard) {
        ConnectorHttp.setSsrfGuard(ssrfGuard);
    }
}
