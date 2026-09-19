package com.ragagent.browserskill.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 对照 Go {@code browserskill.Scope}（manager.go L33-43）：按租户与用户隔离浏览器归属。
 *
 * <p>{@code key()} 是 scope 的持久化主键：SHA-256("<tenant>:<user>") 的前 16 字节
 * hex（32 字符）——与 Go 逐字节一致，是 browser_devices/browser_pairings/
 * browser_task_interruptions 三张表的跨语言键空间契约。</p>
 */
public record Scope(long tenant, String user) {

    public String key() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sum = digest.digest((tenant + ":" + user).getBytes(StandardCharsets.UTF_8));
            byte[] half = new byte[16];
            System.arraycopy(sum, 0, half, 0, 16);
            return HexFormat.of().formatHex(half);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 对照 Scope.valid：tenant != 0 && user != "" */
    public boolean valid() {
        return tenant != 0 && user != null && !user.isEmpty();
    }
}
