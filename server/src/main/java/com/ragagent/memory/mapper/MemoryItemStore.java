package com.ragagent.memory.mapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ragagent.common.settings.MemoryKinds;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.memory.domain.MemoryConflictException;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryPage;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemorySubjectMissingException;
import com.ragagent.memory.domain.MemoryTombstone;

/**
 * 记忆条目与墓碑的读写：条目 CRUD 与批量维护、召回所需的各类查询、
 * 保存/确认的生命周期事务，以及墓碑的增删查。
 *
 * <p>持有 {@link MemoryRepository} 回引以访问各 mapper 与事务模板；本类不得独立实例化。</p>
 */
final class MemoryItemStore {


    private final MemoryRepository repo;

    MemoryItemStore(MemoryRepository repo) {
        this.repo = repo;
    }

    // ── 条目：写 ───────────────────────────────────────────────────────────

    /**
     * 对照 {@code CreateItem}：id 为空则生成、{@code valid_from} 为零值则补 {@code now}、
     * {@code status} 为空则 active，然后插入。
     *
     * <p>注意顺序：Go 先补 {@code status} 再交给 GORM，
     * 所以"零值 → 默认值"的替换在这里是显式写出来的。</p>
     */
    public void createItem(MemoryItem item) {
        if (item.getId().isEmpty()) {
            item.setId(UUID.randomUUID().toString());
        }
        if (GoTimeSerializer.isGoZero(item.getValidFrom())) {
            item.setValidFrom(OffsetDateTime.now());
        }
        if (item.getStatus().isEmpty()) {
            item.setStatus(MemoryKinds.STATUS_ACTIVE);
        }
        MemoryIndexStore.applyInsertDefaults(item);
        MemoryIndexStore.stampForCreate(item);
        repo.itemMapper.insert(item);
    }

    /** 对照 {@code UpdateItemContent}：内容变了才删向量、作废提议，最后无条件覆盖五列。 */
    public void updateItemContent(MemoryScope scope, String id, String content,
                                  String normalizedKey, int importance) {
        repo.tx.withSubject(scope, subject -> {
            MemoryItem current = repo.itemMapper.selectScoped(scope.tenantId(), scope.subjectId(), id);
            if (current == null) {
                // 对照 Go 的 First 未命中 → gorm.ErrRecordNotFound 上抛
                throw new MemorySubjectMissingException();
            }
            if (!current.getContent().equals(content)) {
                repo.embeddingMapper.deleteByItemId(scope.tenantId(), scope.subjectId(), id);
                // 编辑一条已确认的事实，会让基于它旧措辞的提议失效。
                repo.itemMapper.supersedeProposalsOf(scope.tenantId(), scope.subjectId(), id,
                        MemoryKinds.STATUS_PENDING, MemoryKinds.STATUS_SUPERSEDED, OffsetDateTime.now());
            }
            repo.itemMapper.updateItemContent(scope.tenantId(), scope.subjectId(), id, content,
                    normalizedKey, importance, MemoryKinds.ORIGIN_MANUAL, OffsetDateTime.now());
            return null;
        });
    }

    /** 对照 {@code SupersedeItem}。 */
    public void supersedeItem(MemoryScope scope, String id, String supersededBy) {
        repo.tx.withSubject(scope, subject -> {
            repo.itemMapper.supersedeItem(scope.tenantId(), scope.subjectId(), id, supersededBy,
                    MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_PENDING,
                    MemoryKinds.STATUS_SUPERSEDED, OffsetDateTime.now());
            return null;
        });
    }

    /**
     * 对照 {@code DeleteItem}：**物理删**。
     *
     * <p>三步：作废指向它的待确认项 → 删向量 → 删条目。"忘记就是忘记"，
     * 所以这里不软删、也不留墓碑（墓碑由 reject 路径单独写）。</p>
     */
    public void deleteItem(MemoryScope scope, String id) {
        repo.tx.withSubject(scope, subject -> {
            repo.itemMapper.supersedePendingReplacements(scope.tenantId(), scope.subjectId(), id,
                    MemoryKinds.STATUS_PENDING, MemoryKinds.STATUS_SUPERSEDED, OffsetDateTime.now());
            repo.embeddingMapper.deleteByItemId(scope.tenantId(), scope.subjectId(), id);
            repo.itemMapper.deleteScoped(scope.tenantId(), scope.subjectId(), id);
            return null;
        });
    }

