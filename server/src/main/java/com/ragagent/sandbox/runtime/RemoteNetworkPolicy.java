package com.ragagent.sandbox.runtime;

import java.util.List;
import java.util.Map;

/**
 * 对照 Go {@code sandbox.RemoteNetworkPolicy} 及其伴生类型
 * （internal/sandbox/remote_client.go L120-230）。
 *
 * <p>provider 中立形态的策略。两个反转在此发生且只发生一次：
 * DenyEgressByDefault → AllowInternetAccess=false、CubeEgressRule.Deny → Allow=true
 * （反转点在 {@code EffectiveConfigResolver.resolveNetworkPolicy}）。
 * 入站恒关闭（AllowPublicTraffic=false）。</p>
 *
 * <p>本批不序列化（不落 jsonb、不出响应），只是 service 与 provider 适配层之间的内部契约
 * ——子批 2 的客户端按它构造 provider 载荷。</p>
 */
public class RemoteNetworkPolicy {

    /** 三态：null 与 false 的区别按 Go 的 *bool 保留 */
    public Boolean allowInternetAccess;
    public Boolean allowPublicTraffic;
    public List<String> allowOut;
    public List<String> denyOut;
    public List<RemoteCubeEgressRule> cubeRules;
    public List<RemoteE2BHostRule> e2bHostRules;

    /**
     * 对照 {@code DeniesEgressByDefault}：出站流量是否默认拒绝。
     * 接受抽屉与校验器都接受的两写法：顶层开关关掉，或 deny_out 里有 deny-all 条目。
     */
    public boolean deniesEgressByDefault() {
        if (allowInternetAccess != null && !allowInternetAccess) {
            return true;
        }
        return com.ragagent.sandbox.domain.SandboxNetworkPolicy.denyOutCoversAllIPv4(denyOut);
    }

    /** 对照 {@code RemoteCubeEgressRule}：provider 中立的一条 CubeEgress L7 规则。 */
    public static class RemoteCubeEgressRule {
        public String name;
        public String scheme;
        public String sni;
        public String host;
        public List<String> methods;
        public String path;
        /** 正向表述（存储配置说 Deny；反转恰好在 resolveNetworkPolicy 发生一次） */
        public boolean allow;
        public String audit;
        public List<RemoteHeaderInject> inject;
    }

    /** 对照 {@code RemoteHeaderInject}：出站代理注入的一条凭据 header。 */
    public static class RemoteHeaderInject {
        public String header;
        public String secret;
        /** 空时 provider 侧默认 "${SECRET}" */
        public String format;
    }

    /** 对照 {@code RemoteE2BHostRule}：一条 E2B 逐 host 请求变换。 */
    public static class RemoteE2BHostRule {
        public String host;
        public Map<String, String> headers;
    }
}
