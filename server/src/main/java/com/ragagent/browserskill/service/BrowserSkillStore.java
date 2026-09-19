package com.ragagent.browserskill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.browserskill.domain.DeviceRecord;
import com.ragagent.browserskill.domain.PairingRecord;
import com.ragagent.browserskill.domain.Scope;
import com.ragagent.browserskill.domain.TaskInterruption;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 对照 Go {@code browserskill.Store}（store.go 全文）：持久化授权存储。
 * 只存令牌的 SHA-256 哈希；member() 同时校验当前用户/成员关系——
 * 被停用的账号不能继续使用先前签发的设备凭据（Go 注释原文）。
 *
 * <p>SQL 逐条对照 Go 的 GORM 调用（表结构以迁移 000093 为准）：</p>
 * <ul>
 *   <li>{@code createPair}/exchange 的 device upsert：GORM
 *       {@code OnConflict{Columns: scope_key, UpdateAll: true}}——Java 用
 *       「UPDATE 0 行则 INSERT」两步等价（H2 无 ON CONFLICT DO UPDATE；
 *       单实例事务内语义一致，并发竞态窗口见类注释已知差异）。</li>
 *   <li>时间列读写一律 OffsetDateTime（TIMESTAMPTZ）；revoke/claim/release
 *       写入的 Go 零值 time.Time{} 对应 {@link #GO_ZERO}</li>。
 *   <li>GORM First 查不到 → ErrRecordNotFound 分支的归一（authenticate →
 *       ErrAuthorization；account → null,null）逐条保留。</li>
 * </ul>
 */
@Repository
public class BrowserSkillStore {

    /** 对照 Go 的 time.Time{} 零值（写入 lease_until/前次撤销等清空语义） */
    public static final OffsetDateTime GO_ZERO =
            OffsetDateTime.parse("0001-01-01T00:00:00Z");

    private static final RowMapper<DeviceRecord> DEVICE = (rs, i) -> {
        DeviceRecord r = new DeviceRecord();
        r.setScopeKey(rs.getString("scope_key"));
        r.setId(rs.getString("id"));
        long tenant = rs.getLong("tenant");
        r.setTenant(rs.wasNull() ? null : tenant);
        r.setUser(rs.getString("user"));
        r.setLabel(rs.getString("label"));
        r.setTokenHash(rs.getString("token_hash"));
        r.setPreviousHash(rs.getString("previous_hash"));
        r.setPreviousUntil(toOdt(rs.getTimestamp("previous_until")));
        r.setExpiresAt(toOdt(rs.getTimestamp("expires_at")));
        r.setRenewAfter(toOdt(rs.getTimestamp("renew_after")));
        r.setCreatedAt(toOdt(rs.getTimestamp("created_at")));
        r.setLastSeenAt(toOdt(rs.getTimestamp("last_seen_at")));
        r.setRevokedAt(toOdt(rs.getTimestamp("revoked_at")));
        r.setOwner(rs.getString("owner"));
        r.setLeaseKey(rs.getString("lease_key"));
        r.setOwnerUrl(rs.getString("owner_url"));
        r.setLeaseUntil(toOdt(rs.getTimestamp("lease_until")));
        return r;
    };

    private static OffsetDateTime toOdt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(OffsetDateTime.now().getOffset());
    }

    private static Timestamp ts(OffsetDateTime t) {
        return t == null ? null : Timestamp.from(t.toInstant());
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public BrowserSkillStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
    }

    /**
     * 对照 Store.member（store.go L82-102）：用户活跃且（跨空间超管或本租户 active 成员）。
     * SQL 逐字对照（含 users 与 tenant_members 的双分支 OR EXISTS）。
     */
    public boolean member(Scope scope) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users AS u " +
                "WHERE u.id = ? AND u.is_active = ? AND u.deleted_at IS NULL " +
                "AND ((u.can_access_all_tenants = ? AND u.tenant_id <> ?) OR EXISTS (SELECT 1 FROM tenant_members " +
                "AS m WHERE m.user_id = u.id AND m.tenant_id = ? AND m.status = ? AND m.deleted_at IS NULL))",
                Integer.class, scope.user(), Boolean.TRUE, Boolean.TRUE, scope.tenant(),
                scope.tenant(), "active");
        if (count == null || count == 0) {
            throw new BrowserAuthorizationException();
        }
        return true;
    }

    /** 对照 Store.createPair：member 校验 + scope_key upsert（UpdateAll） */
    public void createPair(PairingRecord p) {
        member(new Scope(p.getTenant(), p.getUser()));
        upsertPairing(p);
    }

    private void upsertPairing(PairingRecord p) {
        int updated = jdbc.update(
                "UPDATE browser_pairings SET token_hash = ?, tenant = ?, \"user\" = ?, expires_at = ? " +
                "WHERE scope_key = ?",
                p.getTokenHash(), p.getTenant(), p.getUser(), ts(p.getExpiresAt()), p.getScopeKey());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO browser_pairings (scope_key, token_hash, tenant, \"user\", expires_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
                    p.getScopeKey(), p.getTokenHash(), p.getTenant(), p.getUser(), ts(p.getExpiresAt()));
        }
    }

    /**
     * 对照 Store.exchange（store.go L114-140）：一次性票据兑换。
     * 找有效 pairing → member 校验 → 原子删票（RowsAffected != 1 → ErrAuthorization）→
     * upsert 设备行（UpdateAll 语义：revoked_at/owner 等全部重置）。
     */
    public DeviceRecord exchange(String oldHash, DeviceRecord r) {
        return tx.execute(status -> {
            List<DeviceRecord> pairings = jdbc.query(
                    "SELECT * FROM browser_pairings WHERE token_hash = ? AND expires_at > ?",
                    (rs, i) -> {
                        PairingRecord p = new PairingRecord();
                        p.setScopeKey(rs.getString("scope_key"));
                        p.setTokenHash(rs.getString("token_hash"));
                        p.setTenant(rs.getLong("tenant"));
                        p.setUser(rs.getString("user"));
                        p.setExpiresAt(toOdt(rs.getTimestamp("expires_at")));
                        return toDevice(p);
                    }, oldHash, OffsetDateTime.now());
            if (pairings.isEmpty()) {
                throw new BrowserAuthorizationException();
            }
            DeviceRecord p = pairings.get(0);
            member(p.scope());
            PairingRecord key = new PairingRecord();
            key.setScopeKey(p.getScopeKey());
            int used = jdbc.update(
                    "DELETE FROM browser_pairings WHERE scope_key = ? AND token_hash = ? AND expires_at > ?",
                    p.getScopeKey(), oldHash, OffsetDateTime.now());
            if (used != 1) {
                throw new BrowserAuthorizationException();
            }
            DeviceRecord next = new DeviceRecord();
            next.setScopeKey(p.getScopeKey());
            next.setId(r.getId());
            next.setTenant(p.getTenant());
            next.setUser(p.getUser());
            next.setLabel(r.getLabel());
            next.setTokenHash(r.getTokenHash());
            next.setPreviousHash("");
            next.setPreviousUntil(GO_ZERO);
            next.setExpiresAt(r.getExpiresAt());
            next.setRenewAfter(r.getRenewAfter());
            next.setCreatedAt(r.getCreatedAt());
            next.setLastSeenAt(r.getLastSeenAt());
            next.setRevokedAt(null);
            next.setOwner("");
            next.setLeaseKey("");
            next.setOwnerUrl("");
            next.setLeaseUntil(GO_ZERO);
            upsertDevice(next);
            return next;
        });
    }

    /** 把 pairing 行借 DeviceRecord 承载（对照 Go 复用 struct 字段的做法） */
    private DeviceRecord toDevice(PairingRecord p) {
        DeviceRecord d = new DeviceRecord();
        d.setScopeKey(p.getScopeKey());
        d.setTenant(p.getTenant());
        d.setUser(p.getUser());
        return d;
    }

    private void upsertDevice(DeviceRecord r) {
        int updated = jdbc.update(
                "UPDATE browser_devices SET id = ?, tenant = ?, \"user\" = ?, label = ?, token_hash = ?, " +
                "previous_hash = ?, previous_until = ?, expires_at = ?, renew_after = ?, created_at = ?, " +
                "last_seen_at = ?, revoked_at = ?, owner = ?, lease_key = ?, owner_url = ?, lease_until = ? " +
                "WHERE scope_key = ?",
                r.getId(), r.getTenant(), r.getUser(), r.getLabel(), r.getTokenHash(),
                r.getPreviousHash(), ts(r.getPreviousUntil()), ts(r.getExpiresAt()), ts(r.getRenewAfter()),
                ts(r.getCreatedAt()), ts(r.getLastSeenAt()), ts(r.getRevokedAt()), r.getOwner(),
                r.getLeaseKey(), r.getOwnerUrl(), ts(r.getLeaseUntil()), r.getScopeKey());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO browser_devices (scope_key, id, tenant, \"user\", label, token_hash, " +
                    "previous_hash, previous_until, expires_at, renew_after, created_at, last_seen_at, " +
                    "revoked_at, owner, lease_key, owner_url, lease_until) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    r.getScopeKey(), r.getId(), r.getTenant(), r.getUser(), r.getLabel(), r.getTokenHash(),
                    r.getPreviousHash(), ts(r.getPreviousUntil()), ts(r.getExpiresAt()), ts(r.getRenewAfter()),
                    ts(r.getCreatedAt()), ts(r.getLastSeenAt()), ts(r.getRevokedAt()), r.getOwner(),
                    r.getLeaseKey(), r.getOwnerUrl(), ts(r.getLeaseUntil()));
        }
    }

    /** 对照 Store.account：查不到返回 null（Go 的 nil, nil——不是错误） */
    public DeviceRecord account(Scope scope) {
        List<DeviceRecord> rows = jdbc.query(
                "SELECT * FROM browser_devices WHERE scope_key = ?", DEVICE, scope.key());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 对照 Store.authenticate（store.go L151-169）：令牌哈希命中（含 5 分钟宽限的
     * previous_hash）+ member 复核。查不到 → ErrAuthorization。
     */
    public DeviceRecord authenticate(String hash) {
        OffsetDateTime now = OffsetDateTime.now();
        List<DeviceRecord> rows = jdbc.query(
                "SELECT * FROM browser_devices WHERE revoked_at IS NULL AND expires_at > ? " +
                "AND (token_hash = ? OR (previous_hash = ? AND previous_until > ?))",
                DEVICE, ts(now), hash, hash, ts(now));
        if (rows.isEmpty()) {
            throw new BrowserAuthorizationException();
        }
        DeviceRecord r = rows.get(0);
        member(r.scope());
        return r;
    }

    /**
     * 对照 Store.renew（store.go L171-200）：持久化的客户端候选令牌让丢失的响应可重试
     * ——持有新令牌即证明所有权，即使旧令牌宽限已过。幂等：先按 (nextHash, oldHash)
     * 查已完成的续期，命中直接返回。
     */
    public DeviceRecord renew(String oldHash, String nextHash) {
        try {
            DeviceRecord r = authenticate(nextHash);
            if (nextHash.equals(r.getTokenHash()) && oldHash.equals(r.getPreviousHash())) {
                return r;
            }
        } catch (BrowserAuthorizationException ignored) {
            // 对照 Go：err == nil 才走快速路径，否则继续老令牌分支
        }
        DeviceRecord r = authenticate(oldHash);
        if (!oldHash.equals(r.getTokenHash())) {
            throw new BrowserAuthorizationException();
        }
        OffsetDateTime now = OffsetDateTime.now();
        int rows = jdbc.update(
                "UPDATE browser_devices SET token_hash = ?, previous_hash = ?, previous_until = ?, " +
                "expires_at = ?, renew_after = ? WHERE id = ? AND token_hash = ? AND revoked_at IS NULL",
                nextHash, oldHash, ts(now.plusMinutes(5)),
                ts(now.plusDays(90)), ts(now.plusDays(30)), r.getId(), oldHash);
        if (rows != 1) {
            throw new BrowserAuthorizationException();
        }
        return authenticate(nextHash);
    }

    /** 对照 Store.revoke（store.go L202-212）：删 pairing + 设备行打撤销标记并清租约 */
    public void revoke(Scope scope) {
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM browser_pairings WHERE scope_key = ?", scope.key());
            jdbc.update(
                    "UPDATE browser_devices SET revoked_at = ?, owner = '', owner_url = '', lease_until = ? " +
                    "WHERE scope_key = ?",
                    ts(OffsetDateTime.now()), ts(GO_ZERO), scope.key());
        });
    }

    /** 对照 Store.claim（store.go L214-230）：空闲租约的乐观抢占，抢不到 → ErrLeaseHeld */
    public void claim(DeviceRecord r, String owner, String url, String leaseKey) {
        OffsetDateTime now = OffsetDateTime.now();
        int rows = jdbc.update(
                "UPDATE browser_devices SET owner = ?, owner_url = ?, lease_key = ?, " +
                "lease_until = ?, last_seen_at = ? " +
                "WHERE id = ? AND revoked_at IS NULL AND expires_at > ? AND (lease_until < ? OR owner = '')",
                owner, url, leaseKey, ts(now.plusSeconds(45)), ts(now),
                r.getId(), ts(now), ts(now));
        if (rows != 1) {
            throw new BrowserLeaseHeldException();
        }
    }

    /** 对照 Store.heartbeat（store.go L232-253）：10s 一次的租约续期 + member 复核 */
    public DeviceRecord heartbeat(String id, String leaseKey) {
        OffsetDateTime now = OffsetDateTime.now();
        List<DeviceRecord> rows = jdbc.query(
                "SELECT * FROM browser_devices WHERE id = ? AND lease_key = ? " +
                "AND revoked_at IS NULL AND expires_at > ?", DEVICE, id, leaseKey, ts(now));
        if (rows.isEmpty()) {
            // Go: First 查不到直接返回 gorm.ErrRecordNotFound（非 ErrAuthorization）
            throw new BrowserSkillException("record not found");
        }
        DeviceRecord r = rows.get(0);
        member(r.scope());
        int updated = jdbc.update(
                "UPDATE browser_devices SET lease_until = ?, last_seen_at = ? " +
                "WHERE id = ? AND lease_key = ? AND revoked_at IS NULL AND lease_until > ?",
                ts(now.plusSeconds(45)), ts(now), id, leaseKey, ts(now));
        if (updated != 1) {
            throw new BrowserAuthorizationException();
        }
        return r;
    }

    /** 对照 Store.release（store.go L255-261）：连接关闭后的租约清空 */
    public void release(String id, String leaseKey) {
        jdbc.update(
                "UPDATE browser_devices SET owner = '', owner_url = '', lease_until = ? " +
                "WHERE id = ? AND lease_key = ?",
                ts(GO_ZERO), id, leaseKey);
    }

    /** 对照 Store.tasks（store.go L263-267）：该 scope 需要显式 resume 的任务标记 */
    public List<TaskInterruption> tasks(Scope scope) {
        return jdbc.query(
                "SELECT scope_key, session FROM browser_task_interruptions WHERE scope_key = ?",
                (rs, i) -> new TaskInterruption(rs.getString("scope_key"), rs.getString("session")),
                scope.key());
    }

    /** 对照 Store.markTask：ON CONFLICT DO NOTHING（H2 同语法支持 DO NOTHING） */
    public void markTask(Scope scope, String session) {
        jdbc.update(
                "INSERT INTO browser_task_interruptions (scope_key, session) VALUES (?, ?) " +
                "ON CONFLICT DO NOTHING",
                scope.key(), session);
    }

    /** 对照 Store.clearTask */
    public void clearTask(Scope scope, String session) {
        jdbc.update(
                "DELETE FROM browser_task_interruptions WHERE scope_key = ? AND session = ?",
                scope.key(), session);
    }

    /** 对照 Store.releaseOwner（store.go L283-289）：进程关闭时清空自己持有的全部租约 */
    public void releaseOwner(String owner) {
        jdbc.update(
                "UPDATE browser_devices SET owner = '', owner_url = '', lease_until = ? WHERE owner = ?",
                ts(GO_ZERO), owner);
    }

    ObjectMapper mapper() {
        return mapper;
    }
}
