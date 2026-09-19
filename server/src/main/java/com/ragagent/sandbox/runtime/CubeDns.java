package com.ragagent.sandbox.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ragagent.sandbox.domain.SandboxNetworkPolicy;

/**
 * 对照 Go {@code internal/sandbox/cube_dns.go}（全文）。
 *
 * <p>Cube 模板的 {@code dns} 字段是 nameserver IP 列表——不接受主机名。
 * 空（null）结果表示"用 Cubelet 的默认"。</p>
 */
public final class CubeDns {

    private CubeDns() {
    }

    /**
     * 对照 NormalizeCubeDNSServers：trim、丢弃空项、拒绝非 IP 值、去重。
     *
     * @return 规范化后的列表；全空输入 → null
     * @throws IllegalArgumentException 消息形如
     *         {@code sandbox: invalid cube DNS server "x" (need an IP address)}
     */
    public static List<String> normalizeCubeDNSServers(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>(raw.size());
        Set<String> seen = new LinkedHashSet<>(raw.size());
        for (String item : raw) {
            String ip = item == null ? "" : item.trim();
            if (ip.isEmpty()) {
                continue;
            }
            java.net.InetAddress parsed = SandboxNetworkPolicy.parseLiteralIp(ip);
            if (parsed == null) {
                throw new IllegalArgumentException(
                        "sandbox: invalid cube DNS server \"" + item + "\" (need an IP address)");
            }
            String canonical = parsed.getHostAddress();
            if (!seen.add(canonical)) {
                continue;
            }
            out.add(canonical);
        }
        if (out.isEmpty()) {
            return null;
        }
        return out;
    }
}
