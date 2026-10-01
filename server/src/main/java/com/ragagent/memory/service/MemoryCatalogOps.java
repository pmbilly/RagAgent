package com.ragagent.memory.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ragagent.common.settings.MemoryConfig;
import com.ragagent.common.settings.MemoryKeys;
import com.ragagent.common.settings.MemoryKinds;
import com.ragagent.memory.domain.MemoryDocAffinity;
import com.ragagent.memory.domain.MemoryDocView;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryPage;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemorySettings;
import com.ragagent.memory.domain.MemorySubject;
import com.ragagent.memory.domain.MemoryText;
import com.ragagent.memory.domain.MemoryTopicStat;
import com.ragagent.memory.domain.MemoryTopicView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆目录的管理面：条目 CRUD、主题的晋升/改名/别名、文档视图、清空与墓碑、设置开关、
 * 确认/拒绝，以及容量重建的触发。
 *
 * <p>持有 {@link MemoryService} 回引以访问仓储与共享工具；本类不得独立实例化。</p>
 */
final class MemoryCatalogOps {

    private static final Logger log = LoggerFactory.getLogger(MemoryCatalogOps.class);

    private final MemoryService service;

    MemoryCatalogOps(MemoryService service) {
        this.service = service;
    }

    /** 对照 Go {@code ListItems}：记忆管理器的条目列表。 */
    public MemoryPage<MemoryItem> listItems(String status, int limit, int offset) {
        MemoryScope scope = MemoryScopes.resolve();
        return service.repo.listItems(scope, status, limit, offset);
    }

    /** 对照 Go {@code ListTopics}：已计数但还没被提升的主体的视图。 */
    public MemoryPage<MemoryTopicView> listTopics(int limit, int offset) {
        MemoryScope scope = MemoryScopes.resolve();
        MemoryPage<MemoryTopicStat> page = service.repo.listUnpromotedTopics(scope, limit, offset);
        int threshold = service.workspaceConfig(scope.tenantId()).effectiveInterestThreshold();
        List<MemoryTopicView> views = new ArrayList<>(page.items().size());
        for (MemoryTopicStat stat : page.items()) {
            MemoryTopicView view = MemoryTopicView.fromStat(stat, threshold);
            if (view != null) {
                views.add(view);
            }
        }
        return new MemoryPage<>(views, page.total());
    }

    /**
     * 对照 Go {@code unpromotedTopic}：取一条还没被提升的主题，否则
     * {@link MemoryScopeExceptions.ItemNotFound}。
     *
     * <p>"已经提升过"与"不存在"刻意回同一个错误——与 {@code ErrItemNotFound} 的
     * 那条注释同一个理由。</p>
     */
    MemoryTopicStat unpromotedTopic(MemoryScope scope, String id) {
        MemoryTopicStat stat = service.repo.topicById(scope, id);
        if (stat == null || stat.getPromotedAt() != null) {
            throw new MemoryScopeExceptions.ItemNotFound();
        }
        return stat;
    }

    /** 对照 Go {@code PromoteTopic}：把一个被计数的主体立刻变成兴趣，不再等剩余命中数。 */
    public MemoryItem promoteTopic(String id) {
        MemoryService.ScopeState state = service.enabledScope();
        if (!state.ok()) {
            throw new MemoryScopeExceptions.Disabled();
        }
        MemoryTopicStat stat = unpromotedTopic(state.scope(), id);
        MemoryItem item = new MemoryItem();
        item.setKind(MemoryKinds.KIND_INTEREST);
        item.setTopic(stat.getTopic());
        item.setContent(stat.getTopic());
        item.setImportance(3);
        item.setOrigin(MemoryKinds.ORIGIN_MANUAL);
        MemoryItem created = service.write(state.scope(), state.cfg(), item);
        try {
            service.repo.markTopicPromoted(state.scope(), stat.getNormalizedKey());
        } catch (RuntimeException e) {
            log.warn("memory: mark topic promoted failed: {}", e.toString());
        }
        return created;
    }

    /**
     * 对照 Go {@code DeleteTopic}：停止跟踪一个主体，并记住这次拒绝，
     * 免得自动提升又把这个标签带回来。
     */
    public void deleteTopic(String id) {
        MemoryScope scope = MemoryScopes.resolve();
        MemoryTopicStat stat = unpromotedTopic(scope, id);
        tombstoneTopic(scope, stat);
        service.repo.deleteTopic(scope, id);
    }

