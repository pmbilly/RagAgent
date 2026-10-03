package com.ragagent.common.tenant;

/**
 * 租户角色。
 *
 * 等级间距 10 以便未来插入新角色；未知角色 level=0（严格低于任何定义角色，
 * 对应 Go Level() 的 "Unknown roles return 0"）。
 */
public enum TenantRole {
    OWNER("owner", 40),
    ADMIN("admin", 30),
    CONTRIBUTOR("contributor", 20),
    VIEWER("viewer", 10),
    /** 未匹配/空角色，level=0 */
    UNKNOWN("", 0);

    private final String value;
    private final int level;

    TenantRole(String value, int level) {
        this.value = value;
        this.level = level;
    }

    public String value() { return value; }
    public int level() { return level; }

    /** Go TenantRole.HasPermission：r.Level() >= required.Level() */
    public boolean hasPermission(TenantRole required) {
        return this.level >= required.level;
    }

    public boolean isValid() {
        return this != UNKNOWN;
    }

    public static TenantRole fromString(String v) {
        if (v == null) return UNKNOWN;
        for (TenantRole r : values()) {
            if (r.value.equals(v)) return r;
        }
        return UNKNOWN;
    }

    @Override
    public String toString() { return value; }
}
