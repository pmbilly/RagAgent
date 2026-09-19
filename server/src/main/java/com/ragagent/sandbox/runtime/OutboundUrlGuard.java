package com.ragagent.sandbox.runtime;

import java.net.InetAddress;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

import com.ragagent.common.security.IpClass;
import com.ragagent.sandbox.domain.SandboxNetworkPolicy;

/**
 * 对照 Go {@code internal/sandbox/url_guard.go}（全文）：租户自供端点的出站 URL 守卫。
 *
 * <p>租户配置自己的沙箱控制面 URL，服务端去拨号——没有守卫就是一个 SSRF 原语；最危险的
 * 目标是云元数据服务（169.254.169.254）。防御是刻意的两层：Validate 在保存/探测时运行
 * （本类），DialControl 在连接时运行（Java 无 RawConn 等价物，DialControl 未翻译——
 * 本批唯一的调用点是保存路径的 Validate）。</p>
 *
 * <p>自托管部署让事情复杂化，所以私网端点是每个工作区配置上的显式字段
 * ({@code allow_private})。即便启用，link-local 段——包括云元数据——仍然拒绝，
 * 且这对携带 IPv4 link-local 载荷的 IPv6 编码同样成立（分类复用
 * {@link IpClass#classify}，对照 internal/ipclass 的共享决策）。</p>
 */
public final class OutboundUrlGuard {

    /** 对照 ErrUnsafeOutboundURL 的固定前缀。 */
    public static final String ERR_UNSAFE_OUTBOUND_URL = "sandbox: unsafe outbound URL";

    private OutboundUrlGuard() {
    }

    /** 对照 OutboundURLPolicy：AllowPrivate 允许 loopback 与 RFC1918；永不允许 link-local。 */
    public record OutboundURLPolicy(boolean allowPrivate) {
        /** 对照 DefaultOutboundURLPolicy：不携带工作区配置的调用方用的 fail-closed 策略。 */
        public static OutboundURLPolicy failClosed() {
            return new OutboundURLPolicy(false);
        }
    }

    /** 对照 ValidateOutboundURL：环境默认策略。 */
    public static void validateOutboundURL(String raw) {
        validate(raw, OutboundURLPolicy.failClosed());
    }

    /** 对照 ValidateOutboundURLWithPolicy。 */
    public static void validateOutboundURLWithPolicy(String raw, OutboundURLPolicy policy) {
        validate(raw, policy);
    }

