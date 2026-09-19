package com.ragagent.browserskill.service;

/**
 * 对照 Go {@code browserskill.ErrAuthorization}（store.go L13）：
 * 浏览器授权无效、过期或已吊销。authorize 端点对它单独映射 401
 * （对照 errors.Is(err, ErrAuthorization) → "authorization expired or already used"）。
 */
public class BrowserAuthorizationException extends BrowserSkillException {

    public BrowserAuthorizationException() {
        super("browser authorization is invalid or expired");
    }
}
