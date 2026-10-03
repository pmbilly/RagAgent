package com.ragagent.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

/**
 * IpClass 边界回归：走查抓回 0.0.0.0/8 上界误写 0x0fffffff（实为 0.0.0.0/4），
 * 把 1.x–15.x 整段公网（dashscope 8.152.x、8.8.8.8 等）误判 restricted。
 * 受限 IPv4 段表逐段断言。
 */
class IpClassTest {

    private static IpClass.Class classify(String ip) throws Exception {
        return IpClass.classify(InetAddress.getByName(ip)).classification();
    }

    @Test
    void zeroSlashEightOnlyCoversFirstOctetZero() throws Exception {
        assertEquals(IpClass.Class.UNSPECIFIED, classify("0.0.0.0"));
        assertEquals(IpClass.Class.RESERVED, classify("0.255.255.255"));
        // 误写上界 0x0fffffff 会把这段全打成 RESERVED
        assertEquals(IpClass.Class.PUBLIC, classify("1.0.0.0"));
        assertEquals(IpClass.Class.PUBLIC, classify("8.152.159.24")); // dashscope 实案
        assertEquals(IpClass.Class.PUBLIC, classify("8.8.8.8"));
        assertEquals(IpClass.Class.PUBLIC, classify("15.255.255.255"));
    }

    @Test
    void restrictedRangesMatchGoTable() throws Exception {
        assertEquals(IpClass.Class.CGNAT, classify("100.64.0.0"));
        assertEquals(IpClass.Class.CGNAT, classify("100.127.255.255"));
        assertEquals(IpClass.Class.PUBLIC, classify("100.128.0.0"));
        assertEquals(IpClass.Class.RESERVED, classify("198.18.0.0"));
        assertEquals(IpClass.Class.RESERVED, classify("198.19.255.255"));
        assertEquals(IpClass.Class.PUBLIC, classify("198.20.0.0"));
        assertEquals(IpClass.Class.RESERVED, classify("192.0.0.1"));
        assertEquals(IpClass.Class.DOCUMENTATION, classify("192.0.2.1"));
        assertEquals(IpClass.Class.DOCUMENTATION, classify("198.51.100.1"));
        assertEquals(IpClass.Class.DOCUMENTATION, classify("203.0.113.1"));
        assertEquals(IpClass.Class.RESERVED, classify("240.0.0.1"));
        assertEquals(IpClass.Class.RESERVED, classify("255.255.255.255"));
    }

    @Test
    void wellKnownPublicIpsArePublic() throws Exception {
        assertEquals(IpClass.Class.PUBLIC, classify("120.55.160.1"));
        assertEquals(IpClass.Class.PUBLIC, classify("223.5.5.5"));
        assertEquals(IpClass.Class.LINK_LOCAL, classify("224.0.0.1")); // 224.0.0.0/24 链路本地多播谓词先行
        assertEquals(IpClass.Class.MULTICAST, classify("239.0.0.1"));
    }
}