    /**
     * 对照 {@code OutboundURLPolicy.Validate}：raw 是否是可接受的租户自供端点。
     * 拒绝非 HTTP scheme 与解析到禁止地址的 host。
     *
     * @throws UnsafeOutboundURLException 消息对照 Go 的各 fmt.Errorf 分支逐字
     */
    private static void validate(String raw, OutboundURLPolicy policy) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new UnsafeOutboundURLException(ERR_UNSAFE_OUTBOUND_URL + ": empty URL");
        }
        URI parsed;
        try {
            parsed = new URI(trimmed);
        } catch (Exception e) {
            // Java 的 URI 比 Go 的 url.Parse 严格："http://" 这类 Go 能解析出**空 host**
            // 的输入在这里直接抛。先按 Go 语义落 "missing host"，其余解析错误的消息
            // 属「深层解析错误」已知差异族（Go parse 错误与 Java URI 错误措辞不同）。
            if (trimmed.matches("(?i)^[a-z][a-z0-9+.-]*://(?:/|$).*")) {
                throw new UnsafeOutboundURLException(ERR_UNSAFE_OUTBOUND_URL + ": missing host");
            }
            throw new UnsafeOutboundURLException(ERR_UNSAFE_OUTBOUND_URL + ": " + e.getMessage());
        }
        // url.Parse 对 scheme 大小写不敏感且**归一为小写**——错误消息里的 scheme 也是小写
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase();
        switch (scheme) {
            case "http", "https":
                break;
            default:
                throw new UnsafeOutboundURLException(ERR_UNSAFE_OUTBOUND_URL + ": scheme \""
                        + scheme + "\" is not allowed");
        }

        String host = parsed.getHost();
        if (host == null || host.isEmpty()) {
            throw new UnsafeOutboundURLException(ERR_UNSAFE_OUTBOUND_URL + ": missing host");
        }
        // ".local" 是 mDNS；"localhost" 只在 opt-in 下可接受
        String lower = host.toLowerCase();
        if (lower.endsWith(".local")) {
            throw new UnsafeOutboundURLException(ERR_UNSAFE_OUTBOUND_URL + ": host \"" + host
                    + "\" is mDNS-local");
        }
        if (lower.equals("localhost") || lower.endsWith(".localhost")) {
            if (!policy.allowPrivate()) {
                throw new UnsafeOutboundURLException(ERR_UNSAFE_OUTBOUND_URL + ": host \"" + host
                        + "\" is loopback; enable private endpoints for this workspace config");
            }
            return;
        }

        // 字面 IP 直接检查；主机名则解析出的每个地址都检查，
        // 因为一个可接受的答案不能让其余的变安全
        InetAddress literal = SandboxNetworkPolicy.parseLiteralIp(host);
        if (literal != null) {
            checkIP(literal, policy);
            return;
        }
        InetAddress[] addrs;
        try {
            addrs = java.net.InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new UnsafeOutboundURLException(ERR_UNSAFE_OUTBOUND_URL + ": cannot resolve \""
                    + host + "\": " + e.getMessage());
        }
        Set<String> seen = new LinkedHashSet<>();
        for (InetAddress addr : addrs) {
            if (!seen.add(addr.getHostAddress())) {
                continue;
            }
            try {
                checkIP(addr, policy);
            } catch (UnsafeOutboundURLException e) {
                throw new UnsafeOutboundURLException(ERR_UNSAFE_OUTBOUND_URL + ": host \"" + host
                        + "\" resolves to " + addr.getHostAddress());
            }
        }
    }

    /**
     * 对照 {@code checkIP}：把策略应用到具体地址。分类来自 IpClass（对照 internal/ipclass），
     * 只有每类的判决是本守卫自己的。
     */
    private static void checkIP(InetAddress ip, OutboundURLPolicy policy) {
        if (ip == null) {
            throw new UnsafeOutboundURLException(ERR_UNSAFE_OUTBOUND_URL + ": missing address");
        }
        IpClass.Result classified = IpClass.classify(ip);
        switch (classified.classification()) {
            case PUBLIC, DOCUMENTATION:
                // TEST-NET 永不路由，拒绝它保护不了任何东西，
                // 还会让测试失去免 DNS 的公网替身
                break;
            case LOOPBACK, PRIVATE, CGNAT:
                // opt-in 存在的意义：Cube 默认监听 127.0.0.1:33000，
                // RFC1918 内的控制面是正常的自托管形态而非例外
                if (!policy.allowPrivate()) {
                    throw new UnsafeOutboundURLException(ERR_UNSAFE_OUTBOUND_URL + ": address "
                            + ip.getHostAddress() + " is private; enable private endpoints "
                            + "for this workspace config");
                }
                break;
            default:
                // opt-in 下也拒绝：LinkLocal 携带云元数据；Translated 是目标在看似普通
                // 公网 IPv6 的同时触达它的方式；其余的根本无法触达沙箱
                throw new UnsafeOutboundURLException(ERR_UNSAFE_OUTBOUND_URL + ": address "
                        + ip.getHostAddress() + " is never routable to a sandbox ("
                        + reasonFor(classified) + ")");
        }
    }

    /**
     * Go 的 reason 文本（ipclass.Classify 第二返回值）按类拼进消息。
     * Java 侧 IpClass 的 reason 对常见类一致；受限段带具体 CIDR。
     */
    private static String reasonFor(IpClass.Result result) {
        return switch (result.classification()) {
            case INVALID -> "invalid address";
            case UNSPECIFIED -> "unspecified address";
            case LOOPBACK -> "loopback address";
            case PRIVATE -> "private IP address";
            case LINK_LOCAL -> "link-local address";
            case MULTICAST -> "multicast address";
            case SITE_LOCAL_IPV6 -> "site-local IPv6 address";
            case TRANSLATED -> result.reason() == null || result.reason().isEmpty()
                    ? "translated IPv4 address" : result.reason();
            default -> result.reason() == null || result.reason().isEmpty()
                    ? "restricted range" : result.reason();
        };
    }
}
