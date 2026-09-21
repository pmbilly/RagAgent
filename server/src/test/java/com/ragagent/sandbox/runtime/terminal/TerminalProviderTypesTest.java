package com.ragagent.sandbox.runtime.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.ragagent.sandbox.runtime.terminal.TerminalTypes.RemoteTerminalOptions;

/**
 * provider 终端层的纯逻辑验收（对照 Go terminal.go 的钳位族 + pty_input_coalesce.go
 * 行为面）。provider SDK 传输（cube envd / e2b PTY 流）为 provider-XDEP——dev 无
 * 活沙箱，双端同落传输失败族（INTERNAL），本批不引入 SDK。
 */
class TerminalProviderTypesTest {

    @Test
    void optionDefaultsMatchGo() {
        RemoteTerminalOptions o = new RemoteTerminalOptions();
        assertEquals(80, o.cols());
        assertEquals(24, o.rows());
        assertEquals("/workspace", o.cwd());
        assertEquals("root", o.user());
        // envs 空时返回 nil（Go map nil）→ Java null，适配器据此跳过 env 注入
        assertEquals(null, o.envs());

        o.cols = 120;
        o.rows = 40;
        o.cwd = " /tmp ";
        o.user = " ubuntu ";
        o.envs = Map.of("FOO", "bar");
        assertEquals(120, o.cols());
        assertEquals(40, o.rows());
        assertEquals("/tmp", o.cwd());
        assertEquals("ubuntu", o.user());
        // 非空 envs 深拷贝 + TERM 兜底 xterm-256color（terminal.go L146-158）
        Map<String, String> merged = o.envs();
        assertEquals("bar", merged.get("FOO"));
        assertEquals("xterm-256color", merged.get("TERM"));
        // TERM 已设 → 不覆盖
        o.envs = Map.of("TERM", "xterm");
        assertEquals("xterm", o.envs().get("TERM"));
    }

    @Test
    void idleDisconnectClamp() {
        assertEquals(Duration.ofMinutes(15), TerminalTypes.effectiveTerminalIdleDisconnect(null));
        assertEquals(Duration.ofMinutes(15), TerminalTypes.effectiveTerminalIdleDisconnect(Duration.ZERO));
        assertEquals(Duration.ofSeconds(60),
                TerminalTypes.effectiveTerminalIdleDisconnect(Duration.ofSeconds(30)));
        assertEquals(Duration.ofMinutes(15),
                TerminalTypes.effectiveTerminalIdleDisconnect(Duration.ofMinutes(15)));
        assertEquals(Duration.ofHours(24),
                TerminalTypes.effectiveTerminalIdleDisconnect(Duration.ofHours(48)));
    }

    @Test
    void ttlRefreshIntervalClamp() {
        // ttl/3 钳到 [15s, 2m]（terminal.go L233-242）
        assertEquals(Duration.ofSeconds(15), TerminalTypes.terminalTtlRefreshInterval(Duration.ofSeconds(30)));
        assertEquals(Duration.ofSeconds(20), TerminalTypes.terminalTtlRefreshInterval(Duration.ofMinutes(1)));
        assertEquals(Duration.ofMinutes(1), TerminalTypes.terminalTtlRefreshInterval(Duration.ofMinutes(3)));
        assertEquals(Duration.ofMinutes(2), TerminalTypes.terminalTtlRefreshInterval(Duration.ofHours(1)));
    }

    @Test
    void coalescerMergesBurstIntoSingleSend() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        LinkedBlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();
        PtyInputCoalescer c = new PtyInputCoalescer(data -> {
            sends.incrementAndGet();
            sent.add(data.clone());
        });
        // 突发 5 次 Write：循环多半合并成 ≤2 次 send（首 chunk 立即走，其余 drain）
        for (int i = 0; i < 5; i++) {
            c.write(("k" + i).getBytes());
        }
        byte[] first = sent.poll(2, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(first, "首个 chunk 立即发送（无 Nagle 停顿）");
        byte[] rest = sent.poll(2, java.util.concurrent.TimeUnit.SECONDS);
        if (rest != null) {
            int total = first.length + rest.length;
            assertTrue(total >= 4, "drain 后的 follow-up 应含后续键击");
        }
        assertTrue(sends.get() <= 3, "聚合应显著少于 Write 次数");
        c.close();
    }

    @Test
    void coalescerIgnoresEmptyAndClosesCleanly() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        PtyInputCoalescer c = new PtyInputCoalescer(data -> sends.incrementAndGet());
        c.write(new byte[0]);
        c.write("a".getBytes());
        Thread.sleep(200);
        assertEquals(1, sends.get());
        c.close();
        PtyInputCoalescer.PtyInputClosedException err = null;
        try {
            c.write("b".getBytes());
        } catch (PtyInputCoalescer.PtyInputClosedException e) {
            err = e;
        }
        assertNotNull(err, "关闭后 Write 报 terminal input closed");
        assertEquals("terminal input closed", err.getMessage());
    }
}
