package com.ragagent.memory.mapper;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.memory.domain.MemoryConfig;
import com.ragagent.memory.domain.MemoryConflictException;
import com.ragagent.memory.domain.MemoryDocAffinity;
import com.ragagent.memory.domain.MemoryExtractionBatch;
import com.ragagent.memory.domain.MemoryExtractionFailure;
import com.ragagent.memory.domain.MemoryExtractionLeaseLostException;
import com.ragagent.memory.domain.MemoryExtractionSession;
import com.ragagent.memory.domain.MemoryExtractionState;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryItemEmbedding;
import com.ragagent.memory.domain.MemoryKeys;
import com.ragagent.memory.domain.MemoryKinds;
import com.ragagent.memory.domain.MemoryMessageCursor;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemorySubject;
import com.ragagent.memory.domain.MemorySubjectMissingException;
import com.ragagent.memory.domain.MemoryTombstone;
import com.ragagent.memory.domain.MemoryTopicStat;
import com.ragagent.memory.domain.MemoryVectorHit;
import com.ragagent.memory.domain.MemoryVectorQuery;
import com.ragagent.memory.domain.MemoryVectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 长期记忆的存储契约（对照 Go {@code interfaces.MemoryRepository} +
 * internal/application/repository/memory{,_extraction,_lifecycle,_vector}.go 四个文件）。
 *
 * <h2>为什么是一个类而不是七个</h2>
 * <p>Go 的 {@code MemoryRepository} 是**一个**接口（60 多个方法），service 层只持有一个它。
 * 更重要的是：{@code withSubject} 的事务语义横跨多张表（锁 {@code memory_subjects}、
 * 写 {@code memory_items} / {@code memory_item_embeddings} / {@code memory_extraction_sessions}），
 * 按表拆开会让这些事务散到不同 bean 里。所以这里保持"一个门面 + 七个 Mapper"的形状。</p>
 *
 * <h2>逐条对齐的 Go 语义（读代码前先看这几条）</h2>
 * <ol>
 *   <li><b>scoped 是一切的前提</b>：Go 的每个读写都过
 *       {@code Where("tenant_id = ? AND subject_id = ?")}。Java 侧没有中心化的
 *       {@code scoped()} 帮助方法（那会要求每个查询都拼 wrapper），但每个 Mapper 方法的
 *       SQL 都带齐两列——新增方法时务必照做，漏了就是跨主体泄漏。
 *       <b>提示</b>：这一条也意味着本类**不用** MyBatis-Plus 的
 *       {@code selectById}/{@code deleteById}/{@code updateById}（它们只按主键），
 *       而是一律走带 scope 的显式 SQL。</li>
 *   <li><b>"查不到"一律是 {@code null} 而不是异常</b>：{@code getSubject} / {@code getItem} /
 *       {@code findActiveByKey} / {@code topicByKey} / {@code topicById} /
 *       {@code docAffinityById} 未命中都回 {@code null}
 *       （Go 的 {@code gorm.ErrRecordNotFound} → {@code nil, nil}）。
 *       **例外**是 {@code withSubject} 里的主体行、{@code ConfirmPendingItem} 里的条目行
 *       与 {@code UpdateItemContent} 里的当前行，它们把 not-found 原样上抛。</li>
 *   <li><b>空入参短路</b>：{@code normalizedKey == ""}、{@code fingerprint == ""}、
 *       {@code itemID == ""}、空的 id 列表——Go 全都提前 {@code return nil}
 *       而不是去查一个不可能命中的条件。逐处保留。</li>
 *   <li><b>GORM 的两处隐式行为</b>：
 *       (a) 带**字面量** {@code default:} tag 的字段在 CREATE 时若为零值，
 *       GORM 会用默认值**替换并回写结构体**（{@code callbacks/create.go} L336-341）——
 *       对 {@code memory_items} 就是 {@code importance=0→3}、{@code origin=""→"extracted"}、
 *       {@code status=""→"active"}，对 {@code memory_subjects} 是
 *       {@code enabled=false→true}。{@link #applyInsertDefaults} 复刻这条。
 *       (b) {@code created_at}（AutoCreateTime）**零值才补 now**，而
 *       {@code updated_at}（AutoUpdateTime）**无论传什么都被覆盖成 now**。
 *       {@code stampForCreate} 复刻这条。</li>
 *   <li><b>{@code Updates(map)} 的列集</b>：GORM 只写 map 里有的列，**再加上**
 *       （仅当 map 里没写时才补的）{@code updated_at}。三处"Go 没写但 GORM 补了
 *       updated_at"已经写进对应 Mapper 方法的注释，别按 Go 源码的字面列数去核对。</li>
 *   <li><b>行锁只在 {@code withSubject} 里</b>：所有需要"读-改-写"原子性的方法都走
 *       {@link MemoryTxTemplate}（独立 bean，因此 {@code @Transactional} 真的生效）。
 *       纯单语句的写方法不带事务，与 Go 一致。</li>
 * </ol>
 */
@Component
public class MemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(MemoryRepository.class);

    /** 对照 Go {@code fallbackVectorScanCap}：内存兜底排名的扫描上限。 */
    static final int FALLBACK_VECTOR_SCAN_CAP = 5000;

    /** 对照 Go {@code RecordExtractionFailure} 的 {@code attempts >= 3}。 */
    static final int MAX_EXTRACTION_ATTEMPTS = 3;

    private final MemorySubjectMapper subjectMapper;
    private final MemoryItemMapper itemMapper;
    private final MemoryItemEmbeddingMapper embeddingMapper;
    private final MemoryTombstoneMapper tombstoneMapper;
    private final MemoryTopicStatMapper topicMapper;
    private final MemoryDocAffinityMapper affinityMapper;
    private final MemoryExtractionSessionMapper extractionMapper;
    private final MemoryTxTemplate tx;

    /**
     * 对照 Go 的 {@code r.db.Dialector.Name() == "postgres"}——
     * {@code ON CONFLICT DO NOTHING/DO UPDATE} 在这个方言下可用，H2 上要换成条件插入
     * （与 {@code MessageSuggestionMapper} 同款处置）。
     */
    private final boolean postgres;

    /** 元数据探测用的数据源（对照 GORM 的 {@code Migrator().HasColumn}）。 */
    private final DataSource dataSource;

    /** 对照 Go {@code vectorOnce sync.Once} + {@code vectorColumn}。 */
    private volatile boolean vectorProbed;
    private volatile boolean vectorColumn;

    public MemoryRepository(MemorySubjectMapper subjectMapper,
                            MemoryItemMapper itemMapper,
                            MemoryItemEmbeddingMapper embeddingMapper,
                            MemoryTombstoneMapper tombstoneMapper,
                            MemoryTopicStatMapper topicMapper,
                            MemoryDocAffinityMapper affinityMapper,
                            MemoryExtractionSessionMapper extractionMapper,
                            MemoryTxTemplate tx,
                            DataSource dataSource) {
        this.subjectMapper = subjectMapper;
        this.itemMapper = itemMapper;
        this.embeddingMapper = embeddingMapper;
        this.tombstoneMapper = tombstoneMapper;
        this.topicMapper = topicMapper;
        this.affinityMapper = affinityMapper;
        this.extractionMapper = extractionMapper;
        this.tx = tx;
        this.dataSource = dataSource;
        this.postgres = detectPostgres(dataSource);
    }

    // ── 返回值形状（Go 的多返回值） ────────────────────────────────────────

    /** 一页数据 + 总数（对照 Go 的 {@code ([]T, int64, error)}）。 */
    public record Page<T>(List<T> items, long total) {
    }

    /** {@code EnqueuePendingSession} 的结果（对照 Go 的 {@code (*MemorySubject, bool, error)}）。 */
    public record EnqueueResult(MemorySubject subject, boolean shouldSend) {
    }

    // ── 主体 ───────────────────────────────────────────────────────────────

    /** 对照 {@code GetSubject}：不存在时回 {@code null}（Go 的 {@code nil, nil}）。 */
    public MemorySubject getSubject(MemoryScope scope) {
        return subjectMapper.selectByScope(scope.tenantId(), scope.subjectId());
    }

    /**
     * 对照 {@code EnsureSubject}：首次使用时创建。
     *
     * <p>"DoNothing + 重读"让并发的第一次对话不会撞进唯一键冲突；
     * 行已存在时那次插入是空操作。</p>
     *
     * <p>{@code enabled} 显式置 true：字段默认值是 Go 的零值 false，
     * 而 GORM 在 CREATE 时会把零值替换成 {@code default:true} 并回写结构体——
     * 两条路殊途同归。</p>
     */
    public MemorySubject ensureSubject(MemoryScope scope) {
        MemorySubject subject = new MemorySubject();
        subject.setId(UUID.randomUUID().toString());
        subject.setTenantId(scope.tenantId());
        subject.setSubjectId(scope.subjectId());
        subject.setEnabled(true);
        subject.setPendingSessions(new ArrayList<>());
        subject.setExtractionState(new MemoryExtractionState());
        stampForCreate(subject);

        if (postgres) {
            subjectMapper.insertIfAbsentPostgres(subject);
        } else {
            subjectMapper.insertIfAbsentOther(subject);
        }

        MemorySubject existing = getSubject(scope);
        if (existing == null) {
            throw new IllegalStateException("memory subject vanished after upsert");
        }
        return existing;
    }

    /** 对照 {@code UpdateSubjectEnabled}：先确保主体存在，再翻那一列。 */
    public void updateSubjectEnabled(MemoryScope scope, boolean enabled) {
        ensureSubject(scope);
        subjectMapper.updateEnabled(scope.tenantId(), scope.subjectId(), enabled, OffsetDateTime.now());
    }

    /** 对照 {@code UpdateSubjectBlock}：写渲染好的常驻块与条目数。 */
    public void updateSubjectBlock(MemoryScope scope, String block, int itemCount) {
        subjectMapper.updateBlock(scope.tenantId(), scope.subjectId(), block, itemCount, OffsetDateTime.now());
    }

    /** 对照 {@code MarkConsolidated}。 */
    public void markConsolidated(MemoryScope scope) {
        OffsetDateTime now = OffsetDateTime.now();
        subjectMapper.markConsolidated(scope.tenantId(), scope.subjectId(), now);
    }

    /** 对照 {@code MarkForcedConsolidated}：与每日任务**互不影响**的另一只钟。 */
    public void markForcedConsolidated(MemoryScope scope) {
        OffsetDateTime now = OffsetDateTime.now();
        subjectMapper.markForcedConsolidated(scope.tenantId(), scope.subjectId(), now);
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
        applyInsertDefaults(item);
        stampForCreate(item);
        itemMapper.insert(item);
    }

    /** 对照 {@code UpdateItemContent}：内容变了才删向量、作废提议，最后无条件覆盖五列。 */
    public void updateItemContent(MemoryScope scope, String id, String content,
                                  String normalizedKey, int importance) {
        tx.withSubject(scope, subject -> {
            MemoryItem current = itemMapper.selectScoped(scope.tenantId(), scope.subjectId(), id);
            if (current == null) {
                // 对照 Go 的 First 未命中 → gorm.ErrRecordNotFound 上抛
                throw new MemorySubjectMissingException();
            }
            if (!current.getContent().equals(content)) {
                embeddingMapper.deleteByItemId(scope.tenantId(), scope.subjectId(), id);
                // 编辑一条已确认的事实，会让基于它旧措辞的提议失效。
                itemMapper.supersedeProposalsOf(scope.tenantId(), scope.subjectId(), id,
                        MemoryKinds.STATUS_PENDING, MemoryKinds.STATUS_SUPERSEDED, OffsetDateTime.now());
            }
            itemMapper.updateItemContent(scope.tenantId(), scope.subjectId(), id, content,
                    normalizedKey, importance, MemoryKinds.ORIGIN_MANUAL, OffsetDateTime.now());
            return null;
        });
    }

    /** 对照 {@code SupersedeItem}。 */
    public void supersedeItem(MemoryScope scope, String id, String supersededBy) {
        tx.withSubject(scope, subject -> {
            itemMapper.supersedeItem(scope.tenantId(), scope.subjectId(), id, supersededBy,
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
        tx.withSubject(scope, subject -> {
            itemMapper.supersedePendingReplacements(scope.tenantId(), scope.subjectId(), id,
                    MemoryKinds.STATUS_PENDING, MemoryKinds.STATUS_SUPERSEDED, OffsetDateTime.now());
            embeddingMapper.deleteByItemId(scope.tenantId(), scope.subjectId(), id);
            itemMapper.deleteScoped(scope.tenantId(), scope.subjectId(), id);
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
        return itemMapper.deleteAllInScope(scope.tenantId(), scope.subjectId());
    }

    /** 对照 {@code TouchUsed}：{@code use_count} 在 SQL 侧自增。 */
    public void touchUsed(MemoryScope scope, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        itemMapper.touchUsed(scope.tenantId(), scope.subjectId(), ids, OffsetDateTime.now());
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
        List<String> survivors = itemMapper.selectSurvivorIds(scope.tenantId(), scope.subjectId(),
                MemoryKinds.STATUS_ACTIVE, keep);
        return itemMapper.archiveExcept(scope.tenantId(), scope.subjectId(),
                MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_ARCHIVED, survivors, OffsetDateTime.now());
    }

    /** 对照 {@code ExpireOverdue}：{@code expires_at} 已过的 active 条目归档。 */
    public long expireOverdue(MemoryScope scope) {
        OffsetDateTime now = OffsetDateTime.now();
        return itemMapper.expireOverdue(scope.tenantId(), scope.subjectId(),
                MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_ARCHIVED, now);
    }

    // ── 条目：读 ───────────────────────────────────────────────────────────

    /** 对照 {@code GetItem}：不存在时回 {@code null}。 */
    public MemoryItem getItem(MemoryScope scope, String id) {
        return itemMapper.selectScoped(scope.tenantId(), scope.subjectId(), id);
    }

    /** 对照 {@code ListActiveByKinds}：{@code kinds} 为空时 Go 直接回 {@code nil}。 */
    public List<MemoryItem> listActiveByKinds(MemoryScope scope, List<String> kinds, int limit) {
        if (kinds == null || kinds.isEmpty()) {
            return null;
        }
        return itemMapper.listActiveByKinds(scope.tenantId(), scope.subjectId(), kinds,
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
        return itemMapper.listActiveResident(scope.tenantId(), scope.subjectId(),
                MemoryKinds.RESIDENT, MemoryKinds.ORIGIN_EXPLICIT,
                MemoryKinds.STATUS_ACTIVE, OffsetDateTime.now(), limit);
    }

    /**
     * 对照 {@code ListItems}：记忆管理器的分页列表。
     *
     * <p>{@code limit <= 0} 时取 50（Go 的硬编码）；返回值同时带总数与这一页。</p>
     */
    public Page<MemoryItem> listItems(MemoryScope scope, String status, int limit, int offset) {
        long total = itemMapper.countListItems(scope.tenantId(), scope.subjectId(), status);
        int effectiveLimit = limit <= 0 ? 50 : limit;
        List<MemoryItem> items = itemMapper.listItems(scope.tenantId(), scope.subjectId(), status,
                effectiveLimit, offset);
        return new Page<>(items, total);
    }

    /**
     * 对照 {@code ListLive}：用户当前**看得到**的某一 kind 的条目
     * ——在用 + 提议中待定。去重必须同时考虑两者，否则确认一条提议会留下重复。
     */
    public List<MemoryItem> listLive(MemoryScope scope, String kind, int limit) {
        return itemMapper.listLive(scope.tenantId(), scope.subjectId(),
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
        return itemMapper.findLiveByKey(scope.tenantId(), scope.subjectId(),
                List.of(MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_PENDING), normalizedKey);
    }

    /** 对照 {@code CountActive}。 */
    public long countActive(MemoryScope scope) {
        return itemMapper.countByStatus(scope.tenantId(), scope.subjectId(), MemoryKinds.STATUS_ACTIVE);
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
        return itemMapper.itemsMissingEmbeddings(scope.tenantId(), scope.subjectId(),
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
        tx.withSubject(scope, subject -> {
            long tenantId = scope.tenantId();
            String subjectId = scope.subjectId();

            MemoryItem target = new MemoryItem();
            if (replacesId != null && !replacesId.isEmpty()) {
                MemoryItem found = itemMapper.selectScoped(tenantId, subjectId, replacesId);
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
                                itemMapper.selectScoped(tenantId, subjectId, target.getSupersededBy());
                        if (replacement != null
                                && replacement.getStatus().equals(item.getStatus())
                                && replacement.getContent().equals(item.getContent())) {
                            copyInto(item, replacement);
                            return null;
                        }
                    }
                    throw new MemoryConflictException();
                }
            }

            List<MemoryItem> live = itemMapper.listByNormalizedKey(tenantId, subjectId,
                    item.getNormalizedKey(),
                    List.of(MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_PENDING));
            for (MemoryItem old : live) {
                if (old.getContent().equals(item.getContent())
                        && old.getStatus().equals(item.getStatus())) {
                    copyInto(item, old);
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
            applyInsertDefaults(item);
            stampForCreate(item);
            itemMapper.insert(item);

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
            itemMapper.supersedeByIds(tenantId, subjectId, item.getId(), ids,
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
        tx.withSubject(scope, subject -> {
            long tenantId = scope.tenantId();
            String subjectId = scope.subjectId();

            MemoryItem item = itemMapper.selectScoped(tenantId, subjectId, id);
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
                MemoryItem target = itemMapper.selectScoped(tenantId, subjectId, item.getReplacesId());
                if (target == null || !MemoryKinds.STATUS_ACTIVE.equals(target.getStatus())) {
                    throw new MemoryConflictException();
                }
            }

            itemMapper.supersedeForConfirm(tenantId, subjectId, id, item.getNormalizedKey(),
                    item.getReplacesId(),
                    List.of(MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_PENDING),
                    MemoryKinds.STATUS_SUPERSEDED, now);
            itemMapper.activateItem(tenantId, subjectId, id, MemoryKinds.STATUS_ACTIVE, now);
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

        if (postgres) {
            tombstoneMapper.insertIfAbsentPostgres(tombstone);
        } else {
            tombstoneMapper.insertIfAbsentOther(tombstone);
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
        List<String> keep = tombstoneMapper.selectNewestIds(scope.tenantId(), scope.subjectId(),
                MemoryKinds.MAX_TOMBSTONES);
        if (keep.size() < MemoryKinds.MAX_TOMBSTONES) {
            return;
        }
        tombstoneMapper.deleteExcept(scope.tenantId(), scope.subjectId(), keep);
    }

    /** 对照 {@code ListTombstones}：最近的拒绝，{@code created_at DESC}。 */
    public List<MemoryTombstone> listTombstones(MemoryScope scope, int limit) {
        return tombstoneMapper.listTombstones(scope.tenantId(), scope.subjectId(), limit);
    }

    /** 对照 {@code HasTombstone}。 */
    public boolean hasTombstone(MemoryScope scope, String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return false;
        }
        return tombstoneMapper.countByFingerprint(scope.tenantId(), scope.subjectId(), fingerprint) > 0;
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
        return tombstoneMapper.countBySourceMessage(scope.tenantId(), scope.subjectId(),
                sourceMessageId, cutoff) > 0;
    }

    // ── 话题统计 ───────────────────────────────────────────────────────────

    /**
     * 对照 {@code BumpTopic}：计数一次，并返回累计值。
     *
     * <p>插入-再自增的形状让两个并发轮次不会都认为这个话题是新的。
     * 别名记录在最后：**只在这条说法还没被记过、且它归一化后不等于 key 本身**时才追加，
     * 超过 12 条就只留最近的 12 条。</p>
     */
    public MemoryTopicStat bumpTopic(MemoryScope scope, String topic, String normalizedKey, String alias) {
        if (normalizedKey == null || normalizedKey.isEmpty()) {
            return null;
        }
        OffsetDateTime now = OffsetDateTime.now();
        MemoryTopicStat stat = new MemoryTopicStat();
        stat.setId(UUID.randomUUID().toString());
        stat.setTenantId(scope.tenantId());
        stat.setSubjectId(scope.subjectId());
        stat.setNormalizedKey(normalizedKey);
        stat.setTopic(topic == null ? "" : topic);
        stat.setHits(0);
        stat.setLastSeenAt(now);
        stat.setAliases(new ArrayList<>());
        stat.setCreatedAt(now);
        stat.setUpdatedAt(now);

        if (postgres) {
            topicMapper.insertIfAbsentPostgres(stat);
        } else {
            topicMapper.insertIfAbsentOther(stat);
        }
        topicMapper.bumpHits(scope.tenantId(), scope.subjectId(), normalizedKey, now);

        MemoryTopicStat updated = topicMapper.selectByKey(scope.tenantId(), scope.subjectId(), normalizedKey);
        if (updated == null) {
            throw new IllegalStateException("memory topic vanished after upsert");
        }

        // 记下这次到达时的措辞，好让同一种说法下次走精确匹配、不必再裁决一遍。
        if (alias != null && !alias.isEmpty()
                && !updated.hasAlias(alias)
                && !MemoryKeys.normalizeTopicKey(alias).equals(updated.getNormalizedKey())) {
            List<String> aliases = new ArrayList<>(
                    updated.getAliases() == null ? List.of() : updated.getAliases());
            aliases.add(alias);
            if (aliases.size() > 12) {
                aliases = new ArrayList<>(aliases.subList(aliases.size() - 12, aliases.size()));
            }
            topicMapper.updateAliases(scope.tenantId(), scope.subjectId(), normalizedKey, aliases, now);
            updated.setAliases(aliases);
        }
        return updated;
    }

    /**
     * 对照 {@code RenameTopic}：给一个主题换上更好的规范标签。
     *
     * <p>旧标签变成别名而不是被丢掉：之前的每一次统计都记在它名下，
     * 丢掉它会让那种措辞的下一次出现看起来像个全新主题。
     * 新 key 已经被别的行占用时返回 {@code false}——把两行合并是另一件风险不同的事，
     * 当成重命名的副作用来做会丢计数。</p>
     *
     * @return {@code true} = 真的改了名
     */
    public boolean renameTopic(MemoryScope scope, String oldKey, String newKey, String newLabel) {
        if (oldKey == null || newKey == null || oldKey.isEmpty() || newKey.isEmpty()
                || oldKey.equals(newKey)) {
            return false;
        }
        if (topicMapper.countByKey(scope.tenantId(), scope.subjectId(), newKey) > 0) {
            return false;
        }
        MemoryTopicStat current = topicMapper.selectByKey(scope.tenantId(), scope.subjectId(), oldKey);
        if (current == null) {
            return false;
        }

        List<String> aliases = new ArrayList<>();
        if (current.getAliases() != null) {
            for (String alias : current.getAliases()) {
                // 换名之前，那个"要采纳的新说法"已经被记成别名了。
                // 留着它会把规范标签列成它自己的别名。
                if (MemoryKeys.normalizeTopicKey(alias).equals(newKey)) {
                    continue;
                }
                aliases.add(alias);
            }
        }
        if (!current.getTopic().isEmpty() && !hasAlias(aliases, current.getTopic())) {
            aliases.add(current.getTopic());
        }
        if (aliases.size() > 12) {
            aliases = new ArrayList<>(aliases.subList(aliases.size() - 12, aliases.size()));
        }

        topicMapper.rename(scope.tenantId(), scope.subjectId(), oldKey, newKey, newLabel, aliases,
                OffsetDateTime.now());
        return true;
    }

    /** 对照 Go {@code MemoryTopicAliases.Has}（作用在裸列表上，投影前的形态）。 */
    private static boolean hasAlias(List<String> aliases, String surface) {
        String target = MemoryKeys.normalizeTopicKey(surface);
        if (target.isEmpty()) {
            return false;
        }
        for (String alias : aliases) {
            if (target.equals(MemoryKeys.normalizeTopicKey(alias))) {
                return true;
            }
        }
        return false;
    }

    /** 对照 {@code MarkTopicPromoted}：别再提升它第二次。 */
    public void markTopicPromoted(MemoryScope scope, String normalizedKey) {
        topicMapper.markPromoted(scope.tenantId(), scope.subjectId(), normalizedKey, OffsetDateTime.now());
    }

    /** 对照 {@code TopicByKey}：不存在时回 {@code null}。 */
    public MemoryTopicStat topicByKey(MemoryScope scope, String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isEmpty()) {
            return null;
        }
        return topicMapper.selectByKey(scope.tenantId(), scope.subjectId(), normalizedKey);
    }

    /** 对照 {@code TopicByID}：记忆管理器用来提升/丢弃一个还没变成记忆的主题。 */
    public MemoryTopicStat topicById(MemoryScope scope, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return topicMapper.selectScopedById(scope.tenantId(), scope.subjectId(), id);
    }

    /** 对照 {@code TopTopics}：{@code hits DESC, last_seen_at DESC}。 */
    public List<MemoryTopicStat> topTopics(MemoryScope scope, int limit) {
        return topicMapper.topTopics(scope.tenantId(), scope.subjectId(), limit);
    }

    /** 对照 {@code ListUnpromotedTopics}：已计数、尚未变成兴趣的主题。 */
    public Page<MemoryTopicStat> listUnpromotedTopics(MemoryScope scope, int limit, int offset) {
        long total = topicMapper.countUnpromoted(scope.tenantId(), scope.subjectId());
        int effectiveLimit = limit <= 0 ? 50 : limit;
        return new Page<>(topicMapper.listUnpromoted(scope.tenantId(), scope.subjectId(),
                effectiveLimit, offset), total);
    }

    /** 对照 {@code DeleteTopic}：删掉一条计数，之后若再被问到就从零开始。 */
    public void deleteTopic(MemoryScope scope, String id) {
        topicMapper.deleteScoped(scope.tenantId(), scope.subjectId(), id);
    }

    /**
     * 对照 {@code DeleteAllTopics}：清空记忆必须包含这些计数器。
     *
     * <p>否则一个停在 N−1 次的主题会在用户要求"清空一切"之后的**下一个问题**上被提升。</p>
     */
    public void deleteAllTopics(MemoryScope scope) {
        topicMapper.deleteAllInScope(scope.tenantId(), scope.subjectId());
    }

    // ── 文档亲和 ───────────────────────────────────────────────────────────

    /**
     * 对照 {@code BumpDocAffinity}：逐条"先插后自增"。
     *
     * <p>{@code knowledge_id} 为空的条目跳过；{@code title} 与
     * {@code knowledge_base_id} **只在非空时才覆盖**——一次没带标题的引用
     * 不该把已有标题冲成空串。</p>
     */
    public void bumpDocAffinity(MemoryScope scope, List<MemoryDocAffinity> docs) {
        if (docs == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (MemoryDocAffinity doc : docs) {
            if (doc.getKnowledgeId().isEmpty()) {
                continue;
            }
            MemoryDocAffinity row = new MemoryDocAffinity();
            row.setId(UUID.randomUUID().toString());
            row.setTenantId(scope.tenantId());
            row.setSubjectId(scope.subjectId());
            row.setKnowledgeId(doc.getKnowledgeId());
            row.setKnowledgeBaseId(doc.getKnowledgeBaseId());
            row.setTitle(doc.getTitle());
            row.setHits(0);
            row.setLastUsedAt(now);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);

            if (postgres) {
                affinityMapper.insertIfAbsentPostgres(row);
            } else {
                affinityMapper.insertIfAbsentOther(row);
            }
            affinityMapper.bump(scope.tenantId(), scope.subjectId(), doc.getKnowledgeId(),
                    doc.getTitle(), doc.getKnowledgeBaseId(), now);
        }
    }

    /** 对照 {@code DocAffinity}：交给调用方的是一个 {@code knowledgeId → hits} 的映射。 */
    public Map<String, Integer> docAffinity(MemoryScope scope, List<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return null;
        }
        List<MemoryDocAffinity> rows = affinityMapper.selectByKnowledgeIds(scope.tenantId(),
                scope.subjectId(), knowledgeIds);
        Map<String, Integer> affinity = new HashMap<>();
        for (MemoryDocAffinity row : rows) {
            affinity.put(row.getKnowledgeId(), row.getHits());
        }
        return affinity;
    }

    /** 对照 {@code TopDocAffinity}。 */
    public List<MemoryDocAffinity> topDocAffinity(MemoryScope scope, int limit) {
        return affinityMapper.topAffinity(scope.tenantId(), scope.subjectId(), limit);
    }

    /** 对照 {@code DocAffinityByID}：不存在时回 {@code null}。 */
    public MemoryDocAffinity docAffinityById(MemoryScope scope, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return affinityMapper.selectScopedById(scope.tenantId(), scope.subjectId(), id);
    }

    /**
     * 对照 {@code ListFamiliarDocs}：{@code minHits < 1} 时回落到
     * {@code MemoryDocAffinityMinHits}（= 2，一次引用是噪声、两次才是模式）。
     */
    public Page<MemoryDocAffinity> listFamiliarDocs(MemoryScope scope, int minHits, int limit, int offset) {
        int effectiveMinHits = minHits < 1 ? MemoryConfig.MEMORY_DOC_AFFINITY_MIN_HITS : minHits;
        long total = affinityMapper.countFamiliar(scope.tenantId(), scope.subjectId(), effectiveMinHits);
        int effectiveLimit = limit <= 0 ? 50 : limit;
        return new Page<>(affinityMapper.listFamiliar(scope.tenantId(), scope.subjectId(),
                effectiveMinHits, effectiveLimit, offset), total);
    }

    /** 对照 {@code DeleteDocAffinity}。 */
    public void deleteDocAffinity(MemoryScope scope, String id) {
        affinityMapper.deleteScoped(scope.tenantId(), scope.subjectId(), id);
    }

    /** 对照 {@code DeleteAllDocAffinity}。 */
    public void deleteAllDocAffinity(MemoryScope scope) {
        affinityMapper.deleteAllInScope(scope.tenantId(), scope.subjectId());
    }

    // ── 向量 ───────────────────────────────────────────────────────────────

    /**
     * 对照 {@code UpsertItemEmbedding}：写向量，并在同一个事务里把它同步进
     * 数据库自己的 vector 列。
     *
     * <p>三项前置短路（Go 也是）：{@code embedding == null}、{@code itemID == ""}、
     * {@code len(vector) == 0}。</p>
     *
     * <p><b>输入快照</b>：{@code source_content} 非空时会先核对条目现在的
     * content/topic 是否仍是当初输入的那份——不是就**放弃写入**，
     * 免得一个慢的 embedding 调用覆盖掉更新的编辑。</p>
     */
    public void upsertItemEmbedding(MemoryScope scope, MemoryItemEmbedding embedding) {
        if (embedding == null || embedding.getItemId().isEmpty()
                || embedding.getVector() == null || embedding.getVector().length == 0) {
            return;
        }
        embedding.setTenantId(scope.tenantId());
        embedding.setSubjectId(scope.subjectId());
        OffsetDateTime now = OffsetDateTime.now();
        embedding.setUpdatedAt(now);
        if (GoTimeSerializer.isGoZero(embedding.getCreatedAt())) {
            embedding.setCreatedAt(now);
        }

        tx.withSubject(scope, subject -> {
            if (!embedding.getSourceContent().isEmpty()) {
                MemoryItem current = itemMapper.selectScoped(scope.tenantId(), scope.subjectId(),
                        embedding.getItemId());
                if (current == null) {
                    return null;
                }
                if (!current.getContent().equals(embedding.getSourceContent())
                        || !current.getTopic().equals(embedding.getSourceTopic())) {
                    return null;
                }
            }
            if (postgres) {
                embeddingMapper.upsertPostgres(embedding);
            } else {
                // H2 走"先删后插"。为对齐 PG 的 DO UPDATE **不重置 created_at**
                // （它的列集里没有 created_at），把已存在的那行的值读出来带上。
                MemoryItemEmbedding existing =
                        embeddingMapper.selectByItemId(embedding.getItemId());
                if (existing != null) {
                    embedding.setCreatedAt(existing.getCreatedAt());
                }
                embeddingMapper.deleteByItemId(scope.tenantId(), scope.subjectId(), embedding.getItemId());
                embeddingMapper.insertRow(embedding);
            }
            // 上面那个 blob 是每个部署都会读的；这一份是同一个向量的"数据库能排序"的形态。
            // 同一事务里写，所以一行永远不会以"可搜索但向量是旧的"状态存在。
            writeVectorColumn(embedding.getItemId(), embedding.getVector());
            return null;
        });
    }

    /**
     * 对照 {@code DeleteItemEmbedding}：删掉一条记忆的向量，好让补扫重建它。
     *
     * <p>一条记忆该嵌入什么，在写下之后是可能变的（一条兴趣嵌入的是
     * 它主题的其它说法），删掉向量就是"请既有补扫重建"的表达方式。</p>
     */
    public void deleteItemEmbedding(MemoryScope scope, String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return;
        }
        embeddingMapper.deleteByItemId(scope.tenantId(), scope.subjectId(), itemId);
    }

    /**
     * 对照 {@code ItemEmbeddings}：按 item id 取向量。
     *
     * <p>只返回 {@code modelId} 产出的向量——不同模型的向量不可比，
     * 混在一起算出来的就是胡说。</p>
     *
     * <p>⚠️ 解码后长度为 0 的行被**跳过**（Go 的 {@code if len(vector) > 0}），
     * 不会以空数组的形式出现在返回值里。</p>
     */
    public Map<String, float[]> itemEmbeddings(MemoryScope scope, List<String> itemIds, String modelId) {
        if (itemIds == null || itemIds.isEmpty() || modelId == null || modelId.isEmpty()) {
            return null;
        }
        List<MemoryItemEmbedding> rows = embeddingMapper.selectByItemIds(scope.tenantId(),
                scope.subjectId(), itemIds, modelId);
        Map<String, float[]> vectors = new HashMap<>();
        for (MemoryItemEmbedding row : rows) {
            float[] vector = MemoryVectors.decodeEmbedding(row.getVector());
            if (vector != null && vector.length > 0) {
                vectors.put(row.getItemId(), vector);
            }
        }
        return vectors;
    }

    /**
     * 对照 {@code SearchItemsByVector}（memory_vector.go L70-126）：
     * 把一个主体**全部**向量对着一次查询排序。
     *
     * <p>候选集是这个主体拥有的每一个向量，不是其中一个窗口。这个区别是关键：
     * 上一版实现按重要度列出条目、再给列出来的那些打分——于是相关性只能重排
     * 重要度已经选好的东西，窗口之外一条匹配的记忆根本够不到。</p>
     *
     * <p>条目是**另外查一次**而不是 join 进排名查询：这样两条路径都不必复刻
     * {@code memory_items} 的列清单，排名查询也窄到能一眼读完。</p>
     */
    public List<MemoryVectorHit> searchItemsByVector(MemoryScope scope, MemoryVectorQuery query) {
        if (!scope.valid() || query.unusable()) {
            return null;
        }
        int limit = query.effectiveLimit();

        List<VectorHitRow> rows = vectorColumnReady()
                ? rankInDatabase(scope, query, limit)
                : rankInProcess(scope, query, limit);
        if (rows == null || rows.isEmpty()) {
            return null;
        }

        List<String> ids = new ArrayList<>();
        for (VectorHitRow row : rows) {
            if (row.getScore() >= query.minScore()) {
                ids.add(row.getItemId());
            }
        }
        if (ids.isEmpty()) {
            return null;
        }

        List<MemoryItem> items = itemMapper.selectByIds(scope.tenantId(), scope.subjectId(), ids);
        Map<String, MemoryItem> byId = new HashMap<>();
        for (MemoryItem item : items) {
            byId.put(item.getId(), item);
        }

        List<MemoryVectorHit> hits = new ArrayList<>(ids.size());
        for (VectorHitRow row : rows) {
            MemoryItem item = byId.get(row.getItemId());
            if (item == null) {
                continue;
            }
            hits.add(new MemoryVectorHit(item, row.getScore()));
        }
        return hits;
    }

    /**
     * 对照 {@code SyncVectorColumn}：把早于迁移 000095 写下的行搬进数据库的 vector 类型。
     *
     * <p>一条 SQL 排名看不见的行，就是一条语义召回找不到的记忆——所以这件事必须自己排干，
     * 不能等下次调用 embedding 模型。**没有任何模型调用**：向量已经存在，
     * 落后的只是它的表示形式。</p>
     *
     * <p>{@code writeVectorColumn} 失败时**只记日志并继续**（Go 也是这样），
     * 所以返回的 moved 可能小于实际扫描到的行数。</p>
     */
    public int syncVectorColumn(MemoryScope scope, int limit) {
        if (!vectorColumnReady() || !scope.valid()) {
            return 0;
        }
        int effectiveLimit = limit <= 0 ? 500 : limit;
        List<MemoryItemEmbedding> rows = embeddingMapper.selectPendingVectorColumn(
                scope.tenantId(), scope.subjectId(), effectiveLimit);
        int moved = 0;
        for (MemoryItemEmbedding row : rows) {
            try {
                writeVectorColumn(row.getItemId(), row.getVector());
            } catch (RuntimeException e) {
                log.warn("memory: sync vector column failed for {}: {}", row.getItemId(), e.getMessage());
                continue;
            }
            moved++;
        }
        return moved;
    }

    // ── 抽取进度（memory_extraction.go） ───────────────────────────────────

    /** 对照 {@code HasPendingExtraction}。 */
    public boolean hasPendingExtraction(MemoryScope scope) {
        return extractionMapper.countPendingProbe(scope.tenantId(), scope.subjectId()) > 0;
    }

    /**
     * 对照 {@code EnqueuePendingSession}（L81-108）：记下"这个会话有越过游标的轮次"，
     * 并在没有运行中任务时抢下"在途"槽位。
     *
     * @return 更新**之前**的主体快照 + 这次调用是否该负责投递任务。
     *         一串连发的轮次因此产出恰好一个任务，且**绝不会丢掉某一轮**。
     */
    public EnqueueResult enqueuePendingSession(MemoryScope scope, String sessionId, Duration timeout) {
        boolean[] shouldSend = {false};
        MemorySubject snapshot = tx.withSubject(scope, subject -> {
            MemorySubject before = subject.copy();
            importLegacySessions(scope, subject);
            enqueueExtractionSession(scope, subject, sessionId, true);

            OffsetDateTime now = OffsetDateTime.now();
            boolean running = !subject.getExtractionState().getLeaseId().isEmpty()
                    && subject.getExtractionState().leaseUntilAfter(now);
            boolean queued = subject.getExtractScheduledAt() != null
                    && Duration.between(subject.getExtractScheduledAt(), now).compareTo(timeout) < 0;
            if (!running && !queued && hasPendingExtraction(scope)) {
                subject.setExtractScheduledAt(now);
                shouldSend[0] = true;
            }
            saveExtractionState(scope, subject, now);
            return before;
        });
        return new EnqueueResult(snapshot, shouldSend[0]);
    }

    /**
     * 对照 {@code ClaimPendingSessions}（L110-140）：租下一个快照，但**不移除**持久工作。
     *
     * @return {@code null} 表示没有剩活了；{@code retryAt} 非零表示租约正忙、请延后；
     *         {@code sessions} 非空表示这批已被本 worker 租下
     */
    public MemoryExtractionBatch claimPendingSessions(MemoryScope scope, String fallbackSession,
                                                      String leaseId, Duration ttl) {
        return tx.withSubject(scope, subject -> {
            OffsetDateTime now = OffsetDateTime.now();
            if (!subject.getExtractionState().getLeaseId().isEmpty()
                    && subject.getExtractionState().leaseUntilAfter(now)) {
                return MemoryExtractionBatch.retryAt(subject.getExtractionState().getLeaseUntil());
            }
            importLegacySessions(scope, subject);
            // 遗留负载只引导一次；重复调用不能把一个已经排干的行重新激活。
            enqueueExtractionSession(scope, subject, fallbackSession, false);

            List<MemoryExtractionSession> sessions = extractionMapper.listPending(
                    scope.tenantId(), scope.subjectId(), MemoryKinds.MAX_PENDING_SESSIONS);
            if (sessions.isEmpty()) {
                saveExtractionState(scope, subject, now);
                return null;
            }
            subject.getExtractionState().setLeaseId(leaseId);
            subject.getExtractionState().setLeaseUntil(now.plus(ttl));
            saveExtractionState(scope, subject, now);
            return MemoryExtractionBatch.of(sessions);
        });
    }

    /**
     * 对照 {@code CheckpointExtraction}（L146-171）：确认一个已处理的片段
     * （或一次记录在案的跳过）。并发的 enqueue 会改 {@code revision} 并让该会话保持 pending。
     *
     * <p>{@code failed_at} 为空时才重置失败计数——也就是"这次不是失败后的重试"。</p>
     */
    public void checkpointExtraction(MemoryScope scope, String leaseId, MemoryExtractionSession session,
                                     MemoryMessageCursor cursor, boolean drained) {
        tx.withSubject(scope, subject -> {
            if (!validExtractionLease(subject, leaseId)) {
                throw new MemoryExtractionLeaseLostException();
            }
            MemoryExtractionSession progress = extractionMapper.selectBySessionId(
                    scope.tenantId(), scope.subjectId(), session.getSessionId());
            if (progress == null) {
                throw new MemorySubjectMissingException();
            }
            MemoryMessageCursor effective = cursor.after(progress.getCursor())
                    ? cursor : progress.getCursor();
            // 只更新这一行即可转动未完成的工作，不必重写主体的整段历史。
            // 已完成的游标仍然是一条小的、有索引的记录。
            boolean pending = !drained || progress.getRevision() != session.getRevision();
            extractionMapper.checkpoint(scope.tenantId(), scope.subjectId(), session.getSessionId(),
                    effective.getAt(), effective.getId(), pending,
                    progress.getFailedAt() == null, OffsetDateTime.now());
            return null;
        });
    }

    /**
     * 对照 {@code RecordExtractionFailure}（L173-203）：超过有界的
     * "输出不合法"重试预算后返回 {@code true}。它保存失败区间，但**不存对话原文**。
     *
     * <p>重试次数只在"失败区间起点就是当前游标"时才累加——也就是说，
     * 只有**卡在同一个地方**反复失败才算数。</p>
     *
     * @return {@code true} = 该跳过这个会话了（已经试满 3 次）
     */
    public boolean recordExtractionFailure(MemoryScope scope, String leaseId,
                                           MemoryExtractionFailure failure) {
        boolean[] skip = {false};
        tx.withSubject(scope, subject -> {
            if (!validExtractionLease(subject, leaseId)) {
                throw new MemoryExtractionLeaseLostException();
            }
            String sessionId = failure.session().getSessionId();
            MemoryExtractionSession progress = extractionMapper.selectBySessionId(
                    scope.tenantId(), scope.subjectId(), sessionId);
            if (progress == null) {
                throw new MemorySubjectMissingException();
            }
            int attempts = 1;
            if (progress.failedFromEqualsCursor()) {
                attempts += progress.getFailureCount();
            }
            skip[0] = attempts >= MAX_EXTRACTION_ATTEMPTS;
            OffsetDateTime now = OffsetDateTime.now();
            extractionMapper.recordFailure(scope.tenantId(), scope.subjectId(), sessionId,
                    attempts, failure.code(), skip[0] ? now : null,
                    progress.getCursorAt(), progress.getCursorId(),
                    failure.end().getAt(), failure.end().getId(), now);
            return null;
        });
        return skip[0];
    }

    /**
     * 对照 {@code FinishExtraction}（L205-218）：清掉租约、清掉在途标记、
     * 并记下"这个主体刚抽过"。
     *
     * <p>⚠️ 这里的租约判定**只看 LeaseID、不看是否过期**——与
     * {@code validExtractionLease} 不同。照抄，别统一。</p>
     */
    public void finishExtraction(MemoryScope scope, String leaseId) {
        tx.withSubject(scope, subject -> {
            if (!subject.getExtractionState().getLeaseId().equals(leaseId)) {
                throw new MemoryExtractionLeaseLostException();
            }
            subject.getExtractionState().setLeaseId("");
            subject.getExtractionState().setLeaseUntil(GoTimeSerializer.GO_ZERO_DATE_TIME);
            subject.setExtractScheduledAt(null);
            OffsetDateTime now = OffsetDateTime.now();
            saveExtractionState(scope, subject, now);
            subjectMapper.updateLastExtractedAt(scope.tenantId(), scope.subjectId(), now);
            return null;
        });
    }

    /**
     * 对照 {@code ReleaseExtractionSlot}（L220-230）：
     * 租约对不上时**静默返回**（不是错误）——空 leaseID 只该释放一个排队的任务，
     * 绝不该释放一个正在跑的 worker。
     */
    public void releaseExtractionSlot(MemoryScope scope, String leaseId) {
        tx.withSubject(scope, subject -> {
            if (!subject.getExtractionState().getLeaseId().equals(leaseId)) {
                return null;
            }
            subject.getExtractionState().setLeaseId("");
            subject.getExtractionState().setLeaseUntil(GoTimeSerializer.GO_ZERO_DATE_TIME);
            subject.setExtractScheduledAt(null);
            saveExtractionState(scope, subject, OffsetDateTime.now());
            return null;
        });
    }

    // ── 内部工具 ───────────────────────────────────────────────────────────

    /** 对照 Go 的 {@code validExtractionLease}：租约 id 相同**且**还没过期。 */
    private static boolean validExtractionLease(MemorySubject subject, String leaseId) {
        return subject.getExtractionState().getLeaseId().equals(leaseId)
                && subject.getExtractionState().leaseUntilAfter(OffsetDateTime.now());
    }

    /**
     * 对照 Go 的 {@code saveExtractionState}：四列，两个 jsonb 带类型处理器。
     *
     * <p>{@code now} 由调用方给：Go 里 {@code saveExtractionState} 自己取
     * {@code time.Now()}，而调用方在同一事务里也取过一次——两处相差微秒级。
     * Java 侧统一传同一个 {@code now}，让同一事务里的所有时间戳一致（更严格，不更松）。</p>
     */
    private void saveExtractionState(MemoryScope scope, MemorySubject subject, OffsetDateTime now) {
        // ⚠️ null → 空列表：Go 的 MemoryPendingSessions.Value() 对 nil 写 `[]`（**不是** NULL），
        // 而 MyBatis 的 BaseTypeHandler 对 null 参数走的是 setNull。
        // 这一列在 Go 里从不 NULL，所以必须在这里归一。
        List<String> pending = subject.getPendingSessions() == null
                ? new ArrayList<>() : subject.getPendingSessions();
        subjectMapper.saveExtractionState(scope.tenantId(), scope.subjectId(),
                subject.getExtractionState(), pending, subject.getExtractScheduledAt(), now);
    }

    /**
     * 对照 Go 的 {@code importLegacySessions}：把遗留的
     * {@code pending_sessions} 数组一次性导入有索引的进度行，然后把数组清空。
     *
     * <p>清空这一步**由紧随其后的 {@code saveExtractionState} 落库**
     * （Go 也是：函数只改内存里的 subject）。</p>
     */
    private void importLegacySessions(MemoryScope scope, MemorySubject subject) {
        List<String> legacy = subject.getPendingSessions();
        if (legacy != null) {
            for (String id : legacy) {
                enqueueExtractionSession(scope, subject, id, false);
            }
        }
        subject.setPendingSessions(new ArrayList<>());
    }

    /**
     * 对照 Go 的 {@code enqueueExtractionSession}：
     * {@code revision} 恒为 1、{@code pending} 恒为 true，
     * 游标从主体的 {@code extract_cursor} 继承（升级边界，**冻结**不再推进）。
     *
     * <p>{@code bump=true} 时改成 {@code revision = revision + 1, pending = true}。</p>
     */
    private void enqueueExtractionSession(MemoryScope scope, MemorySubject subject, String id, boolean bump) {
        if (id == null || id.isEmpty()) {
            return;
        }
        MemoryExtractionSession row = new MemoryExtractionSession();
        row.setTenantId(subject.getTenantId());
        row.setSubjectId(subject.getSubjectId());
        row.setSessionId(id);
        row.setRevision(1);
        row.setPending(true);
        if (subject.getExtractCursor() != null) {
            row.setCursorAt(subject.getExtractCursor());
        }
        row.setUpdatedAt(OffsetDateTime.now());

        if (postgres) {
            if (bump) {
                extractionMapper.upsertBumpPostgres(row);
            } else {
                extractionMapper.insertIfAbsentPostgres(row);
            }
            return;
        }
        // H2：没有 ON CONFLICT，用"先试 UPDATE 自增、没命中再插"的等价写法。
        if (bump && extractionMapper.bumpExisting(row) > 0) {
            return;
        }
        extractionMapper.insertIfAbsentOther(row);
    }

    /**
     * 对照 GORM 在 CREATE 时对**带字面量 default tag 的零值字段**做的替换
     * （{@code callbacks/create.go} L336-341）：用默认值填入，**并回写结构体**。
     *
     * <p>对 {@code memory_items} 真正生效的只有三个字段——
     * 其余（{@code topic}/{@code normalized_key}/{@code replaces_id}/{@code use_count}）
     * 的默认值就是它们各自的零值，填不填一个样。显式写出来是为了将来改 DDL 时不会静默漂移。</p>
     */
    private static void applyInsertDefaults(MemoryItem item) {
        if (item.getImportance() == 0) {
            item.setImportance(3);
        }
        if (item.getOrigin().isEmpty()) {
            item.setOrigin(MemoryKinds.ORIGIN_EXTRACTED);
        }
        if (item.getStatus().isEmpty()) {
            item.setStatus(MemoryKinds.STATUS_ACTIVE);
        }
    }

    /**
     * 对照 GORM 在 CREATE 时的时间戳规则：{@code created_at} **零值才补 now**，
     * {@code updated_at} **无论传什么都被覆盖成 now**（{@code callbacks/create.go} L336-349）。
     *
     * <p>这是 {@code memory_subjects} / {@code memory_items} / {@code memory_topic_stats} /
     * {@code memory_doc_affinity} / {@code memory_item_embeddings} 五张表的共同规则
     * （{@code created_at} 与 {@code updated_at} 都在）。</p>
     */
    private static void stampForCreate(MemorySubject subject) {
        OffsetDateTime now = OffsetDateTime.now();
        if (GoTimeSerializer.isGoZero(subject.getCreatedAt())) {
            subject.setCreatedAt(now);
        }
        subject.setUpdatedAt(now);
    }

    /** 同 {@link #stampForCreate(MemorySubject)}，条目版。 */
    private static void stampForCreate(MemoryItem item) {
        OffsetDateTime now = OffsetDateTime.now();
        if (GoTimeSerializer.isGoZero(item.getCreatedAt())) {
            item.setCreatedAt(now);
        }
        item.setUpdatedAt(now);
    }

    /** 对照 Go 的 {@code *item = replacement} / {@code *item = *old}（就地改写调用方的对象）。 */
    private static void copyInto(MemoryItem target, MemoryItem source) {
        target.setId(source.getId());
        target.setTenantId(source.getTenantId());
        target.setSubjectId(source.getSubjectId());
        target.setKind(source.getKind());
        target.setContent(source.getContent());
        target.setTopic(source.getTopic());
        target.setNormalizedKey(source.getNormalizedKey());
        target.setImportance(source.getImportance());
        target.setOrigin(source.getOrigin());
        target.setStatus(source.getStatus());
        target.setSourceSessionId(source.getSourceSessionId());
        target.setSourceMessageId(source.getSourceMessageId());
        target.setValidFrom(source.getValidFrom());
        target.setInvalidAt(source.getInvalidAt());
        target.setExpiresAt(source.getExpiresAt());
        target.setReplacesId(source.getReplacesId());
        target.setSupersededBy(source.getSupersededBy());
        target.setLastUsedAt(source.getLastUsedAt());
        target.setUseCount(source.getUseCount());
        target.setInferred(source.isInferred());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
    }

    // ── pgvector 列就绪探测（memory_vector.go L20-55） ──────────────────────

    /**
     * 对照 Go {@code vectorColumnReady}：数据库能不能自己做距离运算。
     *
     * <p>只探一次并缓存。没有装 pgvector 的 PostgreSQL 部署上是 false，
     * 两条路径都仍然可用，只是每个向量都要过一遍网络——这也正是它必须被缓存的原因。</p>
     *
     * <p>判据与 Go 一致：方言必须是 postgres，**且** {@code memory_item_embeddings}
     * 上有 {@code embedding} 列（迁移 000095 是条件执行的，光看方言决定不了）。</p>
     */
    boolean vectorColumnReady() {
        if (!vectorProbed) {
            synchronized (this) {
                if (!vectorProbed) {
                    vectorColumn = postgres && columnExists("memory_item_embeddings", "embedding");
                    vectorProbed = true;
                }
            }
        }
        return vectorColumn;
    }

    /**
     * 对照 Go {@code writeVectorColumn}：让数据库自己的 vector 类型跟上那个 blob。
     *
     * <p>在调用方的事务里尽力而为：**blob 才是源真值**，vector 列落后的行
     * 会被 {@link #syncVectorColumn} 捡回来，不会丢。</p>
     */
    private void writeVectorColumn(String itemId, byte[] raw) {
        if (!vectorColumnReady() || itemId == null || itemId.isEmpty()) {
            return;
        }
        String literal = MemoryVectors.formatEmbeddingLiteral(MemoryVectors.decodeEmbedding(raw));
        if (literal.isEmpty()) {
            return;
        }
        embeddingMapper.writeVectorColumn(itemId, literal);
    }

    /** 对照 {@code rankInDatabase} 的调用包装（只在 {@code vectorColumnReady()} 为真时进来）。 */
    private List<VectorHitRow> rankInDatabase(MemoryScope scope, MemoryVectorQuery query, int limit) {
        String literal = MemoryVectors.formatEmbeddingLiteral(query.vector());
        if (literal.isEmpty()) {
            return List.of();
        }
        return embeddingMapper.rankInDatabase(scope.tenantId(), scope.subjectId(), query.modelId(),
                query.vector().length, query.kinds(), MemoryKinds.STATUS_ACTIVE,
                OffsetDateTime.now(), literal, limit);
    }

    /**
     * 对照 {@code rankInProcess}：可移植的那条路——把这个主体的向量读出来、在这里打分。
     *
     * <p>它受与主体本身相同的容量上限约束，所以**仍然看得到全部**，
     * 只是要付传输的代价。</p>
     */
    private List<VectorHitRow> rankInProcess(MemoryScope scope, MemoryVectorQuery query, int limit) {
        List<MemoryItemEmbedding> stored = embeddingMapper.selectScopedVectors(scope.tenantId(),
                scope.subjectId(), query.modelId(), query.vector().length, query.kinds(),
                MemoryKinds.STATUS_ACTIVE, OffsetDateTime.now(), FALLBACK_VECTOR_SCAN_CAP);

        List<VectorHitRow> rows = new ArrayList<>(stored.size());
        for (MemoryItemEmbedding row : stored) {
            float[] vector = MemoryVectors.decodeEmbedding(row.getVector());
            if (vector == null || vector.length == 0) {
                continue;
            }
            VectorHitRow hit = new VectorHitRow();
            hit.setItemId(row.getItemId());
            hit.setScore(MemoryVectors.cosineSimilarity(query.vector(), vector));
            rows.add(hit);
        }
        sortVectorHitsDesc(rows);
        if (rows.size() > limit) {
            return new ArrayList<>(rows.subList(0, limit));
        }
        return rows;
    }

    /**
     * 对照 {@code sortVectorHitsDesc}：{@code sort.SliceStable} 按 score 降序。
     *
     * <p>Java 的 {@code List.sort} 也是稳定排序，所以相同分数的相对次序与 Go 一致。
     * （NaN 在两边都会让"严格弱序"不成立；实际不可达——{@code cosineSimilarity}
     * 在所有退化输入上都提前回 0，而 NaN 需要输入向量里本身含 NaN。）</p>
     */
    private static void sortVectorHitsDesc(List<VectorHitRow> rows) {
        rows.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
    }

    /** 对照 Go 的 {@code r.db.Dialector.Name() == "postgres"}（同 session/wiki/mcp 的探测写法）。 */
    private static boolean detectPostgres(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * JDBC 元数据探列（对照 GORM 的 {@code Migrator().HasColumn}）。
     *
     * <p>PG 的 {@code getColumns} 要小写表名、H2 要大写——两轮都试一遍，
     * 都比调用方的方言猜测可靠。探测失败记日志并按"没有这一列"处理（保守：
     * 退到内存兜底排名，功能不丢，只是慢）。</p>
     */
    private boolean columnExists(String table, String column) {
        for (String[] pair : new String[][]{
                {table.toLowerCase(Locale.ROOT), column.toLowerCase(Locale.ROOT)},
                {table.toUpperCase(Locale.ROOT), column.toUpperCase(Locale.ROOT)}}) {
            try (Connection c = dataSource.getConnection();
                 ResultSet rs = c.getMetaData().getColumns(null, null, pair[0], pair[1])) {
                if (rs.next()) {
                    return true;
                }
            } catch (SQLException e) {
                log.warn("memory: failed to probe column {}.{}: {}", table, column, e.getMessage());
            }
        }
        return false;
    }
}
