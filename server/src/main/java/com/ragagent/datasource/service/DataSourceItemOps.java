package com.ragagent.datasource.service;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceException;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.SubtreeChildIds;
import com.ragagent.datasource.domain.SyncItemError;
import com.ragagent.datasource.domain.SyncResult;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.service.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 同步条目的灌入：新增/更新/删除的判定与落地、冲突消解（同节点判等）、
 * 以及父节点失效时的子树清扫。
 *
 * <p>持有 {@link DataSourceService} 回引以访问知识桥与共享常量；本类不得独立实例化。</p>
 */
final class DataSourceItemOps {

    private static final Logger log = LoggerFactory.getLogger(DataSourceItemOps.class);

    private final DataSourceService service;

    DataSourceItemOps(DataSourceService service) {
        this.service = service;
    }

    /**
     * 对照 Go {@code applyFetchedItem}（L860-974）：单条抓取结果的分类与灌入。
     *
     * <p>批量循环与流式处理器共用它，所以"删除 / 空内容 / 灌入结果"三类的判定
     * 在两条抓取路径上必然一致。</p>
     *
     * @param suppressed 对照 Go 的 {@code withKBActivitySuppressed(ctx)}——
     *                   同步期间的单条变更<b>不</b>进审计（一次同步能灌几千条，
     *                   只有本次运行的汇总事件该出现）。本实现里审计本就只在
     *                   汇总点发生，所以参数只用于表达调用意图。
     */
    void applyFetchedItem(DataSource ds, FetchedItem item, List<String> tagIDs,
                          SyncResult result, boolean suppressed) {
        if (item.isDeleted()) {
            if (!ds.isSyncDeletions()) {
                return; // 关闭了删除同步：既不计也不删
            }
            if (item.getExternalId() == null || item.getExternalId().isEmpty()) {
                log.warn("[datasource] skipping deletion for item \"{}\": empty external_id",
                        item.getTitle());
                result.setSkipped(result.getSkipped() + 1);
                return;
            }
            applyDeletion(ds, item, result);
            return;
        }

        if ((item.getContent() == null || item.getContent().length == 0)
                && (item.getUrl() == null || item.getUrl().isEmpty())) {
            Map<String, String> meta = item.getMetadata() == null ? Map.of() : item.getMetadata();
            String errMsg = meta.get("error");
            if (errMsg != null) {
                log.warn("[datasource] item \"{}\" (external_id={}) fetch failed: {}",
                        item.getTitle(), item.getExternalId(), errMsg);
                result.setFailed(result.getFailed() + 1);
                DataSourceSyncResultOps.recordSyncError(result, DataSourceSyncResultOps.fetchFailureSyncError(item, errMsg));
            } else {
                log.info("[datasource] skipping item \"{}\" (external_id={}): no content or URL",
                        item.getTitle(), item.getExternalId());
                result.setSkipped(result.getSkipped() + 1);
            }
            return;
        }

        boolean isUpdate;
        try {
            isUpdate = ingestItem(ds, item, tagIDs);
        } catch (KnowledgeService.DuplicateKnowledgeException dup) {
            // 重复的文件/URL 不算失败——计入 skipped
            log.info("[datasource] item \"{}\" (external_id={}) already exists, skipping",
                    item.getTitle(), item.getExternalId());
            result.setSkipped(result.getSkipped() + 1);
            return;
        } catch (RuntimeException err) {
            String embeddedImage = item.getMetadata() == null
                    ? null : item.getMetadata().get("embedded_image");
            if ("true".equals(embeddedImage)) {
                // 从文档里抽出来做 OCR 的图片是"尽力而为"的增强，不是文档本身：
                // 知识库灌不进去（没配 VLM/对象存储）时跳过即可，别让整次同步失败。
                log.info("[datasource] skipping embedded image \"{}\" (external_id={}), "
                                + "not ingested: {}", item.getTitle(), item.getExternalId(),
                        err.getMessage());
                result.setSkipped(result.getSkipped() + 1);
                return;
            }
            log.warn("[datasource] failed to ingest item \"{}\" (external_id={}): {}",
                    item.getTitle(), item.getExternalId(), err.getMessage());
            result.setFailed(result.getFailed() + 1);
            SyncItemError e = new SyncItemError();
            e.setTitle(item.getTitle());
            e.setCode("ingest_failed");
            e.setMessage("Ingest failed; see server logs");
            DataSourceSyncResultOps.recordSyncError(result, e);
            return;
        }
        if (isUpdate) {
            result.setUpdated(result.getUpdated() + 1);
        } else {
            result.setCreated(result.getCreated() + 1);
        }
    }