    /** 对照 Go {@code ListDocuments}：当作习惯被引用过足够多次的文档。 */
    public MemoryPage<MemoryDocView> listDocuments(int limit, int offset) {
        MemoryScope scope = MemoryScopes.resolve();
        MemoryPage<MemoryDocAffinity> page = service.repo.listFamiliarDocs(
                scope, MemoryConfig.MEMORY_DOC_AFFINITY_MIN_HITS, limit, offset);
        List<MemoryDocView> views = new ArrayList<>(page.items().size());
        for (MemoryDocAffinity row : page.items()) {
            MemoryDocView view = MemoryDocView.fromAffinity(row);
            if (view != null) {
                views.add(view);
            }
        }
        return new MemoryPage<>(views, page.total());
    }

    /** 对照 Go {@code DeleteDocument}：不再把某个文档当个人检索信号。 */
    public void deleteDocument(String id) {
        MemoryScope scope = MemoryScopes.resolve();
        if (service.repo.docAffinityById(scope, id) == null) {
            throw new MemoryScopeExceptions.ItemNotFound();
        }
        service.repo.deleteDocAffinity(scope, id);
    }

    /**
     * 对照 Go {@code FamiliarKnowledgeIDs}：这个人反复引用的文档 id。
     *
     * <p>任何失败都回空，好让调用方无条件使用它。</p>
     */
    public List<String> familiarKnowledgeIds() {
        MemoryScope scope;
        try {
            scope = MemoryScopes.resolve();
        } catch (MemoryScopeExceptions.NoScope e) {
            return null;
        }
        List<MemoryDocAffinity> rows;
        try {
            rows = service.repo.topDocAffinity(scope, 200);
        } catch (RuntimeException e) {
            log.warn("memory: load familiar documents failed: {}", e.toString());
            return null;
        }
        if (rows == null) {
            return null;
        }
        List<String> ids = new ArrayList<>(rows.size());
        for (MemoryDocAffinity row : rows) {
            if (row == null || row.getKnowledgeId().isEmpty()
                    || row.getHits() < MemoryConfig.MEMORY_DOC_AFFINITY_MIN_HITS) {
                continue;
            }
            ids.add(row.getKnowledgeId());
        }
        return ids;
    }

    /**
     * 对照 Go {@code service.topicWasForgotten}：这个主题（或它的任一个别名）被刻意忘掉过吗。
     *
     * <p>查墓碑失败时**继续**（Go 的 {@code continue}），而不是当成"没忘过"就返回——
     * 它只是跳过那一个标签。</p>
     */
    boolean topicWasForgotten(MemoryScope scope, String... labels) {
        Set<String> seen = new LinkedHashSet<>();
        for (String label : labels) {
            String fingerprint = MemoryText.fingerprint(MemoryText.sanitizeMemoryContent(label));
            if (fingerprint.isEmpty()) {
                continue;
            }
            if (!seen.add(fingerprint)) {
                continue;
            }
            boolean forgotten;
            try {
                forgotten = service.repo.hasTombstone(scope, fingerprint);
            } catch (RuntimeException e) {
                log.warn("memory: check forgotten topic failed: {}", e.toString());
                continue;
            }
            if (forgotten) {
                return true;
            }
        }
        return false;
    }

    /** 对照 Go {@code tombstoneTopic}：把一条主题连同它的全部别名记成"被拒绝"。 */
    void tombstoneTopic(MemoryScope scope, MemoryTopicStat stat) {
        if (stat == null) {
            return;
        }
        List<String> labels = new ArrayList<>();
        if (!stat.getTopic().isEmpty()) {
            labels.add(stat.getTopic());
        }
        if (stat.getAliases() != null) {
            labels.addAll(stat.getAliases());
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String label : labels) {
            String content = MemoryText.sanitizeMemoryContent(label);
            String fingerprint = MemoryText.fingerprint(content);
            if (fingerprint.isEmpty()) {
                continue;
            }
            if (!seen.add(fingerprint)) {
                continue;
            }
            try {
                service.repo.addTombstone(scope, stat.getTopic(), fingerprint, "");
            } catch (RuntimeException e) {
                log.warn("memory: record topic tombstone failed: {}", e.toString());
            }
        }
    }

