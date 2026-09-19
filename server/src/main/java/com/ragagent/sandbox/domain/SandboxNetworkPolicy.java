package com.ragagent.sandbox.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoMapSerializer;

/**
 * 对照 Go {@code types.SandboxNetworkPolicy} 及同文件的 Cube/E2B 规则类型
 * （internal/types/sandbox_network_policy.go 全文）。存储形状 + 密钥处理 + 校验；
 * provider 侧翻译在 runtime 包。
 *
 * <p>存储形态的管理面网络策略：deny_egress_by_default 的措辞使零值<b>就是</b>想要的默认
 * （出网放开）。入站不是开关：恒要求凭据。allow_public_inbound 留在线上让旧载荷仍能解码，
 * 保存时被清除（{@code SandboxConfigRedaction#mergeNetworkPolicyForUpdate}）、resolve 时忽略。</p>
 *
 * <p>校验入口：{@link #validateSandboxNetworkPolicy(TenantSandboxConfig)}；
 * 密钥遍历：{@link #cloneWithSecrets(UnaryOperator)}（加密/解密/打码三个方向共用，
 * 保证"哪些字段算密钥"不漂移）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SandboxNetworkPolicy {

    /** 对照 DenyAllIPv4：两个 provider 的拒绝列表都期待的 deny-all 条目。 */
    public static final String DENY_ALL_IPV4 = "0.0.0.0/0";

    /** 对照 e2bNetworkLimits（服务端硬限制，保存时前置校验） */
    static final int E2B_MAX_RULE_DOMAINS = 10;
    static final int E2B_MAX_HEADERS_PER_RULE = 20;
    static final int E2B_MAX_RULE_DOMAIN_LENGTH = 128;
    static final int E2B_MAX_HEADER_NAME_LENGTH = 64;
    static final int E2B_MAX_HEADER_VALUE_LEN = 2048;

    @JsonProperty("deny_egress_by_default")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean denyEgressByDefault;

    /** 线上接受、保存时清除、resolve 时忽略 */
    @JsonProperty("allow_public_inbound")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean allowPublicInbound;

    /** IPv4、IPv4 CIDR、DNS 名或 "*.example.com" 单层通配 */
    @JsonProperty("allow_out")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> allowOut;

    /** 只接受 IPv4 与 IPv4 CIDR（拒绝判定是纯最长前缀匹配） */
    @JsonProperty("deny_out")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> denyOut;

    @JsonProperty("cube_rules")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<CubeEgressRule> cubeRules;

    @JsonProperty("e2b_host_rules")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<E2BHostRule> e2bHostRules;

    public boolean isDenyEgressByDefault() { return denyEgressByDefault; }
    public void setDenyEgressByDefault(boolean v) { denyEgressByDefault = v; }
    public boolean isAllowPublicInbound() { return allowPublicInbound; }
    public void setAllowPublicInbound(boolean v) { allowPublicInbound = v; }
    public List<String> getAllowOut() { return allowOut; }
    public void setAllowOut(List<String> v) { allowOut = v; }
    public List<String> getDenyOut() { return denyOut; }
    public void setDenyOut(List<String> v) { denyOut = v; }
    public List<CubeEgressRule> getCubeRules() { return cubeRules; }
    public void setCubeRules(List<CubeEgressRule> v) { cubeRules = v; }
    public List<E2BHostRule> getE2bHostRules() { return e2bHostRules; }
    public void setE2bHostRules(List<E2BHostRule> v) { e2bHostRules = v; }

    // ── CloneWithSecrets（加密/解密/打码共用的深拷贝） ─────────────────────

    /**
     * 对照 {@code SandboxNetworkPolicy.CloneWithSecrets}：深拷贝 p，把每个携带密钥的
     * 字段经 transform 传递。接收者不被修改。注意 Go 的语义：
     * AllowOut/DenyOut 恒拷贝为<b>非 nil</b>（append 到 nil 产出空切片），
     * CubeRules/E2BHostRules 仅在原为非 nil 时保持非 nil。
     */
    public SandboxNetworkPolicy cloneWithSecrets(UnaryOperator<String> transform) {
        SandboxNetworkPolicy out = new SandboxNetworkPolicy();
        out.denyEgressByDefault = denyEgressByDefault;
        out.allowPublicInbound = allowPublicInbound;
        out.allowOut = allowOut == null ? new ArrayList<>() : new ArrayList<>(allowOut);
        out.denyOut = denyOut == null ? new ArrayList<>() : new ArrayList<>(denyOut);

        if (cubeRules != null) {
            List<CubeEgressRule> rules = new ArrayList<>(cubeRules.size());
            for (CubeEgressRule rule : cubeRules) {
                CubeEgressRule copied = rule.copy();
                if (rule.getInject() != null) {
                    List<CubeHeaderInject> injects = new ArrayList<>(rule.getInject().size());
                    for (CubeHeaderInject inject : rule.getInject()) {
                        CubeHeaderInject c = inject.copy();
                        c.setSecret(transform.apply(inject.getSecret()));
                        injects.add(c);
                    }
                    copied.setInject(injects);
                }
                rules.add(copied);
            }
            out.cubeRules = rules;
        }

        if (e2bHostRules != null) {
            List<E2BHostRule> hostRules = new ArrayList<>(e2bHostRules.size());
            for (E2BHostRule rule : e2bHostRules) {
                E2BHostRule copied = rule.copy();
                if (rule.getHeaders() != null) {
                    Map<String, String> headers = new LinkedHashMap<>();
                    for (Map.Entry<String, String> e : rule.getHeaders().entrySet()) {
                        headers.put(e.getKey(), transform.apply(e.getValue()));
                    }
                    copied.setHeaders(headers);
                }
                hostRules.add(copied);
            }
            out.e2bHostRules = hostRules;
        }
        return out;
    }

    // ── 校验（对照 ValidateSandboxNetworkPolicy 与其辅助） ─────────────────

    /**
     * 对照 {@code ValidateSandboxNetworkPolicy}：拒绝 provider 会拒绝、或会静默做出入
     * 管理员本意的策略。错误是普通异常消息；service 层包装为 400。
     */
    public static String validateSandboxNetworkPolicy(TenantSandboxConfig cfg) {
        if (cfg == null || cfg.getNetwork() == null) {
            return null;
        }
        SandboxNetworkPolicy p = cfg.getNetwork();
        String backend = cfg.getSandboxType() == null ? "" : cfg.getSandboxType().trim().toLowerCase();

        if ("docker".equals(backend)
                && (nonEmpty(p.allowOut) || nonEmpty(p.denyOut)
                        || nonEmpty(p.cubeRules) || nonEmpty(p.e2bHostRules))) {
            return "docker 后端只能整体开关出网，无法按 IP、域名或 HTTP 规则放行；"
                    + "请清空放行/拒绝列表，或改用 cube / e2b 后端";
        }

        Map<String, String> allowKeys = new LinkedHashMap<>();
        boolean hasDomainAllow = false;
        if (p.allowOut != null) {
            for (String target : p.allowOut) {
                TargetClassified c = classifyNetworkTarget(target, true);
                if (c.error != null) {
                    return "allow_out \"" + target + "\": " + c.error;
                }
                String prior = allowKeys.get(c.key);
                if (prior != null) {
                    return "allow_out 中 \"" + prior + "\" 与 \"" + target + "\" 是同一个目标，请只保留一条";
                }
                allowKeys.put(c.key, target);
                if (c.kind == TargetKind.DOMAIN) {
                    hasDomainAllow = true;
                }
            }
        }

        Map<String, String> denyKeys = new LinkedHashMap<>();
        boolean deniesEverything = p.denyEgressByDefault;
        if (p.denyOut != null) {
            for (String target : p.denyOut) {
                TargetClassified c = classifyNetworkTarget(target, false);
                if (c.error != null) {
                    return "deny_out \"" + target + "\": " + c.error;
                }
                if (c.kind == TargetKind.DOMAIN) {
                    return "deny_out 不支持域名（\"" + target + "\"）：拒绝判定只按目的 IP 匹配。"
                            + "如需只放行少数域名，请启用「默认拒绝」并把它们写进 allow_out";
                }
                String prior = denyKeys.get(c.key);
                if (prior != null) {
                    return "deny_out 中 \"" + prior + "\" 与 \"" + target + "\" 是同一个目标，请只保留一条";
                }
                denyKeys.put(c.key, target);
                if (DENY_ALL_IPV4.equals(c.key)) {
                    deniesEverything = true;
                }
            }
        }

        if (hasDomainAllow && !deniesEverything) {
            return "allow_out 里有域名时必须同时兜底拒绝其余流量："
                    + "启用「默认拒绝」，或在 deny_out 中加入 0.0.0.0/0。"
                    + "否则未经 DNS 学习的目的 IP 仍会默认放行，白名单形同虚设";
        }

        String cubeError = validateCubeEgressRules(p.cubeRules);
        if (cubeError != null) {
            return cubeError;
        }
        return validateE2BHostRules(p.e2bHostRules, allowKeys);
    }

    /** 对照 {@code DenyOutCoversAllIPv4}：拒绝列表是否阻断全部 IPv4 目的。 */
    public static boolean denyOutCoversAllIPv4(List<String> denyOut) {
        if (denyOut == null) {
            return false;
        }
        for (String target : denyOut) {
            TargetClassified c = classifyNetworkTarget(target, false);
            if (c.error != null || c.kind != TargetKind.IP) {
                continue;
            }
            if (DENY_ALL_IPV4.equals(c.key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对照 {@code CanonicalizeDenyOut}：把任何 IPv4 /0 改写为 DenyAllIPv4，
     * 使字符串匹配 0.0.0.0/0 的 provider（E2B 的 ALL_TRAFFIC）看到校验器接受的同一份
     * deny-all。其余条目原样拷贝。null 入参保持 null。
     */
    public static List<String> canonicalizeDenyOut(List<String> denyOut) {
        if (denyOut == null) {
            return null;
        }
        List<String> out = new ArrayList<>(denyOut.size());
        for (String target : denyOut) {
            TargetClassified c = classifyNetworkTarget(target, false);
            if (c.error == null && c.kind == TargetKind.IP && DENY_ALL_IPV4.equals(c.key)) {
                out.add(DENY_ALL_IPV4);
                continue;
            }
            out.add(target);
        }
        return out;
    }

    enum TargetKind { IP, DOMAIN }

    /** classifyNetworkTarget 的二元组结果（Go 的多返回值 → 字段；error 非 null 即失败） */
    static final class TargetClassified {
        final TargetKind kind;
        final String key;
        final String error;

        TargetClassified(TargetKind kind, String key, String error) {
            this.kind = kind;
            this.key = key;
            this.error = error;
        }
    }

    /**
     * 对照 {@code classifyNetworkTarget}：归一化一条 allow/deny 条目并报告它是地址还是域名。
     * key 是两个 provider 去重所用的归一形式：裸 IPv4 折叠为 /32，域名小写并去尾点。
     */
    static TargetClassified classifyNetworkTarget(String target, boolean allowWildcard) {
        String trimmed = target == null ? "" : target.trim();
        if (trimmed.isEmpty()) {
            return new TargetClassified(null, null, "不能为空");
        }
        if (trimmed.indexOf(' ') >= 0 || trimmed.indexOf('\t') >= 0) {
            return new TargetClassified(null, null, "不能包含空格");
        }
        java.net.InetAddress ip = parseLiteralIp(trimmed);
        if (ip != null) {
            byte[] addr = ip.getAddress();
            if (addr.length != 4) {
                return new TargetClassified(null, null, "暂不支持 IPv6");
            }
            return new TargetClassified(TargetKind.IP, ip.getHostAddress() + "/32", null);
        }
        int slash = trimmed.indexOf('/');
        if (slash > 0) {
            String addrPart = trimmed.substring(0, slash);
            String prefixPart = trimmed.substring(slash + 1);
            java.net.InetAddress base = parseLiteralIp(addrPart);
            Integer prefix = parsePrefixLen(prefixPart);
            if (base != null && prefix != null) {
                byte[] addr = base.getAddress();
                if (addr.length != 4) {
                    return new TargetClassified(null, null, "暂不支持 IPv6 CIDR");
                }
                if (prefix > 32) {
                    return new TargetClassified(null, null,
                            "invalid CIDR address: " + trimmed);
                }
                return new TargetClassified(TargetKind.IP, canonicalV4Cidr(addr, prefix), null);
            }
        }
        return classifyDomainTarget(trimmed, allowWildcard);
    }

    private static TargetClassified classifyDomainTarget(String trimmed, boolean allowWildcard) {
        String domain = trimmed.endsWith(".")
                ? trimmed.substring(0, trimmed.length() - 1).toLowerCase()
                : trimmed.toLowerCase();
        if (domain.contains(":")) {
            return new TargetClassified(null, null, "不能带端口");
        }
        boolean wildcard = false;
        if (domain.startsWith("*.")) {
            if (!allowWildcard) {
                return new TargetClassified(null, null, "此处不支持通配域名");
            }
            wildcard = true;
            domain = domain.substring(2);
        }
        String digitsAndDots = domain.replaceAll("[0123456789.]", "");
        if (digitsAndDots.isEmpty() && !domain.isEmpty()) {
            return new TargetClassified(null, null, "不是合法 IPv4 地址");
        }
        if (domain.isEmpty() || domain.contains("*")) {
            return new TargetClassified(null, null, "通配符只能是单层前缀，例如 *.example.com");
        }
        if (domain.startsWith(".") || domain.endsWith(".") || domain.contains("..")) {
            return new TargetClassified(null, null, "不是合法域名");
        }
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return new TargetClassified(null, null, "不是合法域名");
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63) {
                return new TargetClassified(null, null, "不是合法域名");
            }
            for (int i = 0; i < label.length(); i++) {
                char r = label.charAt(i);
                boolean isAlnum = (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9');
                if (!isAlnum && r != '-') {
                    return new TargetClassified(null, null, "不是合法域名");
                }
            }
            if (label.startsWith("-") || label.endsWith("-")) {
                return new TargetClassified(null, null, "不是合法域名");
            }
        }
        String key = wildcard ? "*." + domain : domain;
        return new TargetClassified(TargetKind.DOMAIN, key, null);
    }

    private static boolean nonEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    // ── IP/CIDR 字面量解析（对照 net.ParseIP / net.ParseCIDR 的字面量语义） ──

    /** 仅解析字面量，绝不做 DNS（对照 net.ParseIP；Java 的 getByName 可能触发解析） */
    public static java.net.InetAddress parseLiteralIp(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        if (host.indexOf(':') >= 0) {
            // IPv6 字面量（可能带 %zone）；带冒号的字符串不是主机名，不会触发 DNS
            try {
                return java.net.InetAddress.getByName(
                        host.startsWith("[") && host.endsWith("]")
                                ? host.substring(1, host.length() - 1)
                                : host);
            } catch (Exception e) {
                return null;
            }
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            // Go 1.17+ 的 ParseIP 拒绝前导零与非数字
            if (p.isEmpty() || p.length() > 3) {
                return null;
            }
            if (p.length() > 1 && p.charAt(0) == '0') {
                return null;
            }
            for (int k = 0; k < p.length(); k++) {
                if (p.charAt(k) < '0' || p.charAt(k) > '9') {
                    return null;
                }
            }
            int v = Integer.parseInt(p);
            if (v > 255) {
                return null;
            }
            octets[i] = v;
        }
        byte[] addr = {(byte) octets[0], (byte) octets[1], (byte) octets[2], (byte) octets[3]};
        try {
            return java.net.InetAddress.getByAddress(addr);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer parsePrefixLen(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 3) {
            return null;
        }
        for (int k = 0; k < raw.length(); k++) {
            if (raw.charAt(k) < '0' || raw.charAt(k) > '9') {
                return null;
            }
        }
        return Integer.parseInt(raw);
    }

    /** 对照 net.ParseCIDR 的 network.String()：主机位清零后的规范化 a.b.c.d/p */
    private static String canonicalV4Cidr(byte[] addr, int prefix) {
        int v = ((addr[0] & 0xff) << 24) | ((addr[1] & 0xff) << 16)
                | ((addr[2] & 0xff) << 8) | (addr[3] & 0xff);
        int mask = prefix == 0 ? 0 : (0xffffffff << (32 - prefix)) & 0xffffffff;
        int net = v & mask;
        return ((net >>> 24) & 0xff) + "." + ((net >>> 16) & 0xff) + "."
                + ((net >>> 8) & 0xff) + "." + (net & 0xff) + "/" + prefix;
    }

    // ── Cube HTTP 规则校验（对照 validateCubeEgressRules） ─────────────────

    private static final Map<String, Boolean> CUBE_AUDIT_LEVELS = Map.of(
            "", true, "none", true, "metadata", true, "full", true);
    private static final Map<String, Boolean> HTTP_METHODS = Map.of(
            "GET", true, "HEAD", true, "POST", true, "PUT", true, "PATCH", true,
            "DELETE", true, "CONNECT", true, "OPTIONS", true, "TRACE", true);

    static String validateCubeEgressRules(List<CubeEgressRule> rules) {
        if (rules == null) {
            return null;
        }
        Map<String, Boolean> names = new LinkedHashMap<>();
        for (CubeEgressRule rule : rules) {
            String name = rule.getName() == null ? "" : rule.getName().trim();
            if (name.isEmpty()) {
                return "每条 Cube HTTP 规则都需要 name，用于审计与模板合并";
            }
            if (names.containsKey(name)) {
                return "cube HTTP 规则 name \"" + name + "\" 重复";
            }
            names.put(name, true);

            String host = rule.getHost() == null ? "" : rule.getHost().trim();
            String sni = rule.getSni() == null ? "" : rule.getSni().trim();
            if (host.isEmpty() && sni.isEmpty()) {
                return "cube HTTP 规则 \"" + name + "\" 必须填 host 或 sni："
                        + "网络层只从这两个字段提取放行目标，只写 method / path 的规则永远到不了 CubeEgress";
            }
            if (!host.isEmpty()) {
                TargetClassified c = classifyNetworkTarget(hostWithoutPort(host), true);
                if (c.error != null) {
                    return "cube HTTP 规则 \"" + name + "\" 的 host \"" + rule.getHost() + "\": " + c.error;
                }
            }
            if (!sni.isEmpty()) {
                TargetClassified c = classifyNetworkTarget(sni, true);
                if (c.error != null) {
                    return "cube HTTP 规则 \"" + name + "\" 的 sni \"" + rule.getSni() + "\": " + c.error;
                }
                if (c.kind != TargetKind.DOMAIN) {
                    return "cube HTTP 规则 \"" + name + "\" 的 sni 只能是域名，不能是 IP";
                }
            }
            String scheme = rule.getScheme() == null ? "" : rule.getScheme().trim().toLowerCase();
            if (!scheme.isEmpty() && !"http".equals(scheme) && !"https".equals(scheme)) {
                return "cube HTTP 规则 \"" + name + "\" 的 scheme 只能是 http 或 https";
            }
            String audit = rule.getAudit() == null ? "" : rule.getAudit().trim().toLowerCase();
            if (!CUBE_AUDIT_LEVELS.containsKey(audit)) {
                return "cube HTTP 规则 \"" + name + "\" 的 audit 只能是 none、metadata 或 full";
            }
            if (rule.getMethods() != null) {
                for (String method : rule.getMethods()) {
                    String upper = method == null ? "" : method.trim().toUpperCase();
                    if (!HTTP_METHODS.containsKey(upper)) {
                        return "cube HTTP 规则 \"" + name + "\" 的 method \"" + method
                                + "\" 不是标准 HTTP 方法";
                    }
                }
            }
            Map<String, Boolean> injectHeaders = new LinkedHashMap<>();
            if (rule.getInject() != null) {
                for (CubeHeaderInject inject : rule.getInject()) {
                    String header = inject.getHeader() == null ? "" : inject.getHeader().trim();
                    if (header.isEmpty()) {
                        return "cube HTTP 规则 \"" + name + "\" 的注入 header 名不能为空";
                    }
                    String headerErr = validateHTTPHeaderName(header);
                    if (headerErr != null) {
                        return "规则 \"" + name + "\" 的注入 header 名 \"" + header + "\": " + headerErr;
                    }
                    if (injectHeaders.containsKey(header)) {
                        return "cube HTTP 规则 \"" + name + "\" 的注入 header 名 \"" + header + "\" 重复";
                    }
                    injectHeaders.put(header, true);
                    String valueErr = validateHTTPHeaderValue(inject.getSecret());
                    if (valueErr != null) {
                        return "规则 \"" + name + "\" 的注入 header \"" + header + "\" 的值: " + valueErr;
                    }
                    String formatErr = validateHTTPHeaderValue(inject.getFormat());
                    if (formatErr != null) {
                        return "规则 \"" + name + "\" 的注入 header \"" + header + "\" 的 format: " + formatErr;
                    }
                    if (rule.isDeny() && inject.getSecret() != null && !inject.getSecret().isEmpty()) {
                        return "cube HTTP 规则 \"" + name + "\" 是拒绝规则，注入 header 不会生效";
                    }
                }
            }
        }
        return null;
    }

    static String hostWithoutPort(String host) {
        int lastColon = host.lastIndexOf(':');
        if (lastColon < 0) {
            return host;
        }
        // net.SplitHostPort 语义：有端口段且剩余部分非空才算成功；IPv6 字面量带 [ ]
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close > 0 && host.length() > close + 1 && host.charAt(close + 1) == ':') {
                return host.substring(1, close);
            }
            return host;
        }
        if (host.indexOf(':') != lastColon) {
            // 多个冒号且无 [ ]：SplitHostPort 报 too many colons → 原样返回
            return host;
        }
        String hostPart = host.substring(0, lastColon);
        String portPart = host.substring(lastColon + 1);
        if (hostPart.isEmpty() || portPart.isEmpty()) {
            return host;
        }
        return hostPart;
    }

    // ── E2B host 规则校验（对照 validateE2BHostRules） ─────────────────────

    static String validateE2BHostRules(List<E2BHostRule> rules, Map<String, String> allowKeys) {
        if (rules == null) {
            return null;
        }
        if (rules.size() > E2B_MAX_RULE_DOMAINS) {
            return "e2b 每个沙箱最多 " + E2B_MAX_RULE_DOMAINS + " 个 host 规则域名";
        }
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (E2BHostRule rule : rules) {
            String host = rule.getHost() == null ? "" : rule.getHost().trim();
            if (host.isEmpty()) {
                return "e2b host 规则的 host 不能为空";
            }
            if (host.length() > E2B_MAX_RULE_DOMAIN_LENGTH) {
                return "e2b host 规则的 host \"" + host + "\" 超过 "
                        + E2B_MAX_RULE_DOMAIN_LENGTH + " 字符";
            }
            TargetClassified c = classifyNetworkTarget(host, true);
            if (c.error != null) {
                return "e2b host 规则的 host \"" + host + "\": " + c.error;
            }
            if (c.kind != TargetKind.DOMAIN) {
                return "e2b host 规则的 host \"" + host + "\" 只能是域名";
            }
            if (seen.containsKey(c.key)) {
                return "e2b host 规则 \"" + host + "\" 重复：每个域名只能有一条规则";
            }
            seen.put(c.key, true);

            if (!allowListCovers(allowKeys, c.key)) {
                return "e2b host 规则的 host \"" + host + "\" 必须同时出现在 allow_out 中："
                        + "规则本身只做 header 注入，不授权出网";
            }
            if (rule.getHeaders() != null && rule.getHeaders().size() > E2B_MAX_HEADERS_PER_RULE) {
                return "e2b host 规则 \"" + host + "\" 最多 " + E2B_MAX_HEADERS_PER_RULE + " 个 header";
            }
            if (rule.getHeaders() != null) {
                for (Map.Entry<String, String> e : rule.getHeaders().entrySet()) {
                    String name = e.getKey();
                    String value = e.getValue();
                    if (name == null || name.trim().isEmpty()) {
                        return "e2b host 规则 \"" + host + "\" 的 header 名不能为空";
                    }
                    String nameErr = validateHTTPHeaderName(name);
                    if (nameErr != null) {
                        return "host 规则 \"" + host + "\" 的 header 名 \"" + name + "\": " + nameErr;
                    }
                    if (name.length() > E2B_MAX_HEADER_NAME_LENGTH) {
                        return "e2b host 规则 \"" + host + "\" 的 header 名超过 "
                                + E2B_MAX_HEADER_NAME_LENGTH + " 字符";
                    }
                    String valueErr = validateHTTPHeaderValue(value);
                    if (valueErr != null) {
                        return "host 规则 \"" + host + "\" 的 header \"" + name + "\" 的值: " + valueErr;
                    }
                    if (value != null && value.length() > E2B_MAX_HEADER_VALUE_LEN) {
                        return "e2b host 规则 \"" + host + "\" 的 header 值超过 "
                                + E2B_MAX_HEADER_VALUE_LEN + " 字符";
                    }
                }
            }
        }
        return null;
    }

    /** 对照 validateHTTPHeaderName：拒绝不可能成为单个 HTTP 字段名的字符串（防 CR/LF 走私） */
    static String validateHTTPHeaderName(String name) {
        if (name == null || name.isEmpty()) {
            return "不能为空";
        }
        for (int i = 0; i < name.length(); i++) {
            if (!isHttpTokenChar(name.charAt(i))) {
                return "不是合法 HTTP header 名";
            }
        }
        return null;
    }

    /** 对照 validateHTTPHeaderValue：拒绝 CR/LF/NUL；空允许（未设置/占位） */
    static String validateHTTPHeaderValue(String value) {
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\u0000') >= 0)) {
            return "不能包含换行或 NUL";
        }
        return null;
    }

    private static boolean isHttpTokenChar(char r) {
        if (r <= 32 || r >= 127) {
            return false;
        }
        switch (r) {
            case '(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']', '?', '=', '{', '}':
                return false;
            default:
                return true;
        }
    }

    /** 对照 allowListCovers：精确名或通配后缀匹配（"*.example.com" 盖子域不盖裸域） */
    private static boolean allowListCovers(Map<String, String> allowKeys, String host) {
        if (allowKeys == null) {
            return false;
        }
        if (allowKeys.containsKey(host)) {
            return true;
        }
        for (String key : allowKeys.keySet()) {
            if (key == null || !key.startsWith("*.")) {
                continue;
            }
            String suffix = key.substring(2);
            if (host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    // ── 规则类型（对照 CubeEgressRule / CubeHeaderInject / E2BHostRule） ────

    /** 对照 {@code types.CubeEgressRule}：Match 字段 AND、Methods 内部 OR。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CubeEgressRule {
        /** 无 omitempty：恒输出 */
        @JsonProperty("name")
        private String name = "";
        @JsonProperty("scheme")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private String scheme = "";
        @JsonProperty("sni")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private String sni = "";
        @JsonProperty("host")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private String host = "";
        @JsonProperty("methods")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private List<String> methods;
        @JsonProperty("path")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private String path = "";
        /** 反转动作（默认 allow）；deny 规则仍需 Host 或 SNI */
        @JsonProperty("deny")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private boolean deny;
        /** none | metadata | full；空用服务端默认 */
        @JsonProperty("audit")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private String audit = "";
        @JsonProperty("inject")
        private List<CubeHeaderInject> inject;

        public String getName() { return name; }
        public void setName(String v) { name = v == null ? "" : v; }
        public String getScheme() { return scheme; }
        public void setScheme(String v) { scheme = v == null ? "" : v; }
        public String getSni() { return sni; }
        public void setSni(String v) { sni = v == null ? "" : v; }
        public String getHost() { return host; }
        public void setHost(String v) { host = v == null ? "" : v; }
        public List<String> getMethods() { return methods; }
        public void setMethods(List<String> v) { methods = v; }
        public String getPath() { return path; }
        public void setPath(String v) { path = v == null ? "" : v; }
        public boolean isDeny() { return deny; }
        public void setDeny(boolean v) { deny = v; }
        public String getAudit() { return audit; }
        public void setAudit(String v) { audit = v == null ? "" : v; }
        public List<CubeHeaderInject> getInject() { return inject; }
        public void setInject(List<CubeHeaderInject> v) { inject = v; }

        CubeEgressRule copy() {
            CubeEgressRule c = new CubeEgressRule();
            c.name = name;
            c.scheme = scheme;
            c.sni = sni;
            c.host = host;
            c.methods = methods == null ? null : new ArrayList<>(methods);
            c.path = path;
            c.deny = deny;
            c.audit = audit;
            c.inject = inject;
            return c;
        }
    }

    /** 对照 {@code types.CubeHeaderInject}：Secret 是凭据（加密/打码），Header/Format 不是。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CubeHeaderInject {
        @JsonProperty("header")
        private String header = "";
        /** 无 omitempty：恒输出 */
        @JsonProperty("secret")
        private String secret = "";
        @JsonProperty("format")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private String format = "";

        public String getHeader() { return header; }
        public void setHeader(String v) { header = v == null ? "" : v; }
        public String getSecret() { return secret; }
        public void setSecret(String v) { secret = v == null ? "" : v; }
        public String getFormat() { return format; }
        public void setFormat(String v) { format = v == null ? "" : v; }

        CubeHeaderInject copy() {
            CubeHeaderInject c = new CubeHeaderInject();
            c.header = header;
            c.secret = secret;
            c.format = format;
            return c;
        }
    }

    /** 对照 {@code types.E2BHostRule}：host 必须同时出现在 allow_out。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class E2BHostRule {
        /** 无 omitempty：恒输出 */
        @JsonProperty("host")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private String host = "";
        /** 值是凭据（加密/打码）；名字保持可读。挂 GoMapSerializer 对齐 Go 的 map 键字母序。 */
        @JsonProperty("headers")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonSerialize(using = GoMapSerializer.class)
        private Map<String, String> headers;

        public String getHost() { return host; }
        public void setHost(String v) { host = v == null ? "" : v; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> v) { headers = v; }

        E2BHostRule copy() {
            E2BHostRule c = new E2BHostRule();
            c.host = host;
            c.headers = headers == null ? null : new LinkedHashMap<>(headers);
            return c;
        }
    }
}