    /** 对照 Go {@code applyFetchedItem} 的删除分支（L864-927）。 */
    private void applyDeletion(DataSource ds, FetchedItem item, SyncResult result) {
        Knowledge existing;
        try {
            existing = service.knowledge.findByDataSourceExternalId(
                    ds.getTenantId(), ds.getKnowledgeBaseId(), ds.getId(), item.getExternalId());
        } catch (RuntimeException e) {
            log.error("[datasource] failed to find deleted knowledge for external_id={} "
                            + "(ds={}, kb={}): {}", item.getExternalId(), ds.getId(),
                    ds.getKnowledgeBaseId(), e.getMessage());
            result.setFailed(result.getFailed() + 1);
            result.setDeletionFailed(result.getDeletionFailed() + 1);
            SyncItemError err = new SyncItemError();
            err.setTitle(item.getTitle());
            err.setCode("deletion_lookup_failed");
            err.setMessage("Failed to look up the item before deletion; see server logs");
            DataSourceSyncResultOps.recordSyncError(result, err);
            return;
        }
        if (existing == null) {
            // 删除是幂等的：源端条目可能已经被手工删掉或上一轮同步删过了
            result.setSkipped(result.getSkipped() + 1);
            return;
        }
        try {
            service.knowledge.softDelete(ds.getTenantId(), existing.getId());
        } catch (RuntimeException deleteErr) {
            result.setFailed(result.getFailed() + 1);
            result.setDeletionFailed(result.getDeletionFailed() + 1);
            log.error("[datasource] failed to delete knowledge {} for external_id={} (ds={}): {}",
                    existing.getId(), item.getExternalId(), ds.getId(), deleteErr.getMessage());
            DataSourceSyncResultOps.recordSyncError(result, DataSourceSyncResultOps.deletionFailedError(item));
            return;
        }
        try {
            service.knowledge.hardDelete(ds.getTenantId(), existing.getId());
        } catch (RuntimeException herr) {
            result.setFailed(result.getFailed() + 1);
            result.setDeletionFailed(result.getDeletionFailed() + 1);
            log.error("[datasource] failed to hard-delete knowledge {} for external_id={} (ds={}): {}",
                    existing.getId(), item.getExternalId(), ds.getId(), herr.getMessage());
            DataSourceSyncResultOps.recordSyncError(result, DataSourceSyncResultOps.deletionFailedError(item));
            return;
        }
        result.setDeleted(result.getDeleted() + 1);
    }

