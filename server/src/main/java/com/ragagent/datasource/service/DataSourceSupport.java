package com.ragagent.datasource.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.common.context.TenantContext;
import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceException;
import com.ragagent.datasource.domain.SyncLog;
import com.ragagent.datasource.domain.TaskInitiator;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据源支撑件：配置的校验/解析/深比较、知识库归属校验、身份与"零值时间"等判据、
 * 自动标签解析，以及尽力而为的落库与知识库活动记录。
 *
 * <p>持有 {@link DataSourceService} 回引以访问其依赖与常量；本类不得独立实例化。</p>
 */
final class DataSourceSupport {

    private static final Logger log = LoggerFactory.getLogger(DataSourceSupport.class);

    private final DataSourceService service;

    DataSourceSupport(DataSourceService service) {
        this.service = service;
    }

    /**
     * 对照 Go {@code resolveAutoTagIDs}（L809-818）：找/建本次数据源的自动标签。
     *
     * <p><b>标签失败不致命</b>：同步照常进行、条目只是没有标签。</p>
     */
    List<String> resolveAutoTagIds(DataSource ds) {
        List<String> autoTagIds = new ArrayList<>();
        try {
            String tagId = service.autoTagProvider.findOrCreateTagId(ds.getKnowledgeBaseId(), ds.getName());
            if (tagId != null) {
                autoTagIds.add(tagId);
                log.info("[datasource] using auto-tag \"{}\" (id={}) for data source sync",
                        ds.getName(), tagId);
            }
        } catch (RuntimeException e) {
            log.warn("[datasource] failed to find/create auto-tag \"{}\": {} "
                    + "(proceeding without tag)", ds.getName(), e.getMessage());
        }
        return autoTagIds;
    }

    /**
     * 对照 Go {@code validateDataSourceConfig}（L1221-1233）：解析配置后交给连接器真连一次。
     *
     * <p>{@code ParseConfig} 失败一律折叠成 {@code ErrInvalidConfig}
     * （{@value #DataSourceService.ERR_INVALID_CONFIG}）——把 JSON 解析器的原文漏给用户是没有意义的。</p>
     */
    void validateDataSourceConfig(DataSource ds) {
        Connector connector = service.connectorRegistry.get(ds.getType());
        DataSourceConfig config = parseConfigOrInvalid(ds);
        // ⚠️ 这里**允许** config 为 null 并原样传给连接器——Go 的
        // `config, err := ds.ParseConfig(); if err != nil {...}; return connector.Validate(ctx, config)`
        // 对"空 config"（ParseConfig 回 nil, nil）是把 nil 递下去的，各连接器自己拒绝。
        // 把 null 提前折叠成 InvalidConfig 会改变**哪个**错误被暴露出来。
        connector.validate(config);
    }

    /**
     * 对照 Go 的 {@code config, err := ds.ParseConfig(); if err != nil { return ErrInvalidConfig }}：
     * <b>只有解析抛错</b>才折叠成 {@code invalid configuration}；空 config 回 {@code null}
     * 并继续往下传（调用方自己决定怎么处理 null）。
     */
    static DataSourceConfig parseConfigOrInvalid(DataSource ds) {
        try {
            return ds.parseConfig();
        } catch (RuntimeException e) {
            throw new ConnectorException.InvalidConfig();
        }
    }

    /**
     * 对照 Go {@code CreateDataSource} 里的两步知识库校验（L74-80）：
     * 找不到 → {@code knowledge base not found}；租户不符 → <b>同一个</b>错误
     * （不泄漏"这个 id 确实存在，只是不属于你"）。
     */
    KnowledgeBase requireOwnedKnowledgeBase(String kbId, Long tenantId) {
        KnowledgeBase kb = service.knowledge.findKnowledgeBase(kbId);
        if (kb == null) {
            throw new DataSourceException(DataSourceService.ERR_KNOWLEDGE_BASE_NOT_FOUND);
        }
        if (!Objects.equals(kb.getTenantId(), tenantId)) {
            throw new DataSourceException(DataSourceService.ERR_KNOWLEDGE_BASE_NOT_FOUND);
        }
        return kb;
    }

    /**
     * 对照 Go 的 {@code reflect.DeepEqual(*mergedCfg, *existingParsedCfg)}：
     * 把两个配置折成规范化 JSON 树再比。
     *
     * <p>字段集与 Go 的结构体逐字对应：{@code type} / {@code credentials} /
     * {@code resource_ids} / {@code settings}。<b>不含</b> {@code multimodal_enabled}
     * ——它在本方法被调用时两侧都还是零值（{@code @JsonIgnore}、从不落库、
     * 只在同步抓取前临时填）。</p>
     */
    static boolean configDeepEquals(DataSourceConfig a, DataSourceConfig b) {
        return Objects.equals(configTree(a), configTree(b));
    }

    static com.fasterxml.jackson.databind.JsonNode configTree(DataSourceConfig cfg) {
        ObjectNode node = DataSourceService.MAPPER.createObjectNode();
        node.put("type", cfg.getType());
        node.set("credentials", DataSourceService.MAPPER.valueToTree(cfg.getCredentials()));
        node.set("resource_ids", DataSourceService.MAPPER.valueToTree(cfg.getResourceIds()));
        node.set("settings", DataSourceService.MAPPER.valueToTree(cfg.getSettings()));
        return node;
    }