    /**
     * 对照 {@code DeleteAll}：物理删本 scope 的全部条目，返回删了几行。
     *
     * <p>⚠️ Go 的 {@code Delete} 只对 {@code memory_items} 生效——**不动**
     * {@code memory_item_embeddings}。清空路径由 service 层另外调
     * {@code deleteAllTopics} / {@code deleteAllDocAffinity} 补齐（Go 也是这样）。
     * 没有外键，所以清空之后向量行确实会留下来——这是 Go 的既有行为，照抄。</p>
     */
    public long deleteAll(MemoryScope scope) {
        return repo.itemMapper.deleteAllInScope(scope.tenantId(), scope.subjectId());
    }

    /** 对照 {@code TouchUsed}：{@code use_count} 在 SQL 侧自增。 */
    public void touchUsed(MemoryScope scope, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        repo.itemMapper.touchUsed(scope.tenantId(), scope.subjectId(), ids, OffsetDateTime.now());
    }

    /**
     * 对照 {@code ArchiveLowestRanked}：留下排名最好的 {@code keep} 条，其余归档。
     *
     * <p>排名是"重要度 → 使用时间 → 生效时间"，**没有衰减曲线**——
     * 一条会悄悄埋掉正确记忆的半衰期，比用户能在列表里看见的硬上限更糟。</p>
     */
    public long archiveLowestRanked(MemoryScope scope, int keep) {
        if (keep <= 0) {
            return 0;
        }
        List<String> survivors = repo.itemMapper.selectSurvivorIds(scope.tenantId(), scope.subjectId(),
                MemoryKinds.STATUS_ACTIVE, keep);
        return repo.itemMapper.archiveExcept(scope.tenantId(), scope.subjectId(),
                MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_ARCHIVED, survivors, OffsetDateTime.now());
    }

    /** 对照 {@code ExpireOverdue}：{@code expires_at} 已过的 active 条目归档。 */
    public long expireOverdue(MemoryScope scope) {
        OffsetDateTime now = OffsetDateTime.now();
        return repo.itemMapper.expireOverdue(scope.tenantId(), scope.subjectId(),
                MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_ARCHIVED, now);
    }

    // ── 条目：读 ───────────────────────────────────────────────────────────

    /** 对照 {@code GetItem}：不存在时回 {@code null}。 */
    public MemoryItem getItem(MemoryScope scope, String id) {
        return repo.itemMapper.selectScoped(scope.tenantId(), scope.subjectId(), id);
    }

    /** 对照 {@code ListActiveByKinds}：{@code kinds} 为空时 Go 直接回 {@code nil}。 */
    public List<MemoryItem> listActiveByKinds(MemoryScope scope, List<String> kinds, int limit) {
        if (kinds == null || kinds.isEmpty()) {
            return null;
        }
        return repo.itemMapper.listActiveByKinds(scope.tenantId(), scope.subjectId(), kinds,
                MemoryKinds.STATUS_ACTIVE, OffsetDateTime.now(), limit);
    }

    /**
     * 对照 {@code ListActiveResident}：常驻块由哪些条目构成。
     *
     * <p>稳定特质按 kind 入选；**用户明确要求记住**的按 origin 入选、不问 kind
     * ——他说了"记住这个"，让这件事取决于他之后的问题恰好与它共享词汇，
     * 是让用户失去对这个功能信任最快的方式。</p>
     */
    public List<MemoryItem> listActiveResident(MemoryScope scope, int limit) {
        return repo.itemMapper.listActiveResident(scope.tenantId(), scope.subjectId(),
                MemoryKinds.RESIDENT, MemoryKinds.ORIGIN_EXPLICIT,
                MemoryKinds.STATUS_ACTIVE, OffsetDateTime.now(), limit);
    }

