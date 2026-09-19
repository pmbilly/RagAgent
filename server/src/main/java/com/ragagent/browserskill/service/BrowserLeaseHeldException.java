package com.ragagent.browserskill.service;

/**
 * 对照 Go {@code browserskill.ErrLeaseHeld}（store.go L16）：
 * 设备连接租约仍被另一副本持有（claim 的乐观锁冲突）。
 */
public class BrowserLeaseHeldException extends BrowserSkillException {

    public BrowserLeaseHeldException() {
        super("browser connection is owned by another node; reconnect shortly");
    }
}
