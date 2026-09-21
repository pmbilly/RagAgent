package com.ragagent.session.service;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;

/**
 * 沙箱终端 WS 的 Servlet 3.1 升级处理器（收尾批 W5d）。
 *
 * <p>存在的理由（Tomcat 实测）：101 之后对 {@code HttpServletResponse} 裸流的写入会被
 * Tomcat 按「1xx 无实体」静默吞掉（flush 成功、客户端收不到），手写 RFC 6455 必须走
 * {@code request.upgrade(...)} 拿 {@link WebConnection} 裸流，才等价 gorilla 的 Hijack。</p>
 *
 * <p>数据交接：容器只按类名实例化本处理器（无 Spring 注入），升级后的续跑逻辑
 * （open 终端 / close 帧 / bridge 装配）由控制器在调 {@code request.upgrade} 前经
 * {@link #arm} 挂上 ThreadLocal。Tomcat 在 service 返回后、<b>同一线程</b>上回调
 * {@link #init}，ThreadLocal 可见；注意此时过滤器链已退出（TenantContext 已清），
 * 续跑所需的租户等上下文必须由控制器显式捕获进闭包（对照 HANDOFF §2.3 纪律 3
 * 「显式拷 TenantContext」）。</p>
 */
public final class TerminalWebSocketUpgradeHandler implements HttpUpgradeHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalWebSocketUpgradeHandler.class);

    /** 升级交接：同线程 arm → init 消费。 */
    private static final ThreadLocal<Consumer<TerminalWebSocketServer>> PENDING = new ThreadLocal<>();

    /** 控制器在 {@code request.upgrade(...)} 之前挂上升级后续跑逻辑。 */
    public static void arm(Consumer<TerminalWebSocketServer> continuation) {
        PENDING.set(continuation);
    }

    @Override
    public void init(WebConnection connection) {
        Consumer<TerminalWebSocketServer> continuation = PENDING.get();
        PENDING.remove();
        TerminalWebSocketServer conn = null;
        try {
            conn = TerminalWebSocketServer.wrap(connection.getInputStream(),
                    connection.getOutputStream());
            if (continuation != null) {
                continuation.accept(conn);
            }
        } catch (Exception e) {
            log.warn("[sandbox-terminal] upgraded connection failed: {}", e.toString());
        } finally {
            if (conn != null) {
                conn.close();
            }
        }
    }

    @Override
    public void destroy() {
        // 连接生命周期由 init 的阻塞式续跑驱动；destroy 无需额外动作
    }
}