    /**
     * 对照 Go {@code CreateItem}：加一条用户自己敲进来的记忆。
     *
     * <p>它走与其它一切相同的写路径，所以一条手写的记忆可以**取代**同主题上
     * 抽取出来的一条，而不是并排躺着。</p>
     */
    public MemoryItem createItem(String kind, String content, int importance) {
        MemoryService.ScopeState state = service.enabledScope();
        if (!state.ok()) {
            throw new MemoryScopeExceptions.Disabled();
        }
        if (!MemoryKinds.isValid(kind)) {
            kind = MemoryKinds.KIND_FACT;
        }
        if (importance <= 0) {
            importance = 3;
        }
        MemoryItem item = new MemoryItem();
        item.setKind(kind);
        item.setContent(content);
        item.setImportance(importance);
        item.setOrigin(MemoryKinds.ORIGIN_MANUAL);
        return service.write(state.scope(), state.cfg(), item);
    }

    /**
     * 对照 Go {@code UpdateItem}：从记忆管理器里编辑一条。
     * 被编辑过的条目会变成 manual，这样之后的抽取不会悄悄撤销用户的更正。
     */
    public MemoryItem updateItem(String id, String content, int importance) {
        MemoryScope scope = MemoryScopes.resolve();
        MemoryItem existing = service.repo.getItem(scope, id);
        if (existing == null) {
            throw new MemoryScopeExceptions.ItemNotFound();
        }
        String sanitized = MemoryText.sanitizeMemoryContent(content);
        if (sanitized.isEmpty()) {
            throw new MemoryScopeExceptions.EmptyContent();
        }
        MemoryText.Redaction redaction = MemoryText.redactSensitive(sanitized);
        if (redaction.changed()) {
            if (MemoryText.isMostlyRedacted(redaction.content())) {
                throw new MemoryScopeExceptions.SensitiveContent();
            }
            sanitized = MemoryText.sanitizeMemoryContent(redaction.content());
        }
        // 保留原来的主题：用户在更正陈述，不是把它重新归到另一个主题下，
        // 而复用主题正是让这条更正能够取代未来某次抽取的原因。
        String normalizedKey = MemoryKeys.itemKey(existing.getTopic(), sanitized);
        int clamped = MemoryText.clampImportance(importance);
        service.repo.updateItemContent(scope, id, sanitized, normalizedKey, clamped);
    service.rebuildBlock(scope);
        MemoryItem updated = service.repo.getItem(scope, id);
        service.vectorService.storeItemEmbedding(scope, service.workspaceConfig(scope.tenantId()), updated);
        return updated;
    }

    /**
     * 对照 Go {@code DeleteItem}：永久忘掉一条记忆。
     */
    public void deleteItem(String id) {
        MemoryScope scope = MemoryScopes.resolve();
        MemoryItem existing = service.repo.getItem(scope, id);
        if (existing == null) {
            throw new MemoryScopeExceptions.ItemNotFound();
        }
        // 在删行之前先记下这次拒绝。删掉一条蒸馏马上要从同一条消息重新推导出来的记忆，
        // 正是用户"同一个东西删两次然后不再信任这个功能"的来路。
        try {
            service.repo.addTombstone(scope, existing.getTopic(),
                    MemoryText.fingerprint(existing.getContent()), existing.getSourceMessageId());
        } catch (RuntimeException e) {
            log.warn("memory: record tombstone failed: {}", e.toString());
        }
        service.repo.deleteItem(scope, id);
    service.rebuildBlock(scope);
    }

    /** 对照 Go {@code Clear}：忘掉调用者记忆空间里的一切。 */
    public long clear() {
        MemoryScope scope = MemoryScopes.resolve();
        // 清空是对当前存着的一切的拒绝，所以它留下的墓碑与逐条删除一样。
        tombstoneEverything(scope);
        long removed = service.repo.deleteAll(scope);
        service.repo.deleteAllTopics(scope);
        service.repo.deleteAllDocAffinity(scope);
    service.rebuildBlock(scope);
        return removed;
    }

