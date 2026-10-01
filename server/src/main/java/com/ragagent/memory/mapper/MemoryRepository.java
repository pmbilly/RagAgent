package com.ragagent.memory.mapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.ragagent.memory.domain.MemoryDocAffinity;
import com.ragagent.memory.domain.MemoryExtractionBatch;
import com.ragagent.memory.domain.MemoryExtractionFailure;
import com.ragagent.memory.domain.MemoryExtractionSession;
import com.ragagent.memory.domain.MemoryExtractionState;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryItemEmbedding;
import com.ragagent.memory.domain.MemoryMessageCursor;
import com.ragagent.memory.domain.MemoryPage;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemorySubject;
import com.ragagent.memory.domain.MemoryTombstone;
import com.ragagent.memory.domain.MemoryTopicStat;
import com.ragagent.memory.domain.MemoryVectorHit;
import com.ragagent.memory.domain.MemoryVectorQuery;
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

    final MemorySubjectMapper subjectMapper;
    final MemoryItemMapper itemMapper;
    final MemoryItemEmbeddingMapper embeddingMapper;
    final MemoryTombstoneMapper tombstoneMapper;
    final MemoryTopicStatMapper topicMapper;
    final MemoryDocAffinityMapper affinityMapper;
    final MemoryExtractionSessionMapper extractionMapper;
    final MemoryTxTemplate tx;

    /** 索引侧读写协作者（构造期装配）。 */
    final MemoryIndexStore indexStore;

    /** 条目/墓碑读写协作者（构造期装配）。 */
    final MemoryItemStore itemStore;

    /**
     * 对照 Go 的 {@code r.db.Dialector.Name() == "postgres"}——
     * {@code ON CONFLICT DO NOTHING/DO UPDATE} 在这个方言下可用，H2 上要换成条件插入
     * （与 {@code MessageSuggestionMapper} 同款处置）。
     */
    final boolean postgres;

    /** 元数据探测用的数据源（对照 GORM 的 {@code Migrator().HasColumn}）。 */
    final DataSource dataSource;

    /** 对照 Go {@code vectorOnce sync.Once} + {@code vectorColumn}。 */
    volatile boolean vectorProbed;
    volatile boolean vectorColumn;

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
        this.indexStore = new MemoryIndexStore(this);
        this.itemStore = new MemoryItemStore(this);
        this.dataSource = dataSource;
        this.postgres = MemoryIndexStore.detectPostgres(dataSource);
    }

    // ── 返回值形状（Go 的多返回值） ────────────────────────────────────────

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
        MemoryIndexStore.stampForCreate(subject);

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

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public void createItem(MemoryItem item) {
        itemStore.createItem(item);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public void updateItemContent(MemoryScope scope, String id, String content, String normalizedKey, int importance) {
        itemStore.updateItemContent(scope, id, content, normalizedKey, importance);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public void supersedeItem(MemoryScope scope, String id, String supersededBy) {
        itemStore.supersedeItem(scope, id, supersededBy);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public void deleteItem(MemoryScope scope, String id) {
        itemStore.deleteItem(scope, id);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public long deleteAll(MemoryScope scope) {
        return itemStore.deleteAll(scope);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public void touchUsed(MemoryScope scope, List<String> ids) {
        itemStore.touchUsed(scope, ids);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public long archiveLowestRanked(MemoryScope scope, int keep) {
        return itemStore.archiveLowestRanked(scope, keep);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public long expireOverdue(MemoryScope scope) {
        return itemStore.expireOverdue(scope);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public MemoryItem getItem(MemoryScope scope, String id) {
        return itemStore.getItem(scope, id);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public List<MemoryItem> listActiveByKinds(MemoryScope scope, List<String> kinds, int limit) {
        return itemStore.listActiveByKinds(scope, kinds, limit);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public List<MemoryItem> listActiveResident(MemoryScope scope, int limit) {
        return itemStore.listActiveResident(scope, limit);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public MemoryPage<MemoryItem> listItems(MemoryScope scope, String status, int limit, int offset) {
        return itemStore.listItems(scope, status, limit, offset);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public List<MemoryItem> listLive(MemoryScope scope, String kind, int limit) {
        return itemStore.listLive(scope, kind, limit);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public MemoryItem findActiveByKey(MemoryScope scope, String normalizedKey) {
        return itemStore.findActiveByKey(scope, normalizedKey);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public long countActive(MemoryScope scope) {
        return itemStore.countActive(scope);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public List<MemoryItem> itemsMissingEmbeddings(MemoryScope scope, String modelId, int limit) {
        return itemStore.itemsMissingEmbeddings(scope, modelId, limit);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public void saveItem(MemoryScope scope, MemoryItem item, String replacesId) {
        itemStore.saveItem(scope, item, replacesId);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public void confirmPendingItem(MemoryScope scope, String id) {
        itemStore.confirmPendingItem(scope, id);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public void addTombstone(MemoryScope scope, String topic, String fingerprint, String sourceMessageId) {
        itemStore.addTombstone(scope, topic, fingerprint, sourceMessageId);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public List<MemoryTombstone> listTombstones(MemoryScope scope, int limit) {
        return itemStore.listTombstones(scope, limit);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public boolean hasTombstone(MemoryScope scope, String fingerprint) {
        return itemStore.hasTombstone(scope, fingerprint);
    }

    /** 实现随协作者 {@link MemoryItemStore}。 */
        public boolean hasTombstoneForMessage(MemoryScope scope, String sourceMessageId, Duration within) {
        return itemStore.hasTombstoneForMessage(scope, sourceMessageId, within);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public MemoryTopicStat bumpTopic(MemoryScope scope, String topic, String normalizedKey, String alias) {
        return indexStore.bumpTopic(scope, topic, normalizedKey, alias);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public boolean renameTopic(MemoryScope scope, String oldKey, String newKey, String newLabel) {
        return indexStore.renameTopic(scope, oldKey, newKey, newLabel);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public void markTopicPromoted(MemoryScope scope, String normalizedKey) {
        indexStore.markTopicPromoted(scope, normalizedKey);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public MemoryTopicStat topicByKey(MemoryScope scope, String normalizedKey) {
        return indexStore.topicByKey(scope, normalizedKey);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public MemoryTopicStat topicById(MemoryScope scope, String id) {
        return indexStore.topicById(scope, id);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public List<MemoryTopicStat> topTopics(MemoryScope scope, int limit) {
        return indexStore.topTopics(scope, limit);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public MemoryPage<MemoryTopicStat> listUnpromotedTopics(MemoryScope scope, int limit, int offset) {
        return indexStore.listUnpromotedTopics(scope, limit, offset);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public void deleteTopic(MemoryScope scope, String id) {
        indexStore.deleteTopic(scope, id);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public void deleteAllTopics(MemoryScope scope) {
        indexStore.deleteAllTopics(scope);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public void bumpDocAffinity(MemoryScope scope, List<MemoryDocAffinity> docs) {
        indexStore.bumpDocAffinity(scope, docs);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public Map<String, Integer> docAffinity(MemoryScope scope, List<String> knowledgeIds) {
        return indexStore.docAffinity(scope, knowledgeIds);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public List<MemoryDocAffinity> topDocAffinity(MemoryScope scope, int limit) {
        return indexStore.topDocAffinity(scope, limit);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public MemoryDocAffinity docAffinityById(MemoryScope scope, String id) {
        return indexStore.docAffinityById(scope, id);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public MemoryPage<MemoryDocAffinity> listFamiliarDocs(MemoryScope scope, int minHits, int limit, int offset) {
        return indexStore.listFamiliarDocs(scope, minHits, limit, offset);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public void deleteDocAffinity(MemoryScope scope, String id) {
        indexStore.deleteDocAffinity(scope, id);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public void deleteAllDocAffinity(MemoryScope scope) {
        indexStore.deleteAllDocAffinity(scope);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public void upsertItemEmbedding(MemoryScope scope, MemoryItemEmbedding embedding) {
        indexStore.upsertItemEmbedding(scope, embedding);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public void deleteItemEmbedding(MemoryScope scope, String itemId) {
        indexStore.deleteItemEmbedding(scope, itemId);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public Map<String, float[]> itemEmbeddings(MemoryScope scope, List<String> itemIds, String modelId) {
        return indexStore.itemEmbeddings(scope, itemIds, modelId);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public List<MemoryVectorHit> searchItemsByVector(MemoryScope scope, MemoryVectorQuery query) {
        return indexStore.searchItemsByVector(scope, query);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public int syncVectorColumn(MemoryScope scope, int limit) {
        return indexStore.syncVectorColumn(scope, limit);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public boolean hasPendingExtraction(MemoryScope scope) {
        return indexStore.hasPendingExtraction(scope);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public EnqueueResult enqueuePendingSession(MemoryScope scope, String sessionId, Duration timeout) {
        return indexStore.enqueuePendingSession(scope, sessionId, timeout);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public MemoryExtractionBatch claimPendingSessions(MemoryScope scope, String fallbackSession, String leaseId, Duration ttl) {
        return indexStore.claimPendingSessions(scope, fallbackSession, leaseId, ttl);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public void checkpointExtraction(MemoryScope scope, String leaseId, MemoryExtractionSession session, MemoryMessageCursor cursor, boolean drained) {
        indexStore.checkpointExtraction(scope, leaseId, session, cursor, drained);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public boolean recordExtractionFailure(MemoryScope scope, String leaseId, MemoryExtractionFailure failure) {
        return indexStore.recordExtractionFailure(scope, leaseId, failure);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public void finishExtraction(MemoryScope scope, String leaseId) {
        indexStore.finishExtraction(scope, leaseId);
    }

    /** 实现随协作者 {@link MemoryIndexStore}。 */
        public void releaseExtractionSlot(MemoryScope scope, String leaseId) {
        indexStore.releaseExtractionSlot(scope, leaseId);
    }

}
