package com.ragagent.common.security;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 对照 Go internal/utils/security.go 的 SSRF 校验子集：
 * ValidateURLForSSRF / FormatSSRFError / IsSSRFWhitelisted。
 *
 * 策略：用户提交的 URL 仅允许 http/https、禁止直连 IP（含八进制/十六进制/十进制混淆）、
 * 禁止受限主机名/后缀/端口，DNS 解析后任一解析 IP 受限即拒绝；
 * SSRF_WHITELIST + SSRF_WHITELIST_EXTRA 白名单豁免（仅放宽主机/IP，不放松 scheme）。
 *
 * 阶段说明：DB 可调白名单（SystemSettingService 运行时推送）未翻译——
 * 该能力属系统设置模块；当前仅 ENV 兜底路径（对照 loadSSRFWhitelist 的 Once 缓存）。
 */
@Component
public class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    private static final List<String> RESTRICTED_HOSTNAMES = List.of(
            "localhost", "127.0.0.1", "::1", "0.0.0.0",
            "metadata.google.internal", "metadata.tencentyun.com", "metadata.aws.internal",
            "host.docker.internal", "gateway.docker.internal", "kubernetes.docker.internal",
            "kubernetes", "kubernetes.default", "kubernetes.default.svc",
            "kubernetes.default.svc.cluster.local");

    private static final List<String> RESTRICTED_SUFFIXES = List.of(
            ".local", ".localhost", ".internal", ".corp", ".lan", ".home", ".localdomain",
            ".svc.cluster.local", ".pod.cluster.local");

    private static final Set<String> RESTRICTED_PORTS = Set.of(
            "22", "23", "25", "445", "3389", "5432", "3306", "6379", "27017", "9200",
            "2379", "2380", "8500", "4001");

    private static final List<Pattern> IP_LIKE_PATTERNS = List.of(
            Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"),
            Pattern.compile("^\\d{8,10}$"),
            Pattern.compile("^0[0-7]+\\."),
            Pattern.compile("(?i)^0x[0-9a-f]+\\."),
            Pattern.compile("(?i)^0x[0-9a-f]{6,8}$"),
            Pattern.compile("(?i)^[0-9a-f:]+::[0-9a-f:]*$"),
            Pattern.compile("(?i)^[0-9a-f]{1,4}(:[0-9a-f]{1,4}){7}$"),
            Pattern.compile("(?i)^::ffff:\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"),
            Pattern.compile("(?i)^\\[[0-9a-f:]+\\]$"));

    private record Whitelist(Set<String> exactHosts, List<String> suffixHosts,
                             List<java.net.InetAddress[]> cidrNets, List<Integer> cidrPrefixLens) {
        static Whitelist empty() {
            return new Whitelist(Set.of(), List.of(), List.of(), List.of());
        }
    }

    private volatile Whitelist whitelist;

    public SsrfGuard() {
        this.whitelist = parseWhitelistRaw(mergeRaws(
                System.getenv("SSRF_WHITELIST"), System.getenv("SSRF_WHITELIST_EXTRA")));
    }

    /** 对照 Go SetSSRFWhitelistFromRaw：原子替换白名单（SystemSettingService 运行时调谐路径；测试亦用） */
    public void reloadWhitelist(String raw) {
        this.whitelist = parseWhitelistRaw(raw);
    }

    /** 对照 mergeSSRFWhitelistRaws */
    static String mergeRaws(String primary, String extra) {
        primary = primary == null ? "" : primary.trim();
        extra = extra == null ? "" : extra.trim();
        if (primary.isEmpty() && extra.isEmpty()) return "";
        if (primary.isEmpty()) return extra;
        if (extra.isEmpty()) return primary;
        return primary + "," + extra;
    }

    /**
     * 对照 ValidateURLForSSRF。空 URL 放行（由调用方决定必填）。
     * @throws SsrfException 校验失败（消息 = Go error 原文）
     */
    public void validateURLForSSRF(String rawURL) {
        if (rawURL == null || rawURL.isEmpty()) {
            return;
        }
        String normalized = rawURL.contains("://") ? rawURL : "https://" + rawURL;
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException e) {
            throw new SsrfException("invalid URL: " + e.getMessage());
        }
        String hostname = uri.getHost();
        if (hostname == null || hostname.isEmpty()) {
            throw new SsrfException("URL has no hostname");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new SsrfException("invalid scheme: " + scheme + " (only http/https allowed)");
        }
        if (isWhitelisted(hostname)) {
            return;
        }
        try {
            isSafeURL(normalized, uri, hostname);
        } catch (SsrfException e) {
            // 对照 Go：仅 isSSRFSafeURL 的拒绝带 "SSRF validation failed: " 前缀
            throw new SsrfException("SSRF validation failed: " + e.getMessage());
        }
    }

    /** 对照 isSSRFSafeURL：受限主机名/后缀/直连 IP/IP 混淆/DNS 解析/端口 */
    private void isSafeURL(String rawURL, URI uri, String hostname) {
        if (rawURL.length() > 2048) {
            throw new SsrfException("URL exceeds maximum length");
        }
        String lower = hostname.toLowerCase(Locale.ROOT);
        for (String restricted : RESTRICTED_HOSTNAMES) {
            if (lower.equals(restricted)) {
                throw new SsrfException("hostname " + hostname + " is restricted");
            }
        }
        for (String suffix : RESTRICTED_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                throw new SsrfException("hostname suffix " + suffix + " is restricted");
            }
        }
        InetAddress directIp = parseIp(hostname);
        if (directIp != null) {
            throw new SsrfException("direct IP address access is not allowed, "
                    + "use domain name or add to SSRF_WHITELIST");
        }
        if (isIPLikeHostname(hostname)) {
            throw new SsrfException("IP-like hostname format is not allowed");
        }
        InetAddress[] ips;
        try {
            ips = InetAddress.getAllByName(IDN.toASCII(hostname));
        } catch (Exception e) {
            throw new SsrfException("DNS resolution failed for hostname " + hostname
                    + ": cannot verify if it resolves to safe IP");
        }
        for (InetAddress ip : ips) {
            IpClass.Result r = IpClass.classify(ip);
            if (r.classification() != IpClass.Class.PUBLIC) {
                throw new SsrfException("hostname " + hostname + " resolves to restricted IP "
                        + ip.getHostAddress() + ": " + r.reason());
            }
        }
        int port = uri.getPort();
        if (port >= 0 && RESTRICTED_PORTS.contains(String.valueOf(port))) {
            throw new SsrfException("port " + port + " is blocked for security reasons");
        }
    }

    /** 对照 FormatSSRFError：中文运维提示文案逐字符一致 */
    public String formatSSRFError(String label, String rawURL, Exception err) {
        String host = rawURL;
        try {
            String norm = rawURL.contains("://") ? rawURL : "https://" + rawURL;
            URI u = URI.create(norm);
            if (u.getHost() != null) {
                host = u.getHost();
            }
        } catch (IllegalArgumentException ignored) {
            // 保留 rawURL
        }
        return label + " 未通过安全校验：" + err.getMessage()
                + "。如该地址确实可信，请联系运维在服务端环境变量 "
                + "SSRF_WHITELIST_EXTRA 中加入该主机（支持精确域名 / *.example.com 通配 / IP / CIDR），"
                + "示例：SSRF_WHITELIST_EXTRA=" + host + ",*.example.com,10.0.0.0/8";
    }

    /** 对照 IsSSRFWhitelisted：精确 / *.后缀 / CIDR（含解析后 CIDR 匹配） */
    public boolean isWhitelisted(String hostname) {
        Whitelist wl = whitelist;
        if (wl == null) {
            return false;
        }
        String lower = hostname.toLowerCase(Locale.ROOT);
        if (wl.exactHosts().contains(lower)) {
            return true;
        }
        for (String suffix : wl.suffixHosts()) {
            if (lower.endsWith(suffix) || lower.equals(suffix.substring(1))) {
                return true;
            }
        }
        InetAddress ip = parseIp(hostname);
        if (ip != null && inAnyCidr(ip, wl)) {
            return true;
        }
        if (ip == null && !wl.cidrNets().isEmpty()) {
            try {
                for (InetAddress resolved : InetAddress.getAllByName(IDN.toASCII(hostname))) {
                    if (inAnyCidr(resolved, wl)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
                // 解析失败 → 未命中白名单
            }
        }
        return false;
    }

    private static boolean inAnyCidr(InetAddress ip, Whitelist wl) {
        byte[] addr = ip.getAddress();
        for (int i = 0; i < wl.cidrNets().size(); i++) {
            InetAddress[] net = wl.cidrNets().get(i);
            int prefix = wl.cidrPrefixLens().get(i);
            byte[] base = net[0].getAddress();
            if (base.length != addr.length) {
                continue;
            }
            int fullBytes = prefix / 8;
            int remBits = prefix % 8;
            boolean match = true;
            for (int j = 0; j < fullBytes && j < addr.length; j++) {
                if (addr[j] != base[j]) {
                    match = false;
                    break;
                }
            }
            if (match && remBits > 0 && fullBytes < addr.length) {
                int mask = 0xff << (8 - remBits);
                if ((addr[fullBytes] & mask) != (base[fullBytes] & mask)) {
                    match = false;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    /** 对照 parseSSRFWhitelistRaw：CIDR / *.通配 / 精确；非法条目丢弃并记日志 */
    static Whitelist parseWhitelistRaw(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Whitelist.empty();
        }
        Set<String> exact = new HashSet<>();
        List<String> suffixes = new ArrayList<>();
        List<InetAddress[]> nets = new ArrayList<>();
        List<Integer> prefixLens = new ArrayList<>();
        for (String entry : raw.split(",")) {
            entry = entry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.contains("/")) {
                try {
                    String[] parts = entry.split("/");
                    InetAddress base = InetAddress.getByName(parts[0]);
                    int prefix = Integer.parseInt(parts[1]);
                    nets.add(new InetAddress[]{base});
                    prefixLens.add(prefix);
                    continue;
                } catch (Exception e) {
                    log.warn("[ssrf-whitelist] dropping invalid CIDR entry \"{}\": {}", entry, e.toString());
                    continue;
                }
            }
            if (entry.startsWith("*.")) {
                String suffix = entry.substring(1).toLowerCase(Locale.ROOT);
                if (suffix.length() <= 1) {
                    log.warn("[ssrf-whitelist] dropping bare wildcard entry \"{}\" (need *.<domain>)", entry);
                    continue;
                }
                suffixes.add(suffix);
                continue;
            }
            if (entry.contains("*")) {
                log.warn("[ssrf-whitelist] dropping unsupported wildcard pattern \"{}\" "
                        + "(only \"*.\" prefix is supported)", entry);
                continue;
            }
            exact.add(entry.toLowerCase(Locale.ROOT));
        }
        return new Whitelist(Set.copyOf(exact), List.copyOf(suffixes), List.copyOf(nets), List.copyOf(prefixLens));
    }

    private static InetAddress parseIp(String host) {
        // 仅纯 IP 字符串（net.ParseIP 语义）：不含字母、不含 '-'
        if (host.indexOf(':') >= 0 || host.matches("[0-9.]+")) {
            try {
                return InetAddress.getByName(host);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static boolean isIPLikeHostname(String hostname) {
        for (Pattern p : IP_LIKE_PATTERNS) {
            if (p.matcher(hostname).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 校验失败异常，消息与 Go error 原文一致 */
    public static class SsrfException extends RuntimeException {
        public SsrfException(String message) {
            super(message);
        }
    }
}
