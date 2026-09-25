package com.ragagent.sandbox.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.sandbox.runtime.OutboundUrlGuard.OutboundURLPolicy;

/**
 * 对照 Go {@code internal/sandbox/docker_host.go}（DetectLocalDockerHost 及其上下文探测）
 * 与 {@code docker_engine.go} 的三个 Validate 函数（L253-331）。
 */
public final class DockerHostSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DockerHostSupport() {
    }

    /**
     * 对照 DetectLocalDockerHost：配置留空时返回 Docker CLI 在本机会用的 daemon endpoint：
     * 先 DOCKER_HOST，再当前 docker context，最后 DefaultDockerHost。
     * 能跑 {@code docker ps} 的操作员不应被迫把 socket 路径抄进设置表单。
     */
    public static String detectLocalDockerHost() {
        String host = System.getenv("DOCKER_HOST");
        if (host != null && !host.trim().isEmpty()) {
            return host;
        }
        String contextHost = dockerCliContextHost();
        if (!contextHost.isEmpty()) {
            return contextHost;
        }
        return EffectiveConfig.DEFAULT_DOCKER_HOST;
    }

    private static String dockerCliContextHost() {
        String configDir = System.getenv("DOCKER_CONFIG");
        if (configDir == null || configDir.trim().isEmpty()) {
            String home = System.getProperty("user.home");
            if (home == null || home.isEmpty()) {
                return "";
            }
            configDir = Path.of(home, ".docker").toString();
        }

        String name = System.getenv("DOCKER_CONTEXT");
        if (name == null || name.trim().isEmpty()) {
            name = dockerCurrentContextName(configDir);
        }
        if (name == null || name.isEmpty() || "default".equals(name)) {
            return "";
        }

        Path metaDir = Path.of(configDir, "contexts", "meta");
        if (!Files.isDirectory(metaDir)) {
            return "";
        }
        try (Stream<Path> entries = Files.list(metaDir)) {
            Iterator<Path> it = entries.iterator();
            while (it.hasNext()) {
                Path entry = it.next();
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                String host = dockerContextHost(
                        entry.resolve("meta.json").toString(), name);
                if (!host.isEmpty()) {
                    return host;
                }
            }
        } catch (IOException e) {
            return "";
        }
        return "";
    }

    private static String dockerCurrentContextName(String configDir) {
        Path configJson = Path.of(configDir, "config.json");
        if (!Files.isRegularFile(configJson)) {
            return "";
        }
        try {
            JsonNode parsed = MAPPER.readTree(configJson.toFile());
            JsonNode current = parsed.get("currentContext");
            return current == null || current.isNull() ? "" : current.asText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String dockerContextHost(String metaPath, String wantName) {
        Path metaFile = Path.of(metaPath);
        if (!Files.isRegularFile(metaFile)) {
            return "";
        }
        try {
            return contextHostFromMeta(MAPPER.readTree(metaFile.toFile()).toString(), wantName);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 对照 Go {@code dockerContextHost}（docker_host.go L80-103）解析 {@code meta.json}：
     * {@code Name} 在<b>顶层</b>（不是 {@code Metadata.Name}——那是用户自定义元数据），
     * {@code Endpoints} 是<b>对象</b>（形如 {@code {"docker":{"Host":"unix://…"}}}，
     * 不是数组）；host 取 {@code Endpoints["docker"].Host} 并 trim。
     *
     * <p><b>2026-09-25 修复（E2E 抓回）</b>：旧实现读 {@code Metadata.Name} + 把
     * {@code Endpoints} 当数组遍历，真实 docker CLI 写出的 meta.json 两种形状都不符
     * → 恒返回 "" → 回落 {@code /var/run/docker.sock}（macOS 上 OrbStack/Colima 的
     * 默认 socket 都在 $HOME 下，正是本函数存在要解决的场景）。</p>
     */
    static String contextHostFromMeta(String metaJson, String wantName) {
        if (metaJson == null || metaJson.isEmpty()) {
            return "";
        }
        try {
            JsonNode parsed = MAPPER.readTree(metaJson);
            JsonNode nameNode = parsed.get("Name");
            if (nameNode == null || !wantName.equals(nameNode.asText().trim())) {
                return "";
            }
            JsonNode endpoints = parsed.get("Endpoints");
            if (endpoints == null || !endpoints.isObject()) {
                return "";
            }
            JsonNode docker = endpoints.get("docker");
            if (docker == null) {
                return "";
            }
            JsonNode hostNode = docker.get("Host");
            return hostNode == null ? "" : hostNode.asText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 对照 ValidateDockerHost：存储或拨号前检查 daemon endpoint。TCP 端点获得与其他
     * 工作区自供 URL 相同的出站待遇；unix socket 本地即定义，只须绝对路径。
     *
     * @throws IllegalArgumentException 消息对照 Go 的三个 fmt.Errorf 分支逐字
     * @throws UnsafeOutboundURLException TCP 端点复用沙箱出站守卫
     */
    public static void validateDockerHost(String host, boolean allowPrivate) {
        String trimmed = host == null ? "" : host.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd < 0) {
            throw new IllegalArgumentException("sandbox: docker host \"" + host
                    + "\" must include a scheme (unix:// or tcp://)");
        }
        String rawScheme = trimmed.substring(0, schemeEnd);
        String scheme = rawScheme.toLowerCase();
        String address = trimmed.substring(schemeEnd + 3);
        switch (scheme) {
            case "unix" -> {
                if (!address.startsWith("/")) {
                    throw new IllegalArgumentException("sandbox: docker unix socket path \""
                            + address + "\" must be absolute");
                }
            }
            case "tcp", "http", "https" ->
                // 守卫说的是 HTTP；daemon 的 TCP 端点也是 HTTP 端点，
                // 所以检查与其他每个后端相同
                OutboundUrlGuard.validateOutboundURLWithPolicy(
                        "http://" + address, new OutboundURLPolicy(allowPrivate));
            // Go 的 %q 用的是 Cut 出来的原大小写 scheme
            default -> throw new IllegalArgumentException(
                    "sandbox: unsupported docker host scheme \"" + rawScheme + "\"");
        }
    }

    /**
     * 对照 ValidateDockerRemoteTLS：TCP daemon 要求客户端证书。接受容器创建的远程
     * Engine API 是那台宿主上的 root shell；明文 tcp://2375 不是可接受的触达方式。
     */
    public static void validateDockerRemoteTLS(String host, String tlsCertPath) {
        String trimmed = host == null ? "" : host.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd < 0) {
            return;
        }
        String scheme = trimmed.substring(0, schemeEnd).toLowerCase();
        switch (scheme) {
            case "tcp", "http", "https" -> {
                if (tlsCertPath == null || tlsCertPath.trim().isEmpty()) {
                    throw new IllegalArgumentException("sandbox: remote docker host \"" + host
                            + "\" requires a TLS certificate directory");
                }
            }
            default -> {
            }
        }
    }

    /**
     * 对照 ValidateDockerNetworkMode：只允许 bridge（出网）与 none（无出网）。
     * host / container: 模式直接共享另一个命名空间；命名网络通常是其部署自己的
     * compose 网络——这会把沙箱放到 Postgres 与 Redis 旁边。
     */
    public static void validateDockerNetworkMode(String mode) {
        String trimmed = mode == null ? "" : mode.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        switch (trimmed.toLowerCase()) {
            case "bridge", "none":
                return;
            default:
                throw new IllegalArgumentException("sandbox: docker network mode \"" + mode
                        + "\" is not allowed; use \"bridge\" or \"none\"");
        }
    }
}