    /**
     * 对照 Go {@code tombstoneEverything}：为清空删掉的每一条记忆记一次拒绝。
     *
     * <p>一个主体最多保留 {@code MaxMemoryTombstones} 条拒绝，而存储能持有的行远多于此：
     * {@code max_items} 只管活跃记忆，被取代与被归档的行可以无上限堆积。所以读一页平铺的
     * 列表会把整个预算花在"恰好最新的那些"上，一条活着的记忆可能一条墓碑都没有，
     * 于是又可以自由地被重新推导出来。</p>
     *
     * <p>按状态逐个走，是把预算花在**能改变行为**的地方：用户还在被服务的，
     * 然后是在等他决定的，最后是其余。总数封顶，好让这次调用不会把自己更早、
     * 更重要的那些行挤掉。</p>
     */
    void tombstoneEverything(MemoryScope scope) {
        int budget = MemoryKinds.MAX_TOMBSTONES;
        for (String status : List.of(MemoryKinds.STATUS_ACTIVE, MemoryKinds.STATUS_PENDING,
                MemoryKinds.STATUS_ARCHIVED, MemoryKinds.STATUS_SUPERSEDED)) {
            if (budget <= 0) {
                return;
            }
            MemoryPage<MemoryItem> page;
            try {
                page = service.repo.listItems(scope, status, budget, 0);
            } catch (RuntimeException e) {
                log.warn("memory: list {} items during clear failed: {}", status, e.toString());
                continue;
            }
            for (MemoryItem item : page.items()) {
                if (item == null) {
                    continue;
                }
                try {
                    service.repo.addTombstone(scope, item.getTopic(), MemoryText.fingerprint(item.getContent()),
                            item.getSourceMessageId());
                } catch (RuntimeException e) {
                    log.warn("memory: record tombstone during clear failed: {}", e.toString());
                }
                budget--;
            }
        }
    }

    /** 对照 Go {@code GetSettings}：设置界面渲染的那个合并视图。 */
    public MemorySettings getSettings() {
        MemoryScope scope = MemoryScopes.resolve();
        MemoryConfig cfg = service.workspaceConfig(scope.tenantId());
        MemorySettings settings = new MemorySettings();
        settings.setWorkspaceEnabled(cfg.memoryEnabled());
        settings.setUserEnabled(true);
        settings.setWriteMode(cfg.getWriteMode());
        settings.setMaxItems(cfg.effectiveMaxItems());
        if (settings.getWriteMode().isEmpty()) {
            settings.setWriteMode(MemoryConfig.WRITE_MODE_EXPLICIT_ONLY);
        }
        MemorySubject subject = service.repo.getSubject(scope);
        if (subject != null) {
            settings.setUserEnabled(subject.isEnabled());
            settings.setItemCount(subject.getItemCount());
        }
        try {
            settings.setItemCount((int) service.repo.countActive(scope));
        } catch (RuntimeException e) {
            // Go 只在 err == nil 时覆盖；失败时保留主体上的那一份。
        }
        settings.setEffective(settings.isWorkspaceEnabled() && settings.isUserEnabled());
        return settings;
    }

    /** 对照 Go {@code SetEnabled}：翻转调用者自己的退出开关。 */
    public void setEnabled(boolean enabled) {
        MemoryScope scope = MemoryScopes.resolve();
        service.repo.updateSubjectEnabled(scope, enabled);
    }

    /** 对照 Go {@code ConfirmItem}：接受系统推断出来的东西，让它开始被使用。 */
    public MemoryItem confirmItem(String id) {
        MemoryScope scope = MemoryScopes.resolve();
        MemoryItem existing = service.repo.getItem(scope, id);
        if (existing == null) {
            throw new MemoryScopeExceptions.ItemNotFound();
        }
        service.repo.confirmPendingItem(scope, id);
    service.enforceCapacity(scope, service.workspaceConfig(scope.tenantId()));
    service.rebuildBlock(scope);
        return service.repo.getItem(scope, id);
    }

    /**
     * 对照 Go {@code RejectItem}：拒绝一条推断。
     *
     * <p>它删掉而不是归档，这样墓碑就能阻止同一个猜测下周再被提出来。</p>
     */
    public void rejectItem(String id) {
        deleteItem(id);
    }