    /**
     * 对照 Go {@code ingestItem}（L1243-1370）：把一条 {@link FetchedItem} 写进知识库。
     *
     * <p>有 external_id 时先删后建（update = delete + re-create）；有内容字节走
     * {@code createFromFile}，只有 URL 走 {@code createFromUrl} 并在<b>新建</b>分支上
     * 补 datasource metadata（重复分支复用已有行，不该被重新打标）。</p>
     *
     * @return true = 替换了一条已有条目
     */
    boolean ingestItem(DataSource ds, FetchedItem item, List<String> tagIDs) {
        // channel 决定界面上显示的来源标签：连接器给的 metadata.channel 优先
        // （飞书云盘把它设成 "feishu"，好和 wiki 共用"飞书"标签），否则回落到 ds.Type。
        String channel = ds.getType();
        Map<String, String> itemMeta = item.getMetadata() == null ? Map.of() : item.getMetadata();
        String mc = itemMeta.get("channel");
        if (mc != null && !mc.isEmpty()) {
            channel = mc;
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("external_id", item.getExternalId());
        metadata.put("source_resource_id", item.getSourceResourceId());
        metadata.put("datasource_id", ds.getId());
        // 源系统自己的最后修改时间：knowledge 行的 updated_at 每次重解析都会动，
        // 所以这是"这份文档本身有多旧"的唯一记录。
        if (!DataSourceSupport.isGoZeroTime(item.getUpdatedAt())) {
            metadata.put("source_updated_at", item.getUpdatedAt().toInstant()
                    .atOffset(ZoneOffset.UTC).format(DataSourceService.RFC3339));
        }
        if (!DataSourceSupport.isGoZeroTime(item.getCreatedAt())) {
            metadata.put("source_created_at", item.getCreatedAt().toInstant()
                    .atOffset(ZoneOffset.UTC).format(DataSourceService.RFC3339));
        }
        metadata.putAll(itemMeta);

        boolean isUpdate = false;
        if (item.getExternalId() != null && !item.getExternalId().isEmpty()) {
            Knowledge existing;
            try {
                existing = service.knowledge.findByDataSourceExternalId(
                        ds.getTenantId(), ds.getKnowledgeBaseId(), ds.getId(), item.getExternalId());
            } catch (RuntimeException e) {
                log.warn("[datasource] failed to check existing knowledge for external_id={}: {}",
                        item.getExternalId(), e.getMessage());
                existing = null; // 非致命：继续新建（可能产生重复）
            }
            if (existing != null) {
                log.info("[datasource] found existing knowledge {} for external_id={}, "
                        + "deleting for update", existing.getId(), item.getExternalId());
                try {
                    service.knowledge.softDelete(ds.getTenantId(), existing.getId());
                    try {
                        service.knowledge.hardDelete(ds.getTenantId(), existing.getId());
                    } catch (RuntimeException herr) {
                        log.warn("[datasource] failed to hard-delete replaced knowledge {}: {}",
                                existing.getId(), herr.getMessage());
                    }
                    isUpdate = true;
                } catch (RuntimeException e) {
                    log.warn("[datasource] failed to delete existing knowledge {}: {}",
                            existing.getId(), e.getMessage());
                }
            }
        }

        if (item.getContent() != null && item.getContent().length > 0) {
            try {
                service.knowledge.createFromFile(ds.getTenantId(), ds.getKnowledgeBaseId(),
                        item.getContent(), item.getFileName(), metadata, tagIDs, channel);
            } catch (KnowledgeService.DuplicateKnowledgeException dup) {
                if (dupIsSameNode(dup, item)) {
                    sweepStaleSubtree(ds, item);
                }
                throw dup;
            }
            sweepStaleSubtree(ds, item);
            return isUpdate;
        }

        if (item.getUrl() != null && !item.getUrl().isEmpty()) {
            Knowledge created;
            try {
                created = service.knowledge.createFromUrl(ds.getTenantId(), ds.getKnowledgeBaseId(),
                        item.getUrl(), item.getFileName(), item.getTitle(), tagIDs, channel);
            } catch (KnowledgeService.DuplicateKnowledgeException dup) {
                if (dupIsSameNode(dup, item)) {
                    sweepStaleSubtree(ds, item);
                }
                throw dup;
            }
            // URL 建出来的行没有 metadata，之后的删除就永远找不到它。
            // 只在**新建**分支上补（重复分支复用已有行）。
            if (created != null) {
                service.knowledge.attachMetadata(created, metadata);
            }
            sweepStaleSubtree(ds, item);
            return isUpdate;
        }

        throw new DataSourceException("item has neither content nor URL");
    }

    /**
     * 对照 Go {@code dupIsSameNode}（L1382-1385）：重复内容命中的是不是<b>这个节点自己</b>
     * 的行（external_id 相同）。
     *
     * <p>文件去重只看 file_hash + file_type，所以"某节点重建后的正文恰好与<b>另一条</b>
     * 知识哈希相同"是可能的——那种情况下这条节点的父行刚被删、还没重建，
     * 若照旧清扫子树，就会把子项删掉却没有父行来替换。</p>
     */
    static boolean dupIsSameNode(KnowledgeService.DuplicateKnowledgeException dup, FetchedItem item) {
        if (dup == null || dup.existing() == null) {
            return false;
        }
        String externalId = DataSourceSupport.readMetadataValue(dup.existing(), "external_id");
        return Objects.equals(externalId, item.getExternalId());
    }

    /**
     * 对照 Go {@code sweepStaleSubtree}（L1399-1444）：删掉源端已经消失的子项。
     *
     * <p>只在父项<b>确实存在于知识库之后</b>才跑（刚重建成功、或经重复哈希确认还在），
     * 这样一次真正失败的父写入绝不会毁掉已有的子项。仍在源端的子项由
     * {@code SubtreeKeep} 保住——即便这一轮没能重新灌入（例如附件下载瞬时失败），
     * 它此前同步好的副本也不会丢。</p>
     */
    void sweepStaleSubtree(DataSource ds, FetchedItem item) {
        if (!item.isReplacesSubtree() || item.getExternalId() == null || item.getExternalId().isEmpty()) {
            return;
        }
        List<Knowledge> children;
        try {
            children = service.knowledge.findByMetadataKeyPrefix(ds.getTenantId(), ds.getKnowledgeBaseId(),
                    "external_id", SubtreeChildIds.subtreeChildPrefix(item.getExternalId()));
        } catch (RuntimeException e) {
            log.warn("[datasource] failed to list subtree of external_id={}: {}",
                    item.getExternalId(), e.getMessage());
            return;
        }
        if (children == null || children.isEmpty()) {
            return;
        }
        List<String> subtreeKeep = item.getSubtreeKeep() == null ? List.of() : item.getSubtreeKeep();
        List<String> ids = new ArrayList<>();
        for (Knowledge child : children) {
            // 限定本次数据源：同一个知识库里另一个连接器的同前缀 external_id 不该被清扫
            if (!Objects.equals(DataSourceSupport.readMetadataValue(child, "datasource_id"), ds.getId())) {
                continue;
            }
            if (subtreeKeep.contains(DataSourceSupport.readMetadataValue(child, "external_id"))) {
                continue;
            }
            ids.add(child.getId());
        }
        if (ids.isEmpty()) {
            return;
        }
        // 批量删：一个节点的附件集从 N 缩到 M 时只付一轮删除扇出，而不是 N 轮
        try {
            service.knowledge.softDeleteList(ds.getTenantId(), ids);
        } catch (RuntimeException derr) {
            log.warn("[datasource] failed to delete {} stale sub-item(s) of external_id={}: {}",
                    ids.size(), item.getExternalId(), derr.getMessage());
            return;
        }
        try {
            service.knowledge.hardDeleteList(ds.getTenantId(), ids);
        } catch (RuntimeException herr) {
            log.warn("[datasource] failed to hard-delete {} stale sub-item(s) of external_id={}: {}",
                    ids.size(), item.getExternalId(), herr.getMessage());
        }
    }
}
