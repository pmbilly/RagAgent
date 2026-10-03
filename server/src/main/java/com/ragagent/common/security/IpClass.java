package com.ragagent.common.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * 出站请求目标的 IP 分类。
 * SSRF 用户 URL 策略：除 {@link #classify} 返回 PUBLIC 外全部拒绝。
 */
public final class IpClass {

    public enum Class {
        PUBLIC(""), INVALID("invalid address"), UNSPECIFIED("unspecified address"),
        LOOPBACK("loopback address"), PRIVATE("private IP address"),
        CGNAT("restricted range 100.64.0.0/10"), LINK_LOCAL("link-local address"),
        MULTICAST("multicast address"), SITE_LOCAL_IPV6("restricted range fec0::/10"),
        RESERVED("restricted range"), DOCUMENTATION("restricted range"),
        TRANSLATED("translated IPv4 address");

        final String reason;
        Class(String reason) { this.reason = reason; }
    }

    public record Result(Class classification, String reason) {}

    private IpClass() {}

    public static Result classify(InetAddress ip) {
        if (ip == null) {
            return new Result(Class.INVALID, Class.INVALID.reason);
        }
        // 谓词判定顺序：unspecified → loopback → link-local → multicast；
        // Java 谓词集无 IsPrivate 等价物（siteLocal 近似但顺序靠后不影响，私网段不与其他谓词重叠）；
        // isAnyLocalAddress = 0.0.0.0 / ::，不能与 loopback 合并——
        // 对 0.0.0.0 报 "unspecified address"，不是 "loopback address"。
        if (ip.isAnyLocalAddress()) {
            return new Result(Class.UNSPECIFIED, Class.UNSPECIFIED.reason);
        }
        if (ip.isLoopbackAddress()) {
            return new Result(Class.LOOPBACK, Class.LOOPBACK.reason);
        }
        if (ip.isLinkLocalAddress() || isLinkLocalMulticast(ip)) {
            return new Result(Class.LINK_LOCAL, Class.LINK_LOCAL.reason);
        }
        if (ip.isMulticastAddress()) {
            return new Result(Class.MULTICAST, Class.MULTICAST.reason);
        }
        byte[] b = ip.getAddress();
        // fec0::/10 deprecated site-local 必须先于 isSiteLocalAddress：Java 的
        // Inet6Address.isSiteLocalAddress() 覆盖 fec0::/10，放后面会先命中 PRIVATE，
        // 文案就不是 "restricted range fec0::/10" 了
        if (ip instanceof Inet6Address && b[0] == (byte) 0xfe && (b[1] & 0xc0) == 0xc0) {
            return new Result(Class.SITE_LOCAL_IPV6, "restricted range fec0::/10");
        }
        if (ip.isSiteLocalAddress()) {
            // Java siteLocal = RFC1918 (10/8, 172.16/12, 192.168/16) 与 fec0::/10
            return new Result(Class.PRIVATE, Class.PRIVATE.reason);
        }
        if (ip instanceof Inet4Address) {
            int v = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
            if (v == 0) {
                return new Result(Class.UNSPECIFIED, Class.UNSPECIFIED.reason);
            }
            // 受限 IPv4 段完整表（0.0.0.0/8 已由 unspecified 覆盖一部分，仍保留完整表）
            if (inRange(v, 0x00000000, 0x00ffffff)) return reserved("0.0.0.0/8");
            if (inRange(v, 0x64400000, 0x647fffff)) return cgnat();
            if (inRange(v, 0xc6120000, 0xc613ffff)) return reserved("198.18.0.0/15");
            if (inRange(v, 0xc0000000, 0xc00000ff)) return reserved("192.0.0.0/24");
            if (inRange(v, 0xc0000200, 0xc00002ff)) return doc("192.0.2.0/24");
            if (inRange(v, 0xc6336400, 0xc63364ff)) return doc("198.51.100.0/24");
            if (inRange(v, 0xcb007100, 0xcb0071ff)) return doc("203.0.113.0/24");
            if (inRange(v, 0xf0000000, 0xffffffff)) return reserved("240.0.0.0/4");
            return new Result(Class.PUBLIC, "");
        }
        if (ip instanceof Inet6Address) {
            return classifyIPv6(b);
        }
        return new Result(Class.INVALID, Class.INVALID.reason);
    }

    private static Result classifyIPv6(byte[] b) {
        // fec0::/10 已在 classify() 里先于 siteLocal 判定处理（Java 谓词覆盖顺序原因）
        // 6to4 2002::/16 —— 内嵌 IPv4 受限则整体受限
        if (b[0] == 0x20 && b[1] == 0x02) {
            int v = ipv4From(b, 2);
            Result inner = classifyIPv4Value(v);
            if (inner.classification != Class.PUBLIC) {
                return new Result(Class.TRANSLATED, "6to4 address embeds restricted IPv4");
            }
            return new Result(Class.PUBLIC, "");
        }
        // Teredo 2001::/32
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x00 && b[3] == 0x00) {
            return new Result(Class.TRANSLATED, "Teredo address");
        }
        // NAT64 well-known 64:ff9b::/96
        if (b[0] == 0x00 && b[1] == 0x64 && b[2] == (byte) 0xff && b[3] == (byte) 0x9b) {
            int v = ipv4From(b, 12);
            Result inner = classifyIPv4Value(v);
            if (inner.classification != Class.PUBLIC) {
                return new Result(Class.TRANSLATED, "NAT64 address embeds restricted IPv4");
            }
            return new Result(Class.PUBLIC, "");
        }
        return new Result(Class.PUBLIC, "");
    }

    /** 链路本地多播：224.0.0.0/24（v4）与 ff02::/16、ff12::/16（v6）。 */
    private static boolean isLinkLocalMulticast(InetAddress ip) {
        if (!ip.isMulticastAddress()) {
            return false;
        }
        byte[] b = ip.getAddress();
        if (ip instanceof Inet4Address) {
            int v = ipv4From(b, 0);
            return inRange(v, 0xe0000000, 0xe00000ff);
        }
        return b[0] == (byte) 0xff && (b[1] == 0x02 || b[1] == 0x12);
    }

    private static int ipv4From(byte[] b, int off) {        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static Result classifyIPv4Value(int v) {
        if (inRange(v, 0x64400000, 0x647fffff)) return cgnat();
        if (inRange(v, 0x0a000000, 0x0affffff) || inRange(v, 0xac100000, 0xac1fffff)
                || inRange(v, 0xc0a80000, 0xc0a8ffff)) return new Result(Class.PRIVATE, Class.PRIVATE.reason);
        if (inRange(v, 0x7f000000, 0x7fffffff)) return new Result(Class.LOOPBACK, Class.LOOPBACK.reason);
        if (inRange(v, 0xa9fe0000, 0xa9feffff)) return new Result(Class.LINK_LOCAL, Class.LINK_LOCAL.reason);
        if (inRange(v, 0xe0000000, 0xefffffff)) return new Result(Class.MULTICAST, Class.MULTICAST.reason);
        if (v == 0) return new Result(Class.UNSPECIFIED, Class.UNSPECIFIED.reason);
        return new Result(Class.PUBLIC, "");
    }

    private static boolean inRange(int v, int from, int to) {
        return v >= from && v <= to;
    }

    private static Result reserved(String cidr) {
        return new Result(Class.RESERVED, "restricted range " + cidr);
    }

    private static Result doc(String cidr) {
        return new Result(Class.DOCUMENTATION, "restricted range " + cidr);
    }

    private static Result cgnat() {
        return new Result(Class.CGNAT, Class.CGNAT.reason);
    }
}