    /**
     * 对照 {@code ListItems}：记忆管理器的分页列表。
     *
     * <p>{@code limit <= 0} 时取 50（Go 的硬编码）；返回值同时带总数与这一页。</p>
     */
    public MemoryPage<MemoryItem> listItems(MemoryScope scope, String status, int limit, int offset) {
        long total = repo.itemMapper.countListItems(scope.tenantId(), scope.subjectId(), status);
        int effectiveLimit = limit <= 0 ? 50 : limit;
        List<MemoryItem> items = repo.itemMapper.listItems(scope.tenantId(), scope.subjectId(), status,
                effectiveLimit, offset);
        return new MemoryPage<>(items, total);
    }

    /**
     * 对照 {@code ListLive}：用户当前**看得到**的某一 kind 的条目
     * ——在用 + 提议中待定。去重必须同时考虑两者，否则确认一条提议会留下重复。
     */
    public List<MemoryItem> listLive(MemoryScope scope, String kind, int limit) {
        return repo.itemMapper.listLive(scope.tenantId(), scope.subjectId(),
                List.of(MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_PENDING),
                kind, OffsetDateTime.now(), limit);
    }

    /**
     * 对照 {@code FindActiveByKey}。
     *
     * <p>{@code pending} 在这里算"活着"：一条等待确认的记忆是用户已经看得到的，
     * 忽略它会让同一个推断每重推一次就在他的待办列表里多堆一份。</p>
     */
    public MemoryItem findActiveByKey(MemoryScope scope, String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isEmpty()) {
            return null;
        }
        return repo.itemMapper.findLiveByKey(scope.tenantId(), scope.subjectId(),
                List.of(MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_PENDING), normalizedKey);
    }

    /** 对照 {@code CountActive}。 */
    public long countActive(MemoryScope scope) {
        return repo.itemMapper.countByStatus(scope.tenantId(), scope.subjectId(), MemoryKinds.STATUS_ACTIVE);
    }

    /**
     * 对照 {@code ItemsMissingEmbeddings}：找出向量积压。
     *
     * <p>在配置 embedding 模型之前写的每一条、模型不可达时写的每一条都没有向量，
     * 而没有向量的记忆对语义召回是**不可见**的。没有这个补扫，
     * 这个功能就只对"打开它之后创建的"记忆有效。</p>
     */
    public List<MemoryItem> itemsMissingEmbeddings(MemoryScope scope, String modelId, int limit) {
        int effectiveLimit = limit <= 0 ? 20 : limit;
        return repo.itemMapper.itemsMissingEmbeddings(scope.tenantId(), scope.subjectId(),
                MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_PENDING, modelId, effectiveLimit);
    }

    // ── 生命周期：SaveItem / ConfirmPendingItem ────────────────────────────

    /**
     * 对照 {@code SaveItem}（memory_lifecycle.go L15-90）：
     * 把"替换"与"确认 / 人工编辑"串行化。一条提议可以替换另一条提议，
     * 但**不能**让一条已生效的事实退休。
     *
     * <p>三处必须照抄的细节：</p>
     * <ol>
     *   <li><b>重放分支</b>：目标已经不在 active/pending 时，若它的
     *       {@code superseded_by} 指向的那条与本次要写的 status+content 完全一致，
     *       说明"上次已经成功应用、只是 checkpoint 失败后被重放"——直接把那一条
     *       复制回 {@code item} 并返回，不要再写一遍。</li>
     *   <li><b>内容完全相同就复用</b>：{@code live} 里有一条 content 与 status 都一样的
     *       ——把已存的整行复制回 {@code item} 返回。</li>
     *   <li><b>pending 的 replaces_id 推导</b>：目标 active → 就是它；
     *       目标 pending → 继承目标的 replaces_id；仍为空 → 取 live 里第一条 active。</li>
     * </ol>
     *
     * <p>{@code item} 是**被就地改写**的（Go 的 {@code *item = …}），
     * 调用方拿到的才是最终落库的那一行。</p>
     */
    public void saveItem(MemoryScope scope, MemoryItem item, String replacesId) {
        repo.tx.withSubject(scope, subject -> {
            long tenantId = scope.tenantId();
            String subjectId = scope.subjectId();

            MemoryItem target = new MemoryItem();
            if (replacesId != null && !replacesId.isEmpty()) {
                MemoryItem found = repo.itemMapper.selectScoped(tenantId, subjectId, replacesId);
                if (found == null) {
                    throw new MemoryConflictException();
                }
                target = found;
                boolean stillReplaceable = MemoryKinds.STATUS_ACTIVE.equals(target.getStatus())
                        || MemoryKinds.STATUS_PENDING.equals(target.getStatus());
                if (!stillReplaceable) {
                    // 一次已经成功应用的决策，可能在 checkpoint 失败后被重放。
                    // 返还它的替换者，而不是写第二遍。
                    if (!target.getSupersededBy().isEmpty()) {
                        MemoryItem replacement =
                                repo.itemMapper.selectScoped(tenantId, subjectId, target.getSupersededBy());
                        if (replacement != null
                                && replacement.getStatus().equals(item.getStatus())
                                && replacement.getContent().equals(item.getContent())) {
                            MemoryIndexStore.copyInto(item, replacement);
                            return null;
                        }
                    }
                    throw new MemoryConflictException();
                }
            }

            List<MemoryItem> live = repo.itemMapper.listByNormalizedKey(tenantId, subjectId,
                    item.getNormalizedKey(),
                    List.of(MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_PENDING));
            for (MemoryItem old : live) {
                if (old.getContent().equals(item.getContent())
                        && old.getStatus().equals(item.getStatus())) {
                    MemoryIndexStore.copyInto(item, old);
                    return null;
                }
            }

            if (MemoryKinds.STATUS_PENDING.equals(item.getStatus())) {
                if (MemoryKinds.STATUS_ACTIVE.equals(target.getStatus())) {
                    item.setReplacesId(target.getId());
                }
                if (MemoryKinds.STATUS_PENDING.equals(target.getStatus())) {
                    item.setReplacesId(target.getReplacesId());
                }
                if (item.getReplacesId().isEmpty()) {
                    for (MemoryItem old : live) {
                        if (MemoryKinds.STATUS_ACTIVE.equals(old.getStatus())) {
                            item.setReplacesId(old.getId());
                            break;
                        }
                    }
                }
            }

            item.setTenantId(scope.tenantId());
            item.setSubjectId(scope.subjectId());
            MemoryIndexStore.applyInsertDefaults(item);
            MemoryIndexStore.stampForCreate(item);
            repo.itemMapper.insert(item);

            List<MemoryItem> supersedeCandidates = new ArrayList<>(live);
            if (!target.getId().isEmpty()) {
                supersedeCandidates.add(target);
            }
            List<String> ids = new ArrayList<>(supersedeCandidates.size() + 1);
            if (!target.getReplacesId().isEmpty()
                    && MemoryKinds.STATUS_ACTIVE.equals(item.getStatus())) {
                ids.add(target.getReplacesId());
            }
            for (MemoryItem old : supersedeCandidates) {
                if (MemoryKinds.STATUS_PENDING.equals(item.getStatus())
                        && MemoryKinds.STATUS_ACTIVE.equals(old.getStatus())) {
                    continue;
                }
                ids.add(old.getId());
            }
            if (ids.isEmpty()) {
                return null;
            }
            repo.itemMapper.supersedeByIds(tenantId, subjectId, item.getId(), ids,
                    List.of(MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_PENDING),
                    MemoryKinds.STATUS_SUPERSEDED, OffsetDateTime.now());
            return null;
        });
    }

    /**
     * 对照 {@code ConfirmPendingItem}（memory_lifecycle.go L92-125）：
     * 原子地把一条提议置为生效、并让它要替换的目标退休。
     *
     * <p>四条分支都要保留：已经是 active → **直接成功返回**（幂等）；
     * 不是 pending、或已经过期 → 冲突；{@code replaces_id} 指向的目标不存在 → 冲突；
     * 目标已经不是 active → 冲突。</p>
     */
    public void confirmPendingItem(MemoryScope scope, String id) {
        repo.tx.withSubject(scope, subject -> {
            long tenantId = scope.tenantId();
            String subjectId = scope.subjectId();

            MemoryItem item = repo.itemMapper.selectScoped(tenantId, subjectId, id);
            if (item == null) {
                throw new MemorySubjectMissingException();
            }
            if (MemoryKinds.STATUS_ACTIVE.equals(item.getStatus())) {
                return null;
            }
            OffsetDateTime now = OffsetDateTime.now();
            boolean expired = item.getExpiresAt() != null && !item.getExpiresAt().isAfter(now);
            if (!MemoryKinds.STATUS_PENDING.equals(item.getStatus()) || expired) {
                throw new MemoryConflictException();
            }
            if (!item.getReplacesId().isEmpty()) {
                MemoryItem target = repo.itemMapper.selectScoped(tenantId, subjectId, item.getReplacesId());
                if (target == null || !MemoryKinds.STATUS_ACTIVE.equals(target.getStatus())) {
                    throw new MemoryConflictException();
                }
            }

            repo.itemMapper.supersedeForConfirm(tenantId, subjectId, id, item.getNormalizedKey(),
                    item.getReplacesId(),
                    List.of(MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_PENDING),
                    MemoryKinds.STATUS_SUPERSEDED, now);
            repo.itemMapper.activateItem(tenantId, subjectId, id, MemoryKinds.STATUS_ACTIVE, now);
            return null;
        });
    }

    // ── 墓碑 ───────────────────────────────────────────────────────────────

    /**
     * 对照 {@code AddTombstone}：记一条"刻意忘掉"，然后做一次修剪。
     *
     * <p>{@code fingerprint} 为空时 Go 直接返回——空指纹是全表冲突，不能插。</p>
     */
    public void addTombstone(MemoryScope scope, String topic, String fingerprint, String sourceMessageId) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return;
        }
        MemoryTombstone tombstone = new MemoryTombstone();
        tombstone.setId(UUID.randomUUID().toString());
        tombstone.setTenantId(scope.tenantId());
        tombstone.setSubjectId(scope.subjectId());
        tombstone.setTopic(topic == null ? "" : topic);
        tombstone.setFingerprint(fingerprint);
        tombstone.setSourceMessageId(sourceMessageId == null ? "" : sourceMessageId);
        tombstone.setCreatedAt(OffsetDateTime.now());

        if (repo.postgres) {
            repo.tombstoneMapper.insertIfAbsentPostgres(tombstone);
        } else {
            repo.tombstoneMapper.insertIfAbsentOther(tombstone);
        }
        trimTombstones(scope);
    }

    /**
     * 对照 {@code trimTombstones}：让这张表保持有界。很久以前的一次拒绝，
     * 没有这张表无上限长大重要。
     *
     * <p>⚠️ 那个 {@code len(keep) < Max} 就返回的判断不能省——它保证"还没到上限时不删"，
     * 而且顺带避开了 {@code id NOT IN ()} 这种非法 SQL。</p>
     */
    private void trimTombstones(MemoryScope scope) {
        List<String> keep = repo.tombstoneMapper.selectNewestIds(scope.tenantId(), scope.subjectId(),
                MemoryKinds.MAX_TOMBSTONES);
        if (keep.size() < MemoryKinds.MAX_TOMBSTONES) {
            return;
        }
        repo.tombstoneMapper.deleteExcept(scope.tenantId(), scope.subjectId(), keep);
    }

    /** 对照 {@code ListTombstones}：最近的拒绝，{@code created_at DESC}。 */
    public List<MemoryTombstone> listTombstones(MemoryScope scope, int limit) {
        return repo.tombstoneMapper.listTombstones(scope.tenantId(), scope.subjectId(), limit);
    }

    /** 对照 {@code HasTombstone}。 */
    public boolean hasTombstone(MemoryScope scope, String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return false;
        }
        return repo.tombstoneMapper.countByFingerprint(scope.tenantId(), scope.subjectId(), fingerprint) > 0;
    }

    /**
     * 对照 {@code HasTombstoneForMessage}。
     *
     * <p>{@code within <= 0} 时不加时间窗（Go 的 {@code if within > 0}）。
     * 窗口是有意义的：这条规则拦的是一个 debounce 之后的重推，不是永久封禁一条消息。</p>
     */
    public boolean hasTombstoneForMessage(MemoryScope scope, String sourceMessageId, Duration within) {
        if (sourceMessageId == null || sourceMessageId.isEmpty()) {
            return false;
        }
        OffsetDateTime cutoff = null;
        if (within != null && !within.isZero() && !within.isNegative()) {
            cutoff = OffsetDateTime.now().minus(within);
        }
        return repo.tombstoneMapper.countBySourceMessage(scope.tenantId(), scope.subjectId(),
                sourceMessageId, cutoff) > 0;
    }
}
