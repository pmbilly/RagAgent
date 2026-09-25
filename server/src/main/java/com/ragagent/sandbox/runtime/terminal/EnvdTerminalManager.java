package com.ragagent.sandbox.runtime.terminal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.sandbox.runtime.SandboxException;

/**
 * provider 中立的中性终端管理器（{@link TerminalTypes.RemoteTerminalManager}）在 envd 上的实现
 * （对照 Go {@code CubeRemoteClient.OpenTerminal}+{@code E2BRemoteClient.OpenTerminal} 的公共骨架，
 * {@code cube_terminal.go:38-101}、{@code e2b_terminal.go:25-95}）。
 *
 * <h2>两个 provider 的差异被收进一个接缝</h2>
 *
 * <p>Go 两侧的 {@code OpenTerminal} 骨架一致：① 解析沙箱句柄 → ② provider 控制面
 * {@code Connect}（刷新沙箱寿命 + 拿数据面端点与 token，<b>顺带算活动信号</b>）→ ③ 建/重附 PTY 流
 * → ④ 读到 {@code start} 事件拿 PID → ⑤ 起泵 + TTL 刷新。</p>
 *
 * <p>其中 ②（控制面）与"TTL 怎么刷"依赖各自 SDK 路由（Cube {@code /sandboxes/{id}} 一族、
 * E2B {@code /sandboxes/{id}/connect}）——**路由字面尚未在真实 provider 上校准**（与
 * {@code RemoteProviderClient} 的诚实声明同源），故本类把它做成 {@link EndpointResolver} 接缝：
 * 执行体（流 + 生命周期 + 事件三态）在本批完整落地并被本地桩覆盖，控制面留在接缝后（XDEP）。</p>
 *
 * <p>建/重附的选择照 Go：{@code opts.attachPid > 0} 先试 {@code Connect}，<b>失败回落 Create</b>
 * （{@code openCubePty}/{@code openE2BPty}）。</p>
 */
public final class EnvdTerminalManager implements TerminalTypes.RemoteTerminalManager {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(EnvdTerminalManager.class);

    /** 数据面端点与它的控制面钩子（由 provider 适配器解析）。 */
    public record Endpoint(URI dataPlaneBase, Map<String, String> headers,
            Duration streamTimeout, Duration sandboxTtl,
            EnvdTerminalSession.TtlRefresher ttlRefresher) {
    }

    /**
     * 控制面接缝：终端引用 → envd 数据面端点（含 token/头与 TTL 钩子）。
     *
     * <p>入参是 {@link TerminalTypes.RemoteTerminalRef}（不透明引用：provider/id/traffic token），
     * 与 Go 的 {@code openCubePty(ctx, handle, opts)} 传句柄同形——这样 resolver 不必反查绑定存储
     * （Java 绑定存储按 (tenantId, sessionId) 建键，没有 by-sandboxId 查询；见
     * {@code docs/w5delta-terminal-spike.md} §7.4）。</p>
     */
    public interface EndpointResolver {
        Endpoint resolve(TerminalTypes.RemoteTerminalRef ref) throws Exception;
    }

    private final String provider;
    private final EndpointResolver resolver;
    private final HttpClient client;

    public EnvdTerminalManager(String provider, EndpointResolver resolver, HttpClient client) {
        this.provider = provider;
        this.resolver = resolver;
        this.client = client;
    }

    @Override
    public TerminalTypes.RemoteTerminalSession openTerminal(TerminalTypes.RemoteTerminalRef ref,
            TerminalTypes.RemoteTerminalOptions opts) throws Exception {
        Endpoint endpoint = resolver.resolve(ref);
        EnvdConnectTransport transport = new EnvdConnectTransport(
                client, endpoint.dataPlaneBase(), endpoint.headers());
        Duration timeout = endpoint.streamTimeout() == null
                ? TerminalTypes.DEFAULT_TERMINAL_IDLE_DISCONNECT : endpoint.streamTimeout();

        // 重附优先：attachPid > 0 先试 Connect，失败回落 Create（照 openCubePty/openE2BPty）
        if (opts != null && opts.attachPid > 0) {
            EnvdConnectTransport.StreamingCall call = null;
            try {
                call = transport.openStream(EnvdPtyProtocol.METHOD_CONNECT,
                        EnvdPtyProtocol.connectBody(opts.attachPid), timeout);
                int pid = readStartPid(call, EnvdPtyProtocol.METHOD_CONNECT);
                log.info("[{}] terminal reattached sandbox={} pid={}", provider, ref.sandboxId(), pid);
                return new EnvdTerminalSession(provider, transport, call, pid,
                        endpoint.sandboxTtl(), endpoint.ttlRefresher());
            } catch (Exception e) {
                if (call != null) {
                    call.close();
                }
                log.debug("[{}] reattach pid={} failed ({}), falling back to create",
                        provider, opts.attachPid, e.toString());
            }
        }

        EnvdConnectTransport.StreamingCall call = transport.openStream(
                EnvdPtyProtocol.METHOD_START,
                EnvdPtyProtocol.createBody(opts, opts.cols(), opts.rows()), timeout);
        try {
            int pid = readStartPid(call, EnvdPtyProtocol.METHOD_START);
            log.info("[{}] terminal opened sandbox={} pid={}", provider, ref.sandboxId(), pid);
            return new EnvdTerminalSession(provider, transport, call, pid,
                    endpoint.sandboxTtl(), endpoint.ttlRefresher());
        } catch (Exception e) {
            call.close();
            throw e;
        }
    }

    /**
     * 读到 {@code start} 事件拿 PID（照 {@code readPtyStartPID}，{@code pty.go}）：
     * 中间事件一律跳过；流先关则报 {@code "<method>: stream closed before start event"}。
     */
    static int readStartPid(EnvdConnectTransport.StreamingCall call, String method)
            throws IOException, InterruptedException {
        while (true) {
            JsonNode payload = call.next();
            if (payload == null) {
                String endError = call.endError();
                throw SandboxException.internal(method
                        + ": stream closed before start event"
                        + (endError == null ? "" : " (" + endError + ")"));
            }
            EnvdPtyProtocol.Event event = EnvdPtyProtocol.parse(payload);
            if (event != null && event.startPid() != null) {
                return event.startPid();
            }
        }
    }
}
