package com.ragagent.auth.service;

/**
 * 对照 Go internal/application/service/password_policy.go。
 *
 * 注册 / 自助改密 / 管理员重置共用同一套策略（字母数字类按 ASCII，与前端正则一致；
 * 特殊字符为文档化白名单）。错误消息是 400 响应体的一部分，逐字符锁定。
 */
public final class PasswordPolicy {

    /** Go ErrPasswordPolicy 原文。 */
    public static final String ERR_PASSWORD_POLICY =
            "password must be 8-32 characters and contain at least one letter and one number";
    /** Go ErrComplexPasswordPolicy 原文。 */
    public static final String ERR_COMPLEX_PASSWORD_POLICY =
            "password must be 8-32 characters and must contain uppercase and lowercase "
                    + "letters, numbers, and special characters";

    /** change-password 响应 details 的机器可读令牌（Go service.Detail*）。 */
    public static final String DETAIL_INVALID_OLD_PASSWORD = "invalid_old_password";
    public static final String DETAIL_PASSWORD_POLICY = "password_policy";
    public static final String DETAIL_SAME_PASSWORD = "same_password";

    private static final String SPECIAL_CHARS = "!@#$%^&*()_+-=[]{}|;:,.<>?";

    private PasswordPolicy() {
    }

    /**
     * 对照 ValidatePasswordPolicy。长度按 UTF-16 code point 计（= Go utf8.RuneCountInString）。
     *
     * @return null = 通过；否则 = 错误消息（Go 哨兵原文）
     */
    public static String validate(String password, boolean complexEnabled) {
        int length = password.codePointCount(0, password.length());
        if (length < 8 || length > 32) {
            return complexEnabled ? ERR_COMPLEX_PASSWORD_POLICY : ERR_PASSWORD_POLICY;
        }
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;
        for (int i = 0; i < password.length(); i++) {
            char r = password.charAt(i);
            if (r >= 'A' && r <= 'Z') {
                hasUpper = true;
            } else if (r >= 'a' && r <= 'z') {
                hasLower = true;
            } else if (r >= '0' && r <= '9') {
                hasDigit = true;
            } else if (SPECIAL_CHARS.indexOf(r) >= 0) {
                hasSpecial = true;
            }
        }
        if (complexEnabled) {
            if (!hasUpper || !hasLower || !hasDigit || !hasSpecial) {
                return ERR_COMPLEX_PASSWORD_POLICY;
            }
            return null;
        }
        if ((!hasUpper && !hasLower) || !hasDigit) {
            return ERR_PASSWORD_POLICY;
        }
        return null;
    }
}
