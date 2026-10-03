package com.ragagent.im.yunzhijia;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 云之家出站端点校验与 WS 地址推导。
 *
 * <h2>行为要点</h2>
 * <ul>
 *   <li>{@link #validateEndpointUrl}：scheme 必须等值（忽略大小写）、不得带 userinfo、
 *       主机必须是非空 <b>DNS 名</b>（{@code localhost}/{@code *.localhost}/IP 字面量一律拒）、
 *       且必须落在允许后缀内（后缀去首尾点、小写；空后缀直接拒）；</li>
 *   <li>{@link #deriveWebSocketUrl}：从 send_msg_url 取 {@code yzjtoken}（缺失报错），
 *       拼成 {@code wss://<host>/xuntong/websocket?yzjtoken=<token>}；</li>
 *   <li>{@link #isPublicIp}：出站前的 SSRF 判定——loopback/私网/链路本地/组播/ULA/CGNAT/
 *       未指定一律非公网；{@link #resolvePublicAddress} 解析后逐个校验，全为公网才放行
 *       （HttpClient 无自定义拨号口，等价做法是连接前解析校验）。</li>
 * </ul>
 */
public final class YunzhijiaUrl {

    private YunzhijiaUrl() {
    }

    /** 校验失败抛 {@link IllegalArgumentException}。 */
    public static URI validateEndpointUrl(String rawUrl, String requiredScheme,
                                          String allowedHostSuffix) {
        URI uri;
        try {
            uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase(requiredScheme)) {
            throw new IllegalArgumentException("URL must use " + requiredScheme);
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) {
            throw new IllegalArgumentException("URL must not contain user information");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("URL has empty host");
        }
        if (host.equals("localhost") || host.endsWith(".localhost") || isIpLiteral(host)) {
            throw new IllegalArgumentException("URL host must be a DNS name");
        }

        String suffix = allowedHostSuffix == null ? "" : allowedHostSuffix.trim();
        while (suffix.startsWith(".")) {
            suffix = suffix.substring(1);
        }
        while (suffix.endsWith(".")) {
            suffix = suffix.substring(0, suffix.length() - 1);
        }
        suffix = suffix.toLowerCase(Locale.ROOT);
        if (suffix.isEmpty()) {
            throw new IllegalArgumentException("allowed host suffix is required");
        }
        if (!host.equals(suffix) && !host.endsWith("." + suffix)) {
            throw new IllegalArgumentException("URL host \"" + host + "\" does not match allowed"
                    + " suffix \"" + suffix + "\"");
        }
        return uri;
    }

    public static String deriveWebSocketUrl(String sendMsgUrl, String allowedHostSuffix) {
        URI uri;
        try {
            uri = validateEndpointUrl(sendMsgUrl, "https", allowedHostSuffix);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid send_msg_url for websocket: "
                    + e.getMessage(), e);
        }
        String token = queryParam(uri.getRawQuery(), "yzjtoken").trim();
        if (token.isEmpty()) {
            throw new IllegalArgumentException("send_msg_url is missing yzjtoken");
        }
        return "wss://" + uri.getHost() + "/xuntong/websocket?yzjtoken="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    static String queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            if (name.equals(key)) {
                return idx < 0 ? "" : java.net.URLDecoder.decode(pair.substring(idx + 1),
                        StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    /** 主机是否为 IP 字面量（{@code 1.2.3.4} / {@code [::1]} 形态）。 */
    static boolean isIpLiteral(String host) {
        String value = host;
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.contains(":")) {
            return true;   // IPv6 字面量（DNS 名不含冒号）
        }
        return value.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** 非公网（含 CGNAT/ULA/IPv4 映射）一律 false。 */
    public static boolean isPublicIp(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (raw.length == 16) {
            int first = raw[0] & 0xFF;
            // IPv6 ULA fc00::/7
            if ((first & 0xFE) == 0xFC) {
                return false;
            }
            // IPv4 映射/兼容（::ffff:a.b.c.d）→ 按内嵌 IPv4 判定
            if (isIpv4Mapped(raw)) {
                byte[] v4 = new byte[] {raw[12], raw[13], raw[14], raw[15]};
                return isPublicIpv4(v4);
            }
            return true;
        }
        return isPublicIpv4(raw);
    }

    private static boolean isIpv4Mapped(byte[] raw) {
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF;
    }

    private static boolean isPublicIpv4(byte[] raw) {
        int a = raw[0] & 0xFF;
        int b = raw[1] & 0xFF;
        if (a == 0 || a == 10 || a == 127) {
            return false;
        }
        if (a == 169 && b == 254) {
            return false;   // 链路本地
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return false;   // 私网
        }
        if (a == 192 && b == 168) {
            return false;   // 私网
        }
        if (a == 100 && b >= 64 && b <= 127) {
            return false;   // CGNAT 100.64/10
        }
        if (a == 192 && b == 0) {
            return false;   // 192.0.0.0/24（含协议保留）
        }
        if (a >= 224) {
            return false;   // 组播 + 保留
        }
        return true;
    }

    /** 解析主机并逐个校验为公网地址。 */
    public static InetAddress[] resolvePublicAddress(String host) throws Exception {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new IllegalStateException("endpoint host \"" + host
                    + "\" resolved to no addresses");
        }
        for (InetAddress address : addresses) {
            if (!isPublicIp(address)) {
                throw new IllegalStateException("endpoint host \"" + host
                        + "\" resolves to a non-public address");
            }
        }
        return addresses;
    }
}
