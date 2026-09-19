package com.ragagent.sandbox.runtime;

import com.ragagent.sandbox.domain.TenantSandboxConfig;

/**
 * 对照 Go {@code internal/sandbox/config_identity.go}（全文）：
 * 什么让一个已存在的沙箱仍然可操作。
 *
 * <p>两组配置字段决定一个配置已创建的沙箱还能否被操作：</p>
 * <ul>
 *   <li><b>控制面</b>（API URL、API key）：一切生命周期调用——list、delete、pause、
 *       resume、刷新超时——都对它发起。丢了它，沙箱就再也无法回收。</li>
 *   <li><b>数据面</b>（sandbox domain，Cube 还有 proxy 端点）：envd 流量经它路由。
 *       丢了它，沙箱仍可删但不可用，配置上的每个活会话同时失败。</li>
 * </ul>
 *
 * <p>这个拆分是在用客户端的属性，不是普遍真理（注释见 Go 原文）。</p>
 */
public record SandboxIdentity(
        String provider,
        boolean allowPrivateEndpoints,
        /** 控制面：已存在的沙箱能否被回收 */
        String apiURL,
        String apiKey,
        /** 数据面：已存在的沙箱能否被使用 */
        String sandboxDomain,
        String proxyURL) {

    private static final SandboxIdentity EMPTY =
            new SandboxIdentity("", false, "", "", "", "");

    public static SandboxIdentity empty() {
        return EMPTY;
    }

    /**
     * 对照 {@code IdentityOf}：把存储配置投影到身份。只读活跃 provider 的字段：
     * 早先 provider 切换留下的子结构对今天的沙箱所在位置没有发言权。未知类型串按原样
     * 比较而非拒绝：拼写错误产生匹配不到任何东西的身份——偏向拒绝编辑，而
     * ParseSandboxType 会在保存时报告它。
     *
     * <p>刻意不做 SSRF 守卫、不返回错误：它回答"这次编辑会不会搁浅什么"，
     * 而旧端点不再解析时这个问题必须仍然可回答——恰是管理员需要重新指配置的情形。
     * 校验传入 URL 是保存路径的职责。</p>
     */
    public static SandboxIdentity identityOf(TenantSandboxConfig tenantCfg) {
        if (tenantCfg == null) {
            return EMPTY;
        }
        String provider = tenantCfg.getSandboxType();
        boolean allowPrivate = tenantCfg.isAllowPrivateEndpoints();
        String apiURL = "";
        String apiKey = "";
        String sandboxDomain = "";
        String proxyURL = "";
        switch (tenantCfg.getSandboxType() == null ? "" : tenantCfg.getSandboxType()) {
            case SandboxTypes.TYPE_CUBE -> {
                if (tenantCfg.getCube() != null) {
                    apiURL = tenantCfg.getCube().getApiUrl();
                    apiKey = tenantCfg.getCube().getApiKey();
                    sandboxDomain = tenantCfg.getCube().getSandboxDomain();
                    proxyURL = tenantCfg.getCube().getProxyUrl();
                }
            }
            case SandboxTypes.TYPE_E2B -> {
                if (tenantCfg.getE2b() != null) {
                    apiURL = tenantCfg.getE2b().getApiUrl();
                    apiKey = tenantCfg.getE2b().getApiKey();
                    sandboxDomain = tenantCfg.getE2b().getSandboxDomain();
                    proxyURL = tenantCfg.getE2b().getProxyUrl();
                }
            }
            case SandboxTypes.TYPE_DOCKER -> {
                // daemon endpoint 同时是两个面：容器在同一 socket 上创建、exec、删除，
                // 改指它就搁浅了本配置拥有的每个沙箱。镜像不是身份——改它只影响之后
                // 创建的沙箱。
                if (tenantCfg.getDocker() != null) {
                    apiURL = tenantCfg.getDocker().getHost();
                    apiKey = tenantCfg.getDocker().getTlsCertPath();
                }
            }
            default -> {
            }
        }
        return new SandboxIdentity(provider, allowPrivate, apiURL, apiKey, sandboxDomain, proxyURL);
    }
}