    /** 对照 Go 的 {@code kb.IsMultimodalEnabled()}：缺失时等价于 false。 */
    static boolean isMultimodalEnabled(KnowledgeBase kb) {
        try {
            java.lang.reflect.Method m = kb.getClass().getMethod("isMultimodalEnabled");
            Object v = m.invoke(kb);
            return v instanceof Boolean b && b;
        } catch (ReflectiveOperationException e) {
            // KB 的 VLM 配置在阶段 3 未落地 → 等价于"没开多模态"（连接器因此不抽图片）
            return false;
        }
    }

    /** 对照 Go {@code TaskInitiatorFromContext}：合成用户（API-Key 主体）刻意留空。 */
    static TaskInitiator taskInitiatorFromContext() {
        String userId = TenantContext.currentUserId();
        if (userId == null || userId.isEmpty() || isSyntheticUserId(userId)) {
            return TaskInitiator.empty();
        }
        String role = TenantContext.currentRole();
        return new TaskInitiator(userId, role == null ? "" : role);
    }

    /** 对照 Go {@code IsSyntheticUserID}：{@code "system-"} + 全数字。 */
    static boolean isSyntheticUserId(String id) {
        String prefix = "system-";
        if (id == null || id.length() <= prefix.length() || !id.startsWith(prefix)) {
            return false;
        }
        for (int i = prefix.length(); i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Go 的 {@code t.IsZero()}（本模块只用来判"连接器有没有给时间"）。 */
    static boolean isGoZeroTime(OffsetDateTime t) {
        return t == null || com.ragagent.common.web.GoTimeSerializer.isGoZero(t);
    }

    static String readMetadataValue(Knowledge k, String key) {
        com.fasterxml.jackson.databind.JsonNode md = k.getMetadata();
        if (md == null || !md.isObject()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode v = md.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    void bestEffortUpdate(DataSource ds) {
        bestEffort(() -> service.dsRepo.update(ds));
    }

    /** 对照 Go 的 {@code _ = s.syncLogRepo.Update(ctx, syncLog)}。 */
    void bestEffortUpdateLog(SyncLog syncLog) {
        bestEffort(() -> service.syncLogRepo.update(syncLog));
    }

    static void bestEffort(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 对照 Go 的 `_ = s.xxxRepo.Update(...)`：写日志失败不改变主流程
        }
    }

    /** 构造一个"按字母序"的 details（Go 的 map → encoding/json 恒排键）。 */
    static Map<String, Object> mapOf(Object... kv) {
        TreeMap<String, Object> m = new TreeMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 一次同步运行在审计详情里的关联字段（对照 Go 的 {@code withKBActivityTask}）。 */
    record ActivityTask(String taskId, String trigger) {
    }

    /**
     * 对照 Go {@code kb_activity.go} 的 {@code recordKBActivity}。
     *
     * <p>本模块只用到它的"汇总事件"形态——同步期间的单条变更一律被
     * {@code withKBActivitySuppressed} 压掉，所以这里没有 suppressed 参数的用武之地，
     * 由调用点自己保证只发汇总。</p>
     *
     * <p>details 的键序：Go 是 {@code map[string]any} → {@code encoding/json} 按字母序输出，
     * 所以这里用 {@link TreeMap} 构造（与 {@code WikiActivityAuditRecorder} 同款处置）。</p>
     */
    void recordKbActivity(long tenantId, String kbId, String action, String targetType,
                          String targetId, String outcome, Map<String, Object> details,
                          ActivityTask task, boolean suppressed) {
        if (suppressed) {
            return;
        }
        if (kbId == null || kbId.isEmpty() || action == null || action.isEmpty()) {
            return;
        }
        long tid = tenantId;
        if (tid == 0) {
            Long ctx = TenantContext.currentTenantId();
            tid = ctx == null ? 0L : ctx;
        }
        if (tid == 0) {
            return;
        }
        String effOutcome = outcome == null || outcome.isEmpty() ? AuditOutcome.SUCCESS : outcome;

        Map<String, Object> activityDetails = new TreeMap<>();
        if (details != null) {
            activityDetails.putAll(details);
        }
        if (task != null) {
            if (task.taskId() != null && !task.taskId().isEmpty()
                    && !activityDetails.containsKey("task_id")) {
                activityDetails.put("task_id", task.taskId());
            }
            if (task.trigger() != null && !task.trigger().isEmpty()
                    && !activityDetails.containsKey("trigger")) {
                activityDetails.put("trigger", task.trigger());
            }
            if (!activityDetails.containsKey("processing_status")) {
                switch (effOutcome) {
                    case AuditOutcome.ACCEPTED -> activityDetails.put("processing_status", "pending");
                    case AuditOutcome.SUCCESS -> activityDetails.put("processing_status", "completed");
                    case AuditOutcome.PARTIAL -> activityDetails.put("processing_status", "partial");
                    case AuditOutcome.FAILED, AuditOutcome.DENIED ->
                            activityDetails.put("processing_status", "failed");
                    case AuditOutcome.CANCELED ->
                            activityDetails.put("processing_status", "canceled");
                    default -> { }
                }
            }
        }

        com.fasterxml.jackson.databind.JsonNode detailsNode =
                activityDetails.isEmpty() ? null : DataSourceService.MAPPER.valueToTree(activityDetails);

        String actorId = nullToEmpty(TenantContext.currentUserId());
        String actorRole = actorId.isEmpty() ? "" : nullToEmpty(TenantContext.currentRole());

        AuditLog entry = new AuditLog();
        entry.setTenantId(tid);
        entry.setActorUserId(actorId);
        entry.setActorRole(actorRole);
        entry.setAction(action);
        entry.setScopeType("knowledge_base");
        entry.setScopeId(kbId);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setOutcome(effOutcome);
        entry.setDetails(detailsNode);
        service.audit.logBestEffort(entry);
    }

    static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