    /**
     * 对照 Go {@code service.renameTopic}：给一个主体采纳更好的标签，并让一切指向它的东西跟上。
     *
     * <p>一次合并留下的标签否则就只是"先到的那个措辞"，而那个标签不是装饰性的：
     * 它被当作这个人的词汇喂给查询改写器，也展示给他看我们以为他在乎什么。</p>
     *
     * @return {@code [label, key]}——继续用下去的那两个值
     */
    String[] renameTopic(MemoryScope scope, MemoryTopicStat stat, String newLabel, String currentKey) {
        String newKey = MemoryKeys.normalizeTopicKey(newLabel);
        boolean renamed;
        try {
            renamed = service.repo.renameTopic(scope, currentKey, newKey, newLabel);
        } catch (RuntimeException e) {
            log.warn("memory: rename topic {} failed: {}", stat.getTopic(), e.toString());
            return new String[]{stat.getTopic(), currentKey};
        }
        if (!renamed) {
            return new String[]{stat.getTopic(), currentKey};
        }
        log.info("memory: renamed topic {} to {}", stat.getTopic(), newLabel);
        renameInterestItem(scope, stat.getTopic(), newLabel);
        return new String[]{newLabel, newKey};
    }

    /**
     * 对照 Go {@code renameInterestItem}：让提升出来的兴趣与它的主体保持同步。
     *
     * <p>它只碰"仍然一字不差地读作旧标签"的条目。别的都被用户编辑过，
     * 悄悄覆盖别人自己的措辞比让两者稍微不同步更糟。</p>
     */
    void renameInterestItem(MemoryScope scope, String oldLabel, String newLabel) {
        List<MemoryItem> items;
        try {
            items = service.repo.listActiveByKinds(scope, List.of(MemoryKinds.KIND_INTEREST), 100);
        } catch (RuntimeException e) {
            log.warn("memory: load interests for rename failed: {}", e.toString());
            return;
        }
        if (items == null) {
            return;
        }
        for (MemoryItem item : items) {
            if (item == null || !item.getContent().equals(oldLabel)) {
                continue;
            }
            try {
                service.repo.updateItemContent(scope, item.getId(), newLabel,
                        MemoryKeys.itemKey(newLabel, newLabel), item.getImportance());
            } catch (RuntimeException e) {
                log.warn("memory: rename interest item failed: {}", e.toString());
                continue;
            }
            // 向量里还拼着旧标签，所以语义召回会继续匹配一个这个主体已经不再用的名字。
            try {
                service.repo.deleteItemEmbedding(scope, item.getId());
            } catch (RuntimeException e) {
                log.warn("memory: drop renamed interest embedding failed: {}", e.toString());
            }
    service.rebuildBlock(scope);
            return;
        }
    }

    /** 对照 Go {@code service.topicAliasCount}：一个主体已经以多少种措辞被认识。 */
    int topicAliasCount(MemoryScope scope, String key) {
        MemoryTopicStat stat;
        try {
            stat = service.repo.topicByKey(scope, key);
        } catch (RuntimeException e) {
            return 0;
        }
        if (stat == null || stat.getAliases() == null) {
            return 0;
        }
        return stat.getAliases().size();
    }

    /**
     * 对照 Go {@code service.invalidateInterestEmbedding}：丢掉从这个主体提升出来的那条兴趣的向量。
     * 尽力而为：在一个维护周期里没有向量只损失一条记忆的语义召回，而这条记忆全程都还能
     * 靠措辞被找到。
     */
    void invalidateInterestEmbedding(MemoryScope scope, String topic) {
        List<MemoryItem> items;
        try {
            items = service.repo.listActiveByKinds(scope, List.of(MemoryKinds.KIND_INTEREST), 100);
        } catch (RuntimeException e) {
            log.warn("memory: load interests for re-embedding failed: {}", e.toString());
            return;
        }
        if (items == null) {
            return;
        }
        for (MemoryItem item : items) {
            if (item == null || !item.getContent().equals(topic)) {
                continue;
            }
            try {
                service.repo.deleteItemEmbedding(scope, item.getId());
            } catch (RuntimeException e) {
                log.warn("memory: drop interest embedding failed: {}", e.toString());
            }
            return;
        }
    }
}
