package com.ragagent.stream;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 测试用的一次性 Redis（对应 Go 测试里的 {@code miniredis}）。
 *
 * <h2>为什么不用 Spring 的自动配置</h2>
 * {@code RedisStreamManager} 的语义有一半在 <b>Lua 脚本与真实 TTL</b> 里：
 * steer 的去重/CAS、live-run 标记的续期，都不是能用内存假实现替代的东西
 * （本项目其他 Redis 依赖点靠 {@code Fake*} 替身测，是因为那些接口的语义在 Java 侧；
 * 这里不是）。所以起一个真 redis-server。
 *
 * <h2>取二进制的方式</h2>
 * 依次尝试：
 * <ol>
 *   <li>环境变量 {@code REDIS_TEST_ADDR}（{@code host:port}）—— 指向已有实例，不启动进程；</li>
 *   <li>{@code redis-server} 在 {@code PATH} 上（Homebrew / apt 装的都算）；</li>
 *   <li>都没有 → {@link org.junit.jupiter.api.Assumptions#assumeTrue} 跳过，测试不会红。</li>
 * </ol>
 * 只绑 {@code 127.0.0.1}、只走回环，不依赖外部网络。
 */
final class EmbeddedRedis implements AutoCloseable {

    private final Process process;
    private final String host;
    private final int port;
    private final LettuceConnectionFactory connectionFactory;
    private final StringRedisTemplate template;

    private EmbeddedRedis(Process process, String host, int port) {
        this.process = process;
        this.host = host;
        this.port = port;
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        this.connectionFactory = new LettuceConnectionFactory(config);
        this.connectionFactory.afterPropertiesSet();
        this.template = new StringRedisTemplate(connectionFactory);
        this.template.afterPropertiesSet();
    }

    /**
     * 启动实例；不可用时返回 {@code null}（调用方据此 assume 跳过）。
     *
     * <p>进程启动失败不抛异常——测试环境没有 redis-server 是常态，
     * 该跳过而不是让整个构建红掉。</p>
     */
    static EmbeddedRedis tryStart() {
        String external = System.getenv("REDIS_TEST_ADDR");
        if (external != null && !external.isEmpty()) {
            int colon = external.lastIndexOf(':');
            String h = external.substring(0, colon);
            int p = Integer.parseInt(external.substring(colon + 1));
            return new EmbeddedRedis(null, h, p);
        }

        Path binary = findRedisServer();
        if (binary == null) {
            return null;
        }
        int port = freePort();
        List<String> cmd = new ArrayList<>(List.of(
                binary.toString(),
                "--port", Integer.toString(port),
                "--bind", "127.0.0.1",
                "--save", "",
                "--appendonly", "no",
                "--daemonize", "no"));
        Process process;
        try {
            process = new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return null;
        }
        if (!awaitPort("127.0.0.1", port, Duration.ofSeconds(10))) {
            process.destroyForcibly();
            return null;
        }
        return new EmbeddedRedis(process, "127.0.0.1", port);
    }

    StringRedisTemplate template() {
        return template;
    }

    /** 清空本实例全部键——测试之间互不干扰。 */
    void flushAll() {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    @Override
    public void close() {
        try {
            connectionFactory.destroy();
        } catch (RuntimeException ignored) {
            // 关连接失败不影响测试结论
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private static Path findRedisServer() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(dir, "redis-server");
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port for embedded redis", e);
        }
    }

    private static boolean awaitPort(String host, int port, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 200);
                return true;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
